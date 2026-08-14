package de.palsoftware.yvoke.chat.orchestration;

import de.palsoftware.yvoke.rag.core.service.CitationVerifier;
import de.palsoftware.yvoke.rag.retrieval.ChunkBlocks;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Turns the specialists' raw tool output into what an agent is actually sent.
 *
 * <p>
 * Two consumers, two different needs. The <b>reviewer</b> is validating a finished draft, and it is
 * given exactly the sources that draft cites — nothing else, and no way to reach anything else.
 * That is not only a saving: a citation is the claim "this source supports this statement", so
 * checking it against precisely that source is the only test of the claim. Given the whole evidence
 * base instead, a reviewer finds a fact cited to chunk A sitting in chunk B, and approves a
 * mis-citation it was supposed to catch. The <b>reviser</b> is about to change the draft, usually
 * because a claim was called unsupported, so the material it needs is precisely what the draft does
 * <em>not</em> yet cite; scoping that would hand it only what it already used and provoke exactly
 * the from-scratch re-research this whole area exists to avoid. It therefore gets de-duplication
 * and nothing else.
 *
 * <p>
 * Evidence that is not chunk text is never reduced for either of them. It carries no chunk id, so
 * it can never be cited and could never be recovered — dropping a {@code query_json_objects}
 * projection would silently remove the only basis for the reviewer's version-history check, and
 * dropping a tool error would remove the only trace that a specialist's evidence has a hole.
 *
 * <p>
 * Both entry points are pure functions of the list handed to them, and the cited set is derived
 * inside {@link #citeScoped} rather than passed in. That is deliberate and load-bearing rather than
 * stylistic: it means "shown above" can only ever refer to a body in the <em>same</em> rendered
 * message. The natural-looking alternative — a shown-set carried on {@code RunState} — silently
 * breaks, because the reviewer is rendered separately from the orchestrator each round. Whichever
 * renderer ran first would consume the id, and the other would point at a body it never received.
 */
final class EvidenceDigest {

    private EvidenceDigest() {}

    /**
     * The reviser's view: one copy of each chunk body, everything else untouched.
     */
    static String deduped(List<String> evidence) {
        return render(evidence, null);
    }

    /**
     * The reviewer's view: the text of every source {@code answer} cites, and nothing else.
     *
     * <p>
     * An answer with no resolvable citation falls back to {@link #deduped}. Scoping to an empty set
     * would withhold the entire evidence base over a formatting slip — a draft that numbers its
     * references but never writes the ids out — and leave the reviewer certain nothing was
     * supported, which is the worst possible reading of a purely cosmetic fault.
     */
    static String citeScoped(List<String> evidence, String answer) {
        CitationVerifier.CitedIds cited = CitationVerifier.citedIds(answer);
        return render(evidence, cited.isEmpty() ? null : cited);
    }

    /**
     * @param cited when null, every chunk is kept (de-duplicated); otherwise chunks the answer does
     *        not cite are dropped entirely. Dropped rather than summarised on purpose: a line
     *        NAMING a source the reviewer cannot read invites approving a claim on the strength of
     *        a plausible-looking title, which is a way to wave through exactly the fabrication the
     *        reviewer exists to catch. A source the answer failed to cite is a citation defect, and
     *        the review loop is what corrects it.
     */
    private static String render(List<String> evidence, CitationVerifier.CitedIds cited) {
        if (evidence == null || evidence.isEmpty()) {
            return "";
        }

        // Pass 1: index every chunk's full text across ALL entries, before deciding anything. The
        // only surviving copy of a body may live in a different entry from the reference to it —
        // repeat suppression upstream works per specialist conversation, not per evidence entry —
        // so resolving references entry-by-entry would leave one pointing at text this method has
        // itself removed.
        Map<UUID, String> bodies = fullBodies(evidence);

        Set<UUID> emitted = new HashSet<>();
        List<String> out = new ArrayList<>();

        for (String entry : evidence) {
            ChunkBlocks.Parsed parsed = ChunkBlocks.parse(entry);
            if (parsed.blocks().isEmpty()) {
                // Not chunk text — a JSON projection, a section body, a tool error. Nothing here
                // can be cited and nothing could be recovered, so it passes through whole.
                out.add(entry);
                continue;
            }

            List<String> rendered = new ArrayList<>();
            for (ChunkBlocks.Block block : parsed.blocks()) {
                UUID id = block.chunkId();
                String indexed = (id != null) ? bodies.get(id) : block.body();
                boolean haveText = indexed != null && !ChunkBlocks.SHOWN_ABOVE.equals(indexed);
                boolean keep =
                    cited == null || cited.covers(id) || cited.covers(block.documentId());

                // A reference whose body is nowhere in this evidence cannot be honoured: emitting
                // it would send an agent looking further up a prompt that does not contain the
                // text. Belt and braces — the harvest keeps a specialist's tool messages together,
                // so its own full copy should always be here.
                if (!keep || (id != null && !haveText && cited != null)) {
                    continue;
                }
                if (id != null && !emitted.add(id)) {
                    rendered.add(block.withBody(ChunkBlocks.SHOWN_ABOVE));
                    continue;
                }
                // An id-less block cannot be dropped (nothing identifies it as uncited) and cannot
                // be de-duplicated, so it is always kept as it stands.
                rendered.add(block.withBody(haveText ? indexed : block.body()));
            }

            if (rendered.isEmpty()) {
                // Every chunk in this entry was dropped; its preamble alone would announce a search
                // and show nothing of it.
                continue;
            }
            out.add(parsed.preamble() + String.join("\n\n", rendered) + parsed.suffix());
        }

        return String.join("\n\n", out);
    }

    /** First full (non-reference) rendering of each chunk, wherever in the evidence it sits. */
    private static Map<UUID, String> fullBodies(List<String> evidence) {
        Map<UUID, String> bodies = new HashMap<>();
        for (String entry : evidence) {
            for (ChunkBlocks.Block block : ChunkBlocks.parse(entry).blocks()) {
                if (block.chunkId() != null && !block.isShownAboveMarker()) {
                    bodies.putIfAbsent(block.chunkId(), block.body());
                }
            }
        }
        return bodies;
    }
}
