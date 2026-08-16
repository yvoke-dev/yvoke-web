package de.palsoftware.yvoke.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import de.palsoftware.yvoke.rag.core.model.AgenticChatContext;
import de.palsoftware.yvoke.rag.core.model.SeenChunks;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link ChunkBlocks} is the only place the {@code search_corpus} result layout is written, and now
 * also the only place it is read back. Those two halves have to agree exactly, and nothing but a
 * test can notice when they stop agreeing — the format has no schema, no version and, until this
 * class existed, no consumer at all outside the model reading it as prose.
 *
 * <p>
 * The round-trip assertion is the load-bearing one: {@code preamble + every block's raw + suffix}
 * must reconstruct the input byte for byte. Anything the parser silently drops — a separator line,
 * a trailing notice, the last newline — is evidence that disappears from the reviewer's prompt, and
 * a looser assertion (fields recovered, sizes plausible) would not see it.
 */
public class ChunkBlocksTest {

    private static final UUID CHUNK_A = UUID.fromString("8f7c1a2b-3d4e-4f50-8a1b-2c3d4e5f6071");
    private static final UUID DOC_A = UUID.fromString("11111111-2222-4333-8444-555555555555");
    private static final UUID CHUNK_B = UUID.fromString("2c3d4e5f-1122-4333-8444-666666666666");
    private static final UUID DOC_B = UUID.fromString("99999999-8888-4777-8666-555555555555");

    private static HybridSearchResult chunk(UUID id, UUID docId, String body, List<String> heading,
        String title, String kind, double score) {
        return new HybridSearchResult(id, docId, body, heading,
            heading.isEmpty() ? null : heading.get(heading.size() - 1), 2, 0, "10.0", title, kind,
            "OIM", Map.of(), score, new TelemetryInfo(true, false, 1, 0, 1));
    }

    /** Parse-side tests render with no conversation, so nothing is ever elided under them. */
    private static String render(List<HybridSearchResult> chunks) {
        return ChunkBlocks.format(chunks, SeenChunks.NONE);
    }

    private static HybridSearchResult chunkA(String body) {
        return chunk(CHUNK_A, DOC_A, body, List.of("Install", "Prerequisites"), "install-kit.md",
            "manual", 0.87);
    }

    // ---------------------------------------------------------------------------------------
    // Repeat suppression. The saving is real — a body averages ~3,000 chars against a ~240-char
    // reference — but the reason it is safe is narrower than it looks: the marker asserts the text
    // is "earlier in this conversation", which is only true while the ledger's lifetime is a subset
    // of the message list's. Every test below defends some part of that claim.
    // ---------------------------------------------------------------------------------------

    @Test
    public void aRepeatedChunkKeepsItsRankedSlotAndHeaderButLosesItsBody() {
        AgenticChatContext conversation = new AgenticChatContext();
        String body = "Install the kit first.";

        String first = ChunkBlocks.format(List.of(chunkA(body)), conversation);
        String second = ChunkBlocks.format(List.of(chunkA(body)), conversation);

        assertThat(first).contains(body);
        assertThat(second)
            .as("the slot, the score and both ids must survive — they are how the model cites it")
            .contains("### manual/install-kit.md  (score=0.870  id=" + CHUNK_A + "  doc_id=" + DOC_A
                + ")")
            .contains("> Install > Prerequisites").contains("already shown above");
        assertThat(second).as("the body is the whole saving").doesNotContain(body);
    }

    @Test
    public void theSameChunkTwiceInOneResultSetIsRenderedInFullOnlyOnce() {
        // Two lanes can surface one chunk, and the ledger is consulted per rendered slot rather
        // than per call, so this needs no special case — but only if the check and the recording
        // are the same step. Snapshotting the batch's ids up front would elide both copies.
        AgenticChatContext conversation = new AgenticChatContext();
        String body = "Install the kit first.";

        String out = ChunkBlocks.format(List.of(chunkA(body), chunkA(body)), conversation);

        assertThat(countOf(out, "id=" + CHUNK_A)).as("both ranked slots are kept").isEqualTo(2);
        assertThat(countOf(out, body)).as("the body travels once").isEqualTo(1);
        assertThat(countOf(out, "already shown above")).isEqualTo(1);
    }

    @Test
    public void aChunkWithNoIdIsAlwaysRenderedInFull() {
        // A null id cannot identify anything, so it can never be "the same chunk again". Recording
        // it would make every id-less chunk collapse into the first one, silently.
        AgenticChatContext conversation = new AgenticChatContext();

        String out =
            ChunkBlocks.format(List.of(chunk(null, null, "First body.", List.of(), "?", "?", 0.1),
                chunk(null, null, "Second body.", List.of(), "?", "?", 0.1)), conversation);

        assertThat(out).contains("First body.").contains("Second body.");
        assertThat(out).doesNotContain("already shown above");
    }

    @Test
    public void theLedgerNeverSpansTwoConversations() {
        // Each specialist runs in its own AgenticChatContext with its own message list. A chunk one
        // specialist read is NOT earlier in another's conversation, so pointing there would be a
        // lie — and the reviewer, which cannot search, would be left holding the pointer.
        String body = "Install the kit first.";

        String inOne = ChunkBlocks.format(List.of(chunkA(body)), new AgenticChatContext());
        String inAnother = ChunkBlocks.format(List.of(chunkA(body)), new AgenticChatContext());

        assertThat(inOne).contains(body);
        assertThat(inAnother).as("a fresh conversation has seen nothing").contains(body);
        assertThat(inAnother).doesNotContain("already shown above");
    }

    @Test
    public void withoutAConversationEveryHitIsRenderedInFull() {
        // External MCP clients hold their own history and we cannot see it, so there is nothing to
        // point "above" at. NONE must be stateless: a shared mutable no-op would be one ledger
        // spanning every caller on the server.
        String body = "Install the kit first.";

        String first = ChunkBlocks.format(List.of(chunkA(body)), SeenChunks.NONE);
        String second = ChunkBlocks.format(List.of(chunkA(body)), SeenChunks.NONE);

        assertThat(first).contains(body);
        assertThat(second).contains(body);
        assertThat(second).doesNotContain("already shown above");
    }

    @Test
    public void aMarkedRepeatIsRecognisableWhenTheRenderIsReadBack() {
        // EvidenceDigest has to tell a reference apart from a body to avoid re-pointing at a body
        // it has itself removed, so the marker must survive the round trip as a flag, not prose.
        AgenticChatContext conversation = new AgenticChatContext();
        ChunkBlocks.format(List.of(chunkA("Install the kit first.")), conversation);

        String repeat = ChunkBlocks.format(List.of(chunkA("Install the kit first.")), conversation);
        ChunkBlocks.Block block = ChunkBlocks.parse(repeat).blocks().get(0);

        assertThat(block.isShownAboveMarker()).isTrue();
        assertThat(block.chunkId()).isEqualTo(CHUNK_A);
    }

    private static int countOf(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }

    @Test
    public void parseRecoversEveryFieldThatFormatWrites() {
        String rendered = render(List.of(
            chunk(CHUNK_A, DOC_A, "Install the kit first.", List.of("Install", "Prerequisites"),
                "install-kit.md", "manual", 0.87),
            chunk(CHUNK_B, DOC_B, "The Person table holds identities.", List.of("Columns"),
                "Person", "table", 0.812)));

        ChunkBlocks.Parsed parsed = ChunkBlocks.parse(rendered);

        assertThat(parsed.blocks()).hasSize(2);

        ChunkBlocks.Block first = parsed.blocks().get(0);
        assertThat(first.chunkId()).isEqualTo(CHUNK_A);
        assertThat(first.documentId()).isEqualTo(DOC_A);
        assertThat(first.kind()).isEqualTo("manual");
        assertThat(first.title()).isEqualTo("install-kit.md");
        assertThat(first.headingPath()).isEqualTo("Install > Prerequisites");
        assertThat(first.body()).isEqualTo("Install the kit first.");

        ChunkBlocks.Block second = parsed.blocks().get(1);
        assertThat(second.chunkId()).isEqualTo(CHUNK_B);
        assertThat(second.kind()).isEqualTo("table");
        assertThat(second.title()).isEqualTo("Person");
        assertThat(second.body()).isEqualTo("The Person table holds identities.");

        String rebuilt = parsed.preamble()
            + String.join("", parsed.blocks().stream().map(ChunkBlocks.Block::raw).toList())
            + parsed.suffix();
        assertThat(rebuilt).as(
            "parse must lose nothing — anything dropped here vanishes from the reviewer's prompt")
            .isEqualTo(rendered);
    }

    @Test
    public void aChunkBodyContainingItsOwnMarkdownHeadingsIsNotSplit() {
        // Chunk bodies are ingested markdown. A corpus full of manuals routinely puts "### Foo"
        // inside a chunk, so a parser that keys on the "### " line prefix shreds one chunk into
        // several — each fragment then carrying the wrong id, or no id at all.
        String body = "Before.\n\n### Configuration\n\nSet the flag.\n\n### Person\n\nA table.";
        String rendered = render(
            List.of(chunk(CHUNK_A, DOC_A, body, List.of("Reference"), "ref.md", "manual", 0.5)));

        ChunkBlocks.Parsed parsed = ChunkBlocks.parse(rendered);

        assertThat(parsed.blocks()).hasSize(1);
        assertThat(parsed.blocks().get(0).body()).isEqualTo(body);
    }

    @Test
    public void textWithNoChunkHeaderIsAllPreambleAndNoBlocks() {
        // The load-bearing rule for evidence handling: anything that is not chunk text — a
        // query_json_objects table, a get_section body, a tool error — must be recognisable as
        // "nothing to reduce here" so it can be passed through untouched.
        String notChunks = "query_json_objects rows: [from 9.2.2 to 9.3]\n| a | b |\n| --- | --- |";

        ChunkBlocks.Parsed parsed = ChunkBlocks.parse(notChunks);

        assertThat(parsed.blocks()).isEmpty();
        assertThat(parsed.preamble()).isEqualTo(notChunks);
        assertThat(parsed.suffix()).isEmpty();
    }

    @Test
    public void aChunkWithNoIdStillParsesAndKeepsItsText() {
        // format() writes a bare "id=" for a null id, so the parser must tolerate an empty group
        // rather than handing "" to UUID.fromString.
        String rendered =
            render(List.of(chunk(null, null, "Body with no ids.", List.of(), "?", "?", 0.1)));

        ChunkBlocks.Parsed parsed = ChunkBlocks.parse(rendered);

        assertThat(parsed.blocks()).hasSize(1);
        assertThat(parsed.blocks().get(0).chunkId()).isNull();
        assertThat(parsed.blocks().get(0).documentId()).isNull();
        assertThat(parsed.blocks().get(0).body()).isEqualTo("Body with no ids.");
    }

    @Test
    public void aTrailingNoticeIsKeptAsSuffixRatherThanSwallowedIntoTheLastBody() {
        // search_corpus appends its "the result is capped" notice after the last body. If the
        // parser treats that as body text it rides along with the last chunk — and disappears
        // entirely when that chunk is the one being reduced to a reference, taking with it the
        // only signal that the specialist may not have seen everything.
        String notice = "\n\n_(showing 2 chunks — the result is capped and more may match.)_";
        String rendered =
            render(List.of(chunk(CHUNK_A, DOC_A, "First.", List.of("A"), "a.md", "manual", 0.9),
                chunk(CHUNK_B, DOC_B, "Second.", List.of("B"), "b.md", "manual", 0.8))) + notice;

        ChunkBlocks.Parsed parsed = ChunkBlocks.parse(rendered);

        assertThat(parsed.blocks()).hasSize(2);
        assertThat(parsed.blocks().get(1).body()).isEqualTo("Second.");
        assertThat(parsed.suffix()).isEqualTo(notice);
    }

    @Test
    public void withBodyReproducesTheBlockWhenHandedItsOwnBody() {
        // The re-emission path must be the exact inverse of the parse path, or a block that is
        // kept in full still comes out subtly different from what the search produced.
        String rendered = render(List.of(chunk(CHUNK_A, DOC_A, "Kept whole.",
            List.of("Install", "Prerequisites"), "install-kit.md", "manual", 0.87)));

        ChunkBlocks.Block block = ChunkBlocks.parse(rendered).blocks().get(0);

        assertThat(block.withBody(block.body())).isEqualTo(block.raw().strip());
    }

    @Test
    public void aManifestLineNamesTheChunkWithoutQuotingIt() {
        String rendered =
            render(List.of(chunk(CHUNK_A, DOC_A, "Some long body that must not appear.",
                List.of("Install", "Prerequisites"), "install-kit.md", "manual", 0.87)));

        String line = ChunkBlocks.parse(rendered).blocks().get(0).manifestLine();

        assertThat(line).contains("chunk_id=" + CHUNK_A);
        assertThat(line).contains("manual/install-kit.md");
        assertThat(line).contains("Install > Prerequisites");
        assertThat(line).doesNotContain("Some long body");
    }

    /**
     * {@code get_section} marks each passage with {@code _(id=…  doc_id=…)_} rather than the search
     * header, because a section read has no relevance score and {@code HEADER} requires one.
     * {@code parse} has to recognise BOTH, or a section reaches the reviewer unreduced: every
     * passage of it, cited or not, at full length.
     *
     * <p>
     * The marker carries {@code doc_id} as well as {@code id} so an answer that still cites a
     * section by its document — which 25 playbooks currently instruct — keeps its passages while
     * those playbooks are being updated.
     */
    @Test
    void aSectionMarkerIsParsedAsABlockJustLikeASearchHeader() {
        String rendered = """
            # Section: Installing > Prerequisites
            _(document: OIM Admin Guide  ·  tag: 10.0  ·  2 passage(s))_

            _(id=8f7c1a2b-3d4e-4f50-8a1b-2c3d4e5f6071  doc_id=3d9a0000-1111-4222-8333-444455556666)_
            ###### Prerequisites
            First body.

            _(id=1a2b3c4d-5e6f-4071-8899-aabbccddeeff  doc_id=3d9a0000-1111-4222-8333-444455556666)_
            ###### Next steps
            Second body.
            """;

        ChunkBlocks.Parsed parsed = ChunkBlocks.parse(rendered);

        assertThat(parsed.blocks()).as("a section must reduce to one block per passage").hasSize(2);
        assertThat(parsed.blocks().get(0).chunkId())
            .isEqualTo(UUID.fromString("8f7c1a2b-3d4e-4f50-8a1b-2c3d4e5f6071"));
        assertThat(parsed.blocks().get(0).documentId())
            .as("without doc_id a document-level citation would drop every passage")
            .isEqualTo(UUID.fromString("3d9a0000-1111-4222-8333-444455556666"));
        assertThat(parsed.blocks().get(0).body()).contains("First body.");
        assertThat(parsed.preamble())
            .as("the section heading is preamble, not a block - it names what was read")
            .contains("# Section:");
    }

    /** The section marker must round-trip like any other block. */
    @Test
    void aSectionRenderSurvivesParseAndReEmission() {
        String rendered = """
            # Section: A
            _(document: D  ·  tag: 9.3)_

            _(id=8f7c1a2b-3d4e-4f50-8a1b-2c3d4e5f6071  doc_id=3d9a0000-1111-4222-8333-444455556666)_
            Body one.
            """;

        ChunkBlocks.Parsed parsed = ChunkBlocks.parse(rendered);
        String rebuilt = parsed.preamble()
            + String.join("\n\n", parsed.blocks().stream().map(ChunkBlocks.Block::raw).toList())
            + parsed.suffix();

        assertThat(rebuilt).isEqualTo(rendered);
    }
}
