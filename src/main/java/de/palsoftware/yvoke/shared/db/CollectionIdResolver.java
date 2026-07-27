package de.palsoftware.yvoke.shared.db;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Resolves a collection name to its id (case-insensitively). This is the single home for the
 * name-&gt;id lookup that was previously copy-pasted across the KG, document, and job-engine
 * repositories (MNT-14).
 *
 * <p>
 * The name is trimmed before matching, so leading/trailing whitespace does not defeat an
 * otherwise-exact match. Collections are created only via the admin collections page
 * ({@code CollectionService}); a miss from {@link #requireId(String)} therefore means the caller
 * referenced a collection that never existed or was deleted mid-operation, so it fails loudly
 * instead of silently recreating a bare collection.
 *
 * <p>
 * This performs read-only lookups only: any tag-mutation side effects that historically shared this
 * code path stay in the owning repository.
 */
@Component
public class CollectionIdResolver {

    private final JdbcClient jdbcClient;

    public CollectionIdResolver(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /** Case-insensitive lookup by (trimmed) name; empty when no such collection exists. */
    public Optional<UUID> findId(String collectionName) {
        return jdbcClient.sql("SELECT id FROM collections WHERE LOWER(name) = LOWER(:name)")
            .param("name", collectionName.trim()).query(UUID.class).optional();
    }

    /**
     * Case-insensitive lookup that throws {@link IllegalArgumentException} with the canonical
     * "create it via the admin collections page" message when the collection is absent.
     */
    public UUID requireId(String collectionName) {
        return findId(collectionName).orElseThrow(() -> new IllegalArgumentException("Collection '"
            + collectionName + "' does not exist - create it via the admin collections page."));
    }
}
