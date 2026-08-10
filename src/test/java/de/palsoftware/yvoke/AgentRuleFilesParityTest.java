package de.palsoftware.yvoke;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * {@code CLAUDE.md} and {@code .agents/AGENTS.md} are the same project rules written for two
 * different agent toolchains, and an agent that loads only one of them must not be missing a
 * pitfall.
 *
 * <p>
 * Only a human keeps them in step, which is exactly the situation this project's own rule says to
 * automate: <em>"when two things must agree and only a human keeps them in sync, add the test that
 * compares them — a reflection or SQL-shape assertion is cheap and is the only thing that will ever
 * notice the drift."</em> That rule produced {@code McpToolCatalogueParityTest} for the
 * {@code @McpTool}/{@code @Tool} pair; this is its counterpart for the two rule files.
 *
 * <p>
 * It was written because the drift had already happened and was invisible. {@code AGENTS.md} § 6
 * used to say the pitfalls were "kept as a pointer rather than a copy … add new entries there, not
 * here" — and then 17 of the 47 entries were copied across anyway (every one added after a certain
 * date, since whoever adds a pitfall naturally adds it to both), leaving the older 30 absent and 4
 * of the copied ones drifting in wording. A stale partial copy plus a pointer is the worst of the
 * three possible states, and it is precisely what that note set out to prevent.
 *
 * <p>
 * The comparison is on the bullets' full text, not their count or their titles: a reworded pitfall
 * is a divergence, because the wording IS the content — several entries turn on one clause
 * ("deletion must be conservative, reporting can be thorough"), and a summary of them is not a
 * substitute.
 */
class AgentRuleFilesParityTest {

    private static final Path CLAUDE = Path.of("CLAUDE.md");
    private static final Path AGENTS = Path.of(".agents/AGENTS.md");

    /** Matches the {@code ## <n>. Known Pitfalls …} heading in either file. */
    private static final Pattern HEADING =
        Pattern.compile("^##\\s*\\d*\\.?\\s*Known Pitfalls.*$", Pattern.MULTILINE);

    /** A pitfall bullet starts at column 0 with {@code - **}; continuation lines are indented. */
    private static final Pattern BULLET = Pattern.compile("^- \\*\\*", Pattern.MULTILINE);

    private static List<String> pitfalls(Path file) throws IOException {
        return pitfallsIn(section(file));
    }

    /** The text of the file's single {@code Known Pitfalls} section. */
    private static String section(Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        Matcher heading = HEADING.matcher(text);
        assertThat(heading.find()).as("%s must have a 'Known Pitfalls' section", file).isTrue();
        return text.substring(heading.end());
    }

    private static List<String> pitfallsIn(String section) {
        List<Integer> starts = new ArrayList<>();
        Matcher bullet = BULLET.matcher(section);
        while (bullet.find()) {
            starts.add(bullet.start());
        }

        List<String> out = new ArrayList<>();
        for (int i = 0; i < starts.size(); i++) {
            int end = (i + 1 < starts.size()) ? starts.get(i + 1) : section.length();
            out.add(section.substring(starts.get(i), end).strip());
        }
        return out;
    }

    @Test
    void theTwoAgentRuleFilesCarryTheSamePitfallsWordForWord() throws IOException {
        List<String> claude = pitfalls(CLAUDE);
        List<String> agents = pitfalls(AGENTS);

        assertThat(claude).as("CLAUDE.md must actually contain pitfalls, or this test is vacuous")
            .isNotEmpty();

        // Report the titles first: a count mismatch names the missing entries, which is what the
        // person who forgot to mirror one needs to see.
        assertThat(agents.stream().map(AgentRuleFilesParityTest::title).toList())
            .as(".agents/AGENTS.md must mirror CLAUDE.md § 6 — add every new pitfall to BOTH files")
            .containsExactlyElementsOf(
                claude.stream().map(AgentRuleFilesParityTest::title).toList());

        // Then the full text, so a reworded entry is a failure too.
        assertThat(agents)
            .as("a pitfall reworded in one file only is still divergence — the wording "
                + "IS the content, and several entries turn on a single clause")
            .containsExactlyElementsOf(claude);
    }

    /**
     * A pitfall must live in the {@code Known Pitfalls} section and nowhere else in the file.
     *
     * <p>
     * The word-for-word check above starts reading at the {@code Known Pitfalls} heading, so it is
     * structurally blind to anything above it — and that is exactly where the drift went. Before
     * this test existed, {@code .agents/AGENTS.md} § 4 carried a second, older, reworded copy of 17
     * of the pitfalls, ~40 lines <em>above</em> the parity-checked § 6. An agent reading top-down
     * hit the stale wording first and acted on that, and because the copy held 17 of ~47 entries it
     * read as a complete list of gotchas when it was a third of one. The parity check stayed green
     * throughout.
     *
     * <p>
     * Two conditions close it: exactly one {@code Known Pitfalls} heading per file, and no bullet
     * before it that is a restatement of one below. Matching is on shared {@code `code spans`}, not
     * on the bold title — the duplicates were *reworded* ("Mock HttpServer Testing" against "Mock
     * {@code HttpServer} tests"), so a title comparison finds nothing, while the identifiers and
     * snippets a pitfall is built from survive any amount of re-editing.
     */
    @Test
    void neitherFileCarriesASecondCopyOfAPitfallOutsideTheKnownPitfallsSection()
        throws IOException {
        for (Path file : List.of(CLAUDE, AGENTS)) {
            String text = Files.readString(file, StandardCharsets.UTF_8);

            Matcher heading = HEADING.matcher(text);
            assertThat(heading.find()).as("%s must have a 'Known Pitfalls' section", file).isTrue();
            int start = heading.start();
            assertThat(heading.find())
                .as("%s must have exactly ONE 'Known Pitfalls' heading — a second section is a "
                    + "place for a copy to diverge unseen", file)
                .isFalse();

            List<Set<String>> canonical =
                pitfalls(file).stream().map(AgentRuleFilesParityTest::codeSpans).toList();

            // Only RARE spans identify a restatement. `./mvnw verify -Pit-tests` appears across
            // many pitfalls and in the tech-stack section too — sharing it means nothing. An
            // identifier that occurs in one or two pitfalls is that incident's fingerprint.
            Map<String, Long> frequency = canonical.stream().flatMap(Set::stream)
                .collect(Collectors.groupingBy(s -> s, Collectors.counting()));

            for (String earlier : pitfallsIn(text.substring(0, start))) {
                Set<String> spans = codeSpans(earlier).stream().filter(
                    s -> frequency.getOrDefault(s, 0L) > 0 && frequency.get(s) <= COMMON_SPAN_LIMIT)
                    .collect(Collectors.toSet());
                if (spans.size() < SHARED_SPAN_THRESHOLD) {
                    continue; // too little distinctive substance to judge
                }
                boolean duplicate = canonical.stream().anyMatch(c -> {
                    long shared = spans.stream().filter(c::contains).count();
                    return shared >= SHARED_SPAN_THRESHOLD;
                });
                assertThat(duplicate).as(
                    "%s restates the pitfall '%s' above its 'Known Pitfalls' section — it shares at "
                        + "least %d code spans with an entry below. A second copy is invisible to "
                        + "the word-for-word check and is read FIRST, so it is what an agent acts "
                        + "on. Keep every pitfall in the parity-checked section only.",
                    file, title(earlier), SHARED_SPAN_THRESHOLD).isFalse();
            }
        }
    }

    /**
     * How many shared distinctive {@code `code spans`} make one bullet a restatement of another.
     */
    private static final int SHARED_SPAN_THRESHOLD = 3;

    /** A span appearing in more than this many pitfalls is vocabulary, not a fingerprint. */
    private static final int COMMON_SPAN_LIMIT = 2;

    private static final Pattern CODE_SPAN = Pattern.compile("`([^`\\n]{2,})`");

    /** The distinct {@code `code spans`} in a bullet — its technical fingerprint. */
    private static Set<String> codeSpans(String bullet) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = CODE_SPAN.matcher(bullet);
        while (m.find()) {
            out.add(m.group(1).strip());
        }
        return out;
    }

    /** The bold lead phrase of a bullet, used to make a mismatch legible. */
    private static String title(String bullet) {
        Matcher m = Pattern.compile("- \\*\\*(.+?)\\*\\*", Pattern.DOTALL).matcher(bullet);
        return m.find() ? m.group(1).replaceAll("\\s+", " ") : bullet.substring(0, 60);
    }
}
