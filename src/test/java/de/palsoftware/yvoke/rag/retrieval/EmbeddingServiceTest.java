package de.palsoftware.yvoke.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;
import java.util.UUID;
import de.palsoftware.yvoke.llm.core.context.LlmCallContextHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

public class EmbeddingServiceTest {

    private MockRestServiceServer server;
    private EmbeddingService embeddingService;

    @BeforeEach
    public void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        embeddingService = new EmbeddingService(builder, "mock-key-for-skeleton", "voyage-4-large",
            "https://api.voyageai.com/v1");
    }

    @Test
    public void testEmbedSuccess() {
        String jsonResponse = """
            {
              "object": "list",
              "data": [
                {
                  "object": "embedding",
                  "embedding": [0.1, 0.2, 0.3],
                  "index": 0
                }
              ],
              "model": "voyage-4-large",
              "usage": {
                "total_tokens": 4
              }
            }
            """;

        server.expect(requestTo("https://api.voyageai.com/v1/embeddings"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", "Bearer mock-key-for-skeleton"))
            .andRespond(withSuccess(jsonResponse, MediaType.APPLICATION_JSON));

        float[] result = embeddingService.embed("OIM");

        assertThat(result).containsExactly(0.1f, 0.2f, 0.3f);
        server.verify();
    }

    @Test
    public void testEmbedBatchSuccess() {
        String jsonResponse = """
            {
              "object": "list",
              "data": [
                {
                  "object": "embedding",
                  "embedding": [0.4, 0.5],
                  "index": 1
                },
                {
                  "object": "embedding",
                  "embedding": [0.1, 0.2],
                  "index": 0
                }
              ],
              "model": "voyage-4-large",
              "usage": {
                "total_tokens": 10
              }
            }
            """;

        server.expect(requestTo("https://api.voyageai.com/v1/embeddings"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess(jsonResponse, MediaType.APPLICATION_JSON));

        List<float[]> results = embeddingService.embedBatch(List.of("first", "second"));

        assertThat(results).hasSize(2);
        // Elements should be sorted by index
        assertThat(results.get(0)).containsExactly(0.1f, 0.2f);
        assertThat(results.get(1)).containsExactly(0.4f, 0.5f);
        server.verify();
    }

    @Test
    public void testEmbedInvalidInput() {
        assertThatThrownBy(() -> embeddingService.embed(""))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> embeddingService.embed((String) null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> embeddingService.embedBatch(null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> embeddingService.embedBatch(List.of()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void testEmbedDocument() {
        String jsonResponse = """
            {
              "object": "list",
              "data": [
                {
                  "object": "embedding",
                  "embedding": [0.7, 0.8],
                  "index": 0
                }
              ],
              "model": "voyage-4-large",
              "usage": {"total_tokens": 2}
            }
            """;

        server.expect(requestTo("https://api.voyageai.com/v1/embeddings"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess(jsonResponse, MediaType.APPLICATION_JSON));

        float[] result =
            embeddingService.embed(new org.springframework.ai.document.Document("doc text"));
        assertThat(result).containsExactly(0.7f, 0.8f);
        server.verify();
    }

    @Test
    public void testEmbedNullDocument() {
        assertThatThrownBy(
            () -> embeddingService.embed((org.springframework.ai.document.Document) null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void testEmbeddingRequestCall() {
        String jsonResponse = """
            {
              "object": "list",
              "data": [
                {
                  "object": "embedding",
                  "embedding": [0.9, 0.1],
                  "index": 0
                }
              ],
              "model": "voyage-4-large",
              "usage": {"total_tokens": 2}
            }
            """;

        server.expect(requestTo("https://api.voyageai.com/v1/embeddings"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess(jsonResponse, MediaType.APPLICATION_JSON));

        org.springframework.ai.embedding.EmbeddingResponse response = embeddingService.call(
            new org.springframework.ai.embedding.EmbeddingRequest(List.of("call text"), null));

        assertThat(response).isNotNull();
        assertThat(response.getResults()).hasSize(1);
        assertThat(response.getResults().get(0).getOutput()).containsExactly(0.9f, 0.1f);
        server.verify();
    }

    @Test
    public void testEmbedServerError() {
        server.expect(requestTo("https://api.voyageai.com/v1/embeddings"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                .withServerError());

        assertThatThrownBy(() -> embeddingService.embed("error query"))
            .isInstanceOf(org.springframework.web.client.HttpServerErrorException.class);
        server.verify();
    }

    @Test
    public void testEmbedEmptyResponseBody() {
        server.expect(requestTo("https://api.voyageai.com/v1/embeddings"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> embeddingService.embed("empty query"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Failed to get embeddings from Voyage AI; empty response.");
        server.verify();
    }

    @Test
    public void testEmbedBatchChunkingSplitsLargeBatch() {
        // Create texts exceeding MAX_BATCH_SIZE (128)
        int totalTexts = EmbeddingService.MAX_BATCH_SIZE + 5; // 133 texts
        List<String> texts = new java.util.ArrayList<>();
        for (int i = 0; i < totalTexts; i++) {
            texts.add("text_" + i);
        }

        // First batch: 128 items
        StringBuilder batch1Json = new StringBuilder();
        batch1Json.append("{\"object\":\"list\",\"data\":[");
        for (int i = 0; i < EmbeddingService.MAX_BATCH_SIZE; i++) {
            if (i > 0)
                batch1Json.append(",");
            batch1Json.append(String.format(
                "{\"object\":\"embedding\",\"embedding\":[%d.0,%d.1],\"index\":%d}", i, i, i));
        }
        batch1Json.append("],\"model\":\"voyage-4-large\",\"usage\":{\"total_tokens\":100}}");

        // Second batch: 5 items
        StringBuilder batch2Json = new StringBuilder();
        batch2Json.append("{\"object\":\"list\",\"data\":[");
        for (int i = 0; i < 5; i++) {
            if (i > 0)
                batch2Json.append(",");
            batch2Json.append(
                String.format("{\"object\":\"embedding\",\"embedding\":[%d.0,%d.1],\"index\":%d}",
                    200 + i, 200 + i, i));
        }
        batch2Json.append("],\"model\":\"voyage-4-large\",\"usage\":{\"total_tokens\":10}}");

        server.expect(requestTo("https://api.voyageai.com/v1/embeddings"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess(batch1Json.toString(), MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.voyageai.com/v1/embeddings"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess(batch2Json.toString(), MediaType.APPLICATION_JSON));

        List<float[]> results = embeddingService.embedBatch(texts);

        assertThat(results).hasSize(totalTexts);
        // Verify first batch results
        assertThat(results.get(0)[0]).isEqualTo(0.0f);
        // Verify second batch results start at position 128
        assertThat(results.get(EmbeddingService.MAX_BATCH_SIZE)[0]).isEqualTo(200.0f);
        server.verify();
    }

    @Test
    public void testEmbedBatchNoChunkingWithinLimit() {
        // Exactly MAX_BATCH_SIZE texts — should be a single API call
        List<String> texts = new java.util.ArrayList<>();
        for (int i = 0; i < EmbeddingService.MAX_BATCH_SIZE; i++) {
            texts.add("text_" + i);
        }

        StringBuilder json = new StringBuilder();
        json.append("{\"object\":\"list\",\"data\":[");
        for (int i = 0; i < EmbeddingService.MAX_BATCH_SIZE; i++) {
            if (i > 0)
                json.append(",");
            json.append(String
                .format("{\"object\":\"embedding\",\"embedding\":[%d.0],\"index\":%d}", i, i));
        }
        json.append("],\"model\":\"voyage-4-large\",\"usage\":{\"total_tokens\":100}}");

        server.expect(requestTo("https://api.voyageai.com/v1/embeddings"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess(json.toString(), MediaType.APPLICATION_JSON));

        List<float[]> results = embeddingService.embedBatch(texts);

        assertThat(results).hasSize(EmbeddingService.MAX_BATCH_SIZE);
        server.verify(); // Only 1 request expected
    }

    @Test
    public void testEmbed_publishesModelUsageEvent() {
        org.springframework.context.ApplicationEventPublisher mockPublisher =
            org.mockito.Mockito.mock(org.springframework.context.ApplicationEventPublisher.class);

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(builder).build();
        EmbeddingService serviceWithPublisher = new EmbeddingService(builder, "mock-key",
            "voyage-4-large", "https://api.voyageai.com/v1", mockPublisher);

        String jsonResponse = """
            {
              "object": "list",
              "data": [
                {
                  "object": "embedding",
                  "embedding": [0.1, 0.2],
                  "index": 0
                }
              ],
              "model": "voyage-4-large",
              "usage": {
                "total_tokens": 12
              }
            }
            """;

        mockServer.expect(requestTo("https://api.voyageai.com/v1/embeddings"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess(jsonResponse, MediaType.APPLICATION_JSON));

        float[] result = serviceWithPublisher.embed("test search query");

        assertThat(result).containsExactly(0.1f, 0.2f);
        mockServer.verify();

        org.mockito.Mockito.verify(mockPublisher)
            .publishEvent(new de.palsoftware.yvoke.llm.core.event.LlmCallLoggedEvent("embedding",
                "embedding", "voyage-4-large", 12, 0, 0, 0));
    }

    @Test
    public void testEmbed_publishesModelUsageEventWithContext() {
        org.springframework.context.ApplicationEventPublisher mockPublisher =
            org.mockito.Mockito.mock(org.springframework.context.ApplicationEventPublisher.class);

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(builder).build();
        EmbeddingService serviceWithPublisher = new EmbeddingService(builder, "mock-key",
            "voyage-4-large", "https://api.voyageai.com/v1", mockPublisher);

        String jsonResponse = """
            {
              "object": "list",
              "data": [
                {
                  "object": "embedding",
                  "embedding": [0.1, 0.2],
                  "index": 0
                }
              ],
              "model": "voyage-4-large",
              "usage": {
                "total_tokens": 12
              }
            }
            """;

        mockServer.expect(requestTo("https://api.voyageai.com/v1/embeddings"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess(jsonResponse, MediaType.APPLICATION_JSON));

        UUID convId = UUID.randomUUID();
        UUID msgId = UUID.randomUUID();
        try {
            LlmCallContextHolder.set(convId, msgId, null, null, "chat", "assistant");
            float[] result = serviceWithPublisher.embed("test search query");
            assertThat(result).containsExactly(0.1f, 0.2f);
        } finally {
            LlmCallContextHolder.clear();
        }

        mockServer.verify();

        org.mockito.Mockito.verify(mockPublisher)
            .publishEvent(new de.palsoftware.yvoke.llm.core.event.LlmCallLoggedEvent(convId, msgId,
                null, null, "embedding", "embedding", "voyage-4-large", 12, 0, 0, 0));
    }
}
