package de.palsoftware.yvoke.llm.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.llm.core.service.AzureOpenAiLlmClient;
import de.palsoftware.yvoke.llm.core.service.CloudflareGeminiLlmClient;
import de.palsoftware.yvoke.llm.core.service.GeminiLlmClient;
import de.palsoftware.yvoke.llm.core.service.LlmClient;
import de.palsoftware.yvoke.llm.core.service.OpenRouterLlmClient;
import org.junit.jupiter.api.Test;
import com.google.genai.ApiClient;
import java.lang.reflect.Field;
import java.util.function.UnaryOperator;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

/**
 * Pins how {@code app.ai.provider} selects the provider bean. The property is set from an
 * environment variable ({@code AI_PROVIDER}) per deployment, so its value is operator input that
 * never passes through a compiler.
 */
public class LlmConfigTest {

    /**
     * Two behaviours that only look cosmetic. The match is case-insensitive because the value is
     * typed by hand into a compose file or a Kubernetes manifest, where {@code OpenRouter} and
     * {@code Cloudflare-Gemini} are the natural spellings; and an unrecognised value falls through
     * to the plain Gemini client instead of failing startup, because a typo must not take a running
     * deployment down.
     *
     * <p>
     * The failure mode this prevents is that both of those are <b>silent</b>. Tightening the match
     * to {@code equals} — the obvious tidy-up, since every value the repo writes is already
     * lowercase — does not throw and does not log a warning: the {@code log.info("Configuring LLM
     * client with provider: {}")} line still prints the operator's spelling, so the logs say
     * {@code OpenRouter} while the process is talking to Gemini with a Gemini key. The deployment
     * then either fails every call with an auth error whose text names the wrong provider, or —
     * worse, and this is the live configuration — silently leaves the Cloudflare AI Gateway, which
     * is where cost accounting, {@code cf-aig-metadata} attribution and the gateway cache all live.
     * Nothing else in the suite instantiates this factory: the ITs get the provider bean from the
     * default {@code cloudflare-gemini} property or replace it wholesale with
     * {@code @MockitoBean(name = "llmProviderClient")}, so no other test ever passes it a string.
     *
     * <p>
     * {@code isExactlyInstanceOf} is load-bearing for the fallback assertion:
     * {@link CloudflareGeminiLlmClient} <em>extends</em> {@link GeminiLlmClient}, so a plain
     * {@code isInstanceOf(GeminiLlmClient.class)} would be satisfied by the Cloudflare client and
     * the two branches would be indistinguishable.
     */
    @Test
    public void providerSelectionIsCaseInsensitiveAndUnknownFallsBackToGemini() {
        LlmClient openRouter = providerFor("OpenRouter");
        LlmClient cloudflare = providerFor("CLOUDFLARE-GEMINI");
        LlmClient azure = providerFor("Azure-OpenAI");
        LlmClient unknown = providerFor("totally-bogus");
        try {
            assertThat(openRouter).as("mixed-case 'OpenRouter' must still select OpenRouter")
                .isExactlyInstanceOf(OpenRouterLlmClient.class);
            assertThat(cloudflare)
                .as("upper-case 'CLOUDFLARE-GEMINI' must still select the gateway")
                .isExactlyInstanceOf(CloudflareGeminiLlmClient.class);
            assertThat(azure).as("mixed-case 'Azure-OpenAI' must still select Azure OpenAI")
                .isExactlyInstanceOf(AzureOpenAiLlmClient.class);
            assertThat(unknown)
                .as("an unrecognised provider must fall back to plain Gemini, not fail startup")
                .isExactlyInstanceOf(GeminiLlmClient.class);
        } finally {
            close(openRouter);
            close(cloudflare);
            close(azure);
            close(unknown);
        }
    }

    /**
     * The one provider that must NOT fall back silently. The Azure SDK decides Azure-versus-public
     * OpenAI purely from the endpoint: with none set it targets {@code api.openai.com} and sends
     * the credential as a bearer token. A forgotten {@code AZURE_OPENAI_ENDPOINT} would therefore
     * hand the Azure key to a third party rather than merely fail, so the placeholder that
     * {@code isUsable} strips must end in a startup failure, not a working-looking client.
     */
    @Test
    public void aMissingAzureEndpointFailsStartupInsteadOfTargetingPublicOpenAi() {
        assertThatThrownBy(() -> config().llmProviderClient(deployment(), "azure-openai",
            new ObjectMapper(), "https://openrouter.example/api/v1", "test-openrouter-key",
            "test-gemini-key", false, "low", "https://gemini.example", "test-cf-account",
            "test-cf-gateway", "test-cf-gateway-token", "test-cf-gemini-key", false, "low",
            "placeholder-azure-openai-endpoint", "test-azure-key", false, "low", ""))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("endpoint");
    }

    /**
     * Every third-party credential and account id in {@code application.yml} ships a literal
     * {@code placeholder-…} default — {@code app.ai.cloudflare-gemini.account-id},
     * {@code gateway-id}, {@code gateway-token} and {@code api-key}, plus the OpenRouter and Voyage
     * keys. An environment that forgets one variable therefore starts with a fully populated but
     * entirely fictional configuration, and the {@code !value.contains("placeholder")} clause in
     * {@code isUsable} is the only line in the codebase that treats such a value as absent.
     *
     * <p>
     * It does two jobs. It stops a made-up account id and a made-up bearer token from being sent to
     * a real third party, and — the part that is easy to miss — it is what lets {@code resolveKey}
     * fall through to {@code CLOUDFLARE_ACCOUNT_ID} / {@code GEMINI_API_KEY}, which is where every
     * real deployment actually keeps those values. Deleting the clause as a redundant-looking extra
     * on top of the null/blank check inverts that: the configured placeholder now wins over the
     * environment variable, so a deployment that worked before the edit stops working after it, for
     * a reason that appears nowhere in the error.
     *
     * <p>
     * Nothing else would notice. Startup succeeds; {@code warnIfMissing} stays silent because a
     * placeholder is not blank; the {@code log.info("Configuring LLM client with provider: {}")}
     * line reads completely normally. The first symptom is production traffic aimed at
     * {@code https://gateway.ai.cloudflare.com/v1/placeholder-cf-account-id/…} carrying
     * {@code cf-aig-authorization: Bearer placeholder-cf-gateway-token} — requests to a real third
     * party, from a real deployment, made of strings that are checked into this repository. The
     * sibling test in this class only ever passes usable values, and every IT replaces the provider
     * wholesale with {@code @MockitoBean(name = "llmProviderClient")}, so this factory is never
     * otherwise exercised with a placeholder.
     *
     * <p>
     * The API key is deliberately supplied as a usable dummy rather than as a placeholder:
     * asserting on it would compare — and on failure print — whatever real {@code GEMINI_API_KEY}
     * the developer has exported. The base URL carries the account and gateway ids, which are not
     * secret, and is enough to pin the rule.
     *
     * <p>
     * The environment is supplied by {@link #config()} rather than read from the process, because
     * {@code resolveKey} falls through to {@code CLOUDFLARE_ACCOUNT_ID} for exactly the value this
     * test must pass. Reading the real one made the assertion depend on the developer's shell: a
     * machine exporting the deployment's own variables saw "nothing was thrown", and a machine
     * exporting only some of them saw the throw name a different setting.
     */
    @Test
    public void aConfiguredPlaceholderValueIsNeverForwardedAsACredential() {
        // Behaviour change: a placeholder account/gateway id used to be silently treated as absent
        // and formatted into the URL as an empty segment, giving ".../v1///google-ai-studio" — a
        // 404 on every call, from the first message, with nothing at startup pointing at it. The
        // placeholder must still never be FORWARDED (that is what this test has always guarded);
        // it now fails closed instead of producing a client that cannot work.
        IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> config().llmProviderClient(deployment(), "cloudflare-gemini", new ObjectMapper(),
                "https://openrouter.example/api/v1", "test-openrouter-key", "test-gemini-key",
                false, "low", "https://gemini.example", "placeholder-cf-account-id",
                "placeholder-cf-gateway-id", "test-cf-gateway-token", "test-cf-gemini-key", false,
                "low", "https://azure.example", "test-azure-key", false, "low", ""));

        assertThat(e).hasMessageContaining("account id")
            .as("the failure must name which setting is missing, not merely that one is");
        assertThat(e.getMessage()).doesNotContain("placeholder-cf-account-id")
            .doesNotContain("test-cf-gateway-token");
    }

    /**
     * A blank gateway TOKEN is the quiet case: the URL is right and the request is unauthenticated.
     */
    @Test
    public void aBlankGatewayTokenFailsStartupRatherThanSendingAnUnauthenticatedRequest() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> config().llmProviderClient(deployment(), "cloudflare-gemini", new ObjectMapper(),
                "https://openrouter.example/api/v1", "test-openrouter-key", "test-gemini-key",
                false, "low", "https://gemini.example", "real-account", "real-gateway", "  ",
                "test-cf-gemini-key", false, "low", "https://azure.example", "test-azure-key",
                false, "low", ""));

        assertThat(e).hasMessageContaining("gateway token");
    }

    /**
     * The regression the fail-closed check above caused, and the case its own tests could not see.
     *
     * <p>
     * {@code app.ai.provider} defaults to {@code cloudflare-gemini} and the three gateway values
     * default to {@code placeholder-…} literals that {@code isUsable} treats as absent — so the
     * configuration this repository <b>ships</b> is precisely the one the check rejects. Throwing
     * unconditionally aborted context refresh for all 70 {@code @SpringBootTest} classes bar the
     * six that replace {@code llmProviderClient}, and for CI, which sets no {@code CLOUDFLARE_*}.
     * Nothing in the unit tier noticed, because every assertion pointed at the throw and none at
     * startup surviving; the whole failure lived one tier away, in
     * {@code ./mvnw verify -Pit-tests}.
     *
     * <p>
     * The {@code test} profile is what every IT context runs under
     * ({@code PostgresTestContainerInitializer} adds it for all of them) and {@code local} is what
     * {@code docker-compose} sets, so gating on {@link DevProfiles} is the same seam
     * {@code SecretCipher} and {@code SecurityConfig} already use to stay strict in production
     * while remaining startable on a dev box.
     */
    @Test
    public void theShippedDefaultsStillStartUnderADevelopmentProfile() {
        MockEnvironment devBox = new MockEnvironment();
        devBox.setActiveProfiles("test");

        LlmClient client = config().llmProviderClient(devBox, "cloudflare-gemini",
            new ObjectMapper(), "https://openrouter.example/api/v1", "test-openrouter-key",
            "test-gemini-key", false, "low", "https://gemini.example", "placeholder-cf-account-id",
            "placeholder-cf-gateway-id", "placeholder-cf-gateway-token", "test-cf-gemini-key",
            false, "low", "https://azure.example", "test-azure-key", false, "low", "");
        try {
            assertThat(client).as("an unconfigured dev box must still build a context")
                .isExactlyInstanceOf(CloudflareGeminiLlmClient.class);
        } finally {
            close(client);
        }
    }

    /**
     * A {@link LlmConfig} whose environment lookup finds nothing, so a test asserting on the
     * fail-closed rules pins the code rather than the machine it runs on.
     */
    private static LlmConfig config() {
        UnaryOperator<String> nothingExported = name -> null;
        return new LlmConfig(nothingExported);
    }

    /** An environment with no development profile active — i.e. a real deployment. */
    private static Environment deployment() {
        return new MockEnvironment();
    }

    /**
     * Reads the base URL the genai SDK will actually issue requests against:
     * {@code GeminiLlmClient.client} -> {@code Client.apiClient} -> the public
     * {@code ApiClient.httpOptions()}. Asserting on the constructor argument instead would prove
     * nothing about what the SDK ends up holding, since {@code ApiClient.mergeHttpOptions} overlays
     * our options onto the SDK's own defaults.
     */
    private static String baseUrlOf(LlmClient client) throws Exception {
        Field clientField = GeminiLlmClient.class.getDeclaredField("client");
        clientField.setAccessible(true);
        Object genaiClient = clientField.get(client);

        Field apiClientField = genaiClient.getClass().getDeclaredField("apiClient");
        apiClientField.setAccessible(true);
        ApiClient apiClient = (ApiClient) apiClientField.get(genaiClient);

        return apiClient.httpOptions().baseUrl().orElse("");
    }

    /**
     * Non-placeholder dummy keys are passed for every provider so {@code LlmConfig.resolveKey}
     * takes the configured value and never reads the developer's real {@code GEMINI_API_KEY} /
     * {@code OPENROUTER_API_KEY} from the environment. None of the three constructors performs any
     * network I/O — they only build an SDK client and its connection pool.
     */
    private static LlmClient providerFor(String provider) {
        return config().llmProviderClient(deployment(), provider, new ObjectMapper(),
            "https://openrouter.example/api/v1", "test-openrouter-key", "test-gemini-key", false,
            "low", "https://gemini.example", "test-cf-account", "test-cf-gateway",
            "test-cf-gateway-token", "test-cf-gemini-key", false, "low", "https://azure.example",
            "test-azure-key", false, "low", "");
    }

    /** Releases the SDK connection pools; {@code OpenRouterLlmClient} is not closeable. */
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
