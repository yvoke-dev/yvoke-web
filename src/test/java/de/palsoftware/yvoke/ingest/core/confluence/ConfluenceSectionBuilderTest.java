package de.palsoftware.yvoke.ingest.core.confluence;

import static org.assertj.core.api.Assertions.assertThat;

import de.palsoftware.yvoke.ingest.core.model.MarkdownTree;
import de.palsoftware.yvoke.ingest.core.model.Section;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Pins the three content-loss paths that made real Confluence pages ingest as zero chunks while the
 * job reported success: a body with no heading at all, a body whose only heading is a leading H1
 * (consumed as the document title by {@link MarkdownTree}), and prose sitting before the first
 * heading.
 */
class ConfluenceSectionBuilderTest {

    @Test
    void headingLessBodyStillProducesASection() {
        List<Section> sections =
            ConfluenceSectionBuilder.build("Release Notes", "Just prose.\n\nMore prose.");

        assertThat(sections).hasSize(1);
        Section only = sections.get(0);
        assertThat(only.title()).isEqualTo("Release Notes");
        assertThat(only.depth()).isGreaterThanOrEqualTo(1);
        assertThat(only.body()).contains("Just prose.").contains("More prose.");
    }

    @Test
    void singleLeadingH1IsNotSwallowedAsTheDocumentTitle() {
        List<Section> sections =
            ConfluenceSectionBuilder.build("Page Title", "# Only Heading\n\nBody text.");

        assertThat(sections).isNotEmpty();
        assertThat(sections).anySatisfy(s -> assertThat(s.body()).contains("Body text."));
    }

    @Test
    void proseBeforeTheFirstHeadingSurvives() {
        List<Section> sections = ConfluenceSectionBuilder.build("Guide",
            "Intro paragraph that explains everything.\n\n## Section A\n\nA body.");

        assertThat(sections).anySatisfy(
            s -> assertThat(s.body()).contains("Intro paragraph that explains everything."));
        assertThat(sections).anySatisfy(s -> assertThat(s.body()).contains("A body."));
    }

    @Test
    void everySectionCarriesThePageTitleInItsChunkText() {
        List<Section> sections = ConfluenceSectionBuilder.build("Kerberos SSO",
            "Intro.\n\n## Setup\n\nsetup body\n\n### Detail\n\ndetail body");

        assertThat(sections).isNotEmpty();
        for (Section s : sections) {
            assertThat(s.toChunkText()).contains("Kerberos SSO");
            assertThat(s.depth()).isGreaterThanOrEqualTo(1);
        }
    }

    @Test
    void oversizedHeadingLessBodyIsSplitIntoSeveralChunks() {
        String paragraph = "x".repeat(2_000);
        String body = (paragraph + "\n\n").repeat(6); // 12k chars, no heading anywhere

        List<Section> sections = ConfluenceSectionBuilder.build("Big Page", body);

        assertThat(sections.size()).isGreaterThan(1);
        for (Section s : sections) {
            assertThat(s.body().length()).isLessThanOrEqualTo(MarkdownTree.CHUNK_BODY_MAX_CHARS);
        }
    }

    @Test
    void oversizedSectionUnderAHeadingIsSplitIntoSeveralChunks() {
        String paragraph = "y".repeat(2_000);
        String md = "## Section A\n\n" + (paragraph + "\n\n").repeat(6);

        List<Section> sections = ConfluenceSectionBuilder.build("Big Page", md);

        assertThat(sections.size()).isGreaterThan(1);
        for (Section s : sections) {
            assertThat(s.body().length()).isLessThanOrEqualTo(MarkdownTree.CHUNK_BODY_MAX_CHARS);
        }
    }

    /**
     * The realistic shape of a Confluence page: H1 sections with H2/H3 beneath them. Before the
     * converter was pinned to ATX headings this arrived as setext underlines, the builder saw no
     * heading at all, and the whole page collapsed into one title blob with an empty heading path.
     */
    @Test
    void h1H2H3PageNestsProperlyInsteadOfCollapsingIntoOneBlob() {
        String md = """
            Intro prose.

            # Overview

            overview body

            ## Installation

            install body

            ### Prerequisites

            prereq body

            ## Configuration

            config body
            """;

        List<Section> sections = ConfluenceSectionBuilder.build("Agent Guide", md);

        assertThat(sections).hasSize(1);
        Section chunk = sections.get(0);
        assertThat(chunk.title()).isEqualTo("Agent Guide");
        assertThat(chunk.depth()).isEqualTo(1);
        assertThat(chunk.headingPath()).isEmpty();
        assertThat(chunk.body()).contains("Intro prose.").contains("# Overview\n\noverview body")
            .contains("## Installation\n\ninstall body")
            .contains("### Prerequisites\n\nprereq body")
            .contains("## Configuration\n\nconfig body");

        assertThat(chunk.toChunkText()).isEqualTo("""
            # Agent Guide

            Intro prose.

            # Overview

            overview body

            ## Installation

            install body

            ### Prerequisites

            prereq body

            ## Configuration

            config body
            """);
    }

    @Test
    void smallSectionsArePackedUpToMaxChars() {
        String p1 = "1".repeat(1_000);
        String p2 = "2".repeat(1_000);
        String p3 = "3".repeat(1_000);
        String p4 = "4".repeat(1_000);
        String md = "## Section 1\n\n" + p1 + "\n\n## Section 2\n\n" + p2 + "\n\n## Section 3\n\n"
            + p3 + "\n\n## Section 4\n\n" + p4;

        List<Section> sections = ConfluenceSectionBuilder.build("Big Guide", md);

        // Sections 1, 2, 3 fit within 3,500 chars; section 4 rolls over into chunk 2
        assertThat(sections).hasSize(2);

        Section chunk1 = sections.get(0);
        assertThat(chunk1.body()).contains("Section 1").contains("Section 2").contains("Section 3");
        assertThat(chunk1.body()).doesNotContain("Section 4");
        assertThat(chunk1.body().length()).isLessThanOrEqualTo(MarkdownTree.CHUNK_BODY_MAX_CHARS);

        Section chunk2 = sections.get(1);
        assertThat(chunk2.title()).isEqualTo("Section 4");
        assertThat(chunk2.toChunkText()).contains("Section 4");
        assertThat(chunk2.body()).contains(p4);
        assertThat(chunk2.body()).doesNotContain("Section 1").doesNotContain("Section 2")
            .doesNotContain("Section 3");
        assertThat(chunk2.body().length()).isLessThanOrEqualTo(MarkdownTree.CHUNK_BODY_MAX_CHARS);
    }

    @Test
    void oversizedSectionIsSplitAndEmittedSeparately() {
        String shortText1 = "a".repeat(300);
        String hugeText = "b".repeat(5_000);
        String shortText2 = "c".repeat(300);
        String shortText3 = "d".repeat(300);

        String md = "## Section 1\n\n" + shortText1 + "\n\n## Huge Section\n\n" + hugeText
            + "\n\n## Section 3\n\n" + shortText2 + "\n\n## Section 4\n\n" + shortText3;

        List<Section> sections = ConfluenceSectionBuilder.build("Mixed Page", md);

        // Chunk 1: Section 1 (flushed before huge section)
        // Chunks 2 & 3: Huge Section split into parts
        // Chunk 4: Section 3 & 4 packed together
        assertThat(sections).hasSize(4);

        assertThat(sections.get(0).title()).isEqualTo("Section 1");
        assertThat(sections.get(0).body()).contains(shortText1);

        assertThat(sections.get(1).title()).contains("Huge Section (part 1/");
        assertThat(sections.get(1).body().length())
            .isLessThanOrEqualTo(MarkdownTree.CHUNK_BODY_MAX_CHARS);

        assertThat(sections.get(2).title()).contains("Huge Section (part 2/");
        assertThat(sections.get(2).body().length())
            .isLessThanOrEqualTo(MarkdownTree.CHUNK_BODY_MAX_CHARS);

        assertThat(sections.get(3).body()).contains("Section 3").contains("Section 4");
    }

    @Test
    void packedChunkRetainsSubheadingsAndCommonParentMetadata() {
        // Data Storage is large enough that it forms its own chunk, so Primary DB and Cache
        // rollover
        // into chunk 2 sharing Data Storage as their common parent heading path.
        List<Section> raw = List.of(
            new Section(2, "Data Storage", List.of("Manual", "Architecture"), "x".repeat(3_480)),
            new Section(3, "Primary DB", List.of("Manual", "Architecture", "Data Storage"),
                "Postgres 16 details."),
            new Section(3, "Cache", List.of("Manual", "Architecture", "Data Storage"),
                "Redis 7 details."));

        List<Section> packed = ConfluenceSectionBuilder.packSections("Manual", raw, 3500);

        assertThat(packed).hasSize(2);
        Section chunk2 = packed.get(1);
        assertThat(chunk2.title()).isEqualTo("Data Storage");
        assertThat(chunk2.depth()).isEqualTo(2);
        assertThat(chunk2.headingPath()).containsExactly("Manual", "Architecture");
        assertThat(chunk2.body())
            .isEqualTo("### Primary DB\n\nPostgres 16 details.\n\n### Cache\n\nRedis 7 details.");
        assertThat(chunk2.toChunkText())
            .isEqualTo("> Section path: Manual > Architecture\n\n" + "## Data Storage\n\n"
                + "### Primary DB\n\nPostgres 16 details.\n\n" + "### Cache\n\nRedis 7 details.\n");
    }

    @Test
    void singleChunkPageRemainsUnchanged() {
        List<Section> sections = ConfluenceSectionBuilder.build("Single Section Page",
            "Intro paragraph without subheadings.");

        assertThat(sections).hasSize(1);
        Section only = sections.get(0);
        assertThat(only.title()).isEqualTo("Single Section Page");
        assertThat(only.depth()).isEqualTo(1);
        assertThat(only.headingPath()).isEmpty();
        assertThat(only.body()).isEqualTo("Intro paragraph without subheadings.");
    }

    /**
     * FIX D: {@code MarkdownTree.filterSections} drops any depth-1 section with an empty heading
     * path titled "table of contents" — which is exactly the shape of the synthetic page-title
     * root. A page legitimately called "Table of Contents" therefore lost its whole preamble (and,
     * with no nested sections, produced zero sections and failed the job).
     */
    @Test
    void pageTitledTableOfContentsKeepsItsPreamble() {
        List<Section> sections = ConfluenceSectionBuilder.build("Table of Contents",
            "This page explains how the manual is organised.");

        assertThat(sections).hasSize(1);
        assertThat(sections.get(0).title()).isEqualTo("Table of Contents");
        assertThat(sections.get(0).body()).contains("how the manual is organised");
    }

    @Test
    void pageTitledTableOfContentsStillFiltersItsNestedTocSection() {
        String md = """
            Preamble prose.

            ## Table of contents

            ### Linky

            ## Real Section

            Real body content.
            """;

        List<Section> sections = ConfluenceSectionBuilder.build("Table of Contents", md);

        assertThat(sections).anySatisfy(s -> assertThat(s.body()).contains("Preamble prose."));
        assertThat(sections).noneSatisfy(s -> assertThat(s.title()).isEqualTo("Table of contents"));
        assertThat(sections).noneSatisfy(s -> assertThat(s.title()).isEqualTo("Linky"));
        assertThat(sections).noneSatisfy(s -> assertThat(s.body()).contains("Linky"));
        assertThat(sections).anySatisfy(s -> assertThat(s.body()).contains("Real Section"));
    }

    @Test
    void tableOfContentsIsStillFilteredOut() {
        String md = """
            ## Table of contents

            ### Linky

            ## Real Section

            Real body content.
            """;

        List<Section> sections = ConfluenceSectionBuilder.build("Page Title", md);

        assertThat(sections).noneSatisfy(s -> assertThat(s.title()).isEqualTo("Table of contents"));
        assertThat(sections).anySatisfy(s -> assertThat(s.title()).isEqualTo("Real Section"));
    }

    @Test
    void leadingH1DuplicatingThePageTitleIsNotRepeated() {
        List<Section> sections =
            ConfluenceSectionBuilder.build("Page Title", "# Page Title\n\nBody.");

        assertThat(sections).hasSize(1);
        Section only = sections.get(0);
        assertThat(only.body()).contains("Body.");
        assertThat(only.headingPath()).doesNotContain("Page Title");
        assertThat(only.toChunkText()).containsOnlyOnce("Page Title");
    }

    @Test
    void blankPageTitleFallsBackToAPlaceholder() {
        List<Section> sections = ConfluenceSectionBuilder.build("   ", "Some prose.");

        assertThat(sections).hasSize(1);
        assertThat(sections.get(0).title()).isEqualTo("Untitled");
        assertThat(sections.get(0).depth()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void trulyEmptyBodyYieldsNoSectionsSoTheCallerCanFailLoudly() {
        assertThat(ConfluenceSectionBuilder.build("Page Title", "   \n\n  ")).isEmpty();
        assertThat(ConfluenceSectionBuilder.build("Page Title", "")).isEmpty();
    }

    @Test
    void buildPrependsMetadataHeaderToSingleSectionChunk() {
        String url = "https://wiki.example.com/spaces/SP/pages/123";
        List<Section> sections = ConfluenceSectionBuilder.build("Single Page", "Prose content.",
            url, "Alice", "2026-09-01", 4);

        assertThat(sections).hasSize(1);
        Section chunk = sections.get(0);
        String expectedHeader = "🔗 Confluence Page: [View in Confluence](" + url + ")\n"
            + "👤 Author: Alice | 📅 Last Updated: 2026-09-01 (v4)";
        assertThat(chunk.body()).startsWith(expectedHeader + "\n\n");
        assertThat(chunk.body()).endsWith("Prose content.");
    }

    @Test
    void buildPrependsMetadataHeaderToMultiSectionCompositeChunk() {
        String url = "https://wiki.example.com/spaces/SP/pages/456";
        String md = "Intro.\n\n# Overview\n\nOverview prose.\n\n## Details\n\nDetail prose.";
        List<Section> sections =
            ConfluenceSectionBuilder.build("Guide", md, url, "Bob", "2026-08-15", 2);

        assertThat(sections).hasSize(1);
        Section chunk = sections.get(0);
        String expectedHeader = "🔗 Confluence Page: [View in Confluence](" + url + ")\n"
            + "👤 Author: Bob | 📅 Last Updated: 2026-08-15 (v2)";
        assertThat(chunk.body()).startsWith(expectedHeader + "\n\n");
        assertThat(chunk.body()).contains("Intro.").contains("Overview prose.")
            .contains("Detail prose.");
    }

    @Test
    void buildPrependsMetadataHeaderToAllPackedChunksWhenRolloverOccurs() {
        String url = "https://wiki.example.com/spaces/SP/pages/789";
        String p1 = "1".repeat(2_000);
        String p2 = "2".repeat(2_000);
        String md = "## Section 1\n\n" + p1 + "\n\n## Section 2\n\n" + p2;

        List<Section> sections =
            ConfluenceSectionBuilder.build("Big Guide", md, url, "Charlie", "2026-07-20", 1);

        assertThat(sections).hasSize(2);
        String expectedHeader = "🔗 Confluence Page: [View in Confluence](" + url + ")\n"
            + "👤 Author: Charlie | 📅 Last Updated: 2026-07-20 (v1)";
        for (Section chunk : sections) {
            assertThat(chunk.body()).startsWith(expectedHeader + "\n\n");
        }
    }

    @Test
    void buildPrependsMetadataHeaderToPreambleAndFollowingSections() {
        String url = "https://wiki.example.com/spaces/SP/pages/101";
        String md = "Preamble prose.\n\n## Heading A\n\nContent A.";

        List<Section> sections =
            ConfluenceSectionBuilder.build("Preamble Page", md, url, "Dana", "2026-06-10", 5);

        assertThat(sections).isNotEmpty();
        String expectedHeader = "🔗 Confluence Page: [View in Confluence](" + url + ")\n"
            + "👤 Author: Dana | 📅 Last Updated: 2026-06-10 (v5)";
        for (Section chunk : sections) {
            assertThat(chunk.body()).startsWith(expectedHeader + "\n\n");
        }
    }

    @Test
    void buildPrependsMetadataHeaderToEverySplitPartOfOversizedSection() {
        String url = "https://wiki.example.com/spaces/SP/pages/102";
        String paragraph = "z".repeat(2_000);
        String md = "## Oversized\n\n" + (paragraph + "\n\n").repeat(6);

        List<Section> sections =
            ConfluenceSectionBuilder.build("Huge Page", md, url, "Eve", "2026-05-01", 10);

        assertThat(sections.size()).isGreaterThan(1);
        String expectedHeader = "🔗 Confluence Page: [View in Confluence](" + url + ")\n"
            + "👤 Author: Eve | 📅 Last Updated: 2026-05-01 (v10)";
        for (Section chunk : sections) {
            assertThat(chunk.body()).startsWith(expectedHeader + "\n\n");
        }
    }

    /**
     * The header is prepended AFTER packing and after the oversized split, so unless it comes out
     * of the allowance first every chunk on a page that has metadata is over the cap by its length
     * — and that length is a function of the page URL and the author's name, so the real ceiling
     * moves per page with nothing measuring it.
     *
     * <p>
     * The three inputs are sized to land JUST under the cap on purpose. A first version of this
     * test used round numbers, packed to roughly 3,050 and passed on its first run against the
     * unfixed builder — proving only that a chunk well inside the budget stays inside it. All three
     * paths have to be adversarial: a lone section at the cap, a composite packed to the cap, and
     * an oversized body whose split parts land at the cap.
     */
    @Test
    void everyChunkStaysWithinTheCapOnceTheMetadataHeaderIsCounted() {
        String url = "https://wiki.example.com/wiki/spaces/ARCHITECTURE/pages/2147483647"
            + "/Identity+Manager+Custom+Connector+Installation+Guide";
        String author = "Alexandra Featherstonehaugh-Wolfeschlegelstein";
        int cap = MarkdownTree.CHUNK_BODY_MAX_CHARS;

        // 1. A lone section whose body already sits exactly on the cap.
        List<Section> lone = ConfluenceSectionBuilder.build("Guide",
            "## Only\n\n" + "x".repeat(cap), url, author, "2026-08-15", 137);

        // 2. Two sections that pack to just under the cap together.
        String half = "y".repeat((cap / 2) - 20);
        List<Section> packed = ConfluenceSectionBuilder.build("Guide",
            "## A\n\n" + half + "\n\n## B\n\n" + half, url, author, "2026-08-15", 137);

        // 3. An oversized body whose greedy split lands each part just under the cap.
        String para = "z".repeat((cap / 2) - 20);
        List<Section> split = ConfluenceSectionBuilder.build("Guide",
            "## Oversized\n\n" + String.join("\n\n", para, para, para, para), url, author,
            "2026-08-15", 137);
        assertThat(split).as("the oversized body must actually split").hasSizeGreaterThan(1);
        assertThat(split).anySatisfy(sec -> assertThat(sec.title()).contains("(part 1/"));

        for (Section chunk : Stream.of(lone, packed, split).flatMap(List::stream).toList()) {
            assertThat(chunk.body()).startsWith("\uD83D\uDD17 Confluence Page:");
            assertThat(chunk.body().length()).as(
                "chunk '%s' overruns the cap by %d chars — the header must come out of the"
                    + " allowance, not sit on top of it",
                chunk.title(), chunk.body().length() - cap).isLessThanOrEqualTo(cap);
        }
    }

    /**
     * A heading's identity on this corpus is its PATH, not its name — "Overview", "Notes" and
     * "Configuration" repeat all over a space. Resolving the composite's depth by scanning the
     * whole page for the first section with a matching title therefore picks whichever same-named
     * heading happens to come first in document order, and the chunk is then rendered at the wrong
     * level and stores the wrong {@code chunks.depth}, which is what the document hierarchy reads.
     *
     * <p>
     * Here the earlier {@code ## Notes} shadows the real ancestor {@code ### Notes}, so a
     * name-based lookup answers 2 where the answer is 3.
     */
    @Test
    void aCompositeTakesItsDepthFromTheAncestorAtItsOwnPathNotTheFirstSameNamedHeading() {
        // Sized so the rollover actually isolates A and B: the shadow heading and Config pack
        // together, the real ancestor fills a chunk on its own, and only then do A and B form a
        // group whose common prefix ends at [Page, Config, Notes]. Without that sizing the whole
        // page packs into one chunk and the lookup is never exercised.
        String md = "## Notes\n\n" + "n".repeat(100) + "\n\n## Config\n\n" + "c".repeat(100)
            + "\n\n### Notes\n\n" + "r".repeat(MarkdownTree.CHUNK_BODY_MAX_CHARS - 20)
            + "\n\n#### A\n\naaa\n\n#### B\n\nbbb";

        List<Section> sections = ConfluenceSectionBuilder.build("Page", md);

        Section composite = sections.stream()
            .filter(sec -> sec.body().contains("aaa") && sec.body().contains("bbb")).findFirst()
            .orElseThrow(() -> new AssertionError("A and B did not pack together: " + sections));
        assertThat(composite.title()).isEqualTo("Notes");
        assertThat(composite.headingPath()).containsExactly("Page", "Config");
        assertThat(composite.depth())
            .as("the ancestor at path [Page, Config, Notes] is depth 3; the earlier ## Notes is a"
                + " different heading that merely shares a name")
            .isEqualTo(3);
        assertThat(composite.toChunkText()).contains("### Notes");
    }

    @Test
    void buildMetadataHeaderGracefulDegradation() {
        String url = "https://wiki.example.com/spaces/SP/pages/200";

        // 1. Author and lastUpdated without version
        List<Section> s1 =
            ConfluenceSectionBuilder.build("Page", "Body", url, "Alice", "2026-09-01", null);
        assertThat(s1.get(0).body()).startsWith("🔗 Confluence Page: [View in Confluence](" + url
            + ")\n" + "👤 Author: Alice | 📅 Last Updated: 2026-09-01\n\n");

        // 2. Author only (no date, no version)
        List<Section> s2 = ConfluenceSectionBuilder.build("Page", "Body", url, "Alice", null, null);
        assertThat(s2.get(0).body()).startsWith(
            "🔗 Confluence Page: [View in Confluence](" + url + ")\n" + "👤 Author: Alice\n\n");

        // 3. LastUpdated with version (no author)
        List<Section> s3 =
            ConfluenceSectionBuilder.build("Page", "Body", url, null, "2026-09-01", 3);
        assertThat(s3.get(0).body()).startsWith("🔗 Confluence Page: [View in Confluence](" + url
            + ")\n" + "📅 Last Updated: 2026-09-01 (v3)\n\n");

        // 4. LastUpdated without version (no author)
        List<Section> s4 =
            ConfluenceSectionBuilder.build("Page", "Body", url, null, "2026-09-01", null);
        assertThat(s4.get(0).body()).startsWith("🔗 Confluence Page: [View in Confluence](" + url
            + ")\n" + "📅 Last Updated: 2026-09-01\n\n");

        // 5. SourceUrl only (no author, no date, no version)
        List<Section> s5 = ConfluenceSectionBuilder.build("Page", "Body", url, null, null, null);
        assertThat(s5.get(0).body())
            .startsWith("🔗 Confluence Page: [View in Confluence](" + url + ")\n\n");

        // 6. Author only, no sourceUrl
        List<Section> s6 =
            ConfluenceSectionBuilder.build("Page", "Body", null, "Alice", null, null);
        assertThat(s6.get(0).body()).startsWith("👤 Author: Alice\n\n");

        // 7. All null
        List<Section> s7 = ConfluenceSectionBuilder.build("Page", "Body", null, null, null, null);
        assertThat(s7.get(0).body()).isEqualTo("Body");

        // 8. Backward-compatible overload delegates with null metadata
        List<Section> s8 = ConfluenceSectionBuilder.build("Page", "Body");
        assertThat(s8.get(0).body()).isEqualTo("Body");
    }
}
