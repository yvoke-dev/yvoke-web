/*
 * Corpus browser: which tags the Tag filter offers for the collection currently picked.
 *
 * The server already scopes this list — DocumentAdminController.tagOptions — but only when it
 * renders the page, which on this form means only after "Apply Filters". So changing the Collection
 * <select> left the previous collection's tags on screen, and you chose a tag from the scope you had
 * just navigated away from. This is that same rule evaluated client-side, off the collection/tags
 * map the page already ships in #collections-data; no request, no new endpoint.
 *
 * It must agree with the server, or the pre-Apply list and the post-Apply list disagree: a named
 * collection gets its declared tags in declaration order (Collection::tags), and "All Collections"
 * gets the de-duplicated union ordered case-insensitively, mirroring
 * `SELECT DISTINCT unnest(tags) FROM collections ORDER BY lower(tag_name)`.
 */

/**
 * Resolves `{ options, selected }` for the Tag select.
 *
 * `currentTag` survives only when the new scope declares it. That is the one deliberate departure
 * from the server, which instead APPENDS an out-of-scope selected tag: the server is describing a
 * query it has already run, so hiding an active filter would make rows vanish with no visible
 * cause, whereas here nothing has been applied yet — the select is the pending filter, so carrying
 * a tag the collection cannot have would only let the user submit a guaranteed-empty search.
 */
export function tagOptionsFor(collectionTags, collection, currentTag) {
    const declared = collectionTags || {};
    const options = collection
        ? (declared[collection] || []).slice()
        : [...new Set(Object.values(declared).flat())]
            .sort((a, b) => a.toLowerCase().localeCompare(b.toLowerCase()));

    return { options, selected: options.includes(currentTag) ? currentTag : '' };
}
