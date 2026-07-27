package de.palsoftware.yvoke.ingest.core.confluence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Data access for {@code confluence_instances}. */
@Repository
public class ConfluenceInstanceRepository {

    private final JdbcClient jdbcClient;
    private final ApplicationEventPublisher events;

    public ConfluenceInstanceRepository(JdbcClient jdbcClient, ApplicationEventPublisher events) {
        this.jdbcClient = jdbcClient;
        this.events = events;
    }

    public List<ConfluenceInstance> findAll() {
        return jdbcClient.sql("""
            SELECT id, name, slug, domain, email, api_token_enc, token_key_id, space, root_page_id,
                   include_labels, exclude_labels, target_collection, target_tag,
                   process_attachments, enabled, created_at, updated_at
            FROM confluence_instances
            ORDER BY name ASC
            """).query((rs, rowNum) -> mapRow(rs)).list();
    }

    public Optional<ConfluenceInstance> findById(UUID id) {
        return jdbcClient.sql("""
            SELECT id, name, slug, domain, email, api_token_enc, token_key_id, space, root_page_id,
                   include_labels, exclude_labels, target_collection, target_tag,
                   process_attachments, enabled, created_at, updated_at
            FROM confluence_instances
            WHERE id = :id
            """).param("id", id).query((rs, rowNum) -> mapRow(rs)).optional();
    }

    /**
     * Whether the row still exists, without loading it.
     *
     * <p>
     * Used by the crawl once per page batch to notice that its instance was deleted underneath it.
     * Deliberately not {@code findById(...).isPresent()}: that reads the token ciphertext and its
     * key fingerprint into the heap on every batch for a question that is a boolean.
     */
    public boolean existsById(UUID id) {
        return Boolean.TRUE.equals(
            jdbcClient.sql("SELECT EXISTS(SELECT 1 FROM confluence_instances WHERE id = :id)")
                .param("id", id).query(Boolean.class).single());
    }

    public Optional<ConfluenceInstance> findBySlug(String slug) {
        return jdbcClient.sql("""
            SELECT id, name, slug, domain, email, api_token_enc, token_key_id, space, root_page_id,
                   include_labels, exclude_labels, target_collection, target_tag,
                   process_attachments, enabled, created_at, updated_at
            FROM confluence_instances
            WHERE slug = :slug
            """).param("slug", slug).query((rs, rowNum) -> mapRow(rs)).optional();
    }

    /**
     * Looks an instance up by its unique {@code name}, so a "create the row named 'default', or
     * adopt the one the V2 backfill produced" caller can decide for itself instead of relying on
     * the upsert to arbitrate on the name (which is exactly how one instance can overwrite
     * another).
     */
    public Optional<ConfluenceInstance> findByName(String name) {
        return jdbcClient.sql("""
            SELECT id, name, slug, domain, email, api_token_enc, token_key_id, space, root_page_id,
                   include_labels, exclude_labels, target_collection, target_tag,
                   process_attachments, enabled, created_at, updated_at
            FROM confluence_instances
            WHERE name = :name
            """).param("name", name).query((rs, rowNum) -> mapRow(rs)).optional();
    }

    /**
     * Inserts or updates the instance keyed by its PRIMARY KEY, and returns the persisted row
     * (RETURNING, so the generated id and the server-side timestamps come back in the same
     * round-trip).
     *
     * <p>
     * The arbiter is deliberately {@code id} and nothing else. Postgres evaluates only the arbiter
     * index before choosing INSERT vs UPDATE, so arbitrating on {@code name} made a rename either
     * impossible (a free name collided on the row's own primary key) or destructive (a name already
     * held by another instance made DO UPDATE rewrite THAT row — credential included — and report
     * success). With the PK as arbiter a {@code name} or {@code slug} clash surfaces as a
     * {@code DataIntegrityViolationException} the caller can map to a field error.
     *
     * <p>
     * A null {@code apiTokenEnc} means "keep the stored credential", not "destroy it": an edit that
     * changes only a label filter must not silently produce a 401 at the next sync. Use
     * {@link #clearToken(UUID)} to remove a credential on purpose. The ciphertext and its
     * fingerprint are ONE value and are always written together — a row holding one without the
     * other has a {@link ConfluenceInstance#tokenHealth(String)} that lies.
     *
     * @throws IllegalArgumentException if a {@code tokenKeyId} is supplied without its ciphertext,
     *         or if the domain is not an absolute http(s) URL
     */
    public ConfluenceInstance upsert(ConfluenceInstance instance) {
        if (instance.apiTokenEnc() == null && instance.tokenKeyId() != null) {
            throw new IllegalArgumentException(
                "tokenKeyId was supplied without api_token_enc; the ciphertext and its key "
                    + "fingerprint must always be written together.");
        }
        // Strict on the WRITE path (the record is lenient because it also maps rows on read): this
        // is what makes V2's "rows written by the application are canonicalized on save" true, and
        // source_file identity depends on it.
        String domain = ConfluenceDomains.canonicalize(instance.domain());
        UUID id = instance.id() != null ? instance.id() : UUID.randomUUID();
        return jdbcClient.sql("""
            INSERT INTO confluence_instances (id, name, slug, domain, email, api_token_enc,
                                              token_key_id, space, root_page_id, include_labels,
                                              exclude_labels, target_collection, target_tag,
                                              process_attachments, enabled, created_at, updated_at)
            VALUES (:id, :name, :slug, :domain, :email, :apiTokenEnc, :tokenKeyId, :space,
                    :rootPageId, :includeLabels, :excludeLabels, :targetCollection, :targetTag,
                    :processAttachments, :enabled, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            ON CONFLICT (id) DO UPDATE SET
                name = EXCLUDED.name,
                slug = EXCLUDED.slug,
                domain = EXCLUDED.domain,
                email = EXCLUDED.email,
                -- Null token == "keep the current credential". The fingerprint follows the
                -- ciphertext rather than COALESCE'ing on its own, so a resupplied token can never
                -- be left wearing the previous key's fingerprint (which reads as UNDECRYPTABLE).
                api_token_enc = COALESCE(EXCLUDED.api_token_enc,
                                         confluence_instances.api_token_enc),
                token_key_id = CASE WHEN EXCLUDED.api_token_enc IS NULL
                                    THEN confluence_instances.token_key_id
                                    ELSE EXCLUDED.token_key_id END,
                space = EXCLUDED.space,
                root_page_id = EXCLUDED.root_page_id,
                include_labels = EXCLUDED.include_labels,
                exclude_labels = EXCLUDED.exclude_labels,
                target_collection = EXCLUDED.target_collection,
                target_tag = EXCLUDED.target_tag,
                process_attachments = EXCLUDED.process_attachments,
                enabled = EXCLUDED.enabled,
                updated_at = CURRENT_TIMESTAMP
            RETURNING id, name, slug, domain, email, api_token_enc, token_key_id, space,
                      root_page_id, include_labels, exclude_labels, target_collection, target_tag,
                      process_attachments, enabled, created_at, updated_at
            """).param("id", id).param("name", instance.name()).param("slug", instance.slug())
            .param("domain", domain).param("email", instance.email())
            .param("apiTokenEnc", instance.apiTokenEnc()).param("tokenKeyId", instance.tokenKeyId())
            .param("space", instance.space()).param("rootPageId", instance.rootPageId())
            .param("includeLabels", instance.includeLabels())
            .param("excludeLabels", instance.excludeLabels())
            .param("targetCollection", instance.targetCollection())
            .param("targetTag", instance.targetTag())
            .param("processAttachments", instance.processAttachments())
            .param("enabled", instance.enabled()).query((rs, rowNum) -> mapRow(rs)).single();
    }

    /**
     * Removes the stored credential on purpose, nulling the ciphertext and its fingerprint in one
     * statement — the pair must never be half-written (see {@link #upsert(ConfluenceInstance)}).
     *
     * <p>
     * The removal is announced so the built {@link org.springframework.web.client.RestClient} —
     * which carries the credential as a default header — is dropped from
     * {@link ConfluenceClientService}'s cache instead of surviving in the heap until the next
     * restart.
     */
    public void clearToken(UUID id) {
        jdbcClient.sql("""
            UPDATE confluence_instances
            SET api_token_enc = NULL, token_key_id = NULL, updated_at = CURRENT_TIMESTAMP
            WHERE id = :id
            """).param("id", id).update();
        events.publishEvent(new ConfluenceInstanceCredentialsChangedEvent(id));
    }

    /** Announced like {@link #clearToken(UUID)}: the deleted row's cached client must go too. */
    public void deleteById(UUID id) {
        jdbcClient.sql("DELETE FROM confluence_instances WHERE id = :id").param("id", id).update();
        events.publishEvent(new ConfluenceInstanceCredentialsChangedEvent(id));
    }

    private ConfluenceInstance mapRow(ResultSet rs) throws SQLException {
        return new ConfluenceInstance(rs.getObject("id", UUID.class), rs.getString("name"),
            rs.getString("slug"), rs.getString("domain"), rs.getString("email"),
            rs.getString("api_token_enc"), rs.getString("token_key_id"), rs.getString("space"),
            rs.getString("root_page_id"), rs.getString("include_labels"),
            rs.getString("exclude_labels"), rs.getString("target_collection"),
            rs.getString("target_tag"), rs.getBoolean("process_attachments"),
            rs.getBoolean("enabled"), rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class));
    }
}
