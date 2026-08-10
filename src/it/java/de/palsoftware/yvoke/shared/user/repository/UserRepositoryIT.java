package de.palsoftware.yvoke.shared.user.repository;

import static org.assertj.core.api.Assertions.assertThat;

import de.palsoftware.yvoke.shared.user.model.User;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The identity columns are written from TWO places with different claim availability, so the upsert
 * has to be non-destructive. {@code UserService.syncUser} runs on browser login and reads the OIDC
 * ID token, where {@code name} and {@code preferred_username} are present by default.
 * {@code UserService.getCurrentUser} runs on EVERY bearer request (MCP and the desktop API) and
 * reads the ACCESS token, where those are Entra *optional* claims that are absent unless the app
 * registration adds them — and it passes whatever it found straight through, nulls included.
 *
 * <p>
 * Config matches the other repository ITs exactly so this class reuses their cached Spring context
 * rather than minting a new one (CLAUDE.md § 6, TestContext cache thrash).
 */
@SpringBootTest(
    properties = {"spring.flyway.enabled=true",
        "spring.flyway.locations=filesystem:docker/db/migration"})
public class UserRepositoryIT {

    private static final String ENTRA_OID = "user-repo-it-oid";

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    public void setUp() {
        cleanup();
    }

    @AfterEach
    public void tearDown() {
        cleanup();
    }

    private void cleanup() {
        jdbcTemplate.update("DELETE FROM users WHERE entra_oid = ?", ENTRA_OID);
    }

    /**
     * A bearer token missing the optional claims MUST NOT blank what a browser login stored. Without
     * a COALESCE the sequence is: log in via the web UI (columns populated) → connect over MCP →
     * the first request, and every one after it, overwrites both columns with NULL. They stay null
     * until the next browser login, which the next MCP call then undoes again. Nothing errors; the
     * damage surfaces only as blank names in the cost dashboard — for exactly the heavy MCP users
     * an operator most wants to see there.
     */
    @Test
    public void aTokenWithoutTheOptionalClaimsMustNotBlankWhatAnEarlierLoginStored() {
        userRepository.upsert(ENTRA_OID, "alice@corp.com", "Alice Smith");

        // The bearer path: preferred_username/email and name all absent from the access token.
        userRepository.upsert(ENTRA_OID, null, null);

        User after = userRepository.findByEntraOid(ENTRA_OID).orElseThrow();
        assertThat(after.email()).as("a null claim must not overwrite a known email")
            .isEqualTo("alice@corp.com");
        assertThat(after.displayName()).as("a null claim must not overwrite a known display name")
            .isEqualTo("Alice Smith");
    }

    /** The complement: a token that DOES carry the claims must still update them. */
    @Test
    public void aTokenCarryingTheClaimsStillUpdatesThem() {
        userRepository.upsert(ENTRA_OID, "old@corp.com", "Old Name");

        userRepository.upsert(ENTRA_OID, "new@corp.com", "New Name");

        User after = userRepository.findByEntraOid(ENTRA_OID).orElseThrow();
        assertThat(after.email()).isEqualTo("new@corp.com");
        assertThat(after.displayName()).isEqualTo("New Name");
    }

    /**
     * Preserving the identity columns must not also freeze {@code last_seen_at}: that is the
     * upsert's other job, and bearer traffic is exactly the traffic it is meant to record.
     */
    @Test
    public void aClaimlessRefreshStillAdvancesLastSeenAt() {
        userRepository.upsert(ENTRA_OID, "alice@corp.com", "Alice Smith");
        Instant first = userRepository.findByEntraOid(ENTRA_OID).orElseThrow().lastSeenAt();

        userRepository.upsert(ENTRA_OID, null, null);

        Instant second = userRepository.findByEntraOid(ENTRA_OID).orElseThrow().lastSeenAt();
        // isAfter, not isAfterOrEqualTo: the weaker form holds when the timestamp does not move at
        // all, which is exactly the regression this guards (coalescing last_seen_at away). Separate
        // auto-commit transactions get distinct transaction timestamps, so this is deterministic.
        assertThat(second).isAfter(first);
    }

    /** A first-seen MCP-only user still gets a row — the bearer upsert cannot be skipped. */
    @Test
    public void aUserSeenOnlyOverBearerIsStillCreated() {
        userRepository.upsert(ENTRA_OID, null, null);

        assertThat(userRepository.findByEntraOid(ENTRA_OID)).isPresent();
    }
}
