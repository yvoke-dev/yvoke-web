package de.palsoftware.yvoke.tag.core.repository;

import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TagRepository {

    private final JdbcClient jdbcClient;

    public TagRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void addTagToCollection(UUID collectionId, String tagName) {
        String cleaned = tagName.trim();
        if (cleaned.isEmpty()) {
            return;
        }

        // Append to array if not exists
        String sql = """
            UPDATE collections
            SET tags = array_append(tags, :tag)
            WHERE id = :id AND NOT (:tag = ANY(tags))
            """;
        jdbcClient.sql(sql).param("tag", cleaned).param("id", collectionId).update();
    }

    /**
     * Detaches the tag from the collection's own tag array only. The cross-domain content cascade
     * (documents / entities / relationships / json_objects) is owned by
     * {@code LifecycleService.removeTagFromCollection}, which applies tag-aware semantics and
     * auditing — a repository must not reach into other domains' tables (MNT-01).
     */
    public void removeTagFromCollection(UUID collectionId, String tagName) {
        String cleaned = tagName.trim();
        jdbcClient.sql("""
            UPDATE collections
            SET tags = array_remove(tags, :tag)
            WHERE id = :id
            """).param("tag", cleaned).param("id", collectionId).update();
    }

    public void addTagToConversation(UUID conversationId, String tagName) {
        String cleaned = tagName.trim();
        if (cleaned.isEmpty()) {
            return;
        }

        // Append to array if not exists
        String sql = """
            UPDATE conversations
            SET tags = array_append(tags, :tag)
            WHERE id = :id AND NOT (:tag = ANY(tags))
            """;
        jdbcClient.sql(sql).param("tag", cleaned).param("id", conversationId).update();
    }

    public void removeTagFromConversation(UUID conversationId, String tagName) {
        String cleaned = tagName.trim();
        String sql = """
            UPDATE conversations
            SET tags = array_remove(tags, :tag)
            WHERE id = :id
            """;
        jdbcClient.sql(sql).param("tag", cleaned).param("id", conversationId).update();
    }


    /**
     * Tags one document, unless the resulting tag set is already taken by a SIBLING document for
     * the same {@code (collection, kind, source_file)}.
     *
     * <p>
     * tags is part of {@code ux_documents_collection_kind_source_file_tags} (V3), so tagging
     * rewrites part of the row's identity: an untagged document and a {@code {10.0}} document for
     * one source file (two versions of one file in one collection — the documented install-kit
     * shape) collide the moment the first is tagged {@code 10.0}. That surfaced as a raw 23505,
     * i.e. an HTTP 500 on an admin form post; the operator is told what blocks it instead.
     */
    public void addTagToDocument(UUID documentId, String tagName) {
        String cleaned = tagName.trim();
        if (cleaned.isEmpty()) {
            return;
        }

        // Append to array if not exists, and only if no sibling already holds the resulting scope.
        String sql = """
            UPDATE documents d
            SET tags = array_append(d.tags, :tag),
                updated_at = CURRENT_TIMESTAMP
            WHERE d.id = :id
              AND NOT (:tag = ANY(d.tags))
              AND NOT EXISTS (
                  SELECT 1 FROM documents s
                  WHERE s.collection_id = d.collection_id
                    AND s.kind = d.kind
                    AND s.metadata->>'source_file' = d.metadata->>'source_file'
                    AND s.id <> d.id
                    AND kg_canonical_tags(s.tags)
                          = kg_canonical_tags(array_append(d.tags, :tag)))
            """;
        int updated = jdbcClient.sql(sql).param("tag", cleaned).param("id", documentId).update();
        if (updated == 0) {
            String diagnostic = """
                SELECT d.metadata->>'source_file'
                FROM documents d
                JOIN documents s
                  ON s.collection_id = d.collection_id
                 AND s.kind = d.kind
                 AND s.metadata->>'source_file' = d.metadata->>'source_file'
                 AND s.id <> d.id
                 AND kg_canonical_tags(s.tags)
                     = kg_canonical_tags(array_append(d.tags, :tag))
                WHERE d.id = :id
                LIMIT 1
                """;
            rejectIfSiblingBlocks(diagnostic, documentId, cleaned, "add");
        }
    }

    /** The mirror image: untagging rewrites the same identity and can collide the same way. */
    public void removeTagFromDocument(UUID documentId, String tagName) {
        String cleaned = tagName.trim();
        String sql = """
            UPDATE documents d
            SET tags = array_remove(d.tags, :tag),
                updated_at = CURRENT_TIMESTAMP
            WHERE d.id = :id
              AND NOT EXISTS (
                  SELECT 1 FROM documents s
                  WHERE s.collection_id = d.collection_id
                    AND s.kind = d.kind
                    AND s.metadata->>'source_file' = d.metadata->>'source_file'
                    AND s.id <> d.id
                    AND kg_canonical_tags(s.tags)
                          = kg_canonical_tags(array_remove(d.tags, :tag)))
            """;
        int updated = jdbcClient.sql(sql).param("tag", cleaned).param("id", documentId).update();
        if (updated == 0) {
            String diagnostic = """
                SELECT d.metadata->>'source_file'
                FROM documents d
                JOIN documents s
                  ON s.collection_id = d.collection_id
                 AND s.kind = d.kind
                 AND s.metadata->>'source_file' = d.metadata->>'source_file'
                 AND s.id <> d.id
                 AND kg_canonical_tags(s.tags)
                     = kg_canonical_tags(array_remove(d.tags, :tag))
                WHERE d.id = :id
                LIMIT 1
                """;
            rejectIfSiblingBlocks(diagnostic, documentId, cleaned, "remove");
        }
    }

    // NOTE: there is deliberately no findAllTagNames() here. A `tags` registry table used to answer
    // that, populated by a getOrCreateTag call inside each add* method above — so it recorded a tag
    // exactly when one arrived through an admin form or the ingest enqueue, and never learned about
    // the writers that set the TEXT[] columns directly (the corpus import above all). It held 2
    // names while the corpus carried 4. The vocabulary is now derived from the arrays themselves:
    // CollectionService.listAllTags() for corpus tags, ChatConversationService for chat folders.

    /**
     * Turns "the guarded UPDATE changed nothing" into either silence (an ordinary no-op: the tag
     * was already there / already absent, or the row is gone) or an actionable rejection naming the
     * source file whose sibling blocks the change. {@code diagnosticSql} is one of this class's two
     * fixed literals — nothing caller-derived is ever concatenated into it.
     */
    private void rejectIfSiblingBlocks(String diagnosticSql, UUID documentId, String tag,
        String operation) {
        Optional<String> blockedSourceFile = jdbcClient.sql(diagnosticSql).param("tag", tag)
            .param("id", documentId).query(String.class).optional();
        if (blockedSourceFile.isPresent()) {
            throw new IllegalArgumentException("Cannot " + operation + " tag '" + tag
                + "': another document in this collection already covers source file '"
                + blockedSourceFile.get() + "' with exactly the tags this change would produce."
                + " Merge or delete that duplicate first.");
        }
    }
}
