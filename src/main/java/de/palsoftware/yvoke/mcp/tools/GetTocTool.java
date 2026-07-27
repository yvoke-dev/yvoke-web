package de.palsoftware.yvoke.mcp.tools;

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

    @McpTool(name = "get_toc",
        description = "Table of contents of a document with section summaries — top-down navigation. Only works for hierarchical document types (e.g. kind='hierarchical', kind='confluence'). Document IDs (full UUIDs) can be discovered using the 'list_documents' tool or from 'search_corpus' hits. Returns an indented markdown list where each entry shows the section name, chunk count, and a one-line summary.")
    @Tool(name = "get_toc",
        description = "Table of contents of a document with section summaries — top-down navigation. Only works for hierarchical document types (e.g. kind='hierarchical', kind='confluence'). Document IDs (full UUIDs) can be discovered using the 'list_documents' tool or from 'search_corpus' hits. Returns an indented markdown list where each entry shows the section name, chunk count, and a one-line summary.")
    public String getToc(@McpToolParam(description = "Document ID (full UUID).", required = true)
    @ToolParam(description = "Document ID (full UUID).", required = true) String document_id) {
        log.info("GetTocTool: fetching TOC for document '{}'", document_id);

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

            List<TocNode> nodes = tocService.getToc(docId);
            if (nodes.isEmpty()) {
                return "(no sections found for this manual)";
            }

            List<String> out = new ArrayList<>();
            out.add("# Table of contents: " + docName);
            out.add(String.format(Locale.US,
                "_(full manual  ·  depth ≤ 2  ·  %d entry%s  ·  chunk counts are full-subtree sizes  ·  ↳ lines are section summaries)_",
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
                int indentCount = nd.path().size() - 1;
                String indent = "  ".repeat(Math.max(0, indentCount));
                int count = nd.subtreeChunkCount();
                String lastSeg = nd.path().get(nd.path().size() - 1);
                out.add(String.format(Locale.US, "%s- %s  _(%d chunk%s)_", indent, lastSeg, count,
                    count == 1 ? "" : "s"));
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
            out.add("");
            out.add("_Read text: `get_section(document_id=\"" + docId
                + "\", heading_path=\"…\")` (copy a path by joining the entries with ' > ')._");

            return String.join("\n", out);
        } catch (Exception e) {
            return McpToolUtils.toolError("get_toc", e);
        }
    }
}
