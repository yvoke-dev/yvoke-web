package de.palsoftware.yvoke.rag.retrieval;

import de.palsoftware.yvoke.document.core.model.ChunkRow;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class RrfFuser {

    private final int rrfK;
    private final double semanticWeight;
    private final double fulltextWeight;

    public RrfFuser(@Value("${app.retrieval.rrf.k}") int rrfK,
        @Value("${app.retrieval.rrf.semantic-weight}") double semanticWeight,
        @Value("${app.retrieval.rrf.fulltext-weight}") double fulltextWeight) {
        this.rrfK = rrfK;
        this.semanticWeight = semanticWeight;
        this.fulltextWeight = fulltextWeight;
    }

    /**
     * Canonical RRF: each lane contributes {@code 1/(k + rank)} for the chunks it actually ranked,
     * and nothing at all for the chunks it did not. Deliberately takes no pool sizes — an earlier
     * version charged an absent lane that lane's requested pool size as a fallback rank, which made
     * the penalty scale with the other lane's pool instead of with relevance, and flipped direction
     * with the requested page size. Dropping the parameters makes that class of bug unrepresentable
     * rather than merely fixed.
     */
    public List<IntermediateRrfResult> fuse(List<ChunkRow> semanticRows, List<ChunkRow> ftRows) {

        Map<UUID, IntermediateRrfResult> combined = new HashMap<>();

        for (int i = 0; i < semanticRows.size(); i++) {
            ChunkRow row = semanticRows.get(i);
            IntermediateRrfResult res = new IntermediateRrfResult();
            res.setRow(row);
            res.setSemanticRank(i + 1);
            res.setInSem(true);
            res.setInFt(false);
            combined.put(row.id(), res);
        }

        for (int j = 0; j < ftRows.size(); j++) {
            ChunkRow row = ftRows.get(j);
            IntermediateRrfResult res = combined.get(row.id());
            if (res != null) {
                res.setFullTextRank(j + 1);
                res.setInFt(true);
            } else {
                res = new IntermediateRrfResult();
                res.setRow(row);
                res.setFullTextRank(j + 1);
                res.setInSem(false);
                res.setInFt(true);
                combined.put(row.id(), res);
            }
        }

        // Apply RRF scoring logic
        for (IntermediateRrfResult res : combined.values()) {
            double semScore = res.isInSem() ? 1.0 / (rrfK + res.getSemanticRank()) : 0.0;
            double ftScore = res.isInFt() ? 1.0 / (rrfK + res.getFullTextRank()) : 0.0;
            res.setRrfScore((semScore * semanticWeight + ftScore * fulltextWeight)
                / (semanticWeight + fulltextWeight));
        }

        // Sort descending by RRF score, then by id. The tie-break is load-bearing now: with equal
        // weights a semantic-only row at rank r and a full-text-only row at rank r score
        // identically by construction, where before ties were only accidental. Id is arbitrary with
        // respect to relevance — an exact tie IS a genuine tie — but it is total and reproducible,
        // unlike the previous fall-through to HashMap iteration order.
        List<IntermediateRrfResult> sorted = new ArrayList<>(combined.values());
        sorted.sort(Comparator.comparingDouble(IntermediateRrfResult::getRrfScore).reversed()
            .thenComparing(res -> res.getRow().id()));

        // Assign pre-rerank RRF rank (1-indexed)
        for (int i = 0; i < sorted.size(); i++) {
            sorted.get(i).setRrfRank(i + 1);
        }

        return sorted;
    }

    public static class IntermediateRrfResult {
        private ChunkRow row;
        private int semanticRank;
        private int fullTextRank;
        private boolean inSem;
        private boolean inFt;
        private double rrfScore;
        private int rrfRank;
        private Double rerankScore;

        public ChunkRow getRow() {
            return row;
        }

        public void setRow(ChunkRow row) {
            this.row = row;
        }

        public int getSemanticRank() {
            return semanticRank;
        }

        public void setSemanticRank(int semanticRank) {
            this.semanticRank = semanticRank;
        }

        public int getFullTextRank() {
            return fullTextRank;
        }

        public void setFullTextRank(int fullTextRank) {
            this.fullTextRank = fullTextRank;
        }

        public boolean isInSem() {
            return inSem;
        }

        public void setInSem(boolean inSem) {
            this.inSem = inSem;
        }

        public boolean isInFt() {
            return inFt;
        }

        public void setInFt(boolean inFt) {
            this.inFt = inFt;
        }

        public double getRrfScore() {
            return rrfScore;
        }

        public void setRrfScore(double rrfScore) {
            this.rrfScore = rrfScore;
        }

        public int getRrfRank() {
            return rrfRank;
        }

        public void setRrfRank(int rrfRank) {
            this.rrfRank = rrfRank;
        }

        public Double getRerankScore() {
            return rerankScore;
        }

        public void setRerankScore(Double rerankScore) {
            this.rerankScore = rerankScore;
        }

        /** Returns rerankScore if reranking succeeded, otherwise the original RRF score. */
        public double getFinalScore() {
            return rerankScore != null ? rerankScore : rrfScore;
        }
    }
}
