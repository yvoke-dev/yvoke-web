package de.palsoftware.yvoke;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * The deployment manifests are the third place this application's configuration lives, after
 * {@code application.yml} and {@code .env.example}, and the only one no test used to look at.
 *
 * <p>
 * That gap had already produced a live defect. The ConfigMap declares three models routed to
 * {@code azure-openai-responses}; {@code LlmConfig} constructs a client for every route a model
 * names, and {@code AzureOpenAiResponsesLlmClient} rejects an absent endpoint rather than
 * defaulting — because openai-java would otherwise target {@code api.openai.com} and forward the
 * Azure key there. Neither the ConfigMap nor the sealed Secret supplied
 * {@code AZURE_OPENAI_ENDPOINT}, so the next rollout would have failed to start, and the
 * ConfigMap's own comment claimed the value was present. {@code LlmConfigTest} pins that behaviour
 * and passes; nothing checked that the manifest satisfied it. A behaviour test proves the code is
 * right about a configuration — it cannot prove the configuration is right.
 *
 * <p>
 * Reading the sealed Secret is safe and needs no key: sops encrypts values under
 * {@code encrypted_regex: ^(data|stringData)$} and leaves every key name in plaintext, which is
 * exactly the half this test needs.
 *
 * @see EnvExampleContractTest the same contract for the local Compose environment
 */
class K8sManifestContractTest {

    private static final Path CONFIGMAP = Path.of("k8s/app/yvoke-app/configmap.yaml");
    private static final Path SECRET = Path.of("k8s/app/yvoke-app/secrets/secret.sops.yaml");

    /**
     * Supplied by the {@code yvoke-postgres} ConfigMap or by an explicit {@code env:} entry in the
     * Deployment rather than resolved from {@code application.yml}.
     */
    private static final Set<String> SUPPLIED_ELSEWHERE =
        Set.of("POSTGRES_HOST", "POSTGRES_PORT", "POSTGRES_DATABASE");

    /**
     * Providers {@code LlmConfig.rejectRetired} refuses at startup. Their settings stay in
     * {@code application.yml} so re-enabling one is a small change, but shipping a value for them
     * in a manifest is how an operator ends up selecting a provider that stops the pod.
     */
    private static final Set<String> RETIRED_PREFIXES = Set.of("CLOUDFLARE_", "OPENROUTER_");

    private static String yaml;
    private static Map<String, String> configMap;
    private static Set<String> secretKeys;

    @BeforeAll
    static void load() throws Exception {
        try (InputStream in =
            K8sManifestContractTest.class.getResourceAsStream("/application.yml")) {
            assertThat(in).as("application.yml must be on the test classpath").isNotNull();
            yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        configMap = stringMap(section(CONFIGMAP, "data"));
        secretKeys = new TreeSet<>(stringMap(section(SECRET, "stringData")).keySet());
    }

    /**
     * A ConfigMap entry nothing reads is an instruction to configure something inert. This is what
     * {@code OPENAI_API_BASE} was: shipped in the ConfigMap and in every {@code .env}, while
     * {@code application.yml} excludes every OpenAI autoconfiguration and reads the value nowhere.
     */
    @Test
    void everyConfigMapKeyIsConsumedByTheApplication() {
        Set<String> orphans = new TreeSet<>();
        for (String name : configMap.keySet()) {
            if (!SUPPLIED_ELSEWHERE.contains(name) && !yaml.contains("${" + name)) {
                orphans.add(name);
            }
        }
        assertThat(orphans).as("the ConfigMap sets variables that nothing reads — remove them, or "
            + "add them to SUPPLIED_ELSEWHERE naming what consumes them").isEmpty();
    }

    /**
     * Declaring a route CONSTRUCTS its client, so the credentials have to ship in the same release.
     * Without them the pod does not degrade to another provider — it fails to start.
     */
    @Test
    void aDeclaredAzureRouteShipsItsEndpointAndKeyInTheSameRelease() {
        String routes = configMap.getOrDefault("AI_MODEL_ROUTES", "");
        boolean azureRouted = routes.contains("azure-openai-responses")
            || "azure-openai-responses".equals(configMap.get("AI_PROVIDER"));
        if (!azureRouted) {
            return;
        }
        assertThat(configMap).as("AI_MODEL_ROUTES sends a model to azure-openai-responses, so the "
            + "endpoint must ship in the same release — LlmConfig resolves it to \"\" otherwise and "
            + "AzureOpenAiResponsesLlmClient throws at construction, before the pod is ready")
            .containsKey("AZURE_OPENAI_ENDPOINT");
        assertThat(secretKeys)
            .as("AI_MODEL_ROUTES sends a model to azure-openai-responses, so the "
                + "API key must ship in the same release; add it with "
                + "`sops k8s/app/yvoke-app/secrets/secret.sops.yaml`")
            .contains("AZURE_OPENAI_API_KEY");
    }

    /** Nothing should hand an operator a value for a provider that refuses to start. */
    @Test
    void noRetiredProviderSettingIsShipped() {
        Set<String> shipped = new TreeSet<>();
        Stream.concat(configMap.keySet().stream(), secretKeys.stream())
            .filter(name -> RETIRED_PREFIXES.stream().anyMatch(name::startsWith))
            .forEach(shipped::add);
        assertThat(shipped).as("cloudflare-gemini and openrouter are rejected at startup; drop "
            + "these from the ConfigMap, and from the Secret with "
            + "`sops k8s/app/yvoke-app/secrets/secret.sops.yaml`").isEmpty();
    }

    /** The {@code data} / {@code stringData} block of a manifest, or an empty map. */
    private static Object section(Path file, String key) throws Exception {
        Map<String, Object> doc = new Yaml().load(Files.readString(file, StandardCharsets.UTF_8));
        assertThat(doc).as("%s must be a YAML document", file).isNotNull();
        return doc.get(key);
    }

    private static Map<String, String> stringMap(Object raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> map) {
            map.forEach((k, v) -> out.put(String.valueOf(k), v == null ? "" : String.valueOf(v)));
        }
        return out;
    }
}
