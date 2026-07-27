package de.palsoftware.yvoke.ingest.web.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.palsoftware.yvoke.ingest.core.confluence.ConfluenceClientService;
import de.palsoftware.yvoke.ingest.core.confluence.ConfluenceInstance;
import de.palsoftware.yvoke.ingest.core.confluence.ConfluenceInstanceService;
import de.palsoftware.yvoke.ingest.core.confluence.ConfluenceInstanceView;
import de.palsoftware.yvoke.ingest.core.confluence.TokenHealth;
import de.palsoftware.yvoke.shared.audit.repository.AuditLogRepository;
import de.palsoftware.yvoke.shared.jobengine.model.EnqueueRequest;
import de.palsoftware.yvoke.shared.jobengine.model.EnqueueResult;
import de.palsoftware.yvoke.shared.jobengine.service.JobService;
import de.palsoftware.yvoke.shared.user.service.UserService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

class ConfluenceConnectorControllerTest {

    private static final UUID INSTANCE_ID = UUID.randomUUID();

    private ConfluenceClientService confluenceClientService;
    private ConfluenceInstanceService instanceService;
    private AuditLogRepository auditLogRepository;
    private JobService jobService;
    private UserService userService;
    private RedirectAttributes redirectAttributes;

    private ConfluenceConnectorController controller;

    @BeforeEach
    void setUp() {
        confluenceClientService = mock(ConfluenceClientService.class);
        instanceService = mock(ConfluenceInstanceService.class);
        auditLogRepository = mock(AuditLogRepository.class);
        jobService = mock(JobService.class);
        userService = mock(UserService.class);
        redirectAttributes = mock(RedirectAttributes.class);

        when(userService.getCurrentUser()).thenReturn(Optional.empty());
        when(instanceService.save(any(), any()))
            .thenAnswer(invocation -> invocation.getArgument(0, ConfluenceInstance.class));

        controller = new ConfluenceConnectorController(confluenceClientService, instanceService,
            auditLogRepository, jobService, userService);
    }

    private static ConfluenceInstanceForm form(UUID id) {
        return new ConfluenceInstanceForm(id, "iCC Wiki", "icc-wiki", "https://acme.atlassian.net",
            "svc@example.com", "DOCS", "12345", "public", "draft", "OIM - Docs", "10.0", false,
            true);
    }

    private static BindingResult noErrors(ConfluenceInstanceForm form) {
        return new BeanPropertyBindingResult(form, "instanceForm");
    }

    private static ConfluenceInstanceView view(boolean enabled) {
        return new ConfluenceInstanceView(INSTANCE_ID, "iCC Wiki", "icc-wiki",
            "https://acme.atlassian.net/wiki", "svc@example.com", "DOCS", "12345", "public", null,
            "OIM - Docs", "10.0", false, enabled, TokenHealth.OK);
    }

    private static ConfluenceInstance stored(String apiTokenEnc, String tokenKeyId) {
        return new ConfluenceInstance(INSTANCE_ID, "iCC Wiki", "icc-wiki",
            "https://acme.atlassian.net/wiki", "svc@example.com", apiTokenEnc, tokenKeyId, "DOCS",
            "12345", "public", "draft", "OIM - Docs", "10.0", false, true, null, null);
    }

    // ---------------------------------------------------------------------
    // Save
    // ---------------------------------------------------------------------

    @Test
    void savingPassesTheEditedFieldsAndTheSubmittedTokenToTheService() {
        ConfluenceInstanceForm form = form(INSTANCE_ID);

        assertThat(
            controller.saveInstance(form, noErrors(form), "brand-new-token", redirectAttributes))
            .isEqualTo("redirect:/admin/connectors");

        ArgumentCaptor<ConfluenceInstance> draft =
            ArgumentCaptor.forClass(ConfluenceInstance.class);
        verify(instanceService).save(draft.capture(), eq("brand-new-token"));
        assertThat(draft.getValue().id()).isEqualTo(INSTANCE_ID);
        assertThat(draft.getValue().slug()).isEqualTo("icc-wiki");
        // The credential is attached in the service; it can never travel on the form DTO.
        assertThat(draft.getValue().apiTokenEnc()).isNull();
        assertThat(draft.getValue().tokenKeyId()).isNull();
        verify(redirectAttributes).addFlashAttribute(eq("success"), contains("iCC Wiki"));
    }

    /** A blank token means "keep the stored one" and reaches the service unchanged. */
    @Test
    void aBlankTokenIsHandedOnAsIsSoTheServiceKeepsTheStoredCredential() {
        ConfluenceInstanceForm form = form(INSTANCE_ID);

        controller.saveInstance(form, noErrors(form), "   ", redirectAttributes);

        verify(instanceService).save(any(), eq("   "));
    }

    /**
     * Bean Validation failures are flashed and redirected, matching every other admin form on this
     * console — no field-level error rendering, and nothing is written.
     */
    @Test
    void aViolatedConstraintIsFlashedAndNothingIsSaved() {
        ConfluenceInstanceForm form = form(null);
        BindingResult binding = new BeanPropertyBindingResult(form, "instanceForm");
        binding.rejectValue("rootPageId", "Pattern", "The root page ID must be numeric.");

        assertThat(controller.saveInstance(form, binding, null, redirectAttributes))
            .isEqualTo("redirect:/admin/connectors");

        verify(redirectAttributes).addFlashAttribute(eq("error"), contains("must be numeric"));
        verify(instanceService, never()).save(any(), any());
        verify(auditLogRepository, never()).log(anyString(), anyString(), any(), any());
    }

    /**
     * A binding failure carries the {@code TypeMismatchException} message as its DEFAULT message,
     * so falling back to a friendly string only when the default is blank published framework
     * internals ("Failed to convert value of type 'null' to required type …", editor class names)
     * straight into the operator's flash. The error CODE decides, not the message.
     */
    @Test
    void aBindingFailureIsReportedWithoutLeakingFrameworkInternals() {
        ConfluenceInstanceForm form = form(null);
        BindingResult binding = new BeanPropertyBindingResult(form, "instanceForm");
        binding.addError(
            new FieldError("instanceForm", "id", "not-a-uuid", true, new String[] {"typeMismatch"},
                null, "Failed to convert value of type 'java.lang.String' to required type "
                    + "'java.util.UUID'; nested exception is IllegalArgumentException"));

        assertThat(controller.saveInstance(form, binding, null, redirectAttributes))
            .isEqualTo("redirect:/admin/connectors");

        ArgumentCaptor<String> flash = ArgumentCaptor.forClass(String.class);
        verify(redirectAttributes).addFlashAttribute(eq("error"), flash.capture());
        assertThat(flash.getValue()).contains("wrong format").doesNotContain("java.util.UUID")
            .doesNotContain("nested exception");
        verify(instanceService, never()).save(any(), any());
    }

    /**
     * The service rejects a domain the repository cannot canonicalize; the global MVC advice turns
     * that into the same error flash, so the controller must NOT swallow it here.
     */
    @Test
    void aRejectedDomainPropagatesInsteadOfBeingSwallowed() {
        ConfluenceInstanceForm form = form(INSTANCE_ID);
        when(instanceService.save(any(), any())).thenThrow(
            new IllegalArgumentException("Confluence base URL must use http(s): acme.example.com"));

        assertThatThrownBy(
            () -> controller.saveInstance(form, noErrors(form), null, redirectAttributes))
            .isInstanceOf(IllegalArgumentException.class);

        verify(auditLogRepository, never()).log(anyString(), anyString(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void theAuditTrailRecordsTheChangeButNeverTheToken() {
        ConfluenceInstanceForm form = form(INSTANCE_ID);

        controller.saveInstance(form, noErrors(form), "brand-new-token", redirectAttributes);

        ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
        verify(auditLogRepository).log(anyString(), eq("CONFIGURE_CONFLUENCE"), anyString(),
            detail.capture());
        assertThat(detail.getValue()).containsEntry("tokenUpdated", true).containsEntry("slug",
            "icc-wiki");
        assertThat(detail.getValue().values())
            .noneMatch(v -> String.valueOf(v).contains("brand-new-token")
                || String.valueOf(v).contains("enc:"));
    }

    // ---------------------------------------------------------------------
    // Delete
    // ---------------------------------------------------------------------

    /**
     * Deleting must not silently orphan the instance's queued work — the operator is told how many
     * jobs were cancelled with it.
     */
    @Test
    void deletingReportsTheQueuedJobsThatWereCancelledWithIt() {
        when(instanceService.delete(INSTANCE_ID))
            .thenReturn(new ConfluenceInstanceService.Deletion("iCC Wiki", "icc-wiki", 42));

        assertThat(controller.deleteInstance(INSTANCE_ID, redirectAttributes))
            .isEqualTo("redirect:/admin/connectors");

        verify(redirectAttributes).addFlashAttribute(eq("success"), contains("42 queued job(s)"));
        verify(auditLogRepository).log(anyString(), eq("DELETE_CONFLUENCE_INSTANCE"),
            eq("icc-wiki"), any());
    }

    @Test
    void deletingAnInstanceWithNoQueuedWorkJustConfirms() {
        when(instanceService.delete(INSTANCE_ID))
            .thenReturn(new ConfluenceInstanceService.Deletion("iCC Wiki", "icc-wiki", 0));

        controller.deleteInstance(INSTANCE_ID, redirectAttributes);

        verify(redirectAttributes).addFlashAttribute(eq("success"), contains("deleted"));
    }

    // ---------------------------------------------------------------------
    // Sync
    // ---------------------------------------------------------------------

    @Test
    void syncEnqueuesACrawlQualifiedWithTheInstanceSlug() {
        when(instanceService.findView(INSTANCE_ID)).thenReturn(Optional.of(view(true)));
        UUID jobId = UUID.randomUUID();
        when(jobService.enqueue(any())).thenReturn(EnqueueResult.created(jobId));

        String result = controller.syncConfluence(INSTANCE_ID, redirectAttributes);

        ArgumentCaptor<EnqueueRequest> captor = ArgumentCaptor.forClass(EnqueueRequest.class);
        verify(jobService).enqueue(captor.capture());
        EnqueueRequest request = captor.getValue();
        assertThat(request.kind()).isEqualTo("confluence-import:icc-wiki");
        assertThat(request.sourceRef()).isEqualTo("confluence/DOCS/12345");
        assertThat(request.collection()).isEqualTo("OIM - Docs");
        assertThat(request.tags()).containsExactly("10.0");
        assertThat(result).isEqualTo("redirect:/admin/jobs/" + jobId);
        verify(auditLogRepository).log(anyString(), eq("SYNC_CONFLUENCE_TRIGGER"), anyString(),
            any());
    }

    /**
     * The crawl must carry its instance id, not just the slug in its kind.
     *
     * <p>
     * The slug is editable on this same form and a crawl can sit queued for hours, so resolving by
     * slug at execution time lets a later rename point the crawl at whichever instance now holds
     * that slug — authenticating with the other site's credentials and fanning its pages into the
     * other site's collection. The id is what makes the job self-contained.
     */
    @Test
    void syncSnapshotsTheInstanceIdSoARenamedSlugCannotRetargetTheCrawl() {
        when(instanceService.findView(INSTANCE_ID)).thenReturn(Optional.of(view(true)));
        when(jobService.enqueue(any())).thenReturn(EnqueueResult.created(UUID.randomUUID()));

        controller.syncConfluence(INSTANCE_ID, redirectAttributes);

        ArgumentCaptor<EnqueueRequest> captor = ArgumentCaptor.forClass(EnqueueRequest.class);
        verify(jobService).enqueue(captor.capture());
        assertThat(captor.getValue().settings()).containsEntry("instanceId", INSTANCE_ID.toString())
            .containsEntry("instanceName", "iCC Wiki");
    }

    /**
     * Triggering Sync again while the last crawl is still draining is an ordinary mistake, and
     * admission control turns it into a duplicate-enqueue. It must land on the crawl already
     * running with a plain explanation, not on an error page.
     */
    @Test
    void syncWhileACrawlIsAlreadyActiveShowsThatJobInsteadOfFailing() {
        when(instanceService.findView(INSTANCE_ID)).thenReturn(Optional.of(view(true)));
        UUID activeJobId = UUID.randomUUID();
        when(jobService.enqueue(any())).thenReturn(EnqueueResult.adopted(activeJobId));

        assertThat(controller.syncConfluence(INSTANCE_ID, redirectAttributes))
            .isEqualTo("redirect:/admin/jobs/" + activeJobId);
        verify(redirectAttributes, never()).addFlashAttribute(eq("error"), any());
        verify(redirectAttributes).addFlashAttribute(eq("success"),
            contains("already queued or running"));
    }

    @Test
    void syncOfAnUnknownInstanceReportsAnErrorInsteadOfEnqueuing() {
        when(instanceService.findView(INSTANCE_ID)).thenReturn(Optional.empty());

        assertThat(controller.syncConfluence(INSTANCE_ID, redirectAttributes))
            .isEqualTo("redirect:/admin/connectors");
        verify(jobService, never()).enqueue(any());
    }

    @Test
    void syncOfADisabledInstanceReportsAnErrorInsteadOfEnqueuing() {
        when(instanceService.findView(INSTANCE_ID)).thenReturn(Optional.of(view(false)));

        assertThat(controller.syncConfluence(INSTANCE_ID, redirectAttributes))
            .isEqualTo("redirect:/admin/connectors");
        verify(jobService, never()).enqueue(any());
        verify(redirectAttributes).addFlashAttribute(eq("error"), contains("disabled"));
    }

    // ---------------------------------------------------------------------
    // Sync all
    // ---------------------------------------------------------------------

    @Test
    void syncAllOnlyStartsEnabledInstances() {
        when(instanceService.listInstances()).thenReturn(List.of(view(true), view(false)));
        when(jobService.enqueue(any()))
            .thenAnswer(invocation -> EnqueueResult.created(UUID.randomUUID()));

        assertThat(controller.syncAllInstances(redirectAttributes))
            .isEqualTo("redirect:/admin/connectors");

        verify(jobService, times(1)).enqueue(any());
        verify(redirectAttributes).addFlashAttribute(eq("success"), contains("1 instance(s)"));
    }

    /**
     * One instance that cannot be enqueued (an unknown collection, an undeclared tag) must not stop
     * the others: with three sites, aborting halfway leaves the operator unable to tell which were
     * started.
     */
    @Test
    void syncAllKeepsGoingAfterOneInstanceCannotBeEnqueuedAndNamesIt() {
        ConfluenceInstanceView broken = new ConfluenceInstanceView(UUID.randomUUID(), "Broken",
            "broken", "https://b.atlassian.net/wiki", "b@example.com", "B", "1", null, null,
            "Gone Collection", null, false, true, TokenHealth.OK);
        when(instanceService.listInstances()).thenReturn(List.of(broken, view(true)));
        when(jobService.enqueue(any()))
            .thenThrow(new IllegalArgumentException("unknown collection"))
            .thenReturn(EnqueueResult.created(UUID.randomUUID()));

        controller.syncAllInstances(redirectAttributes);

        verify(jobService, times(2)).enqueue(any());
        verify(redirectAttributes).addFlashAttribute(eq("warning"), contains("Broken"));
    }

    @Test
    void syncAllWithNothingEnabledSaysSoInsteadOfClaimingSuccess() {
        when(instanceService.listInstances()).thenReturn(List.of(view(false)));

        controller.syncAllInstances(redirectAttributes);

        verify(jobService, never()).enqueue(any());
        verify(redirectAttributes).addFlashAttribute(eq("error"), contains("No enabled"));
    }

    // ---------------------------------------------------------------------
    // Test connection
    // ---------------------------------------------------------------------

    /** A row's Test button sends only its id: every value comes from that row. */
    @Test
    void testingARowUsesItsStoredSettingsAndItsStoredToken() {
        ConfluenceInstance instance = stored("enc:stored-token", "keyA");
        when(instanceService.findInstance(INSTANCE_ID)).thenReturn(Optional.of(instance));
        when(confluenceClientService.resolveApiToken(instance)).thenReturn("decrypted-token");
        when(confluenceClientService.testConnection(anyString(), anyString(), anyString(),
            anyString(), anyString(), any(), any())).thenReturn(
                ConfluenceClientService.ConnectionTestResult.success("Docs", "Root", 3, 7, ""));

        String html = controller.testConfluenceConnection(INSTANCE_ID, null, null, null, null, null,
            null, null);

        verify(confluenceClientService).testConnection(eq("https://acme.atlassian.net/wiki"),
            eq("svc@example.com"), eq("decrypted-token"), eq("DOCS"), eq("12345"), eq("public"),
            eq("draft"));
        assertThat(html).contains("Docs").doesNotContain("decrypted-token");
    }

    /**
     * The Test button uses the values currently typed in, with the stored credential — but only
     * against the domain that credential belongs to.
     */
    @Test
    void testingTheFormUsesTheTypedValuesAndFallsBackToTheStoredToken() {
        ConfluenceInstance instance = stored("enc:stored-token", "keyA");
        when(instanceService.findInstance(INSTANCE_ID)).thenReturn(Optional.of(instance));
        when(confluenceClientService.resolveApiToken(instance)).thenReturn("decrypted-token");
        when(confluenceClientService.testConnection(anyString(), anyString(), anyString(),
            anyString(), anyString(), any(), any())).thenReturn(
                ConfluenceClientService.ConnectionTestResult.success("Ops", "Root", 1, 2, ""));

        controller.testConfluenceConnection(INSTANCE_ID, "https://acme.atlassian.net/wiki",
            "other@example.com", "  ", "OPS", "999", "", "");

        verify(confluenceClientService).testConnection(eq("https://acme.atlassian.net/wiki"),
            eq("other@example.com"), eq("decrypted-token"), eq("OPS"), eq("999"), eq(""), eq(""));
    }

    /**
     * The destination comes from the request while the credential comes from the row, so pairing a
     * blank token box with an edited domain would send the live Atlassian token to whatever host
     * was typed. The form tells the operator to leave the token blank, so a typo is enough.
     */
    @Test
    void testingRefusesToSendTheStoredTokenToADifferentDomain() {
        ConfluenceInstance instance = stored("enc:stored-token", "keyA");
        when(instanceService.findInstance(INSTANCE_ID)).thenReturn(Optional.of(instance));

        String html = controller.testConfluenceConnection(INSTANCE_ID,
            "https://attacker.example.com", "svc@example.com", "  ", "DOCS", "12345", "", "");

        assertThat(html).contains("Re-enter the API token");
        verifyNoInteractions(confluenceClientService);
    }

    /** An explicitly submitted token is the operator's to send wherever they mean to send it. */
    @Test
    void testingAllowsADifferentDomainWhenTheTokenIsSubmittedExplicitly() {
        ConfluenceInstance instance = stored("enc:stored-token", "keyA");
        when(instanceService.findInstance(INSTANCE_ID)).thenReturn(Optional.of(instance));
        when(confluenceClientService.testConnection(anyString(), anyString(), anyString(),
            anyString(), anyString(), any(), any())).thenReturn(
                ConfluenceClientService.ConnectionTestResult.success("Ops", "Root", 1, 2, ""));

        controller.testConfluenceConnection(INSTANCE_ID, "https://other.atlassian.net",
            "other@example.com", "typed-token", "OPS", "999", "", "");

        verify(confluenceClientService).testConnection(eq("https://other.atlassian.net"),
            eq("other@example.com"), eq("typed-token"), eq("OPS"), eq("999"), eq(""), eq(""));
    }

    /** Every credential-egress path is audited; the connection test used to be the exception. */
    @Test
    void testingWritesAnAuditRowNamingTheTargetHost() {
        ConfluenceInstance instance = stored("enc:stored-token", "keyA");
        when(instanceService.findInstance(INSTANCE_ID)).thenReturn(Optional.of(instance));
        when(confluenceClientService.resolveApiToken(instance)).thenReturn("decrypted-token");
        when(confluenceClientService.testConnection(anyString(), anyString(), anyString(),
            anyString(), anyString(), any(), any())).thenReturn(
                ConfluenceClientService.ConnectionTestResult.success("Ops", "Root", 1, 2, ""));

        controller.testConfluenceConnection(INSTANCE_ID, "https://acme.atlassian.net/wiki",
            "svc@example.com", "  ", "DOCS", "12345", "", "");

        verify(auditLogRepository).log(any(), eq("TEST_CONFLUENCE_CONNECTION"),
            eq("https://acme.atlassian.net/wiki"), any());
    }

    /**
     * An unreadable credential must say WHICH instance is broken and what to do — a generic
     * "connector is not configured" tells an administrator with several sites neither.
     */
    @Test
    void testConnectionSurfacesTheInstanceScopedCredentialFailure() {
        ConfluenceInstance instance = stored("enc:stored-token", "keyA");
        when(instanceService.findInstance(INSTANCE_ID)).thenReturn(Optional.of(instance));
        when(confluenceClientService.resolveApiToken(instance))
            .thenThrow(new IllegalStateException("Confluence instance 'iCC Wiki': stored API token "
                + "cannot be decrypted (APP_SECRET_KEY rotated?) (token health: UNDECRYPTABLE); "
                + "re-enter the token on the connectors page."));

        String html = controller.testConfluenceConnection(INSTANCE_ID, null, null, null, null, null,
            null, null);

        assertThat(html).contains("Connection Failed").contains("iCC Wiki")
            .contains("re-enter the token");
        verify(confluenceClientService, never()).testConnection(anyString(), anyString(),
            anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void testingAnUnsavedInstanceWithoutATokenExplainsWhatIsMissing() {
        String html = controller.testConfluenceConnection(null, "https://acme.atlassian.net",
            "svc@example.com", null, "DOCS", "12345", null, null);

        assertThat(html).contains("Connection Failed").contains("API token");
        verify(confluenceClientService, never()).testConnection(anyString(), anyString(),
            anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void testingAnInstanceThatWasJustDeletedFailsCleanly() {
        when(instanceService.findInstance(INSTANCE_ID)).thenReturn(Optional.empty());

        assertThat(controller.testConfluenceConnection(INSTANCE_ID, null, null, null, null, null,
            null, null)).contains("no longer exists");
    }

    @Test
    void testingWithIncompleteUnsavedSettingsSaysWhatIsMissing() {
        String html = controller.testConfluenceConnection(null, "https://acme.atlassian.net", null,
            "a-token", null, null, null, null);

        assertThat(html).contains("Connection Failed");
        verify(confluenceClientService, never()).testConnection(anyString(), anyString(),
            anyString(), anyString(), anyString(), isNull(), isNull());
    }
}
