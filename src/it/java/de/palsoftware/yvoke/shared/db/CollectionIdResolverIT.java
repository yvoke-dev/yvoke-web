package de.palsoftware.yvoke.shared.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Pins the shared collection-id resolution contract (MNT-14): case-insensitive matching, name
 * trimming, empty vs. throwing lookups, and the canonical missing-collection message that the KG /
 * document / job-engine repositories all rely on.
 */
@SpringBootTest(properties = {"spring.flyway.enabled=true",
    "spring.flyway.locations=filesystem:docker/db/migration"})
public class CollectionIdResolverIT {

    private static final String COLLECTION = "OIM-RESOLVER-TEST";

    @Autowired
    private CollectionIdResolver resolver;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID collectionId;

    @BeforeEach
    public void setUp() {
        cleanup();
        collectionId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO collections (id, name) VALUES (?, ?)", collectionId,
            COLLECTION);
    }

    @AfterEach
    public void tearDown() {
        cleanup();
    }

    private void cleanup() {
        jdbcTemplate.update("DELETE FROM collections WHERE name = ?", COLLECTION);
    }

    @Test
    public void findIdMatchesCaseInsensitivelyAndTrimmed() {
        assertThat(resolver.findId(COLLECTION)).contains(collectionId);
        assertThat(resolver.findId(COLLECTION.toLowerCase())).contains(collectionId);
        assertThat(resolver.findId("  " + COLLECTION + "  ")).contains(collectionId);
    }

    @Test
    public void findIdReturnsEmptyWhenAbsent() {
        assertThat(resolver.findId("no-such-collection")).isEqualTo(Optional.empty());
    }

    @Test
    public void requireIdReturnsIdWhenPresent() {
        assertThat(resolver.requireId(COLLECTION)).isEqualTo(collectionId);
        assertThat(resolver.requireId("  " + COLLECTION.toLowerCase() + "  "))
            .isEqualTo(collectionId);
    }

    @Test
    public void requireIdThrowsCanonicalMessageWhenAbsent() {
        assertThatThrownBy(() -> resolver.requireId("no-such-collection"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("does not exist - create it via the admin collections page");
    }
}
