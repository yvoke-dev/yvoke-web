package de.palsoftware.yvoke.ingest.core.confluence;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConfluenceInstanceViewTest {

    private static ConfluenceInstance instance(String apiTokenEnc, String tokenKeyId) {
        return new ConfluenceInstance(UUID.randomUUID(), "iCC Wiki", "icc-wiki",
            "https://acme.atlassian.net/wiki", "svc@example.com", apiTokenEnc, tokenKeyId, "DOCS",
            "12345", "public", "draft", "OIM - Docs", "10.0", true, true, null, null);
    }

    /**
     * The whole point of the view: the record it is mapped from carries the token ciphertext and
     * the key fingerprint, and it travels to a Thymeleaf template where one
     * {@code ${x.apiTokenEnc}} renders a credential into HTML. Making the component absent is what
     * turns "we don't do that" into "it cannot be done".
     */
    @Test
    void theViewHasNoTokenComponentAtAll() {
        String[] components = Arrays.stream(ConfluenceInstanceView.class.getRecordComponents())
            .map(RecordComponent::getName).toArray(String[]::new);

        assertThat(components).doesNotContain("apiTokenEnc", "tokenKeyId");
        assertThat(components).noneMatch(
            name -> name.toLowerCase(Locale.ROOT).contains("token") && !name.equals("tokenHealth"));
    }

    @Test
    void mappingCarriesEveryDisplayedFieldAndTheDerivedTokenHealth() {
        ConfluenceInstance source = instance("enc:ciphertext", "keyA");

        ConfluenceInstanceView view = ConfluenceInstanceView.of(source, "keyA");

        assertThat(view.id()).isEqualTo(source.id());
        assertThat(view.name()).isEqualTo("iCC Wiki");
        assertThat(view.slug()).isEqualTo("icc-wiki");
        assertThat(view.domain()).isEqualTo("https://acme.atlassian.net/wiki");
        assertThat(view.email()).isEqualTo("svc@example.com");
        assertThat(view.space()).isEqualTo("DOCS");
        assertThat(view.rootPageId()).isEqualTo("12345");
        assertThat(view.includeLabels()).isEqualTo("public");
        assertThat(view.excludeLabels()).isEqualTo("draft");
        assertThat(view.targetCollection()).isEqualTo("OIM - Docs");
        assertThat(view.targetTag()).isEqualTo("10.0");
        assertThat(view.processAttachments()).isTrue();
        assertThat(view.enabled()).isTrue();
        assertThat(view.tokenHealth()).isEqualTo(TokenHealth.OK);
    }

    /** A key rotation has to be visible on the list page BEFORE a sync is attempted. */
    @Test
    void aRotatedKeyIsReportedAsUndecryptableAndAMissingTokenAsMissing() {
        assertThat(
            ConfluenceInstanceView.of(instance("enc:ciphertext", "keyA"), "keyB").tokenHealth())
            .isEqualTo(TokenHealth.UNDECRYPTABLE);
        assertThat(ConfluenceInstanceView.of(instance(null, null), "keyA").tokenHealth())
            .isEqualTo(TokenHealth.MISSING);
    }

    /**
     * A row whose stored domain was never canonical (the V2 backfill promoted whatever an
     * administrator once typed) must still produce a view — the list page is the only place from
     * which it can be repaired.
     */
    @Test
    void aRowWithAMalformedStoredDomainStillMaps() {
        ConfluenceInstance broken = new ConfluenceInstance(UUID.randomUUID(), "Legacy", "legacy",
            "acme.atlassian.net", "svc@example.com", null, null, "DOCS", "12345", null, null,
            "OIM - Docs", null, false, true, null, null);

        ConfluenceInstanceView view = ConfluenceInstanceView.of(broken, "keyA");

        assertThat(view.domain()).isEqualTo("acme.atlassian.net");
        assertThat(view.tokenHealth()).isEqualTo(TokenHealth.MISSING);
    }
}
