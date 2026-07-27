package de.palsoftware.yvoke.shared.jobengine.model;

/**
 * How much work of one job kind is still waiting, for the admin jobs page.
 *
 * <p>
 * The kind is the FULL kind, including any {@code :<instance>} suffix, so a Confluence crawl that
 * fanned out hundreds of page jobs can be cancelled per connector instance rather than for every
 * instance at once.
 */
public record QueuedKindSummary(String kind,long queuedCount){}
