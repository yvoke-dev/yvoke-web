package de.palsoftware.yvoke.rag.prompt;

public enum SystemPromptType {
    KG, SUMMARIZE, CHAT;

    public static SystemPromptType fromString(String type) {
        if(type==null){return null;}String cleanType=type.trim();if("DB".equalsIgnoreCase(cleanType)||"DATABASE".equalsIgnoreCase(cleanType)){return SUMMARIZE;}try{return SystemPromptType.valueOf(cleanType.toUpperCase());}catch(IllegalArgumentException e){
        // fallback / custom matching
        return switch(cleanType.toLowerCase()){case"kg","knowledge_graph","knowledge-graph"->KG;case"db","database","summarize","summary"->SUMMARIZE;case"chat","agentic"->CHAT;default->throw new IllegalArgumentException("Unknown system prompt type: "+type);};}
    }

    public String dbValue() {
        return name().toLowerCase();
    }
}
