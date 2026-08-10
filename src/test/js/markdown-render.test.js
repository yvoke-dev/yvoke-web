/*
 * Tests for the markdown render pipeline.
 *
 * Uses the REAL vendored marked (static/js/marked.min.js) loaded into node:vm — no install, no
 * stub. Testing the pipeline against a fake parser would prove nothing about the thing that
 * actually breaks: how our protect/restore passes interact with real markdown output.
 *
 * DOMPurify cannot run headless (it needs a DOM; isSupported is false and sanitize throws), so the
 * sanitizer is injected as a spy. That is not a gap: the only things that can drift on our side are
 * the ADD_TAGS list and which branch is taken, both of which a spy captures. DOMPurify's own
 * behaviour is vendored third-party code and out of scope.
 */

import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

import {
    forceToolCallNewlines,
    installCodeRenderer,
    renderMarkdown,
    wrapToolCalls,
} from '../../main/resources/static/js/chat/markdown-render.js';

/** Loads the vendored marked bundle into a sandbox and returns its export. */
function loadMarked() {
    const src = fs.readFileSync('src/main/resources/static/js/marked.min.js', 'utf8');
    const sandbox = { window: {}, globalThis: null, console };
    sandbox.globalThis = sandbox;
    vm.createContext(sandbox);
    vm.runInContext(src, sandbox);
    const marked = sandbox.marked || (sandbox.window && sandbox.window.marked);
    assert.ok(marked && typeof marked.parse === 'function', 'vendored marked failed to load');
    return marked;
}

const marked = loadMarked();
installCodeRenderer(marked);

/** Records what reached the sanitizer, and passes it through unchanged. */
function spySanitizer() {
    const calls = [];
    const fn = (html) => {
        calls.push(html);
        return html;
    };
    fn.calls = calls;
    return fn;
}

function render(text, isStreaming = false) {
    const sanitize = spySanitizer();
    const html = renderMarkdown(text, isStreaming, { parse: (s) => marked.parse(s), sanitize });
    return { html, sanitize };
}

describe('the vendored parser is real', () => {
    test('marked loads and parses', () => {
        assert.equal(marked.parse('# h').trim(), '<h1>h</h1>');
    });
});

describe('code blocks and mermaid', () => {
    test('a finished mermaid block becomes a diagram container', () => {
        const { html } = render('```mermaid\ngraph TD\n  A[1] --> B[2]\n```', false);
        assert.ok(html.includes('<pre class="mermaid">'), html);
        assert.ok(html.includes('mermaid-diagram-container'));
    });

    test('a streaming mermaid block stays a plain code block', () => {
        // A half-arrived diagram cannot render; showing source beats showing an error.
        const { html } = render('```mermaid\ngraph TD\n  A[1] --> B[2]\n```', true);
        assert.ok(html.includes('language-mermaid'), html);
        assert.ok(!html.includes('<pre class="mermaid">'));
    });

    test('the streaming flag is per-call, not sticky', () => {
        // It used to live on window; a leaked `true` would make every later message render its
        // diagrams as plain code with nothing to indicate why.
        render('```mermaid\ngraph TD\n A-->B\n```', true);
        const { html } = render('```mermaid\ngraph TD\n A-->B\n```', false);
        assert.ok(html.includes('<pre class="mermaid">'), 'flag leaked from the streaming render');
    });

    test('the flag resets even when the parser throws', () => {
        const boom = () => { throw new Error('parser exploded'); };
        assert.throws(() => renderMarkdown('x', true, { parse: boom, sanitize: (h) => h }));
        const { html } = render('```mermaid\ngraph TD\n A-->B\n```', false);
        assert.ok(html.includes('<pre class="mermaid">'), 'flag stuck after a parser throw');
    });

    test('mermaid source keeps its numeric node labels', () => {
        // Badging [1]/[2] here yields "A1 --> B2" — valid mermaid, silently wrong diagram.
        const { html } = render('```mermaid\ngraph TD\n  A[1] --> B[2]\n```', false);
        assert.ok(html.includes('A[1]') && html.includes('B[2]'), html);
    });

    test('an unlabelled fence that looks like mermaid is detected', () => {
        const { html } = render('```\nsequenceDiagram\n  A->>B: hi\n```', false);
        assert.ok(html.includes('<pre class="mermaid">'), html);
    });

    test('a java fence keeps its array index', () => {
        const { html } = render('```java\nString s = args[1];\n```', false);
        assert.ok(html.includes('args[1]'), html);
    });

    test('indented text is not turned into a code block', () => {
        // The tokenizer disables 4-space code blocks: ingested manuals are full of indented raw
        // markup that would otherwise be swallowed into <pre><code>.
        const { html } = render('Normal paragraph\n\n    indented but not code\n', false);
        assert.ok(!html.includes('<code>indented'), html);
    });
});

describe('math protection', () => {
    test('display math \\[ … \\] survives the parser', () => {
        // The old pattern compiled to the character class {backslash, s, S} rather than "any
        // char", so it never matched and display math was never protected.
        const { html } = render('Given \\[x = \\frac{a}{b}\\] we get y.', false);
        assert.ok(html.includes('\\[x = \\frac{a}{b}\\]'), html);
    });

    test('inline math \\( … \\) survives', () => {
        const { html } = render('Given \\(x = a/b\\) we get y.', false);
        assert.ok(html.includes('\\(x = a/b\\)'), html);
    });

    test('block math $$ … $$ survives', () => {
        const { html } = render('$$\n\\sum_{i=1}^{n} i\n$$', false);
        assert.ok(html.includes('\\sum_{i=1}^{n} i'), html);
    });

    test('math containing markdown characters is not reformatted', () => {
        const { html } = render('\\[a_1 * b_2 _emphasis_ \\]', false);
        assert.ok(html.includes('a_1 * b_2 _emphasis_'), 'underscores became <em>: ' + html);
    });
});

describe('think blocks', () => {
    test('a closed think block is preserved verbatim', () => {
        const { html } = render('before <think>reasoning</think> after', false);
        assert.ok(html.includes('<think>reasoning</think>'), html);
    });

    test('an unclosed block is closed and rendered when finished', () => {
        const { html } = render('before <think>reasoning', false);
        assert.ok(html.includes('<think>reasoning</think>'), html);
    });

    test('an unclosed block while streaming is pre-rendered and marked', () => {
        const { html } = render('before <think>**bold** reasoning', true);
        assert.ok(html.includes('streaming-think'), html);
        assert.ok(html.includes('<strong>bold</strong>'), 'inner markdown was parsed');
    });
});

describe('tool-call handling', () => {
    test('forceToolCallNewlines puts a marker on its own line', () => {
        assert.equal(forceToolCallNewlines('text 🔧 Calling tool'), 'text\n\n🔧 Calling tool');
    });

    test('forceToolCallNewlines leaves fenced code alone', () => {
        const md = '```\nlog("🔧 Calling tool")\n```';
        assert.equal(forceToolCallNewlines(md), md);
    });

    test('wrapToolCalls wraps chatter outside code', () => {
        const out = wrapToolCalls('<p>🔧 Calling tool: search</p><p>answer</p>');
        assert.ok(out.includes('<span class="tool-call">'), out);
    });

    test('wrapToolCalls never injects into a code block', () => {
        // The pattern runs to the next block boundary; inside a fence it would swallow real answer
        // text into a display:none span, and the answer would just vanish.
        const html = '<pre><code>🔧 Calling tool: not really</code></pre><p>answer</p>';
        assert.equal(wrapToolCalls(html), html);
    });

    test('a marker inside an inline code span starts no run', () => {
        // Inline code is masked rather than skipped, so it cannot END a run (see below) — but it
        // must still not START one, which is the protection skipping used to give for free.
        const html = '<p>Type <code>🔧 Calling tool:</code> to see it, then read on.</p>';
        assert.equal(wrapToolCalls(html), html);
    });
});

/*
 * The desktop agent's sub-agent calls quote a sandbox path in backticks — `C:\…\tool-results\
 * mcp-yvoke-get_section-<ts>.txt` — so the arguments of a tool call routinely contain inline code.
 *
 * wrapToolCalls used to run through mapOutsideCode, which excises <pre> AND inline <code> and maps
 * only what lies between. That cut one tool call into three: the half carrying the 🔧 marker was
 * wrapped and hidden, the <code> was skipped by design, and the text after it had no marker left to
 * match — so everything from the first backtick to the end of the paragraph stayed on screen, with
 * the "🔧 Calling tool:" prefix that would have explained it hidden by hide-thinking-process. Live,
 * one answer showed four orphaned paths and their argument prose mid-answer.
 *
 * These render through the real parser: the bug lives in how marked's OUTPUT is segmented, so
 * hand-written HTML would not have caught the shape that actually occurs.
 */
describe('tool calls whose arguments contain inline code', () => {
    const PATH = 'C:\\Users\\jr\\.claude\\projects\\sandbox\\tool-results\\'
        + 'mcp-yvoke-get_section-1785915065041.txt';
    const CALL = '🔧 *Calling tool:* Agent({"description":"Grep web portal config",'
        + '"prompt":"Search the file at `' + PATH + '` for content related to:'
        + '\\n\\n1. Any mention of hierarchy"})';

    /** The production chain for a finished message (thread.js:696-704). */
    const renderAndWrap = (text) => wrapToolCalls(render(text).html);

    /** What is left on screen once the hidden .tool-call spans are removed. */
    const visibleText = (html) => html
        .replace(/<span class="tool-call">[\s\S]*?<\/span>/g, '')
        .replace(/<[^>]+>/g, '')
        .replace(/&quot;/g, '"')
        .replace(/\s+/g, ' ')
        .trim();

    const spans = (html) =>
        [...html.matchAll(/<span class="tool-call">([\s\S]*?)<\/span>/g)].map((m) => m[1]);

    test('nothing of the call survives on screen', () => {
        const html = renderAndWrap(CALL + '\n\nReal answer paragraph.\n');
        assert.equal(visibleText(html), 'Real answer paragraph.');
    });

    test('the path itself is not left behind', () => {
        // The single most visible symptom: a Windows sandbox path sitting in the middle of an
        // answer about One Identity Manager.
        const html = renderAndWrap(CALL + '\n\nReal answer paragraph.\n');
        assert.ok(!visibleText(html).includes('tool-results'), visibleText(html));
    });

    test('the argument prose after the path is not left behind', () => {
        const html = renderAndWrap(CALL + '\n\nReal answer paragraph.\n');
        assert.ok(!visibleText(html).includes('for content related to'), visibleText(html));
        assert.ok(!visibleText(html).includes('Any mention of hierarchy'), visibleText(html));
    });

    test('the code span ends up inside the hidden wrapper, not beside it', () => {
        const html = renderAndWrap(CALL + '\n\nReal answer paragraph.\n');
        const wrapped = spans(html);
        assert.equal(wrapped.length, 1, html);
        assert.ok(wrapped[0].includes('<code>'), 'code span left outside the wrapper: ' + html);
        assert.ok(wrapped[0].includes('tool-results'), html);
    });

    test('consecutive calls each hide whole', () => {
        const html = renderAndWrap(CALL + '\n\n' + CALL + '\n\nReal answer paragraph.\n');
        assert.equal(visibleText(html), 'Real answer paragraph.');
        assert.equal(spans(html).length, 2, html);
    });

    test('a fenced block after a tool call is still answer content', () => {
        // Why inline <code> could not simply be added to the run's boundary set, and why <pre>
        // must keep terminating a run: a fence following a tool call belongs to the answer, and
        // swallowing it into a display:none span makes the answer vanish with no error anywhere.
        const md = '🔧 *Calling tool:* search({"q":"x"})\n\n```java\nString s = args[1];\n```\n\nDone.\n';
        const visible = visibleText(renderAndWrap(md));
        assert.ok(visible.includes('String s = args[1];'), visible);
        assert.ok(visible.includes('Done.'), visible);
    });

    test('a mermaid diagram after a tool call is still rendered', () => {
        const md = '🔧 *Calling tool:* search({"q":"x"})\n\n```mermaid\ngraph TD\n  A-->B\n```\n';
        const html = renderAndWrap(md);
        assert.ok(html.includes('<pre class="mermaid">'), html);
        assert.ok(!spans(html).some((s) => s.includes('mermaid')), 'diagram was hidden: ' + html);
    });

    test('inline code in ordinary prose is untouched', () => {
        const html = renderAndWrap('The `Department` view is not a table.\n');
        assert.ok(html.includes('<code>Department</code>'), html);
        assert.ok(!html.includes('%%TOOLCODE'), 'a mask placeholder leaked: ' + html);
    });
});

describe('pipeline contract', () => {
    test('sanitize is the last step and receives the assembled html', () => {
        const { html, sanitize } = render('# Heading\n\nBody [1].', false);
        assert.equal(sanitize.calls.length >= 1, true);
        assert.equal(sanitize.calls[sanitize.calls.length - 1], html,
            'the returned html must be exactly what the sanitizer last saw');
    });

    test('empty input short-circuits without calling the parser', () => {
        let parsed = false;
        const out = renderMarkdown('', false,
            { parse: () => { parsed = true; return ''; }, sanitize: (h) => h });
        assert.equal(out, '');
        assert.equal(parsed, false);
    });

    test('citation tokens are still plain text on return', () => {
        // The caller adds the trusted, regex-constrained links afterwards; if renderMarkdown
        // linkified them, they would be sanitized as untrusted markup.
        const { html } = render('A claim [chunk_id=4b7b0f51-6293-4cd6-8f4b-5a66adf42742].', false);
        assert.ok(html.includes('[chunk_id=4b7b0f51-6293-4cd6-8f4b-5a66adf42742]'), html);
        assert.ok(!html.includes('citation-link'), 'renderMarkdown must not linkify');
    });

    test('no placeholder ever leaks into the output', () => {
        const messy = 'a [1] b <think>t [2] u</think> c \\[x\\] d $$y$$ e '
            + '[chunk_id=4b7b0f51-6293-4cd6-8f4b-5a66adf42742] f\n\n```\ncode [3]\n```';
        const { html } = render(messy, false);
        assert.ok(!html.includes('%%CITE_'), html);
    });

    test('headings run together with prose are separated', () => {
        const { html } = render('text ## Heading', false);
        assert.match(html, /<h2[^>]*>Heading<\/h2>/);
    });
});

/*
 * These go through the REAL parser on purpose. The spacing normalizer is a string→string function
 * pinned unit-side in citation-render.test.js, but what it costs when it is wrong is a whole GFM
 * table — and only marked can show that. A live answer rendered its checklist as `<p>|</p>`, an
 * <h1> holding the header row, and every remaining row run together in one paragraph.
 */
describe('GFM tables survive the pipeline', () => {
    const TABLE = '| # | What to check | Where |\n'
        + '|---|---|---|\n'
        + '| 1 | **Approval policy** is assigned | Manager |\n'
        + '| 2 | The process is **active** | Designer |\n';

    /** Header cells marked emitted, or [] when the table did not parse. */
    function headers(html) {
        // `<th(?:\s…)?>` and not `<th[^>]*>`: the latter also matches `<thead>`.
        return [...html.matchAll(/<th(?:\s[^>]*)?>([\s\S]*?)<\/th>/g)].map((m) => m[1]);
    }

    test('a "#" header cell still renders a table', () => {
        const { html } = render('## What to Verify\n\n' + TABLE, false);
        assert.deepEqual(headers(html), ['#', 'What to check', 'Where']);
        assert.ok(!/<h1[^>]*>/.test(html), 'the header row became a heading: ' + html);
        assert.ok(!html.includes('|---|'), 'delimiter row leaked as text: ' + html);
    });

    test('every row of a "#" table becomes a row, not a paragraph', () => {
        const { html } = render(TABLE, false);
        assert.equal((html.match(/<tr>/g) || []).length, 3, html);
        assert.ok(html.includes('<strong>Approval policy</strong>'), 'cell markdown lost: ' + html);
    });

    // A split BODY row still leaves a valid `| a |` row behind, so counting <tr> passes while the
    // cell's text has been thrown out of the table entirely. Assert the cell instead.
    test('a numbered bold item in a cell stays in its cell', () => {
        const md = '| step | what |\n|---|---|\n| a | 1. **do it** |\n';
        const { html } = render(md, false);
        assert.match(html, /<td>1\. <strong>do it<\/strong><\/td>/, html);
    });

    test('a reference-list marker in a cell stays in its cell', () => {
        const md = '| source | note |\n|---|---|\n| a | - [1] see there |\n';
        const { html } = render(md, false);
        assert.match(html, /<td>- \[1\] see there<\/td>/, html);
    });

    test('a lone hash in a cell does not break the table', () => {
        const md = '| fix | note |\n|---|---|\n| a | use # to comment the line |\n';
        const { html } = render(md, false);
        assert.equal((html.match(/<tr>/g) || []).length, 2, html);
        assert.ok(!/<h1[^>]*>/.test(html), html);
    });

    test('a half-arrived header row is safe mid-stream', () => {
        // renderMarkdown runs on every SSE chunk, and a partial row has no closing pipe — so
        // MD_TABLE_ROW cannot match it and the character-class guard is the only thing covering
        // this case. Without it the user watches the row shatter as it arrives.
        const { html } = render('| # | What to ch', true);
        assert.ok(html.includes('| # | What to ch'), html);
        assert.ok(!/<h1[^>]*>/.test(html), html);
    });

    test('a table inside a fence is still code', () => {
        const { html } = render('```\n' + TABLE + '```', false);
        assert.ok(html.includes('<pre><code'), html);
        assert.ok(!html.includes('<table>'), html);
    });

    test('a heading directly after a table is still a heading', () => {
        const { html } = render(TABLE + '\n## After\n', false);
        assert.ok(html.includes('<table>'), html);
        assert.match(html, /<h2[^>]*>After<\/h2>/);
    });

    test('a language name ending in # keeps its hash', () => {
        const { html } = render('The script is written in C# code.', false);
        assert.ok(html.includes('C# code'), html);
        assert.ok(!/<h1[^>]*>/.test(html), 'C# became a heading: ' + html);
    });
});
