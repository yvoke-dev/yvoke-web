/*
 * Ingest-form logic: which panels a given ingest kind shows, and client-side upload size checking.
 * The page keeps the DOM work (setting style.display, wiring the submit listener); this decides.
 */

/** Bytes in a megabyte, as the upload limit is expressed. */
const MB = 1024 * 1024;

/** Default client-side upload ceiling. Mirrors the server's multipart limit. */
export const MAX_UPLOAD_BYTES = 200 * MB;

/**
 * Which optional panels an ingest kind shows, and whether a summarize prompt is required.
 *
 * Returned as data rather than applied directly so the combinations are enumerable in a test —
 * the original was four near-identical if/else arms setting three displays plus a `required`
 * flag, where one wrong assignment leaves a hidden field required and blocks submission with no
 * visible cause.
 */
export function panelsFor(kind) {
    const base = { summarize: false, custom: false, jsonImport: false, summarizeRequired: false };
    switch (kind) {
        case 'hierarchical':
            return { ...base, summarize: true, summarizeRequired: true };
        case 'custom':
            return { ...base, custom: true };
        case 'json-import':
            return { ...base, jsonImport: true };
        default:
            return base;
    }
}

/**
 * Validation message for an oversized upload, or null when the file is acceptable.
 *
 * The limit in the text is derived from `maxBytes` rather than hardcoded: the original message
 * said "200 MB" literally, so raising the server limit would have left the UI quoting the old one.
 */
export function fileSizeError(bytes, maxBytes = MAX_UPLOAD_BYTES) {
    if (!(bytes > maxBytes)) return null;
    const actual = (bytes / MB).toFixed(2);
    const limit = Math.round(maxBytes / MB);
    return `Error: The selected file size (${actual} MB) exceeds the ${limit} MB limit.`;
}
