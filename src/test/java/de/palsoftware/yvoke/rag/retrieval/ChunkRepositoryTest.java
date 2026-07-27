package de.palsoftware.yvoke.rag.retrieval;


import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.document.core.repository.ChunkRepository;
import de.palsoftware.yvoke.document.core.model.ChunkRow;
import de.palsoftware.yvoke.document.core.repository.ChunkRowMapper;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

public class ChunkRepositoryTest {

    private JdbcClient jdbcClient;
    private ObjectMapper objectMapper;
    private ChunkRepository chunkRepository;

    private JdbcClient.StatementSpec statementSpec;
    private JdbcClient.MappedQuerySpec<ChunkRow> querySpec;

    @SuppressWarnings("unchecked")
    @BeforeEach
    public void setUp() {
        jdbcClient = mock(JdbcClient.class);
        objectMapper = new ObjectMapper();
        chunkRepository = new ChunkRepository(jdbcClient, objectMapper);

        statementSpec = mock(JdbcClient.StatementSpec.class);
        querySpec = (JdbcClient.MappedQuerySpec<ChunkRow>) mock(JdbcClient.MappedQuerySpec.class);

        when(jdbcClient.sql(anyString())).thenReturn(statementSpec);
        when(statementSpec.params(anyMap())).thenReturn(statementSpec);
        when(statementSpec.param(anyString(), any())).thenReturn(statementSpec);
        when(statementSpec.query(any(RowMapper.class))).thenReturn(querySpec);

        JdbcClient.MappedQuerySpec uuidQuerySpec = mock(JdbcClient.MappedQuerySpec.class);
        when(statementSpec.query(UUID.class)).thenReturn(uuidQuerySpec);
        when(uuidQuerySpec.list()).thenReturn(List.of(UUID.randomUUID()));
    }

    @Test
    public void testFindSemanticCandidates() {
        UUID id = UUID.randomUUID();
        ChunkRow mockRow = new ChunkRow(id, UUID.randomUUID(), "text", Collections.emptyList(),
            "head", 1, 0, "1.0", "file.md", "manual", "OIM", Collections.emptyMap(), 0.95);
        when(querySpec.list()).thenReturn(List.of(mockRow));

        List<ChunkRow> results = chunkRepository.findSemanticCandidates("[0.1,0.2]", 10, 0,
            List.of("1.0"), List.of("OIM"));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).id()).isEqualTo(id);
        assertThat(results.get(0).score()).isEqualTo(0.95);

        // Verify correct SQL query was prepared (last sql() call; the first resolves
        // collection names to ids for partition pruning)
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcClient, atLeastOnce()).sql(sqlCaptor.capture());
        String lastSql = sqlCaptor.getAllValues().get(sqlCaptor.getAllValues().size() - 1);
        assertThat(lastSql).contains("cosine_similarity");
        assertThat(lastSql).contains("unnest(d.tags)");
        assertThat(lastSql).contains("ch.collection_id IN (:collectionIds)");
        assertThat(lastSql).contains("OFFSET :offset");
    }

    @Test
    public void testFindSemanticCandidatesWithOffset() {
        when(querySpec.list()).thenReturn(Collections.emptyList());

        chunkRepository.findSemanticCandidates("[0.1]", 5, 10, List.of("1.0"), List.of("OIM"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(statementSpec).params(paramsCaptor.capture());
        assertThat(paramsCaptor.getValue().get("offset")).isEqualTo(10);
        assertThat(paramsCaptor.getValue().get("limit")).isEqualTo(5);
    }

    @Test
    public void testFindFulltextCandidates() {
        UUID id = UUID.randomUUID();
        ChunkRow mockRow = new ChunkRow(id, UUID.randomUUID(), "text", Collections.emptyList(),
            "head", 1, 0, "1.0", "file.md", "manual", "OIM", Collections.emptyMap(), 8.5);
        when(querySpec.list()).thenReturn(List.of(mockRow));

        List<ChunkRow> results =
            chunkRepository.findFulltextCandidates("query", 5, 0, null, List.of("OIM"));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).id()).isEqualTo(id);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcClient, atLeastOnce()).sql(sqlCaptor.capture());

        String lastSql = sqlCaptor.getAllValues().get(sqlCaptor.getAllValues().size() - 1);
        assertThat(lastSql).contains("pdb.score(ch.id)");
        assertThat(lastSql).contains("ch.text ||| :query");
        assertThat(lastSql).contains("ch.collection_id IN (:collectionIds)");
        // Since version is null, no tag clause should be present
        assertThat(lastSql).doesNotContain("ch.tags ===");
        assertThat(lastSql).contains("OFFSET :offset");
    }

    @Test
    public void testChunkRowMapperSuccess() throws java.sql.SQLException {
        java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
        java.sql.Array mockSqlArray = mock(java.sql.Array.class);

        UUID id = UUID.randomUUID();
        UUID docId = UUID.randomUUID();

        when(rs.getObject("id", UUID.class)).thenReturn(id);
        when(rs.getObject("document_id", UUID.class)).thenReturn(docId);
        when(rs.getString("text")).thenReturn("some chunk text");
        when(rs.getArray("heading_path")).thenReturn(mockSqlArray);
        when(mockSqlArray.getArray()).thenReturn(new String[] {"Section 1", "Sub 2"});
        when(rs.getString("heading")).thenReturn("heading text");
        when(rs.getObject("depth", Integer.class)).thenReturn(3);
        when(rs.getObject("sort_order", Integer.class)).thenReturn(12);
        when(rs.getString("tag")).thenReturn("9.3");
        when(rs.getString("document_title")).thenReturn("src.md");
        when(rs.getString("kind")).thenReturn("manual");
        when(rs.getString("collection")).thenReturn("OIM-TEST");
        when(rs.getString("metadata")).thenReturn("{\"custom\":\"value\"}");
        when(rs.getDouble("cosine_similarity")).thenReturn(0.88);

        ChunkRowMapper mapper = new ChunkRowMapper(objectMapper, "cosine_similarity");
        ChunkRow row = mapper.mapRow(rs, 1);

        assertThat(row).isNotNull();
        assertThat(row.id()).isEqualTo(id);
        assertThat(row.documentId()).isEqualTo(docId);
        assertThat(row.text()).isEqualTo("some chunk text");
        assertThat(row.headingPath()).containsExactly("Section 1", "Sub 2");
        assertThat(row.heading()).isEqualTo("heading text");
        assertThat(row.depth()).isEqualTo(3);
        assertThat(row.sortOrder()).isEqualTo(12);
        assertThat(row.tag()).isEqualTo("9.3");
        assertThat(row.documentTitle()).isEqualTo("src.md");
        assertThat(row.kind()).isEqualTo("manual");
        assertThat(row.collection()).isEqualTo("OIM-TEST");
        assertThat(row.metadata()).containsEntry("custom", "value");
        assertThat(row.score()).isEqualTo(0.88);
    }

    @Test
    public void testChunkRowMapperNullHeadingPathAndMetadata() throws java.sql.SQLException {
        java.sql.ResultSet rs = mock(java.sql.ResultSet.class);

        UUID id = UUID.randomUUID();

        when(rs.getObject("id", UUID.class)).thenReturn(id);
        when(rs.getArray("heading_path")).thenReturn(null);
        when(rs.getString("metadata")).thenReturn(null);

        ChunkRowMapper mapper = new ChunkRowMapper(objectMapper, null);
        ChunkRow row = mapper.mapRow(rs, 1);

        assertThat(row).isNotNull();
        assertThat(row.id()).isEqualTo(id);
        assertThat(row.headingPath()).isEmpty();
        assertThat(row.metadata()).isEmpty();
        assertThat(row.score()).isEqualTo(0.0);
    }

    @Test
    public void testChunkRowMapperMetadataParsingException() throws java.sql.SQLException {
        java.sql.ResultSet rs = mock(java.sql.ResultSet.class);

        UUID id = UUID.randomUUID();

        when(rs.getObject("id", UUID.class)).thenReturn(id);
        when(rs.getArray("heading_path")).thenReturn(null);
        when(rs.getString("metadata")).thenReturn("{invalid-json}");

        ChunkRowMapper mapper = new ChunkRowMapper(objectMapper, null);
        ChunkRow row = mapper.mapRow(rs, 1);

        assertThat(row).isNotNull();
        assertThat(row.id()).isEqualTo(id);
        assertThat(row.metadata()).isEmpty(); // Gracefully falls back to empty map
    }

    @Test
    public void testFindByIdPrefixValidation() {
        // Test null prefix
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> {
            chunkRepository.findByIdPrefix(null);
        });

        // Test too short prefix (less than 8 characters but not empty)
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> {
            chunkRepository.findByIdPrefix("1234567");
        });

        // Test invalid characters prefix
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> {
            chunkRepository.findByIdPrefix("1234567g"); // 'g' is not valid hex
        });

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> {
            chunkRepository.findByIdPrefix("1234-56%"); // '%' is sql wildcard
        });

        // Test valid prefix (empty string is allowed, triggers no validation exception)
        try {
            when(querySpec.list()).thenReturn(Collections.emptyList());
            chunkRepository.findByIdPrefix("");
        } catch (IllegalArgumentException e) {
            org.junit.jupiter.api.Assertions.fail("Empty prefix should be allowed");
        }

        // Test valid prefix (8 characters hex)
        try {
            when(querySpec.list()).thenReturn(Collections.emptyList());
            chunkRepository.findByIdPrefix("a1b2c3d4");
        } catch (IllegalArgumentException e) {
            org.junit.jupiter.api.Assertions
                .fail("Valid hex prefix of 8 characters should not throw exception");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testCountByIdPrefix() {
        JdbcClient.MappedQuerySpec<Long> countQuerySpec = mock(JdbcClient.MappedQuerySpec.class);
        when(statementSpec.params(anyMap())).thenReturn(statementSpec);
        when(statementSpec.query(Long.class)).thenReturn(countQuerySpec);
        when(countQuerySpec.single()).thenReturn(5L);

        long count = chunkRepository.countByIdPrefix("a1b2c3d4", "9.3");
        assertThat(count).isEqualTo(5L);

        verify(jdbcClient).sql(contains("COUNT(*)"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testFindDistinctSourceFiles() {
        JdbcClient.MappedQuerySpec<String> stringQuerySpec = mock(JdbcClient.MappedQuerySpec.class);
        when(statementSpec.params(anyMap())).thenReturn(statementSpec);
        when(statementSpec.query(String.class)).thenReturn(stringQuerySpec);
        when(stringQuerySpec.list()).thenReturn(List.of("file1.md", "file2.md"));

        List<String> files = chunkRepository.findDistinctDocumentTitles("9.3");
        assertThat(files).containsExactly("file1.md", "file2.md");

        verify(jdbcClient).sql(contains("DISTINCT d.title"));
    }
}
