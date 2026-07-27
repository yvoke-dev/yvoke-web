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
