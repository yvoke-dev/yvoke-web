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

    public List<IntermediateRrfResult> fuse(List<ChunkRow> semanticRows, List<ChunkRow> ftRows,
        int semanticLimit, int ftLimitCap) {

        int semanticFallbackRank = semanticLimit;
        int ftFallbackRank = ftLimitCap;

        Map<UUID, IntermediateRrfResult> combined = new HashMap<>();

        for (int i = 0; i < semanticRows.size(); i++) {
            ChunkRow row = semanticRows.get(i);
            IntermediateRrfResult res = new IntermediateRrfResult();
            res.setRow(row);
            res.setSemanticRank(i + 1);
            res.setFullTextRank(ftFallbackRank);
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
                res.setSemanticRank(semanticFallbackRank);
                res.setFullTextRank(j + 1);
                res.setInSem(false);
                res.setInFt(true);
                combined.put(row.id(), res);
            }
        }

        // Apply RRF scoring logic
        for (IntermediateRrfResult res : combined.values()) {
            double semScore = 1.0 / (rrfK + res.getSemanticRank());
            double ftScore = 1.0 / (rrfK + res.getFullTextRank());
            res.setRrfScore((semScore * semanticWeight + ftScore * fulltextWeight)
                / (semanticWeight + fulltextWeight));
        }

        // Sort descending by RRF score
        List<IntermediateRrfResult> sorted = new ArrayList<>(combined.values());
        sorted.sort((a, b) -> Double.compare(b.getRrfScore(), a.getRrfScore()));

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
