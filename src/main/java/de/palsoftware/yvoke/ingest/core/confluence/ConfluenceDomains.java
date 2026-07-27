package de.palsoftware.yvoke.ingest.core.confluence;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * Canonicalizes a Confluence base URL so that the same site typed two ways yields the same string.
 *
 * <p>
 * This matters because a document's {@code source_file} — which IS its identity — is built from
 * this value: {@code https://Acme.atlassian.net} and {@code https://acme.atlassian.net/} would
 * otherwise mint two full sets of documents for one site, and nothing in the pipeline detects that.
 *
 * <p>
 * Only the case-insensitive parts are folded: scheme and host (DNS). The path is left byte-for-byte
 * because Confluence Data Center legitimately runs under a context path and paths are
 * case-SENSITIVE.
 */
public final class ConfluenceDomains {

    private static final String WIKI_SUFFIX = "/wiki";
    private static final String HTTPS = "https";
    private static final String HTTP = "http";

    private ConfluenceDomains() {}

    /**
     * @param domain the Confluence base URL as entered by an administrator
     * @return the canonical base URL (lower-cased scheme+host, default port dropped, no trailing
     *         slash, path untouched, {@code /wiki} suffix applied as before)
     * @throws IllegalArgumentException if the value is not an absolute http(s) URL with a host
     */
    public static String canonicalize(String domain) {
        if (domain == null || domain.isBlank()) {
            throw new IllegalArgumentException("Confluence base URL must not be blank");
        }
        String trimmed = domain.trim();
        if (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }

        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid Confluence base URL: " + trimmed, e);
        }

        String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!HTTPS.equals(scheme) && !HTTP.equals(scheme)) {
            throw new IllegalArgumentException("Confluence base URL must use http(s): " + trimmed);
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Confluence base URL has no host: " + trimmed);
        }

        StringBuilder base =
            new StringBuilder(scheme).append("://").append(host.toLowerCase(Locale.ROOT));
        int port = uri.getPort();
        if (port != -1 && !isDefaultPort(scheme, port)) {
            base.append(':').append(port);
        }
        // Raw path: percent-encoding and casing are part of the path's identity. Any userinfo,
        // query or fragment is dropped — a base URL carries none of them, and credentials in a
        // stored URL would leak into every log line built from it.
        if (uri.getRawPath() != null) {
            base.append(uri.getRawPath());
        }

        String result = base.toString();
        if (!result.endsWith(WIKI_SUFFIX) && !result.contains(WIKI_SUFFIX + "/")) {
            result = result + WIKI_SUFFIX;
        }
        return result;
    }

    /**
     * Canonicalizes when possible, and otherwise returns the value unchanged (trimmed) instead of
     * throwing.
     *
     * <p>
     * For the READ path only. A stored row may predate canonicalization — restored from an older
     * data-only dump, or hand-edited — and a row mapper that throws takes the whole connector admin
     * page down (a 500 on {@code findAll()}) with no way to reach the form that would fix the
     * value. Writes still go through {@link #canonicalize(String)}, and a value that survives here
     * unchanged is rejected again the moment a client is built from it.
     */
    public static String canonicalizeOrKeep(String domain) {
        try {
            return canonicalize(domain);
        } catch (IllegalArgumentException e) {
            return domain == null ? null : domain.trim();
        }
    }

    private static boolean isDefaultPort(String scheme, int port) {
        return (HTTPS.equals(scheme) && port == 443) || (HTTP.equals(scheme) && port == 80);
    }
}
