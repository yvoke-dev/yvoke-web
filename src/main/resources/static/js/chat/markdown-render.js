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
    mapOutsideCode,
    mapOutsideFences,
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
 * Wraps rendered tool-call chatter in a span so CSS can hide it.
 *
 * Runs only outside <pre>/<code>. The pattern consumes everything up to the next block boundary, so
 * inside a code block it would swallow real answer text into a `display:none` element — the answer
 * would simply vanish with no error anywhere.
 */
export function wrapToolCalls(html) {
    return mapOutsideCode(html, function (segment) {
        return segment.replace(
            /(🔧\s*(?:<\w+>)?\*?Calling\s+tool(?:<\/\w+>)?[\s\S]*?(?=(?:<p>|<div>|🔧|\s*$)))/gi,
            '<span class="tool-call">$1</span>');
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
