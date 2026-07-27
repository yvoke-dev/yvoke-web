package de.palsoftware.yvoke.shared.jobengine.model;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * A blank tag must normalize to "no tag". A literal {@code ""} in the tag list makes the collection
 * validator reject the enqueue ("tag must not be blank") and makes the version-skip lookup
 * ({@code :tag = ANY(tags)}) never match, so every sync re-embeds every page.
 */
class EnqueueRequestTest {

    @Test
    void blankStringTagBecomesNoTag() {
        EnqueueRequest req = new EnqueueRequest("confluence-page-import", "ref", "", "coll");

        assertThat(req.tags()).isEmpty();
        assertThat(req.tag()).isNull();
    }

    @Test
    void blankStringTagWithSettingsBecomesNoTag() {
        EnqueueRequest req =
            new EnqueueRequest("confluence-page-import", "ref", "   ", "coll", Map.of("a", "b"));

        assertThat(req.tags()).isEmpty();
        assertThat(req.tag()).isNull();
    }

    @Test
    void nullTagBecomesNoTag() {
        EnqueueRequest req = new EnqueueRequest("standard", "ref", (String) null, "coll");

        assertThat(req.tags()).isEmpty();
        assertThat(req.tag()).isNull();
    }

    @Test
    void realTagIsKeptAndTrimmed() {
        EnqueueRequest req = new EnqueueRequest("standard", "ref", " 9.3 ", "coll");

        assertThat(req.tags()).containsExactly("9.3");
        assertThat(req.tag()).isEqualTo("9.3");
    }

    @Test
    void blankEntriesAreStrippedFromATagList() {
        EnqueueRequest req = new EnqueueRequest("standard", "ref",
            Arrays.asList("9.3", "", "  ", null), "coll", Map.of());

        assertThat(req.tags()).containsExactly("9.3");
    }

    @Test
    void jsonCreatorWithBlankTagYieldsNoTag() {
        EnqueueRequest req = EnqueueRequest.create("standard", "ref", "", "coll", Map.of());

        assertThat(req.tags()).isEmpty();
    }

    @Test
    void jsonCreatorWithBlankListEntriesYieldsNoTag() {
        EnqueueRequest req =
            EnqueueRequest.create("standard", "ref", List.of("", "  "), "coll", Map.of());

        assertThat(req.tags()).isEmpty();
    }

    /** {@code @Valid} on the REST boundary only bites if the DTO actually carries constraints. */
    @Test
    void blankIdentityFieldsViolateBeanValidation() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();

            Set<ConstraintViolation<EnqueueRequest>> violations =
                validator.validate(new EnqueueRequest("", "  ", "9.3", ""));

            assertThat(violations).extracting(v -> v.getPropertyPath().toString()).contains("kind",
                "sourceRef", "collection");
        }
    }

    @Test
    void wellFormedRequestPassesBeanValidation() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();

            assertThat(validator.validate(new EnqueueRequest("standard", "ref", "9.3", "coll")))
                .isEmpty();
        }
    }
}
