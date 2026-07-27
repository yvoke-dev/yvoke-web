package de.palsoftware.yvoke.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

public class RerankClientTest {

    private MockRestServiceServer server;
    private RerankClient rerankClient;

    @BeforeEach
    public void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        rerankClient = new RerankClient(builder, "mock-key-for-skeleton", "rerank-2.5",
            "https://api.voyageai.com/v1");
    }

    @Test
    public void testRerankSuccess() {
        String jsonResponse = """
            {
              "object": "list",
              "data": [
                {
                  "index": 0,
                  "relevance_score": 0.95
                },
                {
                  "index": 1,
                  "relevance_score": 0.12
                }
              ],
              "model": "rerank-2.5",
              "usage": {
                "total_tokens": 15
              }
            }
            """;

        server.expect(requestTo("https://api.voyageai.com/v1/rerank"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", "Bearer mock-key-for-skeleton"))
            .andRespond(withSuccess(jsonResponse, MediaType.APPLICATION_JSON));

        List<RerankClient.RerankResult> results =
            rerankClient.rerank("query", List.of("Paris is capital", "Berlin is capital"));

        assertThat(results).hasSize(2);
        assertThat(results.get(0).index()).isEqualTo(0);
        assertThat(results.get(0).relevanceScore()).isEqualTo(0.95);
        assertThat(results.get(1).index()).isEqualTo(1);
        assertThat(results.get(1).relevanceScore()).isEqualTo(0.12);
        server.verify();
    }

    @Test
    public void testRerankEmptyDocuments() {
        List<RerankClient.RerankResult> results = rerankClient.rerank("query", List.of());
        assertThat(results).isEmpty();
    }

    @Test
    public void testRerankInvalidInput() {
        assertThatThrownBy(() -> rerankClient.rerank("", List.of("doc")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rerankClient.rerank(null, List.of("doc")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void testRerankServerError() {
        server.expect(requestTo("https://api.voyageai.com/v1/rerank"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                .withServerError());

        assertThatThrownBy(() -> rerankClient.rerank("query", List.of("doc")))
            .isInstanceOf(org.springframework.web.client.HttpServerErrorException.class);
        server.verify();
    }

    @Test
    public void testRerankEmptyResponseBody() {
        server.expect(requestTo("https://api.voyageai.com/v1/rerank"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> rerankClient.rerank("query", List.of("doc")))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining(
                "Failed to get reranking response from Voyage AI; empty response.");
        server.verify();
    }

    @Test
    public void testRerank_publishesModelUsageEvent() {
        org.springframework.context.ApplicationEventPublisher mockPublisher =
            org.mockito.Mockito.mock(org.springframework.context.ApplicationEventPublisher.class);

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(builder).build();
        RerankClient clientWithPublisher = new RerankClient(builder, "mock-key", "rerank-2.5",
            "https://api.voyageai.com/v1", mockPublisher);

        String jsonResponse = """
            {
              "object": "list",
              "data": [
                {
                  "object": "rerank",
                  "relevance_score": 0.9,
                  "index": 0
                }
              ],
              "model": "rerank-2.5",
              "usage": {
                "total_tokens": 15
              }
            }
            """;

        mockServer.expect(requestTo("https://api.voyageai.com/v1/rerank"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess(jsonResponse, MediaType.APPLICATION_JSON));

        List<RerankClient.RerankResult> results =
            clientWithPublisher.rerank("query", List.of("doc1"));

        assertThat(results).hasSize(1);
        mockServer.verify();

        org.mockito.Mockito.verify(mockPublisher)
            .publishEvent(new de.palsoftware.yvoke.llm.core.event.LlmCallLoggedEvent("rerank",
                "rerank", "rerank-2.5", 15, 0, 0, 0));
    }
}
