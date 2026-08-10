package de.palsoftware.yvoke.lifecycle.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;

import de.palsoftware.yvoke.collection.core.repository.CollectionRepository;
import de.palsoftware.yvoke.document.core.repository.DocumentRepository;
import de.palsoftware.yvoke.document.core.model.DocumentRow;
import de.palsoftware.yvoke.kg.core.repository.KgWriteRepository;
import de.palsoftware.yvoke.jsonobject.core.service.JsonObjectService;
import de.palsoftware.yvoke.tag.core.repository.TagRepository;
import de.palsoftware.yvoke.shared.audit.repository.AuditLogRepository;
import de.palsoftware.yvoke.shared.user.service.UserService;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import de.palsoftware.yvoke.collection.core.model.Collection;
import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Transactional;

public class LifecycleServiceTest {

    private DocumentRepository documentRepository;
    private KgWriteRepository kgRepository;
    private CollectionRepository collectionRepository;
    private AuditLogRepository auditLogRepository;
    private UserService userService;
    private JsonObjectService jsonObjectService;
    private TagRepository tagRepository;
    private LifecycleService lifecycleService;

    @BeforeEach
    public void setUp() {
        documentRepository = mock(DocumentRepository.class);
        kgRepository = mock(KgWriteRepository.class);
        collectionRepository = mock(CollectionRepository.class);
        auditLogRepository = mock(AuditLogRepository.class);
        userService = mock(UserService.class);
        jsonObjectService = mock(JsonObjectService.class);
        tagRepository = mock(TagRepository.class);

        when(userService.getCurrentUser()).thenReturn(Optional.empty());

        lifecycleService =
            new LifecycleService(documentRepository, kgRepository, collectionRepository,
                jsonObjectService, tagRepository, auditLogRepository, userService);
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testDeleteDocument() {
        UUID docId = UUID.randomUUID();
        DocumentRow mockDoc = new DocumentRow(docId, UUID.randomUUID(), "OIM", "manual",
            "Test Document", Map.of("tag", "9.3", "source_file", "source.md"), "completed",
            Collections.emptyList(), Instant.now());
        when(documentRepository.findById(docId)).thenReturn(Optional.of(mockDoc));

        lifecycleService.deleteDocument(docId);

        verify(documentRepository).deleteById(docId);

        ArgumentCaptor<Map<String, Object>> detailsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditLogRepository).log(eq("anonymous_admin"), eq("DELETE_DOCUMENT"),
            eq(docId.toString()), detailsCaptor.capture());

        Map<String, Object> details = detailsCaptor.getValue();
        assertThat(details.get("title")).isEqualTo("Test Document");
        assertThat(details.get("collection")).isEqualTo("OIM");
        assertThat(details.get("tag")).isEqualTo("9.3");
    }

    /**
     * Three properties of the cascade that nothing currently pins. {@code testDeleteCollection}
     * uses plain {@code verify()} (no {@code InOrder}) and never stubs
     * {@code collectionRepository.findByName}, so the JSON branch is not even executed;
     * {@code testDeleteDocument} never asserts what a single-document delete must NOT touch; and no
     * test reads the annotations at all.
     *
     * <p>
     * Losing {@code @Transactional} leaves a half-destroyed collection — entities and documents
     * gone, the {@code collections} row and its {@code chunks} partition still there — together
     * with an audit row claiming a completed deletion, which is worse than the failure it hides.
     * Reordering it so the {@code collections} row goes first fires
     * {@code trg_collections_create_chunks_partition}'s drop side while content still references
     * the row. And adding a graph or JSON cascade to the SINGLE-document delete destroys entities
     * shared across the whole tag scope: the graph is keyed on
     * {@code (collection, kind, name, tags)}, not on the document, so deleting one page would take
     * out every other document's entities with it.
     */
    @Test
    public void aCollectionDeleteIsTransactionalAndCascadesInOrderWhileADocumentDeleteDoesNot() {
        UUID docId = UUID.randomUUID();
        DocumentRow doc = new DocumentRow(docId, UUID.randomUUID(), "OIM", "manual", "Shared Page",
            Map.of("tag", "9.3", "source_file", "source.md"), "completed", Collections.emptyList(),
            Instant.now());
        when(documentRepository.findById(docId)).thenReturn(Optional.of(doc));

        lifecycleService.deleteDocument(docId);

        // A document delete removes exactly one row; the graph, the JSON corpus and the tag
        // registry are shared by the rest of the collection and must be untouched.
        verifyNoInteractions(kgRepository, jsonObjectService, tagRepository);

        UUID collectionId = UUID.randomUUID();
        when(collectionRepository.findByName("OIM")).thenReturn(Optional.of(
            new Collection(collectionId, "OIM", "corpus", List.of("9.3"), OffsetDateTime.now())));
        when(documentRepository.deleteByCollection("OIM")).thenReturn(7);

        lifecycleService.deleteCollection("OIM");

        InOrder ordered = inOrder(documentRepository, kgRepository, jsonObjectService,
            collectionRepository, auditLogRepository);
        ordered.verify(documentRepository).deleteByCollection("OIM");
        ordered.verify(kgRepository).deleteCollectionGraph("OIM");
        ordered.verify(jsonObjectService).deleteObjectsByCollection(collectionId);
        ordered.verify(collectionRepository).delete("OIM");
        // The audit row is written INSIDE the transaction, i.e. after the last cascade step, so a
        // rollback takes the "deleted" record with it.
        ordered.verify(auditLogRepository).log(eq("anonymous_admin"), eq("DELETE_COLLECTION"),
            eq("OIM"), any());

        List<String> transactionalMethods = Arrays
            .stream(LifecycleService.class.getDeclaredMethods())
            .filter(m -> m.isAnnotationPresent(Transactional.class)).map(Method::getName).toList();
        assertThat(transactionalMethods).contains("deleteDocument", "deleteCollection",
            "removeTagFromCollection");
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testDeleteCollection() {
        when(documentRepository.deleteByCollection("OIM")).thenReturn(7);

        lifecycleService.deleteCollection("OIM");

        // Order: content first (documents, then KG), collection row last.
        verify(documentRepository).deleteByCollection("OIM");
        verify(kgRepository).deleteCollectionGraph("OIM");
        // collection is mocked, but the inner lambda isn't executed easily if we don't return it.
        // wait, we need to mock findByName to return a collection so jsonObjectService is called
        // Actually the test doesn't check jsonObjectService right now
        verify(collectionRepository).delete("OIM");

        ArgumentCaptor<Map<String, Object>> detailsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditLogRepository).log(eq("anonymous_admin"), eq("DELETE_COLLECTION"), eq("OIM"),
            detailsCaptor.capture());

        Map<String, Object> details = detailsCaptor.getValue();
        assertThat(details.get("collection")).isEqualTo("OIM");
        assertThat(details.get("documents_deleted")).isEqualTo(7);
    }
}
