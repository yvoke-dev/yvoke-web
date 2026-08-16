package de.palsoftware.yvoke.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.document.core.model.SectionChunks;
import de.palsoftware.yvoke.rag.core.model.AgenticChatContext;
import de.palsoftware.yvoke.document.core.model.SectionChunks.SectionChunk;
import de.palsoftware.yvoke.document.core.service.SectionService;
import de.palsoftware.yvoke.rag.retrieval.ChunkBlocks;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code get_section} returns the passages of a section, each preceded by its own id.
 *
 * <p>
 * Without the ids an agent that read a section could only cite the whole document, which is both
 * imprecise for the reader and self-contradictory in orchestrated mode: 25 specialist playbooks
 * tell the agent to cite a section by {@code document_id}, while the reviewer playbook is told to
 * flag exactly that when the evidence carries a chunk id. The result was a rejection the
 * orchestrator has no tool to satisfy.
 *
 * <p>
 * The marker is deliberately NOT the {@code ChunkBlocks} header used by {@code search_corpus}: that
 * shape mandates a {@code score=} a section read has no value for. The two are separate
 * <em>patterns</em>, not separate parsers — {@code ChunkBlocks.SECTION_MARKER} matches this marker
 * too, which is what lets {@code EvidenceDigest} reduce a section to the passages the answer cites
 * instead of forwarding all of it to the reviewer.
 *
 * <p>
 * That makes the marker a two-place contract: written here, read in {@code ChunkBlocks}. The double
 * spaces are load-bearing on both sides. Parentheses rather than brackets, so
 * {@code CitationVerifier} and {@code CitationStreamingFilter} cannot mistake a marker for a
 * citation (CLAUDE.md § 6).
 */
public class GetSectionToolTest {

    private static final UUID CHUNK_A = UUID.fromString("8f7c1a2b-3d4e-4f50-8a1b-2c3d4e5f6071");
    private static final UUID CHUNK_B = UUID.fromString("1a2b3c4d-5e6f-4071-8899-aabbccddeeff");
    private static final UUID DOC = UUID.fromString("3d9a0000-1111-4222-8333-444455556666");

    private SectionService sectionService;
    private GetSectionTool getSectionTool;

    @BeforeEach
    public void setUp() {
        sectionService = mock(SectionService.class);
        getSectionTool = new GetSectionTool(sectionService);
    }

    private static SectionChunks section(SectionChunk... chunks) {
        return new SectionChunks(List.of("Ch1", "SecA"), "OIM Admin Guide", "9.3",
            "with sub-sections", List.of(chunks));
    }

    /**
     * The marker is written here and read in {@code ChunkBlocks}, and until this test existed
     * nothing joined the two ends: {@code ChunkBlocksTest} round-trips a hand-written fixture and
     * the assertions below compare against a hand-written literal, so changing the shape on one
     * side left both suites green while section evidence silently stopped reducing — the reviewer
     * would receive every passage of every section read, cited or not.
     *
     * <p>
     * So this feeds <em>real</em> tool output into the real parser. It is the only test that fails
     * when the two drift apart (CLAUDE.md § 6: when two things must agree and only a human keeps
     * them in sync, add the test that compares them).
     */
    @Test
    public void theRenderedMarkerIsTheOneChunkBlocksParses() {
        when(sectionService.getSectionChunksByDocumentId(anyString(), any()))
            .thenReturn(section(new SectionChunk(CHUNK_A, DOC, "Prerequisites", "First body."),
                new SectionChunk(CHUNK_B, DOC, "Next steps", "Second body.")));

        ChunkBlocks.Parsed parsed =
            ChunkBlocks.parse(getSectionTool.getSection(DOC.toString(), "Ch1 > SecA", null));

        assertThat(parsed.blocks()).as("one block per passage, or EvidenceDigest cannot cite-scope")
            .hasSize(2);
        assertThat(parsed.blocks()).extracting(ChunkBlocks.Block::chunkId)
            .as("the parsed ids must be the ids the tool wrote").containsExactly(CHUNK_A, CHUNK_B);
        assertThat(parsed.blocks()).extracting(ChunkBlocks.Block::documentId).containsExactly(DOC,
            DOC);
        assertThat(parsed.blocks()).extracting(ChunkBlocks.Block::body)
            .as("the body must travel with its own id, not the neighbouring one")
            .containsExactly("First body.", "Second body.");
        assertThat(parsed.preamble())
            .as("the section heading names what was read; it is not a block")
            .contains("# Section:");
    }

    @Test
    public void eachPassageIsPrecededByItsOwnChunkId() {
        when(sectionService.getSectionChunksByDocumentId(anyString(), any()))
            .thenReturn(section(new SectionChunk(CHUNK_A, DOC, "Prerequisites", "First body."),
                new SectionChunk(CHUNK_B, DOC, "Next steps", "Second body.")));

        String output = getSectionTool.getSection(DOC.toString(), "Ch1 > SecA", null);

        assertThat(output).as("an agent cannot cite what it cannot name")
            .contains("_(id=" + CHUNK_A + "  doc_id=" + DOC + ")_")
            .contains("_(id=" + CHUNK_B + "  doc_id=" + DOC + ")_").contains("First body.")
            .contains("Second body.");
        assertThat(output.indexOf("_(id=" + CHUNK_A + "  doc_id=" + DOC + ")_"))
            .as("the id must precede the passage it names, or it names the wrong one")
            .isLessThan(output.indexOf("First body."));
        assertThat(output.indexOf("First body."))
            .isLessThan(output.indexOf("_(id=" + CHUNK_B + "  doc_id=" + DOC + ")_"));
    }

    /**
     * Brackets are the citation syntax. A marker written as {@code [id=…]} would be scanned by
     * {@code CitationVerifier} and could be stripped from a live answer by
     * {@code CitationStreamingFilter}; parentheses are invisible to both.
     */
    @Test
    public void theMarkerIsNotBracketedSoItCannotBeReadAsACitation() {
        when(sectionService.getSectionChunksByDocumentId(anyString(), any()))
            .thenReturn(section(new SectionChunk(CHUNK_A, DOC, "Prerequisites", "Body.")));

        String output = getSectionTool.getSection(DOC.toString(), "Ch1 > SecA", null);

        assertThat(output).doesNotContain("[id=").doesNotContain("[chunk_id=");
    }

    /** The header still tells the agent which document and version it is reading. */
    @Test
    public void theSectionHeaderStillNamesTheDocumentAndTag() {
        when(sectionService.getSectionChunksByDocumentId(anyString(), any()))
            .thenReturn(section(new SectionChunk(CHUNK_A, DOC, "Prerequisites", "Body.")));

        String output = getSectionTool.getSection(DOC.toString(), "Ch1 > SecA", null);

        assertThat(output).contains("Ch1 > SecA").contains("OIM Admin Guide").contains("9.3")
            .contains("with sub-sections");
    }

    @Test
    public void aChunkIdLookupAlsoReturnsTheIdsOfTheWholeEnclosingSection() {
        when(sectionService.getSectionChunksByChunkId(eq("chunk-123")))
            .thenReturn(section(new SectionChunk(CHUNK_A, DOC, "Prerequisites", "First body."),
                new SectionChunk(CHUNK_B, DOC, "Next steps", "Second body.")));

        String output = getSectionTool.getSection(null, null, "chunk-123");

        assertThat(output)
            .as("a chunk_id lookup returns the WHOLE enclosing section, so every "
                + "passage in it needs its own id — not just the one asked for")
            .contains("_(id=" + CHUNK_A + "  doc_id=" + DOC + ")_")
            .contains("_(id=" + CHUNK_B + "  doc_id=" + DOC + ")_");
    }

    /**
     * The description the model reads now lives on {@link GetSectionToolCallback}, because the
     * hand-registered callback replaces the annotation-driven registration — {@code get_section}
     * has to see the conversation, and {@code ToolCallbacks.from(bean)} gives it nowhere to.
     *
     * <p>
     * Two things it must not say. It used to advertise {@code document (file name)}, a third way to
     * identify a section that was removed because a document name is not unique (one corpus lookup
     * matched 131 rows) — a model taking that at its word spends a turn on a call that can only
     * return the argument error. And it must mention the ids, or a model with no reason to expect
     * them keeps citing whole documents.
     */
    @Test
    public void theDescriptionMatchesWhatTheToolActuallyAcceptsAndReturns() {
        String description = new GetSectionToolCallback(getSectionTool, new ObjectMapper())
            .getToolDefinition().description();

        assertThat(description).as("name-based lookup was removed; advertising it wastes a turn")
            .doesNotContain("file name");
        assertThat(description).as("the model must know the passages carry citable ids")
            .contains("chunk_id=");
    }

    /**
     * Name-based lookup was removed: a document name is not unique (one corpus lookup matched 131
     * rows), so it can never identify a section unambiguously. The tool now says what it needs.
     */
    @Test
    public void testGetSectionWithoutAnIdIsRejected() {
        String output = getSectionTool.getSection(null, null, null);

        assertEquals("Error: Either 'document_id' or 'chunk_id' must be provided.", output);
    }

    /**
     * A section read now counts as having seen its passages, in BOTH directions.
     *
     * <p>
     * Reading a section and then searching used to hand the model the same passage twice at full
     * length, and the agentic loop re-sends its whole transcript every turn, so the duplicate is
     * billed once per remaining turn rather than once. The ledger lives on the conversation, so
     * "already shown above" is only ever said about text that really is earlier in this
     * conversation.
     */
    @Test
    public void aSectionReadMarksItsPassagesSoALaterSearchDoesNotRepeatThem() {
        AgenticChatContext ctx = new AgenticChatContext();
        when(sectionService.getSectionChunksByDocumentId(anyString(), any()))
            .thenReturn(section(new SectionChunk(CHUNK_A, DOC, "Prerequisites", "The body.")));

        getSectionTool.getSection(DOC.toString(), "Ch1 > SecA", null, ctx);

        assertThat(ctx.firstSighting(CHUNK_A))
            .as("the section read must have recorded this passage, or a later search repeats it")
            .isFalse();
    }

    /** The converse: a passage a search already showed is not printed again by a section read. */
    @Test
    public void aPassageAlreadyShownIsRenderedAsAReferenceNotRepeatedInFull() {
        AgenticChatContext ctx = new AgenticChatContext();
        ctx.firstSighting(CHUNK_A); // as if a search had already rendered it
        when(sectionService.getSectionChunksByDocumentId(anyString(), any()))
            .thenReturn(section(new SectionChunk(CHUNK_A, DOC, "Prerequisites", "The body."),
                new SectionChunk(CHUNK_B, DOC, "Next steps", "Fresh body.")));

        String output = getSectionTool.getSection(DOC.toString(), "Ch1 > SecA", null, ctx);

        assertThat(output).as("the repeat keeps its id and loses only its text")
            .contains("_(id=" + CHUNK_A + "  doc_id=" + DOC + ")_").doesNotContain("The body.");
        assertThat(output).as("a passage not yet shown must still arrive in full")
            .contains("Fresh body.");
    }

    /**
     * An external MCP client has no conversation for "already shown above" to refer to, so it must
     * never be told text is earlier in a transcript that does not exist.
     */
    @Test
    public void anExternalCallerWithNoConversationAlwaysGetsTheFullText() {
        when(sectionService.getSectionChunksByDocumentId(anyString(), any()))
            .thenReturn(section(new SectionChunk(CHUNK_A, DOC, "Prerequisites", "The body.")));

        String first = getSectionTool.getSection(DOC.toString(), "Ch1 > SecA", null);
        String second = getSectionTool.getSection(DOC.toString(), "Ch1 > SecA", null);

        assertThat(first).contains("The body.");
        assertThat(second).as("no conversation means no ledger, however many times it is called")
            .contains("The body.");
    }
}
