package de.palsoftware.yvoke.rag.retrieval;

import java.util.List;
import java.util.UUID;

/**
 * One {@code retrieval_logs} row, read back by {@code searchId} for the admin search console.
 *
 * <p>
 * The three JSON blobs are handed to the template as-is; the pool sizes and stage snapshots are
 * what {@code RagAdminViewService.toLaneTrace} replays into a per-lane trace.
 * {@code initialChunkIds} is the semantic ids in rank order <em>followed by</em> the BM25 ids in
 * rank order, <strong>undeduped</strong>, so slicing it at {@code semPool} recovers each lane's
 * per-chunk rank — a chunk found by both lanes appears twice, which is exactly what makes the split
 * work.
 */
public record RetrievalTelemetryRow(String poolsJson,String finalJson,String rerankJson,int semPool,int ftPool,List<UUID>initialChunkIds,List<UUID>fusedChunkIds,List<UUID>retrievedChunkIds){}
