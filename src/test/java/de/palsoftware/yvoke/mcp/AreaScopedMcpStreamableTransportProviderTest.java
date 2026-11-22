package de.palsoftware.yvoke.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.palsoftware.yvoke.rag.prompt.Playbook;
import de.palsoftware.yvoke.rag.prompt.PlaybookRepository;
import de.palsoftware.yvoke.shared.security.AreaAccessContext;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
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
}
