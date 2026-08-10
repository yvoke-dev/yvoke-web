package de.palsoftware.yvoke.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import de.palsoftware.yvoke.rag.retrieval.HybridSearchResult;
import de.palsoftware.yvoke.rag.retrieval.TelemetryInfo;
import java.util.UUID;

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

    /**
     * S4.17. {@code formatChunks} renders every {@code search_corpus} hit into the text the model
     * reads, and it emits the chunk body UNTRUNCATED — deliberately unlike the table cells the rest
     * of this class caps at {@link McpToolUtils#MAX_CELL_CHARS}. The asymmetry is the point: a
     * table cell is one projected field among many on a row, while a chunk IS the evidence. Cutting
     * it removes the part of the section the answer was supposed to rest on, and does it after
     * retrieval has already decided that chunk was the best match.
     *
     * <p>
     * The failure is completely silent. The answer still cites the chunk id, {@code verify_
     * citations} still resolves it (that tool loads no chunk text at all), and the model simply
     * writes a confident answer from the surviving prefix — so a truncation bug reads as the corpus
     * not containing the detail. And a cap here is the obvious, tempting symmetry: the budget
     * constant is right there in the same class, one line above the loop.
     *
     * <p>
     * {@code formatChunks} has no test at any tier — {@code McpToolUtilsTruncationTest} covers
     * {@code formatTableRows} only — so today it can be capped, and the citation handle the whole
     * answer stands on can be reshaped, with the entire suite green. The last assertion pins the
     * asymmetry explicitly, so this cannot be "fixed" by removing the table-cell cap instead.
     */
    @Test
    public void aChunkBodyReachesTheModelWholeWhileTableCellsStayCapped() {
        UUID chunkId = UUID.fromString("8f7c1a2b-3d4e-4f50-8a1b-2c3d4e5f6071");
        UUID docId = UUID.fromString("11111111-2222-4333-8444-555555555555");
        // Comfortably past the cell budget, with a sentinel at each end so a clip anywhere shows.
        String body = "OIM install prerequisites. " + "z".repeat(5000) + " END-OF-CHUNK";
        HybridSearchResult chunk = new HybridSearchResult(chunkId, docId, body,
            List.of("Install", "Prerequisites"), "Prerequisites", 2, 0, "10.0", "install-kit.md",
            "manual", "OIM", Map.of(), 0.87, new TelemetryInfo(true, false, 1, 0, 1));

        String out = McpToolUtils.formatChunks(List.of(chunk));

        assertTrue(out.contains(body),
            "the chunk body must reach the model whole — it is the evidence, not a table cell");
        assertFalse(out.contains("[truncated"),
            "a chunk must never be marked truncated: " + tail(out));
        assertTrue(out.contains("id=" + chunkId),
            "the citation handle the answer is grounded on must survive: " + out.substring(0, 120));
        assertTrue(out.contains("doc_id=" + docId), "the document handle must survive too");

        // The asymmetry is deliberate: the same class still caps a table cell of the same size.
        assertTrue(cell("z".repeat(5000)).contains("[truncated"),
            "table cells must stay capped, or this test would pass by removing the wrong cap");
    }

    /**
     * A markdown cell is delimited by {@code |} and terminated by a newline, so an unescaped value
     * containing either does not corrupt one cell — it re-columns the whole row. One pipe shifts
     * every later value left by a column, silently pairing each with the wrong header; one newline
     * splits the row in two and the tail line is then parsed as a further data row. Both shapes are
     * real here: {@code query_json_objects} projects arbitrary JSON leaves into cells (a
     * {@code "9.3|10.0"} version string, a multi-line description), and {@code list_documents} /
     * {@code get_toc} render titles straight from ingested markdown, where a pipe is ordinary
     * prose.
     *
     * <p>
     * Nothing throws and the output is still a well-formed grid, which is why no caller notices:
     * the agent reads a table whose values have quietly moved under the wrong columns and cites
     * them that way. The escaping is one {@code replace()} chain that reads like formatting polish
     * and invites removal — and the sibling truncation tests in this file all use pipe-free,
     * newline-free values, so not one of them would go red if it were dropped.
     */
    @Test
    public void aCellContainingPipesOrNewlinesCannotBreakTheTableGrid() {
        String piped = cell("a|b");
        String multiline = cell("x\ny");

        assertTrue(piped.contains("| a\\|b |"),
            "the pipe must be escaped so the value stays inside its own cell: " + piped);
        assertEquals(3, piped.split("\n").length,
            "a one-row table is header + separator + row and nothing else: " + piped);

        assertFalse(multiline.contains("x\ny"),
            "a raw newline must not survive inside a cell: " + multiline);
        assertTrue(multiline.contains("| x y |"),
            "the newline must collapse to a space, not vanish or split: " + multiline);
        assertEquals(3, multiline.split("\n").length,
            "the value split its row into two rows: " + multiline);
    }

    private static String tail(String s) {
        return s.length() > 160 ? "…" + s.substring(s.length() - 160) : s;
    }
}
