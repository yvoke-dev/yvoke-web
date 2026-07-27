package de.palsoftware.yvoke.kg.core.model;

import jakarta.annotation.Nullable;
import java.util.List;
import java.util.Map;

/**
 * Per-view DTOs for the knowledge-graph admin pages (ARC-01 / Wave 3.3). The kg templates render
 * these slim projections instead of raw {@link KgEntity}/{@link KgRelationship}/{@link KgCall}/etc.
 * records, so persistence-shaped types never leak into Thymeleaf. Accessor names mirror the source
 * records (including the derived {@code displayTag}) so the templates bind unchanged; mapping is in
 * {@code KgAdminViewService}.
 */
public final class KgAdminViews {

    private KgAdminViews() {}

    /** A scope row on the KG overview (admin/kg). */
    public record KgScopeView(String collection, String tag, long entityCount,
        long relationshipCount) {}

    /**
     * An entity in the search/browse list or the active-entity panel (admin/kg-view). Carries the
     * tag list purely to reproduce {@link #displayTag(String)}; {@code metadataJson} is pre-rendered
     * so the template never touches a raw metadata map beyond the emptiness guard.
     */
    public record KgEntityView(String name, @Nullable String kind, List<String> tags,
        @Nullable String description, Map<String, Object> metadata, String metadataJson) {

        @Nullable
        public String displayTag(@Nullable String activeTag) {
            if (activeTag != null && !activeTag.isBlank() && tags != null
                && tags.contains(activeTag.trim())) {
                return activeTag.trim();
            }
            if (tags != null && !tags.isEmpty()) {
                return String.join(", ", tags);
            }
            return null;
        }
    }

    /** A relationship / neighborhood edge (admin/kg-view). */
    public record KgRelationshipView(String subject, String predicate, String object,
        @Nullable String description) {}

    /** The neighborhood panel; {@code entity} is used server-side to seed the active entity. */
    public record KgNeighborhoodView(@Nullable KgEntityView entity,
        List<KgRelationshipView> outgoing, List<KgRelationshipView> incoming) {}

    /** A row in the FK-walk table (admin/kg-view, OIM-DB only). */
    public record KgWalkView(int depth, List<String> path) {}

    /**
     * A caller/callee row in the call-graph table (admin/kg-view, OIM-DB only). Exposes
     * {@code category()} to match the template — the raw {@link KgCall} only had {@code kind()}, so
     * the call-graph section used to fail to render; mapping {@code category = kind} fixes it.
     */
    public record KgCallView(String name, @Nullable String category) {}
}
