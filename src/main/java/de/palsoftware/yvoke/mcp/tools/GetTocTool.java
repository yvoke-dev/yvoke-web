package de.palsoftware.yvoke.mcp.tools;

import de.palsoftware.yvoke.document.core.HierarchyUtils;
import de.palsoftware.yvoke.document.core.repository.DocumentRepository;
import de.palsoftware.yvoke.document.core.model.DocumentRow;
import de.palsoftware.yvoke.document.core.model.TocNode;
import de.palsoftware.yvoke.document.core.service.TocService;
import de.palsoftware.yvoke.mcp.McpToolUtils;
import java.util.*;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;


@Component
public class GetTocTool {

    private static final Logger log = LoggerFactory.getLogger(GetTocTool.class);

    private final DocumentRepository documentRepository;
    private final TocService tocService;

    public GetTocTool(DocumentRepository documentRepository, TocService tocService) {
        this.documentRepository = documentRepository;
        this.tocService = tocService;
    }

    private static final String DESCRIPTION =
        "Table of contents of a document with section summaries — top-down navigation. Only works "
            + "for hierarchical document types (e.g. kind='hierarchical', kind='confluence'). "
            + "Document IDs (full UUIDs) can be discovered using the 'list_documents' tool or from "
            + "'search_corpus' hits. Returns an indented markdown list where each entry shows the "
            + "section name, its size in chunks and characters, and a one-line summary. Pass "
            + "heading_path to see the two levels BELOW that section instead of the top of the "
            + "document — that is how you navigate into a large section without reading it.";

    private static final String PATH_PARAM =
        "Optional section path, e.g. \"Chapter > Section\". Returns the two levels below it. Omit "
            + "for the top two levels of the whole document.";

    /**
     * Sections at or below this are cheap enough to read whole; above it, descend instead.
     *
     * <p>
     * The number an agent needs is characters, not chunks: chunk sizes across this corpus run from
     * ~300 to ~3,600, so a chunk count says almost nothing about what a {@code get_section} will
     * cost. Reading a whole chapter to find one passage is the expense this tool exists to avoid,
     * and an agent can only avoid it if it is told the size before it commits.
     */
    private static final int READ_WHOLE_CHAR_BUDGET = 30_000;

    @McpTool(name = "get_toc", description = DESCRIPTION)
    @Tool(name = "get_toc", description = DESCRIPTION)
    public String getToc(
        @McpToolParam(description = "Document ID (full UUID).", required = true)
        @ToolParam(description = "Document ID (full UUID).", required = true) String document_id,
        @McpToolParam(description = PATH_PARAM, required = false)
        @ToolParam(description = PATH_PARAM, required = false) String heading_path) {
        log.info("GetTocTool: fetching TOC for document '{}', heading_path='{}'", document_id,
            heading_path);

        if (document_id == null || document_id.isBlank()) {
            return "Error: 'document_id' parameter is required for get_toc.";
        }
        try {
            UUID docId;
            try {
                docId = UUID.fromString(document_id.trim());
            } catch (IllegalArgumentException e) {
                return "Error: Invalid UUID format for 'document_id': " + document_id;
            }

            Optional<DocumentRow> docOpt = documentRepository.findById(docId);
            if (docOpt.isEmpty()) {
                return "Error: Document with ID '" + docId + "' not found.";
            }
            DocumentRow doc = docOpt.get();
            String docName = doc.title();

            List<String> scope = (heading_path == null || heading_path.isBlank()) ? List.of()
                : HierarchyUtils.splitHeadingPath(heading_path);

            List<TocNode> nodes = tocService.getToc(docId, scope);
            if (nodes.isEmpty()) {
                return "(no sections found for this manual)";
            }

            List<String> out = new ArrayList<>();
            out.add("# Table of contents: " + docName);
            out.add(String.format(Locale.US,
                "_(%s  ·  depth ≤ 2 below it  ·  %d entry%s  ·  counts are full-subtree sizes  ·  ↳ lines are section summaries)_",
                scope.isEmpty() ? "full manual" : "under: " + String.join(" > ", scope),
                nodes.size(), nodes.size() == 1 ? "" : "ies"));
            out.add("");

            int lineCap = 400;
            int truncated = 0;
            for (int i = 0; i < nodes.size(); i++) {
                if (out.size() - 3 >= lineCap) {
                    truncated = nodes.size() - i;
                    break;
                }
                TocNode nd = nodes.get(i);
                // Indent relative to the scope, so a deep subtree is not pushed off to the right.
                int indentCount = nd.path().size() - 1 - scope.size();
                String indent = "  ".repeat(Math.max(0, indentCount));
                int count = nd.subtreeChunkCount();
                String lastSeg = nd.path().get(nd.path().size() - 1);
                out.add(String.format(Locale.US, "%s- %s  _(%d chunk%s · %,d chars)_", indent,
                    lastSeg, count, count == 1 ? "" : "s", nd.subtreeCharCount()));
                String summary = nd.summary();
                if (summary != null && !summary.isBlank()) {
                    out.add(indent + "    ↳ " + summary.strip());
                }
            }

            if (truncated > 0) {
                out.add("");
                out.add(String.format(Locale.US, "_[%d more entr%s not shown]_", truncated,
                    truncated == 1 ? "y" : "ies"));
            }
            // The stop rule. Without it the char counts are decoration: the agent needs to be told
            // which of the two calls to make, or it defaults to reading the whole section — the
            // expense this tool exists to avoid.
            out.add("");
            out.add("_Copy a path by joining the entries above with ' > ' "
                + "(paths are absolute, so they work as-is)._");
            out.add(String.format(Locale.US,
                "_Under ~%,d chars: read it — `get_section(document_id=\"%s\", heading_path=\"…\")`._",
                READ_WHOLE_CHAR_BUDGET, docId));
            out.add(String.format(Locale.US,
                "_Larger than that: go one level deeper first — `get_toc(document_id=\"%s\", heading_path=\"…\")`._",
                docId));

            return String.join("\n", out);
        } catch (Exception e) {
            return McpToolUtils.toolError("get_toc", e);
        }
    }
}
