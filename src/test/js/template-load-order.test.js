/*
 * Load-order rules for the inline <script> blocks in Thymeleaf templates.
 *
 * The admin pages keep classic inline scripts and reach their extracted logic through a tiny
 * `<script type="module">` that assigns `window.X` (see the module/deferred pitfall in CLAUDE.md).
 * Module scripts are ALWAYS deferred: they run after every classic script but before
 * DOMContentLoaded. The known form of that trap is a top-level init call, and the existing code
 * already defends against it.
 *
 * This file pins the OTHER form, which is not visible by reading the script top to bottom: a
 * classic script that starts an ASYNCHRONOUS PRODUCER — an EventSource, a WebSocket, a poll timer —
 * whose callback reaches the bridge. The call site looks late (it is a callback) and is not: the
 * producer is created during parsing, and its first event can be dispatched while the parser is
 * still working through the rest of the document, so the callback can beat the deferred module.
 *
 * That is not a theoretical ordering. `JobApiController.progress` sends a snapshot event the moment
 * a client subscribes, so every non-terminal job page races its own bridge on first paint, and it
 * was observed losing: `AdminJobProgressE2EIT` failed with
 * `TypeError: Cannot read properties of undefined (reading 'getStatusClass')` thrown from inside
 * the EventSource handler.
 *
 * The rule is therefore about WHERE the producer is created, not where the bridge is read: creating
 * it inside a DOMContentLoaded callback makes the race unrepresentable, because the module is
 * guaranteed to have run by then. A race cannot be pinned by a browser test — the e2e that caught
 * this passes far more often than it fails — so the property is asserted over the source, which is
 * where it actually lives.
 *
 * The second rule below is the same concern one layer out: what a page does before its JavaScript
 * has taken effect. htmx binds swapped-in content at SETTLE rather than at swap, so any htmx form
 * has a window in which the browser, not htmx, owns its submit — and `<form hx-post=…>` with no
 * `method`/`action` degrades to a GET that puts every field in the URL. `htmx-submit-guard.js`
 * closes that for the whole app, which only works if every layout that loads htmx also loads it.
 */

import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';

const TEMPLATES = 'src/main/resources/templates';

/** Asynchronous producers that keep calling back long after the statement that created them. */
const PRODUCERS = [/\bnew\s+EventSource\s*\(/g, /\bnew\s+WebSocket\s*\(/g];

function templates(dir) {
    return fs.readdirSync(dir, { recursive: true })
        .map(String)
        .filter(f => f.endsWith('.html'))
        .map(f => path.join(dir, f))
        .sort();
}

/** Classic inline <script> bodies, with their offset in the file, for error messages. */
function classicScripts(html) {
    const out = [];
    const re = /<script([^>]*)>([\s\S]*?)<\/script>/gi;
    let m;
    while ((m = re.exec(html)) !== null) {
        const attrs = m[1];
        if (/\bsrc\s*=|\bth:src\s*=/i.test(attrs)) continue;
        if (/\btype\s*=\s*["']module["']/i.test(attrs)) continue;
        const type = /\btype\s*=\s*["']([^"']+)["']/i.exec(attrs);
        if (type && !/javascript/i.test(type[1])) continue;
        if (m[2].trim() === '') continue;
        out.push(m[2]);
    }
    return out;
}

/**
 * Index ranges of every `addEventListener('DOMContentLoaded', …)` callback body.
 *
 * Brace-matches while skipping strings, template literals and comments, so a `{` inside a message
 * or a regex-looking string cannot close a block early and silently shrink the range — which would
 * make the assertion below pass for the wrong reason.
 */
function domReadyRanges(src) {
    const ranges = [];
    const listener = /addEventListener\s*\(\s*['"]DOMContentLoaded['"]/g;
    let m;
    while ((m = listener.exec(src)) !== null) {
        const open = src.indexOf('{', m.index);
        if (open === -1) continue;
        const close = matchBrace(src, open);
        if (close !== -1) ranges.push([open, close]);
    }
    return ranges;
}

/** Index of the `}` closing the `{` at `open`, or -1. Skips strings and comments. */
function matchBrace(src, open) {
    let depth = 0;
    for (let i = open; i < src.length; i++) {
        const c = src[i];
        if (c === '/' && src[i + 1] === '/') {
            i = src.indexOf('\n', i);
            if (i === -1) return -1;
        } else if (c === '/' && src[i + 1] === '*') {
            i = src.indexOf('*/', i + 2);
            if (i === -1) return -1;
            i++;
        } else if (c === '"' || c === "'" || c === '`') {
            i = endOfString(src, i);
            if (i === -1) return -1;
        } else if (c === '{') {
            depth++;
        } else if (c === '}') {
            depth--;
            if (depth === 0) return i;
        }
    }
    return -1;
}

/** Index of the quote closing the string opened at `start`, or -1. Honours backslash escapes. */
function endOfString(src, start) {
    const quote = src[start];
    for (let i = start + 1; i < src.length; i++) {
        if (src[i] === '\\') {
            i++;
        } else if (src[i] === quote) {
            return i;
        }
    }
    return -1;
}

function producerSites(src) {
    const sites = [];
    for (const re of PRODUCERS) {
        re.lastIndex = 0;
        let m;
        while ((m = re.exec(src)) !== null) sites.push({ index: m.index, text: m[0].trim() });
    }
    return sites;
}

describe('a classic inline script starts no async producer before DOMContentLoaded', () => {
    const found = [];
    for (const file of templates(TEMPLATES)) {
        for (const body of classicScripts(fs.readFileSync(file, 'utf8'))) {
            for (const site of producerSites(body)) {
                found.push({ file, body, site });
            }
        }
    }

    // Without this the suite would go green the day someone renames the templates directory, or
    // rewrites job-detail.html in a way this walk stops recognising — the exact shape of "a short
    // result rather than a wrong one" that CLAUDE.md § 6 warns about.
    test('the walk actually finds the producers it exists to police', () => {
        assert.ok(
            found.some(f => f.file.endsWith('job-detail.html')),
            `expected the job-detail EventSource, found: ${found.map(f => f.file).join(', ') || 'nothing'}`,
        );
    });

    for (const { file, body, site } of found) {
        const label = `${file.replace(TEMPLATES + '/', '')} — ${site.text}`;
        test(label, () => {
            const inside = domReadyRanges(body)
                .some(([open, close]) => site.index > open && site.index < close);
            assert.ok(
                inside,
                `${site.text} is created at classic-script time. Its callbacks can then run before `
                + 'the deferred module that assigns the window bridge, so the first event throws '
                + 'and the live UI stops updating. Create it inside a DOMContentLoaded callback.',
            );
        });
    }
});

describe('every layout that loads htmx also loads the submit guard', () => {
    const GUARD = 'js/htmx-submit-guard.js';
    const withHtmx = templates(TEMPLATES)
        .map(file => ({ file, html: fs.readFileSync(file, 'utf8') }))
        .filter(t => /js\/htmx\.min\.js/.test(t.html));

    // Two files load htmx today. A walk that found none would make the rule vacuous, and a walk
    // that silently found fewer than exist is how a new layout ships unguarded.
    test('the walk finds the layouts that load htmx', () => {
        assert.ok(withHtmx.length >= 2,
            `expected the htmx-loading layouts, found ${withHtmx.map(t => t.file).join(', ')}`);
    });

    for (const { file, html } of withHtmx) {
        test(file.replace(TEMPLATES + '/', ''), () => {
            assert.ok(
                html.includes(GUARD),
                `loads htmx but not ${GUARD}. Until htmx settles a swapped-in form, the browser `
                + 'owns its submit and turns it into a GET carrying every field in the URL.',
            );
        });
    }
});
