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

                // Balanced HTML tables (both complete <table>...</table> blocks from imported
                // manuals and split table/cell fragments repaired by balanceHtmlTables) are
                // allowed through as HTML tables, with any non-table XML/placeholder tags inside
                // cells escaped so DOMPurify never silently drops content.
                if (token && token.block && isBalancedHtmlTableBlock(trimmed)) {
                    return escapeNonTableHtmlTags(trimmed);
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

    const hasNbsp = codeLines.some((l) => l.includes('\u00a0'));
    const normalized = codeLines.map((l) => l.replace(/\u00a0/g, ' '));
    const nonEmpty = normalized.filter((l) => l.trim().length > 0);
    const minIndent = Math.min(...nonEmpty.map((l) => l.match(/^[ \t]*/)[0].length));
    if (!hasNbsp && !(minIndent >= 4 && nonEmpty.length === gutterCount)) {
        return text.replace(/\u00a0/g, ' ');
    }
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

/**
 * Matches CommonMark fenced code blocks (` ``` ` or `~~~` of length >= 3, closed by a fence of
 * the same character and at least the same length, or running to end-of-input if unclosed).
 */
const MD_FENCE_RE =
    /^[ \t]*(?:(?<btfence>`{3,})[^\r\n]*\r?\n[\s\S]*?(?:\r?\n[ \t]*\k<btfence>`*[ \t]*(?=\r?\n|$)|(?![\s\S]))|(?<tfence>~{3,})[^\r\n]*\r?\n[\s\S]*?(?:\r?\n[ \t]*\k<tfence>~*[ \t]*(?=\r?\n|$)|(?![\s\S])))/gm;

/** Matches CommonMark inline code spans (single-backtick or multi-backtick). */
const MD_CODE_SPAN_RE = /(?<span>`{2,})(?!`)[\s\S]*?(?<!`)\k<span>(?!`)|`[^`\r\n]+`/g;

/**
 * Applies `fn` only to substrings outside CommonMark fenced code blocks and inline code spans,
 * scanning the two regexes independently so backreference numbering never collides.
 */
function mapOutsideCodeRegions(text, fn) {
    const protectedRanges = [];
    const fenceRe = new RegExp(MD_FENCE_RE.source, MD_FENCE_RE.flags);
    let m;
    while ((m = fenceRe.exec(text)) !== null) {
        protectedRanges.push([m.index, m.index + m[0].length]);
    }

    const collectCodeSpans = (sliceStart, sliceEnd) => {
        const sub = text.slice(sliceStart, sliceEnd);
        const spanRe = new RegExp(MD_CODE_SPAN_RE.source, MD_CODE_SPAN_RE.flags);
        let sm;
        while ((sm = spanRe.exec(sub)) !== null) {
            protectedRanges.push([sliceStart + sm.index, sliceStart + sm.index + sm[0].length]);
        }
    };

    let cursor = 0;
    const fenceSnapshot = [...protectedRanges];
    for (const [fStart, fEnd] of fenceSnapshot) {
        if (cursor < fStart) {
            collectCodeSpans(cursor, fStart);
        }
        cursor = fEnd;
    }
    if (cursor < text.length) {
        collectCodeSpans(cursor, text.length);
    }

    protectedRanges.sort((a, b) => a[0] - b[0]);

    let out = '';
    let last = 0;
    for (const [start, end] of protectedRanges) {
        out += fn(text.slice(last, start)) + text.slice(start, end);
        last = end;
    }
    return out + fn(text.slice(last));
}

/** Matches empty `<a>` anchor tags with an id or name (e.g. `<a id="topic-..."></a>`). */
const EMPTY_ANCHOR = /<a(?=\s)[^>]*\s(?:id|name)\s*=[^>]*(?:>\s*<\/a>|\s*\/>)/gi;

/**
 * Drops empty anchor bookmark tags outside code fences and inline code spans so they do not
 * escape into visible text.
 */
export function stripEmptyAnchorBookmarks(text) {
    return mapOutsideCodeRegions(text, function (segment) {
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
 * Walking `MD_FENCE_RE` matches whole fenced blocks (`m[0]`), so `text.slice(last, m.index)` is
 * both outside every fence and guaranteed to end right at the next opening fence — a `Copy` line
 * inside a fence before its closing delimiter sits inside `m[0]` and is never seen.
 */
export function stripCodeSnippetCopyButtons(text) {
    const re = new RegExp(MD_FENCE_RE.source, MD_FENCE_RE.flags);
    let out = '';
    let last = 0;
    let m;
    while ((m = re.exec(text)) !== null) {
        out += text.slice(last, m.index).replace(CODE_SNIPPET_COPY_BEFORE_FENCE, '') + m[0];
        last = m.index + m[0].length;
    }
    return out + text.slice(last);
}

/** Allowed lowercase HTML tags inside an imported manual table block. */
const TABLE_HTML_TAGS = new Set([
    'table', 'caption', 'colgroup', 'col', 'thead', 'tbody', 'tfoot', 'tr', 'th', 'td',
    'p', 'ul', 'ol', 'li', 'dl', 'dt', 'dd', 'div', 'span',
    'b', 'strong', 'i', 'em', 'u', 's', 'sub', 'sup', 'code', 'pre', 'br', 'hr', 'a', 'img',
]);

/** Self-closing / void HTML elements inside a table block. */
const VOID_HTML_TAGS = new Set(['br', 'hr', 'img', 'col']);

/** Validates standard HTML `key="value"` / `key='value'` / `key=value` / boolean attribute lists. */
const HTML_ATTR_LIST_RE =
    /^(?:\s+[a-zA-Z_:][\w:.-]*(?:\s*=\s*(?:"[^"]*"|'[^']*'|[^\s"'=<>`]+))?)*\s*$/;

/** Matches any `<...>` tag candidate in a fragment. */
const ANY_ANGLE_TAG_RE = /<(\/?)([a-zA-Z][\w:.-]*)((?:\s+[^>]*?)?)(\/?)>/g;

/**
 * Matches a real HTML `<table...>` opening tag followed by a table child tag (`<caption`,
 * `<colgroup`, `<col`, `<thead`, `<tbody`, `<tfoot`, `<tr`, `<td`, `<th`), distinguishing genuine
 * HTML tables from prose placeholders like `**<table>**` or `<table name>`.
 */
const REAL_TABLE_OPEN_RE =
    /<table(?:\s+[a-zA-Z_:][\w:.-]*(?:\s*=\s*(?:"[^"]*"|'[^']*'|[^\s"'=<>`]+))?)*\s*>(?=\s*<(?:caption|colgroup|col|thead|tbody|tfoot|tr|td|th)\b)/;

/**
 * Detects a raw HTML table or split table/cell fragment. Every real HTML block in the imported
 * manuals comes from a complex `<table>...</table>` preserved by the converter; matching lowercase
 * closing table/block tags or opening table structural tags has zero false positives on uppercase
 * XML examples (`<Key><T>DialogTable</T></Key>`) and prose angle-bracket placeholders.
 */
const TABLE_FRAGMENT_DETECTOR_RE =
    /<\/(?:table|thead|tbody|tfoot|tr|td|th|colgroup|caption|p|ul|ol|li|div)\s*>|<(?:tr|td|th|thead|tbody|tfoot|colgroup|caption)\b/;

/** Leading orphan closing tags at the very start of a split chunk before any cell content. */
const LEADING_EMPTY_CLOSERS_RE =
    /^(?:\s*<\/(?:p|ul|ol|li|div|span|b|i|strong|em|u|s|sub|sup|code|pre|a|td|th|tr|tbody|thead|tfoot|colgroup|caption|table)\s*>)+/;

/** MadCap Flare conditional text wrapper tags (`<madcap:conditionaltext ...>...</madcap:conditionaltext>`). */
const MADCAP_CONDITIONAL_TAG_RE = /<\/?madcap:conditionaltext\b[^>]*>/gi;

/**
 * Returns true when a regex match (`slash`, `rawName`, `attrs`) is a genuine lowercase
 * HTML table/cell tag with well-formed attributes (not an uppercase XML element like
 * `<Class Name="Group">` or a prose placeholder like `<table name>` / `<column name>`).
 */
function isValidTableHtmlTag(slash, rawName, attrs) {
    if (rawName !== rawName.toLowerCase() || !TABLE_HTML_TAGS.has(rawName)) {
        return false;
    }
    return slash ? attrs.trim() === '' : HTML_ATTR_LIST_RE.test(attrs);
}

/**
 * Escapes any `<...>` inside a balanced HTML table that is not a valid lowercase HTML table/cell
 * tag (for example `<condition>`, `<column name>`, `<form name>`, or `<Class Name="Group">`) so
 * DOMPurify does not strip unknown elements and silently drop their text.
 */
export function escapeNonTableHtmlTags(html) {
    return html.replace(ANY_ANGLE_TAG_RE, function (full, slash, rawName, attrs) {
        return isValidTableHtmlTag(slash, rawName, attrs) ? full : escapeHtml(full);
    });
}

/**
 * Checks whether a trimmed block HTML token is a single `<table...>...</table>` element.
 */
function isBalancedHtmlTableBlock(trimmed) {
    return REAL_TABLE_OPEN_RE.test(trimmed) && /<\/table\s*>$/.test(trimmed);
}

/**
 * Counts how many top-level `<td>`/`<th>` cells appear in the first `<tr>` vs the widest `<tr>`
 * of a balanced table, and when a split chunk started mid-row (`'tr'` was an unmatched closer)
 * adds `colspan` to the synthesized leading `<td>` so the partial first row spans all columns.
 */
function applyLeadingPartialRowColspan(tableHtml) {
    const rowRe = /<tr\b[^>]*>([\s\S]*?)<\/tr\s*>/g;
    let firstRowCols = 0;
    let maxCols = 0;
    let rowMatch;
    while ((rowMatch = rowRe.exec(tableHtml)) !== null) {
        const cols = (rowMatch[1].match(/<(?:td|th)\b/g) || []).length;
        if (firstRowCols === 0) {
            firstRowCols = cols;
        }
        if (cols > maxCols) {
            maxCols = cols;
        }
    }
    if (firstRowCols > 0 && maxCols > firstRowCols) {
        const span = maxCols - firstRowCols + 1;
        return tableHtml.replace(
            /^(<table\b[^>]*>(?:<tbody>|<thead\b[^>]*>|<tfoot>)?<tr>)<(td|th)>/,
            `$1<$2 colspan="${span}">`
        );
    }
    return tableHtml;
}

/**
 * Converts a markdown inline code span (e.g. `` `cmd` `` or ```` ``a`<td>`b`` ````) into an HTML
 * `<code>...</code>` element with HTML-special characters escaped.
 */
function codeSpanToHtml(span) {
    const openMatch = span.match(/^`+/);
    /* c8 ignore next */
    if (!openMatch) return escapeHtml(span);
    const ticks = openMatch[0].length;
    let inner = span.slice(ticks, span.length - ticks);
    if (inner.length >= 2 && inner.startsWith(' ') && inner.endsWith(' ') && /[^ ]/.test(inner)) {
        inner = inner.slice(1, -1);
    }
    return `<code>${escapeHtml(inner)}</code>`;
}

/**
 * Balances missing opening and closing HTML tags for a raw HTML table or a split table/cell
 * fragment (produced when `MarkdownTree.splitRecursive` slices a large `<table>` across multiple
 * chunks on `\n\n` / `\n`), wrapping orphan cell/row content in `<table><tbody><tr><td>...` as
 * needed and collapsing internal blank lines so CommonMark parses the table as one block token.
 */
function balanceTableFragment(fragment, codeSpans = []) {
    const cleaned = fragment.replace(LEADING_EMPTY_CLOSERS_RE, '');
    const openStack = [];
    const unmatchedClosers = [];

    ANY_ANGLE_TAG_RE.lastIndex = 0;
    let match;
    while ((match = ANY_ANGLE_TAG_RE.exec(cleaned)) !== null) {
        const [, slash, rawName, attrs, selfSlash] = match;
        if (!isValidTableHtmlTag(slash, rawName, attrs)) {
            continue;
        }
        if (VOID_HTML_TAGS.has(rawName) || selfSlash === '/') {
            continue;
        }
        if (!slash) {
            openStack.push(rawName);
        } else {
            const idx = openStack.lastIndexOf(rawName);
            if (idx !== -1) {
                openStack.splice(idx);
            } else {
                unmatchedClosers.push(rawName);
            }
        }
    }

    const firstTagMatch = cleaned.match(/^\s*<([a-z0-9]+)\b/);
    const firstTag = firstTagMatch ? firstTagMatch[1] : null;
    const allTags = new Set([...unmatchedClosers, ...openStack]);
    if (firstTag) {
        allTags.add(firstTag);
    }

    const outerAncestors = [];
    if (!allTags.has('table')) {
        outerAncestors.push('table');
    }
    if (!allTags.has('table') && !allTags.has('tbody') && !allTags.has('thead') && !allTags.has('tfoot')) {
        if (
            allTags.has('tr')
            || allTags.has('td')
            || allTags.has('th')
            || (!allTags.has('colgroup') && !allTags.has('caption'))
        ) {
            outerAncestors.push('tbody');
        }
    }
    if (!allTags.has('table') && !allTags.has('tbody') && !allTags.has('thead') && !allTags.has('tfoot') && !allTags.has('tr')) {
        if (
            allTags.has('td')
            || allTags.has('th')
            || (!allTags.has('colgroup') && !allTags.has('caption'))
        ) {
            outerAncestors.push('tr');
        }
    }
    if (
        !allTags.has('td') && !allTags.has('th') && !allTags.has('tr')
        && !allTags.has('tbody') && !allTags.has('thead') && !allTags.has('tfoot')
        && !allTags.has('colgroup') && !allTags.has('caption') && !allTags.has('table')
    ) {
        outerAncestors.push('td');
    }

    const prefixTags = [...outerAncestors, ...[...unmatchedClosers].reverse()];
    const suffixTags = [...[...openStack].reverse(), ...[...outerAncestors].reverse()];
    const prefix = prefixTags.map((t) => `<${t}>`).join('');
    const suffix = suffixTags.map((t) => `</${t}>`).join('');

    let balanced = escapeNonTableHtmlTags(prefix + cleaned.trim() + suffix);
    if (codeSpans.length > 0) {
        balanced = balanced.replace(/\uE000CS(\d+)\uE001/g, (_, idx) =>
            codeSpanToHtml(codeSpans[Number(idx)])
        );
    }
    if (unmatchedClosers.includes('tr')) {
        balanced = applyLeadingPartialRowColspan(balanced);
    }
    return balanced.replace(/\r?\n(?:[ \t]*\r?\n)+/g, '\n');
}

/**
 * Finds the end index (after `</table>`) of the outermost `<table...>` starting at `startIdx`,
 * or `-1` if the table was cut off before its closing `</table>` tag.
 */
function findMatchingTableEnd(segment, startIdx) {
    const tableTagRe =
        /<(\/?)table(?:\s+[a-zA-Z_:][\w:.-]*(?:\s*=\s*(?:"[^"]*"|'[^']*'|[^\s"'=<>`]+))?)*\s*>/g;
    tableTagRe.lastIndex = startIdx;
    let depth = 0;
    let m;
    while ((m = tableTagRe.exec(segment)) !== null) {
        depth += m[1] === '/' ? -1 : 1;
        if (depth === 0) {
            return m.index + m[0].length;
        }
    }
    return -1;
}

/**
 * Appends a balanced `<table>...</table>` block to `out` with blank-line separation from any
 * preceding content so CommonMark parses the table as its own HTML block.
 */
function appendBalancedBlock(out, headingPrefix, balancedTable) {
    const combined = headingPrefix
        ? `${headingPrefix.replace(/(?:\r?\n)*$/, '\n\n')}${balancedTable}`
        : balancedTable;
    if (out.length === 0) {
        return combined;
    }
    const sep = out.endsWith('\n\n') ? '' : out.endsWith('\n') ? '\n' : '\n\n';
    return `${out}${sep}${combined}`;
}

/**
 * Checks whether a segment ending with `</table>` (and no preceding `<table...>`) is a genuine
 * split table tail rather than single-line prose mentioning `</table>`.
 */
function isSplitTableTail(frag) {
    return (
        /(?:<\/(?:tbody|thead|tfoot|tr|td|th|colgroup|caption)\s*>)\s*<\/table\s*>$/.test(frag)
        && (/^\s*</.test(frag) || /\r?\n/.test(frag))
    );
}

/**
 * Checks whether a segment with neither `<table...>` nor `</table>` is a genuine mid-table split
 * chunk from an imported manual rather than assistant prose mentioning `<td>`, `</td>`, `<tr>`,
 * or `</p>` inline.
 */
function isMidTableFragment(frag) {
    return (
        /^\s*<\/?(?:tr|td|th|tbody|thead|tfoot|colgroup|caption|p|ul|ol|li|div)\b/.test(frag)
        && (
            /<\/?(?:tr|td|th|tbody|thead|tfoot|colgroup|caption)\b/.test(frag)
            || /^[ \t]{4,}<(?:p|ul|ol|li|div)\b/.test(frag)
        )
    );
}

/**
 * Unwraps MadCap conditional text tags and repairs split HTML table fragments outside fenced code
 * blocks and inline code spans so complete and chunk-split manual tables render cleanly.
 */
export function balanceHtmlTables(text) {
    const processGap = function (rawFenceGap) {
        const codeSpans = [];
        const maskedGap = rawFenceGap.replace(MD_CODE_SPAN_RE, function (span) {
            const idx = codeSpans.length;
            codeSpans.push(span);
            return `\uE000CS${idx}\uE001`;
        });
        const restoreCodeSpans = (s) =>
            s.replace(/\uE000CS(\d+)\uE001/g, (_, idx) => codeSpans[Number(idx)]);

        const segment = maskedGap.replace(MADCAP_CONDITIONAL_TAG_RE, '');
        if (!TABLE_FRAGMENT_DETECTOR_RE.test(segment)) {
            return restoreCodeSpans(segment);
        }

        const hasLeadingNewline = /^\r?\n/.test(segment);
        const hasTrailingNewline = /\r?\n[ \t]*$/.test(segment);
        let balancedAny = false;
        let out = '';
        let pos = 0;
        while (pos < segment.length) {
            const rest = segment.slice(pos);
            const openMatch = REAL_TABLE_OPEN_RE.exec(rest);
            const closeTagMatch = /<\/table\s*>/.exec(rest);
            const closeIdx = closeTagMatch ? closeTagMatch.index : -1;
            const closeLen = closeTagMatch ? closeTagMatch[0].length : 0;

            // Case 1: Leading split table tail before `</table>` (no opening `<table>` for it).
            if (closeIdx !== -1 && (openMatch === null || closeIdx < openMatch.index)) {
                const beforeClose = rest.slice(0, closeIdx + closeLen);
                const headingPrefixMatch = beforeClose.match(/^(?:\s*#{1,6}\s+[^\r\n]+\r?\n+)+/);
                const headingPrefix = headingPrefixMatch ? headingPrefixMatch[0] : '';
                const frag = beforeClose.slice(headingPrefix.length);
                if (isSplitTableTail(frag)) {
                    out = appendBalancedBlock(out, headingPrefix, balanceTableFragment(frag, codeSpans));
                    balancedAny = true;
                } else {
                    out += beforeClose;
                }
                pos += beforeClose.length;
                continue;
            }

            // Case 2: An explicit `<table...>` starts here or later in `rest`.
            if (openMatch !== null) {
                const tableStart = pos + openMatch.index;
                out += segment.slice(pos, tableStart);
                const tableEnd = findMatchingTableEnd(segment, tableStart);
                if (tableEnd !== -1) {
                    const frag = segment.slice(tableStart, tableEnd);
                    out = appendBalancedBlock(out, '', balanceTableFragment(frag, codeSpans));
                    balancedAny = true;
                    pos = tableEnd;
                } else {
                    const frag = segment.slice(tableStart);
                    out = appendBalancedBlock(out, '', balanceTableFragment(frag, codeSpans));
                    balancedAny = true;
                    pos = segment.length;
                }
                continue;
            }

            // Case 3: Mid-table split chunk with neither `<table...>` nor `</table>` in `rest`.
            if (pos === 0 && TABLE_FRAGMENT_DETECTOR_RE.test(rest)) {
                const headingPrefixMatch = rest.match(/^(?:\s*#{1,6}\s+[^\r\n]+\r?\n+)+/);
                const headingPrefix = headingPrefixMatch ? headingPrefixMatch[0] : '';
                const frag = rest.slice(headingPrefix.length);
                if (isMidTableFragment(frag)) {
                    out = appendBalancedBlock(out, headingPrefix, balanceTableFragment(frag, codeSpans));
                    balancedAny = true;
                    break;
                }
            }

            if (out.length > 0 && !out.endsWith('\n') && /^\s*\r?\n/.test(rest)) {
                out += `\n\n${rest.replace(/^\s+/, '')}`;
            } else {
                out += rest;
            }
            break;
        }
        if (balancedAny) {
            if (hasLeadingNewline && !out.startsWith('\n')) {
                out = `\n\n${out}`;
            }
            if (hasTrailingNewline && !out.endsWith('\n')) {
                out = `${out}\n\n`;
            }
        }
        return restoreCodeSpans(out);
    };

    const re = new RegExp(MD_FENCE_RE.source, MD_FENCE_RE.flags);
    let result = '';
    let last = 0;
    let m;
    while ((m = re.exec(text)) !== null) {
        result += processGap(text.slice(last, m.index)) + m[0];
        last = m.index + m[0].length;
    }
    return result + processGap(text.slice(last));
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
    source = balanceHtmlTables(source);
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

