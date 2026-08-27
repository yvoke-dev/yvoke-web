package de.palsoftware.yvoke.chat.orchestration;

/**
 * What the chat thread's profile dropdown needs to render one option: the name it posts back and
 * whether the profile is a prototype, so the client can hide it while prototype visibility is off.
 *
 * <p>
 * Deliberately not the whole {@link OrchestratorProfile} — the per-role model bindings are operator
 * configuration and have no business being rendered into a user-facing page.
 */
public record OrchestratorProfileOption(String name,boolean prototype){}
