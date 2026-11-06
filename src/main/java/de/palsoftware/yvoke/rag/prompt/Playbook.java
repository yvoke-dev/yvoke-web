package de.palsoftware.yvoke.rag.prompt;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

public record Playbook(String name,String title,String description,String templateText,List<String>tools,boolean codeExecution,String targetAgent,boolean prototype,Instant createdAt,Instant updatedAt,boolean readOnly,String area,int version){

@Nullable public String systemPrompt(){return null;}

public Playbook{if(version<1){throw new IllegalArgumentException("Playbook version must be >= 1.");}}

public boolean isSpecialist(){return isSpecialist(targetAgent);}

public static boolean isSpecialist(String targetAgent){if(targetAgent==null||targetAgent.isBlank()){return true;}return!"orchestrator".equalsIgnoreCase(targetAgent)&&!"reviewer".equalsIgnoreCase(targetAgent);}

public Playbook(String name,String title,String description,String templateText,List<String>tools,boolean codeExecution,String targetAgent,boolean prototype,Instant createdAt,Instant updatedAt,boolean readOnly,String area){this(name,title,description,templateText,tools,codeExecution,targetAgent,prototype,createdAt,updatedAt,readOnly,area,1);}

public Playbook(String name,String title,String description,String templateText,List<String>tools,boolean codeExecution,String targetAgent,boolean prototype,Instant createdAt,Instant updatedAt,boolean readOnly){this(name,title,description,templateText,tools,codeExecution,targetAgent,prototype,createdAt,updatedAt,readOnly,null,1);}

public Playbook(String name,String title,String description,String templateText,List<String>tools,boolean codeExecution,Instant createdAt,Instant updatedAt,boolean readOnly){this(name,title,description,templateText,tools,codeExecution,"specialist",false,createdAt,updatedAt,readOnly,null,1);}

public Playbook(String name,String title,String description,String templateText,List<String>tools,boolean codeExecution,Instant createdAt,Instant updatedAt){this(name,title,description,templateText,tools,codeExecution,"specialist",false,createdAt,updatedAt,false,null,1);}

public Playbook(String name,String title,String description,String templateText,List<String>tools,boolean codeExecution,String targetAgent,Instant createdAt,Instant updatedAt){this(name,title,description,templateText,tools,codeExecution,targetAgent,false,createdAt,updatedAt,false,null,1);}

public Playbook(String name,String title,String description,String templateText,List<String>tools,boolean codeExecution,String targetAgent,boolean prototype,Instant createdAt,Instant updatedAt){this(name,title,description,templateText,tools,codeExecution,targetAgent,prototype,createdAt,updatedAt,false,null,1);}}
