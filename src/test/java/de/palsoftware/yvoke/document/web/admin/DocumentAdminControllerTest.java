package de.palsoftware.yvoke.document.web.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

import de.palsoftware.yvoke.collection.core.model.Collection;
import de.palsoftware.yvoke.collection.core.service.CollectionService;
import de.palsoftware.yvoke.document.core.model.DocumentRow;
import de.palsoftware.yvoke.document.core.repository.DocumentRepository;
import de.palsoftware.yvoke.document.core.service.DocumentAdminViewService;
import de.palsoftware.yvoke.shared.audit.repository.AuditLogRepository;
import de.palsoftware.yvoke.shared.jobengine.model.EnqueueResult;
import de.palsoftware.yvoke.shared.jobengine.service.JobService;
import de.palsoftware.yvoke.shared.user.service.UserService;
import de.palsoftware.yvoke.tag.core.service.TagService;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;
import java.time.OffsetDateTime;

public class DocumentAdminControllerTest {

    private DocumentRepository documentRepository;
    private DocumentAdminViewService documentAdminViewService;
    private CollectionService collectionService;
    private TagService tagService;
    private JobService jobService;
    private AuditLogRepository auditLogRepository;
    private UserService userService;

    private DocumentAdminController controller;
    private Model model;

    @BeforeEach
    public void setUp() {
        documentRepository = mock(DocumentRepository.class);
        documentAdminViewService = mock(DocumentAdminViewService.class);
        collectionService = mock(CollectionService.class);
        tagService = mock(TagService.class);
        jobService = mock(JobService.class);
        auditLogRepository = mock(AuditLogRepository.class);
        userService = mock(UserService.class);

        when(userService.getCurrentUser()).thenReturn(Optional.empty());

        Collection col = new Collection(UUID.randomUUID(), "OIM", "manual", List.of("9.3"),
            OffsetDateTime.now());
        when(collectionService.listCollections()).thenReturn(List.of(col));
        when(collectionService.listAllTags()).thenReturn(Collections.emptyList());
        when(collectionService.listTagsOf(any())).thenReturn(Collections.emptyList());

        controller = new DocumentAdminController(documentRepository, documentAdminViewService,
            collectionService, tagService, jobService, auditLogRepository, userService);

        model = new ConcurrentModel();
    }

    @Test
    public void testListDocuments() {
        when(documentAdminViewService.listDocuments(any(), anyInt(), anyInt(), any(), any(), any(),
            any())).thenReturn(Collections.emptyList());
        when(documentAdminViewService.countDocuments(any(), any(), any(), any(), any()))
            .thenReturn(0L);
        when(documentAdminViewService.distinctKinds()).thenReturn(List.of("manual"));

        String view = controller.listDocuments(null, null, null, null, null, 0, 20, model);

        assertThat(view).isEqualTo("admin/documents");
        assertThat(model.getAttribute("documents")).isNotNull();
        assertThat(model.getAttribute("collections")).isEqualTo(List.of("OIM"));
    }

    @Test
    public void testProcessKgEnqueuesAndRedirectsToTheNewJob() {
        UUID docId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        when(documentRepository.findById(docId)).thenReturn(Optional.of(document(docId)));
        when(jobService.enqueue(any())).thenReturn(EnqueueResult.created(jobId));
        RedirectAttributes redirect = new RedirectAttributesModelMap();

        String view = controller.processDocumentKg(docId, null, null, null, redirect);

        assertThat(view).isEqualTo("redirect:/admin/jobs/" + jobId);
        assertThat(redirect.getFlashAttributes().get("success").toString()).contains("enqueued");
    }

    /**
     * Clicking "Process KG" twice must not 500 on the admission-control index: the second click
     * lands on the job already in flight, and says so.
     *
     * <p>
     * It says so on the WARNING channel, not the success one. The admission key is
     * {@code (kind, source_ref, collection_id, tags)} and does NOT include {@code settings}, so a
     * resubmission of the same document into the same target adopts the in-flight job and runs
     * under THAT job's prompt — including a job the REST path enqueued with no settings at all. The
     * prompt the operator just picked is silently dropped, so the message has to name the
     * consequence and the remedy.
     */
    @Test
    public void testProcessKgOnADocumentAlreadyQueuedWarnsThatTheSettingsWereNotApplied() {
        UUID docId = UUID.randomUUID();
        UUID activeJobId = UUID.randomUUID();
        when(documentRepository.findById(docId)).thenReturn(Optional.of(document(docId)));
        when(jobService.enqueue(any())).thenReturn(EnqueueResult.adopted(activeJobId));
        RedirectAttributes redirect = new RedirectAttributesModelMap();

        String view = controller.processDocumentKg(docId, null, null, "kg-prompt-v2", redirect);

        assertThat(view).isEqualTo("redirect:/admin/jobs/" + activeJobId);
        assertThat(redirect.getFlashAttributes()).doesNotContainKey("success");
        assertThat(redirect.getFlashAttributes().get("warning").toString())
            .contains("already queued or running").contains("settings it was enqueued with")
            .contains("cancel");
    }

    // ---------------------------------------------------------------------
    // Tag dropdown. It used to read a `tags` registry table that only the admin forms and the
    // ingest enqueue ever wrote to, so tags that arrived any other way — above all the corpus
    // import, which sets collections.tags directly — were simply absent from the filter. The
    // options are now DERIVED from collections.tags, scoped to the collection being browsed.
    // ---------------------------------------------------------------------

    @Test
    public void listDocumentsOffersEveryDeclaredTagWhenNoCollectionIsSelected() {
        stubEmptyDocumentList();
        when(collectionService.listAllTags()).thenReturn(List.of("10.0", "9.3.1", "content"));

        controller.listDocuments(null, null, null, null, null, 0, 20, model);

        assertThat(model.getAttribute("allTags")).isEqualTo(List.of("10.0", "9.3.1", "content"));
        verify(collectionService, never()).listTagsOf(any());
    }

    @Test
    public void listDocumentsScopesTheTagOptionsToTheSelectedCollection() {
        stubEmptyDocumentList();
        when(collectionService.listTagsOf("OIM")).thenReturn(List.of("9.3.1", "10.0"));

        controller.listDocuments("OIM", null, null, null, null, 0, 20, model);

        assertThat(model.getAttribute("allTags")).isEqualTo(List.of("9.3.1", "10.0"));
        verify(collectionService, never()).listAllTags();
    }

    /**
     * The active filter must always be among the options.
     *
     * <p>
     * The template marks an option selected with {@code th:selected="${t == selectedTag}"}; if the
     * scoped list does not contain the tag currently being filtered on — switch collection while a
     * tag is applied, or filter on a tag a collection carries on its documents but never declared —
     * the {@code <select>} falls back to displaying "All Tags" while the query is still filtered,
     * which reads as "the filter was cleared" and hides rows with no visible cause.
     */
    @Test
    public void listDocumentsKeepsTheActiveTagAmongTheOptionsEvenWhenTheCollectionDoesNotDeclareIt() {
        stubEmptyDocumentList();
        when(collectionService.listTagsOf("OIM")).thenReturn(List.of("9.3.1"));

        controller.listDocuments("OIM", null, "10.0", null, null, 0, 20, model);

        assertThat(model.getAttribute("allTags")).isEqualTo(List.of("9.3.1", "10.0"));
        assertThat(model.getAttribute("selectedTag")).isEqualTo("10.0");
    }

    private void stubEmptyDocumentList() {
        when(documentAdminViewService.listDocuments(any(), anyInt(), anyInt(), any(), any(), any(),
            any())).thenReturn(Collections.emptyList());
        when(documentAdminViewService.countDocuments(any(), any(), any(), any(), any()))
            .thenReturn(0L);
        when(documentAdminViewService.distinctKinds()).thenReturn(List.of("manual"));
    }

    private static DocumentRow document(UUID id) {
        return new DocumentRow(id, UUID.randomUUID(), "OIM", "manual", "Title", null, "completed",
            List.of("9.3"), Instant.now());
    }
}
