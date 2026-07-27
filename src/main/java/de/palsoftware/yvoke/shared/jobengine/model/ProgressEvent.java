package de.palsoftware.yvoke.shared.jobengine.model;

import jakarta.annotation.Nullable;
import java.util.UUID;

public record ProgressEvent(UUID jobId,String status,@Nullable String step,int progress,@Nullable String error,@Nullable JobCounts counts,@Nullable String message){

public ProgressEvent(UUID jobId,String status,@Nullable String step,int progress,@Nullable String error,@Nullable JobCounts counts){this(jobId,status,step,progress,error,counts,null);}

public boolean isTerminal(){return JobStatus.fromDbValue(status).isTerminal();}

public static ProgressEvent of(IngestionJob job){return new ProgressEvent(job.id(),job.status().dbValue(),job.step()==null?null:job.step().dbValue(),job.progress(),job.error(),job.counts(),null);}}
