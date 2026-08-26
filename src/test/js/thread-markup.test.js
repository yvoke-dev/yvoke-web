/*
 * Tests for the pure markup builders extracted from thread.js.
 *
 * These builders interpolate untrusted text (LLM output, admin playbook fields, corpus filenames)
 * into HTML that is later assigned via innerHTML — outside DOMPurify. So the escaping assertions
 * here are the real subject; the layout assertions are the regression net around them.
 */

import { test, describe } from 'node:test';
import assert from 'node:assert/strict';

import {
    autocompleteOptionsMarkup,
    citationQueryParam,
    clarifyCardActiveMarkup,
    clarifyCardAnsweredMarkup,
    feedbackButtonsMarkup,
    pickTokenCounts,
    preflightCardMarkup,
    streamingStatusText,
    tokenUsageMarkup,
    userMessageMarkup,
} from '../../main/resources/static/js/chat/thread-markup.js';

const XSS = '"><img src=x onerror=alert(1)>';

/**
 * Every builder that takes untrusted text must neutralise this payload.
 *
 * Note what is NOT asserted: the escaped output still contains the literal characters
 * "onerror=alert(1)" as inert text, and that is correct — what makes it harmless is that the
 * angle brackets and quotes are entities, so it can never become a tag or break an attribute.
 */
function assertNeutralised(html, label) {
    assert.ok(!html.includes('<img'), `${label}: raw tag survived — ${html}`);
    assert.ok(!html.includes(XSS), `${label}: payload present unescaped — ${html}`);
    assert.ok(html.includes('&lt;img'), `${label}: expected the payload in escaped form`);
}

describe('autocompleteOptionsMarkup', () => {
    const prompts = [
        { name: 'oim-full', title: 'OIM Full', description: 'Everything' },
        { name: 'oim-db', title: 'OIM DB', description: 'History' },
    ];

    test('marks only the highlighted row active', () => {
        const html = autocompleteOptionsMarkup(prompts, 1);
        const actives = html.match(/prompt-option active/g) || [];
        assert.equal(actives.length, 1);
        assert.ok(html.indexOf('data-index="1"') > 0);
    });

    test('carries name and title in escaped data attributes', () => {
        const html = autocompleteOptionsMarkup(prompts, 0);
        assert.ok(html.includes('data-prompt-name="oim-full"'));
        assert.ok(html.includes('data-prompt-title="OIM Full"'));
    });

    test('escapes admin-authored playbook fields', () => {
        // Playbook name/title/description are admin-authored and reach innerHTML.
        assertNeutralised(
            autocompleteOptionsMarkup([{ name: XSS, title: XSS, description: XSS }], 0),
            'autocomplete');
    });

    test('renders nothing for an empty or missing list', () => {
        assert.equal(autocompleteOptionsMarkup([], 0), '');
        assert.equal(autocompleteOptionsMarkup(undefined, 0), '');
    });

    test('a highlight index past the end simply highlights nothing', () => {
        assert.ok(!autocompleteOptionsMarkup(prompts, 99).includes('prompt-option active'));
    });

    test('renders prototype badge when prototype is true', () => {
        const withProto = [
            { name: 'oim-browsing', title: 'OIM Browsing', description: 'Test', prototype: true },
            { name: 'oim-full', title: 'OIM Full', description: 'Full', prototype: false },
        ];
        const html = autocompleteOptionsMarkup(withProto, 0);
        assert.ok(html.includes('🧪 Prototype'));
        assert.ok(html.includes('badge-warning'));
    });
});

describe('citationQueryParam', () => {
    test('prefers chunk, then document', () => {
        assert.equal(citationQueryParam('c1', 'd1'), 'chunkId=c1');
        assert.equal(citationQueryParam(null, 'd1'), 'documentId=d1');
        assert.equal(citationQueryParam(null, null), '');
    });

    test('percent-encodes the value', () => {
        // Ids are not guaranteed URL-safe; an unencoded & or # truncates the request.
        assert.equal(citationQueryParam(null, 'a b&c#d'), 'documentId=a%20b%26c%23d');
    });

    test('an encoded value cannot inject another parameter', () => {
        const out = citationQueryParam(null, 'x&chunkId=evil');
        assert.ok(!out.includes('&chunkId=evil'), out);
    });
});

describe('clarifying question cards', () => {
    test('the answered card shows the question and the answer', () => {
        const html = clarifyCardAnsweredMarkup('Which version?', '10.0');
        assert.ok(html.includes('Clarification Provided'));
        assert.ok(html.includes('Which version?'));
        assert.ok(html.includes('Clarified: &quot;10.0&quot;') || html.includes('"10.0"'), html);
    });

    test('the answered card escapes both LLM-authored strings', () => {
        assertNeutralised(clarifyCardAnsweredMarkup(XSS, XSS), 'answered card');
    });

    test('the active card renders option chips carrying escaped answers', () => {
        const html = clarifyCardActiveMarkup('Which version?', ['9.3.1', '10.0']);
        // Count the chips, not the `option-chips` wrapper that also contains that substring.
        assert.equal((html.match(/class="option-chip"/g) || []).length, 2);
        assert.ok(html.includes('data-answer="9.3.1"'));
        assert.ok(html.includes('or provide custom response:'));
    });

    test('the active card omits the chip block when there are no options', () => {
        const html = clarifyCardActiveMarkup('Free text?', []);
        assert.ok(!html.includes('option-chips'));
        assert.ok(!html.includes('or provide custom response:'));
        assert.ok(html.includes('question-answer-input'), 'textarea still offered');
    });

    test('the active card escapes option text', () => {
        // Option text is LLM output and lands in a data attribute read back by the handler.
        assertNeutralised(clarifyCardActiveMarkup('q', [XSS]), 'active card');
    });

    test('a missing options list is treated as none', () => {
        assert.ok(!clarifyCardActiveMarkup('q', undefined).includes('option-chips'));
    });
});

describe('preflightCardMarkup', () => {
    test('offers the switch button when a playbook is suggested', () => {
        const html = preflightCardMarkup('Better suited', 'oim-db', 'OIM DB');
        assert.ok(html.includes('data-switch-name="oim-db"'));
        assert.ok(html.includes('Switch to OIM DB'));
        assert.ok(html.includes('Send Anyway'));
    });

    test('offers only Send Anyway when nothing is suggested', () => {
        const html = preflightCardMarkup('No better match', null, null);
        assert.ok(!html.includes('btn-preflight-switch'), html);
        assert.ok(html.includes('Send Anyway'));
    });

    test('escapes the reason and the suggested title', () => {
        assertNeutralised(preflightCardMarkup(XSS, 'n', XSS), 'preflight');
    });
});

describe('userMessageMarkup', () => {
    test('escapes the user content', () => {
        const html = userMessageMarkup('temp-1', XSS, '');
        assertNeutralised(html, 'user message');
    });

    test('includes the playbook tag verbatim when supplied', () => {
        // The tag is built from already-escaped pieces by the caller.
        const html = userMessageMarkup('temp-1', 'hi', '<span class="playbook-tag">OIM</span>');
        assert.ok(html.includes('<span class="playbook-tag">OIM</span>'));
    });

    test('tolerates a missing tag', () => {
        assert.ok(userMessageMarkup('temp-1', 'hi', null).includes('message-user'));
    });
});

describe('feedbackButtonsMarkup', () => {
    test('wires both htmx posts to the same swap target', () => {
        const html = feedbackButtonsMarkup('abc-123');
        assert.ok(html.includes('hx-post="/chat/message/abc-123/feedback?rating=1"'));
        assert.ok(html.includes('hx-post="/chat/message/abc-123/feedback?rating=-1"'));
        assert.equal((html.match(/hx-target="#feedback-container-abc-123"/g) || []).length, 2);
        assert.ok(html.includes('id="feedback-container-abc-123"'), 'target must exist');
    });
});

describe('tokenUsageMarkup', () => {
    test('renders nothing without a positive prompt count', () => {
        assert.equal(tokenUsageMarkup(0, 5, 5), '');
        assert.equal(tokenUsageMarkup(null, 5, 5), '');
        assert.equal(tokenUsageMarkup(undefined, 5, 5), '');
    });

    test('shows the three core counts', () => {
        const html = tokenUsageMarkup(100, 20, 120);
        assert.ok(html.includes('Prompt: <span>100</span>'));
        assert.ok(html.includes('Completion: <span>20</span>'));
        assert.ok(html.includes('Total: <span>120</span>'));
    });

    test('adds cached and thoughts only when positive', () => {
        assert.ok(!tokenUsageMarkup(100, 20, 120, 0, 0).includes('Cached:'));
        const html = tokenUsageMarkup(100, 20, 120, 50, 7);
        assert.ok(html.includes('Cached: <span>50</span>'));
        assert.ok(html.includes('Thoughts: <span>7</span>'));
    });
});

describe('pickTokenCounts', () => {
    test('reads camelCase', () => {
        const t = pickTokenCounts({ promptTokens: 1, completionTokens: 2, totalTokens: 3 });
        assert.equal(t.promptTokens, 1);
        assert.equal(t.totalTokens, 3);
    });

    test('falls back to snake_case', () => {
        // The async status endpoint and the SSE path disagree on casing; a reader that knows only
        // one silently shows a blank usage line.
        const t = pickTokenCounts({ prompt_tokens: 1, completion_tokens: 2, total_tokens: 3 });
        assert.equal(t.promptTokens, 1);
        assert.equal(t.completionTokens, 2);
        assert.equal(t.totalTokens, 3);
    });

    test('prefers camelCase when both are present', () => {
        assert.equal(pickTokenCounts({ promptTokens: 1, prompt_tokens: 9 }).promptTokens, 1);
    });

    test('preserves an explicit zero rather than falling through', () => {
        assert.equal(pickTokenCounts({ promptTokens: 0, prompt_tokens: 9 }).promptTokens, 0);
    });

    test('yields undefined for absent counts', () => {
        assert.equal(pickTokenCounts({}).promptTokens, undefined);
    });
});

describe('streamingStatusText', () => {
    test('defaults to Thinking', () => {
        assert.equal(streamingStatusText(''), 'Thinking');
        assert.equal(streamingStatusText('some prose'), 'Thinking');
        assert.equal(streamingStatusText(null), 'Thinking');
    });

    test('names the tool being called, de-underscored', () => {
        assert.equal(streamingStatusText('🔧 Calling tool: search_corpus'),
            'Running search corpus');
    });

    test('handles the exact format the server emits', () => {
        // RagService.java writes "🔧 *Calling tool:* name(args)" — single asterisks. Pinning the
        // real wire format matters more than any variant we might imagine.
        assert.equal(streamingStatusText('🔧 *Calling tool:* get_section({"id":1})'),
            'Running get section');
    });

    test('reports the most recent tool, not the first', () => {
        const raw = '🔧 *Calling tool:* search_corpus()\n…\n🔧 *Calling tool:* verify_citations()';
        assert.equal(streamingStatusText(raw), 'Running verify citations');
    });
});
