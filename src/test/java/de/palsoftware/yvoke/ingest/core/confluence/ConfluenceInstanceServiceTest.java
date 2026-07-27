package de.palsoftware.yvoke.ingest.core.confluence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.palsoftware.yvoke.collection.core.model.Collection;
import de.palsoftware.yvoke.collection.core.repository.CollectionRepository;
import de.palsoftware.yvoke.shared.jobengine.repository.JobRepository;
import de.palsoftware.yvoke.shared.jobengine.service.JobProgressBroker;
import de.palsoftware.yvoke.shared.security.SecretCipher;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

class ConfluenceInstanceServiceTest {

    private static final UUID INSTANCE_ID = UUID.randomUUID();

    private ConfluenceInstanceRepository repository;
    private SecretCipher secretCipher;
    private JobRepository jobRepository;
    private CollectionRepository collectionRepository;
    private ConfluenceInstanceService service;

    @BeforeEach
    void setUp() {
        repository = mock(ConfluenceInstanceRepository.class);
        secretCipher = mock(SecretCipher.class);
        jobRepository = mock(JobRepository.class);
        collectionRepository = mock(CollectionRepository.class);

        when(secretCipher.keyId()).thenReturn("keyA");
        when(secretCipher.encrypt(anyString()))
            .thenAnswer(invocation -> "enc:" + invocation.getArgument(0));
        when(repository.upsert(any()))
            .thenAnswer(invocation -> invocation.getArgument(0, ConfluenceInstance.class));
        when(collectionRepository.findByName("OIM - Docs")).thenReturn(Optional
            .of(new Collection(UUID.randomUUID(), "OIM - Docs", "docs", List.of("10.0"), null)));

        service = new ConfluenceInstanceService(repository, secretCipher, jobRepository,
            collectionRepository, mock(JobProgressBroker.class));
    }

    private static ConfluenceInstance stored(String apiTokenEnc, String tokenKeyId) {
        return new ConfluenceInstance(INSTANCE_ID, "iCC Wiki", "icc-wiki",
            "https://acme.atlassian.net/wiki", "svc@example.com", apiTokenEnc, tokenKeyId, "DOCS",
            "12345", "public", "draft", "OIM - Docs", "10.0", false, true, null, null);
    }

    /** Exactly what the form produces: every editable field, and never a credential. */
    private static ConfluenceInstance draft(UUID id) {
        return new ConfluenceInstance(id, "iCC Wiki", "icc-wiki", "https://acme.atlassian.net/wiki",
            "svc@example.com", null, null, "DOCS", "12345", "public", "draft", "OIM - Docs", "10.0",
            false, true, null, null);
    }

    private ConfluenceInstance captureUpsert() {
        ArgumentCaptor<ConfluenceInstance> captor =
            ArgumentCaptor.forClass(ConfluenceInstance.class);
        verify(repository).upsert(captor.capture());
        return captor.getValue();
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    @Test
    void listingMapsEveryRowToAViewWithItsTokenHealth() {
        when(repository.findAll())
            .thenReturn(List.of(stored("enc:ciphertext", "keyA"), stored(null, null)));

        List<ConfluenceInstanceView> views = service.listInstances();

        assertThat(views).extracting(ConfluenceInstanceView::tokenHealth)
            .containsExactly(TokenHealth.OK, TokenHealth.MISSING);
    }

    @Test
    void aRowWrittenUnderARetiredKeyIsListedAsUndecryptable() {
        when(secretCipher.keyId()).thenReturn("keyB");
        when(repository.findAll()).thenReturn(List.of(stored("enc:ciphertext", "keyA")));

        assertThat(service.listInstances()).singleElement()
            .extracting(ConfluenceInstanceView::tokenHealth).isEqualTo(TokenHealth.UNDECRYPTABLE);
    }

    @Test
    void findViewMapsTheRowAndAnUnknownIdIsEmpty() {
        when(repository.findById(INSTANCE_ID)).thenReturn(Optional.of(stored("enc:c", "keyA")));

        assertThat(service.findView(INSTANCE_ID)).isPresent().get()
            .extracting(ConfluenceInstanceView::slug).isEqualTo("icc-wiki");
        assertThat(service.findView(UUID.randomUUID())).isEmpty();
    }

    // ------------------------------------------------------------------
    // Save
    // ------------------------------------------------------------------

    /**
     * A blank token means "keep the stored one" and has to reach the repository as NULL: the upsert
     * COALESCEs a null token onto the stored ciphertext, but {@code ''} would overwrite it and the
     * next sync would 401 with nothing in the UI to explain why.
     */
    @Test
    void aBlankTokenLeavesBothCredentialColumnsNullSoTheStoredOneSurvives() {
        service.save(draft(INSTANCE_ID), "   ");

        ConfluenceInstance saved = captureUpsert();
        assertThat(saved.apiTokenEnc()).isNull();
        assertThat(saved.tokenKeyId()).isNull();
        verify(secretCipher, never()).encrypt(anyString());
    }

    /**
     * The ciphertext and its key fingerprint are ONE value: a row with one but not the other has a
     * token health that lies (a rotated key still reads as OK, or a valid token as broken).
     */
    @Test
    void aSubmittedTokenIsStoredWithTheFingerprintOfTheKeyThatEncryptedIt() {
        service.save(draft(INSTANCE_ID), " brand-new-token ");

        ConfluenceInstance saved = captureUpsert();
        assertThat(saved.apiTokenEnc()).isEqualTo("enc:brand-new-token");
        assertThat(saved.tokenKeyId()).isEqualTo("keyA");
    }

    /**
     * {@code SecretCipher.encrypt} throws on an already-encrypted value, so a ciphertext pasted out
     * of the database has to become a readable rejection rather than a raw 500.
     */
    @Test
    void anAlreadyEncryptedTokenIsRejectedBeforeAnythingIsWritten() {
        assertThatThrownBy(() -> service.save(draft(INSTANCE_ID), "enc:pasted-ciphertext"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("already-encrypted");

        verify(repository, never()).upsert(any());
        verify(secretCipher, never()).encrypt(anyString());
    }

    /** The save path must be structurally unable to smuggle a credential in through the draft. */
    @Test
    void aDraftThatAlreadyCarriesACredentialIsRefused() {
        ConfluenceInstance smuggled = stored("enc:ciphertext", "keyA");

        assertThatThrownBy(() -> service.save(smuggled, null))
            .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).upsert(any());
    }

    /**
     * A name/slug clash is a user error on a form field, not an internal failure: the unique index
     * raises a DataIntegrityViolationException that would otherwise surface as a generic "Something
     * went wrong".
     */
    @Test
    void aDuplicateNameOrSlugBecomesAReadableRejection() {
        when(repository.upsert(any()))
            .thenThrow(new DuplicateKeyException("uq_confluence_instances_slug"));

        assertThatThrownBy(() -> service.save(draft(null), null))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("icc-wiki");
    }

    /**
     * The form only checks that a collection name was typed, so a collection that does not exist
     * used to be accepted and then failed much later — at the first sync, from
     * {@code CollectionTagEnqueueValidator}, on a page that says nothing about connectors. It is
     * the same class of user error as a duplicate slug, so it is rejected in the same place and
     * reads the same way.
     */
    @Test
    void aTargetCollectionThatDoesNotExistIsRefusedBeforeAnythingIsWritten() {
        ConfluenceInstance draft = new ConfluenceInstance(INSTANCE_ID, "iCC Wiki", "icc-wiki",
            "https://acme.atlassian.net/wiki", "svc@example.com", null, null, "DOCS", "12345", null,
            null, "Gone Collection", null, false, true, null, null);

        assertThatThrownBy(() -> service.save(draft, null))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Gone Collection");

        verify(repository, never()).upsert(any());
    }

    @Test
    void anExistingTargetCollectionIsAccepted() {
        service.save(draft(INSTANCE_ID), null);

        assertThat(captureUpsert().targetCollection()).isEqualTo("OIM - Docs");
    }

    @Test
    void aMalformedDomainIsRefusedByTheRepositoryAndReachesTheCallerAsAFieldError() {
        when(repository.upsert(any())).thenThrow(
            new IllegalArgumentException("Confluence base URL must use http(s): acme.example.com"));

        assertThatThrownBy(() -> service.save(draft(INSTANCE_ID), null))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("http(s)");
    }

    // ------------------------------------------------------------------
    // Delete
    // ------------------------------------------------------------------

    /**
     * Deleting an instance must not leave its queued work behind: once the row is gone every queued
     * page job can only fail with "instance no longer exists". Queued jobs have not started, so
     * cancelling them is free; running ones are left alone (they are mid-write and stop
     * cooperatively).
     */
    @Test
    void deletingAnInstanceCancelsItsQueuedCrawlAndPageJobsFirst() {
        when(repository.findById(INSTANCE_ID)).thenReturn(Optional.of(stored("enc:c", "keyA")));
        when(jobRepository.cancelQueued("confluence-import:icc-wiki"))
            .thenReturn(List.of(UUID.randomUUID()));
        when(jobRepository.cancelQueued("confluence-page-import:icc-wiki")).thenReturn(
            java.util.stream.IntStream.range(0, 41).mapToObj(i -> UUID.randomUUID()).toList());

        ConfluenceInstanceService.Deletion deletion = service.delete(INSTANCE_ID);

        assertThat(deletion.name()).isEqualTo("iCC Wiki");
        assertThat(deletion.cancelledQueuedJobs()).isEqualTo(42);
        verify(jobRepository).cancelQueued("confluence-import:icc-wiki");
        verify(jobRepository).cancelQueued("confluence-page-import:icc-wiki");
        // Through the repository, so the credentials-changed event drops the cached RestClient —
        // which holds Basic base64(email:token) as a default header.
        verify(repository).deleteById(INSTANCE_ID);
    }

    /**
     * The slug in a job kind is a label the operator can edit, so it cannot be the only handle on
     * that job. Rename an instance's slug while its crawl is still fanning out, then delete it: the
     * queued jobs still carry the OLD slug in their kind and the slug-qualified cancel misses every
     * one of them. They are then left to fail one by one at execution with "instance no longer
     * exists". The instance id, which every Confluence job snapshots into its settings, is the
     * handle that survives a rename.
     */
    @Test
    void deletingAnInstanceCancelsQueuedJobsEnqueuedUnderAPreviousSlug() {
        when(repository.findById(INSTANCE_ID)).thenReturn(Optional.of(stored("enc:c", "keyA")));
        // Nothing matches the CURRENT slug — those jobs were queued as ":old-slug".
        when(jobRepository.cancelQueued(anyString())).thenReturn(List.of());
        when(jobRepository.cancelQueuedBySetting("instanceId", INSTANCE_ID.toString())).thenReturn(
            java.util.stream.IntStream.range(0, 37).mapToObj(i -> UUID.randomUUID()).toList());

        ConfluenceInstanceService.Deletion deletion = service.delete(INSTANCE_ID);

        assertThat(deletion.cancelledQueuedJobs()).isEqualTo(37);
        verify(jobRepository).cancelQueuedBySetting("instanceId", INSTANCE_ID.toString());
        verify(repository).deleteById(INSTANCE_ID);
    }

    @Test
    void deletingAnUnknownInstanceIsRefusedAndTouchesNoJobs() {
        when(repository.findById(INSTANCE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(INSTANCE_ID))
            .isInstanceOf(IllegalArgumentException.class);

        verify(jobRepository, never()).cancelQueued(anyString());
        verify(jobRepository, never()).cancelQueuedBySetting(anyString(), anyString());
        verify(repository, never()).deleteById(any());
    }

    /**
     * The tag select is filled by an htmx swap. Saving before it lands submits an empty value,
     * which would otherwise write NULL over a configured tag and leave the instance ingesting
     * untagged with nothing shown to the operator.
     */
    @Test
    void aBlankTargetTagOnUpdateKeepsTheStoredOne() {
        ConfluenceInstance existing = stored(null, null);
        when(repository.findById(INSTANCE_ID)).thenReturn(Optional.of(existing));
        ConfluenceInstance draft = new ConfluenceInstance(INSTANCE_ID, "iCC Wiki", "icc-wiki",
            "https://acme.atlassian.net/wiki", "svc@example.com", null, null, "DOCS", "12345",
            "public", "draft", "OIM - Docs", "  ", false, true, null, null);

        service.save(draft, null);

        assertThat(captureUpsert().targetTag()).isEqualTo(existing.targetTag());
    }

    /** A submitted tag still wins — this is "keep on blank", not "never change". */
    @Test
    void aSubmittedTargetTagOnUpdateIsWritten() {
        ConfluenceInstance draft = new ConfluenceInstance(INSTANCE_ID, "iCC Wiki", "icc-wiki",
            "https://acme.atlassian.net/wiki", "svc@example.com", null, null, "DOCS", "12345",
            "public", "draft", "OIM - Docs", "9.3.1", false, true, null, null);

        service.save(draft, null);

        assertThat(captureUpsert().targetTag()).isEqualTo("9.3.1");
    }

    /** Blank is the only way to express "no tag" on create, so it must survive there. */
    @Test
    void aBlankTargetTagOnCreateIsKeptBlank() {
        ConfluenceInstance draft = new ConfluenceInstance(null, "iCC Wiki", "icc-wiki",
            "https://acme.atlassian.net/wiki", "svc@example.com", null, null, "DOCS", "12345",
            "public", "draft", "OIM - Docs", null, false, true, null, null);

        service.save(draft, "tok");

        assertThat(captureUpsert().targetTag()).isNull();
    }
}
