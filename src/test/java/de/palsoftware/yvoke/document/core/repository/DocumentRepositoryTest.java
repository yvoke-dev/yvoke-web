package de.palsoftware.yvoke.document.core.repository;

import de.palsoftware.yvoke.document.core.model.DocumentDetails;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import de.palsoftware.yvoke.shared.db.CollectionIdResolver;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;

public class DocumentRepositoryTest {

    private JdbcClient jdbcClient;
    private ObjectMapper objectMapper;
    private DocumentRepository documentRepository;

    private JdbcClient.StatementSpec statementSpec;
    private JdbcClient.MappedQuerySpec<DocumentDetails> querySpec;
    private JdbcClient.MappedQuerySpec<Long> countQuerySpec;
    private JdbcClient.MappedQuerySpec<String> stringQuerySpec;

    @SuppressWarnings("unchecked")
    @BeforeEach
    public void setUp() {
        jdbcClient = mock(JdbcClient.class);
        objectMapper = new ObjectMapper();
        documentRepository = new DocumentRepository(jdbcClient, objectMapper,
            mock(JdbcTemplate.class), mock(CollectionIdResolver.class));

        statementSpec = mock(JdbcClient.StatementSpec.class);
        querySpec =
            (JdbcClient.MappedQuerySpec<DocumentDetails>) mock(JdbcClient.MappedQuerySpec.class);
        countQuerySpec = (JdbcClient.MappedQuerySpec<Long>) mock(JdbcClient.MappedQuerySpec.class);
        stringQuerySpec =
            (JdbcClient.MappedQuerySpec<String>) mock(JdbcClient.MappedQuerySpec.class);

        when(jdbcClient.sql(anyString())).thenReturn(statementSpec);
        when(statementSpec.params(anyMap())).thenReturn(statementSpec);
        when(statementSpec.param(anyString(), any())).thenReturn(statementSpec);
        when(statementSpec.query(any(RowMapper.class))).thenReturn(querySpec);
        when(statementSpec.query(Long.class)).thenReturn(countQuerySpec);
        when(statementSpec.query(String.class)).thenReturn(stringQuerySpec);
    }

    @Test
    public void testListDocuments() {
        DocumentDetails mockDoc = new DocumentDetails(UUID.randomUUID(), UUID.randomUUID(), "OIM",
            "manual", "Title", Collections.emptyMap(), "completed", 10L, false, 0L,
            Collections.emptyList(), Instant.now());
        when(querySpec.list()).thenReturn(List.of(mockDoc));

        List<DocumentDetails> result = documentRepository.listDocuments(null, 10, 0, "manual");

        assertThat(result).hasSize(1);
        verify(jdbcClient).sql(contains("FROM documents d"));
    }

    @Test
    public void testCountDocuments() {
        when(countQuerySpec.single()).thenReturn(5L);

        long count = documentRepository.countDocuments("OIM", "9.3", "manual");

        assertThat(count).isEqualTo(5L);
        verify(jdbcClient).sql(contains("SELECT count(*)"));
    }

    @Test
    public void testListDocumentsWithSearchId() {
        DocumentDetails mockDoc = new DocumentDetails(UUID.randomUUID(), UUID.randomUUID(), "OIM",
            "manual", "Title", Collections.emptyMap(), "completed", 10L, false, 0L,
            Collections.emptyList(), Instant.now());
        when(querySpec.list()).thenReturn(List.of(mockDoc));

        List<DocumentDetails> result =
            documentRepository.listDocuments(null, 10, 0, null, null, "273cb8a2");

        assertThat(result).hasSize(1);
        verify(jdbcClient).sql(contains("AND CAST(d.id AS text) ILIKE :searchId"));
    }

    @Test
    public void testCountDocumentsWithSearchId() {
        when(countQuerySpec.single()).thenReturn(1L);

        long count = documentRepository.countDocuments(null, null, null, "273cb8a2", null);

        assertThat(count).isEqualTo(1L);
        verify(jdbcClient).sql(contains("AND CAST(d.id AS text) ILIKE :searchId"));
    }

    @Test
    public void testFindDistinctCollections() {
        when(stringQuerySpec.list()).thenReturn(List.of("OIM", "OIM-DB"));

        List<String> collections = documentRepository.findDistinctCollections();

        assertThat(collections).containsExactly("OIM", "OIM-DB");
        verify(jdbcClient).sql(contains("SELECT DISTINCT name FROM collections"));
    }

    @Test
    public void testListDocumentsWithSearchTitle() {
        DocumentDetails mockDoc = new DocumentDetails(UUID.randomUUID(), UUID.randomUUID(), "OIM",
            "manual", "Title", Collections.emptyMap(), "completed", 10L, false, 0L,
            Collections.emptyList(), Instant.now());
        when(querySpec.list()).thenReturn(List.of(mockDoc));

        List<DocumentDetails> result =
            documentRepository.listDocuments(null, 10, 0, null, null, null, "My Manual Title");

        assertThat(result).hasSize(1);
        verify(jdbcClient).sql(contains("AND d.title ILIKE :searchTitle"));
    }

    @Test
    public void testCountDocumentsWithSearchTitle() {
        when(countQuerySpec.single()).thenReturn(2L);

        long count = documentRepository.countDocuments(null, null, null, null, "My Manual Title");

        assertThat(count).isEqualTo(2L);
        verify(jdbcClient).sql(contains("AND d.title ILIKE :searchTitle"));
    }
}
