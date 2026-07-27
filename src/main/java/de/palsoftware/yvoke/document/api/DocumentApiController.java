package de.palsoftware.yvoke.document.api;

import de.palsoftware.yvoke.document.api.model.DocumentDto;
import de.palsoftware.yvoke.document.core.model.DocumentDetails;
import de.palsoftware.yvoke.document.core.repository.DocumentRepository;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/document/v1")
public class DocumentApiController {

    private final DocumentRepository documentRepository;

    public DocumentApiController(DocumentRepository documentRepository) {
        this.documentRepository = documentRepository;
    }

    @GetMapping
    public List<DocumentDto> listDocuments(@RequestParam("collection") String collectionName,
        @RequestParam("tag") String tag) {

        return documentRepository.listByCollectionAndTag(collectionName.trim(), tag.trim()).stream()
            .map(DocumentApiController::toDto).toList();
    }

    private static DocumentDto toDto(DocumentDetails d) {
        Map<String, Object> metadata = d.metadata();
        String sourceFile = metadata != null ? (String) metadata.get("source_file") : null;
        return new DocumentDto(d.id(), sourceFile, d.title(), d.ingestionStatus());
    }
}
