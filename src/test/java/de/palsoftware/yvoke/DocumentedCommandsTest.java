package de.palsoftware.yvoke;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Three tracked documents tell a reader how to run this repository's tests — {@code README.md},
 * {@code CLAUDE.md} and {@code .agents/AGENTS.md} — and every Maven command in them names a profile
 * that has to exist in {@code pom.xml}. Only a human keeps those in step, which is the situation
 * this project's own rule says to automate.
 *
 * <p>
 * {@code docs/testing-plan.md} says the same things and is deliberately <em>not</em> checked: the
 * whole {@code docs/} tree is git-ignored ("Docs (local-only, not tracked)"), so it is absent from
 * every clone and from CI. A test that read it would pass here and fail everywhere else — which is
 * the same class of machine-local assumption as the {@code protect-migrations} hook whose wiring
 * lives in the ignored {@code .claude/settings.local.json}.
 *
 * <p>
 * The failure this prevents is quiet in the way that matters here: renaming or removing a profile
 * does not break the build, it breaks the *instructions*. An agent then runs
 * {@code ./mvnw verify -Pit-tests}, Maven cheerfully ignores an unknown profile and runs the
 * default lifecycle instead, and the integration tests the agent believes it just ran never
 * executed — while the output still ends in {@code BUILD SUCCESS}. That is the same shape as the
 * recorded pitfalls where a bare success line was read as proof that new code ran.
 *
 * <p>
 * The check is deliberately one-directional: every documented profile must exist, but a profile may
 * exist without being documented — a future one may be internal, or not yet worth a command
 * reference. (Note that {@code jacoco-merge}, {@code add-it-test-sources} and {@code run-it} are
 * plugin {@code <execution>} ids, not profiles: {@code -P} cannot reach them, and this test does
 * not see them.)
 */
class DocumentedCommandsTest {

    private static final List<Path> DOCS =
        List.of(Path.of("README.md"), Path.of("CLAUDE.md"), Path.of(".agents/AGENTS.md"));

    /**
     * A {@code -PsomeProfile} flag, scanned only on lines that actually invoke {@code mvnw}.
     *
     * <p>
     * Scanning the whole document matches prose: {@code CLAUDE.md} says "naming a non-PK unique
     * column", and {@code -PK} is a perfectly good profile flag as far as a regex is concerned. A
     * documented command is what this test is about, so require the line to contain the wrapper.
     */
    private static final Pattern DOCUMENTED_PROFILE = Pattern.compile("-P([A-Za-z0-9][\\w-]*)");

    private static final Pattern MVNW_LINE = Pattern.compile("^.*\\bmvnw\\b.*$", Pattern.MULTILINE);

    /** A {@code <profile>…<id>x</id>} declaration in the POM. */
    private static final Pattern DECLARED_PROFILE =
        Pattern.compile("<profile>\\s*<id>([^<]+)</id>");

    @Test
    void everyMavenProfileNamedInTheDocsExistsInThePom() throws IOException {
        String pom = Files.readString(Path.of("pom.xml"), StandardCharsets.UTF_8);
        Set<String> declared = new TreeSet<>();
        Matcher declaration = DECLARED_PROFILE.matcher(pom);
        while (declaration.find()) {
            declared.add(declaration.group(1).strip());
        }
        assertThat(declared).as("pom.xml must declare profiles, or this test is vacuous")
            .isNotEmpty();

        for (Path doc : DOCS) {
            Set<String> referenced = new LinkedHashSet<>();
            Matcher line = MVNW_LINE.matcher(Files.readString(doc, StandardCharsets.UTF_8));
            while (line.find()) {
                Matcher use = DOCUMENTED_PROFILE.matcher(line.group());
                while (use.find()) {
                    referenced.add(use.group(1));
                }
            }
            assertThat(referenced).as("%s must document at least one Maven profile, "
                + "or this file contributes nothing to the check", doc).isNotEmpty();

            assertThat(declared)
                .as("%s documents a Maven profile that pom.xml does not declare. Maven ignores an "
                    + "unknown -P flag silently and runs the default lifecycle instead, so the "
                    + "reader's tests never run and the output still says BUILD SUCCESS. "
                    + "Documented: %s — declared: %s", doc, referenced, declared)
                .containsAll(referenced);
        }
    }
}
