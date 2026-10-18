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
    MD_FENCE,
    mapOutsideFences,
    mapOutsidePre,
    mapOutsideRegions,
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
            // Raw HTML is ESCAPED rather than passed through. DOMPurify drops an unknown element
            // while keeping its text, so `<Class Name="Group">` reached the user as nothing at all
            // — silent content loss on a corpus full of XML, C# and VB.NET config. The cost is
            // that an answer can no longer use HTML for formatting: <br>, <sub>, an <a> or an HTML
            // table now show as source. That is the deliberate trade — over-rendering loses
            // content invisibly, over-escaping is ugly but readable.
            //
            // A BLOCK token additionally needs a wrapper. Emitted bare it has no element around
            // it, so the browser collapses its newlines and indentation and a multi-line schema
            // renders as one run-on line with no margins. <pre><code> is what the code renderer
            // emits and what index.css already styles, so the markup reads as the source it is —
            // and mapOutsidePre then treats it like any other code block. Inline tokens (<br>
            // mid-sentence) must NOT be wrapped, which is what token.block separates.
            html(token) {
                const text = typeof token === 'string' ? token : (token.text || token.raw || '');
                const trimmed = text.trim();

                // HTML comments are dropped — otherwise block-level comments like
                // <!-- topic: ... --> in ingested manuals get wrapped in <pre><code> code blocks.
                // Requiring `endsWith('-->')` means a half-arrived comment mid-stream briefly
                // shows as escaped source until its closer arrives; that is deliberate so an
                // unclosed `<!--` in quoted text never swallows the rest of the message.
                if (trimmed.startsWith('<!--') && trimmed.endsWith('-->')) {
                    return '';
                }

                return token && token.block
                    ? `<pre><code>${escapeHtml(text)}</code></pre>`
                    : escapeHtml(text);
            },
            code(token) {
                const rawText = token.text || '';
                const lang = token.lang || '';
                const isMermaid = lang === 'mermaid' || (!lang && isMermaidText(rawText));

                if (isMermaid) {
                    if (streaming) {
                        // Plain code block while streaming: a half-arrived diagram cannot render.
                        return `<pre><code class="language-mermaid">${escapeHtml(rawText)}</code></pre>`;
                    }
                    return `<div class="mermaid-diagram-container"><pre class="mermaid">${escapeHtml(rawText)}</pre></div>`;
                }

                const text = stripCodeLineNumberGutter(rawText);
                const escapedLang = (lang || '').match(/^\S*/)?.[0] || '';
                return `<pre><code class="language-${escapedLang}">${escapeHtml(text)}</code></pre>`;
            }
        }
    });
}

/**
 * Strips a MadCap Flare `<span class="codeSnippetLineNumbers">` line-number gutter (`1\n2\n…\nN`)
 * that was fused onto the top of a `<pre><code>` block during HTML-to-Markdown conversion,
 * normalizes non-breaking spaces (`\u00a0`) to ASCII spaces, and removes any common leading
 * indentation left on the remaining code lines.
 */
export function stripCodeLineNumberGutter(text) {
    const lines = text.split('\n');
    let gutterCount = 0;
    while (gutterCount < lines.length && /^[ \t]*\d+[ \t]*\r?$/.test(lines[gutterCount])) {
        if (Number(lines[gutterCount].trim()) !== gutterCount + 1) {
            break;
        }
        gutterCount++;
    }

    const codeLines = lines.slice(gutterCount);
    if (gutterCount < 2 || !codeLines.some((l) => l.trim().length > 0)) {
        return text.replace(/\u00a0/g, ' ');
    }

    const normalized = codeLines.map((l) => l.replace(/\u00a0/g, ' '));
    const nonEmpty = normalized.filter((l) => l.trim().length > 0);
    const minIndent = Math.min(...nonEmpty.map((l) => l.match(/^[ \t]*/)[0].length));
    return normalized
        .map((l) => (l.trim().length > 0 ? l.slice(minIndent) : ''))
        .join('\n');
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

/** An inline code span in raw markdown. */
const MD_CODE_SPAN = '`+[^`]+`+';

/** Fenced code blocks and inline code spans in raw markdown. */
const MD_CODE_REGIONS = `${MD_FENCE}|${MD_CODE_SPAN}`;

/** Matches empty `<a>` anchor tags with an id or name (e.g. `<a id="topic-..."></a>`). */
const EMPTY_ANCHOR = /<a(?=\s)[^>]*\s(?:id|name)\s*=[^>]*(?:>\s*<\/a>|\s*\/>)/gi;

/**
 * Drops empty anchor bookmark tags outside code fences and inline code spans so they do not
 * escape into visible text.
 */
export function stripEmptyAnchorBookmarks(text) {
    return mapOutsideRegions(text, MD_CODE_REGIONS, function (segment) {
        return segment.replace(EMPTY_ANCHOR, '');
    });
}

/**
 * Matches a standalone MadCap Flare `<a class="codeSnippetCopyButton">Copy</a>` line at the end of
 * a prose segment immediately preceding an opening code fence (with an optional single-line
 * snippet caption such as "Example" or "Example of a multi-line statement" between them).
 *
 * Uses `(?![\s\S])` rather than `$` to anchor to the true end of the segment: under `/m`, `$`
 * matches at the end of any line, which would allow backtracking to strip a distant `Copy` line
 * separated from the fence by arbitrary prose paragraphs.
 */
const CODE_SNIPPET_COPY_BEFORE_FENCE =
    /^[ \t]*Copy[ \t]*\r?\n(?:[ \t]*\r?\n)*(?=(?:[ \t]*[A-Za-z][^\r\n`|#*>]*[ \t]*\r?\n(?:[ \t]*\r?\n)*)?[ \t]*(?![\s\S]))/m;

/**
 * Drops MadCap Flare "Copy" toolbar lines immediately preceding fenced code blocks.
 *
 * Walking `MD_FENCE` matches whole fenced blocks (`m[0]`), so `text.slice(last, m.index)` is both
 * outside every fence and guaranteed to end right at the next opening fence — a `Copy` line inside
 * a fence before its closing delimiter sits inside `m[0]` and is never seen.
 */
export function stripCodeSnippetCopyButtons(text) {
    const re = new RegExp(MD_FENCE, 'g');
    let out = '';
    let last = 0;
    let m;
    while ((m = re.exec(text)) !== null) {
        out += text.slice(last, m.index).replace(CODE_SNIPPET_COPY_BEFORE_FENCE, '') + m[0];
        last = m.index + m[0].length;
    }
    return out + text.slice(last);
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

/** An `<a>` opening tag whose href is http(s) — anything else is ours or in-page. */
const EXTERNAL_ANCHOR = /<a\s([^>]*href="https?:\/\/[^"]*"[^>]*)>/gi;

/**
 * Points every external link at a new tab.
 *
 * <p>Runs AFTER the sanitizer, deliberately: it only ADDS `target`/`rel` to anchors DOMPurify has
 * already approved, so it cannot reintroduce anything the sanitizer removed, and living here — a
 * pure string -> string pass — keeps it in the node test tier. A DOMPurify hook would be the
 * idiomatic home and is unpinnable: DOMPurify needs a DOM and the tests inject a spy for it.
 *
 * Gated on http(s) so `formatCitations`' own `<a href="#" class="citation-link">` is untouched —
 * a target on those opens a blank tab instead of the citation panel. An anchor that already
 * declares a target is left alone, which also makes the pass idempotent.
 */
export function openExternalLinksInNewTab(html) {
    return html.replace(EXTERNAL_ANCHOR, function (match, attrs) {
        if (/\btarget\s*=/i.test(attrs)) {
            return match;
        }
        return `<a ${attrs} target="_blank" rel="noopener noreferrer">`;
    });
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
    source = stripEmptyAnchorBookmarks(source);
    source = stripCodeSnippetCopyButtons(source);
    source = normalizeSpacing(source);

    // No unwrapBacktickedCitations() call here: it is protectTokens' own first step, because the
    // unwrap exists solely to let it see tokens hidden inside a <code> span. Calling it here too
    // was a no-op that made "unwrap before protect" a convention rather than a fact.
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
    // openExternalLinksInNewTab only adds target/rel to anchors the sanitizer already passed, so
    // running it afterwards cannot put back anything DOMPurify took out.
    return openExternalLinksInNewTab(deps.sanitize(safe));
}
