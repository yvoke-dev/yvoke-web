package de.palsoftware.yvoke;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.chat.orchestration.OrchestratorProperties;
import de.palsoftware.yvoke.llm.core.LlmModelRoutes;
import de.palsoftware.yvoke.llm.core.LlmRouteId;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Pins the handful of {@code application.yml} settings whose value is a security decision rather
 * than a preference. Configuration is the one part of this application that no compiler, no
 * ArchUnit rule and no controller test looks at: a one-word edit here changes production behaviour,
 * passes every existing test, and is invisible in a diff review unless the reviewer already knows
 * why the word was chosen. Each assertion below therefore carries the reason the value cannot
 * drift.
 *
 * <p>
 * The file is read from the classpath (i.e. the copy under {@code target/classes}), so it is the
 * same bytes Spring Boot loads at startup, and it is parsed with SnakeYAML — the parser Spring Boot
 * itself uses for this file — rather than by pattern-matching lines.
 */
public class ApplicationYamlInvariantsTest {

    private static Map<String, Object> config;

    @BeforeAll
    public static void loadApplicationYaml() throws Exception {
        try (InputStream in =
            ApplicationYamlInvariantsTest.class.getResourceAsStream("/application.yml")) {
            assertThat(in).as("application.yml must be on the test classpath").isNotNull();
            config = new Yaml().load(in);
        }
    }

    /**
     * {@code app.ai.kg.max-tokens} is the completion budget for the graph-extraction call, whose
     * entire output is one strict-JSON document of entities and relationships for a chunk. The
     * model is not told the budget, so a budget that is too small does not produce a smaller graph
     * — it produces a graph cut off mid-token.
     *
     * <p>
     * What happens next is the reason this number is pinned rather than tuned. The truncated JSON
     * makes {@code DocumentKgExtractor.parseJson} throw {@code "Unparseable KG extraction JSON"};
     * the chunk is retried with a corrective prompt, which truncates in exactly the same place
     * because the budget has not changed; and after {@code app.ai.kg.max-attempts} the chunk is
     * discarded — {@code new ChunkOutcome(index, List.of(), List.of(), true)} — behind a single
     * {@code log.warn}. The job then completes successfully. So the visible outcome of halving this
     * value is a knowledge graph quietly missing a share of its entities and edges, with every MCP
     * graph tool answering "not found" for content that was ingested without error, while the
     * ingest costs roughly twice as many LLM calls as before because each doomed chunk now burns
     * its whole retry budget.
     *
     * <p>
     * Nothing else notices. The extractor's own tests feed it canned JSON instead of a real model,
     * and the ingest ITs assert that a job reaches {@code completed}, which it does. The only
     * signal is a warn line per lost chunk and a {@code skippedEntities} count on the job-detail
     * page that nobody reads when the job is green.
     */
    /**
     * The orchestrator's two limits had four definitions each and only a human kept them aligned —
     * so when the review-round limit was raised from 2 to 3, one of the four moved. The other three
     * (the DB column default, the admin POST endpoint's {@code defaultValue}, the admin form and
     * its two JS prefill paths) went on writing 2, and a profile created through the admin UI
     * silently ran with the old limit. Nothing failed; the local DB rows happened to carry 3, so
     * there was nothing to notice.
     *
     * <p>
     * The controller and the template now carry no number at all, which leaves exactly two: this
     * yaml and the record's own fallback for when the key is absent. Two is the minimum — a
     * {@code @ConfigurationProperties} record has to survive an empty environment — so the drift is
     * closed by comparing them rather than by deleting one. Same shape and same remedy as the
     * {@code DEFAULT_DEV_API_KEY} contract in {@code SecurityConfigMockAuthGuardTest}.
     */
    @Test
    public void theOrchestratorLimitsInYamlAreTheOnesTheCodeFallsBackTo() {
        OrchestratorProperties unconfigured = new OrchestratorProperties(null, null, null, null);

        assertThat(Integer.parseInt(text("app", "ai", "orchestrator", "max-review-rounds")))
            .as("application.yml and OrchestratorProperties must not disagree about the review"
                + " limit — a profile created through the admin form is pre-filled from the former"
                + " and a profile that omits it resolves through the latter")
            .isEqualTo(unconfigured.resolvedMaxReviewRounds());

        assertThat(Integer.parseInt(text("app", "ai", "orchestrator", "max-specialist-calls")))
            .isEqualTo(unconfigured.resolvedMaxSpecialistCalls());
    }

    @Test
    public void kgExtractionTokenBudgetStaysAtLeast4096() {
        int kgMaxTokens = Integer.parseInt(text("app", "ai", "kg", "max-tokens"));

        assertThat(kgMaxTokens)
            .as("a smaller budget truncates the extracted graph JSON mid-element; the chunk is then"
                + " retried, discarded and only logged, so the graph silently loses entities while"
                + " the ingest job still reports success")
            .isGreaterThanOrEqualTo(4096);
    }

    /**
     * SEC-14. {@code SameSite} is the CSRF defence that still applies to the chains where CSRF
     * tokens are deliberately disabled (the bearer-authenticated API/MCP/desktop chains), and
     * {@code HttpOnly} is what keeps the session cookie out of reach of any script that makes it
     * onto a page.
     *
     * <p>
     * The value must be {@code lax} and nothing stronger, which is the part that looks like a
     * weakening and is not: Entra completes the OAuth2 authorization-code flow by sending the
     * browser back to {@code /login/oauth2/code/entra} from {@code login.microsoftonline.com}, a
     * cross-site navigation. Under {@code strict} the browser withholds the session cookie on that
     * navigation, so Spring Security cannot match the callback to the authorization request it
     * saved, and every real login fails — while local development, which runs with
     * {@code app.security.mock=true} and a same-site form post, keeps working perfectly. That is
     * the exact shape of a change that ships: it is a hardening, it is green everywhere, and it
     * breaks only the one flow no test exercises.
     */
    @Test
    public void sessionCookieIsLaxAndHttpOnly() {
        String sameSite = text("server", "servlet", "session", "cookie", "same-site");
        String httpOnly = text("server", "servlet", "session", "cookie", "http-only");

        assertThat(sameSite)
            .as("'strict' withholds the session cookie on Entra's cross-site OAuth2 callback, "
                + "which breaks every real login while mock/local auth stays green")
            .isEqualToIgnoringCase("lax");
        assertThat(httpOnly).as("HttpOnly keeps the session cookie unreadable from script")
            .isEqualToIgnoringCase("true");
    }

    /**
     * SEC-15. {@code SecretCipher} derives its encryption key with
     * {@code Encryptors.delux(secretKey, salt)} and fingerprints the pair into
     * {@code confluence_instances.token_key_id}. A checked-in default for either half would be a
     * value every deployment shares and every reader of this repository already knows, which
     * reduces at-rest encryption of the stored Confluence API token to obfuscation — and the
     * fingerprint, which outlives the ciphertext, then tells an attacker holding a database dump
     * that the known key is the right one.
     *
     * <p>
     * The salt is the half that will eventually be "fixed", because an unset salt is currently a
     * hard startup failure: with a key configured and no salt, {@code SecretCipher} throws
     * {@code "app.security.secret-salt must be set when app.security.secret-key is configured"}.
     * Giving it a default makes that exception disappear, which reads exactly like a bug fix and is
     * the opposite of one — the deployment that refused to start was refusing precisely because its
     * ciphertext would otherwise have been decryptable with a salt taken from GitHub. The key's
     * default is asserted alongside it for the same reason.
     *
     * <p>
     * No running test can catch either. The IT harness activates the {@code test} profile, where an
     * unset key is a logged warning and plaintext storage (SEC-05), so the whole encrypting branch
     * of {@code SecretCipher} — the only place the salt is ever read — is never entered by the
     * suite at all.
     */
    @Test
    public void secretKeyAndSaltShipNoSharedDefault() {
        assertThat(text("app", "security", "secret-key"))
            .as("APP_SECRET_KEY must come from the environment; a checked-in default is a key"
                + " shared by every deployment and known to every reader of this repository")
            .isEmpty();
        assertThat(text("app", "security", "secret-salt"))
            .as("SEC-15: the KDF salt is per-deployment — a default makes the derived key a pure"
                + " function of a secret-key that may itself be shared")
            .isEmpty();
    }

    /**
     * Two independent guarantees that together keep the operational surface off the internet-facing
     * port.
     *
     * <p>
     * The management port must differ from {@code server.port}, and it is the only mechanism doing
     * that job. {@code SecurityConfig}'s {@code @Order(0)} actuator chain anonymises exactly
     * {@code /actuator/health}, {@code /actuator/health/**} and {@code /actuator/info} and requires
     * plain {@code authenticated()} for everything else — which on a browser-facing port means the
     * Entra login every employee already holds, not "operators only". Its own comment says the
     * sensitive endpoints are "additionally kept off the public port via
     * {@code management.server.port=9090}", so collapsing the two ports — the obvious
     * simplification when someone finds actuator unreachable behind the ingress — silently removes
     * the second of the two barriers and leaves any logged-in user one URL away from the
     * operational endpoints. (The {@code /actuator/**} {@code permitAll()} further down the browser
     * chain is dead: the {@code @Order(0)} chain claims those paths first. Do not read it as
     * evidence either way.)
     *
     * <p>
     * And the exposure list must stay exactly {@code health} and {@code info}. Widening it to
     * {@code "*"} is the standard debugging move and it registers {@code /actuator/env} and
     * {@code /actuator/configprops}, which render the resolved values of
     * {@code spring.datasource.password}, {@code app.security.secret-key},
     * {@code app.security.api-key} and every provider API key. Masking is a separate setting, so
     * nothing about the widening warns you — and the two edits compose: widen the list and collapse
     * the port, and those values are one authenticated request away on the published port. This is
     * asserted as an exact set rather than a containment check precisely because the damaging edit
     * adds entries rather than removing them.
     */
    @Test
    public void actuatorStaysOffThePublicPortAndExposesOnlyHealthAndInfo() {
        String managementPort = text("management", "server", "port");
        String serverPort = text("server", "port");

        assertThat(managementPort)
            .as("only health/info are anonymous; the rest rely on not sharing the public port")
            .isNotEqualTo(serverPort);
        assertThat(exposedEndpoints())
            .as("a wider exposure list publishes /actuator/env, i.e. every resolved secret")
            .containsExactlyInAnyOrder("health", "info");
    }

    /**
     * The other half of the rule above, which application.yml cannot express: nothing may publish
     * the management port.
     *
     * <p>
     * {@code /actuator/info} is anonymous by design (the {@code @Order(0)} chain permits it) and
     * now carries the build version, so the only thing keeping the operational surface off the
     * public network is that no {@code Service} routes to 9090 — a pod's own port is reachable
     * in-cluster regardless, and an Ingress can only target a Service. Every manifest is walked
     * rather than one named file, because a NEW Service added anywhere under {@code k8s/} would
     * expose it just as effectively as an edit to the existing one.
     */
    @Test
    public void noServicePublishesTheManagementPort() throws Exception {
        String managementPort = text("management", "server", "port");
        List<Map<String, Object>> services = new ArrayList<>();
        List<Object> workloads = new ArrayList<>();

        try (Stream<Path> files = Files.walk(Path.of("k8s"))) {
            for (Path manifest : files.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".yaml")).toList()) {
                for (Object document : new Yaml()
                    .loadAll(Files.readString(manifest, StandardCharsets.UTF_8))) {
                    if (document instanceof Map<?, ?> map && "Service".equals(map.get("kind"))) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> service = (Map<String, Object>) map;
                        services.add(service);
                    } else if (document != null) {
                        workloads.add(document);
                    }
                }
            }
        }

        assertThat(services)
            .as("vacuity guard: at least one Service manifest must be found under k8s/, or this "
                + "test passes without examining anything")
            .isNotEmpty();

        // A Service selects a container port by NUMBER or by its NAME, and this repository's own
        // Service uses the name form (`targetPort: http`). Scanning the spec for the literal 9090
        // therefore missed the idiomatic way of making the mistake: `targetPort: management` routes
        // to the actuator port while containing no "9090" anywhere. Resolve the aliases first.
        Set<String> managementAliases = new TreeSet<>();
        managementAliases.add(managementPort);
        collectPortNames(workloads, managementPort, managementAliases);

        assertThat(managementAliases)
            .as("vacuity guard: the container port %s must be declared with a name somewhere under "
                + "k8s/ — if nothing resolves, this check has silently degraded to the literal "
                + "number scan it replaced", managementPort)
            .hasSizeGreaterThan(1);

        for (Map<String, Object> service : services) {
            for (Object entry : servicePorts(service)) {
                for (String key : List.of("port", "targetPort", "nodePort")) {
                    Object value = ((Map<?, ?>) entry).get(key);
                    if (value == null) {
                        continue;
                    }
                    assertThat(String.valueOf(value))
                        .as("a Service must not route to the management port — reachable as %s. "
                            + "/actuator/info is anonymous and carries the build metadata, and "
                            + "/actuator/health/** is anonymous too, so the containment is the "
                            + "port and not the authorization", managementAliases)
                        .isNotIn(managementAliases);
                }
            }
        }
    }

    /** Every name any container gives to {@code port}, walked over whole manifests. */
    private static void collectPortNames(Object node, String port, Set<String> names) {
        if (node instanceof Map<?, ?> map) {
            Object containerPort = map.get("containerPort");
            Object name = map.get("name");
            if (containerPort != null && String.valueOf(containerPort).equals(port)
                && name != null) {
                names.add(String.valueOf(name));
            }
            map.values().forEach(child -> collectPortNames(child, port, names));
        } else if (node instanceof List<?> list) {
            list.forEach(child -> collectPortNames(child, port, names));
        }
    }

    private static List<?> servicePorts(Map<String, Object> service) {
        if (service.get("spec") instanceof Map<?, ?> spec
            && spec.get("ports") instanceof List<?> ports) {
            return ports;
        }
        return List.of();
    }

    /**
     * {@code /actuator/info} may disclose what the build IS; it must not disclose where it was
     * built.
     *
     * <p>
     * Binding the {@code build-info} goal populated this endpoint with group/artifact/version/time
     * — deliberate, and harmless. The adjacent contributor is not: {@code management.info.git} is
     * enabled by default in Boot, so the moment a {@code git.properties} appears on the classpath
     * (the standard companion move for a git-driven versioning scheme —
     * {@code git-commit-id-maven-plugin} writes exactly that file) the anonymous endpoint silently
     * gains branch and commit, and {@code mode: full} adds the build host, the committer's email
     * and the remote URL. Nothing warns: the sibling test above pins the endpoint EXPOSURE list and
     * says nothing about which contributors fill those endpoints.
     *
     * <p>
     * Both halves are asserted because they fail independently — the flag can be flipped back, and
     * the resource can appear without anyone touching this file.
     */
    @Test
    public void theInfoEndpointNeverDisclosesTheBuildMachine() {
        assertThat(text("management", "info", "git", "enabled"))
            .as("management.info.git must stay disabled: it defaults to ENABLED, so this key is "
                + "not redundant — it is the whole guard")
            .isEqualTo("false");

        assertThat(ApplicationYamlInvariantsTest.class.getResourceAsStream("/git.properties"))
            .as("no git.properties may reach the classpath. The contributor above is disabled, so "
                + "this is defence in depth — but it is the half that changes without anyone "
                + "editing configuration, because it arrives as a side effect of adding a plugin")
            .isNull();
    }

    /**
     * The version advertised to external MCP clients must be the version that was built.
     *
     * <p>
     * This was a hardcoded {@code "1.0.0"} that had already drifted from the pom, equalled the
     * spring-ai library default so it conveyed nothing, and was the only version any MCP client is
     * ever told. It is now {@code @project.version@}, substituted by the Spring Boot parent's
     * resource filtering — which is why this test reading the CLASSPATH copy rather than the source
     * file is essential rather than incidental: the source says {@code @project.version@} and only
     * the filtered copy says what clients actually receive.
     */
    @Test
    public void theMcpServerVersionIsTheBuildVersion() throws Exception {
        Properties buildInfo = new Properties();
        try (InputStream in = ApplicationYamlInvariantsTest.class
            .getResourceAsStream("/META-INF/build-info.properties")) {
            assertThat(in).as("build-info.properties must be on the classpath — it is what the "
                + "version below is compared against").isNotNull();
            buildInfo.load(in);
        }

        String advertised = text("spring", "ai", "mcp", "server", "version");

        assertThat(advertised)
            .as("an unsubstituted '@project.version@' would be advertised verbatim to every MCP "
                + "client. Note the defaultOf() helper unwraps only ${...}, so an @...@ token "
                + "sails through as an ordinary string and would otherwise pass unnoticed")
            .doesNotStartWith("@").doesNotEndWith("@");

        assertThat(advertised)
            .as("MCP clients cache serverInfo; telling them a version the artifact does not have "
                + "makes every field report unfalsifiable")
            .isEqualTo(buildInfo.getProperty("build.version"));
    }

    /**
     * The shipped defaults for the generation cap, the chat feature flags and the AI provider — the
     * values every deployment that never exports the matching environment variable actually runs
     * on, and the one set of values no {@code @SpringBootTest} in this repository can observe,
     * because every context overrides at least one of them.
     *
     * <p>
     * {@code app.generation.max-concurrent} is the sharpest of the five. It is not a tuning knob
     * with a graceful low end: {@code GenerationConcurrencyLimiter} computes
     * {@code this.enabled = maxConcurrent > 0}, so 0 (or any negative) does not mean "one at a
     * time", it means {@code tryAcquire} always returns true and {@code release} is a no-op —
     * PRF-15 removed outright. The comment above the key even documents 0 as the disable switch,
     * which is exactly why the value is one keystroke away from being deleted. Nothing reports it:
     * no exception, no startup warning, and the app answers normally right up to the point where
     * enough concurrent SSE generations are in flight to exhaust the connection pool or the LLM
     * quota at once.
     *
     * <p>
     * {@code app.rate-limit.capacity}/{@code refill-period-seconds} are the numbers behind SEC-03.
     * {@code securityDefaultsAreFailClosed} below pins only that limiting is switched ON; a
     * capacity widened to, say, 2000 leaves that assertion green while making the bucket
     * unreachable in practice, so "enabled" stops meaning anything.
     *
     * <p>
     * The three {@code app.chat} values are product-level kill switches. {@code enabled=false}
     * makes the whole chat surface answer 503 for any deployment that never set
     * {@code APP_CHAT_ENABLED}; {@code playbook-validation-enabled=false} silently drops the
     * preflight guard so a question is sent against a playbook that cannot answer it, with no
     * warning card; and {@code allowed-models} is the whitelist the model picker and the
     * per-request model validation are built from, so a drifted default offers or accepts a model
     * the account is not entitled to.
     *
     * <p>
     * {@code app.ai.provider} defaulting away from {@code cloudflare-gemini} is the quietest
     * failure of the lot, because the app keeps answering perfectly: it simply stops going through
     * the AI Gateway, so {@code gateway_cache_status} is never populated, replayed calls are billed
     * at list price, {@code cost_avoided} reports nothing and the {@code cf-aig-metadata}
     * attribution disappears from every call — a cost-accounting regression with no functional
     * symptom at all.
     */
    @Test
    public void shippedGenerationChatAndProviderDefaultsAreTheOnesProductionActuallyRunsOn() {
        assertThat(Integer.parseInt(text("app", "generation", "max-concurrent")))
            .as("PRF-15: GenerationConcurrencyLimiter treats <= 0 as DISABLED (enabled ="
                + " maxConcurrent > 0), so a drift to 0 silently removes the cap entirely — no"
                + " exception, no log line, no failing test")
            .isEqualTo(64);

        assertThat(Integer.parseInt(text("app", "rate-limit", "capacity")))
            .as("SEC-03: 'enabled: true' is meaningless if the bucket is too large to ever empty")
            .isEqualTo(20);
        assertThat(Integer.parseInt(text("app", "rate-limit", "refill-period-seconds")))
            .as("the refill window is the other half of the limit; widening it multiplies the"
                + " effective allowance without touching the capacity")
            .isEqualTo(60);

        assertThat(text("app", "chat", "enabled"))
            .as("a false default 503s the entire chat surface for every deployment that never set"
                + " APP_CHAT_ENABLED")
            .isEqualToIgnoringCase("true");
        assertThat(text("app", "chat", "allowed-models").split(","))
            .as("this is the whitelist the model picker and the per-request model check are built"
                + " from; a drifted default offers a model the account may not be entitled to."
                + " ORDER is behaviour, not presentation: element 0 is stamped on every new"
                + " conversation and is the model the playbook preflight runs against, so a reorder"
                + " changes what everybody gets by default")
            .containsExactly("gemini-3.7-flash", "gemini-3.6-flash", "gemini-3.5-flash-lite",
                "gpt-5.4-mini", "DeepSeek-V4-Flash", "gpt-5.6-luna");
        assertThat(text("app", "ai", "azure-openai", "reasoning-models"))
            .as("this list REPLACES the name heuristic rather than extending it, so a half-filled"
                + " value declassifies every deployment it omits and 400s each of them; empty means"
                + " 'detect from the name', which is correct for every deployment in use")
            .isEmpty();
        assertThat(text("app", "chat", "playbook-validation-enabled"))
            .as("false removes the preflight guard silently — the question is sent against a"
                + " playbook that cannot answer it, with no warning card and nothing in the log")
            .isEqualToIgnoringCase("true");

        assertThat(text("app", "ai", "provider"))
            .as("the default route for every model app.ai.model-routes does not map. Leaving the"
                + " Cloudflare AI Gateway was a deliberate decision and it was not free:"
                + " gateway_cache_status is no longer populated, nothing is billed as replayed, and"
                + " the cf-aig-metadata attribution is gone. Drifting BACK is equally consequential,"
                + " so the value is pinned in both directions")
            .isEqualTo("gemini");
        assertThat(text("app", "ai", "model-routes"))
            .as("the route table must ship EMPTY: every entry is validated at startup, so a value"
                + " committed here would have to be valid in every environment at once")
            .isEmpty();
    }

    /**
     * The deployed route table is a hand-written JSON string inside a YAML manifest, and nothing
     * else ever parses it until a pod starts. Running it through the real
     * {@link LlmModelRoutes#parse} here turns a typo — a stray comma, a misspelled route, a model
     * named twice — from a failed rollout into a failed build.
     *
     * <p>
     * The cross-check matters as much as the parse. A route naming a model that is not in
     * {@code ALLOWED_MODELS} is dead configuration: nobody can select it, so the route silently
     * never applies. The reverse is deliberately NOT asserted — a Gemini model correctly has no
     * entry, because unrouted models take the default route.
     */
    @Test
    void theDeployedRouteTableParsesAndOnlyNamesSelectableModels() throws Exception {
        Map<String, String> data = deployedConfigMap();
        String routesJson = data.get("AI_MODEL_ROUTES");
        assertThat(routesJson).as("the ConfigMap must declare AI_MODEL_ROUTES").isNotNull();

        LlmModelRoutes routes = LlmModelRoutes.parse(new ObjectMapper(), routesJson);
        assertThat(routes.routeFor("gpt-5.4-mini")).contains(LlmRouteId.AZURE_OPENAI_RESPONSES);
        assertThat(routes.routeFor("DeepSeek-V4-Flash"))
            .contains(LlmRouteId.AZURE_OPENAI_RESPONSES);
        assertThat(routes.routeFor("gpt-5.6-luna")).contains(LlmRouteId.AZURE_OPENAI_RESPONSES);

        List<String> allowed =
            Arrays.stream(data.get("ALLOWED_MODELS").split(",")).map(String::trim).toList();
        assertThat(routes.byModel().keySet())
            .as("a route for a model nobody can select never applies; both spellings are"
                + " hand-typed and only this compares them")
            .allSatisfy(
                routed -> assertThat(allowed.stream().anyMatch(a -> a.equalsIgnoreCase(routed)))
                    .as("routed model '%s' is absent from ALLOWED_MODELS %s", routed, allowed)
                    .isTrue());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> deployedConfigMap() throws Exception {
        Path configMap = Path.of("k8s/app/yvoke-app/configmap.yaml");
        assertThat(configMap).as("the deployment's env source must exist to be checked").exists();
        Map<String, Object> root =
            new Yaml().load(Files.readString(configMap, StandardCharsets.UTF_8));
        return (Map<String, String>) root.get("data");
    }

    /**
     * The shipped default and the deployed value are one contract that nothing else compares.
     *
     * <p>
     * It matters more than it looks, because {@code LlmConfig} now REJECTS a retired provider
     * rather than falling through to Gemini. A ConfigMap still naming {@code cloudflare-gemini}
     * after the code stopped accepting it does not degrade — the container fails to start. And the
     * ConfigMap cannot self-heal on the way in: {@code kustomization.yaml} lists it under
     * {@code resources:} with no generator and no hash, the Deployment consumes it via
     * {@code envFrom}, and {@code redeploy.sh} deliberately omits a restart — so a ConfigMap edit
     * shipped on its own leaves the pod template identical, reports a successful rollout, and takes
     * effect at some later unrelated release.
     */
    @Test
    void theDeployedProviderIsOneTheCodeStillAccepts() throws Exception {
        Path configMap = Path.of("k8s/app/yvoke-app/configmap.yaml");
        assertThat(configMap).as("the deployment's env source must exist to be checked").exists();

        String deployed = Files.readAllLines(configMap, StandardCharsets.UTF_8).stream()
            .map(String::trim).filter(line -> line.startsWith("AI_PROVIDER:")).findFirst()
            .orElseThrow(() -> new AssertionError("k8s ConfigMap declares no AI_PROVIDER"))
            .replace("AI_PROVIDER:", "").replace("\"", "").trim();

        assertThat(deployed)
            .as("the deployed provider must be one LlmConfig still wires; a retired value is not a"
                + " degraded start, it is a failed one")
            .isEqualTo(text("app", "ai", "provider"));
    }

    /**
     * Both of these defaults are the value that applies when nobody sets the environment variable —
     * which is exactly the case in which the safe value matters, because it is the accidental one.
     *
     * <p>
     * SEC-03 rate limiting is all that stands between one principal and unbounded LLM spend:
     * {@code GenerationRateLimiter.tryAcquire} returns {@code true} immediately when disabled, so
     * flipping the default silently uncaps every generation and ingest endpoint. There is no error,
     * no log line, and no difference visible in normal use — the difference shows up on the
     * invoice, and by then the traffic that produced it is gone.
     *
     * <p>
     * {@code app.security.mock} is worse: it makes {@code SecurityConfig} swap Entra OAuth2 for
     * {@code MockAuthenticationProvider} and a form login, i.e. sign in as anybody. Today that
     * fails closed only because SEC-09 refuses to start when {@code mock=true} without a
     * {@code dev}/{@code local}/{@code test} profile — but that guard is a second line of defence
     * for a default that must not be wrong in the first place, and a deployment that activates a
     * dev profile for some unrelated reason has nothing left underneath it.
     *
     * <p>
     * Neither can be pinned by a {@code @SpringBootTest}, which is the whole problem: every context
     * in the suite overrides at least one of them ({@code app.security.mock=true} is set by most
     * ITs, and the {@code test} profile is added by {@code PostgresTestContainerInitializer}), so
     * the shipped default is the one value no running test ever observes.
     */
    @Test
    public void securityDefaultsAreFailClosed() {
        assertThat(text("app", "rate-limit", "enabled"))
            .as("SEC-03: with rate limiting off by default, a single principal can drive unbounded"
                + " LLM spend and nothing anywhere reports it")
            .isEqualToIgnoringCase("true");
        assertThat(text("app", "security", "mock"))
            .as("mock auth defaulting to on would let anyone sign in as anyone; the SEC-09 profile"
                + " guard is a backstop, not the primary control")
            .isEqualToIgnoringCase("false");
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    /**
     * SEC-03's fallback bucket is {@code "ip:" + request.getRemoteAddr()}, and this one word is
     * what decides whose address that is.
     *
     * <p>
     * Production runs behind a TLS-terminating proxy, so without
     * {@code forward-headers-strategy: framework} Spring Boot registers no
     * {@code ForwardedHeaderFilter} and {@code getRemoteAddr()} returns the INGRESS address for
     * every request. Every unauthenticated caller then shares one bucket: the first few requests
     * exhaust it and everyone else is 429ed by traffic that is not theirs — a limiter that is
     * supposed to bound one principal instead becomes a denial of service against all of them, and
     * the 429s appear to come from nowhere because each individual client sent almost nothing.
     * {@code X-Forwarded-Proto} rides on the same setting, so losing it also makes the container
     * consider HTTPS requests plain HTTP — which is what applies the {@code Secure} flag to the
     * session cookie the sibling test above pins as {@code lax}/{@code HttpOnly}.
     *
     * <p>
     * Nothing else looks at it: {@code RateLimitInterceptorTest} builds its own
     * {@code MockHttpServletRequest}, and MockMvc never installs Boot's registered servlet filters,
     * so no test at any tier can observe the difference at runtime. The value is only ever wrong in
     * a deployed environment, where the symptom is other people's 429s.
     */
    @Test
    public void forwardHeadersStrategyStaysFrameworkSoRateLimitBucketsSeeTheRealClient() {
        assertThat(text("server", "forward-headers-strategy"))
            .as("without it getRemoteAddr() is the ingress for every request, so SEC-03's ip:"
                + " fallback collapses into ONE bucket shared by every unauthenticated caller")
            .isEqualToIgnoringCase("framework");
    }

    /** Accepts either the comma-separated scalar the file uses today or a YAML sequence. */
    private static List<String> exposedEndpoints() {
        Object include = at("management", "endpoints", "web", "exposure", "include");
        if (include instanceof List<?> sequence) {
            return sequence.stream().map(String::valueOf).map(String::trim).toList();
        }
        return Arrays.stream(defaultOf(String.valueOf(include)).split(",")).map(String::trim)
            .filter(entry -> !entry.isEmpty()).toList();
    }

    private static String text(String... path) {
        return defaultOf(String.valueOf(at(path)));
    }

    /** Walks the parsed document, failing with the exact path when a key has moved or vanished. */
    @SuppressWarnings("unchecked")
    private static Object at(String... path) {
        Object node = config;
        StringBuilder walked = new StringBuilder();
        for (String key : path) {
            if (walked.length() > 0) {
                walked.append('.');
            }
            walked.append(key);
            assertThat(node).as("application.yml: '%s' is not a mapping", walked)
                .isInstanceOf(Map.class);
            node = ((Map<String, Object>) node).get(key);
            assertThat(node).as("application.yml: missing key '%s'", walked).isNotNull();
        }
        return node;
    }

    /**
     * Unwraps a {@code ${ENV_VAR:default}} placeholder to its default so these assertions keep
     * working if a value later becomes environment-overridable; anything else passes through.
     */
    private static String defaultOf(String value) {
        if (value.startsWith("${") && value.endsWith("}")) {
            String inner = value.substring(2, value.length() - 1);
            int separator = inner.indexOf(':');
            return separator >= 0 ? inner.substring(separator + 1) : inner;
        }
        return value;
    }
}
