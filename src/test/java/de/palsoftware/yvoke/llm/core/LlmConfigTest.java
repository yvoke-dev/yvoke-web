package de.palsoftware.yvoke.llm.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.llm.core.service.AzureOpenAiResponsesLlmClient;
import de.palsoftware.yvoke.llm.core.service.GeminiLlmClient;
import de.palsoftware.yvoke.llm.core.service.LlmClient;
import de.palsoftware.yvoke.llm.core.service.ModelRoutingLlmClient;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * Pins how {@code app.ai.provider} and {@code app.ai.model-routes} select provider beans. Both are
 * set from environment variables per deployment, so their values are operator input that never
 * passes through a compiler.
 */
public class LlmConfigTest {

    /**
     * Two behaviours that only look cosmetic. The match is case-insensitive because the value is
     * typed by hand into a compose file or a Kubernetes manifest, where
     * {@code Azure-OpenAI-Responses} is a natural spelling; and an unrecognised value falls through
     * to Gemini instead of failing startup, because a typo must not take a running deployment down.
     *
     * <p>
     * Tightening the match to {@code equals} — the obvious tidy-up, since every value the repo
     * writes is already lowercase — does not throw and does not warn: the {@code log.info}
     * ("Configuring LLM client with provider: {}") line still prints the operator's spelling, so
     * the logs say {@code Azure-OpenAI-Responses} while the process talks to Gemini with a Gemini
     * key. Nothing else in the suite instantiates this factory: the ITs take the default property
     * or replace the bean wholesale with {@code @MockitoBean(name = "llmProviderClient")}, so no
     * other test ever passes it a string.
     */
    @Test
    public void providerSelectionIsCaseInsensitiveAndUnknownFallsBackToGemini() {
        LlmClient gemini = providerFor("Gemini");
        LlmClient responses = providerFor("Azure-OpenAI-Responses");
        LlmClient unknown = providerFor("totally-bogus");
        try {
            assertThat(defaultClientOf(gemini)).isExactlyInstanceOf(GeminiLlmClient.class);
            assertThat(defaultClientOf(responses))
                .as("mixed-case 'Azure-OpenAI-Responses' must still select the Responses client")
                .isExactlyInstanceOf(AzureOpenAiResponsesLlmClient.class);
            assertThat(defaultClientOf(unknown))
                .as("an unrecognised provider must fall back to Gemini, not fail startup")
                .isExactlyInstanceOf(GeminiLlmClient.class);
        } finally {
            close(gemini);
            close(responses);
            close(unknown);
        }
    }

    /**
     * A RETIRED provider is not the same thing as an unknown one, and must not share its fallback.
     * All three below were valid values that selected real clients, so a deployment can still be
     * carrying one — and letting it drop through to the Gemini fallback would silently answer with
     * a different provider, on a different key, having logged the operator's own spelling back at
     * them. That is tolerable for a typo, which never worked, and not for a value that did.
     *
     * <p>
     * The rejection is case-insensitive for the same reason the selection is: an exact match would
     * let {@code Azure-OpenAI} — the natural spelling in a hand-written manifest — miss the guard
     * and reach the Gemini fallback instead, which is precisely the outcome being prevented. All
     * three cases are therefore deliberately mixed-case.
     */
    @Test
    public void aRetiredProviderIsRejectedRatherThanSilentlyAnsweredByGemini() {
        assertThatThrownBy(() -> providerFor("Azure-OpenAI"))
            .isInstanceOf(IllegalStateException.class)
            .as("the message must name the replacement, or the operator has nowhere to go")
            .hasMessageContaining("azure-openai-responses");

        assertThatThrownBy(() -> providerFor("OpenRouter"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("openrouter");

        assertThatThrownBy(() -> providerFor("Cloudflare-Gemini"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("cloudflare-gemini");
    }

    /**
     * The one provider that must NOT fall back silently. The SDK decides Azure-versus-public OpenAI
     * purely from the endpoint: with none set it targets {@code api.openai.com} and sends the
     * credential as a bearer token. A forgotten {@code AZURE_OPENAI_ENDPOINT} would therefore hand
     * the Azure key to a third party rather than merely fail.
     *
     * <p>
     * The endpoint here is the literal {@code placeholder-…} that {@code application.yml} ships,
     * which is what the {@code !value.contains("placeholder")} clause in {@code isUsable} exists to
     * treat as absent — that clause is also what lets {@code resolveKey} fall through to the
     * environment variable where real deployments keep the value, so this is its only coverage.
     */
    @Test
    public void aMissingAzureEndpointFailsStartupInsteadOfTargetingPublicOpenAi() {
        assertThatThrownBy(
            () -> build("azure-openai-responses", "", "placeholder-azure-openai-endpoint"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("endpoint")
            .as("a failure about configuration must never quote the credential back")
            .hasMessageNotContaining("test-azure-key");
    }

    /** The configuration this repository ships must build a context without special-casing. */
    @Test
    public void theShippedDefaultProviderStartsWithNothingConfigured() {
        LlmClient client = config().llmProviderClient("gemini", "", new ObjectMapper(),
            "placeholder-gemini-api-key", false, "low", "https://gemini.example",
            "placeholder-azure-openai-endpoint", "placeholder-azure-openai-api-key", false, "low",
            "");
        try {
            assertThat(defaultClientOf(client))
                .as("an unconfigured dev box must still build a context")
                .isExactlyInstanceOf(GeminiLlmClient.class);
        } finally {
            close(client);
        }
    }

    // ------------------------------------------------------------------------
    // model -> client routing
    // ------------------------------------------------------------------------

    /**
     * The provider bean is ALWAYS the router, whether or not any model is mapped.
     *
     * <p>
     * Returning the single client unwrapped when the table is empty was the obvious optimisation
     * and it made {@code app.ai.provider} mean two different things: the only client in one
     * configuration, and merely the fallback in another. One code path is worth more than one
     * object — the empty-table router is a map lookup per call — and it means the bean's type no
     * longer depends on configuration, which is the kind of difference that makes a bug reproduce
     * in one environment and not another.
     */
    @Test
    public void theProviderBeanIsAlwaysTheRouterEvenWithNoRoutesMapped() {
        LlmClient empty = providerFor("gemini");
        LlmClient mapped = build("gemini", "{\"gpt-5.6-luna\": \"azure-openai-responses\"}");
        try {
            assertThat(empty).isExactlyInstanceOf(ModelRoutingLlmClient.class);
            assertThat(mapped).isExactlyInstanceOf(ModelRoutingLlmClient.class);
            assertThat(((ModelRoutingLlmClient) empty).clientFor("anything"))
                .as("with nothing mapped every model takes the default route")
                .isExactlyInstanceOf(GeminiLlmClient.class);
        } finally {
            close(empty);
            close(mapped);
        }
    }

    @Test
    public void aMappedModelIsRoutedWhileEverythingElseUsesTheDefault() {
        LlmClient client = build("gemini", "{\"gpt-5.6-luna\": \"azure-openai-responses\"}");
        try {
            assertThat(client).isExactlyInstanceOf(ModelRoutingLlmClient.class);
            ModelRoutingLlmClient router = (ModelRoutingLlmClient) client;

            assertThat(router.clientFor("gpt-5.6-luna"))
                .isExactlyInstanceOf(AzureOpenAiResponsesLlmClient.class);
            assertThat(router.clientFor("gemini-3.6-flash"))
                .as("an unmapped model falls to the default route")
                .isExactlyInstanceOf(GeminiLlmClient.class);
        } finally {
            close(client);
        }
    }

    /**
     * Only the routes actually named are built. Constructing every client eagerly would fail on the
     * shipped placeholder Azure endpoint and take every Spring context — and CI — with it.
     */
    @Test
    public void aRouteIsBuiltOnlyWhenSomeModelNamesIt() {
        LlmClient unrouted = build("gemini", "", "placeholder-azure-openai-endpoint");
        try {
            assertThat(defaultClientOf(unrouted))
                .as("no model names the Azure route, so its unusable endpoint is not reached")
                .isExactlyInstanceOf(GeminiLlmClient.class);
        } finally {
            close(unrouted);
        }

        assertThatThrownBy(() -> build("gemini", "{\"gpt-5.6-luna\": \"azure-openai-responses\"}",
            "placeholder-azure-openai-endpoint")).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("endpoint");
    }

    /**
     * A {@link LlmConfig} whose environment lookup finds nothing, so a test asserting on the
     * fail-closed rules pins the code rather than the machine it runs on: {@code resolveKey} falls
     * through to {@code GEMINI_API_KEY} / {@code AZURE_OPENAI_ENDPOINT} for exactly the values
     * these tests must supply as placeholders.
     */
    private static LlmConfig config() {
        UnaryOperator<String> nothingExported = name -> null;
        return new LlmConfig(nothingExported);
    }

    /** What the router would use for a model nobody mapped, i.e. the declared default route. */
    private static LlmClient defaultClientOf(LlmClient bean) {
        return ((ModelRoutingLlmClient) bean).clientFor("some-unmapped-model");
    }

    private static LlmClient providerFor(String provider) {
        return build(provider, "");
    }

    private static LlmClient build(String provider, String routes) {
        return build(provider, routes, "https://azure.example");
    }

    /**
     * Non-placeholder dummy keys are passed so {@code resolveKey} takes the configured value and
     * never reads the developer's real {@code GEMINI_API_KEY} from the environment. Neither
     * constructor performs network I/O — they build an SDK client and its connection pool.
     */
    private static LlmClient build(String provider, String routes, String azureEndpoint) {
        return config().llmProviderClient(provider, routes, new ObjectMapper(), "test-gemini-key",
            false, "low", "https://gemini.example", azureEndpoint, "test-azure-key", false, "low",
            "");
    }

    private static void close(LlmClient client) {
        if (client instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception ignored) {
                // Nothing useful to do in a unit test teardown.
            }
        }
    }
}
