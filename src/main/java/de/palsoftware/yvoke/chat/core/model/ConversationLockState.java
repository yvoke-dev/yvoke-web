package de.palsoftware.yvoke.chat.core.model;

import java.util.Objects;
import java.util.Optional;

/**
 * Resolved immutability and mode locking state for a chat conversation.
 *
 * <p>
 * Encapsulates the rules governing single-playbook vs in-depth (orchestrator) mode, playbook
 * immutability, and UI indicator visibility across thread rendering and send paths.
 */
public record ConversationLockState(Optional<String>initialPlaybook,Optional<Integer>initialPlaybookVersion,boolean hasInDepthTurns,boolean hasActiveProfile){

public ConversationLockState{Objects.requireNonNull(initialPlaybook,"initialPlaybook must not be null");Objects.requireNonNull(initialPlaybookVersion,"initialPlaybookVersion must not be null");initialPlaybook=initialPlaybook.map(String::trim).filter(s->!s.isBlank());if(initialPlaybook.isEmpty()){initialPlaybookVersion=Optional.empty();}else{initialPlaybookVersion=initialPlaybookVersion.filter(v->v>=1);}}

public static ConversationLockState from(Optional<String>initialPlaybook,boolean hasInDepthTurns,boolean hasActiveProfile){return new ConversationLockState(initialPlaybook,Optional.empty(),hasInDepthTurns,hasActiveProfile);}

public static ConversationLockState from(Optional<String>initialPlaybook,Optional<Integer>initialPlaybookVersion,boolean hasInDepthTurns,boolean hasActiveProfile){return new ConversationLockState(initialPlaybook,initialPlaybookVersion,hasInDepthTurns,hasActiveProfile);}

/**
 * True if the thread is permanently locked to single-playbook mode (initial playbook set and no
 * in-depth turns).
 */
public boolean isSinglePlaybookLocked(){return initialPlaybook.isPresent()&&!hasInDepthTurns;}

/**
 * True if the conversation runs in in-depth (orchestrator) mode.
 */
public boolean isInDepthMode(){return hasInDepthTurns&&hasActiveProfile;}

/**
 * True if the conversation's playbook cannot be changed and must be sent on subsequent turns.
 */
public boolean isPlaybookLocked(){return initialPlaybook.isPresent()&&!isInDepthMode();}

/**
 * The locked playbook name, or {@code null} if not locked or suppressed by in-depth mode.
 */
public String lockedPlaybookName(){return isPlaybookLocked()?initialPlaybook.orElse(null):null;}

/**
 * The initial playbook's recorded version when locked, or {@code null} if not locked, suppressed by
 * in-depth mode, or unversioned.
 */
public Integer lockedPlaybookVersion(){return isPlaybookLocked()?initialPlaybookVersion.orElse(null):null;}}
