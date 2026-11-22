package de.palsoftware.yvoke.llm.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import org.junit.jupiter.api.Test;

/**
 * Pins that all {@code com.openai:openai-java-*} modules on the runtime classpath ship at the same
 * version.
 *
 * <p>{@code spring-ai-starter-model-openai} (retained for victools JSON Schema generation) brings
 * in {@code openai-java-core} transitively at the same dependency depth as {@code
 * openai-java:client-okhttp}. Without explicit dependency alignment, Maven's declaration-order
 * tie-breaker ships an older {@code openai-java-core} alongside a newer {@code
 * openai-java-client-okhttp}, risking {@link NoSuchMethodError} at runtime and silently swallowing
 * {@code openai-java} upgrades.
 */
class OpenAiSdkDependencyConvergenceTest {

    @Test
    void openAiJavaCoreAndOkHttpClientShipAtTheSameVersion() {
        String coreVersion = OpenAIClient.class.getPackage().getImplementationVersion();
        String okHttpVersion = OpenAIOkHttpClient.class.getPackage().getImplementationVersion();

        assertThat(coreVersion)
                .as("openai-java-core Implementation-Version must be present in MANIFEST.MF")
                .isNotBlank();
        assertThat(okHttpVersion)
                .as(
                        "openai-java-client-okhttp Implementation-Version must be present in MANIFEST.MF")
                .isNotBlank();
        assertThat(coreVersion)
                .as(
                        "openai-java-core and openai-java-client-okhttp must converge to the same "
                                + "version on the classpath")
                .isEqualTo(okHttpVersion);
    }
}
