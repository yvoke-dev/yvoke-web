package de.palsoftware.yvoke.lifecycle.core.service;

import de.palsoftware.yvoke.collection.core.model.Collection;
import de.palsoftware.yvoke.collection.core.repository.CollectionRepository;
import de.palsoftware.yvoke.document.core.repository.DocumentRepository;
import de.palsoftware.yvoke.document.core.model.DocumentRow;
import de.palsoftware.yvoke.kg.core.repository.KgWriteRepository;
import de.palsoftware.yvoke.tag.core.repository.TagRepository;
import de.palsoftware.yvoke.shared.audit.repository.AuditLogRepository;
import de.palsoftware.yvoke.shared.user.model.User;
import de.palsoftware.yvoke.shared.user.service.UserService;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cross-domain content-deletion coordinator. It owns the ORDER and atomicity of cascading deletes
 * across the document, knowledge-graph and collection domains; the actual SQL lives in each
 * domain's repository. This is a (cross-cutting) domain orchestrator, so it lives in its own {@code
 * lifecycle} domain rather than in {@code shared}, which must not depend on domain packages.
 */
@Service
public class LifecycleService {

    private static final Logger log = LoggerFactory.getLogger(LifecycleService.class);
    private static final String DEFAULT_ACTOR = "anonymous_admin";

    private final DocumentRepository documentRepository;
    private final KgWriteRepository kgRepository;
    private final CollectionRepository collectionRepository;
    private final de.palsoftware.yvoke.jsonobject.core.service.JsonObjectService jsonObjectService;
    private final TagRepository tagRepository;
    private final AuditLogRepository auditLogRepository;
    private final UserService userService;

    public LifecycleService(DocumentRepository documentRepository, KgWriteRepository kgRepository,
        CollectionRepository collectionRepository,
        de.palsoftware.yvoke.jsonobject.core.service.JsonObjectService jsonObjectService,
        TagRepository tagRepository, AuditLogRepository auditLogRepository,
        UserService userService) {
        this.documentRepository = documentRepository;
        this.kgRepository = kgRepository;
        this.collectionRepository = collectionRepository;
        this.jsonObjectService = jsonObjectService;
        this.tagRepository = tagRepository;
        this.auditLogRepository = auditLogRepository;
        this.userService = userService;
    }

    private String getActor() {
        return userService.getCurrentUser().map(User::entraOid).orElse(DEFAULT_ACTOR);
    }

    @Transactional
    public void deleteDocument(UUID id) {
        DocumentRow doc = documentRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Document not found: " + id));

        log.info("Deleting document {} (title={})", doc.id(), doc.title());

        documentRepository.deleteById(id);

        Map<String, Object> details = new HashMap<>();
        details.put("title", doc.title());
        details.put("collection", doc.collection());
        if (doc.metadata() != null && doc.metadata().get("tag") != null) {
            details.put("tag", doc.metadata().get("tag"));
        }

        auditLogRepository.log(getActor(), "DELETE_DOCUMENT", doc.id().toString(), details);
    }

    @Transactional
    public void deleteCollection(String collection) {
        log.info("Deleting entire collection: {}", collection);

        // Order matters: wipe content (resolved by collection name) before removing the collection
        // row.
        int docsDeleted = documentRepository.deleteByCollection(collection);
        kgRepository.deleteCollectionGraph(collection);

        collectionRepository.findByName(collection).ifPresent(col -> {
            jsonObjectService.deleteObjectsByCollection(col.id());
        });

        collectionRepository.delete(collection);

        auditLogRepository.log(getActor(), "DELETE_COLLECTION", collection,
            Map.of("collection", collection, "documents_deleted", docsDeleted));
    }

    /**
     * Removes a tag from a collection with tag-aware cascade: content shared with other tags is
     * only detached from the tag, and only content left with no tags is deleted. This replaces the
     * previous hard-delete (in TagRepository) that destroyed every row carrying the tag — including
     * multi-tagged content that should have survived (MNT-01).
     */
    @Transactional
    public void removeTagFromCollection(UUID collectionId, String tag) {
        String cleaned = tag == null ? "" : tag.trim();
        if (cleaned.isEmpty()) {
            return;
        }
        Collection col = collectionRepository.findById(collectionId).orElseThrow(
            () -> new IllegalArgumentException("Collection not found: " + collectionId));

        log.info("Removing tag '{}' from collection {} (title={})", cleaned, col.id(), col.name());

        int docsDeleted = documentRepository.removeTagAndPurgeOrphans(collectionId, cleaned);
        kgRepository.deleteTagGraph(col.name(), cleaned); // entities + relationships (tag-aware)
        jsonObjectService.removeTagAndPurgeOrphans(collectionId, cleaned);

        // Finally detach the tag from the collection itself.
        tagRepository.removeTagFromCollection(collectionId, cleaned);

        Map<String, Object> details = new HashMap<>();
        details.put("collection", col.name());
        details.put("tag", cleaned);
        details.put("documents_deleted", docsDeleted);
        auditLogRepository.log(getActor(), "REMOVE_COLLECTION_TAG", collectionId.toString(),
            details);
    }
}
