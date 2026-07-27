package de.palsoftware.yvoke.ingest.core.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class MarkdownTreeSplitTest {

    @Test
    void oversizedSectionSplitsAtBoundariesPreservingMetadata() {
        String paragraph = "x".repeat(2_000);
        String body = paragraph + "\n\n" + paragraph; // 4002 chars > 3500 cap
        Section big = new Section(3, "Big", List.of("Parent"), body);

        List<Section> parts = MarkdownTree.splitOversizedSection(big);

        assertThat(parts).hasSize(2);
        assertThat(parts.get(0).title()).isEqualTo("Big (part 1/2)");
        assertThat(parts.get(1).title()).isEqualTo("Big (part 2/2)");
        for (Section p : parts) {
            assertThat(p.depth()).isEqualTo(3);
            assertThat(p.headingPath()).containsExactly("Parent");
            assertThat(p.body().length()).isLessThanOrEqualTo(MarkdownTree.CHUNK_BODY_MAX_CHARS);
        }
    }

    @Test
    void sectionWithinCapIsNotSplit() {
        Section small = new Section(2, "Small", List.of(), "short body");
        assertThat(MarkdownTree.splitOversizedSection(small)).containsExactly(small);
    }

    @Test
    void greedySplitFallsBackToFinerSeparators() {
        // No blank lines or newlines: must fall through to the space separator.
        String word = "y".repeat(100);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            sb.append(word).append(' ');
        }
        List<String> pieces = MarkdownTree.splitRecursive(sb.toString().strip(), 3_500);
        assertThat(pieces.size()).isGreaterThan(1);
        for (String p : pieces) {
            assertThat(p.length()).isLessThanOrEqualTo(3_500);
        }
    }

    @Test
    void tableOfContentsBlockIsDropped() {
        String md = """
            # Title

            ## Table of contents

            ### Linky

            ## Real Section

            Real body content.
            """;
        List<Section> sections = MarkdownTree.buildOrderedSections(md);
        assertThat(sections).extracting(Section::title).containsExactly("Real Section");
    }

    @Test
    void emptyPlaceholderWithoutChildrenIsDropped() {
        String md = """
            # Title

            ## Group

            ### Child

            Child body.

            ## Lonely Placeholder

            ## Another

            Another body.
            """;
        List<Section> sections = MarkdownTree.buildOrderedSections(md);
        assertThat(sections).extracting(Section::title).containsExactly("Group", "Child",
            "Another");
    }

    @Test
    void sortOrderIsContiguousAndInDocumentOrder() {
        String md = """
            # Title

            ## A

            Body a.

            ## B

            Body b.

            ### B1

            Body b1.
            """;
        List<Section> sections = MarkdownTree.buildOrderedSections(md);
        // sort_order is the index in this list: contiguous 0..N-1, strictly increasing, no gaps.
        assertThat(sections).extracting(Section::title).containsExactly("A", "B", "B1");
        assertThat(sections).hasSize(3);
    }

    @Test
    void documentWithNoHeadingsYieldsNoSections() {
        assertThat(MarkdownTree.buildOrderedSections("just prose, no headings")).isEmpty();
    }
}
