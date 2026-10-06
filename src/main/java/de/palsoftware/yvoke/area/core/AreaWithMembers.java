package de.palsoftware.yvoke.area.core;

import java.util.List;

/**
 * An area with its members. The default accessors report a default only when it is a member a
 * client can pick: a chat prompt, a pickable playbook or a profile of this same area. Anything else
 * (an item since moved to another area, an orchestrator playbook) would start a session on an item
 * the area does not offer, so it reads as no default.
 */
public record AreaWithMembers(Area area,AreaMembers members){

public String defaultSystemPrompt(){return memberOrNull(area.defaultSystemPrompt(),members.systemPrompts());}

public String defaultPlaybook(){return memberOrNull(area.defaultPlaybook(),members.playbooks());}

public String defaultProfile(){return memberOrNull(area.defaultProfile(),members.profiles());}

// The null check comes first: List.of().contains(null) throws.
private static String memberOrNull(String name,List<String>names){return name!=null&&names.contains(name)?name:null;}}
