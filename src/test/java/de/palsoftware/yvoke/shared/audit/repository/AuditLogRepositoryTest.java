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
