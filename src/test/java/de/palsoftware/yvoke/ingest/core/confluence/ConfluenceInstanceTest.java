package de.palsoftware.yvoke.ingest.core.confluence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConfluenceInstanceTest {

    private static ConfluenceInstance withTag(String tag) {
        return instance(tag, "enc:abc", "keyA");
    }

    private static ConfluenceInstance instance(String tag, String apiTokenEnc, String tokenKeyId) {
        return new ConfluenceInstance(UUID.randomUUID(), "Docs", "docs",
            "https://mycompany.atlassian.net/wiki", "svc@example.com", apiTokenEnc, tokenKeyId,
            "DOCS", "12345", null, null, "OIM - Docs", tag, false, true, null, null);
    }

    private static ConfluenceInstance withDomain(String domain) {
        return new ConfluenceInstance(UUID.randomUUID(), "Docs", "docs", domain, "svc@example.com",
            null, null, "DOCS", "12345", null, null, "OIM - Docs", null, false, true, null, null);
    }

    private static ConfluenceInstance withSlug(String slug) {
        return new ConfluenceInstance(UUID.randomUUID(), "Docs", slug,
            "https://mycompany.atlassian.net/wiki", "svc@example.com", null, null, "DOCS", "12345",
            null, null, "OIM - Docs", null, false, true, null, null);
    }

    @Test
    void blankTargetTagBecomesNull() {
        // '' violates the table CHECK, becomes List.of("") at enqueue and defeats the ingest
        // version-skip (which tests `:tag IS NULL`).
        assertThat(withTag("").targetTag()).isNull();
        assertThat(withTag("   ").targetTag()).isNull();
        assertThat(withTag(null).targetTag()).isNull();
    }

    @Test
    void nonBlankTargetTagIsTrimmedAndKept() {
        assertThat(withTag(" 10.0 ").targetTag()).isEqualTo("10.0");
    }

    @Test
    void tokenHealthIsMissingWithoutCiphertext() {
        assertThat(instance("10.0", null, null).tokenHealth("keyA")).isEqualTo(TokenHealth.MISSING);
        assertThat(instance("10.0", "  ", "keyA").tokenHealth("keyA"))
            .isEqualTo(TokenHealth.MISSING);
    }

    @Test
    void tokenHealthIsOkWhenFingerprintMatches() {
        assertThat(instance("10.0", "enc:abc", "keyA").tokenHealth("keyA"))
            .isEqualTo(TokenHealth.OK);
    }

    @Test
    void tokenHealthIsOkForLegacyRowWithoutFingerprint() {
        // The backfilled row records no fingerprint; assume the current key rather than crying
        // wolf.
        assertThat(instance("10.0", "enc:abc", null).tokenHealth("keyA")).isEqualTo(TokenHealth.OK);
        assertThat(instance("10.0", "enc:abc", "").tokenHealth("keyA")).isEqualTo(TokenHealth.OK);
    }

    @Test
    void tokenHealthIsUndecryptableForFingerprintlessCiphertextWithoutAKey() {
        // Restoring a production dump into a key-less box: a ciphertext is on file and NOTHING can
        // read it. Reporting OK here would hide the one state this mechanism exists to catch.
        assertThat(instance("10.0", "enc:abc", null).tokenHealth(null))
            .isEqualTo(TokenHealth.UNDECRYPTABLE);
        assertThat(instance("10.0", "enc:abc", "").tokenHealth(null))
            .isEqualTo(TokenHealth.UNDECRYPTABLE);
        // Legacy PLAINTEXT (no "enc:" prefix) is readable without a key, so it stays OK.
        assertThat(instance("10.0", "legacy-plaintext-token", null).tokenHealth(null))
            .isEqualTo(TokenHealth.OK);
    }

    @Test
    void domainIsCanonicalizedOnConstruction() {
        // V2 claims rows written by the application are canonicalized on save; the record is what
        // makes that true, because source_file identity is built from this value.
        assertThat(withDomain("  https://Acme.Atlassian.NET/  ").domain())
            .isEqualTo("https://acme.atlassian.net/wiki");
        assertThat(withDomain("https://acme.atlassian.net:443/wiki").domain())
            .isEqualTo("https://acme.atlassian.net/wiki");
    }

    /**
     * A domain the canonicalizer cannot parse is KEPT rather than rejected, because this
     * constructor also runs on every row mapped out of {@code confluence_instances}: the row V2
     * backfilled holds whatever an administrator once typed into {@code app_config}, and throwing
     * here would turn {@code findAll()} — and therefore the whole connector admin page — into a 500
     * with no way to reach the form that fixes the value.
     *
     * <p>
     * The invariant did not move to nowhere: {@link ConfluenceInstanceRepository#upsert} rejects it
     * on the write path (see {@code ConfluenceInstanceRepositoryValidationTest}), and
     * {@code ConfluenceClientService.getClient} rejects it again before any request is issued.
     */
    @Test
    void invalidDomainSurvivesConstructionSoStoredRowsStillLoad() {
        assertThat(withDomain("not-a-url").domain()).isEqualTo("not-a-url");
        assertThat(withDomain("ftp://acme.example.com").domain())
            .isEqualTo("ftp://acme.example.com");
    }

    @Test
    void slugMustMatchTheJobKindSafeFormat() {
        // The slug is embedded in the job kind "confluence-page-import:<slug>", which JobService
        // splits on ':'.
        assertThat(withSlug("oim-docs-9-3-1").slug()).isEqualTo("oim-docs-9-3-1");
        assertThatThrownBy(() -> withSlug("has:colon"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> withSlug("has space"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> withSlug("has/slash"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> withSlug("UPPER")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> withSlug("-leading-dash"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> withSlug("")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requiredColumnsAreRejectedWhenNull() {
        // The seven NOT NULL columns fail here, at the boundary, instead of arriving as an opaque
        // DataIntegrityViolationException from the driver.
        assertThatThrownBy(() -> new ConfluenceInstance(null, null, "docs",
            "https://acme.atlassian.net/wiki", "svc@example.com", null, null, "DOCS", "1", null,
            null, "OIM - Docs", null, false, true, null, null))
            .isInstanceOf(NullPointerException.class).hasMessageContaining("name");
        assertThatThrownBy(() -> new ConfluenceInstance(null, "Docs", null,
            "https://acme.atlassian.net/wiki", "svc@example.com", null, null, "DOCS", "1", null,
            null, "OIM - Docs", null, false, true, null, null))
            .isInstanceOf(NullPointerException.class).hasMessageContaining("slug");
        assertThatThrownBy(
            () -> new ConfluenceInstance(null, "Docs", "docs", null, "svc@example.com", null, null,
                "DOCS", "1", null, null, "OIM - Docs", null, false, true, null, null))
            .isInstanceOf(NullPointerException.class).hasMessageContaining("domain");
        assertThatThrownBy(() -> new ConfluenceInstance(null, "Docs", "docs",
            "https://acme.atlassian.net/wiki", null, null, null, "DOCS", "1", null, null,
            "OIM - Docs", null, false, true, null, null)).isInstanceOf(NullPointerException.class)
            .hasMessageContaining("email");
        assertThatThrownBy(() -> new ConfluenceInstance(null, "Docs", "docs",
            "https://acme.atlassian.net/wiki", "svc@example.com", null, null, null, "1", null, null,
            "OIM - Docs", null, false, true, null, null)).isInstanceOf(NullPointerException.class)
            .hasMessageContaining("space");
        assertThatThrownBy(() -> new ConfluenceInstance(null, "Docs", "docs",
            "https://acme.atlassian.net/wiki", "svc@example.com", null, null, "DOCS", null, null,
            null, "OIM - Docs", null, false, true, null, null))
            .isInstanceOf(NullPointerException.class).hasMessageContaining("rootPageId");
        assertThatThrownBy(() -> new ConfluenceInstance(null, "Docs", "docs",
            "https://acme.atlassian.net/wiki", "svc@example.com", null, null, "DOCS", "1", null,
            null, null, null, false, true, null, null)).isInstanceOf(NullPointerException.class)
            .hasMessageContaining("targetCollection");
    }

    @Test
    void tokenHealthIsUndecryptableAfterKeyRotation() {
        assertThat(instance("10.0", "enc:abc", "keyA").tokenHealth("keyB"))
            .isEqualTo(TokenHealth.UNDECRYPTABLE);
        // Encryption switched off entirely while a ciphertext is on file.
        assertThat(instance("10.0", "enc:abc", "keyA").tokenHealth(null))
            .isEqualTo(TokenHealth.UNDECRYPTABLE);
    }
}
