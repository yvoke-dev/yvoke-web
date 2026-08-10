package de.palsoftware.yvoke.ingest.core.confluence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.Map;

// Same properties as OrchestratorProfileRepositoryIT on purpose: an identical configuration reuses
// that cached TestContext instead of minting a new one.
@SpringBootTest(properties = {"spring.flyway.enabled=true",
    "spring.flyway.locations=filesystem:docker/db/migration"})
public class ConfluenceInstanceRepositoryIT {

    private static ConfluenceInstance sample(String name, String slug, String targetTag) {
        return new ConfluenceInstance(null, name, slug, "https://mycompany.atlassian.net/wiki",
            "svc@example.com", "enc:ciphertext", "abcdef0123456789", "DOCS", "12345", "public",
            "draft", "IT - Confluence", targetTag, true, true, null, null);
    }

    @Autowired
    private ConfluenceInstanceRepository repository;

    /** Writes a row the repository itself would refuse, to exercise the READ path on its own. */
    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void testUpsertInsertsThenUpdatesKeepingTheId() {
        String name = "IT_Confluence_Instance";
        ConfluenceInstance inserted = repository.upsert(sample(name, "it-confluence", "10.0"));

        try {
            assertThat(inserted.id()).isNotNull();
            assertThat(inserted.createdAt()).isNotNull();
            assertThat(inserted.targetTag()).isEqualTo("10.0");
            assertThat(inserted.processAttachments()).isTrue();

            Optional<ConfluenceInstance> bySlug = repository.findBySlug("it-confluence");
            assertThat(bySlug).isPresent();
            assertThat(bySlug.get().id()).isEqualTo(inserted.id());
            assertThat(bySlug.get().apiTokenEnc()).isEqualTo("enc:ciphertext");
            assertThat(bySlug.get().tokenKeyId()).isEqualTo("abcdef0123456789");

            assertThat(repository.findById(inserted.id())).isPresent();
            assertThat(repository.findByName(name)).isPresent()
                .get().extracting(ConfluenceInstance::id).isEqualTo(inserted.id());
            assertThat(repository.findAll()).extracting(ConfluenceInstance::name).contains(name);

            // Same id -> update in place.
            ConfluenceInstance updated = repository.upsert(new ConfluenceInstance(inserted.id(),
                name, "it-confluence-2", "https://other.atlassian.net/wiki", "svc2@example.com",
                null, null, "OPS", "999", null, null, "IT - Confluence", "", false, false, null,
                null));

            assertThat(updated.id()).isEqualTo(inserted.id());
            assertThat(updated.slug()).isEqualTo("it-confluence-2");
            assertThat(updated.enabled()).isFalse();
            // A null token means "keep the current credential", NOT "destroy it" — an edit that
            // only changes a label filter must not silently produce a 401 at the next sync.
            assertThat(updated.apiTokenEnc()).isEqualTo("enc:ciphertext");
            assertThat(updated.tokenKeyId()).isEqualTo("abcdef0123456789");
            // '' was normalized to null by the record, so the CHECK constraint is satisfied.
            assertThat(updated.targetTag()).isNull();
            assertThat(repository.findBySlug("it-confluence")).isEmpty();
        } finally {
            repository.deleteById(inserted.id());
        }

        assertThat(repository.findById(inserted.id())).isEmpty();
        assertThat(repository.findAll()).extracting(ConfluenceInstance::name).doesNotContain(name);
    }

    @Test
    void testRenameToAFreeNameKeepsTheSameRow() {
        // The upsert used to arbitrate on `name`: a rename found no name conflict, so the INSERT
        // proceeded and collided on the row's own primary key. Renaming was impossible.
        ConfluenceInstance inserted =
            repository.upsert(sample("IT_Confluence_Rename_Before", "it-conf-rename", null));
        try {
            ConfluenceInstance renamed = repository.upsert(new ConfluenceInstance(inserted.id(),
                "IT_Confluence_Rename_After", "it-conf-rename", inserted.domain(),
                inserted.email(), null, null, inserted.space(), inserted.rootPageId(), null, null,
                inserted.targetCollection(), null, false, true, null, null));

            assertThat(renamed.id()).isEqualTo(inserted.id());
            assertThat(renamed.name()).isEqualTo("IT_Confluence_Rename_After");
            assertThat(repository.findByName("IT_Confluence_Rename_Before")).isEmpty();
            assertThat(repository.findAll()).extracting(ConfluenceInstance::id)
                .filteredOn(inserted.id()::equals).hasSize(1);
        } finally {
            repository.deleteById(inserted.id());
        }
    }

    @Test
    void testRenamingOntoAnotherInstancesNameFailsAndLeavesItUntouched() {
        // With `ON CONFLICT (name)` this rewrote B's ENTIRE row (including B's credential) with
        // A's data, returned B's id and left A behind: cross-row data loss reported as success.
        ConfluenceInstance a = repository.upsert(sample("IT_Confluence_A", "it-conf-a", null));
        ConfluenceInstance b =
            repository.upsert(new ConfluenceInstance(null, "IT_Confluence_B", "it-conf-b",
                "https://bbb.atlassian.net/wiki", "b@example.com", "enc:b-ciphertext", "bbbbbbbb",
                "BBB", "777", null, null, "IT - Confluence B", null, false, true, null, null));
        try {
            ConfluenceInstance clash = new ConfluenceInstance(a.id(), b.name(), a.slug(),
                a.domain(), a.email(), null, null, a.space(), a.rootPageId(), null, null,
                a.targetCollection(), null, false, true, null, null);

            assertThatThrownBy(() -> repository.upsert(clash))
                .isInstanceOf(DataIntegrityViolationException.class);

            ConfluenceInstance reloadedB = repository.findById(b.id()).orElseThrow();
            assertThat(reloadedB.id()).isEqualTo(b.id());
            assertThat(reloadedB.name()).isEqualTo("IT_Confluence_B");
            assertThat(reloadedB.domain()).isEqualTo("https://bbb.atlassian.net/wiki");
            assertThat(reloadedB.apiTokenEnc()).isEqualTo("enc:b-ciphertext");
            assertThat(reloadedB.tokenKeyId()).isEqualTo("bbbbbbbb");
            assertThat(repository.findById(a.id())).isPresent()
                .get().extracting(ConfluenceInstance::name).isEqualTo("IT_Confluence_A");
        } finally {
            repository.deleteById(a.id());
            repository.deleteById(b.id());
        }
    }

    @Test
    void testSlugCollisionFails() {
        ConfluenceInstance a = repository.upsert(sample("IT_Confluence_Slug_A", "it-conf-slug", null));
        ConfluenceInstance b =
            repository.upsert(sample("IT_Confluence_Slug_B", "it-conf-slug-other", null));
        try {
            ConfluenceInstance clash = new ConfluenceInstance(b.id(), b.name(), a.slug(),
                b.domain(), b.email(), null, null, b.space(), b.rootPageId(), null, null,
                b.targetCollection(), null, false, true, null, null);

            assertThatThrownBy(() -> repository.upsert(clash))
                .isInstanceOf(DataIntegrityViolationException.class);
            assertThat(repository.findById(b.id())).isPresent()
                .get().extracting(ConfluenceInstance::slug).isEqualTo("it-conf-slug-other");
        } finally {
            repository.deleteById(a.id());
            repository.deleteById(b.id());
        }
    }

    @Test
    void testClearTokenNullsCiphertextAndFingerprintTogether() {
        ConfluenceInstance inserted =
            repository.upsert(sample("IT_Confluence_ClearToken", "it-conf-clear", null));
        try {
            repository.clearToken(inserted.id());

            ConfluenceInstance reloaded = repository.findById(inserted.id()).orElseThrow();
            assertThat(reloaded.apiTokenEnc()).isNull();
            assertThat(reloaded.tokenKeyId()).isNull();
            assertThat(reloaded.tokenHealth("abcdef0123456789")).isEqualTo(TokenHealth.MISSING);
            // Everything else survives a credential removal.
            assertThat(reloaded.space()).isEqualTo("DOCS");
        } finally {
            repository.deleteById(inserted.id());
        }
    }

    @Test
    void testResuppliedTokenReplacesTheFingerprintInsteadOfKeepingTheOldOne() {
        // The pair is one value: a token re-entered on a key-less box (keyId() == null) must not
        // keep the previous key's fingerprint, which would read as UNDECRYPTABLE forever.
        ConfluenceInstance inserted =
            repository.upsert(sample("IT_Confluence_Repair", "it-conf-repair", null));
        try {
            ConfluenceInstance repaired = repository.upsert(new ConfluenceInstance(inserted.id(),
                inserted.name(), inserted.slug(), inserted.domain(), inserted.email(),
                "plaintext-token", null, inserted.space(), inserted.rootPageId(), null, null,
                inserted.targetCollection(), null, false, true, null, null));

            assertThat(repaired.apiTokenEnc()).isEqualTo("plaintext-token");
            assertThat(repaired.tokenKeyId()).isNull();
        } finally {
            repository.deleteById(inserted.id());
        }
    }

    @Test
    void testUpsertRejectsAFingerprintWithoutItsCiphertext() {
        // The ciphertext and its fingerprint are one value; a row with only the fingerprint has a
        // tokenHealth that lies.
        ConfluenceInstance broken = new ConfluenceInstance(null, "IT_Confluence_Broken",
            "it-conf-broken", "https://mycompany.atlassian.net/wiki", "svc@example.com", null,
            "abcdef0123456789", "DOCS", "12345", null, null, "IT - Confluence", null, false, true,
            null, null);

        assertThatThrownBy(() -> repository.upsert(broken))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(repository.findBySlug("it-conf-broken")).isEmpty();
    }

    /**
     * The V2 backfill promotes whatever an administrator once typed into {@code app_config}, so a
     * stored domain need not be a canonical (or even valid) URL. Mapping such a row must NOT throw:
     * the record's compact constructor runs on every read, and a throw here fails
     * {@code findAll()} — i.e. the whole connector admin page — with no way to reach the form that
     * fixes the value. Saving it again is what rejects it.
     */
    @Test
    void testARowWhoseStoredDomainIsNotAUrlStillLoads() {
        UUID id = UUID.randomUUID();
        jdbcClient.sql("""
            INSERT INTO confluence_instances (id, name, slug, domain, email, space, root_page_id,
                                              target_collection)
            VALUES (:id, :name, :slug, :domain, :email, :space, :rootPageId, :collection)
            """).param("id", id).param("name", "IT_Confluence_Legacy_Domain")
            .param("slug", "it-conf-legacy").param("domain", "mycompany.atlassian.net")
            .param("email", "svc@example.com").param("space", "DOCS").param("rootPageId", "12345")
            .param("collection", "IT - Confluence").update();
        try {
            ConfluenceInstance loaded = repository.findBySlug("it-conf-legacy").orElseThrow();
            assertThat(loaded.domain()).isEqualTo("mycompany.atlassian.net");
            assertThat(repository.findAll()).extracting(ConfluenceInstance::id).contains(id);

            // ...but re-saving it is rejected, so the bad value cannot spread.
            assertThatThrownBy(() -> repository.upsert(loaded))
                .isInstanceOf(IllegalArgumentException.class);
        } finally {
            repository.deleteById(id);
        }
    }

    /**
     * {@code target_collection} is a collection NAME, resolved case-insensitively at use time, and
     * deliberately NOT a foreign key — V1 says so in a comment and says "Do not fix this into an
     * FK". A comment is not an enforcement: adding {@code REFERENCES collections(name)} is a
     * one-line edit that reads like an obvious integrity improvement, and {@code collections.name}
     * is UNIQUE, so it would be accepted.
     *
     * <p>
     * With {@code ON DELETE CASCADE} the consequence is that deleting a collection from the admin
     * collections page — destructive, but a routine and entirely legitimate action one click away —
     * silently destroys the connector configuration too: the root page id, the label filters, and
     * the ENCRYPTED API TOKEN. Nothing warns, nothing errors, and the admin cannot restore it
     * without obtaining a fresh Confluence token. With {@code RESTRICT}/{@code NO ACTION} instead,
     * the collection delete fails with an opaque 23503 from a table the operator was not editing. A
     * dangling name is the intended and strictly better outcome: the connector page can render it
     * as a missing target and let the operator repoint it, which is what the tail of this test
     * pins. Nothing else in the suite looks at what a collection delete does to a connector row.
     */
    @Test
    void deletingTheTargetCollectionLeavesTheInstanceAndItsCredentialsIntact() {
        String collectionName = "IT - Confluence Orphan Probe";
        jdbcClient
            .sql("INSERT INTO collections (id, name) VALUES (:id, :name) "
                + "ON CONFLICT (name) DO NOTHING")
            .param("id", UUID.randomUUID()).param("name", collectionName).update();

        ConfluenceInstance inserted = repository.upsert(new ConfluenceInstance(null,
            "IT_Confluence_Orphan", "it-conf-orphan", "https://mycompany.atlassian.net/wiki",
            "svc@example.com", "enc:orphan-ciphertext", "0123456789abcdef", "DOCS", "12345", null,
            null, collectionName, "10.0", true, true, null, null));
        try {
            jdbcClient.sql("DELETE FROM collections WHERE name = :name")
                .param("name", collectionName).update();
            assertThat(jdbcClient.sql("SELECT count(*) FROM collections WHERE name = :name")
                .param("name", collectionName).query(Long.class).single()).isZero();

            ConfluenceInstance survivor = repository.findBySlug("it-conf-orphan").orElseThrow();
            assertThat(survivor.id()).isEqualTo(inserted.id());
            assertThat(survivor.apiTokenEnc())
                .as("a collection delete must never take a credential with it")
                .isEqualTo("enc:orphan-ciphertext");
            assertThat(survivor.tokenKeyId()).isEqualTo("0123456789abcdef");
            assertThat(survivor.tokenHealth("0123456789abcdef")).isEqualTo(TokenHealth.OK);
            assertThat(survivor.targetCollection()).isEqualTo(collectionName);
            assertThat(survivor.targetTag()).isEqualTo("10.0");
            assertThat(survivor.space()).isEqualTo("DOCS");
            assertThat(survivor.rootPageId()).isEqualTo("12345");
            assertThat(repository.findAll()).extracting(ConfluenceInstance::id)
                .contains(inserted.id());

            // ...and the dangling name stays repairable: repointing it is an ordinary save.
            ConfluenceInstance repointed = repository.upsert(new ConfluenceInstance(survivor.id(),
                survivor.name(), survivor.slug(), survivor.domain(), survivor.email(), null, null,
                survivor.space(), survivor.rootPageId(), null, null, "IT - Confluence",
                survivor.targetTag(), true, true, null, null));
            assertThat(repointed.targetCollection()).isEqualTo("IT - Confluence");
            assertThat(repointed.apiTokenEnc()).isEqualTo("enc:orphan-ciphertext");
        } finally {
            repository.deleteById(inserted.id());
            jdbcClient.sql("DELETE FROM collections WHERE name = :name")
                .param("name", collectionName).update();
        }
    }

    /**
     * {@code target_tag = ''} is not "no tag" — it is a value that breaks two pipelines quietly.
     * At enqueue it becomes {@code List.of("")}, which hard-fails {@code CollectionTagEnqueueValidator}
     * the moment the target collection declares any tag; and it defeats the ingest version-skip,
     * which tests {@code :tag IS NULL} ({@code ''} is neither NULL nor a member of the document's
     * tags array), so every sync re-embeds the entire space instead of skipping unchanged pages.
     *
     * <p>
     * The record's compact constructor normalizes {@code ''} to null, and
     * {@code testUpsertInsertsThenUpdatesKeepingTheId} asserts exactly that — which is the reason
     * the CHECK constraint looks redundant and is not. The constructor only guards writes that go
     * through the record: a data-only restore, a hand-run UPDATE against production, a future
     * repository method that binds the form value straight into SQL, and the V2-style backfill that
     * promoted whatever an administrator once typed into {@code app_config} all reach the column
     * without it. Deleting the constraint as "already enforced in Java" leaves nothing at the layer
     * that actually stores the value, and the corruption surfaces days later as a connector that
     * re-ingests everything on every run. Nothing else in the suite writes this column raw.
     */
    @Test
    void aBlankTargetTagIsRejectedByTheDatabaseEvenOnARawInsert() {
        String slug = "it-conf-blank-tag";
        try {
            assertThatThrownBy(() -> insertRawInstance(slug, ""))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_confluence_instances_target_tag_not_blank");
            assertThat(repository.findBySlug(slug)).isEmpty();

            // Control: NULL IS the supported "no tag", so the constraint rejects '' specifically
            // rather than every row that carries no tag at all.
            insertRawInstance(slug, null);
            assertThat(repository.findBySlug(slug)).isPresent()
                .get().extracting(ConfluenceInstance::targetTag).isNull();
        } finally {
            jdbcClient.sql("DELETE FROM confluence_instances WHERE slug = :slug").param("slug", slug)
                .update();
        }
    }

    /** Bypasses the record's compact constructor, which normalizes '' to null before any write. */
    private void insertRawInstance(String slug, String targetTag) {
        jdbcClient.sql("""
            INSERT INTO confluence_instances (id, name, slug, domain, email, space, root_page_id,
                                              target_collection, target_tag)
            VALUES (:id, :name, :slug, :domain, :email, :space, :rootPageId, :collection, :tag)
            """).param("id", UUID.randomUUID()).param("name", "IT_Confluence_Blank_Tag")
            .param("slug", slug).param("domain", "https://mycompany.atlassian.net/wiki")
            .param("email", "svc@example.com").param("space", "DOCS").param("rootPageId", "12345")
            .param("collection", "IT - Confluence").param("tag", targetTag).update();
    }

    /**
     * Deleting an instance must drop its cached {@link ConfluenceClientService} client — which
     * carries {@code Basic base64(email:plaintextToken)} as a DEFAULT HEADER — as a direct,
     * synchronous consequence of the delete, on a call path where <em>no transaction is active</em>.
     *
     * <p>
     * That last clause is the whole point and it is invisible in the code:
     * {@link ConfluenceInstanceRepository#deleteById} publishes one line after the DELETE and
     * {@code ConfluenceClientService.onInstanceCredentialsChanged} is a plain {@code @EventListener}.
     * Promote that listener to {@code @TransactionalEventListener} — a change that looks like
     * tightening, compiles, and passes review — and Spring SILENTLY SKIPS delivery whenever no
     * transaction is bound to the thread ({@code fallbackExecution} is false by default; it logs
     * "No transaction is active" at trace and returns). {@code deleteById} and {@code clearToken} are
     * plain JdbcClient methods with no {@code @Transactional} anywhere on the class, so that is
     * every non-service caller: the row is deleted, the token is revoked, and the built client with
     * the old credential stays reachable in the heap until the next restart. Deferring the publish
     * to an {@code afterCommit} synchronization has the same shape of consequence.
     *
     * <p>
     * {@code ConfluenceInstanceRepositoryValidationTest} verifies only THAT an event is published,
     * against a mocked publisher with no listener on the other end — it says nothing about whether
     * anything ever receives it, and it passes under both changes above. Nothing else wires the
     * repository and the cache together.
     *
     * <p>
     * The cache is seeded with a sentinel rather than a real client because the only warming entry
     * point runs {@code assertSafeConfluenceUrl}, which calls {@code InetAddress.getAllByName} —
     * a live DNS lookup for {@code mycompany.atlassian.net}. {@code evict} only removes by key, so
     * the sentinel exercises the identical code path.
     */
    @Test
    void deletingAnInstanceDropsItsCachedClientWithNoTransactionInPlay(
        @Autowired ConfluenceClientService clientService) throws Exception {
        ConfluenceInstance inserted =
            repository.upsert(sample("IT_Confluence_Evict", "it-conf-evict", null));

        Field cacheField =
            ConfluenceClientService.class.getDeclaredField("clientCache");
        cacheField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<UUID, Object> clientCache =
            (Map<UUID, Object>) cacheField.get(clientService);

        try {
            clientCache.put(inserted.id(), "client-carrying-the-basic-auth-header");
            assertThat(clientCache).containsKey(inserted.id());

            repository.deleteById(inserted.id());

            assertThat(clientCache)
                .as("the deleted instance's client — and the credential inside it — must be dropped "
                    + "by the delete itself, on a path with no transaction to defer to")
                .doesNotContainKey(inserted.id());
        } finally {
            clientCache.remove(inserted.id());
            repository.deleteById(inserted.id());
        }
    }

    @Test
    void testFindByUnknownIdAndSlugReturnEmpty() {
        assertThat(repository.findById(UUID.randomUUID())).isEmpty();
        assertThat(repository.findBySlug("no-such-slug")).isEmpty();
        assertThat(repository.findByName("no-such-name")).isEmpty();
    }
}
