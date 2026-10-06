package de.palsoftware.yvoke.area.core;

import java.util.List;

/**
 * The names of an area's members, each list sorted by name: its chat system prompts, its
 * collections, the playbooks a user can pick (orchestrator and reviewer playbooks left out, as in
 * {@code list_playbooks}) and its multi-agent profiles.
 */
public record AreaMembers(List<String>systemPrompts,List<String>collections,List<String>playbooks,List<String>profiles){

public static final AreaMembers NONE=new AreaMembers(List.of(),List.of(),List.of(),List.of());}
