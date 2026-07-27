package de.palsoftware.yvoke.lifecycle.web.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import de.palsoftware.yvoke.lifecycle.core.service.LifecycleService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

public class LifecycleAdminControllerTest {

    private LifecycleService lifecycleService;
    private LifecycleAdminController controller;
    private RedirectAttributes redirectAttributes;

    @BeforeEach
    public void setUp() {
        lifecycleService = mock(LifecycleService.class);
        controller = new LifecycleAdminController(lifecycleService);
        redirectAttributes = mock(RedirectAttributes.class);
    }

    @Test
    public void testDeleteCollectionCrud() {
        String view = controller.deleteCollectionCrud("OIM", redirectAttributes);
        assertThat(view).isEqualTo("redirect:/admin/collections");
        verify(lifecycleService).deleteCollection("OIM");
    }

    @Test
    public void testDeleteCollection() {
        String view = controller.deleteCollection("OIM", redirectAttributes);
        assertThat(view).isEqualTo("redirect:/admin/documents");
        verify(lifecycleService).deleteCollection("OIM");
    }

    @Test
    public void testDeleteDocument() {
        UUID id = UUID.randomUUID();
        String view = controller.deleteDocument(id, redirectAttributes);
        assertThat(view).isEqualTo("redirect:/admin/documents");
        verify(lifecycleService).deleteDocument(id);
    }
}
