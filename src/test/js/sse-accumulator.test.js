/*
 * Tests for the SSE frame decoder.
 *
 * The decisive property here is CHUNK-BOUNDARY INDEPENDENCE: the network splits the stream at
 * arbitrary byte offsets, so the same bytes must decode identically whether they arrive as one
 * chunk, as per-line chunks, or one character at a time. A decoder that only works on tidy frame
 * boundaries passes a naive test and drops text in production.
 */

import { test, describe } from 'node:test';
import assert from 'node:assert/strict';

import { createSseAccumulator, errorMarkup }
    from '../../main/resources/static/js/chat/sse-accumulator.js';

/** Feeds a whole stream in the given chunk sizes; returns the final text and all events. */
function run(frames, splitEvery) {
    const acc = createSseAccumulator();
    const events = [];
    const whole = frames.join('');
    const chunks = splitEvery
        ? whole.match(new RegExp(`[\\s\\S]{1,${splitEvery}}`, 'g')) || []
        : frames;
    for (const chunk of chunks) {
        const r = acc.feed(chunk);
        events.push(...r.events);
        if (acc.finished) break;
    }
    return { text: acc.text, events, acc };
}

const DELTA = (s) => `data:${s}\n\n`;

describe('content accumulation', () => {
    test('joins consecutive events in order', () => {
        const { text } = run([DELTA('Hello '), DELTA('world'), DELTA('!')]);
        assert.equal(text, 'Hello world!');
    });

    test('multi-line events join with a newline', () => {
        // Consecutive data: lines belong to ONE event and are joined with \n per SSE.
        const { text } = run(['data:line one\ndata:line two\n\n']);
        assert.equal(text, 'line one\nline two');
    });

    test('an uncommitted event is still visible in text', () => {
        // The user must see the delta as it arrives, before its terminating blank line.
        const acc = createSseAccumulator();
        const r = acc.feed('data:partial');
        assert.equal(r.text, '');
        acc.feed('\n');
        assert.equal(acc.text, 'partial');
    });

    test('carriage returns are stripped', () => {
        const { text } = run(['data:hi\r\n\r\n']);
        assert.equal(text, 'hi');
    });

    test('non-data lines are ignored', () => {
        const { text } = run([':heartbeat\n', 'event:ping\n', DELTA('real')]);
        assert.equal(text, 'real');
    });
});

describe('chunk-boundary independence', () => {
    const frames = [DELTA('The quick '), DELTA('brown fox'), 'data:[DONE] {"messageId":"m1"}\n\n'];

    test('same result whether fed whole, per-frame, or per-character', () => {
        const byFrame = run(frames);
        const oneShot = run([frames.join('')]);
        const byChar = run(frames, 1);
        const byThree = run(frames, 3);

        assert.equal(byFrame.text, 'The quick brown fox');
        assert.equal(oneShot.text, byFrame.text, 'one-shot differs');
        assert.equal(byChar.text, byFrame.text, 'per-character differs');
        assert.equal(byThree.text, byFrame.text, '3-byte chunks differ');
    });

    test('the done payload survives being split mid-JSON', () => {
        const { events } = run(frames, 2);
        const done = events.find(e => e.type === 'done');
        assert.ok(done, 'no done event');
        assert.equal(done.payload.messageId, 'm1');
    });

    test('a split inside the [DONE] marker itself still terminates', () => {
        const acc = createSseAccumulator();
        acc.feed('data:hi\n\ndata:[DO');
        assert.equal(acc.finished, false, 'must not finish on a partial marker');
        const r = acc.feed('NE] {}\n\n');
        assert.equal(acc.finished, true);
        assert.equal(r.text, 'hi');
    });
});

describe('[DONE] handling', () => {
    test('parses the usage payload', () => {
        const { events } = run([DELTA('answer'),
            'data:[DONE] {"messageId":"m1","promptTokens":10,"totalTokens":15}\n\n']);
        const done = events.find(e => e.type === 'done');
        assert.equal(done.payload.messageId, 'm1');
        assert.equal(done.payload.promptTokens, 10);
    });

    test('a bare [DONE] is valid and reports no usage', () => {
        const { events, text } = run([DELTA('answer'), 'data:[DONE]\n\n']);
        assert.equal(text, 'answer');
        assert.deepEqual(events.find(e => e.type === 'done').payload, {});
    });

    test('malformed JSON does not lose the answer', () => {
        const { events, text } = run([DELTA('answer'), 'data:[DONE] {not json\n\n']);
        assert.equal(text, 'answer', 'the answer must survive a bad payload');
        assert.deepEqual(events.find(e => e.type === 'done').payload, {});
    });

    test('a pending delta is committed before finishing', () => {
        const { text } = run(['data:tail', '\ndata:[DONE] {}\n\n']);
        assert.equal(text, 'tail');
    });

    test('nothing after [DONE] is processed', () => {
        const acc = createSseAccumulator();
        acc.feed('data:a\n\ndata:[DONE] {}\n\ndata:LEAKED\n\n');
        assert.equal(acc.text, 'a');
        acc.feed('data:MORE\n\n');
        assert.equal(acc.text, 'a', 'accepted input after the stream ended');
    });

    test('exactly one done event is emitted', () => {
        const { events } = run([DELTA('a'), 'data:[DONE] {}\n\n']);
        assert.equal(events.filter(e => e.type === 'done').length, 1);
    });
});

describe('[ERROR] handling', () => {
    test('renders inline and lets the stream continue', () => {
        const { text, events } = run([DELTA('before '), 'data:[ERROR] model timeout\n\n',
            DELTA('after')]);
        assert.ok(text.includes('before '), text);
        assert.ok(text.includes('after'), 'stream must continue past an error');
        assert.ok(text.includes('⚠️ model timeout'));
        assert.equal(events.filter(e => e.type === 'error').length, 1);
    });

    test('the error message is escaped', () => {
        // The message is server/LLM-derived and goes straight into innerHTML.
        const { text } = run(['data:[ERROR] <img src=x onerror=alert(1)>\n\n']);
        assert.ok(!text.includes('<img'), text);
        assert.ok(text.includes('&lt;img'));
    });

    test('a pending delta is committed before the error is appended', () => {
        const { text } = run(['data:partial', '\ndata:[ERROR] boom\n\n']);
        assert.ok(text.startsWith('partial'), text);
    });

    test('errorMarkup carries the class the stylesheet targets', () => {
        assert.ok(errorMarkup('x').includes('class="error-text"'));
    });
});

describe('stream closing without [DONE]', () => {
    test('end() commits a partial event', () => {
        const acc = createSseAccumulator();
        acc.feed('data:half-written');
        const r = acc.end();
        assert.equal(r.text, 'half-written');
        assert.equal(r.events[0].type, 'done');
    });

    test('end() after a proper [DONE] does not double-finish', () => {
        const acc = createSseAccumulator();
        acc.feed('data:a\n\ndata:[DONE] {}\n\n');
        assert.equal(acc.end().text, 'a');
    });

    test('an empty stream yields empty text', () => {
        assert.equal(createSseAccumulator().end().text, '');
    });
});

describe('event reporting', () => {
    test('a content chunk reports a delta so the caller repaints once', () => {
        // One paint per chunk, not per line: the old loop re-ran the whole markdown + sanitize +
        // citation pipeline over the entire answer for every line of every frame.
        const acc = createSseAccumulator();
        const r = acc.feed(DELTA('a') + DELTA('b') + DELTA('c'));
        assert.equal(r.events.filter(e => e.type === 'delta').length, 1,
            'a chunk with three events must still request a single repaint');
        assert.equal(r.text, 'abc');
    });

    test('the terminal chunk reports done rather than delta', () => {
        const acc = createSseAccumulator();
        const r = acc.feed('data:x\n\ndata:[DONE] {}\n\n');
        assert.ok(r.events.some(e => e.type === 'done'));
        assert.ok(!r.events.some(e => e.type === 'delta'));
    });
});
