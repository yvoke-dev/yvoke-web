package de.palsoftware.yvoke.shared.jobengine.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.List;

public record JobStep(@JsonValue String dbValue,String label,String description){

public static final JobStep CRAWL=new JobStep("crawl","Crawl Space/Repository","Scan the source space or repository for pages/documents.");public static final JobStep DISPATCH=new JobStep("dispatch","Queue Import Jobs","Enqueue discovered pages or documents to the ingestion pipeline.");public static final JobStep CHUNK=new JobStep("chunk","Parse & Chunk Document","Read Markdown headers and construct heading paths.");public static final JobStep EMBED=new JobStep("embed","Generate Embeddings","Call embedding models in batch.");public static final JobStep INSERT=new JobStep("insert","Atomically Persist to DB","Upsert document metadata and write chunk vectors.");public static final JobStep EXTRACT=new JobStep("extract","LLM Extraction & Summarization","Generate summaries or extract knowledge graph entities via LLM.");public static final JobStep INJECT=new JobStep("inject","Consolidation & Maintenance","Perform consolidation.");

private static final List<JobStep>ALL_STEPS=List.of(CRAWL,DISPATCH,CHUNK,EMBED,INSERT,EXTRACT,INJECT);

@JsonCreator public static JobStep fromDbValue(String value){if(value==null){return null;}for(JobStep step:ALL_STEPS){if(step.dbValue().equals(value)){return step;}}return new JobStep(value,value,"");}}
