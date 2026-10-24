package de.palsoftware.yvoke.collection.core.service;

import de.palsoftware.yvoke.area.core.AreaService;
import de.palsoftware.yvoke.collection.core.model.Collection;
import de.palsoftware.yvoke.collection.core.repository.CollectionRepository;
import de.palsoftware.yvoke.shared.security.AreaAccessContext;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CollectionService {

    private final CollectionRepository collectionRepository;
    private final AreaService areaService;

    public CollectionService(CollectionRepository collectionRepository, AreaService areaService) {
        this.collectionRepository = collectionRepository;
        this.areaService = areaService;
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

    public Optional<Collection> findById(UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        return collectionRepository.findById(id);
    }

    public Optional<Collection> findByName(String name) {
        return getCollection(name);
    }

    /**
     * Filters collections by an allowed set of lowercase area names. When areasLower is null,
     * returns all collections.
     */
    public List<Collection> listByAreas(Iterable<String> areasLower) {
        if (areasLower == null) {
            return listCollections();
        }
        Set<String> set =
            StreamSupport.stream(areasLower.spliterator(), false).filter(Objects::nonNull)
                .map(s -> s.trim().toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        if (set.isEmpty()) {
            return List.of();
        }
        return listCollections().stream()
            .filter(c -> c.area() != null && set.contains(c.area().trim().toLowerCase(Locale.ROOT)))
            .toList();
    }

    /**
     * Lists collections in areas administered by the current user (all collections for Superadmin).
     */
    public List<Collection> listForAdminUser() {
        return AreaAccessContext.allowedAdminAreasLower().map(this::listByAreas)
            .orElseGet(this::listCollections);
    }

    public Optional<Collection> findByIdForAdmin(UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        return collectionRepository.findById(id)
            .filter(c -> AreaAccessContext.canAdministerArea(c.area()));
    }

    public Optional<Collection> findByNameForAdmin(String name) {
        return getCollection(name).filter(c -> AreaAccessContext.canAdministerArea(c.area()));
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
    public Collection createCollection(String name, String description, String area) {
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

        return collectionRepository.create(trimmedName, description, areaService.requireArea(area));
    }

    /** Moves an existing collection to another area. */
    @Transactional
    public String moveCollection(String name, String area) {
        Collection collection = getCollection(name).orElseThrow(
            () -> new IllegalArgumentException("Collection '" + name + "' does not exist."));
        String storedArea = areaService.requireArea(area);
        collectionRepository.updateArea(collection.name(), storedArea);
        return storedArea;
    }
}
