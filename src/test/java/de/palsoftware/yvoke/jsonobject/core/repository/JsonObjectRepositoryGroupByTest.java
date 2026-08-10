package de.palsoftware.yvoke.jsonobject.core.repository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import java.util.List;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient.MappedQuerySpec;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;

/**
 * The grouped-count field is concatenated into {@code data->>'…'} because the right side of that
 * operator cannot be parameterized. Stripping unsafe characters keeps it injection-safe but used to
 * rewrite a nested path into a different, non-existent key — returning one {@code (null)} bucket
 * whose count equalled the whole collection. The strip must therefore reject, not correct.
 */
public class JsonObjectRepositoryGroupByTest {

    private JsonObjectRepository repository;

    @BeforeEach
    public void setUp() {
        // The guard runs before any query is issued, so the collaborators are never touched.
        repository = new JsonObjectRepository(mock(JdbcClient.class), mock(JdbcTemplate.class),
            new ObjectMapper());
    }

    private void assertRejected(String field) {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> repository.countGroupedByJsonPath(UUID.randomUUID(), null, field, null),
            "expected '" + field + "' to be rejected");
        assertTrue(ex.getMessage().contains(field),
            "message must quote the offending field: " + ex.getMessage());
    }

    @Test
    public void testNestedPathIsRejected() {
        assertRejected("Customer.name");
    }

    @Test
    public void testSpacedKeyIsRejected() {
        assertRejected("Server Edition");
    }

    @Test
    public void testQuotedKeyIsRejected() {
        assertRejected("SystemOverview.\"CPU Count Cores Total\"");
    }

    /**
     * A {@code $}-prefixed query in the admin browser (and in {@code query_json_objects}) is routed
     * by {@code JsonObjectService} to the three jsonpath methods below, and all three must express
     * the filter as the {@code @?} OPERATOR. {@code jsonb_path_exists(data, ...)} is the same
     * predicate written as a function call and returns byte-identical rows, so every behavioural
     * test in the suite stays green — but it is not an indexable operator expression, so the
     * {@code jsonb_ops} GIN index on {@code json_objects.data} stops being usable and each filtered
     * page becomes a sequential scan plus a per-row jsonpath evaluation over a corpus that holds
     * tens of thousands of objects. The failure is a timeout under load, on the exact query shape
     * an agent uses most, with no error and no diff-visible clue: the two forms look equivalent and
     * one of them reads like the more idiomatic Postgres.
     *
     * <p>
     * The operator is also easy to break by accident in the opposite direction: it is written
     * {@code @??} in the source because {@code ?} is the JDBC placeholder escape (PgJDBC unescapes
     * {@code ??} to a literal {@code ?}), so "fixing the typo" to a single {@code ?} turns the rest
     * of the statement into positional parameters. Asserting on the SQL text is the only way to pin
     * either mistake; the rows come back the same regardless, which is precisely why no existing
     * test — including {@code JsonObjectRepositoryIT}, which exercises these methods against a real
     * database — would notice.
     */
    @Test
    public void theJsonPathFilterUsesTheIndexableContainmentOperator() {
        JdbcClient jdbcClient = mock(JdbcClient.class);
        StatementSpec statement = mock(StatementSpec.class, RETURNS_SELF);
        @SuppressWarnings("unchecked")
        MappedQuerySpec<Long> countSpec = mock(MappedQuerySpec.class);
        @SuppressWarnings("unchecked")
        MappedQuerySpec<Object> rowSpec = mock(MappedQuerySpec.class);
        when(jdbcClient.sql(anyString())).thenReturn(statement);
        when(statement.query(Long.class)).thenReturn(countSpec);
        when(statement.query(ArgumentMatchers.<RowMapper<Object>>any())).thenReturn(rowSpec);
        when(countSpec.single()).thenReturn(0L);
        when(rowSpec.list()).thenReturn(List.of());

        JsonObjectRepository pathRepository =
            new JsonObjectRepository(jdbcClient, mock(JdbcTemplate.class), new ObjectMapper());
        UUID collectionId = UUID.randomUUID();
        String jsonPath = "$.category ? (@ == \"Sync\")";

        // Every entry point a $-prefixed search reaches: the page, its pager, and the breakdown.
        pathRepository.queryByJsonPath(collectionId, jsonPath, null, 25, 0);
        pathRepository.countByJsonPath(collectionId, jsonPath, null);
        pathRepository.countGroupedByJsonPath(collectionId, jsonPath, "category", null);

        ArgumentCaptor<String> issued = ArgumentCaptor.forClass(String.class);
        verify(jdbcClient, times(3)).sql(issued.capture());
        for (String sql : issued.getAllValues()) {
            assertTrue(sql.contains("data @?? :"),
                "the jsonpath filter must stay the GIN-usable @? operator: " + sql);
            assertFalse(sql.contains("jsonb_path_exists"),
                "jsonb_path_exists() is not indexable and forces a sequential scan: " + sql);
        }
    }

    @Test
    public void testQuoteInjectionAttemptIsRejected() {
        assertRejected("x' || (select 1) || '");
    }
}
