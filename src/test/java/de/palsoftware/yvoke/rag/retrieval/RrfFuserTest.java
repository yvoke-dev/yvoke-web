package de.palsoftware.yvoke.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import de.palsoftware.yvoke.document.core.model.ChunkRow;
import java.util.*;
import org.junit.jupiter.api.Test;

public class RrfFuserTest {

    @Test
    public void testRrfFusionBasic() {
        RrfFuser fuser = new RrfFuser(60, 1.0, 1.0);

        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        UUID id3 = UUID.randomUUID();

        // Lane 1: Semantic
        // id1 (rank 1), id2 (rank 2)
        List<ChunkRow> semantic = List.of(createMockRow(id1, "doc1"), createMockRow(id2, "doc2"));

        // Lane 2: Full-text
        // id2 (rank 1), id3 (rank 2)
        List<ChunkRow> fulltext = List.of(createMockRow(id2, "doc2"), createMockRow(id3, "doc3"));

        List<RrfFuser.IntermediateRrfResult> results = fuser.fuse(semantic, fulltext);

        // All 3 elements should be present
        assertThat(results).hasSize(3);

        // A lane that did not rank a chunk contributes nothing for it — no fallback rank.
        // id2 appears in both (rank 2 semantic, rank 1 full-text): 0.5 * (1/62 + 1/61) = 0.016261
        // id1 appears in semantic only (rank 1): 0.5 * (1/61 + 0) = 0.008197
        // id3 appears in full-text only (rank 2): 0.5 * (0 + 1/62) = 0.008065
        // Order should be id2 (rank 1), id1 (rank 2), id3 (rank 3)
        assertThat(results.get(0).getRow().id()).isEqualTo(id2);
        assertThat(results.get(0).getRrfRank()).isEqualTo(1);
        assertThat(results.get(0).isInSem()).isTrue();
        assertThat(results.get(0).isInFt()).isTrue();

        assertThat(results.get(1).getRow().id()).isEqualTo(id1);
        assertThat(results.get(1).getRrfRank()).isEqualTo(2);
        assertThat(results.get(1).isInSem()).isTrue();
        assertThat(results.get(1).isInFt()).isFalse();

        assertThat(results.get(2).getRow().id()).isEqualTo(id3);
        assertThat(results.get(2).getRrfRank()).isEqualTo(3);
        assertThat(results.get(2).isInSem()).isFalse();
        assertThat(results.get(2).isInFt()).isTrue();
    }

    @Test
    public void testRrfFusionWeights() {
        // High semantic weight (2.0) vs low full-text weight (0.5)
        RrfFuser fuser = new RrfFuser(60, 2.0, 0.5);

        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();

        // id1 is top semantic match (rank 1).
        List<ChunkRow> semantic = List.of(createMockRow(id1, "semantic-top"));

        // id2 is top full-text match (rank 1).
        List<ChunkRow> fulltext = List.of(createMockRow(id2, "fulltext-top"));

        List<RrfFuser.IntermediateRrfResult> results = fuser.fuse(semantic, fulltext);

        // id1 score should be weighted much higher by semantic weight 2.0
        // id1: (2.0 * (1/61) + 0) / 2.5 = 0.013115
        // id2: (0 + 0.5 * (1/61)) / 2.5 = 0.003279
        assertThat(results.get(0).getRow().id()).isEqualTo(id1);
        assertThat(results.get(1).getRow().id()).isEqualTo(id2);
    }

    /**
     * The defect this pins: a chunk absent from a lane used to be charged that lane's requested
     * pool size as its rank, so the penalty scaled with the OTHER lane's pool rather than with
     * anything about relevance. With the production pools (semantic 12, full-text 30) missing from
     * full-text cost rank 30 while missing from semantic cost only 12, so a BM25-only hit outranked
     * a vector-only hit at the same in-lane rank — measured live, the best possible semantic-only
     * chunk could not enter the top 14 of fusion however relevant it was. Worse, the direction
     * flipped with page size, because {@code semanticLimit} is derived from the request while the
     * BM25 pool was a constant. Canonical RRF sums only over the lanes that actually ranked the
     * document, which makes the two lanes symmetric by construction.
     */
    @Test
    public void aRowFoundByOneLaneScoresTheSameWhicheverLaneFoundIt() {
        RrfFuser fuser = new RrfFuser(60, 1.0, 1.0);

        UUID semOnly = UUID.randomUUID();
        UUID ftOnly = UUID.randomUUID();

        List<RrfFuser.IntermediateRrfResult> results = fuser
            .fuse(List.of(createMockRow(semOnly, "sem")), List.of(createMockRow(ftOnly, "ft")));

        assertThat(scoreOf(results, semOnly)).isEqualTo(scoreOf(results, ftOnly));
    }

    /**
     * Cross-lane agreement is the one signal fusion adds over either lane alone, and it should be
     * worth something: under the old fallback a both-lane row led a single-lane rank-1 row by ~8%,
     * because the phantom term handed the single-lane row most of a second lane's credit. It is now
     * ~2x. That matters most where agreement is rarest — the measured corpus overlapped on 1 of 42
     * candidates.
     */
    @Test
    public void aRowFoundByBothLanesOutranksEitherSingleLaneRow() {
        RrfFuser fuser = new RrfFuser(60, 1.0, 1.0);

        UUID both = UUID.randomUUID();
        UUID semOnly = UUID.randomUUID();
        UUID ftOnly = UUID.randomUUID();

        // `both` is rank 2 in each lane — beaten on rank in both, yet it must still lead.
        List<RrfFuser.IntermediateRrfResult> results =
            fuser.fuse(List.of(createMockRow(semOnly, "s"), createMockRow(both, "b")),
                List.of(createMockRow(ftOnly, "f"), createMockRow(both, "b")));

        assertThat(results.get(0).getRow().id()).isEqualTo(both);
        assertThat(scoreOf(results, both)).isGreaterThan(scoreOf(results, semOnly));
        assertThat(scoreOf(results, both)).isGreaterThan(scoreOf(results, ftOnly));
    }

    /**
     * Symmetry makes exact ties systematic rather than accidental, so the tie-break becomes
     * load-bearing. The previous sort fell through to {@code HashMap} iteration order — arbitrary,
     * and unstable as soon as the id set changes. Ordering by id is equally arbitrary with respect
     * to relevance (an exact tie IS a genuine tie) but it is total and reproducible, which is the
     * property a caller can rely on.
     */
    @Test
    public void exactTiesResolveByIdSoTheOrderIsReproducible() {
        RrfFuser fuser = new RrfFuser(60, 1.0, 1.0);

        // Both ids are chosen with a zero high half: UUID.compareTo compares mostSigBits as a
        // SIGNED long, so `ffffffff-...` (-1) would sort BEFORE `00000000-...` (0) and an id pair
        // picked by string-lexicographic intuition asserts the wrong winner.
        UUID low = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID high = UUID.fromString("00000000-0000-0000-0000-000000000002");

        // `high` tops the semantic lane, `low` tops the full-text lane: identical scores.
        List<RrfFuser.IntermediateRrfResult> results =
            fuser.fuse(List.of(createMockRow(high, "s")), List.of(createMockRow(low, "f")));

        assertThat(results.get(0).getRrfScore()).isEqualTo(results.get(1).getRrfScore());
        assertThat(results.get(0).getRow().id()).isEqualTo(low);
        assertThat(results.get(0).getRrfRank()).isEqualTo(1);
    }

    private double scoreOf(List<RrfFuser.IntermediateRrfResult> results, UUID id) {
        return results.stream().filter(r -> r.getRow().id().equals(id)).findFirst().orElseThrow()
            .getRrfScore();
    }

    @Test
    public void testRrfFusionEmptyLanes() {
        RrfFuser fuser = new RrfFuser(60, 1.0, 1.0);
        List<RrfFuser.IntermediateRrfResult> results =
            fuser.fuse(Collections.emptyList(), Collections.emptyList());
        assertThat(results).isEmpty();
    }

    private ChunkRow createMockRow(UUID id, String text) {
        return new ChunkRow(id, UUID.randomUUID(), text, Collections.emptyList(), "", 1, 0, "1.0",
            "file.md", "manual", "coll", Collections.emptyMap(), 0.0);
    }
}
