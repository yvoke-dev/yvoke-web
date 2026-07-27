package de.palsoftware.yvoke.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Markdown table cells are clipped so one long value cannot blow up a whole tool result. The clip
 * used to end in a bare {@code "..."}, which is also legitimate content — so a truncated cell was
 * indistinguishable from a value that genuinely ends that way, and the caller had no way to know
 * how much was missing. Projecting a 4,275-char object returned ~23% of it, silently.
 */
public class McpToolUtilsTruncationTest {

    private static String cell(String value) {
        return McpToolUtils.formatTableRows(List.of(Map.of("v", value)), List.of("v"));
    }

    @Test
    public void testOverLongValueIsMarkedAndReportsItsTrueLength() {
        String value = "x".repeat(4275);

        String out = cell(value);

        assertTrue(out.contains("[truncated"), "expected an unambiguous marker, got: " + tail(out));
        assertTrue(out.contains("4275"),
            "the marker must state the original length so the caller can judge the loss: "
                + tail(out));
    }

    @Test
    public void testTruncatedCellStaysWithinTheWidthBudget() {
        String out = cell("x".repeat(9000));

        // The marker must fit INSIDE the budget, not be appended past it.
        for (String line : out.split("\n")) {
            assertTrue(line.length() <= McpToolUtils.MAX_CELL_CHARS + 8,
                "cell exceeded its budget (" + line.length() + " chars): " + tail(out));
        }
    }

    @Test
    public void testValueExactlyAtTheLimitIsNotMarked() {
        // Off-by-one guard: at the budget nothing was lost, so claiming truncation would be a lie.
        String out = cell("y".repeat(McpToolUtils.MAX_CELL_CHARS));

        assertFalse(out.contains("[truncated"),
            "a complete value was marked truncated: " + tail(out));
    }

    @Test
    public void testShortValueEndingInEllipsisIsUntouched() {
        // The exact ambiguity this fix removes: real content may end in "...".
        String value = "to be continued...";

        String out = cell(value);

        assertTrue(out.contains(value), "a genuine ellipsis was altered: " + out);
        assertFalse(out.contains("[truncated"), "a short value was marked truncated: " + out);
    }

    private static String tail(String s) {
        return s.length() > 160 ? "…" + s.substring(s.length() - 160) : s;
    }
}
