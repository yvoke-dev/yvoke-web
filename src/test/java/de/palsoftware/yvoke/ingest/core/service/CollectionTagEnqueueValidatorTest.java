package de.palsoftware.yvoke.ingest.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.palsoftware.yvoke.collection.core.model.Collection;
import de.palsoftware.yvoke.collection.core.repository.CollectionRepository;
import de.palsoftware.yvoke.shared.jobengine.model.EnqueueRequest;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CollectionTagEnqueueValidatorTest {

    private CollectionRepository collectionRepository;
    private CollectionTagEnqueueValidator validator;

    @BeforeEach
    void setUp() {
        collectionRepository = mock(CollectionRepository.class);
        validator = new CollectionTagEnqueueValidator(collectionRepository);

        Collection defaultCol = new Collection(UUID.randomUUID(), "col", "manual",
            List.of("1.0", "2.0"), OffsetDateTime.now());
        when(collectionRepository.findByName("col")).thenReturn(Optional.of(defaultCol));
    }

    @Test
    void rejectsUnknownCollection() {
        when(collectionRepository.findByName("missing")).thenReturn(Optional.empty());
        assertThatThrownBy(
            () -> validator.validate(new EnqueueRequest("it_test", "ref", "1.0", "missing")))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("does not exist");
    }

    @Test
    void rejectsInvalidVersionForCollectionWithVersions() {
        assertThatThrownBy(
            () -> validator.validate(new EnqueueRequest("it_test", "ref", "3.0", "col")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Tag '3.0' is not valid for collection 'col'");
    }

    @Test
    void normalizesVersionToNullForCollectionWithoutVersions() {
        Collection noVersionCol = new Collection(UUID.randomUUID(), "no-ver",
            "no version collection", List.of(), OffsetDateTime.now());
        when(collectionRepository.findByName("no-ver")).thenReturn(Optional.of(noVersionCol));

        EnqueueRequest result =
            validator.validate(new EnqueueRequest("it_test", "ref", "1.0", "no-ver"));

        assertThat(result).isEqualTo(new EnqueueRequest("it_test", "ref", null, "no-ver"));
    }

    @Test
    void passesThroughValidVersion() {
        EnqueueRequest result =
            validator.validate(new EnqueueRequest("it_test", "ref", "1.0", "col"));
        assertThat(result).isEqualTo(new EnqueueRequest("it_test", "ref", "1.0", "col"));
    }

    /**
     * Once a collection DECLARES tags, an enqueue carrying none must be rejected loudly. Letting it
     * through would write documents/chunks/entities with {@code tags = '{}'}, which then collide on
     * the same {@code source_file} identity as the tagged rows — so the next version's ingest
     * REPLACES the untagged corpus instead of sitting beside it, and every tag-scoped read
     * ({@code :tag = ANY(tags)}) misses the rows entirely. This is the loud half of the contract;
     * its silent half — a collection that declares NO tags drops the requested tag instead — is
     * pinned by {@link #normalizesVersionToNullForCollectionWithoutVersions()} and is exactly why
     * tags must be declared on the collection BEFORE the first ingest.
     */
    @Test
    void aCollectionThatDeclaresTagsRejectsAnEnqueueThatCarriesNone() {
        assertThatThrownBy(
            () -> validator.validate(new EnqueueRequest("it_test", "ref", (String) null, "col")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must not be blank/empty");
    }

    /**
     * Two halves of the same contract, and every existing case is blind to both: they all reject a
     * WHOLLY different tag ("3.0" against ["1.0","2.0"]) and pass a request collection string that
     * is already identical to the stub's name, so neither the comparison's case-sensitivity nor the
     * rewrite is exercised. The collection lookup is deliberately LENIENT —
     * {@code CollectionRepository.findByName} matches {@code LOWER(name)} — while everything
     * downstream is strict: {@code ChunkRepository.resolveCollectionIds} does an exact
     * {@code name IN (:names)}, and every tag-scoped read is {@code :tag = ANY(tags)}. That is the
     * "validate leniently, query strictly" shape, and the validator is the single place the two are
     * reconciled.
     *
     * <p>
     * Loosen the tag test to {@code equalsIgnoreCase} and an ingest requesting "BETA" against a
     * collection declaring "beta" is ACCEPTED and the caller's spelling is written into
     * documents/chunks/entities — a second, invisible tag scope that no read for "beta" ever
     * returns, and which silently competes for the same {@code source_file} identity. Drop the
     * rewrite and the job is enqueued under the caller's spelling of the collection, which the
     * exact-match resolvers do not find at all. Both fail as an empty corpus, never as an error.
     */
    @Test
    void theValidatorMatchesTagsCaseSensitivelyAndRewritesTheCollectionToItsStoredName() {
        // The catalog row: stored name "Beta-Coll", declared tag "beta". findByName is
        // case-insensitive, so the lower-case request below resolves it.
        Collection stored = new Collection(UUID.randomUUID(), "Beta-Coll", "manual",
            List.of("beta"), OffsetDateTime.now());
        when(collectionRepository.findByName("beta-coll")).thenReturn(Optional.of(stored));

        assertThatThrownBy(
            () -> validator.validate(new EnqueueRequest("it_test", "ref", "BETA", "beta-coll")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Tag 'BETA' is not valid");

        EnqueueRequest accepted =
            validator.validate(new EnqueueRequest("it_test", "ref", "beta", "beta-coll"));

        assertThat(accepted.collection()).isEqualTo("Beta-Coll");
        assertThat(accepted.tags()).containsExactly("beta");
    }

    @Test
    void aBlankTagIsRejectedRatherThanTrimmedAwayIntoAnUntaggedIngest() {
        assertThatThrownBy(
            () -> validator.validate(new EnqueueRequest("it_test", "ref", "   ", "col")))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must not be blank");
    }
}
