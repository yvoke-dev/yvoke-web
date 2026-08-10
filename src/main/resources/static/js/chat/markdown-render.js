/*
 * The markdown render pipeline for chat messages.
 *
 * Why the renderer config and renderMarkdown live in the SAME module: the code-block renderer needs
 * to know whether the answer is still streaming (a mermaid block renders as plain code mid-stream
 * and as a diagram once finished). That used to travel through `window.markedIsStreaming` — written
 * by renderMarkdown, read inside the renderer closure. Injecting `parse` as a parameter gives that
 * closure no channel to the flag, so both halves must share one module-level `let` instead. Keeping
 * them together is what let the global go.
 *
 * `parse` (marked) and `sanitize` (DOMPurify) are injected: both are classic-script globals in the
 * browser, and injecting them keeps this module loadable in Node.
 */

import {
    mapOutsideFences,
    mapOutsidePre,
    normalizeSpacing,
    protectTokens,
    restoreTokens,
} from './citation-render.js';
import { escapeHtml, isMermaidText, normalizeThinkTags } from './thread-text.js';

/** Set for the duration of one parse; read by the code renderer installed below. */
let streaming = false;

/**
 * Installs the chat's code-block handling on a marked instance.
 *
 * The tokenizer disables indented (4-space) code blocks — ingested manuals are full of indented
 * raw markup that would otherwise be swallowed into <pre><code>. The renderer routes mermaid to a
 * diagram container, or to a plain code block while streaming.
 */
export function installCodeRenderer(marked) {
    marked.use({
        tokenizer: {
            code(src) {
                return undefined;
            }
        },
        renderer: {
            code(token) {
                const text = token.text || '';
                const lang = token.lang || '';
                const isMermaid = lang === 'mermaid' || (!lang && isMermaidText(text));

                if (isMermaid) {
                    if (streaming) {
                        // Plain code block while streaming: a half-arrived diagram cannot render.
                        return `<pre><code class="language-mermaid">${escapeHtml(text)}</code></pre>`;
                    }
                    return `<div class="mermaid-diagram-container"><pre class="mermaid">${escapeHtml(text)}</pre></div>`;
                }

                const escapedLang = (lang || '').match(/^\S*/)?.[0] || '';
                return `<pre><code class="language-${escapedLang}">${escapeHtml(text)}</code></pre>`;
            }
        }
    });
}

/**
 * Forces a "🔧 Calling tool" marker onto its own line so marked treats it as a block.
 * Skips fenced code — a fence that legitimately contains that string is content, not a marker.
 */
export function forceToolCallNewlines(text) {
    return mapOutsideFences(text, function (segment) {
        return segment.replace(/([^\n])\s*(🔧\s*\*?Calling\s+tool)/gi, '$1\n\n$2');
    });
}

/**
 * One run of tool-call chatter: the 🔧 marker and everything up to the next block boundary.
 *
 * The boundaries are `<p`/`<div` rather than the literal `<p>`/`<div>` they used to be. Our own
 * mermaid container is `<div class="mermaid-diagram-container">`, which the literal did not match,
 * so a diagram straight after a tool call had its opening tag swallowed into the hidden span and
 * the output was only well-formed by the browser's error recovery. `</p>` closes a run too, so the
 * wrapper nests inside the paragraph instead of straddling it — this HTML goes to innerHTML with
 * no sanitizer after it, so it has to be well-formed on its own.
 */
const TOOL_CALL_RUN =
    /(🔧\s*(?:<\w+>)?\*?Calling\s+tool(?:<\/\w+>)?[\s\S]*?(?=(?:<p\b|<div\b|<\/p>|🔧|\s*$)))/gi;

/** An inline `code` span in rendered HTML. */
const INLINE_CODE = /<code\b[\s\S]*?<\/code>/g;

/**
 * Wraps rendered tool-call chatter in a span so CSS can hide it.
 *
 * `<pre>` blocks are skipped: a run consumes everything to the next block boundary, so a fenced
 * block following a tool call would be swallowed into a `display:none` element and the answer would
 * simply vanish with no error anywhere.
 *
 * Inline `<code>` is **masked, not skipped** — and that distinction is the whole point. This used
 * to run through `mapOutsideCode`, which skips both. A tool call whose arguments contain a
 * backticked path — which the desktop agent produces constantly, quoting sandbox paths like
 * `C:\…\tool-results\mcp-yvoke-get_section-<ts>.txt` — was therefore cut into three: the half
 * carrying the marker was wrapped and hidden, the `<code>` was left alone by design, and the text
 * after it had no marker left to match, so nothing wrapped it. Everything from the first backtick
 * to the end of the paragraph stayed on screen — and because `hide-thinking-process` correctly hid
 * the marker half, the user saw an orphaned Windows path and raw argument prose mid-answer with
 * nothing to explain it. Masking lets a run continue through inline code while still keeping a
 * literal marker *inside* inline code from starting one, which is what skipping bought.
 */
export function wrapToolCalls(html) {
    return mapOutsidePre(html, function (segment) {
        const codes = [];
        const masked = segment.replace(INLINE_CODE, function (match) {
            codes.push(match);
            return `%%TOOLCODE_${codes.length - 1}%%`;
        });
        const wrapped = masked.replace(TOOL_CALL_RUN, '<span class="tool-call">$1</span>');
        return wrapped.replace(/%%TOOLCODE_(\d+)%%/g,
            (match, idx) => (codes[Number(idx)] === undefined ? match : codes[Number(idx)]));
    });
}

/**
 * Swaps block-level constructs marked must not touch for placeholders, appending to the array
 * protectTokens already started so indices stay contiguous for restoreTokens.
 */
function protectBlocks(safe, placeholders) {
    const push = function (value) {
        const idx = placeholders.length;
        placeholders.push(value);
        return `%%CITE_${idx}%%`;
    };

    let out = safe;
    out = out.replace(/<think>([\s\S]*?)<\/think>/g, (m, c) => push(`<think>${c}</think>`));
    out = out.replace(/<clarifying-question>([\s\S]*?)<\/clarifying-question>/g,
        (m, c) => push(`<clarifying-question>${c}</clarifying-question>`));
    out = out.replace(/<code-execution>([\s\S]*?)<\/code-execution>/g,
        (m, c) => push(`<code-execution>${c}</code-execution>`));

    // Math. KaTeX renders these after insertion, so marked must not reformat the delimiters.
    out = out.replace(/\$\$([\s\S]*?)\$\$/g, (m, c) => push(`$$${c}$$`));
    out = out.replace(/\\\(([\s\S]*?)\\\)/g, (m, c) => push(`\\(${c}\\)`));
    // Display math \[ … \]. The previous pattern was built by string-escaping into
    // `[\\s\\S]`, which is the character class {backslash, s, S} — not "any character" — so it
    // could never match and display math was never protected.
    out = out.replace(/\\\[([\s\S]*?)\\\]/g, (m, c) => push(`\\[${c}\\]`));

    return out;
}

/**
 * Handles a `<think>` block that has no closing tag yet.
 *
 * While streaming, its content is parsed and sanitized immediately and the block is closed, so a
 * half-arrived reasoning block cannot break the page layout. Once finished, an unclosed block is
 * treated as complete and left for the normal restore path.
 */
function protectUnclosedThink(safe, placeholders, isStreaming, deps) {
    const lastThinkStart = safe.lastIndexOf('<think>');
    if (lastThinkStart === -1 || safe.indexOf('</think>', lastThinkStart) !== -1) {
        return safe;
    }
    const rawThinkingText = safe.substring(lastThinkStart).substring(7);
    const idx = placeholders.length;
    if (isStreaming) {
        placeholders.push('<think class="streaming-think">'
            + deps.sanitize(deps.parse(rawThinkingText)) + '</think>');
    } else {
        placeholders.push('<think>' + rawThinkingText + '</think>');
    }
    return safe.substring(0, lastThinkStart) + `%%CITE_${idx}%%`;
}

/**
 * Renders one message's raw text to sanitized HTML.
 *
 * `deps.parse` is marked's parser, `deps.sanitize` the DOMPurify wrapper. Citation tokens are still
 * plain text on return — the caller adds the trusted, regex-constrained citation links afterwards.
 */
export function renderMarkdown(text, isStreaming, deps) {
    if (!text) return '';

    let source = normalizeThinkTags(text, isStreaming);
    source = forceToolCallNewlines(source);
    source = normalizeSpacing(source);

    const protectedText = protectTokens(source);
    const placeholders = protectedText.placeholders;
    let safe = protectBlocks(protectedText.safe, placeholders);
    safe = protectUnclosedThink(safe, placeholders, isStreaming, deps);

    streaming = isStreaming;
    try {
        safe = deps.parse(safe);
    } finally {
        // Reset even if marked throws, or every later render would think it is mid-stream.
        streaming = false;
    }

    safe = restoreTokens(safe, placeholders);

    // Sanitize last. wrapToolCalls is deliberately NOT called here — it runs in the caller, after
    // the citation passes, exactly where it always has; moving it would reorder the whole chain.
    return deps.sanitize(safe);
}
