/*
 * The Area field on the admin forms (P1-12).
 *
 * Every system prompt, collection, playbook and profile belongs to exactly one area, and each save
 * endpoint takes a required `area` request parameter. A form field the controller never binds is
 * a silent no-op (see the pitfall in CLAUDE.md), and so is the reverse: a form that never sends
 * `area` is refused on every save. The controller half is pinned by the controller tests; this is
 * the template half.
 */

import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const ADMIN = 'src/main/resources/templates/admin';

/** The `<form>` element posting to `action`, as source text. */
function formPostingTo(html, action) {
    const marker = `th:action="@{${action}}"`;
    const at = html.indexOf(marker);
    assert.notEqual(at, -1, `no form posts to ${action}`);
    const start = html.lastIndexOf('<form', at);
    const end = html.indexOf('</form>', at);
    return html.substring(start, end);
}

for (const [page, action] of [
    ['playbooks', '/admin/playbooks'],
    ['prompts', '/admin/prompts'],
    ['orchestrators', '/admin/orchestrators'],
    ['collections', '/admin/collections'],
    ['collections', '/admin/collections/area'],
]) {
    test(`${page}: the form posting to ${action} sends an area`, () => {
        const html = fs.readFileSync(`${ADMIN}/${page}.html`, 'utf8');
        const form = formPostingTo(html, action);
        assert.match(form, /<select[^>]*\bname="area"/);
        assert.match(form, /th:each="a : \$\{areas\}"/);
    });
}

test('every page that calls AreaSelect loads its bridge', () => {
    for (const file of fs.readdirSync(ADMIN)) {
        const html = fs.readFileSync(`${ADMIN}/${file}`, 'utf8');
        if (html.includes('AreaSelect.')) {
            assert.ok(html.includes('/js/admin/area-select-bootstrap.js'), file);
        }
    }
});

test('areas: the area form posts every field the controller binds', () => {
    const html = fs.readFileSync(`${ADMIN}/areas.html`, 'utf8');
    const form = formPostingTo(html, '/admin/areas');
    for (const name of ['originalName', 'name', 'title', 'description', 'prototype',
        'defaultSystemPrompt', 'defaultPlaybook', 'defaultProfile']) {
        assert.match(form, new RegExp(`name="${name}"`), name);
    }
});

test('the admin sidebar links the Areas page', () => {
    const layout = fs.readFileSync(`${ADMIN}/layout.html`, 'utf8');
    assert.ok(layout.includes('th:href="@{/admin/areas}"'));
});
