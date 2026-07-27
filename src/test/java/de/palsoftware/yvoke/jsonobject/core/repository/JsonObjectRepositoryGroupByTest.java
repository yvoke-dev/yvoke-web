package de.palsoftware.yvoke.jsonobject.core.repository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

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

    @Test
    public void testQuoteInjectionAttemptIsRejected() {
        assertRejected("x' || (select 1) || '");
    }
}
