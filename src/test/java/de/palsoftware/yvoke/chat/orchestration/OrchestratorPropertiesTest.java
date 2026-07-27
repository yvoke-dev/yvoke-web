package de.palsoftware.yvoke.chat.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.palsoftware.yvoke.chat.orchestration.OrchestratorProperties.Profile;
import de.palsoftware.yvoke.chat.orchestration.OrchestratorProperties.RoleConfig;
import de.palsoftware.yvoke.chat.orchestration.OrchestratorProperties.RoleDefaults;
import java.util.List;
import org.junit.jupiter.api.Test;

public class OrchestratorPropertiesTest {

    private OrchestratorProperties props(Profile... profiles) {
        return new OrchestratorProperties(2, 8, new RoleDefaults(new RoleConfig("pro", "high"),
            new RoleConfig("pro", "high"), new RoleConfig("flash", "medium")), List.of(profiles));
    }

    @Test
    public void resolve_inheritsDefaultsWhenNoOverride() {
        ResolvedProfile r =
            props(new Profile("OIM", "o", "r", List.of("s1"), null, null, null)).resolve("OIM");

        assertThat(r.orchestrator().model()).isEqualTo("pro");
        assertThat(r.specialist().model()).isEqualTo("flash");
        assertThat(r.specialist().thinkingLevel()).isEqualTo("medium");
        assertThat(r.maxReviewRounds()).isEqualTo(2);
        assertThat(r.specialistPlaybooks()).containsExactly("s1");
    }

    @Test
    public void resolve_mergesPerRoleOverrideOverDefaults() {
        Profile p = new Profile("PingID", "o", "r", List.of("s1"), null, null,
            new RoleConfig("other-flash", null)); // override specialist model, keep default
                                                  // thinking
        ResolvedProfile r = props(p).resolve("PingID");

        assertThat(r.specialist().model()).isEqualTo("other-flash");
        assertThat(r.specialist().thinkingLevel()).isEqualTo("medium"); // inherited
        assertThat(r.orchestrator().model()).isEqualTo("pro"); // untouched
    }

    @Test
    public void resolve_unknownProfileThrows() {
        assertThatThrownBy(() -> props().resolve("nope"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown orchestrator profile");
    }

    @Test
    public void profileNames_listsConfigured() {
        assertThat(props(new Profile("OIM", "o", "r", List.of(), null, null, null),
            new Profile("PingID", "o", "r", List.of(), null, null, null)).profileNames())
            .containsExactly("OIM", "PingID");
    }
}
