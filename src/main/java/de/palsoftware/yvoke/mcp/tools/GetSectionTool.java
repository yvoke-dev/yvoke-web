package de.palsoftware.yvoke.mcp.tools;


import de.palsoftware.yvoke.collection.core.model.Collection;
import de.palsoftware.yvoke.collection.core.service.CollectionService;
import de.palsoftware.yvoke.document.core.model.ChunkRow;
import de.palsoftware.yvoke.document.core.model.DocumentRow;
import de.palsoftware.yvoke.document.core.model.SectionChunks;
import de.palsoftware.yvoke.document.core.repository.ChunkRepository;
import de.palsoftware.yvoke.document.core.repository.DocumentRepository;
import de.palsoftware.yvoke.rag.core.model.AgenticChatContext;
import de.palsoftware.yvoke.rag.core.model.SeenChunks;
import de.palsoftware.yvoke.rag.retrieval.ChunkBlocks;
import de.palsoftware.yvoke.document.core.service.SectionService;
import de.palsoftware.yvoke.mcp.McpToolUtils;
import de.palsoftware.yvoke.shared.security.AreaAccessContext;
import jakarta.annotation.Nullable;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;


@Component
public class GetSectionTool {

    private static final Logger log = LoggerFactory.getLogger(GetSectionTool.class);

    private final SectionService sectionService;
    private final DocumentRepository documentRepository;
    private final ChunkRepository chunkRepository;
    private final CollectionService collectionService;

    public GetSectionTool(SectionService sectionService, DocumentRepository documentRepository,
        ChunkRepository chunkRepository, CollectionService collectionService) {
        this.sectionService = sectionService;
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.collectionService = collectionService;
    }

    /** No conversation: an external MCP client, which is never told text is "shown above". */
    public String getSection(@Nullable String document_id, @Nullable String heading_path,
        @Nullable String chunk_id) {
        return getSection(document_id, heading_path, chunk_id, null);
    }


    /**
     * The in-conversation entry point. {@code seen} is the conversation's ledger of passages
     * already rendered in full, so a section that overlaps an earlier search returns references for
     * the overlap instead of the text a second time.
     *
     * @param context the agentic conversation, or {@code null} for an external MCP client — which
     *        has no transcript for "already shown above" to refer to, so it always gets full text
     */
    public String getSection(@Nullable String document_id, @Nullable String heading_path,
        @Nullable String chunk_id, @Nullable AgenticChatContext context) {
        log.info("GetSectionTool: fetching section. document_id='{}', heading_path='{}', "
            + "chunk_id='{}'", document_id, heading_path, chunk_id);
        try {
            SectionChunks section;
            if (document_id != null && !document_id.isBlank()) {
                String trimmedDocId = document_id.trim();
                if (!isDocumentAreaAllowed(trimmedDocId, context)) {
                    return "Error: Document not found: " + trimmedDocId;
                }
                section = sectionService.getSectionChunksByDocumentId(trimmedDocId, heading_path);
            } else if (chunk_id != null && !chunk_id.isBlank()) {
                String trimmedChunkId = chunk_id.trim();
                String chunkError = checkChunkAreaAccess(trimmedChunkId, context);
                if (chunkError != null) {
                    return chunkError;
                }
                section = sectionService.getSectionChunksByChunkId(trimmedChunkId);
            } else {
                return "Error: Either 'document_id' or 'chunk_id' must be provided.";
            }
            return render(section, context != null ? context : SeenChunks.NONE);
        } catch (Exception e) {
            return McpToolUtils.toolError("get_section", e);
        }
    }

    private boolean isDocumentAreaAllowed(String docIdStr, @Nullable AgenticChatContext context) {
        UUID docUuid;
        try {
            docUuid = UUID.fromString(docIdStr);
        } catch (IllegalArgumentException e) {
            return true;
        }
        Optional<DocumentRow> docOpt = documentRepository.findById(docUuid);
        if (docOpt.isEmpty() || docOpt.get().collectionId() == null) {
            return true;
        }
        Optional<Collection> colOpt = collectionService.findById(docOpt.get().collectionId());
        if (colOpt.isEmpty()) {
            return true;
        }
        return isAreaAllowed(colOpt.get().area(), context);
    }

    @Nullable
    private String checkChunkAreaAccess(String chunkIdStr, @Nullable AgenticChatContext context) {
        Optional<ChunkRow> chunkOpt;
        try {
            chunkOpt = chunkRepository.findByIdPrefix(chunkIdStr);
        } catch (IllegalArgumentException e) {
            boolean areaRestricted = context != null ? context.getAllowedAreasLower() != null
                : !AreaAccessContext.hasAllUserAreas();
            if (e.getMessage() != null && e.getMessage().startsWith("Ambiguous")
                && areaRestricted) {
                return "Error: Ambiguous chunk ID prefix '" + chunkIdStr
                    + "'. Please provide the full chunk UUID.";
            }
            return null;
        }
        if (chunkOpt.isEmpty() || chunkOpt.get().documentId() == null) {
            return null;
        }
        Optional<DocumentRow> docOpt = documentRepository.findById(chunkOpt.get().documentId());
        if (docOpt.isEmpty() || docOpt.get().collectionId() == null) {
            return null;
        }
        Optional<Collection> colOpt = collectionService.findById(docOpt.get().collectionId());
        if (colOpt.isEmpty()) {
            return null;
        }
        if (!isAreaAllowed(colOpt.get().area(), context)) {
            return "Error: Chunk not found: " + chunkIdStr;
        }
        return null;
    }

    private static boolean isAreaAllowed(@Nullable String area,
        @Nullable AgenticChatContext context) {
        if (context != null) {
            return context.isAreaAllowed(area);
        }
        if (area == null || area.isBlank()) {
            return true;
        }
        return AreaAccessContext.canAccessUserArea(area);
    }

    /**
     * Renders the section with each passage preceded by its own id, so an agent can cite the
     * passage it actually used rather than the whole document.
     *
     * <p>
     * The marker is {@code _(id=…  doc_id=…)_} and not the
     * {@code ### kind/title  (score=… id=… doc_id=…)} header {@code search_corpus} emits, because a
     * section read has no relevance score and that pattern requires one. {@code ChunkBlocks}
     * recognises BOTH shapes, so {@code EvidenceDigest} reduces a section to the passages the
     * answer cites instead of sending all of it to the reviewer. The {@code doc_id} is what keeps
     * an answer that still cites a section by its document from losing every passage.
     *
     * <p>
     * Parentheses, never brackets: {@code CitationVerifier} scans {@code [...]} and
     * {@code CitationStreamingFilter} deletes from a live answer what it reads as a fabricated
     * citation, so a bracketed marker would be teaching the model to emit the one shape that can be
     * stripped (CLAUDE.md § 6).
     */
    private String render(SectionChunks section, SeenChunks seen) {
        String sectionName = section.headingPath().isEmpty() ? "Full Document"
            : String.join(" > ", section.headingPath());
        StringBuilder sb = new StringBuilder();
        sb.append("# Section: ").append(sectionName).append("\n");
        sb.append("_(document: ").append(section.documentTitle()).append("  ·  tag: ")
            .append(section.tag()).append("  ·  ").append(section.chunks().size())
            .append(" passage(s)  ·  ").append(section.scope())
            .append("  ·  cite a passage by the id shown above it)_\n");
        for (SectionChunks.SectionChunk chunk : section.chunks()) {
            sb.append("\n_(id=").append(chunk.id()).append("  doc_id=").append(chunk.documentId())
                .append(")_\n");
            // Check-and-mark in ONE step: the answer to "render the body?" IS the recording, so a
            // passage cannot be marked before it is rendered, and a section repeating a chunk
            // within itself falls out with no extra branch. A null id always renders in full —
            // HashSet.add(null) would otherwise collapse every id-less passage into one.
            boolean firstSighting = (chunk.id() == null) || seen.firstSighting(chunk.id());
            sb.append(firstSighting ? chunk.text() : ChunkBlocks.SHOWN_ABOVE).append("\n");
        }
        return sb.toString();
    }
}
