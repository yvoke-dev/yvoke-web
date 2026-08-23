package de.palsoftware.yvoke.llm.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The model → client mapping, parsed from one operator-typed property.
 *
 * <p>
 * Strict where {@code app.ai.provider} is lenient, and deliberately so: that property has
 * deployments in the wild whose typos must not take them down, whereas this one is new and ships
 * empty, so rejecting an unknown route id costs nothing and prevents a model being answered by
 * silently the wrong provider.
 */
class LlmModelRoutesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static LlmModelRoutes parse(String json) {
        return LlmModelRoutes.parse(MAPPER, json);
    }

    @Test
    void aBlankOrEmptyMappingRoutesNothing() {
        assertThat(parse(null).isEmpty()).isTrue();
        assertThat(parse("").isEmpty()).isTrue();
        assertThat(parse("   ").isEmpty()).isTrue();
        assertThat(parse("{}").isEmpty())
            .as("an operator who cleared the mapping wrote {} rather than leaving it blank")
            .isTrue();
        assertThat(parse("").routeFor("anything")).isEmpty();
    }

    @Test
    void eachPairMapsItsModelToItsRoute() {
        LlmModelRoutes routes = parse("""
            {"gemini-3.6-flash": "gemini", "gpt-5.6-luna": "azure-openai-responses"}""");

        assertThat(routes.routeFor("gemini-3.6-flash")).contains(LlmRouteId.GEMINI);
        assertThat(routes.routeFor("gpt-5.6-luna")).contains(LlmRouteId.AZURE_OPENAI_RESPONSES);
        assertThat(routes.declaredRoutes()).containsExactlyInAnyOrder(LlmRouteId.GEMINI,
            LlmRouteId.AZURE_OPENAI_RESPONSES);
    }

    /** A ConfigMap value is hand-formatted; surrounding space must not change the meaning. */
    @Test
    void surroundingWhitespaceIsTolerated() {
        LlmModelRoutes routes = parse("""
            { "gemini-3.6-flash" : " gemini " , "gpt-5.6-luna":"azure-openai-responses" }  """);

        assertThat(routes.routeFor("gemini-3.6-flash")).contains(LlmRouteId.GEMINI);
        assertThat(routes.routeFor("gpt-5.6-luna")).contains(LlmRouteId.AZURE_OPENAI_RESPONSES);
    }

    /**
     * A manifest is hand-written, so {@code Gemini-3.6-Flash} is a natural spelling of the same
     * deployment. Matching exactly would leave that model unrouted and silently answered by the
     * default route instead — the failure being a wrong ANSWER rather than an error.
     */
    @Test
    void modelLookupIsCaseInsensitive() {
        LlmModelRoutes routes = parse("{\"GPT-5.6-Luna\": \"azure-openai-responses\"}");

        assertThat(routes.routeFor("gpt-5.6-luna")).contains(LlmRouteId.AZURE_OPENAI_RESPONSES);
        assertThat(routes.routeFor("GPT-5.6-LUNA")).contains(LlmRouteId.AZURE_OPENAI_RESPONSES);
    }

    @Test
    void anUnknownRouteIdFailsRatherThanBeingIgnored() {
        assertThatThrownBy(() -> parse("{\"gpt-5.6-luna\": \"azure-openai\"}"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("azure-openai")
            .hasMessageContaining("gpt-5.6-luna");
    }

    @Test
    void malformedJsonFailsRatherThanBeingIgnored() {
        assertThatThrownBy(() -> parse("{\"gpt-5.6-luna\": "))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("app.ai.model-routes");
    }

    /**
     * A JSON array or a bare string parses cleanly and means nothing here. Accepting it would route
     * nothing while looking configured, which is the silent failure this whole class avoids.
     */
    @Test
    void aJsonValueThatIsNotAnObjectFails() {
        assertThatThrownBy(() -> parse("[\"gemini\"]")).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("object");
        assertThatThrownBy(() -> parse("\"gemini\"")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aNonStringRouteFails() {
        assertThatThrownBy(() -> parse("{\"gpt-5.6-luna\": 7}"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("gpt-5.6-luna");
        assertThatThrownBy(() -> parse("{\"gpt-5.6-luna\": null}"))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aBlankModelNameFails() {
        assertThatThrownBy(() -> parse("{\"  \": \"gemini\"}"))
            .isInstanceOf(IllegalStateException.class);
    }

    /**
     * Two rules for one model is not a preference order, it is an ambiguity — and whichever entry
     * won would depend on parse order, which nothing states.
     */
    @Test
    void aDuplicateModelFailsRatherThanLettingOneEntryWinSilently() {
        assertThatThrownBy(() -> parse(
            "{\"gpt-5.6-luna\": \"gemini\", \"GPT-5.6-LUNA\": \"azure-openai-responses\"}"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("gpt-5.6-luna");
    }

    @Test
    void anUnmappedModelHasNoRoute() {
        LlmModelRoutes routes = parse("{\"gpt-5.6-luna\": \"azure-openai-responses\"}");

        assertThat(routes.routeFor("gemini-3.6-flash")).isEqualTo(Optional.empty());
        assertThat(routes.routeFor(null)).isEmpty();
    }

    @Test
    void routeIdsAreReadFromTheirWireSpellingCaseInsensitively() {
        assertThat(LlmRouteId.fromWire("gemini")).contains(LlmRouteId.GEMINI);
        assertThat(LlmRouteId.fromWire("Azure-OpenAI-Responses"))
            .contains(LlmRouteId.AZURE_OPENAI_RESPONSES);
        assertThat(LlmRouteId.fromWire("cloudflare-gemini")).isEmpty();
        assertThat(LlmRouteId.fromWire(null)).isEmpty();
        assertThat(LlmRouteId.GEMINI.wire()).isEqualTo("gemini");
        assertThat(LlmRouteId.AZURE_OPENAI_RESPONSES.wire()).isEqualTo("azure-openai-responses");
    }
}
