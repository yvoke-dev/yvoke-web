package de.palsoftware.yvoke.ingest.web.admin;

import de.palsoftware.yvoke.ingest.core.confluence.ConfluenceClientService;
import de.palsoftware.yvoke.ingest.core.confluence.ConfluenceClientService.ConnectionTestResult;
import de.palsoftware.yvoke.ingest.core.confluence.ConfluenceIngestService;
import de.palsoftware.yvoke.ingest.core.confluence.ConfluenceInstance;
import de.palsoftware.yvoke.ingest.core.confluence.ConfluenceInstanceService;
import de.palsoftware.yvoke.ingest.core.confluence.ConfluenceInstanceView;
import de.palsoftware.yvoke.ingest.core.model.IngestJobKind;
import de.palsoftware.yvoke.ingest.core.confluence.ConfluenceDomains;
import de.palsoftware.yvoke.shared.audit.repository.AuditLogRepository;
import de.palsoftware.yvoke.shared.jobengine.model.EnqueueRequest;
import de.palsoftware.yvoke.shared.jobengine.model.EnqueueResult;
import de.palsoftware.yvoke.shared.jobengine.service.JobService;
import de.palsoftware.yvoke.shared.user.model.User;
import de.palsoftware.yvoke.shared.user.service.UserService;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.HtmlUtils;

/**
 * Writes on the connectors page: create/update, delete, connection test and sync, for any number of
 * Confluence instances.
 *
 * <p>
 * Authorization is URL-based — {@code /admin/**} is {@code hasRole("ADMIN")} on the session filter
 * chain, with CSRF enabled — which is where every endpoint here is gated.
 */
@Controller
@RequestMapping("/admin")
public class ConfluenceConnectorController {

    private static final Logger log = LoggerFactory.getLogger(ConfluenceConnectorController.class);

    private static final String CONNECTORS = "redirect:/admin/connectors";

    private final ConfluenceClientService confluenceClientService;
    private final ConfluenceInstanceService instanceService;
    private final AuditLogRepository auditLogRepository;
    private final JobService jobService;
    private final UserService userService;

    public ConfluenceConnectorController(ConfluenceClientService confluenceClientService,
        ConfluenceInstanceService instanceService, AuditLogRepository auditLogRepository,
        JobService jobService, UserService userService) {
        this.confluenceClientService = confluenceClientService;
        this.instanceService = instanceService;
        this.auditLogRepository = auditLogRepository;
        this.jobService = jobService;
        this.userService = userService;
    }

    private String getCurrentAdminOid() {
        return userService.getCurrentUser().map(User::entraOid).orElse("anonymous_admin");
    }

    // ---------------------------------------------------------------------
    // Create / update
    // ---------------------------------------------------------------------

    /**
     * One create-or-update endpoint: an {@code id} on the form edits that row, no {@code id}
     * creates one.
     *
     * <p>
     * The API token is a separate request parameter on purpose — {@link ConfluenceInstanceForm} has
     * no token component, so the credential cannot be bound into the DTO, echoed back into the page
     * or logged with it. A blank token keeps the stored credential.
     *
     * <p>
     * Validation failures are flashed and redirected, matching every other admin form on this
     * console; anything the service rejects ({@link IllegalArgumentException}) is turned into the
     * same error flash by {@code MvcExceptionHandler}, so there is no try/catch here.
     */
    @PostMapping("/connectors/confluence")
    public String saveInstance(@Valid @ModelAttribute("instanceForm") ConfluenceInstanceForm form,
        BindingResult bindingResult, @RequestParam(required = false) String apiToken,
        RedirectAttributes redirectAttributes) {

        if (bindingResult.hasErrors()) {
            redirectAttributes.addFlashAttribute("error",
                "Could not save the Confluence instance: " + messagesOf(bindingResult));
            return CONNECTORS;
        }

        ConfluenceInstance saved = instanceService.save(form.toInstance(), apiToken);

        boolean tokenUpdated = apiToken != null && !apiToken.isBlank();
        auditLogRepository.log(getCurrentAdminOid(), "CONFIGURE_CONFLUENCE", saved.domain(),
            Map.of("instance", saved.name(), "slug", saved.slug(), "email", saved.email(), "space",
                saved.space(), "rootPageId", saved.rootPageId(), "collection",
                saved.targetCollection(), "tag", saved.targetTag() != null ? saved.targetTag() : "",
                "enabled", saved.enabled(), "tokenUpdated", tokenUpdated));
        redirectAttributes.addFlashAttribute("success",
            "Confluence instance '" + saved.name() + "' saved.");
        return CONNECTORS;
    }

    /**
     * The violated constraints as one readable sentence. Field-level error rendering would be a
     * second error idiom on a console that flashes everything else, so the messages are written to
     * read on their own.
     */
    private static String messagesOf(BindingResult bindingResult) {
        String messages =
            bindingResult.getAllErrors().stream().map(ConfluenceConnectorController::messageOf)
                .distinct().collect(Collectors.joining(" "));
        return messages.isBlank() ? "please check the values you entered." : messages;
    }

    private static String messageOf(ObjectError error) {
        // A binding failure (a hand-edited id that is not a UUID) carries the TypeMismatchException
        // message as its DEFAULT message — internal type and PropertyEditor names an operator can
        // do nothing with. The error CODE decides here, not the presence of a message.
        if (error instanceof FieldError fieldError && fieldError.isBindingFailure()) {
            return "One of the submitted values has the wrong format.";
        }
        String message = error.getDefaultMessage();
        if (message != null && !message.isBlank()) {
            return message;
        }
        return "One of the submitted values has the wrong format.";
    }

    // ---------------------------------------------------------------------
    // Delete
    // ---------------------------------------------------------------------

    /**
     * Deletes an instance, cancelling the work it had already queued (see
     * {@link ConfluenceInstanceService#delete}) and dropping its cached authenticated client.
     */
    @PostMapping("/connectors/confluence/delete")
    public String deleteInstance(@RequestParam UUID id, RedirectAttributes redirectAttributes) {
        ConfluenceInstanceService.Deletion deletion = instanceService.delete(id);

        auditLogRepository.log(getCurrentAdminOid(), "DELETE_CONFLUENCE_INSTANCE", deletion.slug(),
            Map.of("instance", deletion.name(), "cancelledQueuedJobs",
                deletion.cancelledQueuedJobs()));

        String message = "Confluence instance '" + deletion.name() + "' deleted.";
        if (deletion.cancelledQueuedJobs() > 0) {
            message += " " + deletion.cancelledQueuedJobs()
                + " queued job(s) for it were cancelled. A crawl still RUNNING stops itself at its "
                + "next page batch; any other running job finishes or can be stopped from the jobs "
                + "page.";
        }
        redirectAttributes.addFlashAttribute("success", message);
        return CONNECTORS;
    }

    // ---------------------------------------------------------------------
    // Sync
    // ---------------------------------------------------------------------

    @PostMapping("/connectors/confluence/sync")
    public String syncConfluence(@RequestParam UUID id, RedirectAttributes redirectAttributes) {
        Optional<ConfluenceInstanceView> found = instanceService.findView(id);
        if (found.isEmpty()) {
            redirectAttributes.addFlashAttribute("error",
                "That Confluence instance no longer exists.");
            return CONNECTORS;
        }
        ConfluenceInstanceView instance = found.get();
        if (!instance.enabled()) {
            redirectAttributes.addFlashAttribute("error", "Confluence instance '" + instance.name()
                + "' is disabled; enable it before syncing.");
            return CONNECTORS;
        }

        log.info("Triggered Confluence sync for instance={}, domain={}, space={}, rootPageId={}",
            instance.name(), instance.domain(), instance.space(), instance.rootPageId());

        EnqueueResult result = enqueueCrawl(instance);
        UUID jobId = result.jobId();
        if (result.created()) {
            redirectAttributes.addFlashAttribute("success",
                "Confluence sync job enqueued successfully. Job ID: " + jobId);
        } else {
            // Double-clicking Sync, or triggering it again while the last crawl is still draining,
            // is an ordinary mistake — show the crawl that is already running instead of an error.
            redirectAttributes.addFlashAttribute("success", "A sync for instance '"
                + instance.name() + "' is already queued or running — showing that job.");
        }
        return "redirect:/admin/jobs/" + jobId;
    }

    /**
     * Syncs every ENABLED instance in one action.
     *
     * <p>
     * One instance that cannot be enqueued (an unknown collection, a tag the collection does not
     * declare) must not stop the others: the loop records it and the flash names it, rather than
     * aborting halfway and leaving the operator guessing which sites were started.
     */
    @PostMapping("/connectors/confluence/sync-all")
    public String syncAllInstances(RedirectAttributes redirectAttributes) {
        List<ConfluenceInstanceView> enabled = instanceService.listInstances().stream()
            .filter(ConfluenceInstanceView::enabled).toList();
        if (enabled.isEmpty()) {
            redirectAttributes.addFlashAttribute("error",
                "No enabled Confluence instance to sync.");
            return CONNECTORS;
        }

        int queued = 0;
        int alreadyActive = 0;
        List<String> failed = new ArrayList<>();
        for (ConfluenceInstanceView instance : enabled) {
            try {
                if (enqueueCrawl(instance).created()) {
                    queued++;
                } else {
                    alreadyActive++;
                }
            } catch (IllegalArgumentException | IllegalStateException e) {
                log.warn("Could not enqueue the Confluence sync of instance '{}'", instance.name(),
                    e);
                failed.add(instance.name());
            }
        }

        StringBuilder message =
            new StringBuilder("Sync enqueued for ").append(queued).append(" instance(s).");
        if (alreadyActive > 0) {
            message.append(' ').append(alreadyActive)
                .append(" already had a sync queued or running.");
        }
        if (failed.isEmpty()) {
            redirectAttributes.addFlashAttribute("success", message.toString());
        } else {
            redirectAttributes.addFlashAttribute("warning",
                message + " Could not start: " + String.join(", ", failed) + ".");
        }
        return CONNECTORS;
    }

    /**
     * The crawl job. Its kind carries the instance slug; the job engine routes on the base kind
     * before the {@code ':'}, and the jobs list renders the kind verbatim, so the crawl is
     * attributable at a glance.
     *
     * <p>
     * The slug in that kind is a LABEL, not the identity: it is editable on this very form, and a
     * queued crawl whose slug is later taken by another instance would otherwise be resolved to
     * that other site at execution time. The instance id is snapshotted into the settings so the
     * crawl stays bound to the instance it was started for, exactly as every page job already is.
     */
    private EnqueueResult enqueueCrawl(ConfluenceInstanceView instance) {
        auditLogRepository.log(getCurrentAdminOid(), "SYNC_CONFLUENCE_TRIGGER", instance.domain(),
            Map.of("instance", instance.name(), "slug", instance.slug(), "space", instance.space(),
                "rootPageId", instance.rootPageId()));
        return jobService.enqueue(
            new EnqueueRequest(IngestJobKind.CONFLUENCE_IMPORT.getValue() + ":" + instance.slug(),
                "confluence/" + instance.space() + "/" + instance.rootPageId(),
                instance.targetTag(), instance.targetCollection(),
                ConfluenceIngestService.crawlSettings(instance.id(), instance.name())));
    }

    // ---------------------------------------------------------------------
    // Connection test
    // ---------------------------------------------------------------------

    /**
     * Tests one connection and returns an HTML fragment (htmx).
     *
     * <p>
     * Two callers, one endpoint. A row's Test button sends only {@code id}, so everything —
     * settings and stored credential — comes from that row. The form's Test button also sends the
     * values currently typed in, so unsaved settings can be tried before saving; with an {@code id}
     * present and the token box left blank, the STORED token is used, which is what makes "test
     * after editing a label filter" work without re-entering the credential.
     */
    @PostMapping(value = "/connectors/confluence/test", produces = "text/html")
    @ResponseBody
    public String testConfluenceConnection(@RequestParam(required = false) UUID id,
        @RequestParam(required = false) String domain, @RequestParam(required = false) String email,
        @RequestParam(required = false) String apiToken,
        @RequestParam(required = false) String space,
        @RequestParam(required = false) String rootPageId,
        @RequestParam(required = false) String includeLabels,
        @RequestParam(required = false) String excludeLabels) {

        ConfluenceInstance instance = null;
        if (id != null) {
            instance = instanceService.findInstance(id).orElse(null);
            if (instance == null) {
                return failureHtml(
                    "That Confluence instance no longer exists — reload the connectors page.");
            }
        }

        String testDomain = firstNonBlank(domain, instance == null ? null : instance.domain());
        String testEmail = firstNonBlank(email, instance == null ? null : instance.email());
        String testSpace = firstNonBlank(space, instance == null ? null : instance.space());
        String testRootPageId =
            firstNonBlank(rootPageId, instance == null ? null : instance.rootPageId());
        String testIncludeLabels = includeLabels != null ? includeLabels : labelsOf(instance, true);
        String testExcludeLabels =
            excludeLabels != null ? excludeLabels : labelsOf(instance, false);

        if (testDomain == null || testEmail == null || testSpace == null
            || testRootPageId == null) {
            return failureHtml(
                "Domain, e-mail, space key and root page ID are all needed to test a "
                    + "connection.");
        }

        String tokenToUse = apiToken == null || apiToken.isBlank() ? null : apiToken.trim();
        if (tokenToUse == null) {
            if (instance == null) {
                return failureHtml(
                    "Enter an API token to test settings that have not been saved " + "yet.");
            }
            // The destination comes from the request but the credential comes from the row, so
            // without this a mistyped domain sends the stored Atlassian token to that host as a
            // Basic auth header — and the form itself tells the operator to leave the token blank.
            // Refuse rather than quietly substituting the stored domain: a test that probes a
            // different host than the one on screen is worse than one that declines.
            if (!ConfluenceDomains.canonicalizeOrKeep(testDomain)
                .equals(ConfluenceDomains.canonicalizeOrKeep(instance.domain()))) {
                return failureHtml("Re-enter the API token to test a different domain — the "
                    + "stored token is only ever sent to " + instance.domain() + ".");
            }
            try {
                // Naming the instance and its token health beats a generic "not configured": with
                // several sites the message has to say WHICH one is broken and what to do.
                tokenToUse = confluenceClientService.resolveApiToken(instance);
            } catch (IllegalStateException e) {
                return failureHtml(e.getMessage());
            }
        }

        // Every other credential-egress path here is audited (CONFIGURE / DELETE / SYNC); the
        // connection test was the one that left no trace of where a token had been sent.
        auditLogRepository.log(getCurrentAdminOid(), "TEST_CONFLUENCE_CONNECTION", testDomain,
            Map.of("instance", instance == null ? "(unsaved)" : instance.name(), "space", testSpace,
                "tokenSource", apiToken == null || apiToken.isBlank() ? "stored" : "submitted"));

        ConnectionTestResult result = confluenceClientService.testConnection(testDomain, testEmail,
            tokenToUse, testSpace, testRootPageId, testIncludeLabels, testExcludeLabels);

        if (!result.ok()) {
            return failureHtml(result.message());
        }
        return String.format(
            "<div class=\"animate-fade-in\" style=\"background-color: var(--color-success-dim); border: 1px solid var(--color-success); border-radius: 4px; padding: 1rem; color: var(--text-primary);\">"
                + "  <p class=\"text-success\" style=\"font-weight: 600; margin-bottom: 0.5rem;\">%s</p>"
                + "  <ul style=\"list-style-type: none; padding: 0; margin: 0; display: flex; flex-direction: column; gap: 0.25rem;\">"
                + "    <li><strong>Space Name:</strong> %s</li>"
                + "    <li><strong>Root Page Title:</strong> %s (v%d)</li>"
                + "    <li><strong>Total Descendant Pages Found (CQL):</strong> %d</li>" + "  </ul>"
                + "  <hr style=\"border: 0; border-top: 1px solid var(--border-color); margin: 0.75rem 0;\" />"
                + "  <p style=\"font-weight: 600; margin-bottom: 0.5rem; color: var(--text-secondary);\">Sample Pages:</p>"
                + "  <ul style=\"margin: 0; padding-left: 1.25rem; max-height: 150px; overflow-y: auto; display: flex; flex-direction: column; gap: 0.25rem;\">"
                + "    %s" + "  </ul>" + "</div>",
            HtmlUtils.htmlEscape(result.message()), HtmlUtils.htmlEscape(result.spaceName()),
            HtmlUtils.htmlEscape(result.parentPageTitle()), result.parentPageVersion(),
            result.pagesCount(),
            // samplePagesHtml is already escaped per-item at the source (ConfluenceClientService).
            result.samplePagesHtml().isEmpty() ? "<li>No pages found under this root page.</li>"
                : result.samplePagesHtml());
    }

    private static String labelsOf(ConfluenceInstance instance, boolean include) {
        if (instance == null) {
            return null;
        }
        return include ? instance.includeLabels() : instance.excludeLabels();
    }

    private static String firstNonBlank(String submitted, String stored) {
        if (submitted != null && !submitted.isBlank()) {
            return submitted.trim();
        }
        return stored == null || stored.isBlank() ? null : stored;
    }

    private static String failureHtml(String message) {
        return String.format(
            "<div class=\"animate-fade-in\" style=\"background-color: var(--color-danger-dim); border: 1px solid var(--color-danger); border-radius: 4px; padding: 1rem;\">"
                + "  <p class=\"text-danger\" style=\"font-weight: 600; margin-bottom: 0.5rem;\">Connection Failed</p>"
                + "  <p style=\"color: var(--text-primary); font-family: monospace; font-size: 0.85rem; word-break: break-all;\">%s</p>"
                + "</div>",
            HtmlUtils.htmlEscape(message != null ? message : "Unknown error"));
    }
}
