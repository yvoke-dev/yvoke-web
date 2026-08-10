package de.palsoftware.yvoke.rag.retrieval;


import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.junit.jupiter.api.Assertions;

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

    /**
     * S4.4 + S4.14. The two lanes filter tags on DIFFERENT columns, and only one of them re-sorts
     * its page. Both facts are invisible in every existing assertion in this file.
     *
     * <p>
     * The BM25 lane filters {@code ch.tags} with the {@code ===} literal-term operator, OR-ed
     * across the requested tags. That is not interchangeable with the semantic lane's
     * {@code unnest(d.tags)} EXISTS predicate: {@code ===} is the only form the {@code pdb.literal}
     * index can serve, so re-pointing the lane at the document's tags turns an index probe into a
     * full scan of the partition, and it silently re-scopes any chunk whose own tags differ from
     * its document's. {@code testFindFulltextCandidates} asserts only the ABSENCE of
     * {@code ch.tags ===} when no tags are passed, and every IT fixture writes the same value into
     * {@code chunks.tags} and {@code documents.tags} — so substituting one for the other breaks no
     * assertion anywhere and no IT can tell.
     *
     * <p>
     * The semantic lane runs under {@code hnsw.iterative_scan = relaxed_order}, which may emit
     * index rows slightly out of distance order, so the ANN query is WRAPPED and the outer select
     * re-sorts by {@code cosine_similarity DESC}. Callers use row POSITION as the semantic rank and
     * RRF fuses on exactly that rank, so losing the wrapper produces no error and no missing row:
     * it feeds fusion ranks that are wrong by a place or two, which surfaces only as slightly worse
     * answers. {@code testFindSemanticCandidates} asserts the SQL contains "cosine_similarity",
     * which the inner column ALIAS already satisfies, so the wrapper could be deleted with this
     * whole file green.
     *
     * <p>
     * The wrapper's PLACEMENT is asserted too: {@code LIMIT}/{@code OFFSET} must stay inside the
     * subquery, because the outer sort only corrects order within the page it was handed — hoisting
     * the limit outside would change which rows the page contains, not merely their order.
     */
    @Test
    public void theBm25LaneScopesTagsOnTheChunkWhileTheSemanticLaneReSortsItsPage() {
        when(querySpec.list()).thenReturn(Collections.emptyList());

        chunkRepository.findFulltextCandidates("person table", 5, 0, List.of("9.3.1", "10.0"),
            List.of("OIM"));
        chunkRepository.findSemanticCandidates("[0.1,0.2]", 5, 0, List.of("9.3.1", "10.0"),
            List.of("OIM"));

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcClient, atLeastOnce()).sql(sqlCaptor.capture());
        String ftSql = sqlCaptor.getAllValues().stream().filter(s -> s.contains("pdb.score"))
            .findFirst().orElseThrow();
        String semSql = sqlCaptor.getAllValues().stream()
            .filter(s -> s.contains("cosine_similarity")).findFirst().orElseThrow();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(statementSpec, atLeast(2)).params(paramsCaptor.capture());
        Map<String, Object> ftParams = paramsCaptor.getAllValues().get(0);
        Map<String, Object> semParams = paramsCaptor.getAllValues().get(1);

        // BM25: the CHUNK's own tags, term-matched and OR-ed — the only form pdb.literal indexes.
        assertThat(ftSql).contains("(ch.tags === :tag0 OR ch.tags === :tag1)");
        assertThat(ftSql).as("the BM25 lane must not borrow the document-level tag predicate")
            .doesNotContain("unnest(d.tags)");
        assertThat(ftParams).containsEntry("tag0", "9.3.1").containsEntry("tag1", "10.0");

        // Semantic: the DOCUMENT's tags, and never the literal-term operator.
        assertThat(semSql).contains("unnest(d.tags)");
        assertThat(semSql).doesNotContain("ch.tags ===");
        assertThat(semParams).containsEntry("tags", List.of("9.3.1", "10.0"));

        String flat = semSql.replaceAll("\\s+", " ");
        assertThat(flat)
            .as("relaxed_order may emit rows out of distance order; the outer sort "
                + "restores the strict ranking callers read as the semantic rank")
            .contains(") candidates ORDER BY cosine_similarity DESC");
        assertThat(flat.indexOf("LIMIT :limit"))
            .as("the page must be taken INSIDE the subquery — the outer sort only reorders it")
            .isLessThan(flat.indexOf(") candidates ORDER BY"));
    }

    /**
     * A search scoped to named collections that resolve to ZERO ids MUST return empty — it must
     * never fall through to an unscoped query across the whole corpus. The two lanes guard this
     * separately ({@code ChunkRepository:48} for the vector lane, {@code :99-101} for BM25), and
     * the BM25 one is the fragile shape: it builds its predicates conditionally, so dropping the
     * guard simply omits the {@code collection_id} filter and silently widens the search to every
     * collection instead of erroring. Callers rely on this because names resolve case-sensitively.
     */
    @SuppressWarnings("unchecked")
    @Test
    public void namedCollectionsResolvingToZeroIdsReturnEmptyNotTheWholeCorpus() {
        JdbcClient.MappedQuerySpec<UUID> noIds = mock(JdbcClient.MappedQuerySpec.class);
        when(statementSpec.query(UUID.class)).thenReturn(noIds);
        when(noIds.list()).thenReturn(List.of());

        assertThat(chunkRepository.findSemanticCandidates("[0.1,0.2]", 10, 0, List.of(),
            List.of("no-such-collection"))).isEmpty();
        assertThat(chunkRepository.findFulltextCandidates("query", 10, 0, List.of(),
            List.of("no-such-collection"))).isEmpty();

        // The decisive assertion: neither lane may have executed a chunk query at all. If a lane
        // ran one, it ran it WITHOUT a collection predicate — i.e. over the entire corpus.
        verify(statementSpec, never()).query(any(RowMapper.class));
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
    public void testChunkRowMapperSuccess() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        Array mockSqlArray = mock(Array.class);

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
    public void testChunkRowMapperNullHeadingPathAndMetadata() throws SQLException {
        ResultSet rs = mock(ResultSet.class);

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
    public void testChunkRowMapperMetadataParsingException() throws SQLException {
        ResultSet rs = mock(ResultSet.class);

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

    /**
     * A prefix that matches more than one chunk MUST throw and name every candidate — it must never
     * resolve to "whichever row Postgres happened to return first". The query is an unordered
     * {@code LIKE :prefix%} with no {@code ORDER BY}, so the winner of a silent pick is whatever
     * the plan produced that day. Prefix resolution is exactly what the MCP {@code get_section}
     * path and {@code citation-render.js} linkification stand on: an ambiguous prefix would then
     * quote and cite a DIFFERENT chunk's text under an id that looks real and resolves cleanly, so
     * nothing — not the answer, not {@code verify_citations} (which only checks that an id exists)
     * — reports anything wrong.
     *
     * <p>
     * {@code testFindByIdPrefixValidation} only ever stubs an EMPTY result list, so the
     * more-than-one branch has never executed in any test: widening its condition, or replacing the
     * throw with {@code matches.get(0)}, keeps this whole file green.
     */
    @Test
    public void anAmbiguousChunkIdPrefixThrowsAndEnumeratesTheCandidatesInsteadOfPickingOne() {
        // Two real chunks sharing the 8-hex-character prefix an agent typically cites.
        UUID first = UUID.fromString("a1b2c3d4-1111-4111-8111-000000000001");
        UUID second = UUID.fromString("a1b2c3d4-2222-4222-8222-000000000002");
        ChunkRow one = new ChunkRow(first, UUID.randomUUID(), "german text",
            Collections.emptyList(), "Vererbung", 1, 0, "9.3", "inheritance-de.md", "manual", "OIM",
            Collections.emptyMap(), 0.0);
        ChunkRow two = new ChunkRow(second, UUID.randomUUID(), "english text",
            Collections.emptyList(), "Inheritance", 1, 0, "10.0", "inheritance-en.md", "manual",
            "OIM", Collections.emptyMap(), 0.0);
        when(querySpec.list()).thenReturn(List.of(one, two));

        assertThatThrownBy(() -> chunkRepository.findByIdPrefix("a1b2c3d4"))
            .isInstanceOf(IllegalArgumentException.class)
            .as("an ambiguous prefix must fail loudly rather than resolve to one of the rows")
            .hasMessageContaining("a1b2c3d4").hasMessageContaining("2 matches")
            // Both candidates must be enumerated: the caller can only disambiguate by being told
            // which documents/headings the prefix collided across.
            .hasMessageContaining("inheritance-de.md").hasMessageContaining("Vererbung")
            .hasMessageContaining("inheritance-en.md").hasMessageContaining("Inheritance");
    }

    @Test
    public void testFindByIdPrefixValidation() {
        // Test null prefix
        Assertions.assertThrows(IllegalArgumentException.class, () -> {
            chunkRepository.findByIdPrefix(null);
        });

        // Test too short prefix (less than 8 characters but not empty)
        Assertions.assertThrows(IllegalArgumentException.class, () -> {
            chunkRepository.findByIdPrefix("1234567");
        });

        // Test invalid characters prefix
        Assertions.assertThrows(IllegalArgumentException.class, () -> {
            chunkRepository.findByIdPrefix("1234567g"); // 'g' is not valid hex
        });

        Assertions.assertThrows(IllegalArgumentException.class, () -> {
            chunkRepository.findByIdPrefix("1234-56%"); // '%' is sql wildcard
        });

        // Test valid prefix (empty string is allowed, triggers no validation exception)
        try {
            when(querySpec.list()).thenReturn(Collections.emptyList());
            chunkRepository.findByIdPrefix("");
        } catch (IllegalArgumentException e) {
            Assertions.fail("Empty prefix should be allowed");
        }

        // Test valid prefix (8 characters hex)
        try {
            when(querySpec.list()).thenReturn(Collections.emptyList());
            chunkRepository.findByIdPrefix("a1b2c3d4");
        } catch (IllegalArgumentException e) {
            Assertions.fail("Valid hex prefix of 8 characters should not throw exception");
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
