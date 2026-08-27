/*
 * Tests for the decision behind the htmx submit guard.
 *
 * The bug it exists for, measured rather than reasoned about: htmx binds swapped-in content during
 * the SETTLE phase, not during the swap. The event traces from `ChatFeedbackCitationE2EIT`, taken
 * with a document-level listener that survives the navigation via sessionStorage, differ in exactly
 * one respect —
 *
 *   pass: afterSwap … htmx:load … afterSettle … CLICK submit … configRequest … SUBMIT prevented=true
 *   fail: afterSwap … CLICK submit … SUBMIT prevented=false … htmx:load … afterSettle
 *
 * — so between the swap and the settle the form is in the DOM, visible and clickable, and NOT yet
 * htmx-driven. A submit landing in that window is handled by the browser: `<form hx-post=…>` names
 * no `method` or `action`, so the browser does a GET to the current URL with every field in the
 * query string. Measured consequence on the feedback form: a full page reload, the comment
 * discarded, and the user's free text written into the URL — which is exactly what the fragment's
 * own comment says must never happen ("the user's free text must not travel in a URL (access logs,
 * browser history, Referer)"), a rule it enforces for the thumb buttons and lost here.
 *
 * The window is ~20ms by default and widens under load, which is why it surfaced as a flaky e2e
 * rather than a bug report. It is not a test artefact: a fast user hits it too.
 *
 * The guard is a document-level BUBBLE listener, so it runs after the form's own handlers and
 * `defaultPrevented` tells it whether anything took the event — the same signal the diagnostic
 * probe validated in both directions before any of this was written.
 */

import { test, describe } from 'node:test';
import assert from 'node:assert/strict';

import { installSubmitGuard, submitAction } from '../../main/resources/static/js/htmx-submit-guard.js';

describe('submitAction', () => {
    test('leaves an ordinary form alone', () => {
        assert.equal(submitAction(false, false, false, true), 'ignore');
    });

    // htmx took the event, which is the overwhelmingly common case. Touching it here would mean
    // cancelling a request htmx has already decided to make.
    test('leaves a submit htmx already handled alone', () => {
        assert.equal(submitAction(true, true, false, true), 'ignore');
    });

    test('rescues an htmx form whose submit nothing intercepted', () => {
        assert.equal(submitAction(false, true, false, true), 'rescue');
    });

    // Blocking without rescuing still beats the native GET: nothing is leaked and nothing is lost,
    // and the next click works because htmx has settled by then.
    test('blocks rather than rescues when htmx is not available at all', () => {
        assert.equal(submitAction(false, true, false, false), 'block');
    });

    // One rescue per form. Re-processing and re-submitting in a loop is the failure mode a naive
    // guard has, and it would be indistinguishable from a hung page.
    test('blocks rather than rescuing twice', () => {
        assert.equal(submitAction(false, true, true, true), 'block');
    });

    // The ordinary-form check comes first: a plain form must stay plain even after some other
    // form on the page has been rescued.
    test('still ignores an ordinary form once something has been rescued', () => {
        assert.equal(submitAction(false, false, true, true), 'ignore');
    });
});

/** Minimal stand-ins: the JS tier runs on bare node, so the DOM is modelled rather than loaded. */
function harness({ defaultPrevented = false, htmxForm = true, htmx = {} } = {}) {
    const calls = { prevented: 0, processed: 0, resubmitted: 0, scheduled: [] };
    let handler = null;
    const doc = { addEventListener: (type, fn) => { if (type === 'submit') handler = fn; } };
    const form = {
        dataset: {},
        hasAttribute: name => htmxForm && name === 'hx-post',
        requestSubmit: () => { calls.resubmitted++; },
    };
    const htmxRef = htmx && { ...htmx, process: () => { calls.processed++; } };
    installSubmitGuard(doc, () => htmxRef, fn => calls.scheduled.push(fn));
    handler({ target: form, defaultPrevented, preventDefault: () => { calls.prevented++; } });
    return { calls, form, runScheduled: () => calls.scheduled.forEach(fn => fn()) };
}

describe('installSubmitGuard', () => {
    /*
     * The re-submit MUST leave the current event dispatch. The HTML form-submission algorithm
     * returns early while the form's "firing submit event" flag is set, so requestSubmit() called
     * from inside a submit handler returns normally and fires nothing at all. Verified in Chromium:
     * re-entrant gives one submit event, deferred by a task gives two.
     *
     * This is not hypothetical — the first version of this guard did exactly that. It blocked the
     * native GET correctly and then silently sent nothing, so the leak was fixed and the button
     * became dead. Both bugs show the user the same blank result, which is why the deferral needs
     * its own assertion rather than being left to the e2e that only fails a third of the time.
     */
    test('schedules the re-submit instead of calling it inside the dispatch', () => {
        const h = harness();
        assert.equal(h.calls.prevented, 1, 'the native submit must be blocked');
        assert.equal(h.calls.processed, 1, 'htmx must be given the form');
        assert.equal(h.calls.resubmitted, 0, 're-submitting inline is a silent no-op');
        assert.equal(h.calls.scheduled.length, 1);

        h.runScheduled();
        assert.equal(h.calls.resubmitted, 1, 'the deferred task must actually re-submit');
    });

    test('does not touch a submit htmx already owns', () => {
        const h = harness({ defaultPrevented: true });
        assert.deepEqual(
            [h.calls.prevented, h.calls.processed, h.calls.scheduled.length], [0, 0, 0]);
    });

    test('does not touch an ordinary form', () => {
        const h = harness({ htmxForm: false });
        assert.deepEqual(
            [h.calls.prevented, h.calls.processed, h.calls.scheduled.length], [0, 0, 0]);
    });

    // No htmx: still block, so the fields never reach the URL, but schedule nothing.
    test('blocks without scheduling when htmx is absent', () => {
        const h = harness({ htmx: null });
        assert.equal(h.calls.prevented, 1);
        assert.equal(h.calls.scheduled.length, 0);
    });

    test('rescues a given form only once', () => {
        const h = harness();
        assert.equal(h.form.dataset.htmxRescued, '1');
    });

    /*
     * The production install passes no scheduler, so every test above — all of which inject a fake
     * one — leaves the real default unexercised. That is the shape of a suite that proves the
     * seam works and says nothing about the code that ships, so the default is driven here through
     * an actual task boundary.
     */
    test('defaults to a real task when no scheduler is injected', async () => {
        let handler = null;
        let resubmitted = 0;
        const doc = { addEventListener: (type, fn) => { if (type === 'submit') handler = fn; } };
        const form = {
            dataset: {},
            hasAttribute: name => name === 'hx-post',
            requestSubmit: () => { resubmitted++; },
        };
        installSubmitGuard(doc, () => ({ process: () => {} }));
        handler({ target: form, defaultPrevented: false, preventDefault: () => {} });

        assert.equal(resubmitted, 0, 'still must not re-submit inside the dispatch');
        await new Promise(resolve => setTimeout(resolve, 0));
        assert.equal(resubmitted, 1, 'the default scheduler must run the re-submit');
    });
});
