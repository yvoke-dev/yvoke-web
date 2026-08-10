package de.palsoftware.yvoke.ingest.core.confluence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import de.palsoftware.yvoke.shared.security.SecretCipher;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import java.net.URI;
import java.lang.reflect.Method;

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

    /**
     * The two guards at the top of {@code fetchSearchPage} are the ONLY SSRF and host check on the
     * crawl path, and no test executes them: every crawl test drives a scripted subclass that
     * OVERRIDES that method, so both lines have run zero times since they were written. They are
     * what stands between a compromised (or merely hostile) Confluence and the instance's
     * credentials: the crawl follows {@code _links.next} verbatim, so a response naming
     * {@code 169.254.169.254} or another site would be fetched with this instance's
     * {@code Basic base64(email:token)} default header attached — SSRF into the cloud metadata
     * endpoint plus credential egress to an attacker-chosen host, and the crawl would report a
     * perfectly ordinary page batch.
     *
     * <p>
     * Both cases use IP literals, so {@code InetAddress.getAllByName} resolves them without DNS and
     * nothing here touches the network. The instance deliberately carries NO stored token: if
     * either guard stopped firing, the call would fail at client construction with
     * {@code IllegalStateException} ("no API token is stored") rather than opening a socket — which
     * keeps the test hermetic and still turns a removed guard red.
     */
    @Test
    void aCrawlPageUrlIsRefusedWhenItLeavesTheInstanceHostOrResolvesToABlockedAddress() {
        ConfluenceInstance instance = new ConfluenceInstance(UUID.randomUUID(), "Hostile",
            "hostile", "https://203.0.113.5", "e@x.com", null, null, "SPACE", "1", null, null,
            "coll", null, false, true, null, null);

        // A `next` link on the instance's OWN host pointing at the link-local metadata service:
        // the host check cannot see this one, only the address check can.
        assertThatThrownBy(() -> service.fetchSearchPage(instance, "https://169.254.169.254",
            URI.create("https://169.254.169.254/rest/api/content/search?cursor=x")))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("blocked address");

        // A publicly routable but DIFFERENT host: it passes the address check, so only the
        // same-host check keeps the Authorization header from being sent to it.
        assertThatThrownBy(() -> service.fetchSearchPage(instance, "https://203.0.113.5",
            URI.create("https://198.51.100.7/rest/api/content/search?cursor=x")))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("different host");
    }

    private static String cql(String space, String rootPageId, String includeLabels,
        String excludeLabels) throws Exception {
        ConfluenceClientService client =
            new ConfluenceClientService(mock(SecretCipher.class), RestClient.builder(), 3, 1, 5);
        Method m = ConfluenceClientService.class.getDeclaredMethod("buildCqlQuery", String.class,
            String.class, String.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(client, space, rootPageId, includeLabels, excludeLabels);
    }

    /**
     * The crawl CQL is built by string concatenation from operator-supplied values (space, root
     * page id and label filters all come from the connector row), so quote handling is the only
     * thing separating a filter value from the query grammar. An unescaped {@code '} or {@code "}
     * would let a label like {@code x" or type=page and space="OTHER} widen the crawl beyond the
     * configured space — pulling another space's pages into this collection, which reads downstream
     * as a corpus that simply contains documents nobody put there. Scoping to the space AND the
     * ancestor/id pair is what keeps a crawl to one page tree.
     */
    @Test
    void theCrawlQueryScopesToTheSpaceAndRootAndEscapesQuotesInEveryOperatorValue()
        throws Exception {
        assertThat(cql("SPACE", "12345", null, null))
            .isEqualTo("type=page and space='SPACE' and (ancestor='12345' or id='12345')");

        // Include labels are quoted AND escaped; exclude labels get one clause each.
        String withLabels = cql("SPACE", "12345", "de, en", "draft");
        assertThat(withLabels).contains(" and label in (\"de\",\"en\")")
            .contains(" and label!=\"draft\"");

        // A value carrying quotes must not be able to close out of its literal.
        String injected = cql("SP'ACE", "12345", "x\" or type=page and space=\"OTHER", null);
        assertThat(injected).as("a single quote in the space must stay escaped")
            .contains("space='SP\\'ACE'");
        assertThat(injected).as("a double quote in a label must stay escaped")
            .doesNotContain("space=\"OTHER\"");
        assertThat(injected).contains("\\\"");
    }
}
