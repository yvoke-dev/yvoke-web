package de.palsoftware.yvoke.chat.core.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class ConversationLockStateTest {

    @Test
    void from_whenNoInitialPlaybookAndNoProfile_returnsUnlockedState() {
        ConversationLockState state = ConversationLockState.from(Optional.empty(), false, false);

        assertThat(state.initialPlaybook()).isEmpty();
        assertThat(state.hasInDepthTurns()).isFalse();
        assertThat(state.hasActiveProfile()).isFalse();
        assertThat(state.isSinglePlaybookLocked()).isFalse();
        assertThat(state.isInDepthMode()).isFalse();
        assertThat(state.isPlaybookLocked()).isFalse();
        assertThat(state.lockedPlaybookName()).isNull();
    }

    @Test
    void from_whenInitialPlaybookAndNoInDepthTurns_locksSinglePlaybookEvenWithActiveProfile() {
        ConversationLockState state = ConversationLockState.from(Optional.of("pb1"), false, true);

        assertThat(state.initialPlaybook()).contains("pb1");
        assertThat(state.hasInDepthTurns()).isFalse();
        assertThat(state.hasActiveProfile()).isTrue();
        assertThat(state.isSinglePlaybookLocked()).isTrue();
        assertThat(state.isInDepthMode()).isFalse();
        assertThat(state.isPlaybookLocked()).isTrue();
        assertThat(state.lockedPlaybookName()).isEqualTo("pb1");
    }

    @Test
    void from_whenMixedConversationWithActiveProfile_routesToInDepthModeAndSuppressesPlaybookLock() {
        ConversationLockState state = ConversationLockState.from(Optional.of("pb1"), true, true);

        assertThat(state.initialPlaybook()).contains("pb1");
        assertThat(state.hasInDepthTurns()).isTrue();
        assertThat(state.hasActiveProfile()).isTrue();
        assertThat(state.isSinglePlaybookLocked()).isFalse();
        assertThat(state.isInDepthMode()).isTrue();
        assertThat(state.isPlaybookLocked()).isFalse();
        assertThat(state.lockedPlaybookName()).isNull();
    }

    @Test
    void from_whenMixedConversationWithoutActiveProfile_treatsAsPlaybookLocked() {
        ConversationLockState state = ConversationLockState.from(Optional.of("pb1"), true, false);

        assertThat(state.initialPlaybook()).contains("pb1");
        assertThat(state.hasInDepthTurns()).isTrue();
        assertThat(state.hasActiveProfile()).isFalse();
        assertThat(state.isSinglePlaybookLocked()).isFalse();
        assertThat(state.isInDepthMode()).isFalse();
        assertThat(state.isPlaybookLocked()).isTrue();
        assertThat(state.lockedPlaybookName()).isEqualTo("pb1");
    }

    @Test
    void from_whenInDepthWithoutPlaybookAndActiveProfile_returnsInDepthMode() {
        ConversationLockState state = ConversationLockState.from(Optional.empty(), true, true);

        assertThat(state.initialPlaybook()).isEmpty();
        assertThat(state.hasInDepthTurns()).isTrue();
        assertThat(state.hasActiveProfile()).isTrue();
        assertThat(state.isSinglePlaybookLocked()).isFalse();
        assertThat(state.isInDepthMode()).isTrue();
        assertThat(state.isPlaybookLocked()).isFalse();
        assertThat(state.lockedPlaybookName()).isNull();
    }

    @Test
    void from_whenInDepthWithoutPlaybookAndNoActiveProfile_returnsUnlocked() {
        ConversationLockState state = ConversationLockState.from(Optional.empty(), true, false);

        assertThat(state.initialPlaybook()).isEmpty();
        assertThat(state.hasInDepthTurns()).isTrue();
        assertThat(state.hasActiveProfile()).isFalse();
        assertThat(state.isSinglePlaybookLocked()).isFalse();
        assertThat(state.isInDepthMode()).isFalse();
        assertThat(state.isPlaybookLocked()).isFalse();
        assertThat(state.lockedPlaybookName()).isNull();
    }
}
