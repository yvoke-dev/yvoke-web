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

test('playbooks: applySystemPrompt preserves a cross-area stored prompt and does not wipe it', () => {
    const html = fs.readFileSync(`${ADMIN}/playbooks.html`, 'utf8');
    assert.match(html, /filterSystemPromptsForArea\([^)]*preserveValue/);

    const options = [
        { value: '', textContent: '(Default / None)', dataset: {}, getAttribute: () => '' },
        { value: 'p-oim', textContent: 'p-oim', dataset: {}, getAttribute: (attr) => attr === 'data-area' ? 'OIM' : null },
        { value: 'p-ping', textContent: 'p-ping', dataset: {}, getAttribute: (attr) => attr === 'data-area' ? 'PingID' : null }
    ];
    let selectedIndex = 0;
    const select = {
        options,
        get selectedIndex() { return selectedIndex; },
        set selectedIndex(i) { selectedIndex = i; },
        get value() { return selectedIndex >= 0 ? options[selectedIndex].value : ''; },
        set value(v) {
            selectedIndex = options.findIndex(o => o.value === v);
        }
    };
    const areaInput = { value: 'PingID' };

    function filterSystemPromptsForArea(selectedArea, preserveValue) {
        const currentArea = (selectedArea || areaInput.value || '').trim().toLowerCase();
        for (let i = 0; i < select.options.length; i++) {
            const opt = select.options[i];
            if (!opt.value || opt.dataset.dynamic === 'true') {
                opt.hidden = false;
                opt.disabled = false;
                continue;
            }
            const optArea = (opt.getAttribute('data-area') || '').trim().toLowerCase();
            const isPreserved = !!preserveValue && opt.value === preserveValue;
            const matches = !optArea || !currentArea || optArea === currentArea;
            opt.hidden = !matches && !isPreserved;
            opt.disabled = !matches && !isPreserved;
            if (isPreserved && !matches && !opt.dataset.annotated) {
                opt.textContent = opt.textContent + ' (area: ' + (opt.getAttribute('data-area') || 'none') + ')';
                opt.dataset.annotated = 'true';
            }
        }
        if (select.selectedIndex >= 0 && select.options[select.selectedIndex].disabled) {
            select.value = '';
        }
    }

    function applySystemPrompt(value) {
        select.value = value || '';
        filterSystemPromptsForArea(areaInput.value, value);
    }

    applySystemPrompt('p-oim');
    assert.equal(select.value, 'p-oim');
    assert.equal(options[1].disabled, false);
    assert.equal(options[1].textContent, 'p-oim (area: OIM)');
});
