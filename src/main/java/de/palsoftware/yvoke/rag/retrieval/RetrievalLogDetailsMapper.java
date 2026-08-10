package de.palsoftware.yvoke.rag.retrieval;

import de.palsoftware.yvoke.shared.config.JdbcMappers;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;

public class RetrievalLogDetailsMapper implements RowMapper<RetrievalLogDetails> {
    @Override
    public RetrievalLogDetails mapRow(ResultSet rs, int rowNum) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        UUID messageId = rs.getObject("message_id", UUID.class);
        UUID collectionId = rs.getObject("collection_id", UUID.class);
        String collectionName = rs.getString("collection");
        String tag = rs.getString("tag");
        String pools = rs.getString("pools_text");
        String finalVal = rs.getString("final_text");
        String rerank = rs.getString("rerank_text");

        Timestamp ts = rs.getTimestamp("created_at");
        Instant createdAt = ts != null ? ts.toInstant() : null;

        String messageContent = rs.getString("message_content");
        Integer feedbackRating = rs.getObject("feedback_rating", Integer.class);
        String feedbackComment = rs.getString("feedback_comment");

        List<UUID> retrievedChunkIds = JdbcMappers.arrayToUuidList(rs, "retrieved_chunk_ids");

        return new RetrievalLogDetails(id, messageId, collectionId, collectionName, tag, pools,
            finalVal, rerank, createdAt, messageContent, feedbackRating, feedbackComment,
            retrievedChunkIds);
    }
}
