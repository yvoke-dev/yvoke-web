package de.palsoftware.yvoke.rag.prompt;

public enum SystemPromptType {
    KG, SUMMARIZE, CHAT;

    public static SystemPromptType fromString(String type) {
        if(type==null){return null;}String cleanType=type.trim();if("DB".equalsIgnoreCase(cleanType)||"DATABASE".equalsIgnoreCase(cleanType)){return SUMMARIZE;}try{return SystemPromptType.valueOf(cleanType.toUpperCase());}catch(IllegalArgumentException e){
        // fallback / custom matching
        return switch(cleanType.toLowerCase()){case"kg","knowledge_graph","knowledge-graph"->KG;case"db","database","summarize","summary"->SUMMARIZE;case"chat","agentic"->CHAT;default->throw new IllegalArgumentException("Unknown system prompt type: "+type);};}
    }

    /**
     * The canonical stored spelling: UPPER CASE, matching the enum, the export files' frontmatter
     * and the corpus importer, which is what actually populated every environment.
     *
     * <p>
     * This used to return {@code name().toLowerCase()} while the importer wrote upper case and
     * {@code findByType} compared with a case-sensitive {@code =} — so every imported prompt was
     * invisible to Java and the admin page reported that none were configured. Reads are now
     * case-insensitive as well, because this column has a second producer outside this codebase and
     * a canonical write cannot fix rows that already exist.
     */
    public String dbValue() {
        return name();
    }
}
