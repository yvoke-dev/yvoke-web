/*
 * Tests for the three small admin modules: date presets, job status, ingest form.
 *
 * The date-preset suite is the reason this file exists. Its bug was invisible in UTC and wrong
 * everywhere east of it, so the assertions pin LOCAL calendar dates and the suite is meaningful
 * only when run under a non-UTC zone as well — see the TZ note on the first test.
 */

import { test, describe } from 'node:test';
import assert from 'node:assert/strict';

import { presetRange, toLocalIsoDate }
    from '../../main/resources/static/js/admin/date-presets.js';
import { capitalize, getStatusClass, isStepCompleted, isTerminalStatus }
    from '../../main/resources/static/js/admin/job-status.js';
import { fileSizeError, MAX_UPLOAD_BYTES, panelsFor }
    from '../../main/resources/static/js/admin/ingest-form.js';
import { tagOptionsFor }
    from '../../main/resources/static/js/admin/corpus-tag-filter.js';

describe('date presets', () => {
    // 15 Jul 2026, local midnight. Under Europe/Berlin the old toISOString() formatting turned
    // this month's bounds into 2026-06-30 / 2026-07-30 — both off by one, silently.
    const midJuly = new Date(2026, 6, 15);

    test('this_month spans the whole local month', () => {
        assert.deepEqual(presetRange('this_month', midJuly),
            { start: '2026-07-01', end: '2026-07-31' });
    });

    test('last_month spans the whole previous local month', () => {
        assert.deepEqual(presetRange('last_month', midJuly),
            { start: '2026-06-01', end: '2026-06-30' });
    });

    test('all clears both bounds', () => {
        assert.deepEqual(presetRange('all', midJuly), { start: '', end: '' });
    });

    test('an unknown preset leaves the range untouched', () => {
        assert.deepEqual(presetRange('custom', midJuly), { start: null, end: null });
    });

    test('handles a 28-day February', () => {
        assert.deepEqual(presetRange('this_month', new Date(2026, 1, 10)),
            { start: '2026-02-01', end: '2026-02-28' });
    });

    test('handles a leap February', () => {
        assert.deepEqual(presetRange('this_month', new Date(2028, 1, 10)),
            { start: '2028-02-01', end: '2028-02-29' });
    });

    test('rolls back across a year boundary', () => {
        assert.deepEqual(presetRange('last_month', new Date(2026, 0, 15)),
            { start: '2025-12-01', end: '2025-12-31' });
    });

    test('is correct on the first and last day of a month', () => {
        // The boundary days are where a UTC-conversion bug shows up most readily.
        assert.deepEqual(presetRange('this_month', new Date(2026, 6, 1)),
            { start: '2026-07-01', end: '2026-07-31' });
        assert.deepEqual(presetRange('this_month', new Date(2026, 6, 31)),
            { start: '2026-07-01', end: '2026-07-31' });
    });

    test('toLocalIsoDate zero-pads month and day', () => {
        assert.equal(toLocalIsoDate(new Date(2026, 0, 5)), '2026-01-05');
    });

    test('toLocalIsoDate never shifts the day, whatever the zone offset', () => {
        // A local-midnight date must format as that same calendar day. Formatting via
        // toISOString() is what broke this.
        const d = new Date(2026, 6, 1);
        assert.equal(toLocalIsoDate(d), '2026-07-01');
        assert.equal(d.getDate(), 1, 'sanity: the Date itself is the 1st locally');
    });
});

describe('job status', () => {
    test('maps each status to its badge class', () => {
        assert.equal(getStatusClass('queued'), 'badge-warning');
        assert.equal(getStatusClass('running'), 'badge-info');
        assert.equal(getStatusClass('completed'), 'badge-success');
        assert.equal(getStatusClass('failed'), 'badge-danger');
    });

    test('a cancelled job is muted, not danger', () => {
        // An operator stopping a job (or bulk-cancelling a repointed connector's queue) is not a
        // failure; hundreds of red rows would hide the genuine ones.
        assert.equal(getStatusClass('cancelled'), 'badge-muted');
        assert.equal(getStatusClass('CANCELLED'), 'badge-muted');
    });

    test('is case-insensitive and falls back for unknown statuses', () => {
        assert.equal(getStatusClass('COMPLETED'), 'badge-success');
        assert.equal(getStatusClass('mystery'), 'badge-muted');
    });

    test('terminal statuses are completed, failed and cancelled', () => {
        assert.equal(isTerminalStatus('completed'), true);
        assert.equal(isTerminalStatus('FAILED'), true);
        assert.equal(isTerminalStatus('running'), false);
        assert.equal(isTerminalStatus('queued'), false);
    });

    test('cancelled is terminal, so the SSE stream closes and Stop disappears', () => {
        // Mirrors JobStatus.isTerminal() on the server. Left out, the job-detail page keeps the
        // EventSource open forever on a job nothing can stop.
        assert.equal(isTerminalStatus('cancelled'), true);
        assert.equal(isTerminalStatus('CANCELLED'), true);
    });

    const ORDER = ['extract', 'chunk', 'embed', 'index'];

    test('a completed job shows every step done', () => {
        assert.equal(isStepCompleted('index', 'index', 'completed', ORDER), true);
    });

    test('a failed job shows no step done', () => {
        assert.equal(isStepCompleted('extract', 'index', 'failed', ORDER), false);
    });

    test('steps before the current one are done, the current one is not', () => {
        assert.equal(isStepCompleted('extract', 'embed', 'running', ORDER), true);
        assert.equal(isStepCompleted('chunk', 'embed', 'running', ORDER), true);
        assert.equal(isStepCompleted('embed', 'embed', 'running', ORDER), false);
        assert.equal(isStepCompleted('index', 'embed', 'running', ORDER), false);
    });

    test('a step the server never declared is not painted as done', () => {
        // indexOf gives -1 for an unknown name, and the original compared it numerically:
        // -1 < 2 is true, so a step missing from stepDbValues rendered as COMPLETED.
        assert.equal(isStepCompleted('ghost-step', 'embed', 'running', ORDER), false);
    });

    test('an unknown current step marks nothing done', () => {
        assert.equal(isStepCompleted('extract', 'ghost', 'running', ORDER), false);
    });

    test('an empty or missing step order is safe', () => {
        assert.equal(isStepCompleted('extract', 'embed', 'running', []), false);
        assert.equal(isStepCompleted('extract', 'embed', 'running', undefined), false);
    });

    test('capitalize handles empty input', () => {
        assert.equal(capitalize('running'), 'Running');
        assert.equal(capitalize(''), '');
        assert.equal(capitalize(null), '');
    });
});

describe('ingest form', () => {
    test('hierarchical shows summarize and requires a prompt', () => {
        assert.deepEqual(panelsFor('hierarchical'),
            { summarize: true, custom: false, jsonImport: false, summarizeRequired: true });
    });

    test('custom and json-import show one panel each and require nothing', () => {
        assert.deepEqual(panelsFor('custom'),
            { summarize: false, custom: true, jsonImport: false, summarizeRequired: false });
        assert.deepEqual(panelsFor('json-import'),
            { summarize: false, custom: false, jsonImport: true, summarizeRequired: false });
    });

    test('an unknown kind hides everything and requires nothing', () => {
        // A hidden-but-required field blocks submission with no visible cause.
        assert.deepEqual(panelsFor('nope'),
            { summarize: false, custom: false, jsonImport: false, summarizeRequired: false });
    });

    test('exactly one panel is ever visible', () => {
        for (const kind of ['hierarchical', 'custom', 'json-import', 'nope']) {
            const p = panelsFor(kind);
            const shown = [p.summarize, p.custom, p.jsonImport].filter(Boolean).length;
            assert.ok(shown <= 1, `${kind} showed ${shown} panels`);
        }
    });

    test('only hierarchical requires a summarize prompt, and it shows the panel', () => {
        for (const kind of ['hierarchical', 'custom', 'json-import', 'nope']) {
            const p = panelsFor(kind);
            if (p.summarizeRequired) {
                assert.ok(p.summarize, `${kind} requires a prompt in a hidden panel`);
            }
        }
    });

    test('a file at or under the limit passes', () => {
        assert.equal(fileSizeError(1024), null);
        assert.equal(fileSizeError(MAX_UPLOAD_BYTES), null, 'exactly at the limit is allowed');
    });

    test('an oversized file is rejected with both sizes named', () => {
        const msg = fileSizeError(250 * 1024 * 1024);
        assert.match(msg, /250\.00 MB/);
        assert.match(msg, /200 MB limit/);
    });

    test('the quoted limit follows maxBytes rather than being hardcoded', () => {
        // The original text said "200 MB" literally, so raising the server limit would have left
        // the UI quoting the old one.
        assert.match(fileSizeError(60 * 1024 * 1024, 50 * 1024 * 1024), /50 MB limit/);
    });

    test('a zero-byte file passes', () => {
        assert.equal(fileSizeError(0), null);
    });
});

/*
 * Corpus browser tag filter.
 *
 * The server scopes the Tag <select> to the browsed collection, but only at render time — so until
 * this existed, picking a collection left the old collection's tags on screen until you pressed
 * Apply, and the list you chose from was the one for the collection you had just navigated away
 * from. This is the same scoping rule evaluated client-side, so it must agree with
 * DocumentAdminController.tagOptions exactly or the pre-Apply and post-Apply lists disagree.
 */
describe('corpus browser tag filter', () => {
    const DECLARED = {
        'OIM - Custom - Install Kit': ['9.3.1', '10.0'],
        'OIM - DB - History': ['schema', 'content'],
        'OIM - KB': [],
    };

    test('a collection offers exactly the tags it declares, in declaration order', () => {
        assert.deepEqual(tagOptionsFor(DECLARED, 'OIM - Custom - Install Kit', ''),
            { options: ['9.3.1', '10.0'], selected: '' });
    });

    test('all collections offers the union, de-duplicated and sorted case-insensitively', () => {
        // Mirrors the server's `SELECT DISTINCT unnest(tags) … ORDER BY lower(tag_name)`.
        assert.deepEqual(tagOptionsFor(DECLARED, '', '').options,
            ['10.0', '9.3.1', 'content', 'schema']);
    });

    test('a collection that declares nothing offers nothing', () => {
        assert.deepEqual(tagOptionsFor(DECLARED, 'OIM - KB', ''), { options: [], selected: '' });
    });

    test('an unknown collection offers nothing rather than throwing', () => {
        assert.deepEqual(tagOptionsFor(DECLARED, 'No Such Collection', ''),
            { options: [], selected: '' });
    });

    test('a tag the new collection still declares stays selected', () => {
        assert.equal(tagOptionsFor(DECLARED, 'OIM - Custom - Install Kit', '10.0').selected, '10.0');
    });

    /*
     * Dropping is the honest move here, and it is the one case where this deliberately does NOT
     * mirror the server. The server appends an out-of-scope selectedTag because its render must
     * never show "All Tags" over a query that IS filtered. Before Apply nothing is applied yet —
     * the select IS the pending filter — so keeping 9.3.1 while browsing a collection that has no
     * such tag would only let the user submit a filter guaranteed to return nothing.
     */
    test('a tag the new collection does not declare is cleared, not carried over', () => {
        const r = tagOptionsFor(DECLARED, 'OIM - DB - History', '9.3.1');
        assert.equal(r.selected, '');
        assert.ok(!r.options.includes('9.3.1'), 'the stale tag must not linger in the list');
    });

    test('a missing or empty map degrades to no options instead of throwing', () => {
        assert.deepEqual(tagOptionsFor({}, '', ''), { options: [], selected: '' });
        assert.deepEqual(tagOptionsFor(undefined, 'Anything', 'x'),
            { options: [], selected: '' });
    });
});
