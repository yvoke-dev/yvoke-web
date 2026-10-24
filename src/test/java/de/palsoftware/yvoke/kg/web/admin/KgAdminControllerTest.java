package de.palsoftware.yvoke.kg.web.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.palsoftware.yvoke.collection.core.model.Collection;
import de.palsoftware.yvoke.collection.core.service.CollectionService;
import de.palsoftware.yvoke.kg.core.model.KgAdminViews.KgScopeView;
import de.palsoftware.yvoke.kg.core.service.KgAdminViewService;
import de.palsoftware.yvoke.kg.core.service.KgConsolidator;
import de.palsoftware.yvoke.kg.core.repository.KgGraphReadRepository;
import de.palsoftware.yvoke.kg.core.repository.KgWriteRepository;
import de.palsoftware.yvoke.shared.audit.repository.AuditLogRepository;
import de.palsoftware.yvoke.shared.user.service.UserService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

public class KgAdminControllerTest {

    private KgAdminViewService kgAdminViewService;
    private KgGraphReadRepository kgReadRepository;
    private KgWriteRepository kgWriteRepository;
    private KgConsolidator kgConsolidator;
    private AuditLogRepository auditLogRepository;
    private UserService userService;
    private CollectionService collectionService;

    private KgAdminController controller;
    private RedirectAttributes redirectAttributes;

    @BeforeEach
    public void setUp() {
        kgAdminViewService = mock(KgAdminViewService.class);
        kgReadRepository = mock(KgGraphReadRepository.class);
        kgWriteRepository = mock(KgWriteRepository.class);
        kgConsolidator = mock(KgConsolidator.class);
        auditLogRepository = mock(AuditLogRepository.class);
        userService = mock(UserService.class);
        collectionService = mock(CollectionService.class);

        SecurityContextHolder.getContext()
            .setAuthentication(new TestingAuthenticationToken("admin", "pass", "ROLE_ADMIN"));

        when(userService.getCurrentUser()).thenReturn(Optional.empty());
        when(collectionService.getCollection("OIM")).thenReturn(Optional.of(collection("OIM")));

        controller = new KgAdminController(kgAdminViewService, kgReadRepository, kgWriteRepository,
            kgConsolidator, auditLogRepository, userService, collectionService);
        redirectAttributes = mock(RedirectAttributes.class);
    }

    @AfterEach
    public void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /**
     * {@code POST /admin/kg/clear} is a destructive, irreversible bulk delete. A missing or blank
     * tag must be REFUSED, not treated as "all tags": one collection holds several product versions
     * separated only by tag, so a tag-blind clear would wipe every version's graph from a form the
     * operator believed was scoped to one. It must flash an error, delete nothing, and write no
     * audit entry for a deletion that never happened.
     */
    @Test
    public void clearWithoutAnExplicitTagIsRefusedAndDeletesNothing() {
        for (String noTag : new String[] {null, "", "   "}) {
            String view = controller.clearGraph("OIM", noTag, redirectAttributes);

            assertThat(view).isEqualTo("redirect:/admin/kg");
            verify(redirectAttributes, atLeastOnce()).addFlashAttribute(eq("error"), anyString());
        }
        verify(kgWriteRepository, never()).deleteTagGraph(anyString(), anyString());
        verify(auditLogRepository, never()).log(anyString(), eq("CLEAR_KG"), anyString(), anyMap());
    }

    @Test
    public void testConsolidateKg() {
        var mockStats = new KgConsolidator.ConsolidationStats(1, 2, 3, 4);
        when(collectionService.getCollection("OIM")).thenReturn(Optional.of(collection("OIM")));
        when(kgConsolidator.consolidate("OIM", "9.3")).thenReturn(mockStats);

        String view = controller.consolidateKg("OIM", "9.3", redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/kg");
        verify(kgConsolidator).consolidate("OIM", "9.3");
        verify(auditLogRepository).log(eq("anonymous_admin"), eq("CONSOLIDATE_KG"), eq("OIM 9.3"),
            anyMap());
    }

    /**
     * {@code KgConsolidator} matches {@code WHERE c.name = :collection} — case-SENSITIVE, no trim —
     * at four SQL sites, and this endpoint used to hand it the raw request parameter. A mismatch
     * therefore matched zero rows and still flashed "Consolidation done for oim / 9.3! Groups: 0",
     * i.e. a success message for work that never ran. Same family as the SearchCorpusTool /
     * ListDocumentsTool casing bugs: accept the caller's spelling, then query with the STORED one.
     */
    @Test
    public void aCollectionNameInTheWrongCaseIsResolvedToItsStoredSpelling() {
        // Case-only, deliberately: CollectionService.getCollection is LOWER(name) = LOWER(:name)
        // with NO
        // trim, so a padded value would legitimately resolve to nothing. Stubbing padding as
        // resolvable would assert behaviour production does not have.
        when(collectionService.getCollection("oim")).thenReturn(Optional.of(collection("OIM")));
        when(kgConsolidator.consolidate("OIM", "9.3"))
            .thenReturn(new KgConsolidator.ConsolidationStats(1, 2, 3, 4));

        controller.consolidateKg("oim", "9.3", redirectAttributes);

        verify(kgConsolidator).consolidate("OIM", "9.3");
        verify(kgConsolidator, never()).consolidate(eq("oim"), anyString());
    }

    /**
     * The complement: an unknown collection must FAIL LOUDLY. Reporting "Groups: 0" for a typo is
     * indistinguishable from "there was nothing to consolidate", which is a legitimate outcome — so
     * the operator has no way to tell a no-op from a success. Nothing may be audited either: no
     * consolidation happened.
     */
    @Test
    public void anUnknownCollectionIsRefusedRatherThanReportedAsADoneConsolidation() {
        when(collectionService.getCollection(anyString())).thenReturn(Optional.empty());

        String view = controller.consolidateKg("NoSuchCollection", "9.3", redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/kg");
        verify(redirectAttributes).addFlashAttribute(eq("error"), anyString());
        verify(redirectAttributes, never()).addFlashAttribute(eq("success"), anyString());
        verify(kgConsolidator, never()).consolidate(anyString(), anyString());
        verify(auditLogRepository, never()).log(anyString(), eq("CONSOLIDATE_KG"), anyString(),
            anyMap());
    }

    @Test
    public void kgOverview_areaAdmin_filtersScopesToAdministeredCollections() {
        SecurityContextHolder.getContext().setAuthentication(
            new TestingAuthenticationToken("user", "pass", "ROLE_AREA_ADMIN", "AREA_ADMIN_OIM"));
        Collection oimCol = new Collection(UUID.randomUUID(), "OIM", "desc", List.of("9.3"),
            OffsetDateTime.now(), "OIM");
        when(collectionService.listForAdminUser()).thenReturn(List.of(oimCol));
        when(kgAdminViewService.listScopes()).thenReturn(List
            .of(new KgScopeView("OIM", "9.3", 10, 20), new KgScopeView("Finance", "1.0", 50, 60)));

        Model model = new ConcurrentModel();
        String view = controller.kgOverview(model);

        assertThat(view).isEqualTo("admin/kg");
        @SuppressWarnings("unchecked")
        List<KgScopeView> scopes = (List<KgScopeView>) model.getAttribute("scopes");
        assertThat(scopes).hasSize(1);
        assertThat(scopes.get(0).collection()).isEqualTo("OIM");
    }

    @Test
    public void viewKg_unauthorizedArea_throws404() {
        SecurityContextHolder.getContext().setAuthentication(
            new TestingAuthenticationToken("user", "pass", "ROLE_AREA_ADMIN", "AREA_ADMIN_OIM"));
        Collection finCol = new Collection(UUID.randomUUID(), "Finance", "desc", List.of("1.0"),
            OffsetDateTime.now(), "Finance");
        when(collectionService.getCollection("Finance")).thenReturn(Optional.of(finCol));

        Model model = new ConcurrentModel();
        assertThrows(ResponseStatusException.class,
            () -> controller.viewKg("Finance", null, null, null, null, 2, 0, model));
    }

    @Test
    public void clearGraph_unauthorizedArea_throws404() {
        SecurityContextHolder.getContext().setAuthentication(
            new TestingAuthenticationToken("user", "pass", "ROLE_AREA_ADMIN", "AREA_ADMIN_OIM"));
        Collection finCol = new Collection(UUID.randomUUID(), "Finance", "desc", List.of("1.0"),
            OffsetDateTime.now(), "Finance");
        when(collectionService.getCollection("Finance")).thenReturn(Optional.of(finCol));

        assertThrows(ResponseStatusException.class,
            () -> controller.clearGraph("Finance", "1.0", redirectAttributes));
    }

    @Test
    public void consolidateKg_unauthorizedArea_refused() {
        SecurityContextHolder.getContext().setAuthentication(
            new TestingAuthenticationToken("user", "pass", "ROLE_AREA_ADMIN", "AREA_ADMIN_OIM"));
        Collection finCol = new Collection(UUID.randomUUID(), "Finance", "desc", List.of("1.0"),
            OffsetDateTime.now(), "Finance");
        when(collectionService.getCollection("Finance")).thenReturn(Optional.of(finCol));

        String view = controller.consolidateKg("Finance", "1.0", redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/kg");
        verify(redirectAttributes).addFlashAttribute(eq("error"), anyString());
        verify(kgConsolidator, never()).consolidate(anyString(), anyString());
    }

    private static Collection collection(String storedName) {
        return new Collection(UUID.randomUUID(), storedName, "desc", List.of("9.3"),
            OffsetDateTime.now(), "OIM");
    }
}
