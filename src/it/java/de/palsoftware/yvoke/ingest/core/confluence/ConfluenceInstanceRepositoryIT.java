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

    @Test
    void testFindByUnknownIdAndSlugReturnEmpty() {
        assertThat(repository.findById(UUID.randomUUID())).isEmpty();
        assertThat(repository.findBySlug("no-such-slug")).isEmpty();
        assertThat(repository.findByName("no-such-name")).isEmpty();
    }
}
