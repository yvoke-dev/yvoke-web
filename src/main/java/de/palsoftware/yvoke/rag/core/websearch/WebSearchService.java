package de.palsoftware.yvoke.rag.core.websearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.llm.core.context.LlmCallContextHolder;
import de.palsoftware.yvoke.llm.core.event.LlmCallLoggedEvent;
import de.palsoftware.yvoke.rag.core.websearch.WebSearchPolicy.AllowEntry;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * Executes domain-scoped web searches via Gemini Grounding with Google Search
 * ({@code POST /v1beta/models/<model>:generateContent} with {@code "tools": [{"google_search":
 * {}}]}).
 *
 * <p>
 * Resolves {@code vertexaisearch.cloud.google.com/grounding-api-redirect/...}
 * {@code 301}/{@code 302} {@code Location} headers with {@link HttpClient.Redirect#NEVER} (avoiding
 * direct requests to vendor WAF-protected pages) and post-filters every candidate URL through
 * {@link WebSearchPolicy#isUrlDomainAllowed(String, List)}.
 */
@Service
public class WebSearchService {

    private static final Logger log = LoggerFactory.getLogger(WebSearchService.class);

    private static final String GROUNDING_REDIRECT_HOST = "vertexaisearch.cloud.google.com";
    private static final String GROUNDING_REDIRECT_PATH_PREFIX = "/grounding-api-redirect/";
    private static final int MIN_MAX_RESULTS = 1;
    private static final int MAX_MAX_RESULTS = 20;

    private final boolean enabled;
    private final List<AllowEntry> allowedEntries;
    private final String model;
    private final int maxResults;
    private final int timeoutSeconds;
    private final String geminiBaseUrl;
    private final String geminiApiKey;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final HttpClient httpClient;

    @Autowired
    public WebSearchService(@Value("${app.web-search.enabled}") boolean enabled,
        @Value("${app.web-search.allowed-domains}") String allowedDomainsRaw,
        @Value("${app.web-search.model}") String model,
        @Value("${app.web-search.max-results}") int maxResults,
        @Value("${app.web-search.timeout-seconds}") int timeoutSeconds,
        @Value("${app.ai.gemini.base-url}") String geminiBaseUrl,
        @Value("${app.ai.gemini.api-key}") String geminiApiKey, ObjectMapper objectMapper,
        @Autowired(required = false) ApplicationEventPublisher eventPublisher) {
        this(enabled, allowedDomainsRaw, model, maxResults, timeoutSeconds, geminiBaseUrl,
            geminiApiKey, objectMapper, eventPublisher,
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                .followRedirects(HttpClient.Redirect.NEVER).build());
    }

    WebSearchService(boolean enabled, String allowedDomainsRaw, String model, int maxResults,
        int timeoutSeconds, String geminiBaseUrl, String geminiApiKey, ObjectMapper objectMapper,
        ApplicationEventPublisher eventPublisher, HttpClient httpClient) {
        this.enabled = enabled;
        this.allowedEntries = WebSearchPolicy.parseAllowEntries(allowedDomainsRaw);
        this.model = model != null ? model.trim() : "";
        this.maxResults = Math.clamp(maxResults, MIN_MAX_RESULTS, MAX_MAX_RESULTS);
        this.timeoutSeconds = Math.max(1, timeoutSeconds);
        this.geminiBaseUrl = normalizeBaseUrl(geminiBaseUrl);
        this.geminiApiKey = geminiApiKey != null ? geminiApiKey.trim() : "";
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
        this.httpClient = httpClient;
    }

    /**
     * Level 1 deployment gate: returns {@code true} only when web search is enabled, at least one
     * valid domain/path entry is configured, and model, Gemini base URL, and a real
     * (non-placeholder) Gemini API key are set.
     */
    public boolean isAvailable() {
        return enabled && !allowedEntries.isEmpty() && !model.isEmpty() && !geminiBaseUrl.isEmpty()
            && !geminiApiKey.isEmpty() && !geminiApiKey.startsWith("placeholder-");
    }

    /**
     * Executes a domain-scoped Google Search via Gemini Grounding and returns post-filtered,
     * deduplicated {@link WebSearchResult} entries.
     */
    public List<WebSearchResult> search(String query) {
        if (!isAvailable()) {
            throw new IllegalStateException("WebSearch is not enabled or configured");
        }
        String scopedQuery = WebSearchPolicy.buildScopedQuery(query, allowedEntries);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contents",
            List.of(Map.of("role", "user", "parts", List.of(Map.of("text", scopedQuery)))));
        payload.put("tools", List.of(Map.of("google_search", Map.of())));

        String requestBody;
        try {
            requestBody = objectMapper.writeValueAsString(payload);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize WebSearch request", e);
        }

        URI endpoint = URI.create(geminiBaseUrl + "/v1beta/models/" + model + ":generateContent");
        HttpRequest request = HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofSeconds(timeoutSeconds)).header("x-goog-api-key", geminiApiKey)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8)).build();

        long startNanos = System.nanoTime();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("WebSearch request interrupted", e);
        } catch (IOException e) {
            throw new IllegalStateException("WebSearch request failed", e);
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException(
                "Gemini Grounding returned HTTP " + response.statusCode());
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(response.body());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to parse Gemini Grounding response JSON", e);
        }

        publishUsageIfPresent(root.path("usageMetadata"), startNanos);
        return extractGroundedResults(root);
    }

    private List<WebSearchResult> extractGroundedResults(JsonNode root) {
        JsonNode candidates = root.path("candidates");
        if (!candidates.isArray() || candidates.isEmpty()) {
            return List.of();
        }
        JsonNode candidate = candidates.get(0);
        String candidateText = extractCandidateText(candidate);

        JsonNode groundingMetadata = candidate.path("groundingMetadata");
        JsonNode groundingChunks = groundingMetadata.path("groundingChunks");
        if (!groundingChunks.isArray() || groundingChunks.isEmpty()) {
            return List.of();
        }

        Map<Integer, List<String>> supportsByChunk =
            extractSupportsByChunkIndex(groundingMetadata.path("groundingSupports"));

        List<WebSearchResult> results = new ArrayList<>();
        Set<String> seenUrls = new LinkedHashSet<>();
        int maxRedirectResolutions = maxResults * 2;
        int redirectResolutions = 0;

        for (int i = 0; i < groundingChunks.size() && results.size() < maxResults; i++) {
            JsonNode web = groundingChunks.get(i).path("web");
            String rawUri = web.path("uri").asText("").trim();
            if (rawUri.isEmpty()) {
                continue;
            }
            String resolvedUrl;
            if (isGroundingRedirectUrl(rawUri)) {
                if (redirectResolutions >= maxRedirectResolutions) {
                    continue;
                }
                redirectResolutions++;
                resolvedUrl = resolveGroundingRedirect(rawUri);
            } else {
                resolvedUrl = rawUri;
            }
            if (resolvedUrl == null
                || !WebSearchPolicy.isUrlDomainAllowed(resolvedUrl, allowedEntries)
                || !seenUrls.add(resolvedUrl)) {
                continue;
            }

            String title = web.path("title").asText("").trim();
            if (title.isEmpty()) {
                title = resolvedUrl;
            }
            List<String> supportTexts = supportsByChunk.get(i);
            String snippet =
                (supportTexts != null && !supportTexts.isEmpty()) ? String.join(" ", supportTexts)
                    : candidateText;

            results.add(new WebSearchResult(title, resolvedUrl, snippet));
        }

        return List.copyOf(results);
    }

    private static String extractCandidateText(JsonNode candidate) {
        JsonNode parts = candidate.path("content").path("parts");
        if (!parts.isArray() || parts.isEmpty()) {
            return "";
        }
        List<String> texts = new ArrayList<>();
        for (JsonNode part : parts) {
            String text = part.path("text").asText("").trim();
            if (!text.isEmpty()) {
                texts.add(text);
            }
        }
        return String.join(" ", texts);
    }

    private static Map<Integer, List<String>> extractSupportsByChunkIndex(
        JsonNode groundingSupports) {
        if (!groundingSupports.isArray() || groundingSupports.isEmpty()) {
            return Map.of();
        }
        Map<Integer, List<String>> byChunk = new HashMap<>();
        for (JsonNode support : groundingSupports) {
            String text = support.path("segment").path("text").asText("").trim();
            if (text.isEmpty()) {
                continue;
            }
            JsonNode indices = support.path("groundingChunkIndices");
            if (!indices.isArray()) {
                continue;
            }
            for (JsonNode idxNode : indices) {
                if (idxNode.canConvertToInt()) {
                    int idx = idxNode.asInt();
                    List<String> list = byChunk.computeIfAbsent(idx, k -> new ArrayList<>());
                    if (!list.contains(text)) {
                        list.add(text);
                    }
                }
            }
        }
        return byChunk;
    }

    private String resolveGroundingRedirect(String redirectUrl) {
        URI uri;
        try {
            uri = new URI(redirectUrl);
        } catch (URISyntaxException e) {
            return null;
        }
        HttpRequest redirectRequest =
            HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(timeoutSeconds)).GET().build();
        HttpResponse<Void> response;
        try {
            response = httpClient.send(redirectRequest, HttpResponse.BodyHandlers.discarding());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while resolving grounding redirect", e);
        } catch (IOException e) {
            log.debug("Failed to resolve grounding redirect {}: {}", redirectUrl, e.getMessage());
            return null;
        }

        int status = response.statusCode();
        if (status != 301 && status != 302 && status != 303 && status != 307 && status != 308) {
            return null;
        }
        Optional<String> locationOpt = response.headers().firstValue("Location");
        if (locationOpt.isEmpty()) {
            return null;
        }
        String location = locationOpt.get().trim();
        if (location.isEmpty()) {
            return null;
        }

        URI locationUri;
        try {
            locationUri = new URI(location);
        } catch (URISyntaxException e) {
            return null;
        }
        String scheme = locationUri.getScheme();
        if (!locationUri.isAbsolute() || scheme == null
            || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))
            || locationUri.getHost() == null || isGroundingRedirectUri(locationUri)) {
            return null;
        }
        return location;
    }

    private boolean isGroundingRedirectUrl(String rawUrl) {
        try {
            return isGroundingRedirectUri(new URI(rawUrl));
        } catch (URISyntaxException e) {
            return false;
        }
    }

    private boolean isGroundingRedirectUri(URI uri) {
        String scheme = uri.getScheme();
        String host = uri.getHost();
        String path = uri.getRawPath();
        if (scheme == null || host == null || path == null
            || !path.startsWith(GROUNDING_REDIRECT_PATH_PREFIX)) {
            return false;
        }
        if (scheme.equalsIgnoreCase("https")
            && host.toLowerCase(Locale.ROOT).equals(GROUNDING_REDIRECT_HOST)) {
            return true;
        }
        return !geminiBaseUrl.isEmpty()
            && uri.toString().startsWith(geminiBaseUrl + GROUNDING_REDIRECT_PATH_PREFIX);
    }

    private void publishUsageIfPresent(JsonNode usageNode, long startNanos) {
        if (eventPublisher == null || usageNode == null || usageNode.isMissingNode()
            || usageNode.isNull() || !usageNode.isObject()) {
            return;
        }
        int promptTokens = usageNode.path("promptTokenCount").asInt(0)
            + usageNode.path("toolUsePromptTokenCount").asInt(0);
        int completionTokens = usageNode.path("candidatesTokenCount").asInt(0);
        int cachedTokens = usageNode.path("cachedContentTokenCount").asInt(0);
        int thoughtTokens = usageNode.path("thoughtsTokenCount").asInt(0);
        int totalTokens =
            usageNode.hasNonNull("totalTokenCount") ? usageNode.path("totalTokenCount").asInt(0)
                : (promptTokens + completionTokens + thoughtTokens);

        boolean wasInterrupted = Thread.interrupted();
        try {
            LlmCallContextHolder.Context ctx = LlmCallContextHolder.get();
            String source =
                (ctx != null && ctx.source() != null && !ctx.source().isBlank()) ? ctx.source()
                    : "web_search";
            eventPublisher.publishEvent(new LlmCallLoggedEvent(
                ctx != null ? ctx.conversationId() : null, ctx != null ? ctx.messageId() : null,
                ctx != null ? ctx.agentRunId() : null, ctx != null ? ctx.userId() : null, source,
                "web_search", model, promptTokens, completionTokens, cachedTokens, thoughtTokens,
                totalTokens, elapsedMillis(startNanos)));
        } catch (Exception e) {
            log.warn("Failed to publish web_search usage event for model {}: {}", model,
                e.getMessage());
        } finally {
            if (wasInterrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static int elapsedMillis(long startNanos) {
        return (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - startNanos) / 1_000_000L);
    }

    private static String normalizeBaseUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String trimmed = raw.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
