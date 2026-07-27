package de.palsoftware.yvoke.kg.core.model;

import jakarta.annotation.Nullable;
import java.util.List;

/**
 * Result of a kind-aware neighbor query for a single starting node. {@code matchedKinds} lists the
 * distinct kinds of entity nodes that matched the requested name in the collection: when it has
 * more than one element and no kind was requested, the name is ambiguous (e.g. a table and a
 * process both named "ADS"). {@code edges} carries each incident relationship already resolved
 * against the start node's entity ids.
 */
public record KgNeighborEdges(List<String>matchedKinds,List<Edge>edges){

/** Where an edge points relative to the queried start node. */
public enum Direction {

    OUTGOING("outgoing"), INCOMING("incoming"), SELF("self");

    private final String label;

    Direction(String label) {
        this.label = label;
    }

    /** Lower-case rendering used in tool output. */
    public String label() {
        return label;
    }

    }

    /**
     * One incident relationship. {@code direction} and {@code counterpart} are resolved in the
     * repository from {@code subject_id}/{@code object_id}; re-deriving them by comparing the start
     * name to {@code subject}/{@code object} is wrong, because two different nodes routinely share
     * a name (module "ADS" ─has_connector→ connector "ADS"). {@link Direction#SELF} therefore means
     * a genuine self-reference ({@code subject_id = object_id}), never a name collision.
     * {@code counterpartKind} is null only when the counterpart endpoint has no entity row (legacy,
     * name-only data).
     */
    public record Edge(String subject, String predicate, String object,
        @Nullable String description, Direction direction, String counterpart,
        @Nullable String counterpartKind) {}
}
