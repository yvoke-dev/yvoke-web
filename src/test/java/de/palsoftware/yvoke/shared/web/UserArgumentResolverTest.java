package de.palsoftware.yvoke.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.palsoftware.yvoke.shared.user.model.User;
import de.palsoftware.yvoke.shared.user.service.UserService;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Unit tests for {@link UserArgumentResolver} — the plumbing that injects the authenticated
 * {@link User} into every controller method. A regression here fails every {@code @User}-taking
 * endpoint, so pin both the type match and the 401-when-unauthenticated branch.
 */
public class UserArgumentResolverTest {

    private UserService userService;
    private UserArgumentResolver resolver;

    private final User user =
        new User(UUID.randomUUID(), "entra-oid", "user@test.local", "Test User", Instant.now());

    @BeforeEach
    public void setUp() {
        userService = mock(UserService.class);
        resolver = new UserArgumentResolver(userService);
    }

    @Test
    public void supportsParameterTrueForUserType() {
        MethodParameter param = mock(MethodParameter.class);
        doReturn(User.class).when(param).getParameterType();

        assertThat(resolver.supportsParameter(param)).isTrue();
    }

    @Test
    public void supportsParameterFalseForOtherType() {
        MethodParameter param = mock(MethodParameter.class);
        doReturn(String.class).when(param).getParameterType();

        assertThat(resolver.supportsParameter(param)).isFalse();
    }

    @Test
    public void resolveArgumentReturnsCurrentUser() {
        when(userService.getCurrentUser()).thenReturn(Optional.of(user));

        Object resolved = resolver.resolveArgument(null, null, null, null);

        assertThat(resolved).isSameAs(user);
    }

    @Test
    public void resolveArgumentThrows401WhenNoUser() {
        when(userService.getCurrentUser()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> resolver.resolveArgument(null, null, null, null))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(t -> assertThat(((ResponseStatusException) t).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED));
    }
}
