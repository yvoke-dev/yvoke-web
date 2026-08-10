package de.palsoftware.yvoke.document.core.repository;

import de.palsoftware.yvoke.document.core.model.ChunkKgStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.shared.db.CollectionIdResolver;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.junit.jupiter.api.Assertions;

public class DocumentRepositoryWriteTest {

    private final JdbcClient jdbcClient = mock(JdbcClient.class);
    private final ObjectMapper objectMapper = mock(ObjectMapper.class);
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final CollectionIdResolver collectionIdResolver = mock(CollectionIdResolver.class);
    private final DocumentRepository repository =
        new DocumentRepository(jdbcClient, objectMapper, jdbcTemplate, collectionIdResolver);

    @Test
    public void emptyStatusListIsNoOp() {
        repository.markChunkKgStatuses(List.of());

        verify(jdbcTemplate, never()).batchUpdate(anyString(),
            any(BatchPreparedStatementSetter.class));
    }

    @Test
    public void issuesBatchUpdateWithStatusModelAndId() throws Exception {
        UUID c0 = UUID.randomUUID();
        UUID c1 = UUID.randomUUID();
        List<ChunkKgStatus> statuses = List.of(new ChunkKgStatus(c0, true, "kg-model"),
            new ChunkKgStatus(c1, false, "kg-model"));

        repository.markChunkKgStatuses(statuses);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<BatchPreparedStatementSetter> setterCaptor =
            ArgumentCaptor.forClass(BatchPreparedStatementSetter.class);
        verify(jdbcTemplate).batchUpdate(sqlCaptor.capture(), setterCaptor.capture());

        // SQL updates the status columns and stamps the time; it must not touch other columns.
        String sql = sqlCaptor.getValue();
        assertThat(sql).contains("UPDATE chunks");
        assertThat(sql).contains("kg_ok = ?");
        assertThat(sql).contains("kg_model = ?");
        assertThat(sql).contains("kg_extracted_at = now()");
        assertThat(sql).contains("WHERE id = ?");

        BatchPreparedStatementSetter setter = setterCaptor.getValue();
        assertThat(setter.getBatchSize()).isEqualTo(2);

        // Row 0: ok=true, Row 1: ok=false; both bind model then id in order.
        PreparedStatement ps0 = mock(PreparedStatement.class);
        setter.setValues(ps0, 0);
        verify(ps0).setBoolean(1, true);
        verify(ps0).setString(2, "kg-model");
        verify(ps0).setObject(3, c0);

        PreparedStatement ps1 = mock(PreparedStatement.class);
        setter.setValues(ps1, 1);
        verify(ps1).setBoolean(1, false);
        verify(ps1).setString(2, "kg-model");
        verify(ps1).setObject(3, c1);
    }

    @Test
    public void upsertManualDocument_blankKindThrowsException() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> {
            repository.upsertManualDocument("coll", "v1", "file.txt", "   ", "title");
        });
        Assertions.assertThrows(IllegalArgumentException.class, () -> {
            repository.upsertManualDocument("coll", "v1", "file.txt", null, "title");
        });
    }

    @Test
    public void updateIngestionStatus_invalidStatusThrowsException() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> {
            repository.updateIngestionStatus(UUID.randomUUID(), "invalid-status");
        });
    }

    @Test
    public void insertChunks_blankKindThrowsException() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> {
            repository.insertChunks(UUID.randomUUID(), "coll", "v1", "file.txt", "   ", List.of());
        });
        Assertions.assertThrows(IllegalArgumentException.class, () -> {
            repository.insertChunks(UUID.randomUUID(), "coll", "v1", "file.txt", null, List.of());
        });
    }
}
