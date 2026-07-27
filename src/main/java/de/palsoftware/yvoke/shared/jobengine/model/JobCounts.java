package de.palsoftware.yvoke.shared.jobengine.model;

/**
 * Terminal per-job counts. {@code skippedEntities}/{@code skippedEdges} record graph output the
 * handler could NOT persist, so a lossy run is visible in the job result instead of only in a log
 * line.
 */
public record JobCounts(int docs,int chunks,int entities,int edges,int jsonObjects,int skippedEntities,int skippedEdges){

/** Counts for a handler that cannot skip graph output (or skipped nothing). */
public JobCounts(int docs,int chunks,int entities,int edges,int jsonObjects){this(docs,chunks,entities,edges,jsonObjects,0,0);}

public static JobCounts zero(){return new JobCounts(0,0,0,0,0);}}
