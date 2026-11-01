package de.palsoftware.yvoke.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

class LoginControllerTest {

    @Test
    void loginInProductionProfileRedirectsToEntraOidc() {
        LoginController controller = new LoginController(false, Optional.empty());
        Model model = new ConcurrentModel();

        String view = controller.login(model);

        assertThat(view).isEqualTo("redirect:/oauth2/authorization/entra");
        assertThat(model.containsAttribute("availableAreas")).isFalse();
    }

    @Test
    void loginInMockProfilePopulatesAvailableAreas() {
        AvailableAreasProvider provider = mock(AvailableAreasProvider.class);
        when(provider.listAreaNames()).thenReturn(List.of("OIM", "PingID"));

        LoginController controller = new LoginController(true, Optional.of(provider));
        Model model = new ConcurrentModel();

        String view = controller.login(model);

        assertThat(view).isEqualTo("login");
        assertThat(model.getAttribute("availableAreas")).isEqualTo(List.of("OIM", "PingID"));
    }

    @Test
    void loginInMockProfileGracefullyHandlesEmptyProvider() {
        LoginController controller = new LoginController(true, Optional.empty());
        Model model = new ConcurrentModel();

        String view = controller.login(model);

        assertThat(view).isEqualTo("login");
        assertThat(model.getAttribute("availableAreas")).isEqualTo(List.of());
    }

    @Test
    void loginInMockProfileHandlesProviderReturningNull() {
        AvailableAreasProvider provider = mock(AvailableAreasProvider.class);
        when(provider.listAreaNames()).thenReturn(null);

        LoginController controller = new LoginController(true, Optional.of(provider));
        Model model = new ConcurrentModel();

        String view = controller.login(model);

        assertThat(view).isEqualTo("login");
        assertThat(model.getAttribute("availableAreas")).isEqualTo(List.of());
    }

    @Test
    void testSeamConstructorWrapsProviderInOptional() {
        AvailableAreasProvider provider = mock(AvailableAreasProvider.class);
        when(provider.listAreaNames()).thenReturn(List.of("Finance"));

        LoginController controller = new LoginController(true, provider);
        Model model = new ConcurrentModel();

        String view = controller.login(model);

        assertThat(view).isEqualTo("login");
        assertThat(model.getAttribute("availableAreas")).isEqualTo(List.of("Finance"));
    }

    @Test
    void indexRedirectsToChat() {
        LoginController controller = new LoginController(true, Optional.empty());
        assertThat(controller.index()).isEqualTo("redirect:/chat");
    }

    @Test
    void loggedOutReturnsLoggedOutView() {
        LoginController controller = new LoginController(true, Optional.empty());
        assertThat(controller.loggedOut()).isEqualTo("logged-out");
    }
}
