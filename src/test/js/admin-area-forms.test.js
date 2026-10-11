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

import { filterSystemPromptsForArea, applySystemPrompt, updateRoleState } from '../../main/resources/static/js/admin/playbook-prompt.js';

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

test('every page that calls PlaybookPrompt loads its bridge', () => {
    for (const file of fs.readdirSync(ADMIN)) {
        const html = fs.readFileSync(`${ADMIN}/${file}`, 'utf8');
        if (html.includes('PlaybookPrompt.')) {
            assert.ok(html.includes('/js/admin/playbook-prompt-bootstrap.js'), file);
        }
    }
});

test('playbooks: applySystemPrompt preserves a cross-area stored prompt and does not wipe it', () => {
    const html = fs.readFileSync(`${ADMIN}/playbooks.html`, 'utf8');
    assert.ok(html.includes('/js/admin/playbook-prompt-bootstrap.js'));
    assert.match(html, /PlaybookPrompt\?\.filterSystemPromptsForArea/);
    assert.match(html, /PlaybookPrompt\?\.applySystemPrompt/);

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

    applySystemPrompt(select, 'p-oim', 'PingID', false);
    assert.equal(select.value, 'p-oim');
    assert.equal(options[1].disabled, false);
    assert.equal(options[1].textContent, 'p-oim (area: OIM)');
});

test('playbooks: (area: X) annotation cleans up when the prompt is in-area', () => {
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

    // Step 1: cross-area edit -> annotated
    applySystemPrompt(select, 'p-oim', 'PingID', false);
    assert.equal(options[1].textContent, 'p-oim (area: OIM)');

    // Step 2: edit moves to a playbook in OIM (where p-oim is in-area) -> annotation cleaned up
    applySystemPrompt(select, 'p-oim', 'OIM', false);
    assert.equal(options[1].textContent, 'p-oim');
    assert.equal(options[1].disabled, false);
    assert.equal(options[1].dataset.annotated, undefined);

    // Step 3: changing area to PingID without preserving p-oim hides/disables it
    filterSystemPromptsForArea(select, 'PingID', null);
    assert.equal(options[1].hidden, true);
    assert.equal(options[1].disabled, true);
    assert.equal(options[1].textContent, 'p-oim');
});

test('playbooks: updateRoleState disables and clears prompt for orchestrator and reviewer', () => {
    const select = { value: 'some-prompt', disabled: false };

    updateRoleState(select, 'orchestrator', false);
    assert.equal(select.disabled, true);
    assert.equal(select.value, '');

    select.value = 'some-prompt';
    updateRoleState(select, 'reviewer', false);
    assert.equal(select.disabled, true);
    assert.equal(select.value, '');

    updateRoleState(select, 'specialist', false);
    assert.equal(select.disabled, false);
});

test('playbooks: applySystemPrompt creates dynamic option for unlisted/inactive prompts', () => {
    const options = [
        { value: '', textContent: '(Default / None)', dataset: {}, getAttribute: () => '' }
    ];
    let selectedIndex = 0;
    const select = {
        options,
        get selectedIndex() { return selectedIndex; },
        set selectedIndex(i) { selectedIndex = i; },
        get value() { return selectedIndex >= 0 ? options[selectedIndex].value : ''; },
        set value(v) {
            selectedIndex = options.findIndex(o => o.value === v);
        },
        appendChild(opt) {
            options.push(opt);
        }
    };

    applySystemPrompt(select, 'unlisted-prompt', 'OIM', false);
    assert.equal(select.value, 'unlisted-prompt');
    assert.equal(options.length, 2);
    assert.equal(options[1].value, 'unlisted-prompt');
    assert.equal(options[1].textContent, 'unlisted-prompt (inactive / not CHAT)');
    assert.equal(options[1].dataset.dynamic, 'true');

    // Re-applying with empty clears dynamic option
    applySystemPrompt(select, '', 'OIM', false);
    assert.equal(select.value, '');
    assert.equal(options.length, 1);
});

test('playbooks: null/undefined elements are tolerated safely', () => {
    assert.doesNotThrow(() => {
        filterSystemPromptsForArea(null, 'OIM', null);
        applySystemPrompt(null, 'p-oim', 'OIM', false);
        updateRoleState(null, 'orchestrator', false);
    });
});
