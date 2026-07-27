/*
 * Tests for the pure citation-rendering helpers extracted from thread.js.
 *
 * Dependency-free: run with `node --test src/test/js/`, or via `./mvnw test`, which binds it.
 * marked and DOMPurify are deliberately NOT loaded — the inputs below are hand-written HTML in the
 * shape marked emits, so these tests stay fast and pin OUR transforms rather than the library's.
 * Anything that genuinely needs the real sanitizer or a real browser belongs in
 * ChatFeedbackCitationE2EIT instead.
 */

import { test, describe } from 'node:test';
import assert from 'node:assert/strict';

import {
    formatCitations,
    mapOutsideCode,
    normalizeSpacing,
    protectTokens,
    restoreTokens,
} from '../../main/resources/static/js/chat/citation-render.js';

/** Stand-in for thread.js's escapeHtml — the module takes it as a parameter. */
const escape = (s) => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;')
    .replace(/>/g, '&gt;').replace(/"/g, '&quot;');

const fmt = (html) => formatCitations(html, escape);

const UUID = '4b7b0f51-6293-4cd6-8f4b-5a66adf42742';

describe('code regions are never rewritten', () => {
    test('a fenced java block keeps its array index', () => {
        const html = '<pre><code class="language-java">String s = args[1];</code></pre>';
        assert.equal(fmt(html), html);
    });

    test('inline code keeps its array index', () => {
        const html = '<p>Use <code>items[2]</code> to read it.</p>';
        assert.equal(fmt(html), html);
    });

    test('a citation token inside a fence is left as literal text', () => {
        const html = `<pre><code class="language-sql">SELECT * FROM t WHERE id = '[document_id=${UUID}]';</code></pre>`;
        assert.equal(fmt(html), html);
    });

    test('a bare uuid inside a fence is not truncated to 8 chars', () => {
        const html = `<pre><code>lookup[${UUID}]</code></pre>`;
        assert.equal(fmt(html), html);
    });

    test('mermaid diagram source survives — numeric node labels are not badged', () => {
        // thread.js hands mermaid el.textContent. Badging [1]/[2] here yields "A1 --> B2", which is
        // still VALID mermaid, so the user gets a silently wrong diagram and no error anywhere.
        const html = '<pre class="mermaid">graph TD\n  A[1] --&gt; B[2]</pre>';
        assert.equal(fmt(html), html);
    });

    test('prose around a fence is still formatted', () => {
        const html = '<p>See [1].</p><pre><code>args[1]</code></pre><p>And [2].</p>';
        const out = fmt(html);
        assert.match(out, /<p>See <sup class="citation-ref"[^>]*>1<\/sup>\.<\/p>/);
        assert.match(out, /<p>And <sup class="citation-ref"[^>]*>2<\/sup>\.<\/p>/);
        assert.ok(out.includes('<pre><code>args[1]</code></pre>'), 'fence untouched');
    });

    test('mapOutsideCode reassembles losslessly when the callback is identity', () => {
        const html = '<p>a[1]</p><pre><code>b[2]</code></pre><p>c[3]</p><code>d[4]</code>';
        assert.equal(mapOutsideCode(html, (s) => s), html);
    });
});

describe('numbered reference contract', () => {
    test('a single marker becomes one badge showing only the digit', () => {
        const out = fmt('<p>Stored in DialogObject [1].</p>');
        assert.match(out, /<sup class="citation-ref" title="See reference \[1\]">1<\/sup>/);
        assert.ok(!out.includes('[1]</p>'), 'literal marker replaced');
    });

    test('a grouped marker becomes one badge per number', () => {
        const out = fmt('<p>Evaluated in memory [1, 2].</p>');
        assert.equal((out.match(/citation-ref/g) || []).length, 2);
        assert.ok(!out.includes('[1, 2]'), 'no literal group left behind');
    });

    test('groups without spaces and of three work too', () => {
        assert.equal((fmt('<p>x [1,2]</p>').match(/citation-ref/g) || []).length, 2);
        assert.equal((fmt('<p>x [1, 2, 3]</p>').match(/citation-ref/g) || []).length, 3);
    });

    test('an array index in prose is not badged', () => {
        const out = fmt('<p>Read config[1] from disk.</p>');
        assert.ok(!out.includes('citation-ref'), 'config[1] is not a citation');
    });

    test('adjacent markers still badge, since ] is not a word character', () => {
        assert.equal((fmt('<p>x [1][2]</p>').match(/citation-ref/g) || []).length, 2);
    });

    test('a markdown link label is left alone', () => {
        const out = fmt('<p>[1](https://example.com)</p>');
        assert.ok(!out.includes('citation-ref'), 'link label is not a citation marker');
    });

    test('three or more digits are not reference markers', () => {
        const out = fmt('<p>Error code [100] and [500123].</p>');
        assert.ok(!out.includes('citation-ref'));
        assert.ok(out.includes('[100]') && out.includes('[500123]'), 'left as prose');
    });
});

describe('citation link forms', () => {
    test('a document NAME is not a citation form', () => {
        // Only chunk and document ids identify a source. A name cannot: one corpus lookup
        // matched 131 documents, so [file=…] is left as plain text, not linkified.
        const out = fmt('<p>See [file=ADSAccount.md].</p>');
        assert.ok(!out.includes('class="citation-link"'), out);
        assert.ok(!out.includes('data-file'), out);
    });

    test('chunk_id and document_id become citation links', () => {
        for (const [token, attr] of [
            [`[chunk_id=${UUID}]`, 'data-chunk-id'],
            [`[document_id=${UUID}]`, 'data-document-id'],
        ]) {
            const out = fmt(`<p>Source ${token}.</p>`);
            assert.ok(out.includes('class="citation-link"'), `${token} linkified`);
            assert.ok(out.includes(attr), `${token} carries ${attr}`);
        }
    });

    test('dotted ids are linkified, matching the protect pass charset', () => {
        // The protect regex admitted '.', the link regexes did not — dotted ids survived marked
        // only to render as dead, unclickable text.
        const out = fmt('<p>See [document_id=oim.install.guide.md].</p>');
        assert.ok(out.includes('data-document-id="oim.install.guide.md"'), out);
    });

    test('a bare uuid is linkified with a short label', () => {
        const out = fmt(`<p>See [${UUID}].</p>`);
        assert.ok(out.includes('class="citation-link"'));
        assert.ok(out.includes('>[4b7b0f51]<'), 'label truncated to 8 chars');
    });

    test('id values are escaped into attributes', () => {
        const out = fmt('<p>[document_id=a"b.md]</p>');
        assert.ok(!out.includes('data-document-id="a"b.md"'),
            'quote must not break the attribute');
    });
});

describe('placeholder protect/restore round trip', () => {
    test('citation tokens survive a round trip unchanged', () => {
        const text = `A claim [chunk_id=${UUID}] and a marker [1, 2].`;
        const { safe, placeholders } = protectTokens(text);
        assert.ok(!safe.includes('chunk_id='), 'protected out of the markdown');
        assert.equal(restoreTokens(safe, placeholders), text);
    });

    test('restore order does not leak placeholders when captures nest', () => {
        // Simulates thread.js: the citation pass runs first, then a block protector captures a span
        // that already contains %%CITE_0%%. Restoring forwards re-injected the inner placeholder
        // after its own pass had run, leaving it in the output permanently.
        const placeholders = ['[1]', '<think>see %%CITE_0%% here</think>'];
        const restored = restoreTokens('before %%CITE_1%% after', placeholders);
        assert.equal(restored, 'before <think>see [1] here</think> after');
        assert.ok(!restored.includes('%%CITE_'), 'no placeholder left behind');
    });

    test('a placeholder alone in a paragraph is unwrapped', () => {
        assert.equal(restoreTokens('<p>%%CITE_0%%</p>', ['[1]']), '[1]');
    });
});

describe('markdown spacing normalizer', () => {
    test('a heading run together with prose gets a break', () => {
        assert.equal(normalizeSpacing('text ## Heading'), 'text\n\n## Heading');
    });

    test("a heading's own hashes are never eaten", () => {
        // `([^\n])` matched the heading's first '#', consuming it — so '## X' became '#\n\n# X':
        // a stray empty heading plus a level shift.
        assert.equal(normalizeSpacing('## Heading'), '## Heading');
        assert.equal(normalizeSpacing('### Deep'), '### Deep');
    });

    test('a bash comment inside a fence is not treated as a heading', () => {
        const md = '```bash\ncd /opt # go there\nls\n```';
        assert.equal(normalizeSpacing(md), md);
    });

    test('a reference list line inside a fence is not re-broken', () => {
        const md = '```\n- [1] [chunk_id=abc]\n```';
        assert.equal(normalizeSpacing(md), md);
    });

    test('a reference list line in prose still gets its break', () => {
        assert.equal(normalizeSpacing('Refs: - [1] x'), 'Refs:\n- [1] x');
    });

    test('a table pipe before a hash is not turned into a heading', () => {
        const md = '| col | #1 |';
        assert.equal(normalizeSpacing(md), md);
    });
});
