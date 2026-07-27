package de.palsoftware.yvoke.collection.core.service;

import de.palsoftware.yvoke.collection.core.model.Collection;
import de.palsoftware.yvoke.collection.core.repository.CollectionRepository;


import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CollectionService {

    private final CollectionRepository collectionRepository;

    public CollectionService(CollectionRepository collectionRepository) {
        this.collectionRepository = collectionRepository;
    }

    public List<Collection> listCollections() {
        return collectionRepository.findAll();
    }

    public Optional<Collection> getCollection(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return collectionRepository.findByName(name);
    }

    /**
     * The corpus tag vocabulary — every tag declared on any collection. Feeds the admin tag
     * suggestions when no single collection is in scope.
     */
    public List<String> listAllTags() {
        return collectionRepository.findAllTagNames();
    }

    /**
     * The tags one collection declares, empty for an unknown or blank name.
     *
     * <p>
     * This is the same list {@code CollectionTagEnqueueValidator} admits an ingest against, so the
     * admin filters offer exactly the tags that collection's content can carry.
     */
    public List<String> listTagsOf(String collectionName) {
        return getCollection(collectionName).map(Collection::tags).orElseGet(List::of);
    }

    @Transactional
    public Collection createCollection(String name, String description) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Collection name cannot be empty.");
        }
        String trimmedName = name.trim();
        if ("Both".equalsIgnoreCase(trimmedName) || "All".equalsIgnoreCase(trimmedName)) {
            throw new IllegalArgumentException(
                "Collection name '" + trimmedName + "' is reserved.");
        }

        Optional<Collection> existing = collectionRepository.findByName(trimmedName);
        if (existing.isPresent()) {
            throw new IllegalArgumentException("Collection '" + trimmedName + "' already exists.");
        }

        return collectionRepository.create(trimmedName, description);
    }
}
