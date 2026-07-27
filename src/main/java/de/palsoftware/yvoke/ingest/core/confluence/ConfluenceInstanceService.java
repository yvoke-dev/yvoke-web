package de.palsoftware.yvoke.ingest.core.confluence;

import de.palsoftware.yvoke.collection.core.repository.CollectionRepository;
import de.palsoftware.yvoke.ingest.core.model.IngestJobKind;
import de.palsoftware.yvoke.shared.jobengine.model.ProgressEvent;
import de.palsoftware.yvoke.shared.jobengine.service.JobProgressBroker;
import de.palsoftware.yvoke.shared.jobengine.repository.JobRepository;
import de.palsoftware.yvoke.shared.security.SecretCipher;
import jakarta.annotation.Nullable;
import java.util.List;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The connector admin page's view of {@code confluence_instances}: it maps rows to
 * {@link ConfluenceInstanceView} (never the record, which carries the credential), owns the one
 * place where a submitted token is encrypted, and makes deleting an instance stop the work that
 * instance had already queued.
 */
@Service
public class ConfluenceInstanceService {

    private static final Logger log = LoggerFactory.getLogger(ConfluenceInstanceService.class);

    private final ConfluenceInstanceRepository repository;
    private final SecretCipher secretCipher;
    private final JobRepository jobRepository;
    private final CollectionRepository collectionRepository;
    private final JobProgressBroker progressBroker;

    public ConfluenceInstanceService(ConfluenceInstanceRepository repository,
        SecretCipher secretCipher, JobRepository jobRepository,
        CollectionRepository collectionRepository, JobProgressBroker progressBroker) {
        this.repository = repository;
        this.secretCipher = secretCipher;
        this.jobRepository = jobRepository;
        this.collectionRepository = collectionRepository;
        this.progressBroker = progressBroker;
    }

    /** What a delete actually did, so the operator is told rather than left to find out. */
    public record Deletion(String name, String slug, int cancelledQueuedJobs) {}

    /** One round-trip; the token health is derived per row without decrypting anything. */
    @Transactional(readOnly = true)
    public List<ConfluenceInstanceView> listInstances() {
        String keyId = secretCipher.keyId();
        return repository.findAll().stream()
            .map(instance -> ConfluenceInstanceView.of(instance, keyId)).toList();
    }

    @Transactional(readOnly = true)
    public Optional<ConfluenceInstanceView> findView(UUID id) {
        String keyId = secretCipher.keyId();
        return repository.findById(id).map(instance -> ConfluenceInstanceView.of(instance, keyId));
    }

    /**
     * The row itself, credential included — for the ONE caller that needs it, the connection test,
     * which has to decrypt the stored token to authenticate. The result must never be put into a
     * model attribute; use {@link #findView(UUID)} for anything a template renders.
     */
    @Transactional(readOnly = true)
    public Optional<ConfluenceInstance> findInstance(UUID id) {
        return repository.findById(id);
    }

    /**
     * Creates or updates an instance from the edited fields, attaching the credential.
     *
     * <p>
     * {@code draft} must carry no credential at all: it comes from a form DTO that has no token
     * component, and this is the only place a token becomes ciphertext. The ciphertext and its key
     * fingerprint are ONE value and are always written together — a row holding one without the
     * other has a {@link ConfluenceInstance#tokenHealth(String)} that lies. A blank submitted token
     * means "keep the stored credential" and must reach the repository as NULL on BOTH columns (the
     * upsert COALESCEs it onto the stored ciphertext; {@code ''} would overwrite it and the next
     * sync would 401 with nothing in the UI to explain why).
     *
     * <p>
     * The target collection has to exist HERE. The form only checks that a name was typed, so an
     * unknown collection used to be accepted and then failed at the first sync, from
     * {@code CollectionTagEnqueueValidator}, on a page that says nothing about connectors. It is
     * the same class of user error as a duplicate slug, so it is refused in the same place.
     *
     * @param submittedToken the plaintext token an administrator typed, or null/blank to keep the
     *        stored one
     * @throws IllegalArgumentException if the draft already carries a credential, the submitted
     *         token is an already-encrypted value, the domain is not an absolute http(s) URL, the
     *         target collection does not exist, or the name/slug is already taken
     */
    /**
     * On an update, a blank tag keeps the stored one — the same contract the API token field
     * already uses ("leave blank to keep").
     *
     * <p>
     * The tag {@code <select>} is filled by an htmx swap into {@code #tag-container}. Saving before
     * that swap lands — or after it fails — submits an empty value, which would otherwise write
     * NULL over a configured tag and leave the instance ingesting untagged, with no error shown.
     * Guarding here rather than in the browser means a slow or failed request cannot cause it.
     * Blank still means "no tag" on create, which is the only way to express that.
     */
    private String resolveTargetTag(ConfluenceInstance draft) {
        if (draft.targetTag() != null && !draft.targetTag().isBlank()) {
            return draft.targetTag();
        }
        if (draft.id() == null) {
            return draft.targetTag();
        }
        return repository.findById(draft.id()).map(ConfluenceInstance::targetTag)
            .orElse(draft.targetTag());
    }

    public ConfluenceInstance save(ConfluenceInstance draft, @Nullable String submittedToken) {
        if (draft.apiTokenEnc() != null || draft.tokenKeyId() != null) {
            throw new IllegalArgumentException(
                "The edited instance must not carry a credential; the API token is supplied "
                    + "separately and encrypted here.");
        }
        if (collectionRepository.findByName(draft.targetCollection()).isEmpty()) {
            throw new IllegalArgumentException("Collection '" + draft.targetCollection()
                + "' does not exist; create it on the collections page first.");
        }
        String token =
            submittedToken == null || submittedToken.isBlank() ? null : submittedToken.trim();
        if (token != null && SecretCipher.isCiphertext(token)) {
            // SecretCipher.encrypt throws on an already-encrypted value; rejecting it here keeps a
            // pasted ciphertext (copied out of the database, say) a field error, not a 500.
            throw new IllegalArgumentException(
                "That API token looks like an already-encrypted value. Paste the token from your "
                    + "Atlassian account, or leave the field blank to keep the stored one.");
        }
        String apiTokenEnc = token == null ? null : secretCipher.encrypt(token);
        String tokenKeyId = token == null ? null : secretCipher.keyId();

        ConfluenceInstance toSave =
            new ConfluenceInstance(draft.id(), draft.name(), draft.slug(), draft.domain(),
                draft.email(), apiTokenEnc, tokenKeyId, draft.space(), draft.rootPageId(),
                draft.includeLabels(), draft.excludeLabels(), draft.targetCollection(),
                resolveTargetTag(draft), draft.processAttachments(), draft.enabled(), null, null);
        try {
            return repository.upsert(toSave);
        } catch (DataIntegrityViolationException e) {
            // The upsert arbitrates on the PRIMARY KEY, so a name or slug already held by another
            // instance surfaces here instead of silently rewriting that other row.
            log.warn("Confluence instance save violated a unique constraint (name={}, slug={})",
                draft.name(), draft.slug(), e);
            throw new IllegalArgumentException("Another Confluence instance already uses the name '"
                + draft.name() + "' or the slug '" + draft.slug() + "'.", e);
        }
    }

    /**
     * Deletes an instance and CANCELS the work it had already queued.
     *
     * <p>
     * Queued jobs are cancelled rather than left behind: once the row is gone, every queued
     * {@code confluence-page-import:<slug>} can only fail with "instance no longer exists, so its
     * API token cannot be resolved", so a deleted connector would otherwise turn into a wall of red
     * jobs an operator has to sweep up by hand. Queued jobs have not started, so cancelling them is
     * free; RUNNING jobs are deliberately untouched — they are mid-write and stop cooperatively
     * (stop those individually from the jobs page).
     *
     * <p>
     * Cancelling the queue is only half of it: the job most likely to be running is the CRAWL, and
     * the crawl is the producer. {@code ConfluenceIngestService} therefore re-checks that this row
     * still exists once per page batch, so a mid-crawl delete cannot immediately refill the queue
     * this method just emptied.
     *
     * <p>
     * The sweep is by snapshotted instance ID first, then by the two slug-qualified kinds. The id
     * is what makes it complete: the slug is editable, so a job queued before a rename carries the
     * OLD slug in its kind and no kind-based cancel can reach it. The slug passes remain for jobs
     * queued before ids were snapshotted. The bare kind is never used — it would match EVERY
     * instance, and a pre-instances job belongs to no instance in particular.
     *
     * <p>
     * The delete goes through the repository, which publishes
     * {@link ConfluenceInstanceCredentialsChangedEvent} — that is what drops the cached
     * {@link org.springframework.web.client.RestClient}, which carries
     * {@code Basic base64(email:token)} as a default header, instead of leaving it reachable in the
     * heap until the next restart.
     */
    @Transactional
    public Deletion delete(UUID id) {
        ConfluenceInstance instance = repository.findById(id).orElseThrow(
            () -> new IllegalArgumentException("That Confluence instance no longer exists."));
        // By ID FIRST, because it is the only handle that survives a slug rename: a crawl fanning
        // out under ":old-slug" leaves jobs the slug-qualified cancel below cannot see, and they
        // would then fail one by one at execution with "instance no longer exists". Every
        // Confluence job snapshots the id (ConfluenceIngestService.crawlSettings and the page-job
        // settings), so this is the complete sweep.
        //
        // The two slug passes stay as the fallback for jobs queued before ids were snapshotted.
        // They cannot double-count: a job cancelled by the pass above is no longer 'queued', so a
        // later pass does not match it.
        List<UUID> cancelledIds = new ArrayList<>();
        cancelledIds.addAll(jobRepository.cancelQueuedBySetting(
            ConfluenceIngestService.SETTING_INSTANCE_ID, instance.id().toString()));
        cancelledIds.addAll(jobRepository
            .cancelQueued(IngestJobKind.CONFLUENCE_IMPORT.getValue() + ":" + instance.slug()));
        cancelledIds.addAll(jobRepository
            .cancelQueued(IngestJobKind.CONFLUENCE_PAGE_IMPORT.getValue() + ":" + instance.slug()));
        int cancelled = cancelledIds.size();
        // Same reason as the bulk admin cancel: rows updated directly leave any open job page
        // streaming a stale "Queued" with a live Stop button.
        // JobProgressBroker rather than JobService: JobService collects every JobHandler, and
        // ConfluenceIngestService is one, so injecting it here would close a bean cycle. This is
        // the same two lines JobService.publishSnapshot runs.
        cancelledIds.forEach(jobId -> jobRepository.findById(jobId)
            .ifPresent(job -> progressBroker.publish(ProgressEvent.of(job))));
        repository.deleteById(id);
        log.info("Deleted Confluence instance '{}' ({}); cancelled {} queued job(s)",
            instance.name(), instance.slug(), cancelled);
        return new Deletion(instance.name(), instance.slug(), cancelled);
    }
}
