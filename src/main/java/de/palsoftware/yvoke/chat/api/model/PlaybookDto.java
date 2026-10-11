package de.palsoftware.yvoke.chat.api.model;

import de.palsoftware.yvoke.rag.prompt.Playbook;
import java.util.List;

public record PlaybookDto(String name,String title,String description,List<String>tools,boolean codeExecution,String targetAgent,boolean prototype,String area,String systemPrompt){

public PlaybookDto(String name,String title,String description,List<String>tools,boolean codeExecution,String targetAgent,boolean prototype,String area){this(name,title,description,tools,codeExecution,targetAgent,prototype,area,null);}

public static PlaybookDto from(Playbook playbook){String agent=playbook.targetAgent()!=null?playbook.targetAgent():"specialist";boolean isSpecialist="specialist".equalsIgnoreCase(agent)||(!"orchestrator".equalsIgnoreCase(agent)&&!"reviewer".equalsIgnoreCase(agent));String prompt=isSpecialist?playbook.systemPrompt():null;return new PlaybookDto(playbook.name(),playbook.title(),playbook.description(),playbook.tools()!=null?playbook.tools():List.of(),playbook.codeExecution(),agent,playbook.prototype(),playbook.area(),prompt);}}
