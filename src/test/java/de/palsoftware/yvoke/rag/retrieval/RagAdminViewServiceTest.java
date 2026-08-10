package de.palsoftware.yvoke.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import de.palsoftware.yvoke.rag.retrieval.RagAdminViews.LaneTrace;
import de.palsoftware.yvoke.rag.retrieval.RagAdminViews.LaneTraceRow;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The lane trace is reconstructed entirely from columns {@code retrieval_logs} already stores, so
 * these are pure-function tests over the stage snapshots — no DB, no Spring.
 */
public class RagAdminViewServiceTest {

    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();
    private static final UUID C = UUID.randomUUID();
    private static final UUID D = UUID.randomUUID();

    /**
     * Semantic lane ranks A, B; BM25 ranks B, C, D — so B is the one chunk both lanes found, and
     * {@code initial_chunk_ids} carries it twice (undeduped), which is what makes the slice at the
     * recorded pool size recover each lane's own rank.
     */
    private static RetrievalTelemetryRow row(List<UUID> retrieved) {
        return new RetrievalTelemetryRow("{}", "{}", "{}", 2, 3, List.of(A, B, B, C, D),
            List.of(B, A, C, D), retrieved);
    }

    @Test
    public void aReturnedRowCarriesItsFusedRankAndItsRankInEachLaneThatFoundIt() {
        LaneTrace trace = RagAdminViewService.toLaneTrace(row(List.of(C, B)), 10);

        assertThat(trace.returnedOrder()).hasSize(2);

        // C was 3rd after fusion, absent from the semantic lane, 2nd in BM25.
        LaneTraceRow first = trace.returnedOrder().get(0);
        assertThat(first.position()).isEqualTo(1);
        assertThat(first.rrfRank()).isEqualTo(3);
        assertThat(first.semRank()).isNull();
        assertThat(first.ftRank()).isEqualTo(2);
        assertThat(first.lane()).isEqualTo("ft");

        // B topped fusion and was found by both lanes, 2nd semantic and 1st BM25.
        LaneTraceRow second = trace.returnedOrder().get(1);
        assertThat(second.rrfRank()).isEqualTo(1);
        assertThat(second.semRank()).isEqualTo(2);
        assertThat(second.ftRank()).isEqualTo(1);
        assertThat(second.lane()).isEqualTo("both");
    }

    @Test
    public void theFusionOrderListsEveryCandidateWithItsLaneCoordinates() {
        LaneTrace trace = RagAdminViewService.toLaneTrace(row(List.of(B)), 10);

        assertThat(trace.fusedTotal()).isEqualTo(4);
        assertThat(trace.isFusionTruncated()).isFalse();
        assertThat(trace.fusionOrder()).extracting(LaneTraceRow::position).containsExactly(1, 2, 3,
            4);
        assertThat(trace.fusionOrder()).extracting(LaneTraceRow::semRank).containsExactly(2, 1,
            null, null);
        assertThat(trace.fusionOrder()).extracting(LaneTraceRow::ftRank).containsExactly(1, null, 2,
            3);
    }

    /**
     * The fused set runs to {@code semanticLimit + fullTextLimit} candidates, so the panel shows a
     * prefix. A silently truncated list reads as the whole ordering, which is the same failure the
     * telemetry panel already had with "Total Fused (n)" — the total is reported alongside.
     */
    @Test
    public void aTruncatedFusionListReportsTheTotalItWasCutFrom() {
        LaneTrace trace = RagAdminViewService.toLaneTrace(row(List.of(B)), 2);

        assertThat(trace.fusionOrder()).hasSize(2);
        assertThat(trace.fusionShown()).isEqualTo(2);
        assertThat(trace.fusedTotal()).isEqualTo(4);
        assertThat(trace.isFusionTruncated()).isTrue();
    }

    /**
     * Single-lane searches persist a NULL {@code fused_chunk_ids} — there is no fusion to trace.
     */
    @Test
    public void aSingleLaneSearchHasNoTraceRatherThanAPartialOne() {
        RetrievalTelemetryRow singleLane =
            new RetrievalTelemetryRow("{}", "{}", "{}", 0, 2, List.of(A, B), List.of(), List.of(A));

        assertThat(RagAdminViewService.toLaneTrace(singleLane, 10).isEmpty()).isTrue();
    }

    /**
     * The whole reconstruction rests on {@code initial_chunk_ids.length == sem + ft}. If that does
     * not hold the slice boundary is wrong and every rank after it would be misattributed — a
     * confidently wrong trace is worse than none, so the row is refused outright.
     */
    @Test
    public void aRowWhoseSnapshotDoesNotMatchItsPoolSizesYieldsNoTrace() {
        RetrievalTelemetryRow mismatched = new RetrievalTelemetryRow("{}", "{}", "{}", 2, 3,
            List.of(A, B, C), List.of(B, A, C), List.of(B));

        assertThat(RagAdminViewService.toLaneTrace(mismatched, 10).isEmpty()).isTrue();
    }
}
