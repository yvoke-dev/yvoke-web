package de.palsoftware.yvoke.ingest.core.confluence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ConfluenceDomainsTest {

    @Test
    void lowercasesHostAndScheme() {
        // The host is the part of a document's source_file that DNS treats as case-insensitive;
        // leaving its casing alone mints a duplicate document set per spelling.
        assertThat(ConfluenceDomains.canonicalize("HTTPS://MyCompany.Atlassian.NET"))
            .isEqualTo("https://mycompany.atlassian.net/wiki");
    }

    @Test
    void stripsTrailingSlash() {
        assertThat(ConfluenceDomains.canonicalize("https://mycompany.atlassian.net/wiki/"))
            .isEqualTo("https://mycompany.atlassian.net/wiki");
    }

    @Test
    void dropsDefaultPorts() {
        assertThat(ConfluenceDomains.canonicalize("https://mycompany.atlassian.net:443"))
            .isEqualTo("https://mycompany.atlassian.net/wiki");
        assertThat(ConfluenceDomains.canonicalize("http://wiki.example.com:80/wiki"))
            .isEqualTo("http://wiki.example.com/wiki");
    }

    @Test
    void keepsNonDefaultPort() {
        assertThat(ConfluenceDomains.canonicalize("https://WIKI.Example.com:8443"))
            .isEqualTo("https://wiki.example.com:8443/wiki");
    }

    @Test
    void preservesContextPathCasing() {
        // Confluence Data Center runs under a context path, and paths are case-SENSITIVE.
        assertThat(ConfluenceDomains.canonicalize("https://WIKI.Example.com/Confluence/DC/wiki/"))
            .isEqualTo("https://wiki.example.com/Confluence/DC/wiki");
    }

    @Test
    void appendsWikiSuffixLikeTheNormalizeDomainItReplaced() {
        // Byte-for-byte parity with the rule ConfluenceClientService.normalizeDomain applied before
        // Wave 3a: source_file identity is built from this value, so a corpus ingested under the
        // old rule must keep resolving to the same documents.
        assertThat(ConfluenceDomains.canonicalize("https://mycompany.atlassian.net"))
            .isEqualTo("https://mycompany.atlassian.net/wiki");
        assertThat(ConfluenceDomains.canonicalize("https://mycompany.atlassian.net/wiki/spaces"))
            .isEqualTo("https://mycompany.atlassian.net/wiki/spaces");
    }

    /**
     * READ-path leniency. The row V2 backfilled holds whatever an administrator typed into the old
     * singleton configuration, and the record's compact constructor runs on every row mapping — so
     * a strict canonicalizer there would 500 the very admin page that exists to fix the value.
     */
    @ParameterizedTest
    @ValueSource(strings = {"mycompany.atlassian.net", "ftp://wiki.example.com", "not a url"})
    void canonicalizeOrKeepReturnsAMalformedValueInsteadOfThrowing(String input) {
        assertThat(ConfluenceDomains.canonicalizeOrKeep(input)).isEqualTo(input.trim());
    }

    @Test
    void canonicalizeOrKeepStillCanonicalizesAValidUrl() {
        assertThat(ConfluenceDomains.canonicalizeOrKeep("  https://MyCompany.Atlassian.NET/  "))
            .isEqualTo("https://mycompany.atlassian.net/wiki");
    }

    @ParameterizedTest
    @ValueSource(strings = {"HTTPS://MyCompany.Atlassian.NET", "https://mycompany.atlassian.net/",
        "https://mycompany.atlassian.net:443/wiki", "https://WIKI.Example.com:8443",
        "https://WIKI.Example.com/Confluence/DC/wiki/", "http://wiki.example.com:80/wiki"})
    void isIdempotent(String input) {
        String once = ConfluenceDomains.canonicalize(input);
        assertThat(ConfluenceDomains.canonicalize(once)).isEqualTo(once);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "mycompany.atlassian.net", "ftp://wiki.example.com",
        "https://", "not a url"})
    void rejectsInputThatIsNotAnHttpUrl(String input) {
        assertThatThrownBy(() -> ConfluenceDomains.canonicalize(input))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> ConfluenceDomains.canonicalize(null))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
