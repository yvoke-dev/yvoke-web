package de.palsoftware.yvoke.rag.core.websearch;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Domain and path-prefix allow-list policy for the {@code WebSearch} tool.
 *
 * <p>
 * Enforces host and path-prefix scoping both when building the Google Search {@code site:} query
 * and when post-filtering grounding URLs returned by Gemini.
 */
public final class WebSearchPolicy {

    public static final int MAX_QUERY_LENGTH = 500;

    private WebSearchPolicy() {}

    /**
     * Normalized allow-list entry combining a lower-case DNS hostname and an optional lower-case
     * segment-aligned path prefix (either {@code ""} for whole-domain scope or
     * {@code "/segment..."} with no trailing slash).
     */
    public record AllowEntry(String host, String path) {}

    /**
     * Normalizes a domain or URL-like host input to a bare lower-case DNS hostname, or returns
     * {@code ""} if the input is null, blank, an IP literal, single-label, or malformed.
     */
    public static String normalizeDomain(String entry) {
        if (entry == null) {
            return "";
        }
        String s = entry.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) {
            return "";
        }
        if (s.startsWith("https://")) {
            s = s.substring("https://".length());
        } else if (s.startsWith("http://")) {
            s = s.substring("http://".length());
        } else if (s.contains("://")) {
            return "";
        }

        int boundary = firstPathQueryFragmentOrBackslash(s);
        if (boundary >= 0) {
            s = s.substring(0, boundary);
        }

        if (s.contains("@") || s.contains("[") || s.contains("]")) {
            return "";
        }

        int colon = s.indexOf(':');
        if (colon >= 0) {
            String portPart = s.substring(colon + 1);
            if (portPart.isEmpty() || !isAllDigits(portPart)) {
                return "";
            }
            s = s.substring(0, colon);
        }

        while (s.startsWith("*.") || s.startsWith(".")) {
            s = s.startsWith("*.") ? s.substring(2) : s.substring(1);
        }
        while (s.endsWith(".")) {
            s = s.substring(0, s.length() - 1);
        }

        if (!isValidHostname(s)) {
            return "";
        }
        return s;
    }

    /**
     * Parses a single allow-list entry (such as {@code "support.oneidentity.com"} or
     * {@code "www.oneidentity.com/community/"}) into a normalized {@link AllowEntry}, or returns
     * {@code null} when the entry is invalid.
     */
    public static AllowEntry parseAllowEntry(String entry) {
        if (entry == null) {
            return null;
        }
        String trimmed = entry.trim();
        if (trimmed.isEmpty() || trimmed.contains("\\")) {
            return null;
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        String withoutScheme;
        if (lower.startsWith("https://")) {
            withoutScheme = trimmed.substring("https://".length());
        } else if (lower.startsWith("http://")) {
            withoutScheme = trimmed.substring("http://".length());
        } else if (lower.contains("://")) {
            return null;
        } else {
            withoutScheme = trimmed;
        }

        int qOrHash = firstQueryOrFragment(withoutScheme);
        if (qOrHash >= 0) {
            withoutScheme = withoutScheme.substring(0, qOrHash);
        }

        int slash = withoutScheme.indexOf('/');
        String hostPart = slash >= 0 ? withoutScheme.substring(0, slash) : withoutScheme;
        String rawPath = slash >= 0 ? withoutScheme.substring(slash) : "";

        String host = normalizeDomain(hostPart);
        if (host.isEmpty()) {
            return null;
        }
        String normalizedPath = normalizeAllowPath(rawPath);
        if (normalizedPath == null) {
            return null;
        }
        return new AllowEntry(host, normalizedPath);
    }

    /**
     * Parses a comma-separated allow-list string into deduplicated {@link AllowEntry} values in
     * declaration order, discarding invalid entries.
     */
    public static List<AllowEntry> parseAllowEntries(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        Set<AllowEntry> unique = new LinkedHashSet<>();
        for (String token : csv.split(",")) {
            AllowEntry parsed = parseAllowEntry(token);
            if (parsed != null) {
                unique.add(parsed);
            }
        }
        return List.copyOf(unique);
    }

    /**
     * Verifies that {@code rawUrl} is a valid {@code http}/{@code https} URL whose host matches
     * (exact or subdomain) and whose path is within the segment boundary of at least one entry in
     * {@code allowedEntries}.
     */
    public static boolean isUrlDomainAllowed(String rawUrl, List<AllowEntry> allowedEntries) {
        if (rawUrl == null || rawUrl.isBlank() || allowedEntries == null
            || allowedEntries.isEmpty()) {
            return false;
        }
        String trimmed = rawUrl.trim();
        if (trimmed.contains("\\")) {
            return false;
        }

        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException e) {
            return false;
        }

        String scheme = uri.getScheme();
        if (scheme == null
            || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            return false;
        }
        if (uri.getRawUserInfo() != null) {
            return false;
        }

        String host = normalizeDomain(uri.getHost());
        if (host.isEmpty()) {
            return false;
        }

        String urlPath = normalizeCandidateUrlPath(uri.getRawPath());
        if (urlPath == null) {
            return false;
        }

        for (AllowEntry entry : allowedEntries) {
            if (entry == null) {
                continue;
            }
            String entryHost = normalizeDomain(entry.host());
            if (entryHost.isEmpty()) {
                continue;
            }
            boolean hostMatches = host.equals(entryHost) || host.endsWith("." + entryHost);
            if (hostMatches && isPathInScope(urlPath, entry.path())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Builds a domain-scoped Google Search query by appending {@code (site:... OR site:...)}
     * clauses for every entry in {@code allowedEntries}. Fails closed with
     * {@link IllegalStateException} if {@code allowedEntries} is null or empty.
     */
    public static String buildScopedQuery(String query, List<AllowEntry> allowedEntries) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("WebSearch query must not be blank");
        }
        String trimmedQuery = query.trim();
        if (trimmedQuery.length() > MAX_QUERY_LENGTH) {
            throw new IllegalArgumentException(
                "WebSearch query exceeds maximum length of " + MAX_QUERY_LENGTH + " characters");
        }
        if (allowedEntries == null || allowedEntries.isEmpty()) {
            throw new IllegalStateException("WebSearch allowedEntries must not be empty");
        }

        List<String> clauses = new ArrayList<>(allowedEntries.size());
        for (AllowEntry entry : allowedEntries) {
            if (entry == null) {
                continue;
            }
            String host = normalizeDomain(entry.host());
            String path = normalizeAllowPath(entry.path());
            if (!host.isEmpty() && path != null) {
                clauses.add("site:" + host + path);
            }
        }
        if (clauses.isEmpty()) {
            throw new IllegalStateException(
                "WebSearch allowedEntries must contain at least one valid entry");
        }
        return trimmedQuery + " (" + String.join(" OR ", clauses) + ")";
    }

    private static boolean isPathInScope(String urlPath, String allowedPath) {
        if (allowedPath == null || allowedPath.isEmpty() || allowedPath.equals("/")) {
            return true;
        }
        String base = normalizeAllowPath(allowedPath);
        if (base == null) {
            return false;
        }
        if (base.isEmpty()) {
            return true;
        }
        return urlPath.equals(base) || urlPath.startsWith(base + "/");
    }

    private static String normalizeAllowPath(String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) {
            return "";
        }
        String lower = rawPath.trim().toLowerCase(Locale.ROOT);
        if (lower.isEmpty()) {
            return "";
        }
        if (containsEncodedTraversalOrSlash(lower) || lower.contains("\\")) {
            return null;
        }
        String collapsed = lower.replaceAll("/+", "/");
        if (!collapsed.startsWith("/")) {
            collapsed = "/" + collapsed;
        }
        if (collapsed.length() > 1 && collapsed.endsWith("/")) {
            collapsed = collapsed.substring(0, collapsed.length() - 1);
        }
        if (collapsed.equals("/")) {
            return "";
        }
        for (String segment : collapsed.substring(1).split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                return null;
            }
        }
        return collapsed;
    }

    private static String normalizeCandidateUrlPath(String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) {
            return "/";
        }
        String lower = rawPath.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("/") || lower.contains("//") || lower.contains("\\")
            || containsEncodedTraversalOrSlash(lower)) {
            return null;
        }
        String[] segments = lower.substring(1).split("/", -1);
        for (int i = 0; i < segments.length; i++) {
            String seg = segments[i];
            if (seg.equals(".") || seg.equals("..")) {
                return null;
            }
        }
        if (lower.length() > 1 && lower.endsWith("/")) {
            return lower.substring(0, lower.length() - 1);
        }
        return lower;
    }

    private static boolean containsEncodedTraversalOrSlash(String lower) {
        return lower.contains("%2e") || lower.contains("%2f") || lower.contains("%5c");
    }

    private static boolean isValidHostname(String host) {
        if (host.isEmpty() || !host.contains(".") || host.contains("..")) {
            return false;
        }
        String[] labels = host.split("\\.", -1);
        if (labels.length < 2) {
            return false;
        }
        for (String label : labels) {
            if (label.isEmpty()) {
                return false;
            }
            char first = label.charAt(0);
            char last = label.charAt(label.length() - 1);
            if (!isAsciiAlphanumeric(first) || !isAsciiAlphanumeric(last)) {
                return false;
            }
            for (int i = 0; i < label.length(); i++) {
                char c = label.charAt(i);
                if (!isAsciiAlphanumeric(c) && c != '-') {
                    return false;
                }
            }
        }
        return !isAllDigits(labels[labels.length - 1]);
    }

    private static boolean isAsciiAlphanumeric(char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
    }

    private static boolean isAllDigits(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private static int firstPathQueryFragmentOrBackslash(String s) {
        int min = -1;
        for (char delimiter : new char[] {'/', '?', '#', '\\'}) {
            int idx = s.indexOf(delimiter);
            if (idx >= 0 && (min < 0 || idx < min)) {
                min = idx;
            }
        }
        return min;
    }

    private static int firstQueryOrFragment(String s) {
        int q = s.indexOf('?');
        int h = s.indexOf('#');
        if (q >= 0 && h >= 0) {
            return Math.min(q, h);
        }
        return Math.max(q, h);
    }
}
