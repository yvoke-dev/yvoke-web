package de.palsoftware.yvoke.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.stream.Stream;

class ApiKeyAuthenticationFilterTest {

    private final ApiKeyAuthenticationFilter filter =
        new ApiKeyAuthenticationFilter("X-API-Key", "the-secret-key");

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    /**
     * The ingest API key is a bearer credential: whoever presents it is granted ROLE_INGEST, which
     * can enqueue jobs and write into a collection the caller can then search.
     * {@code MessageDigest.isEqual} compares every byte regardless of where the first mismatch is;
     * {@code String.equals} and {@code Arrays.equals} both return on the first differing byte, so
     * how long the 401 takes to come back leaks the key's length and then, one byte at a time, the
     * key itself. This filter answers unauthenticated requests, so an attacker can take as many
     * samples as they need — the textbook remote timing oracle.
     *
     * <p>
     * The three behavioural tests in this file cannot see the difference: a valid key, a wrong key
     * and a missing key produce byte-identical outcomes under either comparison, so swapping in
     * {@code equals} — which reads as a simplification, since the field would then be a plain
     * String — leaves the whole suite green. A genuine timing assertion would be flaky on shared CI
     * hardware, so this pins the MECHANISM instead, in the same source-pattern style as
     * {@code ThreadTemplateXssSafetyTest}. The {@code contains} assertion is load-bearing: without
     * it a wrong path would make every {@code doesNotContain} below vacuously true.
     */
    @Test
    void theApiKeyComparisonStaysConstantTime() throws IOException {
        String source =
            Files.readString(Path.of("src/main/java/de/palsoftware/yvoke/shared/security/"
                + "ApiKeyAuthenticationFilter.java"), StandardCharsets.UTF_8);

        assertThat(source)
            .as("the key comparison must be the constant-time one (a wrong path would make the "
                + "assertions below vacuous)")
            .contains("MessageDigest.isEqual(");
        assertThat(source).as("String.equals short-circuits on the first differing byte")
            .doesNotContain("requestKey.equals(").doesNotContain(".equals(requestKey)");
        assertThat(source).as("Arrays.equals/Objects.equals short-circuit too — not substitutes")
            .doesNotContain("Arrays.equals").doesNotContain("Objects.equals");
    }

    /**
     * {@code ROLE_INGEST} is not a human role — it is the NAME OF THE API KEY'S BLAST RADIUS.
     * {@code apiKeySecurityFilterChain} admits {@code hasAnyRole("INGEST", "ADMIN")} on the corpus
     * WRITE surface ({@code /api/ingest/**}, {@code /api/document/**}, {@code /api/jobs/**}) with
     * CSRF disabled, so whoever holds that authority can upload files and queue import jobs into
     * any knowledge area — content that then becomes searchable by everyone and is cited as
     * authoritative, that spends money on summarisation and embedding, and that is never audited
     * (only admin-screen imports are). This test pins the OTHER half of that contract: the
     * authority may be minted in exactly ONE place, so presenting the key is the only way to obtain
     * it and no browser session can.
     *
     * <p>
     * The regression is a one-line, entirely reasonable-looking edit.
     * {@code EntraAuthoritiesMapper} is additive and already has the exact {@code else if (<claim>)
     * { add(new SimpleGrantedAuthority(...)) }} shape, so wiring a tenant app role named "Ingest"
     * to {@code ROLE_INGEST} reads in review as "we added an ingest role" — while it actually hands
     * every browser session holding that app role the machine credential's authority, on a
     * CSRF-disabled chain, with no audit trail. It compiles and every existing test stays green:
     * {@code aPlainUserSessionCannotReachTheCorpusWriteApis} in {@code SecurityGatingIT} guards
     * only the opposite direction (that {@code ROLE_USER} stays out) and nothing anywhere
     * enumerates who may mint {@code ROLE_INGEST}.
     *
     * <p>
     * Kept a plain unit test so it costs no Spring context, in the same source-pattern style as
     * {@code theApiKeyComparisonStaysConstantTime} and {@code ThreadTemplateXssSafetyTest}. The
     * positive {@code contains} assertion runs first and is load-bearing: without it a wrong root
     * path or a changed expression would make the {@code hasSize(1)} assertion vacuously true. The
     * sources are read as bytes and decoded ourselves rather than shelled out to {@code grep} — a
     * single raw NUL byte once made a file in this very package family invisible to {@code grep},
     * which silently shortened an audit of exactly this shape.
     */
    @Test
    void roleIngestIsMintedInExactlyOnePlaceInTheProductionSources() throws IOException {
        String mintingExpression = "new SimpleGrantedAuthority(\"ROLE_INGEST\")";
        List<String> mintingSites = new ArrayList<>();

        try (Stream<Path> sources = Files.walk(Path.of("src/main/java"))) {
            List<Path> javaFiles = sources.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".java")).toList();
            for (Path javaFile : javaFiles) {
                // Decode the bytes ourselves: a stray NUL byte makes a file binary to grep, and
                // this package family has already lost one MCP tool from an audit that way.
                String text = new String(Files.readAllBytes(javaFile), StandardCharsets.UTF_8);
                if (text.contains(mintingExpression)) {
                    mintingSites.add(javaFile.toString().replace('\\', '/'));
                }
            }
        }

        assertThat(mintingSites)
            .as("the API-key filter must still be the site that mints ROLE_INGEST (a wrong scan "
                + "root would make the size assertion below vacuous)")
            .contains("src/main/java/de/palsoftware/yvoke/shared/security/"
                + "ApiKeyAuthenticationFilter.java");
        assertThat(mintingSites)
            .as("ROLE_INGEST is the API key's blast radius on the CSRF-disabled corpus WRITE "
                + "surface; a second minting site would grant it to browser sessions")
            .hasSize(1);
    }

    @Test
    void validKeyAuthenticatesAsScopedIngestRoleAndContinues() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(request.getHeader("X-API-Key")).thenReturn("the-secret-key");

        filter.doFilter(request, response, chain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getAuthorities()).extracting("authority").containsExactly("ROLE_INGEST");
        verify(chain).doFilter(request, response);
        verify(response, never()).setStatus(anyInt());
    }

    /**
     * The X-API-Key chain is deliberately NOT stateless — the admin UI reaches {@code /api/jobs/**}
     * with its cookie session (the job-progress EventSource) — so by the time this filter runs,
     * {@code SecurityContextHolderFilter} has already loaded a browser admin's authentication into
     * the context. The filter must therefore OVERWRITE that authentication, not fill a gap: the API
     * key is a scoped machine credential worth exactly {@code ROLE_INGEST}, and the request it
     * authenticates has to run with that authority and no more.
     *
     * <p>
     * Turning the assignment into the "don't clobber an existing authentication" guard that most
     * filters in the wild use — an edit that reads as defensive hardening — silently promotes every
     * key-bearing request made from a logged-in admin's browser to full {@code ROLE_ADMIN} on the
     * corpus WRITE surface ({@code /api/ingest/**}, {@code /api/document/**},
     * {@code /api/jobs/**}), and it stamps the admin rather than {@code api-user} on everything
     * downstream that reads {@code SecurityContextHolder} — user resolution, job ownership, cost
     * attribution — so the audit trail names a human who never made the call. It is also the
     * direction nobody checks: the request still succeeds, just with more authority than the
     * credential bought.
     *
     * <p>
     * {@code validKeyAuthenticatesAsScopedIngestRoleAndContinues} cannot see any of this. It starts
     * from an empty {@code SecurityContextHolder}, where "fill the gap" and "overwrite" are
     * indistinguishable, so it stays green through the whole regression.
     */
    @Test
    void aValidApiKeyDowngradesAnAmbientAdminSessionToRoleIngest() throws Exception {
        SecurityContextHolder.getContext()
            .setAuthentication(new UsernamePasswordAuthenticationToken("ambient-admin", "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));

        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(request.getHeader("X-API-Key")).thenReturn("the-secret-key");

        filter.doFilter(request, response, chain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getName())
            .as("the key must authenticate as its own principal, not adopt the browser session's")
            .isEqualTo("api-user");
        assertThat(auth.getAuthorities()).extracting("authority")
            .as("the ambient ROLE_ADMIN must not survive an API-key request")
            .containsExactly("ROLE_INGEST");
        verify(chain).doFilter(request, response);
    }

    @Test
    void invalidKeyIsRejectedWithUnauthorizedAndDoesNotContinue() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(request.getHeader("X-API-Key")).thenReturn("wrong-key");
        when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));

        filter.doFilter(request, response, chain);

        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verify(chain, never()).doFilter(any(), any());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    /**
     * The {@code !requestKey.isBlank()} guard is what makes an empty or whitespace-only header a
     * NON-EVENT, and it holds two distinct contracts that fail in opposite directions.
     *
     * <p>
     * First, the contract this test now owns outright: header absent or blank → the request
     * continues, and session auth may still succeed. Drop the guard and a blank header takes the
     * mismatch branch instead — an immediate 401 with the chain aborted. That breaks live callers
     * that never meant to authenticate with a key at all: the admin UI's job-progress
     * {@code EventSource} reaches this chain on its cookie session, and any script or container
     * that templates an unset variable into the header ({@code -H "X-API-Key: $API_KEY"} expanding
     * to nothing) would be hard-401ed on a header it did not really send, with session auth never
     * consulted.
     *
     * <p>
     * Second — stanza 3 — defence in depth for the empty-key case. The constructor maps a null key
     * to {@code new byte[0]}, and {@code MessageDigest.isEqual(new byte[0], "".getBytes(UTF_8))}
     * returns {@code true} (verified by running it: the implementation returns {@code lenA == 0}
     * when {@code lenB == 0}). So without {@code isBlank()} a valueless header would be byte-equal
     * to an unconfigured key and hand out {@code ROLE_INGEST} on the corpus WRITE surface to any
     * anonymous caller. Today TWO independent guards prevent that — {@code apiKeyConfigured()}
     * refuses to install the filter, and {@code isBlank()} refuses to make the comparison — and
     * neither had a test, so either could be deleted as dead weight with the whole suite green.
     * This is also the only reachable way to exercise the constructor's {@code byte[0]} branch,
     * since {@code SecurityConfig} can never construct the filter that way.
     *
     * <p>
     * No existing test sees any of this: {@code missingKeyPassesThroughWithoutAuthenticating}
     * short-circuits on the {@code != null} half, the valid- and invalid-key tests both pass
     * non-blank values, and {@code theApiKeyComparisonStaysConstantTime} only inspects the
     * comparison expression. It stays a plain unit test, so it mints no Spring context.
     */
    @Test
    void aBlankApiKeyHeaderNeitherAuthenticatesNorRejectsEvenWhenTheConfiguredKeyIsEmpty()
        throws Exception {
        // (1) Empty header against a real configured key: a non-event, not a 401.
        HttpServletRequest emptyRequest = mock(HttpServletRequest.class);
        HttpServletResponse emptyResponse = mock(HttpServletResponse.class);
        FilterChain emptyChain = mock(FilterChain.class);
        when(emptyRequest.getHeader("X-API-Key")).thenReturn("");
        // Stubbed so that a regression takes its 401 branch to completion and fails on the
        // assertions below, rather than dying in an NPE that names nothing.
        when(emptyResponse.getWriter()).thenReturn(new PrintWriter(new StringWriter()));

        filter.doFilter(emptyRequest, emptyResponse, emptyChain);

        verify(emptyChain).doFilter(emptyRequest, emptyResponse);
        verify(emptyResponse, never()).setStatus(anyInt());
        assertThat(SecurityContextHolder.getContext().getAuthentication())
            .as("an empty X-API-Key must leave the request as it found it, so session auth can "
                + "still succeed downstream")
            .isNull();

        // (2) Whitespace-only header: identical treatment.
        HttpServletRequest blankRequest = mock(HttpServletRequest.class);
        HttpServletResponse blankResponse = mock(HttpServletResponse.class);
        FilterChain blankChain = mock(FilterChain.class);
        when(blankRequest.getHeader("X-API-Key")).thenReturn("   ");
        when(blankResponse.getWriter()).thenReturn(new PrintWriter(new StringWriter()));

        filter.doFilter(blankRequest, blankResponse, blankChain);

        verify(blankChain).doFilter(blankRequest, blankResponse);
        verify(blankResponse, never()).setStatus(anyInt());
        assertThat(SecurityContextHolder.getContext().getAuthentication())
            .as("a whitespace-only X-API-Key must not be rejected either").isNull();

        // (3) Empty header against an UNCONFIGURED key: byte[0] vs byte[0] is isEqual == true, so
        // isBlank() is the only thing standing between a valueless header and ROLE_INGEST.
        ApiKeyAuthenticationFilter unconfiguredFilter =
            new ApiKeyAuthenticationFilter("X-API-Key", null);
        HttpServletRequest unconfiguredRequest = mock(HttpServletRequest.class);
        HttpServletResponse unconfiguredResponse = mock(HttpServletResponse.class);
        FilterChain unconfiguredChain = mock(FilterChain.class);
        when(unconfiguredRequest.getHeader("X-API-Key")).thenReturn("");
        when(unconfiguredResponse.getWriter()).thenReturn(new PrintWriter(new StringWriter()));

        unconfiguredFilter.doFilter(unconfiguredRequest, unconfiguredResponse, unconfiguredChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication())
            .as("an empty configured key is byte-identical to an empty submitted key; a valueless "
                + "header must never buy ROLE_INGEST on the corpus WRITE surface")
            .isNull();
        verify(unconfiguredChain).doFilter(unconfiguredRequest, unconfiguredResponse);
        verify(unconfiguredResponse, never()).setStatus(anyInt());
    }

    @Test
    void missingKeyPassesThroughWithoutAuthenticating() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(request.getHeader("X-API-Key")).thenReturn(null);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
