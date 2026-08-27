/*
 * Stops an htmx-driven form from ever being submitted by the browser instead of by htmx.
 *
 * htmx binds swapped-in content during the SETTLE phase, not during the swap, so between
 * `htmx:afterSwap` and `htmx:load` a freshly swapped form is in the DOM, visible, clickable — and
 * not yet htmx-driven. A submit that lands in that window falls through to the browser, and since
 * `<form hx-post=…>` declares no `method` and no `action`, the browser does a GET to the current
 * URL with every field in the query string.
 *
 * That is not a cosmetic fallback. Measured on the chat feedback form: the page reloads, the
 * comment is discarded, and the user's free text ends up in the URL — access logs, browser history,
 * `Referer` — which the feedback fragment explicitly avoids for the thumb buttons and lost here.
 * The window is htmx's ~20ms settle delay and widens under load, so it reads as an occasional
 * "my feedback didn't save" rather than as a reproducible bug.
 *
 * The guard listens on the document in the BUBBLE phase, so it runs after the form's own handlers:
 * `defaultPrevented` is then a reliable read of whether htmx (or anything else) took the event.
 * Fixing it here rather than per-form is deliberate — the exposure belongs to every htmx form the
 * app will ever have, and a rule that each one must also carry a safe `method`/`action` is one more
 * thing a reviewer has to remember.
 */

/**
 * What to do with a submit event.
 *
 * `ignore` — not ours, or htmx already has it.
 * `rescue` — block the browser, hand the form to htmx and re-submit it.
 * `block`  — block the browser and stop. Nothing is sent, but nothing is leaked or lost either,
 *            and the next click works because htmx has settled by then.
 */
export function submitAction(defaultPrevented, isHtmxForm, alreadyRescued, htmxAvailable) {
    if (!isHtmxForm) return 'ignore';
    if (defaultPrevented) return 'ignore';
    if (!htmxAvailable || alreadyRescued) return 'block';
    return 'rescue';
}

/** The htmx verbs that make a form htmx-driven. `data-hx-*` is htmx's own alternate spelling. */
const HX_VERBS = ['hx-post', 'hx-get', 'hx-put', 'hx-patch', 'hx-delete'];

function isHtmxForm(el) {
    if (!el || typeof el.hasAttribute !== 'function') return false;
    return HX_VERBS.some(v => el.hasAttribute(v) || el.hasAttribute('data-' + v));
}

/**
 * Installs the guard on `doc`.
 *
 * `schedule` exists so the deferral is a seam a DOM-free test can observe: it is the one detail
 * that has already been got wrong once, and calling `requestSubmit()` inline fails silently rather
 * than throwing, so nothing else would notice. Production passes a plain task.
 */
export function installSubmitGuard(doc, getHtmx, schedule) {
    const defer = schedule || (fn => setTimeout(fn, 0));
    doc.addEventListener('submit', function (event) {
        const form = event.target;
        const htmx = getHtmx();
        const action = submitAction(event.defaultPrevented, isHtmxForm(form),
            form && form.dataset && form.dataset.htmxRescued === '1', !!htmx);
        if (action === 'ignore') return;

        event.preventDefault();
        if (action === 'block') return;

        form.dataset.htmxRescued = '1';
        htmx.process(form);
        // Deferred, and that is load-bearing rather than tidiness. The HTML form-submission
        // algorithm returns early while the form's "firing submit event" flag is set, so a
        // requestSubmit() from inside a submit handler is a SILENT no-op — it returns normally and
        // fires nothing. Verified in Chromium: re-entrant, one submit event; deferred by a task,
        // two. Getting this wrong keeps the leak fixed and makes the button do nothing at all,
        // which is the same blank symptom for a different reason.
        defer(function () {
            // htmx is bound by now, so this submit is taken by htmx and the guard's own listener
            // sees defaultPrevented and returns early.
            form.requestSubmit();
        });
    });
}

// Guarded so the module can be imported by a Node test, where there is no document.
if (typeof document !== 'undefined') {
    installSubmitGuard(document, () => globalThis.htmx);
}
