package de.palsoftware.yvoke.area.core;

import java.time.Instant;

/**
 * A knowledge base such as OIM: a named group of system prompts, collections, playbooks and
 * orchestrator profiles. Every one of those belongs to exactly one area. The three defaults are
 * what a new client session starts with; each is {@code null} when the area does not set one.
 *
 * <p>
 * {@code prototype} has the same meaning as on playbooks and profiles: a discovery flag that hides
 * the area from pickers unless the user has prototype visibility on.
 */
public record Area(String name,String title,String description,boolean prototype,String defaultSystemPrompt,String defaultPlaybook,String defaultProfile,Instant createdAt,Instant updatedAt){}
