package de.palsoftware.yvoke.kg.core.service;

import java.sql.Array;
import java.sql.SQLException;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class KgConsolidator {

    private static final Logger log = LoggerFactory.getLogger(KgConsolidator.class);

    private final JdbcClient jdbcClient;
    private final TransactionTemplate transactionTemplate;

    public KgConsolidator(JdbcClient jdbcClient, PlatformTransactionManager transactionManager) {
        this.jdbcClient = jdbcClient;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public record ConsolidationStats(int groupsProcessed, int rowsDeleted,
        int relationshipsRepointCount, int relationshipsCollapsedCount) {}

    public ConsolidationStats consolidate(String collection, String tag) {
        log.info("Starting KG consolidation for collection={}, tag={}", collection, tag);

        return transactionTemplate.execute(status -> {
            // 1. Find duplicate entity groups. Grouping is kind-aware (coalesce(kind,'')) so a
            // same-named entity of a different kind (e.g. table 'ADS' vs module 'ADS') is NOT
            // treated as a duplicate — that homonym distinction is exactly what the kind-aware
            // graph identity preserves. Only true (kind, name) case-variants are consolidated.
            //
            // Grouping is also TAG-SCOPE-aware (V2). Two versions of one product live in one
            // collection separated only by tag, so the same (kind, name) under two scopes is two
            // legitimate rows — not a duplicate. Without kg_canonical_tags(e.tags) in the GROUP BY
            // this merges them and DELETEs one, cascading its edges away through
            // fk_relationships_subject/object. The tag parameter is only a row FILTER, and
            // DocumentIngestService calls consolidate(collection, null) for any untagged
            // kg-extract job, which switches that filter off entirely — so the filter alone
            // cannot protect the split.
            List<Map<String, Object>> groups = jdbcClient.sql("""
                SELECT LOWER(TRIM(e.name)) AS name_lc,
                       COALESCE(e.kind, '') AS kind_lc,
                       ARRAY_AGG(e.id ORDER BY e.id) AS all_ids,
                       ARRAY_AGG(e.name) AS all_names,
                       COUNT(*) AS count
                 FROM entities e
                 JOIN collections c ON e.collection_id = c.id
                WHERE c.name = :collection
                  AND (:tag = ANY(e.tags) OR CAST(:tag AS text) IS NULL)
                GROUP BY LOWER(TRIM(e.name)), COALESCE(e.kind, ''), kg_canonical_tags(e.tags)
                HAVING COUNT(*) > 1
                """).param("collection", collection).param("tag", tag).query().listOfRows();

            if (groups.isEmpty()) {
                log.info("No duplicate entity groups found for consolidation.");
                return new ConsolidationStats(0, 0, 0, 0);
            }

            log.info("Found {} duplicate entity name groups in collection={}, tag={}",
                groups.size(), collection, tag);

            int rowsDeleted = 0;
            int relationshipsRepointCount = 0;

            for (Map<String, Object> group : groups) {
                List<UUID> allIds = parseUuidArray(group.get("all_ids"));
                List<String> allNames = parseStringArray(group.get("all_names"));

                if (allIds.size() < 2) {
                    continue;
                }

                UUID canonicalId = allIds.get(0);
                List<UUID> aliasIds = allIds.subList(1, allIds.size());

                // Find canonical name
                String canonicalName =
                    jdbcClient.sql("SELECT name FROM entities WHERE id = :canonicalId")
                        .param("canonicalId", canonicalId).query(String.class).single();

                // Find alias names different from canonical name
                List<String> aliasNames =
                    allNames.stream().filter(n -> !n.equals(canonicalName)).distinct().toList();

                // Update relationships subject/object and subject_id/object_id to canonical
                int repointedSubjects = jdbcClient
                    .sql(
                        """
                            UPDATE relationships
                            SET subject = :canonicalName,
                                subject_id = :canonicalId
                            FROM collections c
                            WHERE relationships.collection_id = c.id
                              AND c.name = :collection
                              AND (:tag = ANY(relationships.tags) OR CAST(:tag AS text) IS NULL)
                              -- Repointing also matches on NAME text, which is not scope-aware:
                              -- without this, merging a case-variant pair in one version's scope
                              -- would drag the OTHER version's identically-named edges onto this
                              -- version's canonical entity.
                              AND kg_canonical_tags(relationships.tags)
                                  = kg_canonical_tags((SELECT tags FROM entities WHERE id = :canonicalId))
                              AND (relationships.subject IN (:aliasNames) OR relationships.subject_id IN (:aliasIds))
                            """)
                    .param("canonicalName", canonicalName).param("canonicalId", canonicalId)
                    .param("collection", collection).param("tag", tag)
                    .param("aliasNames", aliasNames.isEmpty() ? List.of("") : aliasNames)
                    .param("aliasIds", aliasIds).update();

                int repointedObjects = jdbcClient
                    .sql(
                        """
                            UPDATE relationships
                            SET object = :canonicalName,
                                object_id = :canonicalId
                            FROM collections c
                            WHERE relationships.collection_id = c.id
                              AND c.name = :collection
                              AND (:tag = ANY(relationships.tags) OR CAST(:tag AS text) IS NULL)
                              -- Repointing also matches on NAME text, which is not scope-aware:
                              -- without this, merging a case-variant pair in one version's scope
                              -- would drag the OTHER version's identically-named edges onto this
                              -- version's canonical entity.
                              AND kg_canonical_tags(relationships.tags)
                                  = kg_canonical_tags((SELECT tags FROM entities WHERE id = :canonicalId))
                              AND (relationships.object IN (:aliasNames) OR relationships.object_id IN (:aliasIds))
                            """)
                    .param("canonicalName", canonicalName).param("canonicalId", canonicalId)
                    .param("collection", collection).param("tag", tag)
                    .param("aliasNames", aliasNames.isEmpty() ? List.of("") : aliasNames)
                    .param("aliasIds", aliasIds).update();

                relationshipsRepointCount += (repointedSubjects + repointedObjects);

                // Merge descriptions: longest non-empty description
                Optional<String> mergedDescription = jdbcClient.sql("""
                    SELECT description FROM entities
                    WHERE id IN (:allIds) AND description IS NOT NULL AND description <> ''
                    ORDER BY LENGTH(description) DESC
                    LIMIT 1
                    """).param("allIds", allIds).query(String.class).optional();

                if (mergedDescription.isPresent()) {
                    jdbcClient.sql(
                        "UPDATE entities SET description = :description WHERE id = :canonicalId")
                        .param("description", mergedDescription.get())
                        .param("canonicalId", canonicalId).update();
                }

                // Delete alias entities
                int deleted = jdbcClient.sql("DELETE FROM entities WHERE id IN (:aliasIds)")
                    .param("aliasIds", aliasIds).update();

                rowsDeleted += deleted;
            }

            // 2. Collapse duplicate relationship triples — within one tag scope only. The partition
            // is over the NAME text, so without kg_canonical_tags(r.tags) the same edge under two
            // versions collapses to one and the second version loses it.
            int relationshipsCollapsed = jdbcClient.sql("""
                WITH ranked AS (
                  SELECT r.id,
                    ROW_NUMBER() OVER (
                      PARTITION BY LOWER(r.subject), LOWER(r.predicate), LOWER(r.object),
                                   kg_canonical_tags(r.tags)
                      ORDER BY LENGTH(COALESCE(r.description, '')) DESC, r.id
                    ) AS rn
                  FROM relationships r
                  JOIN collections c ON r.collection_id = c.id
                  WHERE c.name = :collection
                    AND (:tag = ANY(r.tags) OR CAST(:tag AS text) IS NULL)
                )
                DELETE FROM relationships
                WHERE id IN (SELECT id FROM ranked WHERE rn > 1)
                """).param("collection", collection).param("tag", tag).update();

            log.info(
                "KG consolidation complete. Groups: {}, Entities Deleted: {}, Relationships Repointed: {}, Relationships Collapsed: {}",
                groups.size(), rowsDeleted, relationshipsRepointCount, relationshipsCollapsed);

            return new ConsolidationStats(groups.size(), rowsDeleted, relationshipsRepointCount,
                relationshipsCollapsed);
        });
    }

    private static List<UUID> parseUuidArray(Object obj) {
        if (obj == null) {
            return List.of();
        }
        if (obj instanceof Array sqlArray) {
            try {
                Object arrayObj = sqlArray.getArray();
                if (arrayObj instanceof UUID[] arr) {
                    return List.of(arr);
                } else if (arrayObj instanceof Object[] arr) {
                    List<UUID> list = new ArrayList<>();
                    for (Object o : arr) {
                        if (o instanceof UUID u) {
                            list.add(u);
                        } else if (o != null) {
                            list.add(UUID.fromString(o.toString()));
                        }
                    }
                    return list;
                }
            } catch (SQLException e) {
                throw new RuntimeException("Failed to read UUID array", e);
            }
        }
        return List.of();
    }

    private static List<String> parseStringArray(Object obj) {
        if (obj == null) {
            return List.of();
        }
        if (obj instanceof Array sqlArray) {
            try {
                Object arrayObj = sqlArray.getArray();
                if (arrayObj instanceof String[] arr) {
                    return List.of(arr);
                } else if (arrayObj instanceof Object[] arr) {
                    List<String> list = new ArrayList<>();
                    for (Object o : arr) {
                        list.add(o != null ? o.toString() : null);
                    }
                    return list;
                }
            } catch (SQLException e) {
                throw new RuntimeException("Failed to read String array", e);
            }
        }
        return List.of();
    }
}
