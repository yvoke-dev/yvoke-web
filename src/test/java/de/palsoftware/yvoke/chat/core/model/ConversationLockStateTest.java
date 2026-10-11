package de.palsoftware.yvoke.chat.core.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class ConversationLockStateTest {

    @Test
    void from_whenNoInitialPlaybookAndNoProfile_returnsUnlockedState() {
        ConversationLockState state = ConversationLockState.from(Optional.empty(), false, null);

        assertThat(state.initialPlaybook()).isEmpty();
        assertThat(state.hasInDepthTurns()).isFalse();
        assertThat(state.isSinglePlaybookLocked()).isFalse();
        assertThat(state.isInDepthMode()).isFalse();
        assertThat(state.isPlaybookLocked()).isFalse();
        assertThat(state.lockedPlaybookName()).isNull();
    }

    @Test
    void from_whenInitialPlaybookAndNoInDepthTurns_locksSinglePlaybookEvenWithStoredProfile() {
        ConversationLockState state =
            ConversationLockState.from(Optional.of("pb1"), false, "profile1");

        assertThat(state.initialPlaybook()).contains("pb1");
        assertThat(state.hasInDepthTurns()).isFalse();
        assertThat(state.isSinglePlaybookLocked()).isTrue();
        assertThat(state.isInDepthMode()).isFalse();
        assertThat(state.isPlaybookLocked()).isTrue();
        assertThat(state.lockedPlaybookName()).isEqualTo("pb1");
    }

    @Test
    void from_whenMixedConversationWithActiveProfile_routesToInDepthModeAndSuppressesPlaybookLock() {
        ConversationLockState state =
            ConversationLockState.from(Optional.of("pb1"), true, "profile1");

        assertThat(state.initialPlaybook()).contains("pb1");
        assertThat(state.hasInDepthTurns()).isTrue();
        assertThat(state.isSinglePlaybookLocked()).isFalse();
        assertThat(state.isInDepthMode()).isTrue();
        assertThat(state.isPlaybookLocked()).isFalse();
        assertThat(state.lockedPlaybookName()).isNull();
    }

    @Test
    void from_whenMixedConversationWithBlankProfile_treatsAsPlaybookLocked() {
        ConversationLockState state = ConversationLockState.from(Optional.of("pb1"), true, "   ");

        assertThat(state.initialPlaybook()).contains("pb1");
        assertThat(state.hasInDepthTurns()).isTrue();
        assertThat(state.isSinglePlaybookLocked()).isFalse();
        assertThat(state.isInDepthMode()).isFalse();
        assertThat(state.isPlaybookLocked()).isTrue();
        assertThat(state.lockedPlaybookName()).isEqualTo("pb1");
    }

    @Test
    void from_whenInDepthWithoutPlaybook_returnsInDepthMode() {
        ConversationLockState state =
            ConversationLockState.from(Optional.empty(), true, "profile1");

        assertThat(state.initialPlaybook()).isEmpty();
        assertThat(state.hasInDepthTurns()).isTrue();
        assertThat(state.isSinglePlaybookLocked()).isFalse();
        assertThat(state.isInDepthMode()).isTrue();
        assertThat(state.isPlaybookLocked()).isFalse();
        assertThat(state.lockedPlaybookName()).isNull();
    }
}
