package de.palsoftware.yvoke.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

public class RetrievalLogRepositoryTest {

    private JdbcClient jdbcClient;
    private RetrievalLogRepository retrievalLogRepository;

    private JdbcClient.StatementSpec statementSpec;
    private JdbcClient.MappedQuerySpec<RetrievalLogDetails> querySpec;
    private JdbcClient.MappedQuerySpec<Long> countQuerySpec;

    @SuppressWarnings("unchecked")
    @BeforeEach
    public void setUp() {
        jdbcClient = mock(JdbcClient.class);
        retrievalLogRepository = new RetrievalLogRepository(jdbcClient);

        statementSpec = mock(JdbcClient.StatementSpec.class);
        querySpec = (JdbcClient.MappedQuerySpec<RetrievalLogDetails>) mock(
            JdbcClient.MappedQuerySpec.class);
        countQuerySpec = (JdbcClient.MappedQuerySpec<Long>) mock(JdbcClient.MappedQuerySpec.class);

        when(jdbcClient.sql(anyString())).thenReturn(statementSpec);
        when(statementSpec.param(anyString(), any())).thenReturn(statementSpec);
        when(statementSpec.query(any(RowMapper.class))).thenReturn(querySpec);
        when(statementSpec.query(Long.class)).thenReturn(countQuerySpec);
    }

    @Test
    public void testListLogs() {
        UUID id = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        Instant now = Instant.now();

        RetrievalLogDetails mockLog = new RetrievalLogDetails(id, messageId, "OIM", "9.3", "{}",
            "{}", "{}", now, "User message", 1, "Good text", List.of(UUID.randomUUID()));
        when(querySpec.list()).thenReturn(List.of(mockLog));

        List<RetrievalLogDetails> result = retrievalLogRepository.listLogs(10, 20);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo(id);
        assertThat(result.get(0).messageId()).isEqualTo(messageId);
        assertThat(result.get(0).feedbackRating()).isEqualTo(1);

        verify(jdbcClient).sql(contains("FROM retrieval_logs l"));
        verify(statementSpec).param("limit", 10);
        verify(statementSpec).param("offset", 20);
    }

    @Test
    public void testCountLogs() {
        when(countQuerySpec.single()).thenReturn(42L);

        long count = retrievalLogRepository.countLogs();

        assertThat(count).isEqualTo(42L);
        verify(jdbcClient).sql("SELECT count(*) FROM retrieval_logs");
    }

    @Test
    public void testSaveTelemetry() {
        UUID searchId = UUID.randomUUID();
        UUID chunkId1 = UUID.randomUUID();
        UUID chunkId2 = UUID.randomUUID();
        List<UUID> chunkIds = List.of(chunkId1, chunkId2);
        UUID semId = UUID.randomUUID();
        UUID ftId = UUID.randomUUID();
        List<UUID> initialIds = List.of(semId, ftId);
        List<UUID> fusedIds = List.of(ftId, semId);
        List<UUID> rerankedIds = List.of(semId, ftId);
        when(statementSpec.update()).thenReturn(1);

        UUID collectionId = UUID.randomUUID();
        retrievalLogRepository.saveTelemetry(searchId, "my test query", collectionId, "1.0", "{}",
            "{}", "{}", chunkIds, initialIds, fusedIds, rerankedIds);

        verify(jdbcClient).sql(contains("INSERT INTO retrieval_logs"));
        verify(statementSpec).param("id", searchId);
        verify(statementSpec).param("query", "my test query");
        verify(statementSpec).param("collectionId", collectionId);
        verify(statementSpec).param("tag", "1.0");
        verify(statementSpec).param(eq("retrieved_chunk_ids"), any(UUID[].class));
        verify(statementSpec).param(eq("initial_chunk_ids"), any(UUID[].class));
        verify(statementSpec).param(eq("fused_chunk_ids"), any(UUID[].class));
        verify(statementSpec).param(eq("reranked_chunk_ids"), any(UUID[].class));
        verify(statementSpec).update();
    }

    @Test
    public void testRetrievalLogDetailsTruncation() {
        Instant now = Instant.now();
        RetrievalLogDetails detailsNormal =
            new RetrievalLogDetails(UUID.randomUUID(), UUID.randomUUID(), "OIM", "9.3", "{}", "{}",
                "{}", now, "Short message", 1, "Good text", List.of(UUID.randomUUID()));
        RetrievalLogDetails detailsLong =
            new RetrievalLogDetails(UUID.randomUUID(), UUID.randomUUID(), "OIM", "9.3", "{}", "{}",
                "{}", now, "This is a very long message that should be truncated to some limit", 1,
                "Good text", List.of(UUID.randomUUID()));
        RetrievalLogDetails detailsNull =
            new RetrievalLogDetails(UUID.randomUUID(), UUID.randomUUID(), "OIM", "9.3", "{}", "{}",
                "{}", now, null, 1, "Good text", List.of(UUID.randomUUID()));

        assertThat(detailsNormal.getTruncatedMessageContent(50)).isEqualTo("Short message");
        assertThat(detailsLong.getTruncatedMessageContent(10)).isEqualTo("This is a ...");
        assertThat(detailsLong.getTruncatedMessageContent(50))
            .isEqualTo("This is a very long message that should be truncat...");
        assertThat(detailsNull.getTruncatedMessageContent(50)).isNull();
    }
}
