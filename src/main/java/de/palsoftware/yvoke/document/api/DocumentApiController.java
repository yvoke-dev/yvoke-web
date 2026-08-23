package de.palsoftware.yvoke.document.api;

import de.palsoftware.yvoke.collection.core.model.Collection;
import de.palsoftware.yvoke.collection.core.service.CollectionService;
import de.palsoftware.yvoke.document.api.model.DocumentDto;
import de.palsoftware.yvoke.document.core.model.DocumentDetails;
import de.palsoftware.yvoke.document.core.repository.DocumentRepository;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/document/v1")
public class DocumentApiController {

    private final DocumentRepository documentRepository;
    private final CollectionService collectionService;

    public DocumentApiController(DocumentRepository documentRepository,
        CollectionService collectionService) {
        this.documentRepository = documentRepository;
        this.collectionService = collectionService;
    }

    /**
     * Lists an area's documents for one version.
     *
     * <p>
     * The area name is matched case-insensitively and then the STORED spelling is what the query
     * runs with. Both halves matter, and they are the two halves of the same rule: matching
     * leniently while querying with the caller's spelling resolves to nothing, and refusing to
     * match at all turns a typo into a confident empty list. Every MCP tool already guards this
     * input the same way; this endpoint was the one member of the family that did not, so
     * {@code collection=oim} against a stored {@code OIM} answered 200 with {@code []} —
     * indistinguishable from an area that genuinely holds no documents.
     */
    @GetMapping
    public List<DocumentDto> listDocuments(@RequestParam("collection") String collectionName,
        @RequestParam("tag") String tag) {

        String requested = collectionName.trim();
        Collection matched = collectionService.listCollections().stream()
            .filter(c -> c.name().equalsIgnoreCase(requested)).findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Collection '" + requested + "' does not exist."));

        return documentRepository.listByCollectionAndTag(matched.name(), tag.trim()).stream()
            .map(DocumentApiController::toDto).toList();
    }

    private static DocumentDto toDto(DocumentDetails d) {
        Map<String, Object> metadata = d.metadata();
        String sourceFile = metadata != null ? (String) metadata.get("source_file") : null;
        return new DocumentDto(d.id(), sourceFile, d.title(), d.ingestionStatus());
    }
}
