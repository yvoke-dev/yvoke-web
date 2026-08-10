/*
 * Tests for the pure text/markup helpers extracted from thread.js.
 * Dependency-free: `node --test`, or via ./mvnw test.
 */

import { test, describe } from 'node:test';
import assert from 'node:assert/strict';

import {
    assistantPlaceholderMarkup,
    cancelledNoticeMarkup,
    chatInputDefaultPlaceholder,
    describeSendFailure,
    escapeHtml,
    isMermaidText,
    normalizeThinkTags,
    pollTerminalDecision,
    promptLabel,
    syncLoaderMarkup,
} from '../../main/resources/static/js/chat/thread-text.js';

describe('escapeHtml', () => {
    test('escapes all five characters that can break out of markup', () => {
        assert.equal(escapeHtml(`&<>"'`), '&amp;&lt;&gt;&quot;&#039;');
    });

    test('escapes the ampersand first, so entities are not double-broken', () => {
        // Escaping < before & would yield "&amp;lt;" for a literal "<".
        assert.equal(escapeHtml('<'), '&lt;');
        assert.equal(escapeHtml('&lt;'), '&amp;lt;');
    });

    test('neutralises a script payload', () => {
        assert.ok(!escapeHtml('<script>alert(1)</script>').includes('<script>'));
    });

    test('neutralises an attribute breakout', () => {
        const out = escapeHtml('" onerror="alert(1)');
        assert.ok(!out.includes('"'), out);
    });

    test('coerces non-strings instead of throwing', () => {
        assert.equal(escapeHtml(42), '42');
        assert.equal(escapeHtml(null), 'null');
    });
});

describe('isMermaidText', () => {
    test('recognises every diagram type the prompt tells the model it may emit', () => {
        // These mirror the "Supported diagram types" list in the default-chat system prompt; a
        // type the prompt allows but this misses renders as a plain code block.
        for (const src of ['graph TD\n A-->B', 'flowchart LR', 'sequenceDiagram\n A->>B: hi',
            'gantt', 'classDiagram', 'stateDiagram-v2', 'erDiagram', 'journey', 'pie title X',
            'mindmap', 'timeline', 'gitGraph', 'quadrantChart', 'xyChart-beta', 'C4Context']) {
            assert.ok(isMermaidText(src), `should detect: ${src.split('\n')[0]}`);
        }
    });

    test('tolerates leading whitespace', () => {
        assert.ok(isMermaidText('\n\n   graph TD\n  A-->B'));
    });

    test('does not claim ordinary code', () => {
        for (const src of ['const graph = 1;', 'SELECT * FROM pie;', 'def journey():',
            'graphql query {}', '# gantt chart notes']) {
            assert.ok(!isMermaidText(src), `should not detect: ${src}`);
        }
    });
});

describe('normalizeThinkTags', () => {
    test('leaves balanced tags alone', () => {
        assert.equal(normalizeThinkTags('a<think>b</think>c', false), 'a<think>b</think>c');
    });

    test('closes an unclosed block when the answer is finished', () => {
        assert.equal(normalizeThinkTags('a<think>b', false), 'a<think>b</think>');
    });

    test('leaves an unclosed block open while still streaming', () => {
        // Closing early would make the renderer treat partial reasoning as complete.
        assert.equal(normalizeThinkTags('a<think>b', true), 'a<think>b');
    });

    test('splits nested opens rather than nesting them', () => {
        assert.equal(normalizeThinkTags('<think>a<think>b</think>', false),
            '<think>a</think>\n<think>b</think>');
    });

    test('drops a stray close tag with no opener', () => {
        assert.equal(normalizeThinkTags('a</think>b', false), 'ab');
    });

    test('handles empty and missing input', () => {
        assert.equal(normalizeThinkTags('', false), '');
        assert.equal(normalizeThinkTags(null, false), '');
        assert.equal(normalizeThinkTags(undefined, true), '');
    });

    test('preserves text with no tags byte-for-byte', () => {
        const text = 'Plain answer with [1] and `code` and <b>markup</b>.';
        assert.equal(normalizeThinkTags(text, false), text);
    });

    test('is idempotent on already-normalized text', () => {
        const once = normalizeThinkTags('<think>a<think>b', false);
        assert.equal(normalizeThinkTags(once, false), once);
    });
});

describe('describeSendFailure', () => {
    test('maps 403 and 404 to sentences, not status codes', () => {
        assert.equal(describeSendFailure(403, ''), 'You do not have access to this conversation.');
        assert.equal(describeSendFailure(404, ''), 'This conversation no longer exists.');
    });

    test('prefers a server-supplied error message', () => {
        assert.equal(describeSendFailure(500, '{"error":"Model timed out"}'), 'Model timed out');
    });

    test('falls back to the status when the body is not JSON', () => {
        assert.equal(describeSendFailure(500, '<html>502 Bad Gateway</html>'),
            'Request failed (500).');
    });

    test('falls back when the body is JSON without an error field', () => {
        assert.equal(describeSendFailure(500, '{"detail":"nope"}'), 'Request failed (500).');
    });

    test('handles an empty body', () => {
        assert.equal(describeSendFailure(429, ''), 'Request failed (429).');
    });
});

describe('markup builders', () => {
    test('the loader escapes its status text', () => {
        const out = syncLoaderMarkup('<img src=x onerror=alert(1)>');
        assert.ok(!out.includes('<img'), out);
        assert.ok(out.includes('&lt;img'));
    });

    test('the placeholder carries the id and variant class', () => {
        const out = assistantPlaceholderMarkup('msg-42', 'streaming');
        assert.ok(out.includes('id="msg-42"'));
        assert.ok(out.includes('message-assistant streaming'));
        assert.ok(out.includes('data-raw-content=""'), 'renderer reads raw content from here');
    });

    test('the placeholder embeds the loader', () => {
        assert.ok(assistantPlaceholderMarkup('m', 'sync').includes('sync-loader-container'));
    });
});

describe('promptLabel', () => {
    const prompts = [{ name: 'oim-full', title: 'OIM Full' }];
    const labels = { search_corpus: 'Search Corpus' };

    test('prefers the curated label', () => {
        assert.equal(promptLabel('search_corpus', prompts, labels), 'Search Corpus');
    });

    test('falls back to the prompt title', () => {
        assert.equal(promptLabel('oim-full', prompts, labels), 'OIM Full');
    });

    test('de-slugifies an unknown name', () => {
        assert.equal(promptLabel('oim-db-history', [], {}), 'Oim Db History');
        assert.equal(promptLabel('some_tool_name', [], {}), 'Some Tool Name');
    });

    test('returns empty for a missing name', () => {
        assert.equal(promptLabel('', prompts, labels), '');
        assert.equal(promptLabel(null, prompts, labels), '');
    });

    test('survives absent collaborators', () => {
        assert.equal(promptLabel('oim-full', undefined, undefined), 'Oim Full');
    });
});

describe('chatInputDefaultPlaceholder', () => {
    const prompts = [{ name: 'oim-full', title: 'OIM Full' }];

    test('names the active playbook by title', () => {
        assert.equal(chatInputDefaultPlaceholder('oim-full', prompts),
            'Add your question for "OIM Full"...');
    });

    test('falls back to the raw name when the playbook is unknown', () => {
        assert.equal(chatInputDefaultPlaceholder('mystery', prompts),
            'Add your question for "mystery"...');
    });

    // Pins the wording, not just the opening words. The same sentence is ALSO hardcoded in
    // chat/thread.html (the server renders it on first paint; this function restores it when the
    // active playbook is cleared), and nothing else ties the two copies together — an assertion of
    // /^Ask a question/ passed whatever the rest of the sentence said, including the old "select a
    // skill", which was the product's only remaining use of that word.
    test('uses the generic hint when no playbook is active, naming the playbook picker', () => {
        assert.equal(chatInputDefaultPlaceholder(null, prompts),
            'Ask a question… (type "/" or click "+" to select a playbook, '
            + 'Shift + Enter for new line, Enter to send)');
    });

    test('survives an absent prompt list', () => {
        assert.equal(chatInputDefaultPlaceholder('oim-full', undefined),
            'Add your question for "oim-full"...');
    });
});

describe('pollTerminalDecision', () => {
    // The reported bug: a run that FAILED was rendered as "[Generation stopped by user]" because
    // the poll passed `status === 'error'` straight into finalizeStream's isAborted parameter.
    // A server-reported error is terminal, but it is not a cancellation.
    test('a failed run is terminal and shows no cancellation notice', () => {
        assert.deepEqual(pollTerminalDecision('error', false),
            { terminal: true, showCancelNotice: false });
    });

    test('a cancelled run shows no client notice — the server already wrote the text', () => {
        assert.deepEqual(pollTerminalDecision('cancelled', false),
            { terminal: true, showCancelNotice: false });
    });

    test('a completed run is terminal and quiet', () => {
        assert.deepEqual(pollTerminalDecision('done', false),
            { terminal: true, showCancelNotice: false });
    });

    test('only "generating" keeps the poll alive', () => {
        assert.deepEqual(pollTerminalDecision('generating', false),
            { terminal: false, showCancelNotice: false });
    });

    // An unknown status must not wedge the client: the poll recurses via setTimeout and only the
    // terminal branch clears the loader interval, so a status matching no branch spins forever.
    test('an unrecognised status is treated as terminal rather than polling forever', () => {
        assert.deepEqual(pollTerminalDecision('weird', false),
            { terminal: true, showCancelNotice: false });
        assert.deepEqual(pollTerminalDecision(undefined, false),
            { terminal: true, showCancelNotice: false });
    });

    test('a genuine local abort wins over any server status', () => {
        assert.deepEqual(pollTerminalDecision('generating', true),
            { terminal: true, showCancelNotice: true });
        assert.deepEqual(pollTerminalDecision('error', true),
            { terminal: true, showCancelNotice: true });
    });
});

describe('cancelledNoticeMarkup', () => {
    test('both forms carry the wording the e2e stop test asserts', () => {
        assert.match(cancelledNoticeMarkup(true), /\[Generation stopped by user\]/);
        assert.match(cancelledNoticeMarkup(false), /\[Generation stopped by user\]/);
    });

    test('appends a paragraph when partial text is already rendered', () => {
        assert.match(cancelledNoticeMarkup(true), /class="aborted-text"/);
    });

    test('replaces the bubble with a bare span when nothing was generated', () => {
        assert.doesNotMatch(cancelledNoticeMarkup(false), /class="aborted-text"/);
    });
});
