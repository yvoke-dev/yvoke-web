package de.palsoftware.yvoke.chat.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import de.palsoftware.yvoke.rag.core.model.AgenticChatContext;
import de.palsoftware.yvoke.rag.core.model.SeenChunks;
import de.palsoftware.yvoke.rag.retrieval.ChunkBlocks;
import de.palsoftware.yvoke.rag.retrieval.HybridSearchResult;
import de.palsoftware.yvoke.rag.retrieval.TelemetryInfo;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The reviewer's prompt is the specialists' raw tool output concatenated — measured at 125,440
 * chars on average in production, where two such prompts outweighed all 31 specialist tool results
 * that produced them. {@link EvidenceDigest} is what reduces it to the sources the answer cites.
 *
 * <p>
 * Every failure here is silent and lands on an agent that cannot search. Strip a body the reviewer
 * needed and it reports a correct answer as unsupported; leave a reference pointing at a body that
 * was removed and it is reading an instruction to scroll up to nothing; strip the reviser's uncited
 * material and it delegates a fresh search for evidence it was already holding. So these tests
 * assert on what survives into the rendered text, never on which branch ran.
 */
public class EvidenceDigestTest {

    private static final UUID CITED = UUID.fromString("8f7c1a2b-3d4e-4f50-8a1b-2c3d4e5f6071");
    private static final UUID UNCITED = UUID.fromString("2c3d4e5f-1122-4333-8444-666666666666");
    private static final UUID DOC_CITED = UUID.fromString("11111111-2222-4333-8444-555555555555");
    private static final UUID DOC_UNCITED = UUID.fromString("99999999-8888-4777-8666-555555555555");

    private static final String CITED_BODY = "The Person table holds identities.";
    private static final String UNCITED_BODY = "Install the kit before configuring anything.";

    private static final String JSON_EVIDENCE =
        "[spec-a · query_json_objects]\nquery_json_objects rows: [from 9.2.2 to 9.3]";

    private static HybridSearchResult hit(UUID id, UUID docId, String body, String title) {
        return new HybridSearchResult(id, docId, body, List.of("Columns"), "Columns", 2, 0, "10.0",
            title, "table", "OIM", Map.of(), 0.9, new TelemetryInfo(true, false, 1, 0, 1));
    }

    /** An evidence entry exactly as {@code collectEvidence} builds it from a tool message. */
    private static String evidence(String specialist, SeenChunks ledger,
        List<HybridSearchResult> chunks) {
        return "[" + specialist + " · search_corpus]\n" + ChunkBlocks.format(chunks, ledger);
    }

    private static String citedBy(UUID... ids) {
        StringBuilder sb = new StringBuilder("The answer [1].\n\n## References\n");
        int n = 1;
        for (UUID id : ids) {
            sb.append("[").append(n++).append("] [chunk_id=").append(id).append("]\n");
        }
        return sb.toString();
    }

    private static int countOf(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }

    @Test
    public void aChunkBodyTravelsOnceHoweverManySpecialistsRetrievedIt() {
        // Two specialists, two independent conversations, so each rendered the chunk in full — the
        // cross-specialist duplication that only ever meets here, in one message, where collapsing
        // it is truthful.
        List<String> ev = List.of(
            evidence("spec-a", new AgenticChatContext(),
                List.of(hit(CITED, DOC_CITED, CITED_BODY, "Person"))),
            evidence("spec-b", new AgenticChatContext(),
                List.of(hit(CITED, DOC_CITED, CITED_BODY, "Person"))));

        String out = citeScoped(ev, citedBy(CITED));

        assertThat(countOf(out, "id=" + CITED)).as("both attributions survive").isEqualTo(2);
        assertThat(countOf(out, CITED_BODY)).as("the body travels once").isEqualTo(1);
        assertThat(out).contains("[spec-a · search_corpus]").contains("[spec-b · search_corpus]");
        assertThat(out).contains(ChunkBlocks.SHOWN_ABOVE);
    }

    /**
     * An uncited source leaves no trace at all — not its text, and not its name.
     *
     * <p>
     * Naming it would be worse than either extreme. A reviewer that can see
     * {@code manual/OIM Install Kit · Install > Prerequisites} but cannot read it is being invited
     * to approve a claim on the strength of a plausible-looking title, which is precisely the
     * fabrication it exists to catch. Withholding the name entirely keeps the question it answers
     * closed: does the cited source support the claim, yes or no.
     */
    @Test
    public void aSourceTheAnswerDoesNotCiteLeavesNoTraceAtAll() {
        List<String> ev = List.of(evidence("spec-a", new AgenticChatContext(),
            List.of(hit(CITED, DOC_CITED, CITED_BODY, "Person"),
                hit(UNCITED, DOC_UNCITED, UNCITED_BODY, "install-kit.md"))));

        String out = citeScoped(ev, citedBy(CITED));

        assertThat(out).contains(CITED_BODY);
        assertThat(out).as("not its text").doesNotContain(UNCITED_BODY);
        assertThat(out).as("and not its id, title or heading either — a name is not evidence")
            .doesNotContain(UNCITED.toString()).doesNotContain("install-kit.md");
        assertThat(out).doesNotContain("Retrieved but not cited");
    }

    /**
     * Drafts routinely cite a truncated id. Matching on whole uuids would classify every such
     * citation as uncited and strip the entire evidence base, leaving the reviewer rejecting an
     * answer whose sources were supplied.
     */
    @Test
    public void aTruncatedCitationStillCountsAsACitation() {
        List<String> ev = List.of(evidence("spec-a", new AgenticChatContext(),
            List.of(hit(CITED, DOC_CITED, CITED_BODY, "Person"))));

        String out = citeScoped(ev, "As described in [chunk_id=8f7c1a2b].");

        assertThat(out).contains(CITED_BODY);
    }

    /**
     * The pass-through rule, and it is structural rather than a list of tool names: an entry is
     * reduced only insofar as chunk headers are found in it. That is what protects
     * {@code query_json_objects} output — which the reviewer validates version history against and
     * which carries no chunk id at all, so it could never be cited and could never be recovered —
     * along with {@code get_section} bodies, graph output and tool error strings.
     */
    @Test
    public void evidenceThatIsNotChunkTextPassesThroughByteIdentical() {
        String toolError = "[spec-b · get_toc]\nERROR: the 'get_toc' tool failed to complete.";
        List<String> ev = List.of(JSON_EVIDENCE, toolError);

        String out = citeScoped(ev, citedBy(CITED));

        assertThat(out).contains(JSON_EVIDENCE).contains(toolError);
    }

    /**
     * The reviewer is asked to flag a {@code [document_id=…]} citation where a {@code chunk_id} was
     * available, which it cannot do unless the chunks of a cited document arrive as text.
     */
    @Test
    public void aChunkCitedOnlyByItsDocumentIdIsShownInFull() {
        List<String> ev = List.of(evidence("spec-a", new AgenticChatContext(),
            List.of(hit(CITED, DOC_CITED, CITED_BODY, "Person"))));

        String out = citeScoped(ev, "See [document_id=" + DOC_CITED + "].");

        assertThat(out).contains(CITED_BODY);
    }

    /**
     * Where the two halves of this change meet. Repeat suppression already replaced a second
     * sighting with a reference, so dropping the entry that held the full copy would leave the
     * reference pointing at text no longer anywhere in the prompt.
     */
    @Test
    public void aBodyElidedUpstreamIsNeverLeftAsADanglingReference() {
        AgenticChatContext oneSpecialist = new AgenticChatContext();
        List<String> ev = List.of(
            evidence("spec-a", oneSpecialist,
                List.of(hit(UNCITED, DOC_UNCITED, UNCITED_BODY, "k"))),
            evidence("spec-a", oneSpecialist,
                List.of(hit(UNCITED, DOC_UNCITED, UNCITED_BODY, "k"))));

        // Nothing here is cited, so both entries go.
        String out = citeScoped(ev, citedBy(CITED));

        assertThat(out).doesNotContain(UNCITED_BODY);
        assertThat(out).as("a reference whose body has been removed points at nothing")
            .doesNotContain(ChunkBlocks.SHOWN_ABOVE);
    }

    /**
     * The mirror of the above for a chunk the answer DOES cite, so it cannot be resolved by
     * dropping it as unneeded. The harvest should always keep a specialist's own full copy beside
     * its references; if it ever does not, the reference must go rather than dangle.
     */
    @Test
    public void aCitedReferenceWithNoBodyAnywhereIsDroppedRatherThanPointedAt() {
        AgenticChatContext ledger = new AgenticChatContext();
        // Burn the first sighting somewhere this evidence list will never see, so the only entry
        // carrying this chunk is already a reference.
        ChunkBlocks.format(List.of(hit(CITED, DOC_CITED, CITED_BODY, "Person")), ledger);
        List<String> ev = List
            .of(evidence("spec-a", ledger, List.of(hit(CITED, DOC_CITED, CITED_BODY, "Person"))));

        String out = citeScoped(ev, citedBy(CITED));

        assertThat(out).as("a reference to a body that is not in this prompt is useless")
            .doesNotContain(ChunkBlocks.SHOWN_ABOVE);
    }

    /**
     * A draft that numbers its references but never writes the ids out resolves to no citations at
     * all. Scoping to "what it cites" would then withhold everything — the worst possible reading
     * of a purely cosmetic fault — so an empty cited set degrades to plain de-duplication.
     */
    @Test
    public void anAnswerWithNoResolvableCitationsFallsBackToDedupeRatherThanWithholdingEverything() {
        List<String> ev = List.of(evidence("spec-a", new AgenticChatContext(),
            List.of(hit(CITED, DOC_CITED, CITED_BODY, "Person"),
                hit(UNCITED, DOC_UNCITED, UNCITED_BODY, "install-kit.md"))));

        String out = citeScoped(ev, "An answer citing only [1].");

        assertThat(out).contains(CITED_BODY).contains(UNCITED_BODY);
    }

    /**
     * The reviser's digest de-duplicates and nothing more. Cite-scoping it would be actively
     * harmful: the draft is the thing being changed, the reviewer's objection is usually that a
     * claim is unsupported, and the fix needs exactly the material the draft does not yet cite.
     */
    @Test
    public void dedupeForTheReviserKeepsUncitedBodies() {
        List<String> ev = List.of(evidence("spec-a", new AgenticChatContext(),
            List.of(hit(CITED, DOC_CITED, CITED_BODY, "Person"),
                hit(UNCITED, DOC_UNCITED, UNCITED_BODY, "install-kit.md"))));

        String out = EvidenceDigest.deduped(ev);

        assertThat(out).contains(CITED_BODY).contains(UNCITED_BODY);
    }

    @Test
    public void dedupeCollapsesOneChunkAcrossTwoSpecialists() {
        List<String> ev = List.of(
            evidence("spec-a", new AgenticChatContext(),
                List.of(hit(CITED, DOC_CITED, CITED_BODY, "Person"))),
            evidence("spec-b", new AgenticChatContext(),
                List.of(hit(CITED, DOC_CITED, CITED_BODY, "Person"))));

        String out = EvidenceDigest.deduped(ev);

        assertThat(countOf(out, CITED_BODY)).isEqualTo(1);
        assertThat(countOf(out, "id=" + CITED)).isEqualTo(2);
    }

    @Test
    public void emptyEvidenceProducesEmptyText() {
        assertThat(EvidenceDigest.deduped(List.of())).isEmpty();
        assertThat(citeScoped(List.of(), citedBy(CITED))).isEmpty();
    }

    /**
     * A section the answer does not cite is reduced like any other evidence. Before
     * {@code get_section} marked its passages this was impossible — a section had no parseable
     * blocks, so it travelled to the reviewer whole however irrelevant it turned out to be.
     */
    @Test
    void anUncitedSectionIsReducedRatherThanSentWhole() {
        String section = """
            # Section: Rollback > Restoring
            _(document: OIM Admin Guide  ·  tag: 10.0  ·  1 passage(s))_

            _(id=%s  doc_id=%s)_
            A passage about restoring backups that the answer never used.""".formatted(UNCITED,
            DOC_UNCITED);

        String out = citeScoped(List.of(section),
            "The answer cites [chunk_id=%s] and nothing else.".formatted(CITED));

        assertThat(out).as("an uncited passage's text is what the reviewer does not need")
            .doesNotContain("A passage about restoring backups");
    }

    /**
     * Round 0 of a review, where the reviewer has been sent nothing yet. Every assertion above is
     * written against this case, and a fresh ledger is exactly what makes it the old behaviour —
     * which is why they were adapted rather than rewritten.
     */
    private static String citeScoped(List<String> evidence, String answer) {
        return EvidenceDigest.citeScoped(evidence, answer, new EvidenceDigest.SentLedger());
    }

    /**
     * The ledger is what turns a rebuilt prompt into an append-only one: a body already sent stays
     * in the conversation it was sent to, and a body newly cited has never been seen, so it must
     * travel. Both halves are load-bearing — withholding the new one leaves the reviewer judging a
     * citation against text it cannot read, which is the exact failure cite-scoping prevents.
     */
    @Test
    void aFollowUpCarriesTheNewlyCitedBodyAndNotTheOneAlreadySent() {
        List<String> ev = List.of(evidence("spec-a", new AgenticChatContext(),
            List.of(hit(CITED, DOC_CITED, CITED_BODY, "Person"),
                hit(UNCITED, DOC_UNCITED, UNCITED_BODY, "install-kit.md"))));
        EvidenceDigest.SentLedger sent = new EvidenceDigest.SentLedger();

        String first = EvidenceDigest.citeScoped(ev, citedBy(CITED), sent);
        assertThat(first).contains(CITED_BODY).doesNotContain(UNCITED_BODY);
        assertThat(sent.alreadySent(CITED)).as("rendering a body IS the record that it was sent")
            .isTrue();

        String followUp = EvidenceDigest.citeScoped(ev,
            "Now citing [chunk_id=%s] and [chunk_id=%s].".formatted(CITED, UNCITED), sent);

        assertThat(followUp).as("newly cited, never sent — the reviewer cannot judge it otherwise")
            .contains(UNCITED_BODY);
        assertThat(followUp).as("already in the conversation; repeating it is what breaks caching")
            .doesNotContain(CITED_BODY);
        assertThat(sent.alreadySent(UNCITED)).as("and the new one is recorded too").isTrue();
    }

    /** A second follow-up citing nothing new renders nothing — the caller says "(none)". */
    @Test
    void aFollowUpThatCitesOnlySourcesAlreadySentRendersEmpty() {
        List<String> ev = List.of(evidence("spec-a", new AgenticChatContext(),
            List.of(hit(CITED, DOC_CITED, CITED_BODY, "Person"),
                hit(UNCITED, DOC_UNCITED, UNCITED_BODY, "install-kit.md"))));
        EvidenceDigest.SentLedger sent = new EvidenceDigest.SentLedger();

        EvidenceDigest.citeScoped(ev, citedBy(CITED), sent);

        assertThat(EvidenceDigest.citeScoped(ev, citedBy(CITED), sent))
            .as("nothing new to say, so nothing is said").isBlank();
    }

    /**
     * The reviser must never acquire a ledger. It is handed the whole evidence base precisely
     * because it needs what the draft does NOT cite, and a "sent already" rule would hide exactly
     * the material a revision exists to reach.
     */
    @Test
    void theReviserIsUnaffectedByWhateverTheReviewerHasBeenSent() {
        List<String> ev = List.of(evidence("spec-a", new AgenticChatContext(),
            List.of(hit(CITED, DOC_CITED, CITED_BODY, "Person"),
                hit(UNCITED, DOC_UNCITED, UNCITED_BODY, "install-kit.md"))));
        EvidenceDigest.SentLedger sent = new EvidenceDigest.SentLedger();
        EvidenceDigest.citeScoped(ev, citedBy(CITED), sent);

        assertThat(EvidenceDigest.deduped(ev))
            .as("both bodies, regardless of the reviewer's ledger").contains(CITED_BODY)
            .contains(UNCITED_BODY);
    }
}
