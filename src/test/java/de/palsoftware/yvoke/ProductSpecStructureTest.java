package de.palsoftware.yvoke;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * {@code spec.md} is the functional specification: the one prose document this repository keeps,
 * and the thing a person or an agent is told to read before making a substantial change. This test
 * pins the parts of it a machine can actually check.
 *
 * <p>
 * It deliberately does <em>not</em> try to check that the prose is current — nothing can, which is
 * exactly why the exact behaviour contract lives in the rest of the test suite rather than in a
 * document. What it can enforce is the shape the document promises its readers, and that promise is
 * load-bearing in three ways.
 *
 * <p>
 * <b>The file must exist.</b> {@code CLAUDE.md} carries a standing rule against spec files in the
 * workspace, aimed at transient per-task plans; this document is the deliberate exception, and it
 * has already been tidied away once by an agent reading that rule too literally.
 *
 * <p>
 * <b>Every chapter must carry all four sections.</b> A chapter without <em>Limits</em> or without
 * <em>Not supported</em> is not merely incomplete — those two sections record what the product
 * deliberately does not do, and a deliberate absence is precisely the thing no other test in this
 * suite can fail on. Omitting them silently converts "we decided against this" into "nobody
 * considered it".
 *
 * <p>
 * <b>The Contents table must resolve.</b> Its entries are the only navigation the document has, and
 * a reader — especially an agent told to read "the chapter for the area you are touching" — finds a
 * chapter through them. A chapter appended without an index entry, or an index entry whose anchor
 * no longer matches a renamed heading, makes that chapter unfindable while the document still looks
 * complete.
 */
class ProductSpecStructureTest {

    private static final Path SPEC = Path.of("spec.md");

    /** The four sections every capability chapter promises. */
    private static final List<String> REQUIRED_SECTIONS =
        List.of("What you can do", "How it behaves", "Limits", "Not supported");

    /** A numbered capability chapter, e.g. {@code ## 3. Building the knowledge base}. */
    private static final Pattern CHAPTER =
        Pattern.compile("^## (\\d+)\\. (.+)$", Pattern.MULTILINE);

    /** Any second- or third-level heading, used to resolve the Contents anchors. */
    private static final Pattern HEADING = Pattern.compile("^#{2,3} (.+)$", Pattern.MULTILINE);

    /** A markdown link to an in-document anchor, e.g. {@code [Limits](#3-limits)}. */
    private static final Pattern ANCHOR_LINK = Pattern.compile("\\[[^]]+]\\(#([a-z0-9-]+)\\)");

    private static String spec() throws IOException {
        assertThat(SPEC)
            .as("spec.md is the functional specification and the deliberate exception to the "
                + "'no spec files in the workspace' rule — it must not be deleted as clutter")
            .exists();
        return Files.readString(SPEC, StandardCharsets.UTF_8);
    }

    @Test
    void everyCapabilityChapterCarriesAllFourSections() throws IOException {
        String text = spec();

        Matcher chapters = CHAPTER.matcher(text);
        List<Integer> starts = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        while (chapters.find()) {
            starts.add(chapters.start());
            titles.add(chapters.group(1) + ". " + chapters.group(2));
        }

        assertThat(titles)
            .as("spec.md must contain numbered capability chapters, " + "or this test is vacuous")
            .isNotEmpty();

        for (int i = 0; i < starts.size(); i++) {
            int end = (i + 1 < starts.size()) ? starts.get(i + 1) : text.length();
            String body = text.substring(starts.get(i), end);
            List<String> present =
                REQUIRED_SECTIONS.stream().filter(s -> body.contains("### " + s)).toList();

            assertThat(present).as(
                "chapter '%s' must carry all four sections — 'Limits' and 'Not supported' "
                    + "especially, since a deliberate absence is what no other test can fail on",
                titles.get(i)).containsExactlyElementsOf(REQUIRED_SECTIONS);
        }
    }

    @Test
    void everyContentsEntryPointsAtAHeadingThatExists() throws IOException {
        String text = spec();

        List<String> anchors = new ArrayList<>();
        Matcher heading = HEADING.matcher(text);
        while (heading.find()) {
            anchors.add(githubAnchor(heading.group(1)));
        }

        int contents = text.indexOf("## Contents");
        assertThat(contents).as("spec.md must have a Contents table").isNotNegative();
        int firstChapter = text.indexOf("\n## 1.");
        String table = text.substring(contents, firstChapter);

        List<String> linked = new ArrayList<>();
        Matcher link = ANCHOR_LINK.matcher(table);
        while (link.find()) {
            linked.add(link.group(1));
        }

        assertThat(linked).as("the Contents table must actually link to the chapters").isNotEmpty();
        assertThat(anchors).as(
            "every Contents link must resolve to a real heading — a renamed heading or a chapter "
                + "added without an index entry makes that chapter unfindable while the document "
                + "still looks complete")
            .containsAll(linked);
    }

    @Test
    void everyCapabilityChapterIsListedInTheContents() throws IOException {
        String text = spec();

        int contents = text.indexOf("## Contents");
        int firstChapter = text.indexOf("\n## 1.");
        String table = text.substring(contents, firstChapter);

        Matcher chapters = CHAPTER.matcher(text);
        while (chapters.find()) {
            String anchor = githubAnchor(chapters.group(1) + ". " + chapters.group(2));
            assertThat(table).as("chapter '%s' exists but the Contents table does not link to it",
                chapters.group(2)).contains("(#" + anchor + ")");
        }
    }

    /**
     * Reproduces GitHub's heading-to-anchor rule: lower-case, drop everything that is not a letter,
     * digit, space or hyphen, then spaces to hyphens.
     */
    private static String githubAnchor(String headingText) {
        return headingText.strip().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 -]", "")
            .replace(' ', '-');
    }
}
