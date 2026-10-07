/*
 * Matching an imported file's `area:` to the Area select on the playbook, prompt and profile
 * admin pages.
 *
 * Every item belongs to exactly one area. A file is loaded in the browser and fills the form, so
 * the area it names has to become the select's value; the server matches area names ignoring case,
 * so this does too, and returns the option's own spelling.
 */

/**
 * The option value naming the same area as `value`, or null when there is none (no value, or an
 * area that does not exist yet).
 */
export function matchArea(optionValues, value) {
    if (value == null) {
        return null;
    }
    const wanted = String(value).trim().replace(/^['"]|['"]$/g, '').trim().toLowerCase();
    if (!wanted) {
        return null;
    }
    return optionValues.find((option) => option.toLowerCase() === wanted) ?? null;
}
