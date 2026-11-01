package de.palsoftware.yvoke.ingest.web.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.palsoftware.yvoke.collection.core.model.Collection;
import de.palsoftware.yvoke.collection.core.service.CollectionService;
import de.palsoftware.yvoke.ingest.core.confluence.ConfluenceInstance;
import de.palsoftware.yvoke.ingest.core.confluence.ConfluenceInstanceService;
import de.palsoftware.yvoke.ingest.core.confluence.ConfluenceInstanceView;
import de.palsoftware.yvoke.ingest.core.confluence.TokenHealth;
import de.palsoftware.yvoke.rag.prompt.SystemPromptService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

class AdminConnectorControllerTest {

    private CollectionService collectionService;
    private ConfluenceInstanceService instanceService;
    private AdminConnectorController controller;
    private Model model;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext()
            .setAuthentication(new TestingAuthenticationToken("admin", "pass", "ROLE_ADMIN"));

        collectionService = mock(CollectionService.class);
        instanceService = mock(ConfluenceInstanceService.class);
        controller = new AdminConnectorController(collectionService, instanceService,
            mock(SystemPromptService.class));
        model = new ConcurrentModel();

        when(collectionService.listCollections()).thenReturn(List.of(
            new Collection(UUID.randomUUID(), "A - Archive", null, List.of("1.0", "2.0"),
                OffsetDateTime.now(), "OIM"),
            new Collection(UUID.randomUUID(), "OIM - Docs", null, List.of("9.3.1", "10.0"),
                OffsetDateTime.now(), "OIM")));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void getConnectors_filtersByAdministeredArea() {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
            "areaAdmin", "pass", "ROLE_AREA_ADMIN", "AREA_ADMIN_OIM"));

        Collection oimCol = new Collection(UUID.randomUUID(), "OIM - Docs", null, List.of("9.3.1"),
            OffsetDateTime.now(), "OIM");
        Collection finCol = new Collection(UUID.randomUUID(), "Finance - Docs", null,
            List.of("1.0"), OffsetDateTime.now(), "Finance");
        when(collectionService.listCollections()).thenReturn(List.of(oimCol, finCol));

        ConfluenceInstanceView oimView = new ConfluenceInstanceView(UUID.randomUUID(), "OIM Wiki",
            "oim-wiki", "https://acme.atlassian.net/wiki", "svc@example.com", "DOCS", "12345",
            "public", null, "OIM - Docs", "9.3.1", true, true, TokenHealth.OK);
        ConfluenceInstanceView finView = new ConfluenceInstanceView(UUID.randomUUID(), "Fin Wiki",
            "fin-wiki", "https://acme.atlassian.net/wiki", "svc@example.com", "FIN", "54321",
            "public", null, "Finance - Docs", "1.0", true, true, TokenHealth.OK);
        when(instanceService.listInstances()).thenReturn(List.of(oimView, finView));

        controller.getConnectors(model);

        @SuppressWarnings("unchecked")
        List<ConfluenceInstanceView> instances =
            (List<ConfluenceInstanceView>) model.getAttribute("instances");
        assertThat(instances).containsExactly(oimView);
        @SuppressWarnings("unchecked")
        List<Collection> collections = (List<Collection>) model.getAttribute("collections");
        assertThat(collections).containsExactly(oimCol);
    }

    private static ConfluenceInstanceView view(String name, String slug, TokenHealth health) {
        return new ConfluenceInstanceView(UUID.randomUUID(), name, slug,
            "https://acme.atlassian.net/wiki", "svc@example.com", "DOCS", "12345", "public",
            "draft", "OIM - Docs", "10.0", true, true, health);
    }

    @Test
    void everyInstanceIsListedWithItsTokenHealth() {
        when(instanceService.listInstances())
            .thenReturn(List.of(view("iCC Wiki", "icc-wiki", TokenHealth.OK),
                view("Acme", "acme", TokenHealth.UNDECRYPTABLE)));

        assertThat(controller.getConnectors(model)).isEqualTo("admin/connectors");

        assertThat((List<?>) model.getAttribute("instances")).hasSize(2);
        assertThat((List<?>) model.getAttribute("collections")).hasSize(2);
    }

    @Test
    void withNoInstancesThePageStillRendersItsEmptyState() {
        when(instanceService.listInstances()).thenReturn(List.of());

        controller.getConnectors(model);

        assertThat((List<?>) model.getAttribute("instances")).isEmpty();
    }

    /**
     * The form opens in "create" mode, so it offers no tag until a collection is chosen and htmx
     * fetches that collection's tags. Seeding the dropdown server-side from an arbitrary collection
     * ({@code collections.get(0)}) is exactly how a saved tag used to be missing from its own
     * dropdown and silently dropped on the next save.
     */
    @Test
    void theTagDropdownStartsEmptyRatherThanShowingSomeOtherCollectionsTags() {
        when(instanceService.listInstances())
            .thenReturn(List.of(view("iCC Wiki", "icc-wiki", TokenHealth.OK)));

        controller.getConnectors(model);

        assertThat((List<?>) model.getAttribute("tags")).isEmpty();
        assertThat((List<?>) model.getAttribute("versions")).isEmpty();
    }

    /**
     * The instance record carries the token ciphertext and the key fingerprint, so it must never
     * reach the template: one careless expression away from rendering a credential into HTML.
     */
    @Test
    void noInstanceRecordAndNoCiphertextReachTheTemplate() {
        when(instanceService.listInstances())
            .thenReturn(List.of(view("iCC Wiki", "icc-wiki", TokenHealth.OK)));

        controller.getConnectors(model);

        assertThat(model.asMap().values()).noneMatch(v -> v instanceof ConfluenceInstance);
        assertThat(model.asMap().values()).noneMatch(v -> v instanceof List<?> list
            && list.stream().anyMatch(item -> item instanceof ConfluenceInstance));
        assertThat(model.asMap().values())
            .noneMatch(v -> v != null && String.valueOf(v).contains("enc:"));
    }
}
