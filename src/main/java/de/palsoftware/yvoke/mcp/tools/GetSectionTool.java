package de.palsoftware.yvoke.mcp.tools;


import de.palsoftware.yvoke.document.core.model.SectionResponse;
import de.palsoftware.yvoke.document.core.service.SectionService;
import de.palsoftware.yvoke.mcp.McpToolUtils;
import jakarta.annotation.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;


@Component
public class GetSectionTool {

    private static final Logger log = LoggerFactory.getLogger(GetSectionTool.class);

    private final SectionService sectionService;

    public GetSectionTool(SectionService sectionService) {
        this.sectionService = sectionService;
    }

    @McpTool(name = "get_section",
        description = "Fetch the full text of a section or the entire document. You must provide either document_id (and optional heading_path), chunk_id, or document (file name).")
    @Tool(name = "get_section",
        description = "Fetch the full text of a section or the entire document. You must provide either document_id (and optional heading_path), chunk_id, or document (file name).")
    public String getSection(
        @McpToolParam(description = "Document ID (full UUID).", required = false)
        @ToolParam(description = "Document ID (full UUID).", required = false)
        @Nullable String document_id,
        @McpToolParam(
            description = "Section path, e.g. \"Chapter > Section\". When nothing is specified the full document is returned.",
            required = false)
        @ToolParam(
            description = "Section path, e.g. \"Chapter > Section\". When nothing is specified the full document is returned.",
            required = false)
        @Nullable String heading_path,
        @McpToolParam(description = "Chunk ID (full UUID).", required = false)
        @ToolParam(description = "Chunk ID (full UUID).", required = false)
        @Nullable String chunk_id) {
        log.info("GetSectionTool: fetching section. document_id='{}', heading_path='{}', "
            + "chunk_id='{}'", document_id, heading_path, chunk_id);
        try {
            SectionResponse resp;
            if (document_id != null && !document_id.isBlank()) {
                resp = sectionService.getSectionByDocumentId(document_id.trim(), heading_path);
            } else if (chunk_id != null && !chunk_id.isBlank()) {
                resp = sectionService.getSectionByChunkId(chunk_id.trim());
            } else {
                // Name-based lookup was removed: a document name is not unique (one corpus
                // lookup matched 131 rows), so it can never identify a section unambiguously.
                return "Error: Either 'document_id' or 'chunk_id' must be provided.";
            }
            return resp.text();
        } catch (Exception e) {
            return McpToolUtils.toolError("get_section", e);
        }
    }
}
