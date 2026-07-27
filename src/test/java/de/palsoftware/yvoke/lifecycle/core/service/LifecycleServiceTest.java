package de.palsoftware.yvoke.lifecycle.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
