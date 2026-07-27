package de.palsoftware.yvoke.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import de.palsoftware.yvoke.shared.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

/**
 * SEC-09: mock authentication must fail CLOSED. It may only be enabled while an explicit
 * development profile ({@code dev}/{@code local}/{@code test}) is active; in any other environment
 * (including no active profile, or a production profile) the context must refuse to start rather
 * than silently trusting every credential.
 */
class SecurityConfigMockAuthGuardTest {

    private SecurityConfig build(boolean mockAuth, String... activeProfiles) {
        Environment environment = new MockEnvironment().withProperty("unused", "x");
        ((MockEnvironment) environment).setActiveProfiles(activeProfiles);
        return new SecurityConfig(mock(UserService.class), environment, mockAuth, "dev-key-12345",
            "Admin", "User", "api://oim-kb", "api://oim-kb/mcp.read", "https://example/keys",
            "https://example/issuer", "http://localhost:8080/mcp");
    }

    @Test
    void mockAuthWithoutAnyProfileIsRejected() {
        assertThatThrownBy(() -> build(true)).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("app.security.mock");
    }

    @Test
    void mockAuthUnderProductionProfileIsRejected() {
        assertThatThrownBy(() -> build(true, "prod")).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("app.security.mock");
    }

    @Test
    void mockAuthUnderTestProfileIsAllowed() {
        assertThatCode(() -> build(true, "test")).doesNotThrowAnyException();
    }

    @Test
    void mockAuthUnderLocalProfileIsAllowed() {
        assertThatCode(() -> build(true, "local")).doesNotThrowAnyException();
    }

    @Test
    void realAuthOutsideDevProfilesIsAllowed() {
        SecurityConfig config = build(false, "prod");
        assertThat(config).isNotNull();
    }
}
