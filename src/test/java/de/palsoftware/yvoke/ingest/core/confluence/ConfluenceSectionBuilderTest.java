package de.palsoftware.yvoke.ingest.core.confluence;

import static org.assertj.core.api.Assertions.assertThat;

import de.palsoftware.yvoke.ingest.core.model.MarkdownTree;
import de.palsoftware.yvoke.ingest.core.model.Section;
import java.util.List;
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

        assertThat(sections).hasSize(5);

        assertThat(sections.get(0).title()).isEqualTo("Agent Guide");
        assertThat(sections.get(0).depth()).isEqualTo(1);
        assertThat(sections.get(0).headingPath()).isEmpty();
        assertThat(sections.get(0).body()).isEqualTo("Intro prose.");

        assertThat(sections.get(1).title()).isEqualTo("Overview");
        assertThat(sections.get(1).depth()).isEqualTo(1);
        assertThat(sections.get(1).headingPath()).containsExactly("Agent Guide");

        assertThat(sections.get(2).title()).isEqualTo("Installation");
        assertThat(sections.get(2).depth()).isEqualTo(2);
        assertThat(sections.get(2).headingPath()).containsExactly("Agent Guide", "Overview");

        assertThat(sections.get(3).title()).isEqualTo("Prerequisites");
        assertThat(sections.get(3).depth()).isEqualTo(3);
        assertThat(sections.get(3).headingPath()).containsExactly("Agent Guide", "Overview",
            "Installation");

        assertThat(sections.get(4).title()).isEqualTo("Configuration");
        assertThat(sections.get(4).depth()).isEqualTo(2);
        assertThat(sections.get(4).headingPath()).containsExactly("Agent Guide", "Overview");
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
        assertThat(sections).anySatisfy(s -> assertThat(s.title()).isEqualTo("Real Section"));
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
}
