package de.palsoftware.yvoke.collection.core.repository;

import de.palsoftware.yvoke.collection.core.model.Collection;
import de.palsoftware.yvoke.shared.config.JdbcMappers;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class CollectionRepository {

    private final JdbcClient jdbcClient;

    public CollectionRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<Collection> findAll() {
        String sql = """
            SELECT id, name, description, created_at, tags
            FROM collections
            ORDER BY name ASC
            """;

        return jdbcClient.sql(sql).query((rs, rowNum) -> {
            UUID id = rs.getObject("id", UUID.class);
            List<String> tags = JdbcMappers.arrayToStringList(rs, "tags");
            return new Collection(id, rs.getString("name"), rs.getString("description"), tags,
                rs.getObject("created_at", OffsetDateTime.class));
        }).list();
    }

    /**
     * The corpus tag vocabulary: every tag declared on any collection, de-duplicated and ordered
     * case-insensitively.
     *
     * <p>
     * Derived, never registered. A {@code tags} table held this list until V6 and drifted, because
     * its only writer was {@code TagRepository.getOrCreateTag} while the corpus import and the
     * ingest paths set {@code collections.tags} directly. Deriving costs 0.05 ms against the
     * production corpus (31 rows) — the alternative source,
     * {@code DISTINCT unnest(documents.tags)}, is a 22k-row scan at 10 ms that no index can serve,
     * and would hide a tag a collection declares but has not ingested yet.
     */
    public List<String> findAllTagNames() {
        // The DISTINCT is nested: Postgres requires every SELECT DISTINCT ORDER BY expression to
        // appear in the select list, so a top-level `ORDER BY lower(tag_name)` would not parse.
        String sql = """
            SELECT tag_name
            FROM (SELECT DISTINCT unnest(tags) AS tag_name FROM collections) declared
            ORDER BY lower(tag_name)
            """;
        return jdbcClient.sql(sql).query(String.class).list();
    }

    public Optional<Collection> findByName(String name) {
        String sql = """
            SELECT id, name, description, created_at, tags
            FROM collections
            WHERE LOWER(name) = LOWER(:name)
            """;

        return jdbcClient.sql(sql).param("name", name).query((rs, rowNum) -> {
            UUID id = rs.getObject("id", UUID.class);
            List<String> tags = JdbcMappers.arrayToStringList(rs, "tags");
            return new Collection(id, rs.getString("name"), rs.getString("description"), tags,
                rs.getObject("created_at", OffsetDateTime.class));
        }).optional();
    }

    public Optional<Collection> findById(UUID id) {
        String sql = """
            SELECT id, name, description, created_at, tags
            FROM collections
            WHERE id = :id
            """;

        return jdbcClient.sql(sql).param("id", id).query((rs, rowNum) -> {
            List<String> tags = JdbcMappers.arrayToStringList(rs, "tags");
            return new Collection(id, rs.getString("name"), rs.getString("description"), tags,
                rs.getObject("created_at", OffsetDateTime.class));
        }).optional();
    }

    public Collection create(String name, String description) {
        UUID id = UUID.randomUUID();
        String sql = """
            INSERT INTO collections (id, name, description, created_at, tags)
            VALUES (:id, :name, :description, CURRENT_TIMESTAMP, '{}'::TEXT[])
            """;
        jdbcClient.sql(sql).param("id", id).param("name", name.trim())
            .param("description", description != null ? description.trim() : null).update();

        return findByName(name.trim()).orElseThrow();
    }

    public void delete(String name) {
        jdbcClient.sql("DELETE FROM collections WHERE LOWER(name) = LOWER(:name)")
            .param("name", name.trim()).update();
    }
}
