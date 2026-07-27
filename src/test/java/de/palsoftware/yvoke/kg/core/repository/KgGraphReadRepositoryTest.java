package de.palsoftware.yvoke.kg.core.repository;

import de.palsoftware.yvoke.kg.core.model.KgCall;
import de.palsoftware.yvoke.kg.core.model.KgEntity;
import de.palsoftware.yvoke.kg.core.model.KgNeighborhood;
import de.palsoftware.yvoke.kg.core.model.KgRelationship;
import de.palsoftware.yvoke.kg.core.model.KgWalk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.shared.db.CollectionIdResolver;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

@SuppressWarnings("unchecked")
public class KgGraphReadRepositoryTest {

    private JdbcClient jdbcClient;
    private ObjectMapper objectMapper;
    private CollectionIdResolver collectionIdResolver;
    private KgGraphReadRepository kgRepository;

    private JdbcClient.StatementSpec statementSpec;
    private JdbcClient.MappedQuerySpec<KgEntity> entityQuerySpec;
    private JdbcClient.MappedQuerySpec<KgRelationship> relQuerySpec;
    private JdbcClient.MappedQuerySpec<KgCall> callQuerySpec;
    private JdbcClient.MappedQuerySpec<KgWalk> fkWalkQuerySpec;
    private JdbcClient.MappedQuerySpec<Long> countQuerySpec;

    @BeforeEach
    public void setUp() {
        jdbcClient = mock(JdbcClient.class);
        objectMapper = new ObjectMapper();
        collectionIdResolver = mock(CollectionIdResolver.class);
        when(collectionIdResolver.findId(anyString())).thenReturn(Optional.of(UUID.randomUUID()));
        kgRepository = new KgGraphReadRepository(jdbcClient, objectMapper, collectionIdResolver);

        statementSpec = mock(JdbcClient.StatementSpec.class);
        entityQuerySpec =
            (JdbcClient.MappedQuerySpec<KgEntity>) mock(JdbcClient.MappedQuerySpec.class);
        relQuerySpec =
            (JdbcClient.MappedQuerySpec<KgRelationship>) mock(JdbcClient.MappedQuerySpec.class);
        callQuerySpec = (JdbcClient.MappedQuerySpec<KgCall>) mock(JdbcClient.MappedQuerySpec.class);
        fkWalkQuerySpec =
            (JdbcClient.MappedQuerySpec<KgWalk>) mock(JdbcClient.MappedQuerySpec.class);
        countQuerySpec = (JdbcClient.MappedQuerySpec<Long>) mock(JdbcClient.MappedQuerySpec.class);

        when(jdbcClient.sql(anyString())).thenReturn(statementSpec);
        when(statementSpec.params(anyMap())).thenReturn(statementSpec);
        when(statementSpec.param(anyString(), any())).thenReturn(statementSpec);
        when(statementSpec.query(any(RowMapper.class))).thenReturn(entityQuerySpec);

        JdbcClient.MappedQuerySpec<Object> objectQuerySpec =
            (JdbcClient.MappedQuerySpec<Object>) mock(JdbcClient.MappedQuerySpec.class);
        when(statementSpec.query(any(Class.class))).thenReturn(objectQuerySpec);
        when(objectQuerySpec.optional()).thenReturn(Optional.of(UUID.randomUUID()));
        when(objectQuerySpec.single()).thenReturn(1L);
    }

    // --- H-2: escapeLike ---

    @Test
    public void testEscapeLikePlainString() {
        assertThat(KgGraphReadRepository.escapeLike("hello")).isEqualTo("hello");
    }

    @Test
    public void testEscapeLikeWithWildcards() {
        assertThat(KgGraphReadRepository.escapeLike("100%")).isEqualTo("100\\%");
        assertThat(KgGraphReadRepository.escapeLike("col_name")).isEqualTo("col\\_name");
        assertThat(KgGraphReadRepository.escapeLike("back\\slash")).isEqualTo("back\\\\slash");
    }

    @Test
    public void testEscapeLikeCombined() {
        assertThat(KgGraphReadRepository.escapeLike("%_\\")).isEqualTo("\\%\\_\\\\");
    }

    @Test
    public void testFuzzySearchEntitiesUsesEscapedLike() {
        KgEntity mockRow = new KgEntity(UUID.randomUUID(), "OIM-DB", "TableA", "table", "9.3",
            "Desc", Collections.emptyMap(), 0.9);
        when(entityQuerySpec.list()).thenReturn(List.of(mockRow));

        kgRepository.fuzzySearchEntities("Table%A", 10, "9.3", "OIM-DB");

        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(statementSpec).params(paramsCaptor.capture());
        // The likeName param must have the % escaped
        assertThat(paramsCaptor.getValue().get("likeName")).isEqualTo("%Table\\%A%");
    }

    @Test
    public void testFuzzySearchEntitiesSqlContainsEscape() {
        KgEntity mockRow = new KgEntity(UUID.randomUUID(), "OIM-DB", "TableA", "table", "9.3",
            "Desc", Collections.emptyMap(), 0.9);
        when(entityQuerySpec.list()).thenReturn(List.of(mockRow));

        kgRepository.fuzzySearchEntities("TableA", 10, "9.3", "OIM-DB");

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcClient, atLeastOnce()).sql(sqlCaptor.capture());
        assertThat(sqlCaptor.getValue()).contains("ESCAPE '\\'");
        assertThat(sqlCaptor.getValue()).contains("similarity");
        assertThat(sqlCaptor.getValue()).contains(":tag = ANY(");
    }

    @Test
    public void testFuzzySearchOrdersDeterministicallyOnTies() {
        when(entityQuerySpec.list()).thenReturn(Collections.emptyList());

        kgRepository.fuzzySearchEntities("Person", 10, "9.3", "OIM-DB");

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcClient, atLeastOnce()).sql(sqlCaptor.capture());
        // Same-named rows tie on similarity AND length: kind breaks the tie, and since identity
        // became tag-scoped two rows can tie on kind too, so tags and id must finish the order.
        // Without a TOTAL order, repeated identical calls return a different row — and a different
        // document_id — first.
        // Compared with whitespace collapsed — the clause wraps, and its indentation is not the
        // contract.
        assertThat(sqlCaptor.getValue().replaceAll("\\s+", " "))
            .contains("ORDER BY similarity DESC, length(e.name) ASC, lower(e.name) ASC, "
                + "coalesce(e.kind, '') ASC, e.tags ASC, e.id ASC");
    }

    @Test
    public void testListEntitiesHasTotalOrder() {
        when(entityQuerySpec.list()).thenReturn(Collections.emptyList());

        kgRepository.listEntities("OIM-DB", "9.3", null, 10, 0);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcClient, atLeastOnce()).sql(sqlCaptor.capture());
        // tags sits before id: two rows differing only by product version must sort adjacently and
        // stably, or a pair straddles a page boundary and looks like two unrelated entities.
        assertThat(sqlCaptor.getValue())
            .contains("ORDER BY e.name ASC, coalesce(e.kind, '') ASC, e.tags ASC, e.id ASC");
    }

    @Test
    public void testFindEntityKindsWithEdgeCountsWhitelistsDirection() {
        when(entityQuerySpec.list()).thenReturn(Collections.emptyList());

        kgRepository.findEntityKindsWithEdgeCounts("Person", "9.3", "OIM-DB", "fk_to",
            "outgoing; DROP TABLE entities");

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcClient, atLeastOnce()).sql(sqlCaptor.capture());
        String sql = sqlCaptor.getValue();
        // An unknown direction falls back to the 'both' clause; it is never interpolated.
        assertThat(sql).doesNotContain("DROP TABLE");
        assertThat(sql).contains("(r.subject_id = e.id OR r.object_id = e.id)");
        // The predicate is bound, not concatenated.
        assertThat(sql).contains("lower(r.predicate) = lower(:predicate)");
        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(statementSpec).params(paramsCaptor.capture());
        assertThat(paramsCaptor.getValue()).containsEntry("predicate", "fk_to").containsEntry("tag",
            "9.3");
    }

    // --- Tag clause helper tests ---

    @Test
    public void testTcWithAllowedAlias() {
        assertThat(KgGraphReadRepository.tc("9.3", "r")).contains(":tag = ANY(r.tags)");
        assertThat(KgGraphReadRepository.tc("9.3", "e")).contains(":tag = ANY(e.tags)");
        assertThat(KgGraphReadRepository.tc("9.3", "c")).contains(":tag = ANY(c.tags)");
        assertThat(KgGraphReadRepository.tc("9.3", "d")).contains(":tag = ANY(d.tags)");
    }

    @Test
    public void testTcWithNullTag() {
        assertThat(KgGraphReadRepository.tc(null, "r")).isEqualTo(" ");
    }

    @Test
    public void testTcWithInvalidAliasThrows() {
        assertThatThrownBy(() -> KgGraphReadRepository.tc("9.3", "x"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Invalid SQL alias: x");
    }

    // --- H-3: Limit capping ---

    @Test
    public void testFuzzySearchLimitCapping() {
        when(entityQuerySpec.list()).thenReturn(Collections.emptyList());

        kgRepository.fuzzySearchEntities("test", 500, null, "OIM-DB");

        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(statementSpec).params(paramsCaptor.capture());
        assertThat(paramsCaptor.getValue().get("limit")).isEqualTo(200);
    }

    @Test
    public void testFuzzySearchLimitFloor() {
        when(entityQuerySpec.list()).thenReturn(Collections.emptyList());

        kgRepository.fuzzySearchEntities("test", -5, null, "OIM-DB");

        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(statementSpec).params(paramsCaptor.capture());
        assertThat(paramsCaptor.getValue().get("limit")).isEqualTo(1);
    }

    @Test
    public void testGetProcsLimitCapped() {
        when(entityQuerySpec.list()).thenReturn(Collections.emptyList());

        kgRepository.getProcsForTable("Table", 999, null, "OIM-DB");

        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(statementSpec).params(paramsCaptor.capture());
        assertThat(paramsCaptor.getValue().get("limit")).isEqualTo(200);
    }

    @Test
    public void testGetCallsLimitCapped() {
        when(statementSpec.query(any(RowMapper.class))).thenReturn(callQuerySpec);
        when(callQuerySpec.list()).thenReturn(Collections.emptyList());

        kgRepository.getCalls("ProcA", "callers", 300, null, "OIM-DB");

        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(statementSpec).params(paramsCaptor.capture());
        assertThat(paramsCaptor.getValue().get("limit")).isEqualTo(200);
    }

    // --- M-8: KgEntityMapper with includeSimilarity flag ---

    @Test
    public void testEntityRowMapperWithSimilarity() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        Array mockArray = mock(Array.class);
        when(rs.getObject("id", UUID.class)).thenReturn(UUID.randomUUID());
        when(rs.getObject("collection_id", UUID.class)).thenReturn(UUID.randomUUID());
        when(rs.getString("collection")).thenReturn("OIM-DB");
        when(rs.getString("name")).thenReturn("TableA");
        when(rs.getString("kind")).thenReturn("table");
        when(rs.getArray("tags")).thenReturn(mockArray);
        when(mockArray.getArray()).thenReturn(new String[] {"9.3"});
        when(rs.getString("description")).thenReturn("Desc");
        when(rs.getString("metadata")).thenReturn("{\"foo\":\"bar\"}");
        when(rs.getDouble("similarity")).thenReturn(0.75);

        KgEntityMapper mapper = new KgEntityMapper(objectMapper, true);
        KgEntity row = mapper.mapRow(rs, 1);

        assertThat(row).isNotNull();
        assertThat(row.name()).isEqualTo("TableA");
        assertThat(row.metadata()).containsEntry("foo", "bar");
        assertThat(row.similarity()).isEqualTo(0.75);
    }

    @Test
    public void testEntityRowMapperWithoutSimilarity() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        Array mockArray = mock(Array.class);
        when(rs.getObject("id", UUID.class)).thenReturn(UUID.randomUUID());
        when(rs.getObject("collection_id", UUID.class)).thenReturn(UUID.randomUUID());
        when(rs.getString("collection")).thenReturn("OIM-DB");
        when(rs.getString("name")).thenReturn("TableA");
        when(rs.getString("kind")).thenReturn("table");
        when(rs.getArray("tags")).thenReturn(mockArray);
        when(mockArray.getArray()).thenReturn(new String[] {"9.3"});
        when(rs.getString("description")).thenReturn("Desc");
        when(rs.getString("metadata")).thenReturn("{}");

        KgEntityMapper mapper = new KgEntityMapper(objectMapper, false);
        KgEntity row = mapper.mapRow(rs, 1);

        assertThat(row).isNotNull();
        assertThat(row.name()).isEqualTo("TableA");
        assertThat(row.similarity()).isNull();
        // Verify similarity column was never read
        verify(rs, never()).getDouble("similarity");
    }

    @Test
    public void testEntityRowMapperNullFields() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        // Standard columns are always present in SELECT; they return SQL NULL (not a
        // missing column).
        when(rs.getObject("id", UUID.class)).thenReturn(null);
        when(rs.getObject("collection_id", UUID.class)).thenReturn(null);
        when(rs.getString("name")).thenReturn("TableA");
        when(rs.getString("collection")).thenReturn(null);
        when(rs.getString("kind")).thenReturn(null);
        when(rs.getArray("tags")).thenReturn(null);
        when(rs.getString("description")).thenReturn(null);
        when(rs.getString("metadata")).thenReturn(null);

        KgEntityMapper mapper = new KgEntityMapper(objectMapper, false);
        KgEntity row = mapper.mapRow(rs, 1);

        assertThat(row).isNotNull();
        assertThat(row.name()).isEqualTo("TableA");
        assertThat(row.id()).isNull();
        assertThat(row.collection()).isNull();
        assertThat(row.category()).isNull();
        assertThat(row.tag()).isNull();
        assertThat(row.description()).isNull();
        assertThat(row.metadata()).isEmpty();
        assertThat(row.similarity()).isNull();
    }

    // --- Existing functionality tests (updated for new API) ---

    @Test
    public void testFuzzySearchEntities() {
        KgEntity mockRow = new KgEntity(UUID.randomUUID(), "OIM-DB", "TableA", "table", "9.3",
            "Desc", Collections.emptyMap(), 0.9);
        when(entityQuerySpec.list()).thenReturn(List.of(mockRow));

        List<KgEntity> results = kgRepository.fuzzySearchEntities("TableA", 10, "9.3", "OIM-DB");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).name()).isEqualTo("TableA");

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcClient, atLeastOnce()).sql(sqlCaptor.capture());
        assertThat(sqlCaptor.getValue()).contains("similarity");
        assertThat(sqlCaptor.getValue()).contains(":tag = ANY(");
    }

    @Test
    public void testGetNeighborhoodFound() {
        KgEntity mockEntity = new KgEntity(UUID.randomUUID(), "OIM-DB", "TableA", "table", "9.3",
            "Desc", Collections.emptyMap(), null);

        // Mocking the cascading query calls
        when(statementSpec.query(any(RowMapper.class))).thenReturn(entityQuerySpec) // 1. Fetch
                                                                                    // Entity
            .thenReturn(relQuerySpec) // 2. Fetch FKs Out
            .thenReturn(relQuerySpec); // 3. Fetch FKs In

        when(entityQuerySpec.list()).thenReturn(List.of(mockEntity));

        KgRelationship mockFkOut =
            new KgRelationship(UUID.randomUUID(), "OIM-DB", "TableA", "fk_to", "TableB",
                UUID.randomUUID(), UUID.randomUUID(), "9.3", "FK", Collections.emptyMap());
        when(relQuerySpec.list()).thenReturn(List.of(mockFkOut)) // fksOut
            .thenReturn(Collections.emptyList()); // fksIn

        KgNeighborhood neighborhood = kgRepository.getNeighborhood("TableA", "9.3", "OIM-DB");

        assertThat(neighborhood).isNotNull();
        assertThat(neighborhood.entity().name()).isEqualTo("TableA");
        assertThat(neighborhood.outgoing()).hasSize(1);
        assertThat(neighborhood.outgoing().get(0).object()).isEqualTo("TableB");
        assertThat(neighborhood.incoming()).isEmpty();
    }

    @Test
    public void testGetNeighborhoodGracefulDegradation() {
        // 1. Fetch Entity returns empty
        when(entityQuerySpec.list()).thenReturn(Collections.emptyList());

        // Mock checkEdges count query
        when(statementSpec.query(any(RowMapper.class))).thenReturn(countQuerySpec);
        when(countQuerySpec.optional()).thenReturn(Optional.of(5L));

        // Mock subsequent relationship list queries
        // Reset query mapping mock to return relQuerySpec after the count check
        doAnswer(invocation -> {
            RowMapper<?> mapper = invocation.getArgument(0);
            if (mapper instanceof KgRelationshipMapper) {
                return relQuerySpec;
            }
            return countQuerySpec;
        }).when(statementSpec).query(any(RowMapper.class));

        when(relQuerySpec.list()).thenReturn(Collections.emptyList());

        KgNeighborhood neighborhood = kgRepository.getNeighborhood("MissingTable", "9.3", "OIM-DB");

        assertThat(neighborhood).isNotNull();
        assertThat(neighborhood.entity().name()).isEqualTo("MissingTable");
        assertThat(neighborhood.entity().description()).contains("no entity node in the graph");
    }

    @Test
    public void testGetNeighborhoodNotFoundAtAll() {
        when(entityQuerySpec.list()).thenReturn(Collections.emptyList());

        when(statementSpec.query(any(RowMapper.class))).thenReturn(countQuerySpec);
        when(countQuerySpec.optional()).thenReturn(Optional.of(0L));

        KgNeighborhood neighborhood = kgRepository.getNeighborhood("MissingTable", "9.3", "OIM-DB");

        assertThat(neighborhood).isNull();
    }

    @Test
    public void testGetProcsForTable() {
        KgEntity mockRow = new KgEntity(UUID.randomUUID(), "OIM-DB", "ProcA", "procedure", "9.3",
            "Proc Desc", Collections.emptyMap(), null);
        when(entityQuerySpec.list()).thenReturn(List.of(mockRow));

        List<KgEntity> results = kgRepository.getProcsForTable("TableA", 10, "9.3", "OIM-DB");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).name()).isEqualTo("ProcA");
        verify(jdbcClient, atLeastOnce()).sql(contains("references_table"));
    }

    @Test
    public void testGetCallsCallers() {
        when(statementSpec.query(any(RowMapper.class))).thenReturn(callQuerySpec);
        KgCall mockCall = new KgCall("ProcA", "procedure", "Desc", "caller");
        when(callQuerySpec.list()).thenReturn(List.of(mockCall));

        List<KgCall> results = kgRepository.getCalls("ProcB", "callers", 10, "9.3", "OIM-DB");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).name()).isEqualTo("ProcA");
        assertThat(results.get(0).relationType()).isEqualTo("caller");
        verify(jdbcClient, atLeastOnce()).sql(contains("lower(r.object) = lower(:name)"));
    }

    @Test
    public void testGetCallsCallees() {
        when(statementSpec.query(any(RowMapper.class))).thenReturn(callQuerySpec);
        KgCall mockCall = new KgCall("ProcB", "procedure", "Desc", "callee");
        when(callQuerySpec.list()).thenReturn(List.of(mockCall));

        List<KgCall> results = kgRepository.getCalls("ProcA", "callees", 10, "9.3", "OIM-DB");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).name()).isEqualTo("ProcB");
        assertThat(results.get(0).relationType()).isEqualTo("callee");
        verify(jdbcClient, atLeastOnce()).sql(contains("lower(r.subject) = lower(:name)"));
    }

    @Test
    public void testGetCallsBoth() {
        when(statementSpec.query(any(RowMapper.class))).thenReturn(callQuerySpec);
        KgCall callerRow = new KgCall("ProcA", "procedure", "Desc A", "caller");
        KgCall calleeRow = new KgCall("ProcC", "procedure", "Desc C", "callee");
        when(callQuerySpec.list()).thenReturn(List.of(callerRow, calleeRow));

        List<KgCall> results = kgRepository.getCalls("ProcB", "both", 10, null, "OIM-DB");

        assertThat(results).hasSize(2);
        verify(jdbcClient, atLeastOnce()).sql(contains("UNION ALL"));
    }

    @Test
    public void testGetCallsInvalidDirection() {
        assertThatThrownBy(() -> kgRepository.getCalls("ProcB", "invalid", 10, null, "OIM-DB"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Invalid direction");
    }

    @Test
    public void testGetFkWalk() {
        when(statementSpec.query(any(RowMapper.class))).thenReturn(fkWalkQuerySpec);
        KgWalk mockRow = new KgWalk(1, List.of("TableA", "→ TableB"));
        when(fkWalkQuerySpec.list()).thenReturn(List.of(mockRow));

        List<KgWalk> results = kgRepository.getFkWalk("TableA", 3, "9.3", "OIM-DB");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).depth()).isEqualTo(1);
        verify(jdbcClient, atLeastOnce()).sql(contains("WITH RECURSIVE walk"));
    }

    @Test
    public void testGetFkWalkDepthClamped() {
        when(statementSpec.query(any(RowMapper.class))).thenReturn(fkWalkQuerySpec);
        kgRepository.getFkWalk("TableA", 10, "9.3", "OIM-DB");

        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(statementSpec).params(paramsCaptor.capture());
        assertThat(paramsCaptor.getValue().get("hops")).isEqualTo(5); // Clamped to 5
    }

    @Test
    public void testRelationshipRowMapper() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        Array mockArray = mock(Array.class);
        UUID id = UUID.randomUUID();
        UUID subId = UUID.randomUUID();
        UUID objId = UUID.randomUUID();
        when(rs.getObject("id", UUID.class)).thenReturn(id);
        when(rs.getObject("collection_id", UUID.class)).thenReturn(UUID.randomUUID());
        when(rs.getString("collection")).thenReturn("OIM-DB");
        when(rs.getString("subject")).thenReturn("TableA");
        when(rs.getString("predicate")).thenReturn("fk_to");
        when(rs.getString("object")).thenReturn("TableB");
        when(rs.getObject("subject_id", UUID.class)).thenReturn(subId);
        when(rs.getObject("object_id", UUID.class)).thenReturn(objId);
        when(rs.getArray("tags")).thenReturn(mockArray);
        when(mockArray.getArray()).thenReturn(new String[] {"9.3"});
        when(rs.getString("description")).thenReturn("FK relation");
        when(rs.getString("metadata")).thenReturn("{\"constraint\":\"fk_01\"}");

        KgRelationshipMapper mapper = new KgRelationshipMapper(objectMapper);
        KgRelationship row = mapper.mapRow(rs, 1);

        assertThat(row).isNotNull();
        assertThat(row.id()).isEqualTo(id);
        assertThat(row.subject()).isEqualTo("TableA");
        assertThat(row.object()).isEqualTo("TableB");
        assertThat(row.metadata()).containsEntry("constraint", "fk_01");
    }

    @Test
    public void testFkWalkRowMapper() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        Array mockArray = mock(Array.class);
        when(rs.getInt("depth")).thenReturn(2);
        when(rs.getArray("path")).thenReturn(mockArray);
        when(mockArray.getArray()).thenReturn(new String[] {"TableA", "→ TableB", "→ TableC"});

        KgWalkMapper mapper = new KgWalkMapper();
        KgWalk row = mapper.mapRow(rs, 1);

        assertThat(row).isNotNull();
        assertThat(row.depth()).isEqualTo(2);
        assertThat(row.path()).containsExactly("TableA", "→ TableB", "→ TableC");
    }
}
