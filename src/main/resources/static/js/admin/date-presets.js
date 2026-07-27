/*
 * Date-range presets for the cost explorer.
 *
 * The DOM half (reading the <select>, writing the two <input>s) stays in the page; this is the
 * calculation, with `now` injected so it is testable and not tied to the ambient clock.
 *
 * The bug this replaces: the page built local-midnight Dates and then formatted them with
 * toISOString(), which converts to UTC. Anywhere east of UTC that lands on the previous day, so in
 * Europe/Berlin "This month" on 15 Jul 2026 produced 2026-06-30 → 2026-07-30 instead of
 * 2026-07-01 → 2026-07-31 — both bounds wrong, and silently: the page just showed the wrong month's
 * costs. It is correct in UTC, which is why it survived.
 */

/** Formats a Date using its LOCAL calendar fields — never via UTC. */
export function toLocalIsoDate(date) {
    const pad = (n) => String(n).padStart(2, '0');
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

/**
 * Resolves a preset to `{ start, end }` as yyyy-MM-dd strings.
 *
 * An empty string means "unbounded", which is how the backend reads a blank date input. An
 * unrecognised preset leaves the range alone by returning nulls, so the caller can skip writing.
 */
export function presetRange(preset, now) {
    const year = now.getFullYear();
    const month = now.getMonth();

    if (preset === 'this_month') {
        return {
            start: toLocalIsoDate(new Date(year, month, 1)),
            end: toLocalIsoDate(new Date(year, month + 1, 0)),
        };
    }
    if (preset === 'last_month') {
        return {
            start: toLocalIsoDate(new Date(year, month - 1, 1)),
            end: toLocalIsoDate(new Date(year, month, 0)),
        };
    }
    if (preset === 'all') {
        return { start: '', end: '' };
    }
    return { start: null, end: null };
}
