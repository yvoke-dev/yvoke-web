package de.palsoftware.yvoke.ingest.core.confluence;

import jakarta.annotation.Nullable;

/**
 * Extracted content and version metadata for a Confluence page.
 *
 * @param xhtml the storage or view XHTML body content
 * @param author display name or username of the last editor, if present
 * @param lastUpdated ISO date (YYYY-MM-DD) of the last update, if present
 * @param version version number of the page, if present
 */
public record ConfluencePageContent(String xhtml,@Nullable String author,@Nullable String lastUpdated,@Nullable Integer version){public ConfluencePageContent{if(xhtml==null){xhtml="";}}}
