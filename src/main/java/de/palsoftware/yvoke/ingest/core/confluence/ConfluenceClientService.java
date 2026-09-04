package de.palsoftware.yvoke.ingest.core.confluence;

import de.palsoftware.yvoke.shared.security.SecretCipher;
import de.palsoftware.yvoke.shared.security.SecretDecryptionException;
import jakarta.annotation.Nullable;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.DateTimeException;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Talks to Confluence on behalf of ONE {@link ConfluenceInstance} per call.
 *
 * <p>
 * Every entry point takes the instance explicitly. Nothing here reads a global configuration: a
 * deployment connects several sites at once, and a service that remembers "the" connector silently
 * sends one site's crawl through another site's credentials.
 *
 * <p>
 * Both caches are therefore keyed by instance: the {@link RestClient} by instance id (plus a
 * signature over the connection fields, so an edited domain/e-mail/token takes effect immediately
 * instead of at the next restart), and the display-name cache by (instance, account id) — the same
 * Atlassian account id exists on unrelated sites, and a single-keyed cache would render one site's
 * person as another's.
 */
@Service
public class ConfluenceClientService {

    private static final Logger log = LoggerFactory.getLogger(ConfluenceClientService.class);

    private static final int TOO_MANY_REQUESTS = 429;

    private static final Pattern DATE_PREFIX_PATTERN = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2})");

    private final SecretCipher secretCipher;

    private final RestClient.Builder restClientBuilder;

    private final int retryMaxAttempts;
    private final long retryInitialBackoffMs;
    private final long retryMaxBackoffMs;

    /**
     * Per-instance clients, so a sync neither rebuilds the client per call nor shares one across
     * sites. {@link CachedClient#signature()} pins the connection fields the client was built from.
     */
    private final Map<UUID, CachedClient> clientCache = new ConcurrentHashMap<>();

    private final Map<UserCacheKey, String> userDisplayNameCache = new ConcurrentHashMap<>();

    public ConfluenceClientService(SecretCipher secretCipher,
        @Qualifier("confluenceRestClientBuilder") RestClient.Builder restClientBuilder,
        @Value("${app.confluence.retry.max-attempts}") int retryMaxAttempts,
        @Value("${app.confluence.retry.initial-backoff-ms}") long retryInitialBackoffMs,
        @Value("${app.confluence.retry.max-backoff-ms}") long retryMaxBackoffMs) {
        this.secretCipher = secretCipher;
        this.restClientBuilder = restClientBuilder;
        this.retryMaxAttempts = Math.max(1, retryMaxAttempts);
        this.retryInitialBackoffMs = Math.max(0, retryInitialBackoffMs);
        this.retryMaxBackoffMs = Math.max(0, retryMaxBackoffMs);
    }

    /** A built client plus the connection fields it was built from. */
    private record CachedClient(String signature, RestClient client) {}

    /**
     * Account ids are only unique WITHIN a site: {@code 557058:abc} on two connected Confluence
     * instances is two different people.
     */
    private record UserCacheKey(UUID instanceId, String accountId) {}

    /**
     * The base URL every request for {@code instance} is issued against.
     *
     * <p>
     * KNOWN LIMITATION — Confluence Data Center under a context path is not supported.
     * {@link ConfluenceDomains#canonicalize} appends {@code /wiki}, which is the correct REST base
     * for Atlassian Cloud but wrong for a Data Center install served from a context path:
     * {@code https://wiki.example.com/Confluence} becomes {@code .../Confluence/wiki}, and every
     * call 404s. The rule is kept identical to the one the previous singleton configuration used so
     * existing corpora keep their {@code source_file} identity (which is derived from this value);
     * supporting DC means storing whether a site is Cloud or DC, which is a schema change, not a
     * configuration flag.
     */
    private static String baseUrl(ConfluenceInstance instance) {
        return ConfluenceDomains.canonicalize(instance.domain());
    }

    /**
     * Decrypts the instance's stored API token.
     *
     * <p>
     * Failures name the instance and its {@link TokenHealth} — never the token, and never a generic
     * "connector is not configured", which with several instances says nothing about which one is
     * broken or what to do about it.
     *
     * @throws IllegalStateException if no token is stored, or the stored ciphertext cannot be read
     *         with the currently configured key
     */
    public String resolveApiToken(ConfluenceInstance instance) {
        TokenHealth health = instance.tokenHealth(secretCipher.keyId());
        if (health == TokenHealth.MISSING) {
            throw new IllegalStateException("Confluence instance '" + instance.name()
                + "': no API token is stored (token health: " + health
                + "); enter the token on the connectors page.");
        }
        try {
            return secretCipher.decryptOrThrow(instance.apiTokenEnc());
        } catch (SecretDecryptionException e) {
            throw new IllegalStateException("Confluence instance '" + instance.name()
                + "': stored API token cannot be decrypted (APP_SECRET_KEY rotated?) (token health: "
                + health + "); re-enter the token on the connectors page.", e);
        }
    }

    /**
     * The (cached) client for {@code instance}. An unsaved instance — the admin form's "test
     * connection" before the row exists — gets an uncached client, because there is no id to key
     * on.
     */
    public RestClient getClient(ConfluenceInstance instance) {
        UUID instanceId = instance.id();
        if (instanceId == null) {
            return getClient(instance.domain(), instance.email(), resolveApiToken(instance));
        }
        String signature = connectionSignature(instance);
        CachedClient cached = clientCache.get(instanceId);
        if (cached != null && cached.signature().equals(signature)) {
            return cached.client();
        }
        RestClient client =
            getClient(instance.domain(), instance.email(), resolveApiToken(instance));
        clientCache.put(instanceId, new CachedClient(signature, client));
        // A changed domain points at a different site, where the cached account ids mean different
        // people (or nothing at all).
        userDisplayNameCache.keySet().removeIf(key -> instanceId.equals(key.instanceId()));
        return client;
    }

    /**
     * Drops everything cached for one instance: its {@link RestClient} — which carries
     * {@code Basic base64(email:plaintextToken)} as a DEFAULT HEADER — and every display name
     * resolved through it.
     *
     * <p>
     * Without this, a deleted instance's client (and a revoked token with it) stayed reachable in
     * the heap until the next restart, and the display-name cache only ever shrank when a client
     * happened to be rebuilt.
     */
    public void evict(UUID instanceId) {
        if (instanceId == null) {
            return;
        }
        clientCache.remove(instanceId);
        userDisplayNameCache.keySet().removeIf(key -> instanceId.equals(key.instanceId()));
    }

    /**
     * The repository announces a deleted instance / cleared credential; this is what acts on it.
     * Keeping the cache a listener rather than a repository dependency preserves the layering
     * (repositories do not call services).
     */
    @EventListener
    public void onInstanceCredentialsChanged(ConfluenceInstanceCredentialsChangedEvent event) {
        evict(event.instanceId());
    }

    /**
     * Only the fields the client is built from. The ciphertext is compared, never the plaintext, so
     * no decrypted secret is retained just to detect a change.
     */
    private static String connectionSignature(ConfluenceInstance instance) {
        return instance.domain() + "\n" + instance.email() + "\n" + instance.apiTokenEnc();
    }

    public RestClient getClient(String domain, String email, String apiToken) {
        String base = ConfluenceDomains.canonicalize(domain);
        // ConfluenceDomains validates the URL's shape; it performs NO SSRF check, so the network
        // guard stays here, on the path that actually builds a client.
        assertSafeConfluenceUrl(base);
        String authHeader = "Basic " + Base64.getEncoder()
            .encodeToString((email + ":" + apiToken).getBytes(StandardCharsets.UTF_8));

        // Derive from the shared pooled builder so Confluence calls reuse connections and inherit
        // the configured connect/read timeouts (rather than the un-pooled, un-timed default JDK
        // client).
        return restClientBuilder.clone().baseUrl(base).defaultHeader("Authorization", authHeader)
            .defaultHeader("Accept", "application/json").build();
    }

    private static void assertSafeConfluenceUrl(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid Confluence URL: " + url);
        }
        String scheme = uri.getScheme();
        if (scheme == null
            || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"))) {
            throw new IllegalArgumentException("Confluence URL must use http(s): " + url);
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Confluence URL has no host: " + url);
        }
        try {
            for (InetAddress addr : InetAddress.getAllByName(host)) {
                if (addr.isLoopbackAddress() || addr.isAnyLocalAddress()
                    || addr.isLinkLocalAddress() || addr.isMulticastAddress()
                    || addr.isSiteLocalAddress()) {
                    throw new IllegalArgumentException(
                        "Confluence URL resolves to a blocked address (" + addr.getHostAddress()
                            + "): " + host);
                }
            }
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Confluence host cannot be resolved: " + host);
        }
    }

    private static void assertSameHostAsBase(String baseUrl, URI candidate) {
        String baseHost = URI.create(baseUrl).getHost();
        String candidateHost = candidate.getHost();
        if (baseHost == null || candidateHost == null
            || !baseHost.equalsIgnoreCase(candidateHost)) {
            throw new IllegalArgumentException(
                "Refusing to follow Confluence URL to a different host: " + candidate);
        }
    }

    @SuppressWarnings("unchecked")
    public ConnectionTestResult testConnection(String domain, String email, String apiToken,
        String space, String parentPageId, String includeLabels, String excludeLabels) {
        if (domain.isBlank() || email.isBlank() || apiToken.isBlank()) {
            return ConnectionTestResult.failure("Domain, Email, and API Token must not be blank.");
        }

        try {
            RestClient client = getClient(domain, email, apiToken);

            // 1. Verify Space
            log.info("Testing Confluence space info: space={}", space);
            Map<String, Object> spaceResult =
                client.get().uri("/rest/api/space/{space}", space.trim()).retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {});

            String spaceName = spaceResult != null ? (String) spaceResult.get("name") : space;

            // 2. Verify Parent Page
            log.info("Testing Confluence parent page info: id={}", parentPageId);
            Map<String, Object> pageResult = client.get()
                .uri(uriBuilder -> uriBuilder.path("/rest/api/content/{id}")
                    .queryParam("expand", "version").build(parentPageId.trim()))
                .retrieve().body(new ParameterizedTypeReference<Map<String, Object>>() {});

            String pageTitle = "Unknown Page";
            int versionNumber = 1;
            if (pageResult != null) {
                pageTitle = (String) pageResult.get("title");
                Map<String, Object> versionObj = (Map<String, Object>) pageResult.get("version");
                if (versionObj != null && versionObj.get("number") != null) {
                    versionNumber = ((Number) versionObj.get("number")).intValue();
                }
            }

            // 3. Retrieve Page Count under Parent using CQL search
            String cql = buildCqlQuery(space, parentPageId, includeLabels, excludeLabels);
            log.info("Testing Confluence CQL query: {}", cql);
            Map<String, Object> searchResult = client.get()
                .uri(uriBuilder -> uriBuilder.path("/rest/api/content/search")
                    .queryParam("cql", cql).queryParam("limit", 5).build())
                .retrieve().body(new ParameterizedTypeReference<Map<String, Object>>() {});

            int totalPagesFound = 0;
            StringBuilder samplePages = new StringBuilder();
            if (searchResult != null) {
                if (searchResult.get("totalSize") != null) {
                    totalPagesFound = ((Number) searchResult.get("totalSize")).intValue();
                }
                List<Map<String, Object>> results =
                    (List<Map<String, Object>>) searchResult.get("results");
                if (results != null) {
                    for (Map<String, Object> p : results) {
                        // Page titles are attacker-controllable (any author in the space); escape
                        // before
                        // embedding in
                        // HTML.
                        samplePages.append(String.format("<li><strong>%s</strong> (ID: %s)</li>",
                            HtmlUtils.htmlEscape(String.valueOf(p.get("title"))),
                            HtmlUtils.htmlEscape(String.valueOf(p.get("id")))));
                    }
                }
            }

            return ConnectionTestResult.success(spaceName, pageTitle, versionNumber,
                totalPagesFound, samplePages.toString());

        } catch (RestClientResponseException e) {
            log.error("Confluence test failed with REST response error: status={}, body={}",
                e.getStatusCode(), e.getResponseBodyAsString(), e);
            return ConnectionTestResult.failure(String.format("Atlassian API returned HTTP %d: %s",
                e.getStatusCode().value(), e.getResponseBodyAsString()));
        } catch (Exception e) {
            log.error("Confluence connection test failed", e);
            return ConnectionTestResult
                .failure(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    /**
     * Crawls the instance's configured page tree, handing each CQL result page to
     * {@code batchProcessor}. Space, root page and label filters all come from {@code instance} — a
     * crawl is scoped to exactly one site.
     */
    @SuppressWarnings("unchecked")
    public void crawlAllDescendantPages(ConfluenceInstance instance,
        Consumer<List<Map<String, Object>>> batchProcessor) {
        String space = instance.space();
        String cql = buildCqlQuery(space, instance.rootPageId(), instance.includeLabels(),
            instance.excludeLabels());
        String base = baseUrl(instance);

        // First URL (not yet encoded, so we use UriComponentsBuilder to build it correctly)
        URI currentUri = UriComponentsBuilder.fromUriString(base).path("/rest/api/content/search")
            .queryParam("cql", cql).queryParam("limit", 50)
            .queryParam("expand", "version,ancestors,space").build().toUri();

        while (currentUri != null) {
            log.info("Fetching Confluence CQL page batch: {}", currentUri);
            // Retried like the per-page fetch: a large space pages through a dozen search calls in
            // a tight loop, and a single 429 mid-crawl used to fail the crawl AFTER it had already
            // enqueued hundreds of pages, with no resume point.
            URI uri = currentUri;
            Map<String, Object> response =
                withRateLimitRetry("the descendant page crawl of space " + space,
                    () -> fetchSearchPage(instance, base, uri));

            if (response == null) {
                break;
            }

            List<Map<String, Object>> results = (List<Map<String, Object>>) response.get("results");
            if (results != null && !results.isEmpty()) {
                batchProcessor.accept(results);
            }

            Map<String, Object> links = (Map<String, Object>) response.get("_links");
            if (links != null && links.get("next") != null) {
                String nextUrl = (String) links.get("next");
                // nextUrl is already encoded. Create absolute URI directly to bypass RestClient
                // double-encoding.
                currentUri = URI.create(base + nextUrl);
            } else {
                currentUri = null;
            }
        }
    }

    /**
     * How many pages live under the instance's root page when its INCLUDE-label filter is IGNORED.
     *
     * <p>
     * Pages are selected for ingestion by a hand-applied Confluence label, so a page nobody labels
     * is invisible to the knowledge base forever with no signal anywhere. Subtracting the crawl's
     * own page count from this one turns that silence into a number an operator sees at the end of
     * every sync. The exclude-label filter is still applied — a page excluded on purpose is not
     * curation drift.
     *
     * <p>
     * Deliberately a COUNT and not a listing: {@code limit=1} fetches one result row and reads
     * {@code totalSize}, so this costs one request no matter how large the tree is.
     */
    public int countPagesIgnoringIncludeLabels(ConfluenceInstance instance) {
        String cql =
            buildCqlQuery(instance.space(), instance.rootPageId(), null, instance.excludeLabels());
        String base = baseUrl(instance);
        URI uri = UriComponentsBuilder.fromUriString(base).path("/rest/api/content/search")
            .queryParam("cql", cql).queryParam("limit", 1).build().toUri();
        Map<String, Object> response =
            withRateLimitRetry("the unlabelled-page count of space " + instance.space(),
                () -> fetchSearchPage(instance, base, uri));
        Object totalSize = response == null ? null : response.get("totalSize");
        return totalSize instanceof Number number ? number.intValue() : 0;
    }

    /**
     * One page of the CQL search. Package-private so the retry policy can be driven
     * deterministically in tests; it re-validates every URL (the initial one and each
     * server-supplied {@code next}) against the instance's base host before issuing the request.
     */
    Map<String, Object> fetchSearchPage(ConfluenceInstance instance, String baseUrl, URI uri) {
        assertSafeConfluenceUrl(uri.toString());
        assertSameHostAsBase(baseUrl, uri);
        return getClient(instance).get().uri(uri).retrieve()
            .body(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    private String buildCqlQuery(String space, String parentPageId, String includeLabels,
        String excludeLabels) {
        StringBuilder cqlBuilder = new StringBuilder();
        cqlBuilder.append(String.format("type=page and space='%s' and (ancestor='%s' or id='%s')",
            escapeCql(space.trim()), escapeCql(parentPageId.trim()),
            escapeCql(parentPageId.trim())));

        if (includeLabels != null && !includeLabels.isBlank()) {
            String[] labels = includeLabels.split(",");
            List<String> quotedLabels = Arrays.stream(labels).map(String::trim)
                .filter(s -> !s.isBlank()).map(s -> "\"" + escapeCql(s) + "\"").toList();
            if (!quotedLabels.isEmpty()) {
                cqlBuilder.append(" and label in (").append(String.join(",", quotedLabels))
                    .append(")");
            }
        }

        if (excludeLabels != null && !excludeLabels.isBlank()) {
            String[] labels = excludeLabels.split(",");
            for (String label : labels) {
                if (!label.trim().isBlank()) {
                    cqlBuilder.append(String.format(" and label!=\"%s\"", escapeCql(label.trim())));
                }
            }
        }

        return cqlBuilder.toString();
    }

    private static String escapeCql(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\\"");
    }

    /**
     * Fetches a page body, retrying HTTP 429 with backoff (honouring {@code Retry-After}, capped by
     * {@code app.confluence.retry.max-backoff-ms}).
     *
     * <p>
     * A failure THROWS rather than returning {@code ""}: a swallowed fetch error is
     * indistinguishable from an empty page, so a throttled or unauthorized crawl of several hundred
     * pages used to complete green while producing an empty corpus. The thrown message deliberately
     * carries only the page id and status code — never the response body or any credential.
     */
    public ConfluencePageContent getPageContent(ConfluenceInstance instance, String pageId) {
        Optional<ConfluencePageContent> storage;
        try {
            storage = withRateLimitRetry("the body fetch for page " + pageId,
                () -> fetchPageContent(instance, pageId, "storage"));
        } catch (RestClientResponseException e) {
            // Some bodies are only retrievable via body.view (e.g. legacy storage formats).
            log.warn(
                "Failed to retrieve body.storage for page {}, falling back to body.view. Status: {}",
                pageId, e.getStatusCode());
            return fetchViewContentOrFail(instance, pageId, e);
        } catch (IllegalStateException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IllegalStateException("Failed to retrieve the body of Confluence page "
                + pageId + ": " + e.getClass().getSimpleName(), e);
        }
        if (storage.isPresent()) {
            return storage.get();
        }
        // A 200 with no body.storage at all. Nothing threw, so the catch above did not run — take
        // the same body.view fallback deliberately rather than letting "" reach the ingest as a
        // page that looks legitimately empty and is then version-skipped forever.
        log.warn("Confluence returned no body.storage for page {}; falling back to body.view",
            pageId);
        return fetchViewContentOrFail(instance, pageId, null);
    }


    /**
     * Runs {@code call}, retrying HTTP 429 with backoff (honouring {@code Retry-After}, capped by
     * {@code app.confluence.retry.max-backoff-ms}) up to {@code app.confluence.retry.max-attempts}.
     *
     * <p>
     * Any other {@link RestClientResponseException} is rethrown unchanged so callers with a
     * fallback (the body.storage → body.view path) can still see it. Exhausting the 429 budget
     * throws an {@link IllegalStateException} naming {@code what} — never the response body or a
     * credential.
     */
    private <T> T withRateLimitRetry(String what, Supplier<T> call) {
        for (int attempt = 1;; attempt++) {
            try {
                return call.get();
            } catch (RestClientResponseException e) {
                if (e.getStatusCode().value() != TOO_MANY_REQUESTS) {
                    throw e;
                }
                if (attempt >= retryMaxAttempts) {
                    throw new IllegalStateException("Confluence rate-limited " + what
                        + " (HTTP 429) after " + attempt + " attempts");
                }
                backoff(what, attempt, e);
            }
        }
    }

    private ConfluencePageContent fetchViewContentOrFail(ConfluenceInstance instance, String pageId,
        @Nullable RestClientResponseException cause) {
        String why = cause == null ? "no body.storage in the response"
            : "HTTP " + cause.getStatusCode().value() + " on body.storage";
        try {
            return fetchPageContent(instance, pageId, "view")
                .orElseThrow(() -> new IllegalStateException(
                    "Confluence returned neither body.storage nor body.view for page " + pageId
                        + " (" + why + "). Treating this as an empty page would record it as"
                        + " successfully ingested and the version-skip would never retry it, so the"
                        + " job fails instead."));
        } catch (IllegalStateException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                "Failed to retrieve the body of Confluence page " + pageId + " (" + why + ")", e);
        }
    }


    /** Largest exponent the doubling backoff may use before the cap applies. */
    private static final int MAX_BACKOFF_SHIFT = 20;

    /**
     * Honours {@code Retry-After} (seconds) when present, else exponential backoff; both capped.
     * The shift is clamped: a configured {@code max-attempts} above 64 otherwise overflows
     * {@code 1L << (attempt - 1)} to a negative delay and {@code Thread.sleep} throws.
     */
    private void backoff(String what, int attempt, RestClientResponseException e) {
        long delayMs = retryInitialBackoffMs * (1L << Math.min(attempt - 1, MAX_BACKOFF_SHIFT));
        String retryAfter =
            e.getResponseHeaders() != null ? e.getResponseHeaders().getFirst("Retry-After") : null;
        if (retryAfter != null) {
            try {
                delayMs = Math.max(delayMs, Long.parseLong(retryAfter.trim()) * 1000L);
            } catch (NumberFormatException ignored) {
                // Retry-After may be an HTTP date; the exponential delay applies instead.
            }
        }
        delayMs = Math.min(delayMs, retryMaxBackoffMs);
        log.warn("Confluence returned HTTP 429 for {} (attempt {}/{}); retrying in {} ms", what,
            attempt, retryMaxAttempts, delayMs);
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                "Interrupted while backing off from Confluence rate limiting for " + what,
                interrupted);
        }
    }

    /**
     * The raw page content and metadata fetch, returning empty when the response carries no
     * {@code body.<type>.value}.
     *
     * <p>
     * Package-private seam: keeps the retry policy testable without a live Confluence. There is no
     * String-returning sibling on purpose — one existed, and it was a working, discoverable entry
     * point that silently discarded the author, date and version this record exists to carry.
     *
     * <p>
     * The Optional is the whole point: an ABSENT field and an EMPTY value are different facts that
     * used to collapse into the same {@code ""}. A page that genuinely has no content reports an
     * empty value, and {@code ingestPage} rightly records it as a completed zero-chunk document. A
     * 200 whose body was simply not expanded (legacy storage formats do this) carries no field at
     * all — and because no exception was thrown, the {@code body.view} fallback never fired, so the
     * page was recorded as empty too, its {@code confluence_page_version} was stored, and the
     * version-skip meant no later crawl ever retried it. Silent, permanent content loss from a
     * green job. Callers must now decide, so the two cases can no longer be confused.
     */
    @SuppressWarnings("unchecked")
    Optional<ConfluencePageContent> fetchPageContent(ConfluenceInstance instance, String pageId,
        String bodyType) {
        RestClient client = getClient(instance);
        String uri = "/rest/api/content/" + pageId + "?expand=body." + bodyType + ",version";
        Map<String, Object> response = client.get().uri(uri).retrieve()
            .body(new ParameterizedTypeReference<Map<String, Object>>() {});

        if (response != null && response.get("body") != null) {
            Map<String, Object> bodyObj = (Map<String, Object>) response.get("body");
            if (bodyObj != null && bodyObj.get(bodyType) != null) {
                Map<String, Object> typedBody = (Map<String, Object>) bodyObj.get(bodyType);
                if (typedBody != null && typedBody.get("value") != null) {
                    String xhtml = (String) typedBody.get("value");
                    Object versionObj = response.get("version");
                    String author = extractAuthor(versionObj);
                    String lastUpdated = extractLastUpdated(versionObj);
                    Integer version = extractVersionNumber(versionObj);
                    return Optional
                        .of(new ConfluencePageContent(xhtml, author, lastUpdated, version));
                }
            }
        }
        return Optional.empty();
    }

    static String extractAuthor(@Nullable Object versionObj) {
        if (!(versionObj instanceof Map<?, ?> versionMap)) {
            return null;
        }
        Object byObj = versionMap.get("by");
        if (!(byObj instanceof Map<?, ?> byMap)) {
            return null;
        }
        Object displayName = byMap.get("displayName");
        if (displayName != null && !displayName.toString().isBlank()) {
            return displayName.toString().trim();
        }
        Object publicName = byMap.get("publicName");
        if (publicName != null && !publicName.toString().isBlank()) {
            return publicName.toString().trim();
        }
        Object username = byMap.get("username");
        if (username != null && !username.toString().isBlank()) {
            return username.toString().trim();
        }
        return null;
    }

    static String extractLastUpdated(@Nullable Object versionObj) {
        if (!(versionObj instanceof Map<?, ?> versionMap)) {
            return null;
        }
        Object when = versionMap.get("when");
        return formatLastUpdated(when);
    }

    static String formatLastUpdated(@Nullable Object when) {
        if (when == null) {
            return null;
        }
        if (when instanceof Number num) {
            try {
                return Instant.ofEpochMilli(num.longValue()).atZone(ZoneOffset.UTC).toLocalDate()
                    .toString();
            } catch (DateTimeException ignored) {
                // Only reachable on an epoch value too large for an Instant. A broader catch here
                // would also swallow a programming error and report it as "no date".
                return null;
            }
        }
        String whenStr = when.toString().trim();
        if (whenStr.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(whenStr).toLocalDate().toString();
        } catch (DateTimeParseException ignored) {
        }
        try {
            return Instant.parse(whenStr).atZone(ZoneOffset.UTC).toLocalDate().toString();
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDate.parse(whenStr).toString();
        } catch (DateTimeParseException ignored) {
        }
        Matcher matcher = DATE_PREFIX_PATTERN.matcher(whenStr);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    static Integer extractVersionNumber(@Nullable Object versionObj) {
        if (!(versionObj instanceof Map<?, ?> versionMap)) {
            return null;
        }
        Object number = versionMap.get("number");
        if (number == null) {
            return null;
        }
        if (number instanceof Number num) {
            return num.intValue();
        }
        try {
            return Integer.parseInt(number.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Lists a page's attachments, retrying HTTP 429 like every other Confluence call.
     *
     * <p>
     * A failure PROPAGATES rather than degrading to an empty list: swallowing it meant that with
     * {@code processAttachments=true} against a throttling server every page ingested green with
     * all attachment text silently missing — the same "green while empty" pathology the page-body
     * fetch was fixed for.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getPageAttachments(ConfluenceInstance instance,
        String pageId) {
        Map<String, Object> response =
            withRateLimitRetry("the attachment listing for page " + pageId,
                () -> fetchAttachmentList(instance, pageId));
        if (response != null && response.get("results") != null) {
            return (List<Map<String, Object>>) response.get("results");
        }
        return Collections.emptyList();
    }

    /** Package-private seam: keeps the retry policy testable without a live Confluence. */
    Map<String, Object> fetchAttachmentList(ConfluenceInstance instance, String pageId) {
        String uri = "/rest/api/content/" + pageId
            + "/child/attachment?limit=100&expand=version,metadata,extensions";
        return getClient(instance).get().uri(uri).retrieve()
            .body(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public byte[] downloadAttachment(ConfluenceInstance instance, String downloadUrl) {
        log.info("Downloading attachment: {}", downloadUrl);
        return withRateLimitRetry("the download of attachment " + downloadUrl,
            () -> fetchAttachment(instance, downloadUrl));
    }

    /** Package-private seam: keeps the retry policy testable without a live Confluence. */
    byte[] fetchAttachment(ConfluenceInstance instance, String downloadUrl) {
        // Attachment download URLs come from the Confluence API response — i.e. from a server that
        // may be hostile or compromised — and the request that follows carries this instance's
        // Authorization header. Relative URLs resolve against the (already validated) base;
        // anything with a HOST must be re-validated and confined to the base host of THIS instance.
        if (hasHost(downloadUrl)) {
            assertSafeConfluenceUrl(downloadUrl);
            assertSameHostAsBase(baseUrl(instance), URI.create(downloadUrl));
        }
        return getClient(instance).get().uri(downloadUrl).retrieve().body(byte[].class);
    }

    /**
     * Whether the URL carries an authority, decided by PARSING it with the same parser the request
     * pipeline uses.
     *
     * <p>
     * Deciding it with {@code startsWith("http://") || startsWith("https://")} was a guard bypass:
     * that test is case-SENSITIVE while {@code UriComponentsBuilder} reads the scheme
     * case-insensitively and Apache HttpClient lower-cases it in {@code HttpHost}. So
     * {@code HTTPS://169.254.169.254/latest/meta-data/…} looked relative, skipped BOTH guards, and
     * was still issued as an absolute authenticated request to the cloud metadata endpoint — whose
     * response Tika then appended into the ingested page. Parsing also catches the
     * protocol-relative {@code //host/path} form, which has an authority and no scheme at all.
     */
    private static boolean hasHost(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("Confluence attachment download URL is missing");
        }
        String host;
        try {
            host = UriComponentsBuilder.fromUriString(url).build().getHost();
        } catch (IllegalArgumentException e) {
            // Unparseable: fail closed rather than hand it to the client and hope.
            throw new IllegalArgumentException("Invalid Confluence attachment download URL", e);
        }
        return host != null && !host.isBlank();
    }

    public String getUserDisplayName(ConfluenceInstance instance, String accountId) {
        if (accountId == null || accountId.isBlank()) {
            return "User(unknown)";
        }
        // Resolve the client FIRST: it drops this instance's cached names when the instance was
        // repointed at another site, where the same account ids mean different people. It also
        // keeps the lookup out of a computeIfAbsent mapping function — a nested update of the same
        // ConcurrentHashMap (which the eviction is) is not allowed there.
        RestClient client = getClient(instance);
        UserCacheKey key = new UserCacheKey(instance.id(), accountId);
        String cached = userDisplayNameCache.get(key);
        if (cached != null) {
            return cached;
        }
        String displayName = "User(" + accountId + ")";
        try {
            Map<String, Object> response = client.get()
                .uri(uriBuilder -> uriBuilder.path("/rest/api/user")
                    .queryParam("accountId", accountId).build())
                .retrieve().body(new ParameterizedTypeReference<Map<String, Object>>() {});
            if (response != null && response.get("displayName") != null) {
                displayName = (String) response.get("displayName");
            }
        } catch (Exception e) {
            log.warn("Failed to resolve user account ID to display name: {}", accountId, e);
        }
        userDisplayNameCache.put(key, displayName);
        return displayName;
    }

    public record ConnectionTestResult(boolean ok, String message, String spaceName,
        String parentPageTitle, int parentPageVersion, int pagesCount, String samplePagesHtml) {
        public static ConnectionTestResult success(String spaceName, String parentPageTitle,
            int parentPageVersion, int pagesCount, String samplePagesHtml) {
            return new ConnectionTestResult(true, "Successfully connected to Confluence!",
                spaceName, parentPageTitle, parentPageVersion, pagesCount, samplePagesHtml);
        }

        public static ConnectionTestResult failure(String message) {
            return new ConnectionTestResult(false, message, null, null, 0, 0, "");
        }
    }
}
