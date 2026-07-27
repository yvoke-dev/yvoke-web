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

        // Limits: semanticLimit = 5, ftLimitCap = 5
        List<RrfFuser.IntermediateRrfResult> results = fuser.fuse(semantic, fulltext, 5, 5);

        // All 3 elements should be present
        assertThat(results).hasSize(3);

        // id2 appears in both (rank 2 in semantic, rank 1 in full-text)
        // Score for id2: (1/(60+2) + 1/(60+1)) / 2
        // id1 appears in semantic (rank 1 in semantic, rank 5 in full-text fallback)
        // Score for id1: (1/(60+1) + 1/(60+5)) / 2
        // id3 appears in full-text (rank 5 in semantic fallback, rank 2 in full-text)
        // Score for id3: (1/(60+5) + 1/(60+2)) / 2

        // Scores comparison:
        // id2: 0.5 * (1/62 + 1/61) = 0.5 * (0.016129 + 0.016393) = 0.01626
        // id1: 0.5 * (1/61 + 1/65) = 0.5 * (0.016393 + 0.015384) = 0.01588
        // id3: 0.5 * (1/65 + 1/62) = 0.5 * (0.015384 + 0.016129) = 0.01575
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

        List<RrfFuser.IntermediateRrfResult> results = fuser.fuse(semantic, fulltext, 2, 2);

        // id1 score should be weighted much higher by semantic weight 2.0
        // id1: (2.0 * (1/61) + 0.5 * (1/62)) / 2.5
        // id2: (2.0 * (1/62) + 0.5 * (1/61)) / 2.5
        assertThat(results.get(0).getRow().id()).isEqualTo(id1);
        assertThat(results.get(1).getRow().id()).isEqualTo(id2);
    }

    @Test
    public void testRrfFusionEmptyLanes() {
        RrfFuser fuser = new RrfFuser(60, 1.0, 1.0);
        List<RrfFuser.IntermediateRrfResult> results =
            fuser.fuse(Collections.emptyList(), Collections.emptyList(), 10, 10);
        assertThat(results).isEmpty();
    }

    private ChunkRow createMockRow(UUID id, String text) {
        return new ChunkRow(id, UUID.randomUUID(), text, Collections.emptyList(), "", 1, 0, "1.0",
            "file.md", "manual", "coll", Collections.emptyMap(), 0.0);
    }
}
