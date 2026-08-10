package de.palsoftware.yvoke.shared.audit.repository;

import de.palsoftware.yvoke.shared.audit.model.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import com.fasterxml.jackson.databind.JsonMappingException;
import java.io.Closeable;

public class AuditLogRepositoryTest {

    private JdbcClient jdbcClient;
    private ObjectMapper objectMapper;
    private AuditLogRepository auditLogRepository;

    private JdbcClient.StatementSpec statementSpec;
    private JdbcClient.MappedQuerySpec<AuditLog> querySpec;
    private JdbcClient.MappedQuerySpec<Long> countQuerySpec;

    @SuppressWarnings("unchecked")
    @BeforeEach
    public void setUp() {
        jdbcClient = mock(JdbcClient.class);
        objectMapper = new ObjectMapper();
        auditLogRepository = new AuditLogRepository(jdbcClient, objectMapper);

        statementSpec = mock(JdbcClient.StatementSpec.class);
        querySpec = (JdbcClient.MappedQuerySpec<AuditLog>) mock(JdbcClient.MappedQuerySpec.class);
        countQuerySpec = (JdbcClient.MappedQuerySpec<Long>) mock(JdbcClient.MappedQuerySpec.class);

        when(jdbcClient.sql(anyString())).thenReturn(statementSpec);
        when(statementSpec.param(anyString(), any())).thenReturn(statementSpec);
        when(statementSpec.query(any(RowMapper.class))).thenReturn(querySpec);
        when(statementSpec.query(Long.class)).thenReturn(countQuerySpec);
    }

    @Test
    public void testLog() {
        when(statementSpec.update()).thenReturn(1);

        auditLogRepository.log("admin", "TEST_ACTION", "target1", Map.of("key", "value"));

        verify(jdbcClient).sql(contains("INSERT INTO audit_log"));
        verify(statementSpec).param(eq("entraOid"), eq("admin"));
        verify(statementSpec).param(eq("action"), eq("TEST_ACTION"));
        verify(statementSpec).param(eq("target"), eq("target1"));
        verify(statementSpec).update();
    }

    /**
     * The audit trail is a security record, and its {@code detail} column is decoration on top of
     * the {@code who / what / target} triple that actually matters. So {@code log()} treats an
     * empty map and a value Jackson cannot serialize identically: both store NULL, and neither is
     * allowed to stop the INSERT. That asymmetry is the whole point — {@code detail} is assembled
     * from caller-supplied, sometimes corpus- or LLM-derived values, so it is exactly the argument
     * most likely to contain something unserializable, and it arrives at the audit call AFTER the
     * audited action has already happened. Let the serialization failure escape and the effect is
     * inverted: the privileged operation succeeds, the record of it is lost, and the admin sees a
     * 500 for an action that in fact took effect — the one combination an audit log exists to make
     * impossible. No existing test reaches either path: {@code testLog} passes a well-formed
     * {@code Map.of("key", "value")} and never asserts what is bound to {@code :detail}, so the
     * repository could bind {@code "{}"} or throw and stay green.
     */
    @Test
    public void unserializableOrEmptyAuditDetailStoresNullWithoutFailingTheAction()
        throws Exception {
        when(statementSpec.update()).thenReturn(1);

        // (1) An empty detail map carries no information: store NULL, not "{}".
        auditLogRepository.log("admin", "EMPTY_DETAIL", "target1", Map.of());
        verify(statementSpec).param(eq("detail"), isNull());
        verify(statementSpec).update();

        // (2) A detail value Jackson refuses to write must degrade to NULL, not abort the INSERT.
        ObjectMapper failingMapper = mock(ObjectMapper.class);
        when(failingMapper.writeValueAsString(any()))
            .thenThrow(new JsonMappingException((Closeable) null, "cannot serialize audit detail"));
        JdbcClient failingClient = mock(JdbcClient.class);
        JdbcClient.StatementSpec failingSpec = mock(JdbcClient.StatementSpec.class);
        when(failingClient.sql(anyString())).thenReturn(failingSpec);
        when(failingSpec.param(anyString(), any())).thenReturn(failingSpec);
        when(failingSpec.update()).thenReturn(1);
        AuditLogRepository repository = new AuditLogRepository(failingClient, failingMapper);

        repository.log("admin", "BAD_DETAIL", "target2", Map.of("payload", new Object()));

        verify(failingSpec).param(eq("entraOid"), eq("admin"));
        verify(failingSpec).param(eq("action"), eq("BAD_DETAIL"));
        verify(failingSpec).param(eq("target"), eq("target2"));
        verify(failingSpec).param(eq("detail"), isNull());
        verify(failingSpec).update();
    }

    @Test
    public void testListLogs() {
        when(querySpec.list()).thenReturn(Collections.emptyList());

        List<AuditLog> result = auditLogRepository.listLogs(10, 0);

        assertThat(result).isEmpty();
        verify(jdbcClient).sql(contains("FROM audit_log"));
        verify(statementSpec).param(eq("limit"), eq(10));
        verify(statementSpec).param(eq("offset"), eq(0));
    }

    @Test
    public void testCountLogs() {
        when(countQuerySpec.single()).thenReturn(42L);

        long count = auditLogRepository.countLogs();

        assertThat(count).isEqualTo(42L);
        verify(jdbcClient).sql(contains("SELECT count(*) FROM audit_log"));
    }
}
