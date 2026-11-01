package de.palsoftware.yvoke.document.web;

import de.palsoftware.yvoke.collection.core.model.Collection;
import de.palsoftware.yvoke.collection.core.service.CollectionService;
import de.palsoftware.yvoke.document.core.model.ChunkRow;
import de.palsoftware.yvoke.document.core.model.DocumentRow;
import de.palsoftware.yvoke.document.core.model.SectionResponse;
import de.palsoftware.yvoke.document.core.repository.ChunkRepository;
import de.palsoftware.yvoke.document.core.repository.DocumentRepository;
import de.palsoftware.yvoke.document.core.service.SectionService;
import de.palsoftware.yvoke.shared.security.AreaAccessContext;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequestMapping("/document")
public class CitationController {
    private static final Logger log = LoggerFactory.getLogger(CitationController.class);

    static final String MISSING_PARAMS_ERROR = "Either chunkId or documentId must be provided.";
    static final String UNRESOLVABLE_ERROR =
        "This citation could not be resolved to a single source.";
    static final String NOT_FOUND_ERROR = "This source is no longer available.";
    static final String UNAVAILABLE_ERROR = "Citation details are currently unavailable.";

    private final SectionService sectionService;
    private final ChunkRepository chunkRepository;
    private final DocumentRepository documentRepository;
    private final CollectionService collectionService;

    @Autowired
    public CitationController(SectionService sectionService, ChunkRepository chunkRepository,
        DocumentRepository documentRepository, CollectionService collectionService) {
        this.sectionService = sectionService;
        this.chunkRepository = chunkRepository;
        this.documentRepository = documentRepository;
        this.collectionService = collectionService;
    }

    public CitationController(SectionService sectionService) {
        this(sectionService, null, null, null);
    }

    @GetMapping("/citation")
    public String getCitation(@RequestParam(value = "chunkId", required = false) String chunkId,
        @RequestParam(value = "documentId", required = false) String documentId, Model model) {
        log.info("Requesting citation partial: chunkId='{}', documentId='{}'", chunkId, documentId);
        boolean hasChunkId = chunkId != null && !chunkId.isBlank();
        boolean hasDocumentId = documentId != null && !documentId.isBlank();
        if (!hasChunkId && !hasDocumentId) {
            // The only citation error whose text this controller authors itself, so the only one
            // safe to render verbatim.
            log.warn("Invalid citation request: no chunkId or documentId given");
            model.addAttribute("section", null);
            model.addAttribute("error", MISSING_PARAMS_ERROR);
            return "document/fragments/citation-expander";
        }

        try {
            // A chunk id names one passage and returns exactly that; a document id has no passage
            // to name, so it still returns the document.
            verifyCitationAreaAccess(chunkId, documentId);
            SectionResponse section = hasChunkId ? sectionService.getChunkContent(chunkId)
                : sectionService.getSectionByDocumentId(documentId, null);

            model.addAttribute("section", section);
            model.addAttribute("error", null);
        } catch (IllegalArgumentException e) {
            // The lookup could not be narrowed to one document. The message is NOT
            // developer-authored: DocumentRepository.findByManual enumerates every match with its
            // full id, kind, title and tags (a [file=…] citation has been seen matching 131 rows),
            // so surfacing it would dump a document inventory into the dialog. Log it, show a fixed
            // string (SEC-17 / ARC-10).
            log.warn("Ambiguous or invalid citation request: {}", e.getMessage());
            model.addAttribute("section", null);
            model.addAttribute("error", UNRESOLVABLE_ERROR);
        } catch (NoSuchElementException e) {
            // Stale reference — a re-ingested or deleted id, not an outage. Fixed string here too:
            // SectionService enumerates the document's section paths when no section matches.
            log.warn("Citation target no longer exists: {}", e.getMessage());
            model.addAttribute("section", null);
            model.addAttribute("error", NOT_FOUND_ERROR);
        } catch (Exception e) {
            // Unexpected failure (e.g. data-access). Log the real cause; show a generic note so no
            // SQL / provider / stack detail leaks into the rendered fragment (SEC-17 / ARC-10).
            log.error("Failed to resolve citation details", e);
            model.addAttribute("section", null);
            model.addAttribute("error", UNAVAILABLE_ERROR);
        }

        return "document/fragments/citation-expander";
    }

    private void verifyCitationAreaAccess(String chunkId, String documentId) {
        if (chunkRepository == null || documentRepository == null || collectionService == null) {
            return;
        }
        String area = null;
        if (chunkId != null && !chunkId.isBlank()) {
            Optional<ChunkRow> chunkOpt = chunkRepository.findByIdPrefix(chunkId.trim());
            if (chunkOpt.isEmpty()) {
                throw new NoSuchElementException("Chunk not found: " + chunkId);
            }
            ChunkRow chunk = chunkOpt.get();
            Optional<Collection> collOpt = collectionService.getCollection(chunk.collection());
            if (collOpt.isPresent()) {
                area = collOpt.get().area();
            }
        } else if (documentId != null && !documentId.isBlank()) {
            UUID docUuid;
            try {
                docUuid = UUID.fromString(documentId.trim());
            } catch (IllegalArgumentException e) {
                throw new NoSuchElementException("Invalid document UUID: " + documentId);
            }
            Optional<DocumentRow> docOpt = documentRepository.findById(docUuid);
            if (docOpt.isEmpty()) {
                throw new NoSuchElementException("Document not found: " + documentId);
            }
            DocumentRow doc = docOpt.get();
            Optional<Collection> collOpt = collectionService.findById(doc.collectionId());
            if (collOpt.isPresent()) {
                area = collOpt.get().area();
            }
        }
        if (area != null && !area.isBlank() && !AreaAccessContext.canAccessUserArea(area)) {
            throw new NoSuchElementException("Citation not found");
        }
    }
}
