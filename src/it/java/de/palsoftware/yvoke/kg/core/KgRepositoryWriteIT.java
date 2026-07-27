package de.palsoftware.yvoke.kg.core;
import de.palsoftware.yvoke.kg.core.repository.KgWriteRepository;


import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;


@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=filesystem:docker/db/migration"
})
public class KgRepositoryWriteIT {

    private static final String COLLECTION = "OIM-KGWRITE-TEST";
    private static final String VERSION = "9.3";

    @Autowired
    private KgWriteRepository kgRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    public void setUp() {
        cleanup();
        // Collections are no longer auto-created by KG writes; the target must pre-exist.
        jdbcTemplate.update(
            "INSERT INTO collections (id, name) VALUES (?, ?) ON CONFLICT (name) DO NOTHING",
            java.util.UUID.randomUUID(), COLLECTION);
    }

    @AfterEach
    public void tearDown() {
        cleanup();
    }

    private void cleanup() {
        jdbcTemplate.update("DELETE FROM collections WHERE name = ?", COLLECTION);
    }

    @Test
    public void upsertEntityIsIdempotentCaseInsensitive() {
        UUID first = kgRepository.upsertEntity(COLLECTION, VERSION, "OAuth Module", "module", "desc");
        UUID second = kgRepository.upsertEntity(COLLECTION, VERSION, "oauth module", "module", "desc2");

        assertThat(second).isEqualTo(first);
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM entities e JOIN collections c ON e.collection_id = c.id WHERE c.name = ?", Integer.class, COLLECTION);
        assertThat(count).isEqualTo(1);
    }

    @Test
    public void upsertRelationshipInsertsOnceAndLinksIds() {
        UUID subjectId = kgRepository.upsertEntity(COLLECTION, VERSION, "OAuth", "module", null);
        UUID objectId = kgRepository.upsertEntity(COLLECTION, VERSION, "OIM", "product", null);

        boolean firstInsert = kgRepository.upsertRelationship(
                COLLECTION, VERSION, "OAuth", "part_of", "OIM", subjectId, objectId, "edge");
        boolean secondInsert = kgRepository.upsertRelationship(
                COLLECTION, VERSION, "OAuth", "part_of", "OIM", subjectId, objectId, "edge");

        assertThat(firstInsert).isTrue();
        assertThat(secondInsert).isFalse();

        Integer edgeCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM relationships r JOIN collections c ON r.collection_id = c.id WHERE c.name = ?", Integer.class, COLLECTION);
        assertThat(edgeCount).isEqualTo(1);

        UUID storedSubjectId = jdbcTemplate.queryForObject(
                "SELECT subject_id FROM relationships r JOIN collections c ON r.collection_id = c.id WHERE c.name = ? AND r.subject = 'OAuth'",
                UUID.class, COLLECTION);
        UUID storedObjectId = jdbcTemplate.queryForObject(
                "SELECT object_id FROM relationships r JOIN collections c ON r.collection_id = c.id WHERE c.name = ? AND r.subject = 'OAuth'",
                UUID.class, COLLECTION);
        assertThat(storedSubjectId).isEqualTo(subjectId);
        assertThat(storedObjectId).isEqualTo(objectId);
    }
}
