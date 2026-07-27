package de.palsoftware.yvoke.rag.retrieval;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import de.palsoftware.yvoke.llm.core.context.LlmCallContextHolder;
import de.palsoftware.yvoke.llm.core.event.LlmCallLoggedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

@Component
public class RerankClient {

    private static final Logger log = LoggerFactory.getLogger(RerankClient.class);

    private final RestClient restClient;
    private final String apiKey;
    private final String model;
    private final ApplicationEventPublisher eventPublisher;

    @Autowired
    public RerankClient(@Qualifier("rerankRestClientBuilder") RestClient.Builder restClientBuilder,
        @Value("${app.ai.ranking.api-key}") String apiKey,
        @Value("${app.ai.ranking.model}") String model,
        @Value("${app.ai.ranking.base-url}") String baseUrl,
        @Autowired(required = false) ApplicationEventPublisher eventPublisher) {
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
        this.apiKey = apiKey;
        this.model = model;
        this.eventPublisher = eventPublisher;
    }

    public RerankClient(RestClient.Builder restClientBuilder, String apiKey, String model,
        String baseUrl) {
        this(restClientBuilder, apiKey, model, baseUrl, null);
    }

    public List<RerankResult> rerank(String query, List<String> documents) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("Query cannot be null or blank");
        }
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }

        log.info("Sending {} documents to Voyage Rerank API (model={})", documents.size(), model);

        RerankResponse response = restClient.post().uri("/rerank")
            .header("Authorization", "Bearer " + apiKey).contentType(MediaType.APPLICATION_JSON)
            .body(Map.of("model", model, "query", query, "documents", documents)).retrieve()
            .body(RerankResponse.class);

        if (response == null || response.getData() == null) {
            throw new IllegalStateException(
                "Failed to get reranking response from Voyage AI; empty response.");
        }

        if (eventPublisher != null) {
            try {
                int totalTokens = 0;
                if (response.getUsage() != null
                    && response.getUsage().get("total_tokens") != null) {
                    totalTokens = ((Number) response.getUsage().get("total_tokens")).intValue();
                } else {
                    int totalChars = query.length()
                        + documents.stream().mapToInt(d -> d != null ? d.length() : 0).sum();
                    totalTokens = Math.max(1, totalChars / 4);
                }
                LlmCallContextHolder.Context ctx = LlmCallContextHolder.get();
                eventPublisher.publishEvent(new LlmCallLoggedEvent(
                    ctx != null ? ctx.conversationId() : null, ctx != null ? ctx.messageId() : null,
                    ctx != null ? ctx.agentRunId() : null, ctx != null ? ctx.userId() : null,
                    "rerank", "rerank", model, totalTokens, 0, 0, 0));
            } catch (Exception e) {
                log.warn("Failed to publish rerank usage event: {}", e.getMessage());
            }
        }

        return response.getData().stream()
            .map(data -> new RerankResult(data.getIndex(), data.getRelevanceScore())).toList();
    }

    public static record RerankResult(int index, double relevanceScore) {}

    public static class RerankResponse {
        private String object;
        private List<RerankData> data;
        private String model;
        private Map<String, Object> usage;

        public String getObject() {
            return object;
        }

        public void setObject(String object) {
            this.object = object;
        }

        public List<RerankData> getData() {
            return data;
        }

        public void setData(List<RerankData> data) {
            this.data = data;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public Map<String, Object> getUsage() {
            return usage;
        }

        public void setUsage(Map<String, Object> usage) {
            this.usage = usage;
        }
    }

    public static class RerankData {
        private int index;

        @JsonProperty("relevance_score")
        private double relevanceScore;

        public int getIndex() {
            return index;
        }

        public void setIndex(int index) {
            this.index = index;
        }

        public double getRelevanceScore() {
            return relevanceScore;
        }

        public void setRelevanceScore(double relevanceScore) {
            this.relevanceScore = relevanceScore;
        }
    }
}
