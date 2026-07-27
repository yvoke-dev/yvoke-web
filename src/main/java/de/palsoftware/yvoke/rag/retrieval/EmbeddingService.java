package de.palsoftware.yvoke.rag.retrieval;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import de.palsoftware.yvoke.llm.core.context.LlmCallContextHolder;
import de.palsoftware.yvoke.llm.core.event.LlmCallLoggedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.beans.factory.annotation.Autowired;

@Service
public class EmbeddingService implements EmbeddingModel {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    static final int MAX_BATCH_SIZE = 128;

    private final RestClient restClient;
    private final String apiKey;
    private final String model;
    private final ApplicationEventPublisher eventPublisher;

    @Autowired
    public EmbeddingService(
        @org.springframework.beans.factory.annotation.Qualifier("embeddingRestClientBuilder") RestClient.Builder restClientBuilder,
        @Value("${app.ai.embedding.api-key}") String apiKey,
        @Value("${app.ai.embedding.model}") String model,
        @Value("${app.ai.embedding.base-url}") String baseUrl,
        @Autowired(required = false) ApplicationEventPublisher eventPublisher) {
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
        this.apiKey = apiKey;
        this.model = model;
        this.eventPublisher = eventPublisher;
    }

    public EmbeddingService(RestClient.Builder restClientBuilder, String apiKey, String model,
        String baseUrl) {
        this(restClientBuilder, apiKey, model, baseUrl, null);
    }

    @Override
    public float[] embed(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Text to embed cannot be null or blank");
        }
        List<float[]> results = embedBatch(List.of(text));
        return results.get(0);
    }

    @Override
    public float[] embed(Document document) {
        if (document == null) {
            throw new IllegalArgumentException("Document cannot be null");
        }
        return embed(document.getText());
    }

    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            throw new IllegalArgumentException("Texts to embed cannot be null or empty");
        }

        List<float[]> allResults = new ArrayList<>();
        List<String> currentBatch = new ArrayList<>();
        int currentBatchCharCount = 0;

        for (String text : texts) {
            int textLength = text != null ? text.length() : 0;
            // If adding this text would exceed MAX_BATCH_SIZE or estimated token/char limit (400k
            // chars),
            // submit the current batch first.
            if (currentBatch.size() >= MAX_BATCH_SIZE
                || (currentBatchCharCount + textLength > 400000 && !currentBatch.isEmpty())) {
                allResults.addAll(doEmbedBatch(currentBatch));
                currentBatch.clear();
                currentBatchCharCount = 0;
            }
            currentBatch.add(text);
            currentBatchCharCount += textLength;
        }

        if (!currentBatch.isEmpty()) {
            allResults.addAll(doEmbedBatch(currentBatch));
        }

        return allResults;
    }

    private List<float[]> doEmbedBatch(List<String> texts) {
        log.info("Voyage Embedding Request: calling model `{}` with batch of {} texts", model,
            texts.size());
        VoyageResponse response = restClient.post().uri("/embeddings")
            .header("Authorization", "Bearer " + apiKey).contentType(MediaType.APPLICATION_JSON)
            .body(Map.of("model", model, "input", texts)).retrieve().body(VoyageResponse.class);

        if (response == null || response.getData() == null) {
            throw new IllegalStateException(
                "Failed to get embeddings from Voyage AI; empty response.");
        }

        List<VoyageData> dataList = new ArrayList<>(response.getData());
        dataList.sort((a, b) -> Integer.compare(a.getIndex(), b.getIndex()));

        if (eventPublisher != null) {
            try {
                int totalTokens = 0;
                if (response.getUsage() != null
                    && response.getUsage().get("total_tokens") != null) {
                    totalTokens = ((Number) response.getUsage().get("total_tokens")).intValue();
                } else {
                    totalTokens = texts.stream()
                        .mapToInt(t -> t != null ? Math.max(1, t.length() / 4) : 0).sum();
                }
                LlmCallContextHolder.Context ctx = LlmCallContextHolder.get();
                eventPublisher.publishEvent(new LlmCallLoggedEvent(
                    ctx != null ? ctx.conversationId() : null, ctx != null ? ctx.messageId() : null,
                    ctx != null ? ctx.agentRunId() : null, ctx != null ? ctx.userId() : null,
                    "embedding", "embedding", model, totalTokens, 0, 0, 0));
            } catch (Exception e) {
                log.warn("Failed to publish embedding usage event: {}", e.getMessage());
            }
        }

        return dataList.stream().map(VoyageData::getEmbedding).toList();
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<String> texts = request.getInstructions();
        List<float[]> embeddings = embedBatch(texts);

        List<org.springframework.ai.embedding.Embedding> list = new ArrayList<>();
        for (int i = 0; i < embeddings.size(); i++) {
            list.add(new org.springframework.ai.embedding.Embedding(embeddings.get(i), i));
        }
        return new EmbeddingResponse(list);
    }

    // Static nested classes for Jackson deserialization
    public static class VoyageResponse {
        private String object;
        private List<VoyageData> data;
        private String model;
        private Map<String, Object> usage;

        public String getObject() {
            return object;
        }

        public void setObject(String object) {
            this.object = object;
        }

        public List<VoyageData> getData() {
            return data;
        }

        public void setData(List<VoyageData> data) {
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

    public static class VoyageData {
        private String object;
        private float[] embedding;
        private int index;

        public String getObject() {
            return object;
        }

        public void setObject(String object) {
            this.object = object;
        }

        public float[] getEmbedding() {
            return embedding;
        }

        public void setEmbedding(float[] embedding) {
            this.embedding = embedding;
        }

        public int getIndex() {
            return index;
        }

        public void setIndex(int index) {
            this.index = index;
        }
    }
}
