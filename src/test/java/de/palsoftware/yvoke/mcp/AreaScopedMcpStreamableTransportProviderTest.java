package de.palsoftware.yvoke.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.palsoftware.yvoke.rag.prompt.Playbook;
import de.palsoftware.yvoke.rag.prompt.PlaybookRepository;
import de.palsoftware.yvoke.shared.security.AreaAccessContext;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStreamableServerSession;
import io.modelcontextprotocol.spec.McpStreamableServerTransport;
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

class AreaScopedMcpStreamableTransportProviderTest {

    private static Playbook playbook(String name, String area) {
        return new Playbook(
                name,
                name,
                "Desc for " + name,
                "Template",
                List.of("search_corpus"),
                false,
                "specialist",
                false,
                null,
                null,
                false,
                area,
                1);
    }

    private static McpSchema.Prompt prompt(String name) {
        return McpSchema.Prompt.builder(name).description("Desc for " + name).build();
    }

    private static Authentication authWithRoles(String... roles) {
        return new UsernamePasswordAuthenticationToken(
                "user",
                "n/a",
                AreaAccessContext.mapRoleClaimsToAuthorities(List.of(roles), "Admin", "User"));
    }

    @Test
    void filtersPromptsListToAuthorizedLivePlaybooksOnly() {
        PlaybookRepository repo = mock(PlaybookRepository.class);
        when(repo.findAll())
                .thenReturn(
                        List.of(
                                playbook("oim-full", "OIM"),
                                playbook("ping-full", "PingID"),
                                playbook("ping-orch", "PingID")));

        McpSchema.ListPromptsResult all =
                new McpSchema.ListPromptsResult(
                        List.of(
                                prompt("oim-full"),
                                prompt("ping-full"),
                                prompt("ping-orch"),
                                prompt("deleted-stale-prompt")),
                        null,
                        Map.of());

        // Single-area OIM.User sees only OIM live prompt
        McpSchema.ListPromptsResult oimOnly =
                AreaScopedMcpStreamableTransportProvider.filterListPromptsResult(
                        all, authWithRoles("OIM.User"), repo);
        assertThat(oimOnly.prompts())
                .extracting(McpSchema.Prompt::name)
                .containsExactly("oim-full");

        // Zero-area caller sees nothing
        McpSchema.ListPromptsResult zeroArea =
                AreaScopedMcpStreamableTransportProvider.filterListPromptsResult(
                        all, authWithRoles(), repo);
        assertThat(zeroArea.prompts()).isEmpty();

        // Unauthenticated caller (null auth) sees nothing (fail-closed)
        McpSchema.ListPromptsResult unauth =
                AreaScopedMcpStreamableTransportProvider.filterListPromptsResult(all, null, repo);
        assertThat(unauth.prompts()).isEmpty();

        // Global User sees all live playbooks, excluding deleted-stale-prompt
        McpSchema.ListPromptsResult globalUser =
                AreaScopedMcpStreamableTransportProvider.filterListPromptsResult(
                        all, authWithRoles("User"), repo);
        assertThat(globalUser.prompts())
                .extracting(McpSchema.Prompt::name)
                .containsExactly("oim-full", "ping-full", "ping-orch");
    }

    /**
     * {@code AreaScopedSession} subclasses the MCP SDK's concrete {@link
     * McpStreamableServerSession} with a minimal stand-in {@code super(...)} and delegates every
     * call to the real session. If a future MCP SDK upgrade adds a new public/protected method to
     * the session or a new {@code default} method to the transport/provider interfaces, an
     * unoverridden method would silently execute against the empty stand-in instead of the live
     * delegate. Pinning exact override completeness here turns any such SDK drift into a loud test
     * failure on upgrade.
     */
    @Test
    void sdkWrappersOverrideEveryOverridableMethodOfTheirSdkTypes() {
        assertAllOverridableMethodsDeclared(
                McpStreamableServerSession.class,
                AreaScopedMcpStreamableTransportProvider.AreaScopedSession.class);
        assertAllOverridableMethodsDeclared(
                McpStreamableServerTransport.class,
                AreaScopedMcpStreamableTransportProvider.PromptsFilteringTransport.class);
        assertAllOverridableMethodsDeclared(
                McpStreamableServerTransportProvider.class,
                AreaScopedMcpStreamableTransportProvider.class);
    }

    private static void assertAllOverridableMethodsDeclared(
            Class<?> sdkType, Class<?> wrapperType) {
        Map<String, Method> required = new LinkedHashMap<>();
        // Public methods across the full class + interface hierarchy (excluding Object)
        for (Method m : sdkType.getMethods()) {
            if (isOverridableNonObjectMethod(m)) {
                required.putIfAbsent(signatureKey(m), m);
            }
        }
        // Protected methods declared on the concrete SDK class hierarchy
        for (Class<?> c = sdkType; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (Modifier.isProtected(m.getModifiers()) && isOverridableNonObjectMethod(m)) {
                    required.putIfAbsent(signatureKey(m), m);
                }
            }
        }

        assertThat(required)
                .as("sanity check: %s must expose overridable methods", sdkType.getSimpleName())
                .isNotEmpty();

        List<String> missing = new ArrayList<>();
        for (Method m : required.values()) {
            try {
                wrapperType.getDeclaredMethod(m.getName(), m.getParameterTypes());
            } catch (NoSuchMethodException e) {
                missing.add(signatureKey(m));
            }
        }
        assertThat(missing)
                .as(
                        "%s must explicitly override every overridable method of %s so SDK upgrades"
                                + " cannot silently fall through to a stand-in super()",
                        wrapperType.getSimpleName(), sdkType.getSimpleName())
                .isEmpty();
    }

    private static boolean isOverridableNonObjectMethod(Method m) {
        if (m.getDeclaringClass() == Object.class) {
            return false;
        }
        int mods = m.getModifiers();
        return !Modifier.isStatic(mods)
                && !Modifier.isFinal(mods)
                && !m.isSynthetic()
                && !m.isBridge();
    }

    private static String signatureKey(Method m) {
        String params =
                Arrays.stream(m.getParameterTypes())
                        .map(Class::getName)
                        .collect(Collectors.joining(","));
        return m.getName() + "(" + params + ")";
    }
}
