package de.palsoftware.yvoke.chat.api.model;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * SEC-18: the desktop-sync request DTOs carry Bean Validation bounds so an oversized payload is
 * rejected (400) at the boundary instead of being materialized into memory.
 */
class DesktopSyncDtoValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    @Test
    void rejectsBatchLargerThanTheAllowedMaximum() {
        List<NewMessageDto> tooMany =
            Collections.nCopies(501, new NewMessageDto("user", "hi", null, null, null, null, null));
        AppendMessagesRequest request = new AppendMessagesRequest(tooMany);

        assertThat(validator.validate(request)).isNotEmpty();
    }

    @Test
    void acceptsBatchAtTheAllowedMaximum() {
        List<NewMessageDto> ok =
            Collections.nCopies(500, new NewMessageDto("user", "hi", null, null, null, null, null));
        assertThat(validator.validate(new AppendMessagesRequest(ok))).isEmpty();
    }

    @Test
    void rejectsMessageWithOverlongContent() {
        String huge = "x".repeat(1_000_001);
        AppendMessagesRequest request = new AppendMessagesRequest(
            List.of(new NewMessageDto("user", huge, null, null, null, null, null)));

        // Cascade validation (@Valid on the list element) flags the oversized content.
        assertThat(validator.validate(request)).isNotEmpty();
    }

    @Test
    void rejectsMessageWithBlankRole() {
        AppendMessagesRequest request = new AppendMessagesRequest(
            List.of(new NewMessageDto("  ", "hi", null, null, null, null, null)));
        assertThat(validator.validate(request)).isNotEmpty();
    }

    @Test
    void rejectsConversationTitleOverLimit() {
        CreateConversationRequest request = new CreateConversationRequest("t".repeat(501), null);
        assertThat(validator.validate(request)).isNotEmpty();
    }

    @Test
    void rejectsOrchestratorRunWithMissingConversationId() {
        OrchestratorRunRequest req = new OrchestratorRunRequest(null, null, "profile", null, null,
            null, null, null, null, null, null, null, null, null);
        assertThat(validator.validate(req)).isNotEmpty();
    }

    @Test
    void rejectsOrchestratorRunWithMissingProfileName() {
        OrchestratorRunRequest req = new OrchestratorRunRequest(UUID.randomUUID(), null, null, null,
            null, null, null, null, null, null, null, null, null, null);
        assertThat(validator.validate(req)).isNotEmpty();
    }

    @Test
    void rejectsOrchestratorRunWithOverlongProfileName() {
        OrchestratorRunRequest req = new OrchestratorRunRequest(UUID.randomUUID(), null,
            "p".repeat(201), null, null, null, null, null, null, null, null, null, null, null);
        assertThat(validator.validate(req)).isNotEmpty();
    }

    @Test
    void rejectsOrchestratorRunWithStepsExceedingLimit() {
        List<OrchestratorRunRequest.Step> steps =
            Collections.nCopies(201, new OrchestratorRunRequest.Step(0, "spec", 0, "pb", "m",
                "high", null, null, null, null, null, null, null, null, null));
        OrchestratorRunRequest req = new OrchestratorRunRequest(UUID.randomUUID(), null, "profile",
            null, null, null, null, null, null, null, null, null, null, steps);
        assertThat(validator.validate(req)).isNotEmpty();
    }

    @Test
    void acceptsOrchestratorRunWithStepsAtLimit() {
        List<OrchestratorRunRequest.Step> steps =
            Collections.nCopies(200, new OrchestratorRunRequest.Step(0, "spec", 0, "pb", "m",
                "high", null, null, null, null, null, null, null, null, null));
        OrchestratorRunRequest req = new OrchestratorRunRequest(UUID.randomUUID(), null, "profile",
            null, null, null, null, null, null, null, null, null, null, steps);
        assertThat(validator.validate(req)).isEmpty();
    }

    @Test
    void rejectsOrchestratorRunStepWithOverlongRole() {
        List<OrchestratorRunRequest.Step> steps =
            List.of(new OrchestratorRunRequest.Step(0, "r".repeat(201), 0, "pb", "m", "high", null,
                null, null, null, null, null, null, null, null));
        OrchestratorRunRequest req = new OrchestratorRunRequest(UUID.randomUUID(), null, "profile",
            null, null, null, null, null, null, null, null, null, null, steps);
        assertThat(validator.validate(req)).isNotEmpty();
    }
}
