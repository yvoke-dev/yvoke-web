package de.palsoftware.yvoke.kg.core;

import static org.assertj.core.api.Assertions.assertThat;

import de.palsoftware.yvoke.kg.core.repository.KgWriteRepository;
import de.palsoftware.yvoke.kg.core.repository.KgWriteRepository.EntityUpsert;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Regression IT for the entity find-or-create race that V2 turned from silent duplication into a
 * hard failure.
 *
 * <p>{@code upsertEntitiesBatch} resolves ids with a SELECT and then inserts what it did not find.
 * Before V2 the {@code entities} table had no unique constraint, so two workers racing on the same
 * name produced duplicate rows (ugly, but the consolidator cleaned up). V2 added
 * {@code ux_entities_collection_kind_lower_name}, so the loser of that race now gets a duplicate-key
 * error instead — and with {@code app.ingest.kg.concurrency} defaulting to 8 workers over documents
 * of the same collection, a recurring entity name (a table mentioned in several manual sections) is
 * the common case, not a corner one.
 *
 * <p>The interleaving is forced deterministically rather than by racing threads: a second connection
 * inserts the row and holds its transaction open, so the repository's SELECT cannot see it and its
 * INSERT blocks on the unique index until the winner commits.
 *
 * <p>Uses a bare {@code @SpringBootTest} to reuse the context already cached by the other KG ITs (no
 * extra properties, no mock beans) — see the context-cache pitfall in CLAUDE.md.
 */
@SpringBootTest
public class KgEntityUpsertRaceIT {

    private static final String COLLECTION = "OIM-UPSERT-RACE-TEST";
    private static final String TAG = "9.3";

    @Autowired
    private KgWriteRepository kgWriteRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    private UUID collectionId;

    @BeforeEach
    public void setUp() {
        cleanup();
        collectionId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO collections (id, name, tags) VALUES (?, ?, ARRAY['9.3'])",
            collectionId, COLLECTION);
    }

    @AfterEach
    public void tearDown() {
        cleanup();
    }

    private void cleanup() {
        jdbcTemplate.update("DELETE FROM collections WHERE name = ?", COLLECTION);
    }

    private int entityCount(String name) {
        Integer n = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM entities e JOIN collections c ON e.collection_id = c.id "
                + "WHERE c.name = ? AND lower(e.name) = lower(?)",
            Integer.class, COLLECTION, name);
        return n == null ? 0 : n;
    }

    /**
     * Blocks until the repository's INSERT is actually waiting on a lock, so that committing the
     * winner afterwards exercises the real race instead of a benign already-visible read.
     */
    private void awaitBlockedEntityInsert() throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        while (Instant.now().isBefore(deadline)) {
            Integer waiting = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_stat_activity WHERE state = 'active' "
                    + "AND wait_event_type = 'Lock' AND query ILIKE 'INSERT INTO entities%'",
                Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError(
            "the repository's INSERT never blocked on the unique index; the race was not reproduced");
    }

    @Test
    public void losingTheInsertRaceResolvesToTheWinningRowInsteadOfFailing() throws Exception {
        UUID winnerId = UUID.randomUUID();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection held = dataSource.getConnection()) {
            held.setAutoCommit(false);
            try (PreparedStatement ps = held.prepareStatement(
                "INSERT INTO entities (id, collection_id, name, kind, description, tags) "
                    + "VALUES (?, ?, 'ADS', 'module', 'inserted by the racing worker', ARRAY['9.3'])")) {
                ps.setObject(1, winnerId);
                ps.setObject(2, collectionId);
                ps.executeUpdate();
            }

            // The repository sees no such row yet, so it generates its own id and inserts —
            // blocking on the unique index until the held transaction resolves.
            Future<Map<String, UUID>> upsert = pool.submit(() -> kgWriteRepository
                .upsertEntitiesBatch(COLLECTION, List.of(TAG),
                    List.of(new EntityUpsert("ADS", "module", "the ADS module"))));
            awaitBlockedEntityInsert();

            held.commit();

            Map<String, UUID> resolved = upsert.get(30, TimeUnit.SECONDS);

            // The caller feeds these ids straight into relationships.subject_id/object_id, so
            // returning the id it optimistically generated (rather than the row that actually
            // exists) would fail later as an FK violation.
            assertThat(resolved).containsEntry(KgWriteRepository.entityKey("module", "ADS"),
                winnerId);
        } finally {
            pool.shutdownNow();
        }

        assertThat(entityCount("ADS")).isEqualTo(1);
        // The losing side must still contribute its tag, or the entity silently loses version
        // scoping for the document that lost the race.
        List<String> tags = jdbcTemplate.queryForList(
            "SELECT unnest(tags) FROM entities WHERE id = ?", String.class, winnerId);
        assertThat(tags).contains(TAG);
    }
}
