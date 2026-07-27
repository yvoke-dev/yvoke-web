package de.palsoftware.yvoke.ingest.core.model;

/**
 * Job kinds owned by the ingest domain. These values are persisted as plain strings in the {@code
 * ingestion_jobs.kind} column; the job engine ({@code shared.jobengine}) treats kinds opaquely and
 * routes by the registered {@link de.palsoftware.yvoke.shared.jobengine.JobHandler#kind()} string,
 * so adding a kind here requires no schema change and no change to the engine.
 */
public enum IngestJobKind {
    STANDARD("standard"), HIERARCHICAL("hierarchical"), KG_EXTRACT("kg-extract"), CONFLUENCE_IMPORT(
        "confluence-import"), CONFLUENCE_PAGE_IMPORT(
            "confluence-page-import"), CUSTOM("custom"), JSON_IMPORT("json-import");

    private final String value;

    IngestJobKind(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
