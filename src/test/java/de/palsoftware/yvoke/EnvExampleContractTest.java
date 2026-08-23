package de.palsoftware.yvoke;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@code .env.example} is the only description of the environment this application needs, and
 * nothing compiles it. It replaced a prose paragraph in {@code README.md} that had drifted the
 * moment model routing landed — it advertised two retired providers' keys as current and omitted
 * the three the shipped {@code ALLOWED_MODELS} depends on, while {@code application.yml} declared
 * <b>57</b> variables the paragraph never mentioned.
 *
 * <p>
 * That is exactly the situation this project's own rule says to automate: <em>"when two things must
 * agree and only a human keeps them in sync, add the test that compares them"</em> — the rule that
 * produced {@link AgentRuleFilesParityTest} for the two rule files and
 * {@code ApplicationYamlInvariantsTest} for {@code application.yml} against the Kubernetes
 * ConfigMap. A file can be compared; a paragraph cannot, which is the whole reason the
 * documentation moved out of prose.
 *
 * <p>
 * The contract runs in both directions, because each direction fails differently. A variable in the
 * example that nothing consumes is a live instruction to configure something inert — the same shape
 * as the {@code OPENAI_API_BASE} that sat in the ConfigMap and in every {@code .env} while
 * {@code application.yml} excluded every OpenAI autoconfiguration and read the value nowhere. A
 * setting whose shipped default is a {@code placeholder-…} and which the example omits is a
 * variable an operator cannot discover at all: the application starts, and the failure surfaces
 * later as a provider call that cannot authenticate.
 */
class EnvExampleContractTest {

    private static final Path EXAMPLE = Path.of(".env.example");

    /**
     * Consumed by the {@code postgres} and {@code db-migration} images rather than by
     * {@code application.yml}. All three Compose services declare {@code env_file: .env}, so these
     * reach their container directly and never appear as a {@code ${...}} placeholder anywhere in
     * the repository — there is no other way to recognise them.
     */
    private static final Set<String> CONTAINER_VARS =
        Set.of("POSTGRES_USER", "POSTGRES_PASSWORD", "POSTGRES_DB", "FLYWAY_URL", "FLYWAY_USER",
            "FLYWAY_PASSWORD", "FLYWAY_CONNECT_RETRIES", "SPRING_PROFILES_ACTIVE");

    /**
     * Settings kept in {@code application.yml} for providers that {@code LlmConfig} rejects at
     * startup. They are deliberately still declared — re-enabling one is a branch in
     * {@code LlmConfig} plus an entry in {@code LlmRouteId} — but an operator must not be told to
     * set them, because naming the provider fails the start rather than degrading it.
     */
    private static final Set<String> RETIRED =
        Set.of("CLOUDFLARE_ACCOUNT_ID", "CLOUDFLARE_GATEWAY_ID", "CLOUDFLARE_GATEWAY_TOKEN",
            "OPENROUTER_API_KEY", "OPENROUTER_BASE_URL");

    /** A value that is neither blank nor visibly a stand-in — i.e. something that looks real. */
    private static final Pattern SECRET_SHAPED = Pattern.compile("^[A-Za-z0-9+/_\\-]{24,}={0,2}$");

    private static String yaml;
    private static Map<String, String> example;

    @BeforeAll
    static void load() throws Exception {
        try (
            InputStream in = EnvExampleContractTest.class.getResourceAsStream("/application.yml")) {
            assertThat(in).as("application.yml must be on the test classpath").isNotNull();
            yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(EXAMPLE).as(".env.example must exist — it is the only description of the "
            + "environment this application needs").exists();
        example = parseEnv(Files.readString(EXAMPLE, StandardCharsets.UTF_8));
    }

    /**
     * Nothing in the example may be inert. A variable an operator is told to set, which no code
     * path reads, is worse than an undocumented one: it is acted on.
     */
    @Test
    void everyVariableInTheExampleIsActuallyConsumed() {
        Set<String> orphans = new TreeSet<>();
        for (String name : example.keySet()) {
            if (!CONTAINER_VARS.contains(name) && !yaml.contains("${" + name)) {
                orphans.add(name);
            }
        }
        assertThat(orphans).as(".env.example names variables that nothing reads — remove them, or "
            + "add them to CONTAINER_VARS with the image that consumes them").isEmpty();
    }

    /**
     * The other direction: a setting whose shipped default is a {@code placeholder-…} cannot work
     * as shipped, so it has to be supplied — and an operator can only supply what they know about.
     */
    @Test
    void everySettingWithAPlaceholderDefaultIsDocumentedInTheExample() {
        Set<String> undocumented = new TreeSet<>();
        defaults().forEach((name, value) -> {
            if (value.contains("placeholder") && !example.containsKey(name)
                && !RETIRED.contains(name)) {
                undocumented.add(name);
            }
        });
        assertThat(undocumented)
            .as("application.yml ships a placeholder default for these, so they "
                + "must be supplied — document them in .env.example, or add them to RETIRED with the "
                + "reason the provider is not selectable")
            .isEmpty();
    }

    /** A committed example is public. Only stand-ins belong in it. */
    @Test
    void theExampleCarriesNoRealLookingSecret() {
        List<String> suspects = new ArrayList<>();
        example.forEach((name, value) -> {
            if (SECRET_SHAPED.matcher(value).matches() && !value.contains("placeholder")
                && !value.toLowerCase(Locale.ROOT).contains("example")) {
                suspects.add(name);
            }
        });
        assertThat(suspects).as("these values in .env.example look like real credentials rather "
            + "than stand-ins; the file is committed").isEmpty();
    }

    /** {@code KEY=value} lines, ignoring comments and blanks. */
    private static Map<String, String> parseEnv(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : text.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq > 0) {
                out.put(trimmed.substring(0, eq).strip(), trimmed.substring(eq + 1).strip());
            }
        }
        return out;
    }

    /**
     * Every {@code ${NAME:default}} in {@code application.yml}, mapped to its default. Brace
     * matching is manual because the defaults nest — {@code ${GEMINI_API_KEY:${GOOGLE_API_KEY:…}}}
     * — and a regex stopping at the first {@code }} would report the wrong default for exactly the
     * settings that matter most here.
     */
    private static Map<String, String> defaults() {
        StringBuilder body = new StringBuilder();
        for (String line : yaml.split("\n")) {
            if (!line.strip().startsWith("#")) {
                body.append(line).append('\n');
            }
        }
        String text = body.toString();
        Map<String, String> out = new LinkedHashMap<>();
        Matcher start = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_.]*)").matcher(text);
        while (start.find()) {
            int depth = 0;
            int i = start.start() + 1;
            while (i < text.length()) {
                if (text.charAt(i) == '{') {
                    depth++;
                } else if (text.charAt(i) == '}') {
                    depth--;
                    if (depth == 0) {
                        break;
                    }
                }
                i++;
            }
            String inner = text.substring(start.end(), Math.min(i, text.length()));
            out.put(start.group(1), inner.startsWith(":") ? inner.substring(1) : "");
        }
        return out;
    }
}
