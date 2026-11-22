package de.palsoftware.yvoke.llm.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ProviderEndpointValidatorTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://openrouter.ai/api/v1",
                "HTTPS://generativelanguage.googleapis.com",
                "https://res.services.ai.azure.com/openai/v1",
                "http://127.0.0.1:8080",
                "http://127.0.0.1:8080/openai/v1",
                "http://localhost:8080",
                "HTTP://LOCALHOST:9000/v1",
                "http://[::1]:8080"
            })
    void allowsHttpsAndLoopbackHttpEndpoints(String url) {
        assertThat(ProviderEndpointValidator.requireHttpsOrLoopback(url, "TestProvider"))
                .isEqualTo(url);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "http://openrouter.ai/api/v1",
                "http://res.services.ai.azure.com",
                "http://generativelanguage.googleapis.com",
                "http://localhost.evil.com/v1",
                "http://127.0.0.1.evil.com/v1",
                "http://10.0.0.1:8080/v1",
                "ftp://example.com/v1",
                "file:///etc/passwd",
                "openrouter.ai/api/v1",
                "https:///missing-host",
                "not a valid uri"
            })
    void rejectsNonHttpsNonLoopbackAndMalformedEndpoints(String url) {
        assertThatThrownBy(
                        () -> ProviderEndpointValidator.requireHttpsOrLoopback(url, "TestProvider"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TestProvider")
                .hasMessageContaining("HTTPS");
    }

    @Test
    void rejectsUserInfoSpoofingLocalhost() {
        assertThatThrownBy(() ->
                        ProviderEndpointValidator.requireHttpsOrLoopback(
                                "http://localhost@evil.com/v1", "TestProvider"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTPS");
    }
}
