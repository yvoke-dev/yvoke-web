package de.palsoftware.yvoke.shared.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SlugNamesTest {

    @ParameterizedTest
    @ValueSource(strings = {"oim-kg", "OIM Default", "my_playbook.v2", "a"})
    void acceptsSafeResourceNames(String name) {
        assertThat(SlugNames.requireValidName("  " + name + "  ", "Prompt")).isEqualTo(name);
    }

    @ParameterizedTest
    @ValueSource(
        strings = {"", "   ", "x\"><img src=x onerror=alert(1)>", "<script>alert(1)</script>",
            "../escape", "bad/slash", "bad\\backslash", "semi;colon", "-leading-dash"})
    void rejectsUnsafeOrEmptyNames(String bad) {
        assertThatThrownBy(() -> SlugNames.requireValidName(bad, "Prompt"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsOverlongName() {
        String tooLong = "a".repeat(129);
        assertThatThrownBy(() -> SlugNames.requireValidName(tooLong, "Playbook"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
