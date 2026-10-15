package de.palsoftware.yvoke.rag.core.websearch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.tool.definition.ToolDefinition;

class WebSearchToolCallbackTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void toolDefinitionExposesOnlyQueryParameterWithAdditionalPropertiesFalse() throws Exception {
        WebSearchService service = mock(WebSearchService.class);
        WebSearchToolCallback callback = new WebSearchToolCallback(service, objectMapper);

        ToolDefinition def = callback.getToolDefinition();
        assertThat(def.name()).isEqualTo("WebSearch");
        assertThat(def.description()).isNotBlank();

        JsonNode schema = objectMapper.readTree(def.inputSchema());
        assertThat(schema.path("type").asText()).isEqualTo("object");
        assertThat(schema.path("properties").fieldNames()).toIterable().containsExactly("query");
        assertThat(schema.at("/properties/query/maxLength").asInt()).isEqualTo(500);
        assertThat(schema.path("properties").has("allowed_domains")).isFalse();
        assertThat(schema.path("properties").has("blocked_domains")).isFalse();
        assertThat(schema.path("required")).hasSize(1);
        assertThat(schema.path("required").get(0).asText()).isEqualTo("query");
        assertThat(schema.path("additionalProperties").asBoolean(true)).isFalse();
    }

    @Test
    void callReturnsCleanJsonResultsWithoutInternalChunkIdMarkers() throws Exception {
        WebSearchService service = mock(WebSearchService.class);
        when(service.isAvailable()).thenReturn(true);
        when(service.search("OIM 9.3.1 hotfix 4380945"))
            .thenReturn(List.of(new WebSearchResult("KB 4380945",
                "https://support.oneidentity.com/kb/4380945", "Hotfix advisory for OIM 9.3.1.")));

        WebSearchToolCallback callback = new WebSearchToolCallback(service, objectMapper);
        String output = callback.call("{\"query\": \"  OIM 9.3.1 hotfix 4380945  \"}");

        assertThat(output).doesNotContain("_(id=").doesNotContain("[chunk_id=");
        JsonNode json = objectMapper.readTree(output);
        assertThat(json.path("query").asText()).isEqualTo("OIM 9.3.1 hotfix 4380945");
        assertThat(json.path("results")).hasSize(1);
        assertThat(json.at("/results/0/title").asText()).isEqualTo("KB 4380945");
        assertThat(json.at("/results/0/url").asText())
            .isEqualTo("https://support.oneidentity.com/kb/4380945");
        assertThat(json.at("/results/0/snippet").asText())
            .isEqualTo("Hotfix advisory for OIM 9.3.1.");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(
        strings = {"   ", "{}", "{\"query\": \"\"}", "{\"query\": \"   \"}", "{\"query\": null}"})
    void callReturnsValidationErrorForMissingOrBlankQuery(String args) {
        WebSearchService service = mock(WebSearchService.class);
        when(service.isAvailable()).thenReturn(true);
        WebSearchToolCallback callback = new WebSearchToolCallback(service, objectMapper);

        assertThat(callback.call(args)).isEqualTo("Error: 'query' is required for WebSearch.");
    }

    @Test
    void callReturnsValidationErrorWhenQueryExceedsMaxLength() {
        WebSearchService service = mock(WebSearchService.class);
        when(service.isAvailable()).thenReturn(true);
        WebSearchToolCallback callback = new WebSearchToolCallback(service, objectMapper);

        String oversized = "q".repeat(501);
        assertThat(callback.call("{\"query\": \"" + oversized + "\"}"))
            .isEqualTo("Error: 'query' exceeds maximum length of 500 characters.");
    }

    @Test
    void callReturnsParseErrorForMalformedJsonArguments() {
        WebSearchService service = mock(WebSearchService.class);
        WebSearchToolCallback callback = new WebSearchToolCallback(service, objectMapper);

        assertThat(callback.call("{not-json")).startsWith("Error parsing arguments:");
    }

    @Test
    void callReturnsDisabledErrorWhenServiceIsNullOrUnavailable() {
        WebSearchToolCallback nullServiceCallback = new WebSearchToolCallback(null, objectMapper);
        assertThat(nullServiceCallback.call("{\"query\": \"OIM\"}"))
            .isEqualTo("Error: WebSearch is not enabled in this deployment.");

        WebSearchService unavailableService = mock(WebSearchService.class);
        when(unavailableService.isAvailable()).thenReturn(false);
        WebSearchToolCallback unavailableCallback =
            new WebSearchToolCallback(unavailableService, objectMapper);
        assertThat(unavailableCallback.call("{\"query\": \"OIM\"}"))
            .isEqualTo("Error: WebSearch is not enabled in this deployment.");
    }

    @Test
    void callPropagatesRuntimeExceptionsForSec17Boundary() {
        WebSearchService service = mock(WebSearchService.class);
        when(service.isAvailable()).thenReturn(true);
        when(service.search("OIM"))
            .thenThrow(new IllegalStateException("HTTP 502 from upstream internal-host"));

        WebSearchToolCallback callback = new WebSearchToolCallback(service, objectMapper);

        assertThatThrownBy(() -> callback.call("{\"query\": \"OIM\"}"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("502");
    }
}
