package de.palsoftware.yvoke.shared.user.repository;

import de.palsoftware.yvoke.shared.user.model.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

public class UserRepositoryTest {

    private JdbcClient jdbcClient;
    private UserRepository userRepository;

    private JdbcClient.StatementSpec statementSpec;
    private JdbcClient.MappedQuerySpec<User> querySpec;

    @SuppressWarnings("unchecked")
    @BeforeEach
    public void setUp() {
        jdbcClient = mock(JdbcClient.class);
        userRepository = new UserRepository(jdbcClient);

        statementSpec = mock(JdbcClient.StatementSpec.class);
        querySpec = (JdbcClient.MappedQuerySpec<User>) mock(JdbcClient.MappedQuerySpec.class);

        when(jdbcClient.sql(anyString())).thenReturn(statementSpec);
        when(statementSpec.param(anyString(), any())).thenReturn(statementSpec);
        when(statementSpec.query(any(RowMapper.class))).thenReturn(querySpec);
    }

    @Test
    public void testUpsert() {
        String entraOid = "user-oid-123";
        String email = "test@example.com";
        String displayName = "Test User";

        userRepository.upsert(entraOid, email, displayName);

        verify(jdbcClient).sql(contains("INSERT INTO users"));
        verify(statementSpec).param("entraOid", entraOid);
        verify(statementSpec).param("email", email);
        verify(statementSpec).param("displayName", displayName);
        verify(statementSpec).update();
    }

    @Test
    public void testFindByEntraOid() {
        String entraOid = "user-oid-123";
        User mockUser =
            new User(UUID.randomUUID(), entraOid, "test@example.com", "Test User", null);
        when(querySpec.optional()).thenReturn(Optional.of(mockUser));

        Optional<User> result = userRepository.findByEntraOid(entraOid);

        assertThat(result).isPresent();
        assertThat(result.get().entraOid()).isEqualTo(entraOid);
        verify(jdbcClient).sql(contains("SELECT id, entra_oid"));
        verify(statementSpec).param("entraOid", entraOid);
    }

    @Test
    public void testFindById() {
        UUID id = UUID.randomUUID();
        User mockUser = new User(id, "user-oid-123", "test@example.com", "Test User", null);
        when(querySpec.optional()).thenReturn(Optional.of(mockUser));

        Optional<User> result = userRepository.findById(id);

        assertThat(result).isPresent();
        assertThat(result.get().id()).isEqualTo(id);
        verify(jdbcClient).sql(contains("SELECT id, entra_oid"));
        verify(statementSpec).param("id", id);
    }
}
