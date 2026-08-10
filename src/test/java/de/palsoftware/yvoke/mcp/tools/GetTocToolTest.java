package de.palsoftware.yvoke.mcp.tools;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import de.palsoftware.yvoke.document.core.repository.DocumentRepository;
import de.palsoftware.yvoke.document.core.model.DocumentRow;
import de.palsoftware.yvoke.document.core.model.TocNode;
import de.palsoftware.yvoke.document.core.service.TocService;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;

public class GetTocToolTest {

    private DocumentRepository documentRepository;
    private TocService tocService;
    private GetTocTool getTocTool;

    @BeforeEach
    public void setUp() {
        documentRepository = mock(DocumentRepository.class);
        tocService = mock(TocService.class);
        getTocTool = new GetTocTool(documentRepository, tocService);
    }

    @Test
    public void testGetToc() {
        UUID mockDocId = UUID.randomUUID();
        DocumentRow mockDoc = new DocumentRow(mockDocId, UUID.randomUUID(), "OIM - Manuals",
            "manual", "Manual Title", Map.of("tag", "9.3", "source_file", "manual.md"), "completed",
            Collections.emptyList(), Instant.now());
        when(documentRepository.findById(eq(mockDocId))).thenReturn(Optional.of(mockDoc));

        TocNode node = new TocNode(List.of("Ch1", "SecA"), 10, 5, null);
        when(tocService.getToc(eq(mockDocId))).thenReturn(List.of(node));

        // Test new direct UUID method
        String outputDirect = getTocTool.getToc(mockDocId.toString());
        assertTrue(outputDirect.contains("# Table of contents:"));
        assertTrue(outputDirect.contains("- SecA  _(5 chunks)_"));
    }

    /**
     * {@code get_toc} stops emitting entries once it has written 400 lines and appends a note
     * saying how many were dropped. Without that note a truncated table of contents is
     * indistinguishable from a complete one: it ends on a well-formed entry, the header still
     * announces the FULL entry count (it is rendered from {@code nodes.size()} before the loop
     * runs), and the closing "Read text: get_section(...)" line still arrives — so the agent reads
     * a manual's TOC, does not find the section it needs among the first 400 lines, and concludes
     * the topic is not documented. On this corpus that is not hypothetical: the big OIM manuals run
     * to thousands of sections, and top-down browsing playbooks navigate exclusively through this
     * output, so a silent cut removes whole chapters from everything downstream of it.
     *
     * <p>
     * No existing test would notice. Both tests in this file stub a single {@link TocNode}, so the
     * cap branch has never executed at all — the line budget, the off-by-one on the three header
     * lines ({@code out.size() - 3 >= lineCap}) and the note itself are all unexercised. The
     * 5-entry half of this test is the control: a TOC that fits must not claim anything is missing,
     * which is what stops the note from being "fixed" by emitting it unconditionally.
     */
    @Test
    public void aTocTruncatedAtTheLineCapSaysHowManyEntriesAreMissing() {
        UUID bigDocId = UUID.randomUUID();
        DocumentRow bigDoc = new DocumentRow(bigDocId, UUID.randomUUID(), "OIM - Manuals", "manual",
            "Big Manual", Map.of("tag", "9.3", "source_file", "big.md"), "completed",
            Collections.emptyList(), Instant.now());
        when(documentRepository.findById(eq(bigDocId))).thenReturn(Optional.of(bigDoc));
        // Summary-less nodes cost exactly one line each, so 450 entries overrun the 400-line cap
        // by 50 — the number the note has to report.
        List<TocNode> many = new ArrayList<>();
        for (int i = 1; i <= 450; i++) {
            many.add(new TocNode(List.of("Manual", "Section " + i), i, 1, null));
        }
        when(tocService.getToc(eq(bigDocId))).thenReturn(many);

        String output = getTocTool.getToc(bigDocId.toString());

        assertTrue(output.contains("- Section 400  _(1 chunk)_"),
            "the 400th entry is the last one that fits the cap, got:\n" + output);
        assertFalse(output.contains("Section 450"),
            "the cap must actually bite, otherwise this test proves nothing:\n" + output);
        assertTrue(output.contains("_[50 more entries not shown]_"),
            "a truncated TOC that does not say so reads as the whole manual, got:\n" + output);

        UUID smallDocId = UUID.randomUUID();
        DocumentRow smallDoc = new DocumentRow(smallDocId, UUID.randomUUID(), "OIM - Manuals",
            "manual", "Small Manual", Map.of("tag", "9.3", "source_file", "small.md"), "completed",
            Collections.emptyList(), Instant.now());
        when(documentRepository.findById(eq(smallDocId))).thenReturn(Optional.of(smallDoc));
        List<TocNode> few = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            few.add(new TocNode(List.of("Manual", "Section " + i), i, 1, null));
        }
        when(tocService.getToc(eq(smallDocId))).thenReturn(few);

        String complete = getTocTool.getToc(smallDocId.toString());

        assertTrue(complete.contains("- Section 5  _(1 chunk)_"),
            "the short TOC must render in full, got:\n" + complete);
        assertFalse(complete.contains("more entr"),
            "a complete TOC must not carry a truncation note, got:\n" + complete);
    }

    @Test
    public void testGetTocDirectValidationAndNotFound() {
        String out1 = getTocTool.getToc("");
        assertTrue(out1.contains("Error: 'document_id' parameter is required"));

        String out2 = getTocTool.getToc("invalid-uuid");
        assertTrue(out2.contains("Error: Invalid UUID format"));

        UUID missingId = UUID.randomUUID();
        when(documentRepository.findById(eq(missingId))).thenReturn(Optional.empty());
        String out3 = getTocTool.getToc(missingId.toString());
        assertTrue(out3.contains("not found"));
    }
}
