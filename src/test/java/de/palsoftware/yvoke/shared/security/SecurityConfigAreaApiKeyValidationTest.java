package de.palsoftware.yvoke.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SecurityConfigAreaApiKeyValidationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void nullOrBlankJsonConfiguresNoAreaKeys() {
        assertThat(SecurityConfig.parseAreaApiKeys(mapper, null)).isEmpty();
        assertThat(SecurityConfig.parseAreaApiKeys(mapper, "")).isEmpty();
        assertThat(SecurityConfig.parseAreaApiKeys(mapper, "   ")).isEmpty();
        assertThat(SecurityConfig.parseAreaApiKeys(mapper, "{}")).isEmpty();
    }

    @Test
    void validAreaApiKeysMappingParsesSuccessfully() {
        Map<String, String> keys =
            SecurityConfig.parseAreaApiKeys(mapper, "{\"OIM\":\"key-1\",\"PingID\":\"key-2\"}");
        assertThat(keys).hasSize(2).containsEntry("OIM", "key-1").containsEntry("PingID", "key-2");
    }

    @Test
    void surroundingWhitespaceInAreaAndKeyIsTolerated() {
        Map<String, String> keys =
            SecurityConfig.parseAreaApiKeys(mapper, "{\"  OIM  \":\"  key-1  \"}");
        assertThat(keys).containsEntry("OIM", "key-1");
    }

    @Test
    void malformedJsonFailsStartupValidation() {
        assertThatThrownBy(() -> SecurityConfig.parseAreaApiKeys(mapper, "{not-json"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("not valid JSON");
    }

    @Test
    void nonObjectJsonFailsStartupValidation() {
        assertThatThrownBy(() -> SecurityConfig.parseAreaApiKeys(mapper, "[\"OIM\"]"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("must be a JSON object");

        assertThatThrownBy(() -> SecurityConfig.parseAreaApiKeys(mapper, "\"just-a-string\""))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("must be a JSON object");
    }

    @Test
    void blankAreaNameFailsStartupValidation() {
        assertThatThrownBy(() -> SecurityConfig.parseAreaApiKeys(mapper, "{\"\":\"key-1\"}"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("blank area name");

        assertThatThrownBy(() -> SecurityConfig.parseAreaApiKeys(mapper, "{\"   \":\"key-1\"}"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("blank area name");
    }

    @Test
    void wildcardAreaNameFailsStartupValidation() {
        assertThatThrownBy(() -> SecurityConfig.parseAreaApiKeys(mapper, "{\"*\":\"key-1\"}"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("wildcard '*'");
    }

    @Test
    void nonStringApiKeyFailsStartupValidation() {
        assertThatThrownBy(() -> SecurityConfig.parseAreaApiKeys(mapper, "{\"OIM\":12345}"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("rather than a string API key");

        assertThatThrownBy(() -> SecurityConfig.parseAreaApiKeys(mapper, "{\"OIM\":true}"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("rather than a string API key");

        assertThatThrownBy(() -> SecurityConfig.parseAreaApiKeys(mapper, "{\"OIM\":null}"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("rather than a string API key");
    }

    @Test
    void blankApiKeyFailsStartupValidation() {
        assertThatThrownBy(() -> SecurityConfig.parseAreaApiKeys(mapper, "{\"OIM\":\"\"}"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("blank API key");

        assertThatThrownBy(() -> SecurityConfig.parseAreaApiKeys(mapper, "{\"OIM\":\"   \"}"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("blank API key");
    }

    @Test
    void placeholderDefaultKeyFailsStartupValidation() {
        assertThatThrownBy(
            () -> SecurityConfig.parseAreaApiKeys(mapper, "{\"OIM\":\"dev-key-12345\"}"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("dev-key-12345");
    }

    @Test
    void duplicateAreaCaseCollisionFailsStartupValidation() {
        assertThatThrownBy(
            () -> SecurityConfig.parseAreaApiKeys(mapper, "{\"OIM\":\"k1\",\"oim\":\"k2\"}"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("more than once");
    }

    @Test
    void duplicateKeyAcrossAreasFailsStartupValidation() {
        assertThatThrownBy(() -> SecurityConfig.parseAreaApiKeys(mapper,
            "{\"OIM\":\"shared-key\",\"PingID\":\"shared-key\"}"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("same API key");
    }

    @Test
    void areaKeyCollidingWithGlobalApiKeyFailsStartupValidation() {
        assertThatThrownBy(() -> SecurityConfig.parseAreaApiKeys(mapper,
            "{\"OIM\":\"global-secret-key\"}", "global-secret-key"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("app.security.api-key");
    }
}
