package de.palsoftware.yvoke.chat.core;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import java.util.Map;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

public class ChatPropertiesTest {

    private final Validator validator;

    public ChatPropertiesTest() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            this.validator = factory.getValidator();
        }
    }

    @Test
    public void testValidProperties() {
        ChatProperties props = new ChatProperties(true, List.of("model1"), true);
        Set<ConstraintViolation<ChatProperties>> violations = validator.validate(props);
        assertThat(violations).isEmpty();
    }

    /**
     * Production ships TWO models in ONE environment variable: {@code application.yml} declares
     * {@code allowed-models: "${ALLOWED_MODELS:gemini-3.6-flash}"} — a single quoted YAML scalar,
     * not a list — and {@code k8s/app/yvoke-app/configmap.yaml} sets it to
     * {@code "gemini-3.6-flash,gemini-3.5-flash-lite"}. Nothing splits that string except Boot's
     * relaxed binding, and nothing anywhere asserts that it does.
     *
     * <p>
     * If the split stops happening the list is not empty, so {@code @NotEmpty} passes and the
     * context starts perfectly: the app simply has one "model" whose name is the literal
     * {@code "gemini-3.6-flash,gemini-3.5-flash-lite"}. Every consequence is silent and downstream.
     * {@code ChatConversationService.createConversation} stamps element 0 onto every new
     * conversation, so every chat is created pointing at a model id no provider knows, and the
     * failure surfaces later as a provider error on the first message rather than as a
     * configuration fault. {@code updateModel} enforces the same list as a whitelist (SEC-04), so
     * the model dropdown offers one nonsense entry and the second real model becomes unselectable.
     * {@code PlaybookValidationController} falls back to {@code getAllowedModels().get(0)} for
     * preflight validation, so playbook validation starts failing too. Nothing logs a word about
     * why.
     *
     * <p>
     * The three existing tests construct {@code ChatProperties} through its canonical constructor
     * with a ready-made {@code List}, which is the one thing production never does — they exercise
     * the validation annotations and skip the binding step entirely. This drives the real
     * {@link Binder} over the real property name so the delimiter behaviour production depends on
     * is actually executed.
     */
    @Test
    public void aCommaSeparatedAllowedModelsValueBindsToTheFullList() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(
            Map.of("app.chat.enabled", "true", "app.chat.playbook-validation-enabled", "true",
                "app.chat.allowed-models", "gemini-3.6-flash,gemini-3.5-flash-lite"));

        ChatProperties props = new Binder(source).bind("app.chat", ChatProperties.class).get();

        assertThat(props.allowedModels()).as("ALLOWED_MODELS arrives as ONE comma-separated scalar")
            .containsExactly("gemini-3.6-flash", "gemini-3.5-flash-lite");
        // The first element is what every new conversation is created with, so its identity matters
        // as much as the count.
        assertThat(props.allowedModels().get(0)).isEqualTo("gemini-3.6-flash");
        assertThat(validator.validate(props)).isEmpty();
    }

    @Test
    public void testNullAllowedModels() {
        ChatProperties props = new ChatProperties(true, null, true);
        Set<ConstraintViolation<ChatProperties>> violations = validator.validate(props);
        assertThat(violations).isNotEmpty();
        assertThat(violations)
            .anyMatch(v -> v.getPropertyPath().toString().equals("allowedModels"));
    }

    @Test
    public void testEmptyAllowedModels() {
        ChatProperties props = new ChatProperties(true, Collections.emptyList(), true);
        Set<ConstraintViolation<ChatProperties>> violations = validator.validate(props);
        assertThat(violations).isNotEmpty();
        assertThat(violations)
            .anyMatch(v -> v.getPropertyPath().toString().equals("allowedModels"));
    }
}
