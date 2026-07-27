package de.palsoftware.yvoke.ingest.core.confluence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import de.palsoftware.yvoke.shared.security.SecretCipher;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class ConfluenceClientServiceSsrfTest {

    private final ConfluenceClientService service =
        new ConfluenceClientService(mock(SecretCipher.class), RestClient.builder(), 3, 1, 5);

    @Test
    void rejectsLoopbackHost() {
        assertThatThrownBy(() -> service.getClient("http://localhost", "e@x.com", "tok"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsPrivateRfc1918Host() {
        assertThatThrownBy(() -> service.getClient("https://10.0.0.5", "e@x.com", "tok"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsLoopbackIpLiteral() {
        assertThatThrownBy(() -> service.getClient("http://127.0.0.1", "e@x.com", "tok"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonHttpScheme() {
        assertThatThrownBy(() -> service.getClient("ftp://example.com", "e@x.com", "tok"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsLinkLocalCloudMetadataAddress() {
        // The canonical SSRF target: the cloud instance-metadata endpoint (link-local).
        assertThatThrownBy(() -> service.getClient("http://169.254.169.254", "e@x.com", "tok"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * The instance-scoped path must keep the SSRF guard. {@code ConfluenceDomains} validates only
     * the URL's SHAPE — it resolves no host and blocks no address — so building a client from a
     * stored instance has to go through the same check as the ad-hoc form path.
     */
    @Test
    void rejectsAnInstanceWhoseStoredDomainResolvesToABlockedAddress() {
        ConfluenceInstance instance = new ConfluenceInstance(UUID.randomUUID(), "Internal",
            "internal", "http://10.0.0.5", "e@x.com", "tok", null, "SPACE", "1", null, null, "coll",
            null, false, true, null, null);

        assertThatThrownBy(() -> service.getClient(instance))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * A row whose domain predates canonicalization (the V2 backfill stored whatever an
     * administrator typed) LOADS — the record is lenient so the admin page can render and fix it —
     * but must never be used to build a client.
     */
    @Test
    void rejectsAnInstanceWhoseStoredDomainIsNotAUrl() {
        ConfluenceInstance instance = new ConfluenceInstance(UUID.randomUUID(), "Legacy", "legacy",
            "wiki.example.com", "e@x.com", "tok", null, "SPACE", "1", null, null, "coll", null,
            false, true, null, null);

        assertThat(instance.domain()).isEqualTo("wiki.example.com");
        assertThatThrownBy(() -> service.getClient(instance))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
