package de.palsoftware.yvoke.chat.core.model;

import java.util.Objects;
import java.util.Optional;

/**
 * Resolved immutability / mode locking state for a chat conversation.
 *
 * <p>
 * Encapsulates the rules governing single-playbook vs in-depth (orchestrator) mode, playbook
 * immutability, and UI indicator visibility across thread rendering and send paths.
 */
public record ConversationLockState(Optional<String>initialPlaybook,boolean hasInDepthTurns,boolean isSinglePlaybookLocked,boolean isInDepthMode,boolean isPlaybookLocked,String lockedPlaybookName){public static ConversationLockState from(Optional<String>initialPlaybook,boolean hasInDepthTurns,String orchestratorProfile){Objects.requireNonNull(initialPlaybook,"initialPlaybook must not be null");boolean hasActiveProfile=orchestratorProfile!=null&&!orchestratorProfile.isBlank();boolean isSinglePlaybookLocked=initialPlaybook.isPresent()&&!hasInDepthTurns;boolean isInDepthMode=hasInDepthTurns&&hasActiveProfile;boolean isPlaybookLocked=initialPlaybook.isPresent()&&!isInDepthMode;String lockedPlaybookName=isPlaybookLocked?initialPlaybook.get().trim():null;

return new ConversationLockState(initialPlaybook,hasInDepthTurns,isSinglePlaybookLocked,isInDepthMode,isPlaybookLocked,lockedPlaybookName);}}
