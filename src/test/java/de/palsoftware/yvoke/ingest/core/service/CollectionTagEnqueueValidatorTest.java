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
}
