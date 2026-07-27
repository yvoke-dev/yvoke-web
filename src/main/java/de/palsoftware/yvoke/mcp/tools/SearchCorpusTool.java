package de.palsoftware.yvoke.mcp.tools;

import de.palsoftware.yvoke.collection.core.model.Collection;
import de.palsoftware.yvoke.collection.core.service.CollectionService;
import de.palsoftware.yvoke.mcp.McpToolUtils;
import de.palsoftware.yvoke.rag.core.model.AgenticChatContext;
import de.palsoftware.yvoke.rag.retrieval.HybridSearch;
import de.palsoftware.yvoke.rag.retrieval.HybridSearchResult;
import de.palsoftware.yvoke.rag.retrieval.SearchOptions;
import de.palsoftware.yvoke.rag.retrieval.SearchWithId;
import jakarta.annotation.Nullable;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;


@Component
public class SearchCorpusTool {

    private static final Logger log = LoggerFactory.getLogger(SearchCorpusTool.class);

    private final HybridSearch hybridSearch;
    private final CollectionService collectionService;
    /**
     * The same property {@link HybridSearch} falls back to. Needed here only to know the effective
     * cap when the caller passed no limit, so a full page can be labelled as possibly truncated.
     */
    private final int defaultLimit;

    public SearchCorpusTool(HybridSearch hybridSearch, CollectionService collectionService,
        @Value("${app.retrieval.default-limit}") int defaultLimit) {
        this.hybridSearch = hybridSearch;
        this.collectionService = collectionService;
        this.defaultLimit = defaultLimit;
    }

    /**
     * @param limit max chunks to return; {@code null} leaves {@code app.retrieval.default-limit} in
     *        effect. Deliberately not defaulted here — that property is the single source of truth
     *        and is shared with the chat retrieval path. {@link SearchOptions} floors it at 1 and
     *        caps it at its own {@code MAX_LIMIT}.
     */
    public String searchCorpus(String query, String collection, @Nullable String tag,
        @Nullable Integer limit, @Nullable AgenticChatContext context) {
        log.info("SearchCorpusTool: searching for '{}' in collection '{}'", query, collection);

        if (query == null || query.isBlank()) {
            return "Error: 'query' parameter is required for search_corpus. If you wanted to list documents or modules in a collection (e.g. by kind), please use the 'list_documents' tool instead.";
        }

        if (collection == null || collection.isBlank()) {
            return "Error: 'collection' parameter is required.";
        }
        String col = collection.trim();

        List<Collection> allCols = collectionService.listCollections();
        Collection matchedCol =
            allCols.stream().filter(c -> c.name().equalsIgnoreCase(col)).findFirst().orElse(null);
        if (matchedCol == null) {
            return "Error: Collection '" + col + "' does not exist.";
        }

        // Validate tag (optional)
        String parsedTag = (tag != null && !tag.isBlank()) ? tag.trim() : null;
        // A tag-scoped collection must be read at exactly one tag: untagged reads either
        // duplicate every hit across product versions or merge unrelated datasets, and
        // neither failure is visible in the result. Conditional on the collection, so an
        // untagged collection exempts itself.
        String tagRequired = McpToolUtils.requireTag(matchedCol, parsedTag);
        if (tagRequired != null) {
            return tagRequired;
        }
        if (parsedTag != null) {
            if (matchedCol.tags() == null || !matchedCol.tags().contains(parsedTag)) {
                return "Error: Tag '" + parsedTag + "' does not exist in collection '" + col + "'.";
            }
        }

        List<String> cols = List.of(col);
        List<String> tagList = (parsedTag != null) ? List.of(parsedTag) : List.of();
        boolean rrk = true;

        SearchOptions opts = new SearchOptions(cols, limit, null, null, 0, rrk, tagList);
        try {
            SearchWithId result = hybridSearch.searchWithId(query, opts);
            List<HybridSearchResult> chunks = result.results();

            // Record telemetry in AgenticChatContext if active
            if (context != null) {
                context.getSearchIds().add(result.searchId());
                for (HybridSearchResult chunk : chunks) {
                    if (chunk.id() != null) {
                        context.getRetrievedChunkIds().add(chunk.id());
                    }
                }
            }

            String formatted = McpToolUtils.formatChunks(chunks);
            String suffix = "";
            if (parsedTag != null) {
                suffix += " (tag=" + parsedTag + ")";
            }
            // A full page is indistinguishable from a complete result set unless we say so, and
            // raising the ceiling only moves the silent cliff. Sections routinely exceed it — the
            // Release Notes' "Known issues" is 22 part-chunks — so an unlabelled full page gets
            // enumerated as if it were everything.
            int effectiveLimit = (opts.limit() != null) ? opts.limit() : defaultLimit;
            String truncation = (chunks.size() >= effectiveLimit) ? "\n\n_(showing " + chunks.size()
                + " chunks — the result is capped and more may match. Raise `limit` or narrow "
                + "the query; for a whole section use `get_section`.)_" : "";
            return "Searched **" + col + "**" + suffix + " for: _" + query + "_\n\n" + formatted
                + truncation;
        } catch (Exception e) {
            return McpToolUtils.toolError("search_corpus", e);
        }
    }
}
