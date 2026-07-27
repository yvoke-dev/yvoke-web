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
