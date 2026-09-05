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
 * {@code spec/} is the functional specification: the modular prose documentation this repository
 * keeps, and the thing a person or an agent is told to read before making a substantial change.
 * This test pins the parts of it a machine can actually check.
 *
 * <p>
 * It deliberately does <em>not</em> try to check that the prose is current — nothing can, which is
 * exactly why the exact behaviour contract lives in the rest of the test suite rather than in a
 * document. What it can enforce is the shape the document promises its readers, and that promise is
 * load-bearing in three ways.
 *
 * <p>
 * <b>The specification directory must exist.</b> {@code CLAUDE.md} carries a standing rule against
 * spec files in the workspace, aimed at transient per-task plans; {@code spec/} is the deliberate
 * exception, containing {@code spec/README.md} and the capability chapters.
 *
 * <p>
 * <b>Every chapter must carry all four sections.</b> A chapter without <em>Limits</em> or without
 * <em>Not supported</em> is not merely incomplete — those two sections record what the product
 * deliberately does not do, and a deliberate absence is precisely the thing no other test in this
 * suite can fail on. Omitting them silently converts "we decided against this" into "nobody
 * considered it".
 *
 * <p>
 * <b>The Contents table must resolve.</b> Its entries are the navigation between chapters and
 * files, and a reader — especially an agent told to read "the relevant chapter in {@code spec/}" —
 * finds a chapter through them. A chapter file added without an index entry or summary, or an index
 * entry whose link no longer matches, makes that chapter unfindable while the documentation still
 * looks complete.
 */
class ProductSpecStructureTest {

    private static final Path SPEC_DIR = Path.of("spec");
    private static final Path SPEC_README = SPEC_DIR.resolve("README.md");

    /** The four sections every capability chapter promises. */
    private static final List<String> REQUIRED_SECTIONS =
        List.of("What you can do", "How it behaves", "Limits", "Not supported");

    /** A numbered capability chapter heading, e.g. {@code # 3. Building the knowledge base}. */
    private static final Pattern CHAPTER =
        Pattern.compile("^#+ (\\d+)\\. (.+)$", Pattern.MULTILINE);

    /** Any second- or third-level heading, used to resolve in-document anchors. */
    private static final Pattern HEADING = Pattern.compile("^#{2,3} (.+)$", Pattern.MULTILINE);

    /** A markdown link to an in-document anchor, e.g. {@code [What Yvoke is](#what-yvoke-is)}. */
    private static final Pattern ANCHOR_LINK = Pattern.compile("\\[[^]]+]\\(#([a-z0-9-]+)\\)");

    /**
     * A markdown link to a chapter file, e.g. {@code [Asking questions](01_asking_questions.md)}.
     */
    private static final Pattern CHAPTER_FILE_LINK =
        Pattern.compile("\\[[^]]+]\\((0\\d_[a-z0-9_-]+\\.md)\\)");

    private static List<Path> chapterFiles() throws IOException {
        assertThat(SPEC_DIR).as("spec/ directory must exist").isDirectory();
        try (var stream = Files.list(SPEC_DIR)) {
            return stream.filter(p -> p.getFileName().toString().matches("^0\\d_.+\\.md$")).sorted()
                .toList();
        }
    }

    private static String specReadme() throws IOException {
        assertThat(SPEC_README)
            .as("spec/README.md is the functional specification catalog and index — it must exist")
            .exists();
        return Files.readString(SPEC_README, StandardCharsets.UTF_8);
    }

    @Test
    void capabilityChapterFilesExist() throws IOException {
        List<Path> files = chapterFiles();
        assertThat(files)
            .as("spec/ must contain at least 8 numbered capability chapters (01_ to ...)")
            .hasSizeGreaterThanOrEqualTo(8);
    }

    @Test
    void everyCapabilityChapterCarriesAllFourSections() throws IOException {
        List<Path> files = chapterFiles();
        assertThat(files).isNotEmpty();

        for (Path chapterPath : files) {
            String text = Files.readString(chapterPath, StandardCharsets.UTF_8);
            Matcher chapterMatcher = CHAPTER.matcher(text);
            assertThat(chapterMatcher.find())
                .as("%s must contain a numbered chapter title", chapterPath).isTrue();

            String title = chapterMatcher.group(1) + ". " + chapterMatcher.group(2);

            for (String section : REQUIRED_SECTIONS) {
                boolean hasSection =
                    text.contains("## " + section) || text.contains("### " + section);
                assertThat(hasSection).as(
                    "chapter '%s' in %s must carry section '%s' — 'Limits' and 'Not supported' "
                        + "especially, since a deliberate absence is what no other test can fail on",
                    title, chapterPath, section).isTrue();
            }
        }
    }

    @Test
    void everyContentsEntryPointsAtAHeadingOrFileThatExists() throws IOException {
        String text = specReadme();

        List<String> anchors = new ArrayList<>();
        Matcher heading = HEADING.matcher(text);
        while (heading.find()) {
            anchors.add(githubAnchor(heading.group(1)));
        }

        int contents = text.indexOf("## Contents");
        assertThat(contents).as("spec/README.md must have a Contents table").isNotNegative();
        int nextSection = text.indexOf("\n---", contents);
        String table = text.substring(contents, nextSection != -1 ? nextSection : text.length());

        // In-document anchors
        List<String> linkedAnchors = new ArrayList<>();
        Matcher link = ANCHOR_LINK.matcher(table);
        while (link.find()) {
            linkedAnchors.add(link.group(1));
        }

        assertThat(linkedAnchors).as("the Contents table must link to in-document sections")
            .isNotEmpty();
        assertThat(anchors)
            .as("every in-document Contents link must resolve to a real heading in spec/README.md")
            .containsAll(linkedAnchors);

        // File links
        List<String> linkedFiles = new ArrayList<>();
        Matcher fileLink = CHAPTER_FILE_LINK.matcher(table);
        while (fileLink.find()) {
            linkedFiles.add(fileLink.group(1));
        }

        List<Path> files = chapterFiles();
        assertThat(linkedFiles).as("the Contents table must link to all chapter files")
            .hasSameSizeAs(files);
        for (String linkedFile : linkedFiles) {
            Path target = SPEC_DIR.resolve(linkedFile);
            assertThat(target)
                .as("chapter link '%s' in spec/README.md must resolve to an existing file",
                    linkedFile)
                .exists();
        }
    }

    @Test
    void everyCapabilityChapterIsListedInTheContentsWithASummary() throws IOException {
        String text = specReadme();

        int contents = text.indexOf("## Contents");
        int nextSection = text.indexOf("\n---", contents);
        String table = text.substring(contents, nextSection != -1 ? nextSection : text.length());

        List<Path> files = chapterFiles();
        assertThat(files).hasSizeGreaterThanOrEqualTo(8);

        for (Path file : files) {
            String filename = file.getFileName().toString();
            assertThat(table).as(
                "chapter file '%s' exists in spec/ but the Contents table in spec/README.md does not link to it",
                filename).contains("(" + filename + ")");
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

