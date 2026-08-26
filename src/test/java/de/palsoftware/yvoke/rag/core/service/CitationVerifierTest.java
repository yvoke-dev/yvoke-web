package de.palsoftware.yvoke.rag.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import de.palsoftware.yvoke.document.core.repository.ChunkRepository;
import de.palsoftware.yvoke.document.core.repository.DocumentRepository;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class CitationVerifierTest {

    private static final UUID REAL_CHUNK_ID =
        UUID.fromString("8f7c1a2b-3c4d-5e6f-7a8b-9c0d1e2f3a4b");
    private static final UUID REAL_DOCUMENT_ID =
        UUID.fromString("9a8b7c6d-1a2b-3c4d-5e6f-7a8b9c0d1e2f");

    private ChunkRepository chunkRepository;
    private DocumentRepository documentRepository;
    private CitationVerifier verifier;

    @BeforeEach
    public void setUp() {
        chunkRepository = mock(ChunkRepository.class);
        documentRepository = mock(DocumentRepository.class);
        // The repositories report only the REAL_* ids as existing.
        when(chunkRepository.findExistingIds(anyCollection()))
            .thenAnswer(inv -> retainExisting(inv.getArgument(0), REAL_CHUNK_ID));
        when(documentRepository.findExistingIds(anyCollection()))
            .thenAnswer(inv -> retainExisting(inv.getArgument(0), REAL_DOCUMENT_ID));
        verifier = new CitationVerifier(chunkRepository, documentRepository);
    }

    private static Set<UUID> retainExisting(Collection<UUID> requested, UUID existing) {
        Set<UUID> result = new HashSet<>(requested);
        result.retainAll(Set.of(existing));
        return result;
    }

    @Test
    public void testParseCitation() {
        CitationVerifier.ParsedCitation parsedChunk = verifier.parseCitation("[chunk_id=8f7c1a2b]");
        assertThat(parsedChunk.kind).isEqualTo("chunk");
        assertThat(parsedChunk.value).isEqualTo("8f7c1a2b");

        CitationVerifier.ParsedCitation parsedDoc =
            verifier.parseCitation("[document_id=9a8b7c6d]");
        assertThat(parsedDoc.kind).isEqualTo("document");
        assertThat(parsedDoc.value).isEqualTo("9a8b7c6d");

        CitationVerifier.ParsedCitation parsedFile = verifier.parseCitation("file=ADSAccount.md");
        assertThat(parsedFile.kind).isEqualTo("unknown");

        // A short hex fragment is NOT a chunk id — too many English words are valid hex. Only a
        // full uuid (hyphenated or not) resolves to kind "id" — a bare uuid does not state its
        // table, so it is resolved against BOTH; see testBareHexProseIsNotTreatedAsAChunkCitation.
        CitationVerifier.ParsedCitation parsedHex = verifier.parseCitation("8f7c1a2b");
        assertThat(parsedHex.kind).isEqualTo("unknown");

        CitationVerifier.ParsedCitation parsedFullHex =
            verifier.parseCitation(REAL_CHUNK_ID.toString().replace("-", ""));
        assertThat(parsedFullHex.kind).isEqualTo("id");
    }

    @Test
    public void testCheckCitations() {
        List<CitationVerifier.CitationCheckResult> results =
            verifier.checkCitations(List.of("[chunk_id=8f7c1a2b-3c4d-5e6f-7a8b-9c0d1e2f3a4b]",
                "[chunk_id=00000000-0000-0000-0000-000000000000]",
                "[document_id=9a8b7c6d-1a2b-3c4d-5e6f-7a8b9c0d1e2f]",
                "[document_id=11111111-1111-1111-1111-111111111111]", "file=ADSAccount.md"));

        assertThat(results).hasSize(5);
        assertThat(results.get(0).getStatus()).isEqualTo(CitationVerifier.CitationStatus.REAL);
        assertThat(results.get(1).getStatus())
            .isEqualTo(CitationVerifier.CitationStatus.FABRICATED);
        assertThat(results.get(2).getStatus()).isEqualTo(CitationVerifier.CitationStatus.REAL);
        assertThat(results.get(3).getStatus())
            .isEqualTo(CitationVerifier.CitationStatus.FABRICATED);
        assertThat(results.get(4).getStatus())
            .isEqualTo(CitationVerifier.CitationStatus.FABRICATED);
    }

    @Test
    public void testCleanFabricatedCitations() {
        String text =
            "This is a statement [chunk_id=8f7c1a2b-3c4d-5e6f-7a8b-9c0d1e2f3a4b] and a fake citation [chunk_id=00000000-0000-0000-0000-000000000000] and a valid doc [document_id=9a8b7c6d-1a2b-3c4d-5e6f-7a8b9c0d1e2f] and a fake doc [document_id=11111111-1111-1111-1111-111111111111] and normal brackets [Optional].";
        String cleaned = verifier.cleanFabricatedCitations(text);

        assertThat(cleaned).contains("[chunk_id=8f7c1a2b-3c4d-5e6f-7a8b-9c0d1e2f3a4b]");
        assertThat(cleaned).doesNotContain("[chunk_id=00000000-0000-0000-0000-000000000000]");
        assertThat(cleaned).contains("[document_id=9a8b7c6d-1a2b-3c4d-5e6f-7a8b9c0d1e2f]");
        assertThat(cleaned).doesNotContain("[document_id=11111111-1111-1111-1111-111111111111]");
        assertThat(cleaned).contains("[Optional]");
    }

    @Test
    public void testParseCitationNumref() {
        CitationVerifier.ParsedCitation ref1 = verifier.parseCitation("[1]");
        assertThat(ref1.kind).isEqualTo("numref");
        assertThat(ref1.value).isEqualTo("1");

        CitationVerifier.ParsedCitation ref42 = verifier.parseCitation("[42]");
        assertThat(ref42.kind).isEqualTo("numref");
        assertThat(ref42.value).isEqualTo("42");

        // 3+ digits should NOT be treated as numref
        CitationVerifier.ParsedCitation ref100 = verifier.parseCitation("[100]");
        assertThat(ref100.kind).isNotEqualTo("numref");
    }

    @Test
    public void testNumrefNotFabricated() {
        assertThat(verifier.isFabricated("[1]")).isFalse();
        assertThat(verifier.isFabricated("[42]")).isFalse();
        assertThat(verifier.isFabricated("[99]")).isFalse();
        // 3+ digits are not numrefs, but they are ordinary bracket text and must survive too.
        assertThat(verifier.isFabricated("[100]")).isFalse();
    }

    @Test
    public void testUnrecognizedBracketTextIsNotFabricated() {
        // Bracket content that is not a citation at all is prose, not a fabrication. Anything that
        // returns true here is deleted from the user's answer mid-stream by
        // CitationStreamingFilter — markdown link labels and mermaid node names included.
        assertThat(verifier.isFabricated("[Optional]")).isFalse();
        assertThat(verifier.isFabricated("[world]")).isFalse();
        assertThat(verifier.isFabricated("[Start]")).isFalse();
        assertThat(verifier.isFabricated("[OIM docs]")).isFalse();
        assertThat(verifier.isFabricated("[Decision]")).isFalse();
        // [file=…] is NOT a citation — a citation is a chunk id or a document id, and nothing
        // else. checkCitations() therefore reports it FABRICATED, which is correct and is the
        // anti-hallucination check doing its job. isFabricated() still leaves it alone, because
        // that verdict DELETES text from the live stream and deletion must stay conservative:
        // "[file=x]" in an answer is bracketed prose, not a provably bad id.
        assertThat(verifier.isFabricated("[file=ADSAccount.md]")).isFalse();
    }

    @Test
    public void testBareHexProseIsNotTreatedAsAChunkCitation() {
        // "^[0-9a-fA-F]{6,32}$" matches ordinary English words built from hex letters, and any
        // 6-digit number. Classifying those as chunk ids made isFabricated() delete them from the
        // stream: "Error code [500123] occurred." reached the user as "Error code occurred."
        // Only the two forms a real id actually takes — 36-char uuid, or 32 hex chars — may resolve
        // to kind "id"; this also matches what thread.js:930 linkifies on the client.
        for (String prose : List.of("[facade]", "[Facade]", "[decade]", "[accede]", "[deadbeef]",
            "[cafebabe]", "[500123]", "[202401]", "[8f7c1a2b]")) {
            assertThat(verifier.parseCitation(prose).kind).as(prose).isEqualTo("unknown");
            assertThat(verifier.isFabricated(prose)).as(prose).isFalse();
        }

        // The full forms still classify as ids, hyphenated or not.
        assertThat(verifier.parseCitation("[" + REAL_CHUNK_ID + "]").kind).isEqualTo("id");
        assertThat(
            verifier.parseCitation("[" + REAL_CHUNK_ID.toString().replace("-", "") + "]").kind)
            .isEqualTo("id");
        assertThat(verifier.isFabricated("[" + REAL_CHUNK_ID + "]")).isFalse();
    }

    @Test
    public void testCheckCitationsDoesNotCondemnNumberedReferences() {
        // verify_citations tells the MAS reviewer to "remove or correct every FABRICATED citation".
        // [1]/[2] are the format every playbook and the default-chat prompt mandate, so reporting
        // them as fabricated drives the reviewer to strip correct citations.
        List<CitationVerifier.CitationCheckResult> results =
            verifier.checkCitations(List.of("[1]", "[42]"));

        assertThat(results).hasSize(2);
        assertThat(results).allSatisfy(r -> assertThat(r.getStatus())
            .isNotEqualTo(CitationVerifier.CitationStatus.FABRICATED));
    }

    @Test
    public void testGenuineFabricationsAreStillDetected() {
        // The narrowing above must not weaken the actual anti-hallucination check.
        assertThat(verifier.isFabricated("[chunk_id=00000000-0000-0000-0000-000000000000]"))
            .isTrue();
        assertThat(verifier.isFabricated("[document_id=11111111-1111-1111-1111-111111111111]"))
            .isTrue();
        assertThat(verifier.isFabricated("[chunk_id=not-a-uuid]")).isTrue();
        assertThat(verifier.isFabricated("[document_id=nonsense]")).isTrue();
        // …and real ids still pass.
        assertThat(verifier.isFabricated("[chunk_id=" + REAL_CHUNK_ID + "]")).isFalse();
        assertThat(verifier.isFabricated("[document_id=" + REAL_DOCUMENT_ID + "]")).isFalse();
    }

    @Test
    public void testTruncatedIdCitationIsNotDeletedFromTheStream() {
        // The UI resolves a chunk/document citation by ID PREFIX (SectionService ->
        // ChunkRepository.findByIdPrefix, >= 8 hex chars) and citation-render.js links exactly this
        // form — it is also the form this tool's own parameter description used to advertise.
        // Deleting it mid-stream removed a citation the user could have expanded. Deletion is only
        // for a provably bad id, and a truncated id is not provably bad: it is unchecked.
        assertThat(verifier.isFabricated("[chunk_id=8f7c1a2b]")).isFalse();
        assertThat(verifier.isFabricated("[document_id=9a8b7c6d]")).isFalse();
        assertThat(verifier.isFabricated("[chunk_id=8f7c1a2b-3c4d]")).isFalse();
        // Too short to resolve even by prefix (findByIdPrefix requires 8), so still a bad id.
        assertThat(verifier.isFabricated("[chunk_id=abc]")).isTrue();
    }

    @Test
    public void testCheckCitationsReportsTruncatedIdAsUnverifiedNotFabricated() {
        // verify_citations condemned the truncated form as FABRICATED, which tells the reviewer to
        // strip a citation that is very probably real. It cannot confirm it either — the existence
        // query is by full id — so it must say "not checked", not "made up".
        List<CitationVerifier.CitationCheckResult> results =
            verifier.checkCitations(List.of("[chunk_id=8f7c1a2b]", "[document_id=9a8b7c6d]"));

        assertThat(results).hasSize(2);
        assertThat(results).allSatisfy(r -> {
            assertThat(r.getStatus()).isEqualTo(CitationVerifier.CitationStatus.UNVERIFIED);
            assertThat(r.getDetail()).contains("full");
        });
    }

    @Test
    public void testStreamDeletionAgreesWithTextCleaning() {
        // The class used to carry two classifiers with different defaults; the streaming one
        // (destructive) disagreed with the text one on every bracketed word. Both now derive from
        // one classifier — this pins that they cannot drift apart again.
        List<String> samples = List.of("[Optional]", "[world]", "[100]", "[1]", "[facade]",
            "[file=ADSAccount.md]", "[chunk_id=8f7c1a2b]", "[chunk_id=abc]",
            "[chunk_id=" + REAL_CHUNK_ID + "]", "[chunk_id=00000000-0000-0000-0000-000000000000]",
            "[document_id=" + REAL_DOCUMENT_ID + "]",
            "[document_id=11111111-1111-1111-1111-111111111111]");

        for (String sample : samples) {
            boolean strippedByCleaner =
                !verifier.cleanFabricatedCitations("before " + sample + " after").contains(sample);
            assertThat(verifier.isFabricated(sample)).as(sample).isEqualTo(strippedByCleaner);
        }
    }

    @Test
    public void testCleanFabricatedCitationsPreservesNumrefs() {
        String text = "First claim [1] and second claim [2]. "
            + "## References\n[1] [chunk_id=8f7c1a2b-3c4d-5e6f-7a8b-9c0d1e2f3a4b]";
        String cleaned = verifier.cleanFabricatedCitations(text);

        assertThat(cleaned).contains("[1]");
        assertThat(cleaned).contains("[2]");
        assertThat(cleaned).contains("[chunk_id=8f7c1a2b-3c4d-5e6f-7a8b-9c0d1e2f3a4b]");
    }

    /**
     * A bare uuid does not say which table it belongs to. Classifying it as a chunk id meant a
     * DOCUMENT id resolved against `chunks` only, found nothing, and was deleted from the live SSE
     * stream — real text removed from the user's answer on a guess about the id's table.
     */
    @Test
    void testBareUuidNamingADocumentIsRealAndSurvivesTheStream() {
        String cited = "[" + REAL_DOCUMENT_ID + "]";

        assertThat(verifier.checkCitations(List.of(cited)).get(0).getStatus())
            .isEqualTo(CitationVerifier.CitationStatus.REAL);
        assertThat(verifier.isFabricated(cited)).isFalse();
    }

    @Test
    void testBareUuidNamingAChunkIsStillReal() {
        String cited = "[" + REAL_CHUNK_ID + "]";

        assertThat(verifier.checkCitations(List.of(cited)).get(0).getStatus())
            .isEqualTo(CitationVerifier.CitationStatus.REAL);
        assertThat(verifier.isFabricated(cited)).isFalse();
    }

    /** A uuid in neither table is still invented, and still stripped. */
    @Test
    void testBareUuidInNeitherTableRemainsFabricated() {
        String cited = "[" + UUID.randomUUID() + "]";

        assertThat(verifier.checkCitations(List.of(cited)).get(0).getStatus())
            .isEqualTo(CitationVerifier.CitationStatus.FABRICATED);
        assertThat(verifier.isFabricated(cited)).isTrue();
    }

    // -------------------------------------------------------------------------------------------
    // citedIds — which sources an answer actually leans on. Used to decide what full text the
    // reviewer receives, so a false negative here is not cosmetic: it manifests away the very
    // evidence a claim rests on, and the reviewer then rejects a correctly grounded answer.
    // -------------------------------------------------------------------------------------------

    /**
     * The single most dangerous way to get this wrong is to compare whole uuids. Drafts routinely
     * cite a TRUNCATED id — it is what {@code findByIdPrefix} resolves, what {@code
     * citation-render.js} links, and what this class already refuses to call fabricated. Matching
     * on equality would classify every such citation as uncited, strip the evidence for all of
     * them, and leave the reviewer rejecting an answer whose sources were right there.
     */
    @Test
    void citedIdsCoversATruncatedCitationOfAFullId() {
        CitationVerifier.CitedIds cited =
            CitationVerifier.citedIds("As described in [chunk_id=8f7c1a2b].");

        assertThat(cited.covers(REAL_CHUNK_ID))
            .as("8f7c1a2b is a prefix of " + REAL_CHUNK_ID + " and resolves to it everywhere else")
            .isTrue();
        assertThat(cited.isEmpty()).isFalse();
    }

    @Test
    void citedIdsAcceptsTheFullPrefixedBareAndHyphenlessForms() {
        String answer = "One [chunk_id=" + REAL_CHUNK_ID + "], two [" + REAL_DOCUMENT_ID + "], "
            + "three [document_id=" + REAL_DOCUMENT_ID.toString().replace("-", "") + "].";

        CitationVerifier.CitedIds cited = CitationVerifier.citedIds(answer);

        assertThat(cited.covers(REAL_CHUNK_ID)).isTrue();
        assertThat(cited.covers(REAL_DOCUMENT_ID)).isTrue();
    }

    /**
     * Playbooks mandate numbered references, so most of a draft's brackets are {@code [1]}, {@code
     * [2]} — and a two-digit number is not a source. The real ids live in the {@code ## References}
     * section, which is why the scan must cover the WHOLE answer rather than the prose above it.
     */
    @Test
    void citedIdsIgnoresNumberedRefsAndOrdinaryBracketedProse() {
        CitationVerifier.CitedIds cited = CitationVerifier
            .citedIds("Per [1] and [2], see [the manual] and [facade] and [500123].");

        assertThat(cited.isEmpty())
            .as("none of these names a corpus row — treating any of them as one would scope "
                + "the reviewer's evidence to a source the answer never cited")
            .isTrue();
        assertThat(cited.covers(REAL_CHUNK_ID)).isFalse();
    }

    @Test
    void citedIdsFindsIdsInAReferencesSectionBelowTheProse() {
        String answer = "The Person table holds identities [1].\n\n## References\n"
            + "[1] [chunk_id=" + REAL_CHUNK_ID + "]\n";

        assertThat(CitationVerifier.citedIds(answer).covers(REAL_CHUNK_ID)).isTrue();
    }

    @Test
    void anEmptyCitedSetCoversNothingRatherThanEverything() {
        CitationVerifier.CitedIds cited = CitationVerifier.citedIds("No citations at all.");

        assertThat(cited.isEmpty()).isTrue();
        assertThat(cited.covers(REAL_CHUNK_ID)).isFalse();
        assertThat(cited.covers(null)).isFalse();
    }

    @Test
    void testGroupedBareUuidsAreParsedAndVerified() {
        String group = "[" + REAL_CHUNK_ID + ", " + REAL_DOCUMENT_ID + "]";

        List<CitationVerifier.CitationCheckResult> results =
            verifier.checkCitations(List.of(group));
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getStatus()).isEqualTo(CitationVerifier.CitationStatus.REAL);

        assertThat(verifier.isFabricated(group)).isFalse();

        String cleaned = verifier.cleanFabricatedCitations("Statement " + group + ".");
        assertThat(cleaned).contains(group);
    }

    @Test
    void testGroupedWithFabricatedUuid() {
        UUID fake1 = UUID.randomUUID();
        UUID fake2 = UUID.randomUUID();
        String mixed = "[" + REAL_CHUNK_ID + ", " + fake1 + "]";
        String allFake = "[" + fake1 + ", " + fake2 + "]";

        // Mixed: classify flags fabricated, but isFabricated is false to avoid deleting real
        // evidence
        assertThat(verifier.checkCitations(List.of(mixed)).get(0).getStatus())
            .isEqualTo(CitationVerifier.CitationStatus.FABRICATED);
        assertThat(verifier.isFabricated(mixed)).isFalse();

        // All fake: both are provably bad IDs, so isFabricated is true and it gets stripped
        assertThat(verifier.checkCitations(List.of(allFake)).get(0).getStatus())
            .isEqualTo(CitationVerifier.CitationStatus.FABRICATED);
        assertThat(verifier.isFabricated(allFake)).isTrue();
        assertThat(verifier.cleanFabricatedCitations("Text " + allFake + ".")).isEqualTo("Text .");
    }

    @Test
    void citedIdsExtractsMultipleIdsFromGroupedBracket() {
        String answer = "Claims supported by [" + REAL_CHUNK_ID + ", " + REAL_DOCUMENT_ID + "].";

        CitationVerifier.CitedIds cited = CitationVerifier.citedIds(answer);

        assertThat(cited.covers(REAL_CHUNK_ID)).isTrue();
        assertThat(cited.covers(REAL_DOCUMENT_ID)).isTrue();
    }
}
