package de.palsoftware.yvoke.document.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.List;
import org.junit.jupiter.api.Test;

class HierarchyUtilsTest {

    @Test
    void testStripPart() {
        assertEquals("Introduction", HierarchyUtils.stripPart("Introduction (part 1/3)"));
        assertEquals("Setup", HierarchyUtils.stripPart("Setup (part 2/2) "));
        assertEquals("Normal Title", HierarchyUtils.stripPart("Normal Title"));
        assertEquals("", HierarchyUtils.stripPart(null));
        assertEquals("", HierarchyUtils.stripPart("   "));
    }

    @Test
    void testSplitHeadingPath() {
        assertEquals(List.of("Chapter 1", "Section 2", "Intro"),
            HierarchyUtils.splitHeadingPath("Chapter 1 > Section 2 > Intro (part 1/2)"));
        assertEquals(List.of(), HierarchyUtils.splitHeadingPath(""));
        assertEquals(List.of(), HierarchyUtils.splitHeadingPath(null));
    }

    @Test
    void testNormalizeSegment() {
        // Test non-breaking space (U+00A0) normalization to regular space
        assertEquals("one identity manager",
            HierarchyUtils.normalizeSegment("One\u00a0Identity\u00a0Manager"));
        assertEquals("some section text",
            HierarchyUtils.normalizeSegment("  Some   Section  Text  "));
        assertEquals("", HierarchyUtils.normalizeSegment(null));
    }

    @Test
    void testStripBreadcrumb() {
        String withBreadcrumb =
            "> Section path: Chapter 1 > Section 2\nActual content here\nMore lines";
        assertEquals("Actual content here\nMore lines",
            HierarchyUtils.stripBreadcrumb(withBreadcrumb));

        String noBreadcrumb = "No breadcrumb text";
        assertEquals("No breadcrumb text", HierarchyUtils.stripBreadcrumb(noBreadcrumb));
        assertEquals("", HierarchyUtils.stripBreadcrumb(null));
    }

    @Test
    void testIsSubpathOf() {
        List<String> target = List.of("Chapter 1", "Section 2");

        assertTrue(
            HierarchyUtils.isSubpathOf(target, List.of("Chapter 1", "Section 2", "Subsection A")));
        assertTrue(HierarchyUtils.isSubpathOf(target, List.of("chapter 1", "section 2")));

        assertFalse(HierarchyUtils.isSubpathOf(target, List.of("Chapter 1")));
        assertFalse(HierarchyUtils.isSubpathOf(target, List.of("Chapter 1", "Section 3")));
    }

    @Test
    void testIsExactPathMatch() {
        List<String> target = List.of("Chapter 1", "Section 2");

        assertTrue(HierarchyUtils.isExactPathMatch(target, List.of("chapter 1", "section 2")));
        assertFalse(HierarchyUtils.isExactPathMatch(target,
            List.of("Chapter 1", "Section 2", "Subsection A")));
        assertFalse(HierarchyUtils.isExactPathMatch(target, List.of("Chapter 1")));
    }
}
