package de.palsoftware.yvoke.tag.core.service;

import de.palsoftware.yvoke.tag.core.repository.TagRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional entry point for tag mutations (ARC-08 / MNT-09).
 *
 * <p>
 * Presentation-layer callers (admin controllers, the ingest API) go through this service so the
 * guarded array-append runs atomically with the sibling-collision check that decides whether it may
 * run at all. {@link TagRepository} no longer carries {@code @Transactional} — transaction
 * demarcation lives on the service layer. Domain services that already manage their own transaction
 * boundary (e.g. {@code ChatConversationService}, {@code LifecycleService}) use the repository
 * directly within their existing transaction.
 *
 * <p>
 * Mutations only: "which tags exist" is derived from the {@code TEXT[]} columns by
 * {@code CollectionService.listAllTags()} (corpus) and {@code ChatConversationService} (chat
 * folders), never from a registry — see the closing note in {@link TagRepository}.
 */
@Service
public class TagService {

    private final TagRepository tagRepository;

    public TagService(TagRepository tagRepository) {
        this.tagRepository = tagRepository;
    }

    @Transactional
    public void addTagToCollection(UUID collectionId, String tagName) {
        tagRepository.addTagToCollection(collectionId, tagName);
    }

    @Transactional
    public void addTagToDocument(UUID documentId, String tagName) {
        tagRepository.addTagToDocument(documentId, tagName);
    }

    @Transactional
    public void removeTagFromDocument(UUID documentId, String tagName) {
        tagRepository.removeTagFromDocument(documentId, tagName);
    }
}
