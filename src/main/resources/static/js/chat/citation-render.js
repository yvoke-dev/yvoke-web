/*
 * Citation rendering — the pure string→string half of the chat message pipeline.
 *
 * These functions were extracted from thread.js so they can be tested without a browser: every one
 * of them takes a string and returns a string, with no DOM, no globals and no side effects. The
 * DOM-reading and DOM-writing parts stay in thread.js. See src/test/js/citation-render.test.js.
 *
 * Three regressions in quick succession came from this logic being untestable:
 *   1. grouped markers "[1, 2]" rendered as literal text next to properly badged "[1]" markers;
 *   2. the citation passes rewrote tokens inside code fences, so copied code was corrupted and
 *      mermaid diagrams silently rendered the WRONG graph (A[1] -> B[2] became A1 -> B2, which is
 *      still valid mermaid, so nothing errored);
 *   3. the placeholder restore loop ran forward, permanently leaking %%CITE_n%% markers into the
 *      output whenever one protector's capture swallowed an earlier one's placeholder.
 */

/**
 * Regions of rendered HTML whose text is code and must never be rewritten. marked emits
 * `<pre><code …>…</code></pre>` for fenced blocks and a bare `<code>` for inline code; the `<pre>`
 * alternative comes first so a fenced block is consumed whole rather than leaving its `</pre>`
 * behind. Diagram sources land in `<pre class="mermaid">` and are protected by the same rule.
 */
const CODE_REGION = '<pre\\b[\\s\\S]*?<\\/pre>|<code\\b[\\s\\S]*?<\\/code>';

/** A fenced code block in RAW markdown, before marked has run. */
const MD_FENCE = '```[\\s\\S]*?(?:```|$)|~~~[\\s\\S]*?(?:~~~|$)';

/**
 * Matches `[1]`, `[42]`, `[1, 2]`, `[1,2,3]` — but not `config[1]` (preceded by a word character,
 * so it is an array index) and not `[1](url)` (a markdown link label, which marked must parse).
 *
 * Declared before its first use: this project has been bitten by temporal-dead-zone ordering in
 * chat scripts before.
 */
export const NUMBERED_REF =
    new RegExp('(?<![\\w])\\x5B(\\d{1,2}(?:\\s*,\\s*\\d{1,2})*)\\x5D(?!\\()', 'g');

/**
 * Applies `fn` to every part of `text` that lies OUTSIDE the regions matched by `regionSource`,
 * leaving those regions byte-identical. Returns the reassembled string.
 */
function mapOutsideRegions(text, regionSource, fn) {
    const re = new RegExp(regionSource, 'g');
    let out = '';
    let last = 0;
    let m;
    while ((m = re.exec(text)) !== null) {
        out += fn(text.slice(last, m.index)) + m[0];
        last = m.index + m[0].length;
        if (m[0].length === 0) {
            re.lastIndex++; // never spin on a zero-width match
        }
    }
    return out + fn(text.slice(last));
}

/** Applies `fn` only outside `<pre>`/`<code>` regions of already-rendered HTML. */
export function mapOutsideCode(html, fn) {
    return mapOutsideRegions(html, CODE_REGION, fn);
}

/** Applies `fn` only outside fenced code blocks of raw markdown. */
export function mapOutsideFences(markdown, fn) {
    return mapOutsideRegions(markdown, MD_FENCE, fn);
}

/**
 * Re-inserts the blank lines that models omit around headings, numbered bold list items and
 * reference-list entries. Skips fenced code, where a bash `# comment` or a SQL `-- [id]` line is
 * content, not markdown.
 *
 * The heading rule's preceding-character class excludes `#` — previously `([^\n])` matched the
 * heading's OWN first hash, so a message starting "## X" rendered as a stray empty heading plus a
 * level shift — and `|` so table cells survive. Word characters stay allowed: they are what makes
 * "text ## Heading" split correctly, and the trailing `\s+` already stops "C#5" and "#42" from
 * being mistaken for headings.
 */
export function normalizeSpacing(text) {
    return mapOutsideFences(text, function (segment) {
        let out = segment.replace(/([^\n#|])\s*(#{1,6}\s+)/g, '$1\n\n$2');
        out = out.replace(/([^\n])\s*(\d+\.\s+\*\*)/g, '$1\n\n$2');
        out = out.replace(new RegExp('([^\\n])\\s*(-\\s+\\x5B)', 'g'), '$1\n$2');
        return out;
    });
}

/**
 * Swaps citation tokens for `%%CITE_n%%` placeholders so marked cannot reinterpret them (a bare
 * `[1]` next to a `(` would otherwise parse as a link). Returns the rewritten text plus the
 * ordered originals for {@link restoreTokens}.
 *
 * `patterns` is supplied by the caller so thread.js keeps ownership of the block-level protectors
 * (`<think>`, math, …) that need its own state; this function owns only the citation forms.
 */
export function protectTokens(text) {
    const placeholders = [];
    const push = function (value) {
        const idx = placeholders.length;
        placeholders.push(value);
        return `%%CITE_${idx}%%`;
    };

    // [chunk_id=…], [document_id=…] and bare full-uuid citations. The id character class matches
    // the one used by the link passes below — a mismatch left dotted ids as dead text. A document
    // NAME is not a citation form: it cannot identify a document unambiguously.
    let safe = text.replace(
        new RegExp('\\x5B(?:(?:chunk_id|document_id)=([a-zA-Z0-9_.-]+)|([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}|[0-9a-fA-F]{32}))\\x5D', 'g'),
        function (match) {
            return push(match);
        });

    // Numbered markers [1], [42] and grouped ones [1, 2]. Not preceded by a word character, so
    // `config[1]` is an array index rather than a citation; not followed by `(`, so a markdown
    // link label `[1](url)` is left for marked to parse.
    safe = safe.replace(NUMBERED_REF, function (match) {
        return push(match);
    });

    return { safe: safe, placeholders: placeholders };
}

/**
 * Restores `%%CITE_n%%` placeholders, unwrapping the `<p>` marked may have put around a
 * placeholder that stood alone on its line.
 *
 * Iterates in REVERSE index order. Protectors run in sequence, so a later one (a `<think>` block,
 * a math span) can capture text that already contains an earlier placeholder. Restoring forwards
 * re-injected that inner placeholder *after* its own restore pass had run, leaving a literal
 * `%%CITE_3%%` in the user's answer forever. Reverse order restores the outer capture first, so
 * the inner placeholder is back in the string before its turn comes.
 */
export function restoreTokens(html, placeholders) {
    let out = html;
    for (let idx = placeholders.length - 1; idx >= 0; idx--) {
        const original = placeholders[idx];
        const pWrapped = new RegExp(`<p>\\s*%%CITE_${idx}%%\\s*</p>`, 'g');
        const replaced = out.replace(pWrapped, () => original);
        if (replaced !== out) {
            out = replaced;
        } else {
            out = out.replace(new RegExp(`%%CITE_${idx}%%`, 'g'), () => original);
        }
    }
    return out;
}

/**
 * Turns citation tokens in rendered HTML into clickable links and numbered badges.
 *
 * Runs only outside `<pre>`/`<code>`: these are blunt string replacements, and applying them to
 * code turned `String s = args[1];` into `args1;` on copy and handed mermaid a corrupted — but
 * still syntactically valid — diagram source.
 *
 * `escapeHtml` is injected so the module stays free of DOM dependencies.
 */
export function formatCitations(html, escapeHtml) {
    return mapOutsideCode(html, function (segment) {
        let out = segment;

        out = out.replace(new RegExp('\\x5Bchunk_id=([a-zA-Z0-9_.-]+)\\x5D', 'g'),
            function (match, chunkId) {
                return `<a href="#" class="citation-link" data-action="toggle-citation" data-chunk-id="${escapeHtml(chunkId)}">${match}</a>`;
            });

        out = out.replace(new RegExp('\\x5Bdocument_id=([a-zA-Z0-9_.-]+)\\x5D', 'g'),
            function (match, documentId) {
                return `<a href="#" class="citation-link" data-action="toggle-citation" data-document-id="${escapeHtml(documentId)}">${match}</a>`;
            });

        out = out.replace(
            new RegExp('\\x5B([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}|[0-9a-fA-F]{32})\\x5D', 'g'),
            function (match, uuid) {
                return `<a href="#" class="citation-link" data-action="toggle-citation" data-chunk-id="${escapeHtml(uuid)}">[${uuid.substring(0, 8)}]</a>`;
            });

        // One badge per number, so [1, 2] reads like two adjacent single citations. The
        // .citation-ref margin supplies the spacing between them.
        out = out.replace(NUMBERED_REF, function (match, group) {
            return group.split(',').map(function (num) {
                const n = num.trim();
                return `<sup class="citation-ref" title="See reference [${n}]">${n}</sup>`;
            }).join('');
        });

        return out;
    });
}
