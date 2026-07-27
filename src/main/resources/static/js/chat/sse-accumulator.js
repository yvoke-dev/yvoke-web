/*
 * SSE frame decoding for the chat stream.
 *
 * This is the protocol half of what used to be a `read()` closure inside a `.then()` inside the
 * submit listener: it owned three mutable outer locals, painted the DOM from inside its per-line
 * loop, and signalled "stop" by `return`ing out of that loop. Splitting it means the wire format is
 * testable without a browser, and the caller keeps every DOM call.
 *
 * Protocol, as the server writes it:
 *   data:<text>          a content delta; consecutive data: lines join with "\n"
 *   <blank line>         end of one event — the pending delta is committed
 *   data:[ERROR] <msg>   an error notice, rendered inline and the stream continues
 *   data:[DONE] {json}   terminal; the JSON payload carries messageId and token counts.
 *                        A bare [DONE] with no payload is valid and means "no usage reported".
 */

import { escapeHtml } from './thread-text.js';

/** Inline markup for a server-reported error, rendered into the answer where it occurred. */
export function errorMarkup(message) {
    return `\n\n<span class="error-text" style="color: var(--color-danger, #ef4444); font-weight: 500;">⚠️ ${escapeHtml(message)}</span>\n\n`;
}

/**
 * Creates a stateful decoder over an SSE byte stream.
 *
 * `feed(chunk)` takes a decoded string and returns `{ text, events }`, where `text` is the full
 * answer so far (committed content plus any uncommitted delta) and `events` lists what happened in
 * this chunk. `end()` reports the stream closing without a [DONE], committing any partial event.
 *
 * The caller paints once per returned result rather than once per line — the old loop re-ran the
 * entire markdown + sanitize + citation pipeline over the whole accumulated answer for every line
 * of every frame.
 */
export function createSseAccumulator() {
    let buffer = '';
    let committed = '';
    let pending = null;
    let finished = false;

    function commitPending() {
        if (pending !== null) {
            committed += pending;
            pending = null;
        }
    }

    function currentText() {
        return committed + (pending !== null ? pending : '');
    }

    function handleLine(line, events) {
        if (!line.startsWith('data:')) {
            // A blank line terminates the current event; anything else (comments, unknown
            // fields) is ignored, as SSE requires.
            if (line === '') {
                commitPending();
            }
            return true;
        }

        const val = line.substring(5);

        if (val.startsWith('[ERROR]')) {
            commitPending();
            const message = val.substring('[ERROR]'.length).trim();
            committed += errorMarkup(message);
            events.push({ type: 'error', message: message });
            return true;
        }

        if (val.startsWith('[DONE]')) {
            commitPending();
            const raw = val.substring('[DONE]'.length).trim();
            let payload = {};
            if (raw) {
                try {
                    payload = JSON.parse(raw);
                } catch (e) {
                    // A malformed payload must not lose the answer: finish with no usage data.
                    payload = {};
                }
            }
            finished = true;
            events.push({ type: 'done', payload: payload });
            return false;
        }

        pending = pending === null ? val : pending + '\n' + val;
        return true;
    }

    return {
        /** Feeds one decoded chunk. Returns the text so far and the events it produced. */
        feed(chunk) {
            const events = [];
            if (finished) {
                return { text: currentText(), events: events };
            }

            buffer += chunk;
            const lines = buffer.split('\n');
            // The final element is an incomplete line: hold it until more bytes arrive.
            buffer = lines.pop();

            for (let line of lines) {
                line = line.replace(/\r$/, '');
                if (!handleLine(line, events)) {
                    break;
                }
            }

            if (!events.some(e => e.type === 'done')) {
                events.push({ type: 'delta' });
            }
            return { text: currentText(), events: events };
        },

        /**
         * The stream closed without a [DONE]; commit whatever was partial.
         *
         * This also drains `buffer` — the trailing bytes of a line that never got its newline.
         * The original loop dropped those on the floor, so an abruptly closed stream silently lost
         * its last few characters of answer. Rare (the server normally ends with [DONE]) but a
         * silent loss, which is the class of bug this module exists to prevent.
         */
        end() {
            if (!finished) {
                if (buffer !== '') {
                    handleLine(buffer.replace(/\r$/, ''), []);
                    buffer = '';
                }
                commitPending();
                finished = true;
            }
            return { text: currentText(), events: [{ type: 'done', payload: {} }] };
        },

        /** True once a [DONE] has been seen or end() has been called. */
        get finished() {
            return finished;
        },

        /** The full answer accumulated so far. */
        get text() {
            return currentText();
        },
    };
}
