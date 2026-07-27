package de.palsoftware.yvoke.kg.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.kg.core.model.KgAdminViews.KgCallView;
import de.palsoftware.yvoke.kg.core.model.KgAdminViews.KgEntityView;
import de.palsoftware.yvoke.kg.core.model.KgAdminViews.KgNeighborhoodView;
import de.palsoftware.yvoke.kg.core.model.KgAdminViews.KgRelationshipView;
import de.palsoftware.yvoke.kg.core.model.KgAdminViews.KgScopeView;
import de.palsoftware.yvoke.kg.core.model.KgAdminViews.KgWalkView;
import de.palsoftware.yvoke.kg.core.model.KgCall;
import de.palsoftware.yvoke.kg.core.model.KgEntity;
import de.palsoftware.yvoke.kg.core.model.KgNeighborEdges;
import de.palsoftware.yvoke.kg.core.model.KgNeighborhood;
import de.palsoftware.yvoke.kg.core.model.KgRelationship;
import de.palsoftware.yvoke.kg.core.repository.KgGraphReadRepository;
import jakarta.annotation.Nullable;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-side view service for the knowledge-graph admin pages (ARC-01 / Wave 3.3). It maps raw
 * {@link KgEntity}/{@link KgRelationship}/{@link KgCall}/etc. rows to the per-view DTOs in
 * {@code KgAdminViews}, so the controller and templates never touch persistence-shaped records.
 */
@Service
public class KgAdminViewService {

    private final KgGraphReadRepository kgRepository;
    private final ObjectMapper objectMapper;

    public KgAdminViewService(KgGraphReadRepository kgRepository, ObjectMapper objectMapper) {
        this.kgRepository = kgRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<KgScopeView> listScopes() {
        return kgRepository.listKgScopes().stream().map(
            s -> new KgScopeView(s.collection(), s.tag(), s.entityCount(), s.relationshipCount()))
            .toList();
    }

    public List<String> listKinds(String collection, @Nullable String tag) {
        return kgRepository.listKinds(collection, tag);
    }

    @Transactional(readOnly = true)
    public List<KgEntityView> searchEntities(String query, int limit, @Nullable String tag,
        String collection, @Nullable String kind) {
        return kgRepository.fuzzySearchEntities(query, limit, tag, collection, kind).stream()
            .map(this::toEntityView).toList();
    }

    @Transactional(readOnly = true)
    public List<KgEntityView> listEntities(String collection, @Nullable String tag,
        @Nullable String kind, int size, int offset) {
        return kgRepository.listEntities(collection, tag, kind, size, offset).stream()
            .map(this::toEntityView).toList();
    }

    public long countEntities(String collection, @Nullable String tag, @Nullable String kind) {
        return kgRepository.countEntities(collection, tag, kind);
    }

    @Transactional(readOnly = true)
    public List<KgRelationshipView> entityRelationships(String entity, @Nullable String tag,
        String collection) {
        return kgRepository.getEntityRelationships(entity, tag, collection).edges().stream()
            .map(KgAdminViewService::toRelationshipView).toList();
    }

    @Transactional(readOnly = true)
    public Optional<KgNeighborhoodView> neighborhood(String entity, @Nullable String tag,
        String collection) {
        KgNeighborhood n = kgRepository.getNeighborhood(entity, tag, collection);
        if (n == null) {
            return Optional.empty();
        }
        return Optional
            .of(new KgNeighborhoodView(n.entity() != null ? toEntityView(n.entity()) : null,
                n.outgoing().stream().map(KgAdminViewService::toRelationshipView).toList(),
                n.incoming().stream().map(KgAdminViewService::toRelationshipView).toList()));
    }

    @Transactional(readOnly = true)
    public Optional<KgEntityView> firstFuzzyMatch(String entity, @Nullable String tag,
        String collection) {
        return kgRepository.fuzzySearchEntities(entity, 1, tag, collection).stream().findFirst()
            .map(this::toEntityView);
    }

    @Transactional(readOnly = true)
    public List<KgWalkView> fkWalk(String entity, int hops, @Nullable String tag,
        String collection) {
        return kgRepository.getFkWalk(entity, hops, tag, collection).stream()
            .map(w -> new KgWalkView(w.depth(), w.path())).toList();
    }

    @Transactional(readOnly = true)
    public List<KgCallView> calls(String entity, String direction, int limit, @Nullable String tag,
        String collection) {
        return kgRepository.getCalls(entity, direction, limit, tag, collection).stream()
            .map(KgAdminViewService::toCallView).toList();
    }

    private KgEntityView toEntityView(KgEntity e) {
        return new KgEntityView(e.name(), e.kind(), e.tags(), e.description(), e.metadata(),
            prettyPrintJson(e.metadata()));
    }

    private static KgRelationshipView toRelationshipView(KgRelationship r) {
        return new KgRelationshipView(r.subject(), r.predicate(), r.object(), r.description());
    }

    private static KgRelationshipView toRelationshipView(KgNeighborEdges.Edge e) {
        return new KgRelationshipView(e.subject(), e.predicate(), e.object(), e.description());
    }

    private static KgCallView toCallView(KgCall c) {
        return new KgCallView(c.name(), c.kind());
    }

    private String prettyPrintJson(@Nullable Object obj) {
        if (obj == null) {
            return "{}";
        }
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }
}
