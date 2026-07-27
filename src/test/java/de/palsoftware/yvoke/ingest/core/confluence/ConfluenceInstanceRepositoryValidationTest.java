package de.palsoftware.yvoke.ingest.core.confluence;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The write-path guards of {@link ConfluenceInstanceRepository}, which run before any SQL — the
 * behaviour that needs a live database is covered by {@code ConfluenceInstanceRepositoryIT}.
 *
 * <p>
 * These matter because the record itself is deliberately lenient: it also maps rows on READ, where
 * a throw would take the admin page down. Rejecting a bad value has to happen somewhere, and that
 * somewhere is the save.
 */
class ConfluenceInstanceRepositoryValidationTest {

    private final JdbcClient jdbcClient = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final ConfluenceInstanceRepository repository =
        new ConfluenceInstanceRepository(jdbcClient, events);

    private static ConfluenceInstance withDomain(String domain) {
        return new ConfluenceInstance(UUID.randomUUID(), "Docs", "docs", domain, "svc@example.com",
            null, null, "DOCS", "12345", null, null, "OIM - Docs", null, false, true, null, null);
    }

    @Test
    void upsertRejectsADomainThatIsNotAnAbsoluteHttpUrl() {
        assertThatThrownBy(() -> repository.upsert(withDomain("wiki.example.com")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.upsert(withDomain("ftp://wiki.example.com")))
            .isInstanceOf(IllegalArgumentException.class);
        // Nothing reached the database: the row is rejected, not half-written.
        verifyNoInteractions(jdbcClient);
    }

    @Test
    void upsertRejectsAKeyFingerprintWithoutItsCiphertext() {
        ConfluenceInstance halfWritten = new ConfluenceInstance(UUID.randomUUID(), "Docs", "docs",
            "https://acme.atlassian.net/wiki", "svc@example.com", null, "keyA", "DOCS", "12345",
            null, null, "OIM - Docs", null, false, true, null, null);

        assertThatThrownBy(() -> repository.upsert(halfWritten))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("api_token_enc");
        verifyNoInteractions(jdbcClient);
    }

    // ---------------------------------------------------------------------
    // A deleted (or de-credentialed) instance left its built RestClient — which carries
    // `Basic base64(email:plaintextToken)` as a default header — alive in the client cache until
    // the next restart. Announcing the change is what lets the cache drop it.
    // ---------------------------------------------------------------------

    @Test
    void deletingAnInstanceAnnouncesItSoItsCachedClientIsDropped() {
        UUID id = UUID.randomUUID();

        repository.deleteById(id);

        verify(events).publishEvent(new ConfluenceInstanceCredentialsChangedEvent(id));
    }

    @Test
    void clearingATokenAnnouncesItSoTheCachedClientStopsCarryingTheOldCredential() {
        UUID id = UUID.randomUUID();

        repository.clearToken(id);

        verify(events).publishEvent(new ConfluenceInstanceCredentialsChangedEvent(id));
    }
}
