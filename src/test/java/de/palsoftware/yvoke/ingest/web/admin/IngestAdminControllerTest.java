package de.palsoftware.yvoke.ingest.web.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.mockito.ArgumentCaptor;

import de.palsoftware.yvoke.collection.core.service.CollectionService;
import de.palsoftware.yvoke.ingest.core.model.IngestJobKind;
import de.palsoftware.yvoke.rag.prompt.SystemPromptService;
import de.palsoftware.yvoke.shared.audit.repository.AuditLogRepository;
import de.palsoftware.yvoke.shared.jobengine.model.EnqueueRequest;
import de.palsoftware.yvoke.shared.jobengine.model.EnqueueResult;
import de.palsoftware.yvoke.shared.jobengine.service.JobService;
import de.palsoftware.yvoke.shared.user.service.UserService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

public class IngestAdminControllerTest {

    @TempDir
    Path tempDir;

    private CollectionService collectionService;
    private SystemPromptService systemPromptService;
    private JobService jobService;
    private AuditLogRepository auditLogRepository;
    private UserService userService;

    private IngestAdminController controller;
    private Model model;
    private RedirectAttributes redirectAttributes;

    @BeforeEach
    public void setUp() {
        collectionService = mock(CollectionService.class);
        systemPromptService = mock(SystemPromptService.class);
        jobService = mock(JobService.class);
        auditLogRepository = mock(AuditLogRepository.class);
        userService = mock(UserService.class);

        when(userService.getCurrentUser()).thenReturn(Optional.empty());

        controller = new IngestAdminController(collectionService, systemPromptService, jobService,
            auditLogRepository, userService, tempDir.toString());

        model = new ConcurrentModel();
        redirectAttributes = mock(RedirectAttributes.class);
    }

    @Test
    public void testIngestView() {
        when(collectionService.listCollections()).thenReturn(Collections.emptyList());
        String view = controller.ingestView(model, null);
        assertThat(view).isEqualTo("admin/ingest");
        assertThat(model.getAttribute("collections")).isNotNull();
    }

    @Test
    public void testUploadIngestEmptyFile() throws IOException {
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(true);

        String view = controller.uploadIngest(file, "OIM", "9.3", IngestJobKind.STANDARD.getValue(),
            null, null, null, null, null, true, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/ingest");
        verify(redirectAttributes).addFlashAttribute(eq("error"), anyString());
    }

    @Test
    public void testUploadIngestSuccess() throws IOException {
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getOriginalFilename()).thenReturn("manual.md");
        when(file.getInputStream())
            .thenReturn(new ByteArrayInputStream("Markdown content".getBytes()));

        UUID jobId = UUID.randomUUID();
        when(jobService.enqueue(any(EnqueueRequest.class)))
            .thenReturn(EnqueueResult.created(jobId));

        String view = controller.uploadIngest(file, "OIM", "9.3", IngestJobKind.STANDARD.getValue(),
            null, null, null, null, null, true, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/jobs/" + jobId);
        verify(jobService).enqueue(any(EnqueueRequest.class));
        verify(auditLogRepository).log(eq("anonymous_admin"), eq("INGEST_DATA"),
            eq(jobId.toString()), anyMap());
    }

    @Test
    public void testUploadIngestCustomWithGraphDisabled() throws IOException {
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getOriginalFilename()).thenReturn("export.zip");
        when(file.getInputStream()).thenReturn(new ByteArrayInputStream("zip bytes".getBytes()));

        UUID jobId = UUID.randomUUID();
        ArgumentCaptor<EnqueueRequest> requestCaptor =
            ArgumentCaptor.forClass(EnqueueRequest.class);
        when(jobService.enqueue(requestCaptor.capture())).thenReturn(EnqueueResult.created(jobId));

        String view = controller.uploadIngest(file, "OIM", "9.3", IngestJobKind.CUSTOM.getValue(),
            null, null, null, null, null, false, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/jobs/" + jobId);
        EnqueueRequest captured = requestCaptor.getValue();
        assertThat(captured.settings()).containsEntry("enableGraph", false);
    }
}
