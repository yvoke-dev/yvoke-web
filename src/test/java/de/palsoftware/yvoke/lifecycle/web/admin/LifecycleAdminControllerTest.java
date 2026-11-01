package de.palsoftware.yvoke.lifecycle.web.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

import de.palsoftware.yvoke.collection.core.model.Collection;
import de.palsoftware.yvoke.collection.core.service.CollectionService;
import de.palsoftware.yvoke.document.core.model.DocumentKind;
import de.palsoftware.yvoke.document.core.model.DocumentRow;
import de.palsoftware.yvoke.document.core.repository.DocumentRepository;
import de.palsoftware.yvoke.lifecycle.core.service.LifecycleService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

public class LifecycleAdminControllerTest {

    private LifecycleService lifecycleService;
    private CollectionService collectionService;
    private DocumentRepository documentRepository;
    private LifecycleAdminController controller;
    private RedirectAttributes redirectAttributes;

    @BeforeEach
    public void setUp() {
        lifecycleService = mock(LifecycleService.class);
        collectionService = mock(CollectionService.class);
        documentRepository = mock(DocumentRepository.class);
        controller =
            new LifecycleAdminController(lifecycleService, collectionService, documentRepository);
        redirectAttributes = mock(RedirectAttributes.class);
    }

    @Test
    public void testDeleteCollectionCrud_authorized() {
        when(collectionService.findByNameForAdmin("OIM")).thenReturn(
            Optional.of(new Collection(UUID.randomUUID(), "OIM", null, List.of(), null, "OIM")));

        String view = controller.deleteCollectionCrud("OIM", redirectAttributes);
        assertThat(view).isEqualTo("redirect:/admin/collections");
        verify(lifecycleService).deleteCollection("OIM");
    }

    @Test
    public void testDeleteCollectionCrud_unauthorized_throws404() {
        when(collectionService.findByNameForAdmin("Finance")).thenReturn(Optional.empty());

        assertThrows(ResponseStatusException.class,
            () -> controller.deleteCollectionCrud("Finance", redirectAttributes));
        verify(lifecycleService, never()).deleteCollection(anyString());
    }

    @Test
    public void testDeleteCollection_authorized() {
        when(collectionService.findByNameForAdmin("OIM")).thenReturn(
            Optional.of(new Collection(UUID.randomUUID(), "OIM", null, List.of(), null, "OIM")));

        String view = controller.deleteCollection("OIM", redirectAttributes);
        assertThat(view).isEqualTo("redirect:/admin/documents");
        verify(lifecycleService).deleteCollection("OIM");
    }

    @Test
    public void testDeleteCollection_unauthorized_throws404() {
        when(collectionService.findByNameForAdmin("Finance")).thenReturn(Optional.empty());

        assertThrows(ResponseStatusException.class,
            () -> controller.deleteCollection("Finance", redirectAttributes));
        verify(lifecycleService, never()).deleteCollection(anyString());
    }

    @Test
    public void testDeleteCollectionById_authorized() {
        UUID id = UUID.randomUUID();
        when(collectionService.findByIdForAdmin(id))
            .thenReturn(Optional.of(new Collection(id, "OIM", null, List.of(), null, "OIM")));

        String view = controller.deleteCollectionById(id, redirectAttributes);
        assertThat(view).isEqualTo("redirect:/admin/collections");
        verify(lifecycleService).deleteCollection("OIM");
    }

    @Test
    public void testDeleteCollectionById_unauthorized_throws404() {
        UUID id = UUID.randomUUID();
        when(collectionService.findByIdForAdmin(id)).thenReturn(Optional.empty());

        assertThrows(ResponseStatusException.class,
            () -> controller.deleteCollectionById(id, redirectAttributes));
        verify(lifecycleService, never()).deleteCollection(anyString());
    }

    @Test
    public void testDeleteDocument_authorized() {
        UUID docId = UUID.randomUUID();
        UUID colId = UUID.randomUUID();
        DocumentRow doc = new DocumentRow(docId, colId, "OIM", "standard", "title", Map.of(),
            "completed", List.of(), Instant.now());
        when(documentRepository.findById(docId)).thenReturn(Optional.of(doc));
        when(collectionService.findByIdForAdmin(colId))
            .thenReturn(Optional.of(new Collection(colId, "OIM", null, List.of(), null, "OIM")));

        String view = controller.deleteDocument(docId, redirectAttributes);
        assertThat(view).isEqualTo("redirect:/admin/documents");
        verify(lifecycleService).deleteDocument(docId);
    }

    @Test
    public void testDeleteDocument_unauthorized_throws404() {
        UUID docId = UUID.randomUUID();
        UUID colId = UUID.randomUUID();
        DocumentRow doc = new DocumentRow(docId, colId, "OIM", "standard", "title", Map.of(),
            "completed", List.of(), Instant.now());
        when(documentRepository.findById(docId)).thenReturn(Optional.of(doc));
        when(collectionService.findByIdForAdmin(colId)).thenReturn(Optional.empty());

        assertThrows(ResponseStatusException.class,
            () -> controller.deleteDocument(docId, redirectAttributes));
        verify(lifecycleService, never()).deleteDocument(any());
    }

    @Test
    public void testRemoveCollectionTag_authorized() {
        UUID colId = UUID.randomUUID();
        when(collectionService.findByIdForAdmin(colId))
            .thenReturn(Optional.of(new Collection(colId, "OIM", null, List.of(), null, "OIM")));

        String view = controller.removeCollectionTag(colId, "v1", redirectAttributes);
        assertThat(view).isEqualTo("redirect:/admin/collections");
        verify(lifecycleService).removeTagFromCollection(colId, "v1");
    }

    @Test
    public void testRemoveCollectionTag_unauthorized_throws404() {
        UUID colId = UUID.randomUUID();
        when(collectionService.findByIdForAdmin(colId)).thenReturn(Optional.empty());

        assertThrows(ResponseStatusException.class,
            () -> controller.removeCollectionTag(colId, "v1", redirectAttributes));
        verify(lifecycleService, never()).removeTagFromCollection(any(), anyString());
    }
}
