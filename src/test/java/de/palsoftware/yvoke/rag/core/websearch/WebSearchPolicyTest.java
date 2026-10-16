package de.palsoftware.yvoke.rag.core.websearch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.palsoftware.yvoke.rag.core.websearch.WebSearchPolicy.AllowEntry;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class WebSearchPolicyTest {

    @Test
    void normalizeDomainStripsSchemeWildcardDotsPortPathQueryAndFragment() {
        assertThat(WebSearchPolicy
            .normalizeDomain("  HTTPS://*.Support.OneIdentity.com.:8443/kb/123?foo=bar#sec "))
            .isEqualTo("support.oneidentity.com");
        assertThat(WebSearchPolicy.normalizeDomain("http://www.oneidentity.com/community/"))
            .isEqualTo("www.oneidentity.com");
        assertThat(WebSearchPolicy.normalizeDomain("..sub.support.oneidentity.com.."))
            .isEqualTo("sub.support.oneidentity.com");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "https://", "http://", "ftp://support.oneidentity.com",
        "127.0.0.1", "169.254.169.254", "[::1]", "https://[::1]:8080/kb", "localhost", "intranet",
        "a..b.com", "-bad.example.com", "bad-.example.com", "evil.com@support.oneidentity.com",
        "support.oneidentity.com:notaport"})
    void normalizeDomainAndParseAllowEntryRejectInvalidOrInjectedHosts(String invalid) {
        assertThat(WebSearchPolicy.normalizeDomain(invalid)).isEmpty();
        assertThat(WebSearchPolicy.parseAllowEntry(invalid)).isNull();
    }

    @Test
    void parseAllowEntryAndParseAllowEntriesNormalizeHostPathAndDeduplicate() {
        assertThat(WebSearchPolicy.parseAllowEntry("support.oneidentity.com/"))
            .isEqualTo(new AllowEntry("support.oneidentity.com", ""));

        assertThat(
            WebSearchPolicy.parseAllowEntry("https://WWW.ONEIDENTITY.COM/Community//?foo=bar#frag"))
            .isEqualTo(new AllowEntry("www.oneidentity.com", "/community"));

        List<AllowEntry> entries = WebSearchPolicy.parseAllowEntries(
            "support.oneidentity.com, www.oneidentity.com/community/, support.oneidentity.com/, https://WWW.ONEIDENTITY.COM/Community");

        assertThat(entries).containsExactly(new AllowEntry("support.oneidentity.com", ""),
            new AllowEntry("www.oneidentity.com", "/community"));
    }

    @Test
    void parseAllowEntriesReturnsEmptyForNullBlankOrAllInvalidInput() {
        assertThat(WebSearchPolicy.parseAllowEntries(null)).isEmpty();
        assertThat(WebSearchPolicy.parseAllowEntries("   ")).isEmpty();
        assertThat(WebSearchPolicy.parseAllowEntries("localhost, 127.0.0.1, ,")).isEmpty();
    }

    @Test
    void isUrlDomainAllowedPermitsExactHostSubdomainsAndSegmentAlignedPaths() {
        List<AllowEntry> allowed = WebSearchPolicy
            .parseAllowEntries("support.oneidentity.com, www.oneidentity.com/community/");

        assertThat(WebSearchPolicy.isUrlDomainAllowed("https://support.oneidentity.com/kb/4380945",
            allowed)).isTrue();
        assertThat(
            WebSearchPolicy.isUrlDomainAllowed("https://sub.support.oneidentity.com/x", allowed))
            .isTrue();
        assertThat(WebSearchPolicy.isUrlDomainAllowed("http://SUPPORT.ONEIDENTITY.COM", allowed))
            .isTrue();
        assertThat(
            WebSearchPolicy.isUrlDomainAllowed("https://www.oneidentity.com/community", allowed))
            .isTrue();
        assertThat(
            WebSearchPolicy.isUrlDomainAllowed("https://www.oneidentity.com/community/", allowed))
            .isTrue();
        assertThat(WebSearchPolicy.isUrlDomainAllowed(
            "https://www.oneidentity.com/community/threads/123?page=2#top", allowed)).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "https://evil-oneidentity.com/kb",
        "https://support.oneidentity.com.evil.org/kb", "javascript:alert(1)", "file:///etc/passwd",
        "ftp://support.oneidentity.com/a", "https:///community/post",
        "https://evil.com@support.oneidentity.com/kb/1",
        "https://www.oneidentity.com/community-blog/post", "https://www.oneidentity.com/community2",
        "https://www.oneidentity.com/products/oim", "https://www.oneidentity.com/",
        "https://www.oneidentity.com", "https://www.oneidentity.com/community/../admin",
        "https://www.oneidentity.com/community/%2e%2e/admin",
        "https://www.oneidentity.com/community/%2E%2E/admin",
        "https://www.oneidentity.com/community\\..\\admin",
        "https://www.oneidentity.com//community-evil",
        "https://support.oneidentity.com/kb/..%2fadmin", "http://[::1"})
    void isUrlDomainAllowedRejectsSpoofedHostsNonHttpSiblingPathsAndTraversal(String candidateUrl) {
        List<AllowEntry> allowed = List.of(new AllowEntry("support.oneidentity.com", ""),
            new AllowEntry("www.oneidentity.com", "/community"));

        assertThat(WebSearchPolicy.isUrlDomainAllowed(candidateUrl, allowed)).isFalse();
    }

    @Test
    void isUrlDomainAllowedReturnsFalseWhenAllowedEntriesIsNullOrEmpty() {
        assertThat(
            WebSearchPolicy.isUrlDomainAllowed("https://support.oneidentity.com/kb/4380945", null))
            .isFalse();
        assertThat(WebSearchPolicy.isUrlDomainAllowed("https://support.oneidentity.com/kb/4380945",
            List.of())).isFalse();
    }

    @Test
    void buildScopedQueryAppendsSiteOperatorsForAllAllowedEntries() {
        List<AllowEntry> allowed = WebSearchPolicy
            .parseAllowEntries("support.oneidentity.com, www.oneidentity.com/community/");

        String scoped = WebSearchPolicy.buildScopedQuery("  OIM 9.3.1 hotfix 4380945  ", allowed);

        assertThat(scoped).isEqualTo(
            "OIM 9.3.1 hotfix 4380945 (site:support.oneidentity.com OR site:www.oneidentity.com/community)");
    }

    @Test
    void buildScopedQueryRejectsEmptyAllowListOrBlankQuery() {
        List<AllowEntry> allowed = List.of(new AllowEntry("support.oneidentity.com", ""));

        assertThatThrownBy(() -> WebSearchPolicy.buildScopedQuery("OIM 9.3", null))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> WebSearchPolicy.buildScopedQuery("OIM 9.3", List.of()))
            .isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> WebSearchPolicy.buildScopedQuery(null, allowed))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WebSearchPolicy.buildScopedQuery("   ", allowed))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WebSearchPolicy.buildScopedQuery("q".repeat(501), allowed))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("500");
        assertThat(WebSearchPolicy.buildScopedQuery("q".repeat(500), allowed))
            .startsWith("q".repeat(500) + " (site:support.oneidentity.com)");
    }
}
