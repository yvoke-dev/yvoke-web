package de.palsoftware.yvoke.llm.core.service;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/**
 * Enforces that provider API endpoints use HTTPS so credentials never travel in cleartext.
 *
 * <p>Plain {@code http://} is permitted strictly for local loopback hosts ({@code localhost},
 * {@code 127.0.0.1}, {@code [::1]}) so ephemeral mock servers in unit tests work without TLS.
 * Loopback matching is purely syntactic — no DNS lookup is ever performed during configuration
 * validation.
 */
final class ProviderEndpointValidator {

    private static final Set<String> LOOPBACK_HOSTS =
            Set.of(
                    "localhost",
                    "127.0.0.1",
                    "[::1]",
                    "::1",
                    "[0:0:0:0:0:0:0:1]",
                    "0:0:0:0:0:0:0:1");

    private ProviderEndpointValidator() {}

    /**
     * Validates that {@code url} is a well-formed {@code https://} URI, or a loopback {@code
     * http://} URI, and returns it unchanged.
     *
     * @throws IllegalArgumentException if the URL is malformed, lacks a host, or uses a non-HTTPS
     *     scheme for a non-loopback host
     */
    static String requireHttpsOrLoopback(String url, String providerName) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    providerName + " endpoint is not a valid URI (must use HTTPS)", e);
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null || host.isBlank()) {
            throw new IllegalArgumentException(
                    providerName + " endpoint must be a complete HTTPS URL with a host");
        }
        String normalizedScheme = scheme.toLowerCase(Locale.ROOT);
        if ("https".equals(normalizedScheme)) {
            return url;
        }
        if ("http".equals(normalizedScheme) && isLoopbackHost(host)) {
            return url;
        }
        throw new IllegalArgumentException(
                providerName
                        + " endpoint must use HTTPS so API credentials are never sent in cleartext "
                        + "(plain HTTP is permitted only for loopback hosts)");
    }

    private static boolean isLoopbackHost(String host) {
        return LOOPBACK_HOSTS.contains(host.toLowerCase(Locale.ROOT));
    }
}
