package de.palsoftware.yvoke;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * {@code CLAUDE.md} § 1: "No fully-qualified class names inline — add an {@code import} at the top
 * of the file and use the simple name". This is the test that makes that rule real.
 *
 * <p>
 * <b>Why this cannot be an ArchUnit rule.</b> {@code ArchitectureTest} reads <em>bytecode</em>, and
 * an inline FQN and an import compile to exactly the same thing — the constant pool records
 * {@code org/springframework/.../JdbcClient} either way. That is not a guess: when this rule was
 * first enforced, the 351-site rewrite was verified by disassembling every class it touched, and
 * all 53 differing class files were identical under {@code javap -c -p}; the only difference in the
 * class files at all was the {@code LineNumberTable}, shifted by the inserted import lines. A rule
 * about source <em>text</em> has to be checked against source text, so this scans the files —
 * following {@code MigrationSeedPolicyTest} and {@code DocumentedCommandsTest}, which also read the
 * repository rather than the compiled output.
 *
 * <p>
 * <b>Why the rule is worth enforcing at all.</b> It is a readability rule, so nothing breaks when
 * it is violated — which is exactly why it rotted: it was stated as a hard rule in two files and
 * violated 351 times across 96 files, including four times inside {@code ArchitectureTest} itself,
 * the class that enforces every other § 1 rule. By this project's own principle, a rule no test
 * fails on is undocumented rather than safe.
 *
 * <p>
 * <b>What counts.</b> Comments, javadoc, string literals, char literals and text blocks are
 * stripped before matching, so {@code {@code java.util.Map}} in a doc comment and a fully-qualified
 * class name inside an SQL string or an exception message are all fine. {@code import} and
 * {@code package} lines are skipped for the obvious reason.
 */
class NoInlineFullyQualifiedNamesTest {

    private static final List<Path> SOURCE_ROOTS =
        List.of(Path.of("src/main/java"), Path.of("src/test/java"), Path.of("src/it/java"));

    /**
     * A package path starting at a real root, then a {@code Capitalised} type name.
     *
     * <p>
     * Anchoring on known roots keeps an ordinary method chain on a lower-cased receiver
     * ({@code config.security.Provider}) from being mistaken for a package.
     */
    private static final Pattern INLINE_FQN = Pattern.compile(
        "\\b(?:java|javax|jakarta|org|com|de|net|io)(?:\\.[a-z][a-zA-Z0-9_]*)+\\.[A-Z][A-Za-z0-9_]*\\b");

    private static final Pattern IMPORT_OR_PACKAGE =
        Pattern.compile("^\\s*(?:import|package)\\b.*$", Pattern.MULTILINE);

    /**
     * The only place a fully-qualified name is unavoidable: the file imports the domain's own
     * {@code de.palsoftware.yvoke.collection.core.model.Collection}, so the {@code getParts()}
     * override it declares cannot name {@code java.util.Collection} any other way.
     */
    private static final Map<String, String> ALLOWED =
        Map.of("src/it/java/de/palsoftware/yvoke/ingest/api/IngestApiControllerIT.java",
            "java.util.Collection");

    @Test
    void noJavaSourceUsesAFullyQualifiedClassNameInline() throws IOException {
        List<Path> sources = new ArrayList<>();
        for (Path root : SOURCE_ROOTS) {
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(p -> p.toString().endsWith(".java")).forEach(sources::add);
            }
        }
        assertThat(sources).as("the source roots must contain java files, or this test is vacuous")
            .hasSizeGreaterThan(100);

        List<String> violations = new ArrayList<>();
        for (Path file : sources) {
            String code = strip(Files.readString(file, StandardCharsets.UTF_8));
            String allowed = ALLOWED.get(file.toString().replace('\\', '/'));
            Matcher m = INLINE_FQN.matcher(code);
            while (m.find()) {
                if (m.group().equals(allowed)) {
                    continue;
                }
                int line = (int) code.chars().limit(m.start()).filter(c -> c == '\n').count() + 1;
                violations.add(file + ":" + line + "  " + m.group());
            }
        }

        assertThat(violations)
            .as("CLAUDE.md / .agents/AGENTS.md § 1: add an import and use the simple name. "
                + "Nothing breaks when this is violated, which is why it needs a test — the rule "
                + "had rotted to 351 violations across 96 files before this existed. If a simple "
                + "name genuinely collides with another import, add the file to ALLOWED with the "
                + "reason.")
            .isEmpty();
    }

    /**
     * Blanks comments, string/char literals and text blocks, preserving offsets so lines stay true.
     */
    private static String strip(String src) {
        char[] out = src.toCharArray();
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            int end;
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                end = src.indexOf('\n', i);
                end = end < 0 ? n : end;
            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                end = src.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
            } else if (c == '"' && src.startsWith("\"\"\"", i)) {
                end = src.indexOf("\"\"\"", i + 3);
                end = end < 0 ? n : end + 3;
            } else if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < n && src.charAt(j) != c) {
                    j += src.charAt(j) == '\\' ? 2 : 1;
                }
                end = Math.min(j + 1, n);
            } else {
                i++;
                continue;
            }
            for (int k = i; k < end; k++) {
                if (out[k] != '\n') {
                    out[k] = ' ';
                }
            }
            i = end;
        }
        String blanked = new String(out);
        Matcher m = IMPORT_OR_PACKAGE.matcher(blanked);
        StringBuilder sb = new StringBuilder(blanked);
        while (m.find()) {
            for (int k = m.start(); k < m.end(); k++) {
                sb.setCharAt(k, ' ');
            }
        }
        return sb.toString();
    }
}
