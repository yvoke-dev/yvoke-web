package de.palsoftware.yvoke.collection.core.service;

import de.palsoftware.yvoke.collection.core.model.Collection;
import de.palsoftware.yvoke.collection.core.repository.CollectionRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class CollectionServiceTest {

    private CollectionRepository collectionRepository;
    private CollectionService collectionService;

    @BeforeEach
    public void setUp() {
        collectionRepository = mock(CollectionRepository.class);
        collectionService = new CollectionService(collectionRepository);
    }

    @Test
    public void testListCollections() {
        Collection col = new Collection(UUID.randomUUID(), "Test", "Desc", Collections.emptyList(),
            OffsetDateTime.now());
        when(collectionRepository.findAll()).thenReturn(List.of(col));

        List<Collection> result = collectionService.listCollections();
        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("Test");
    }

    @Test
    public void testGetCollection() {
        Collection col = new Collection(UUID.randomUUID(), "Test", "Desc", Collections.emptyList(),
            OffsetDateTime.now());
        when(collectionRepository.findByName("Test")).thenReturn(Optional.of(col));

        Optional<Collection> result = collectionService.getCollection("Test");
        assertThat(result).isPresent();
        assertThat(result.get().name()).isEqualTo("Test");

        assertThat(collectionService.getCollection(null)).isEmpty();
        assertThat(collectionService.getCollection("   ")).isEmpty();
    }

    @Test
    public void testCreateCollectionSuccess() {
        Collection col = new Collection(UUID.randomUUID(), "Test", "Desc", Collections.emptyList(),
            OffsetDateTime.now());
        when(collectionRepository.findByName("Test")).thenReturn(Optional.empty());
        when(collectionRepository.create("Test", "Desc")).thenReturn(col);

        Collection created = collectionService.createCollection("Test", "Desc");
        assertThat(created).isNotNull();
        assertThat(created.name()).isEqualTo("Test");
        verify(collectionRepository).create("Test", "Desc");
    }

    @Test
    public void testCreateCollectionInvalidNames() {
        assertThatThrownBy(() -> collectionService.createCollection(null, "desc"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> collectionService.createCollection("   ", "desc"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> collectionService.createCollection("Both", "desc"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> collectionService.createCollection("All", "desc"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * "Both" and "All" are not ordinary names: the corpus selectors render them as the
     * search-everything pseudo-options, so a real collection carrying either name is
     * indistinguishable from the option meaning "no filter" — the picker then either searches the
     * whole corpus when the user asked for one collection, or searches one collection when the user
     * asked for everything, and neither outcome errors. Casing is exactly how such a name gets in:
     * collection names are typed into an admin form, so {@code both}, {@code ALL} and a copy-pasted
     * {@code " all "} are the shapes that actually arrive, not the two title-cased spellings a
     * developer writes in a test.
     *
     * <p>
     * {@code testCreateCollectionInvalidNames} above passes only the canonical casing, so turning
     * {@code equalsIgnoreCase} into {@code equals} — an edit that reads like tightening a
     * comparison — leaves it green while every other spelling is accepted and persisted. The
     * repository assertion is the other half of the rule: a rejected name must never reach
     * {@code create}, because the row it would mint has to be deleted by hand afterwards, and the
     * name it holds is the one the collection dropdown cannot represent.
     */
    @Test
    public void theReservedCollectionNamesAreRejectedInAnyCasing() {
        for (String reserved : List.of("both", "ALL", " all ", "BoTh")) {
            assertThatThrownBy(() -> collectionService.createCollection(reserved, "desc"))
                .as("'%s' is a reserved collection name whatever the casing", reserved)
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("reserved");
        }

        verify(collectionRepository, never()).create(any(), any());
    }

    @Test
    public void testCreateCollectionDuplicate() {
        Collection existing = new Collection(UUID.randomUUID(), "Test", "Desc",
            Collections.emptyList(), OffsetDateTime.now());
        when(collectionRepository.findByName("Test")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> collectionService.createCollection("Test", "New Desc"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("already exists");
    }

    @Test
    public void listAllTagsDelegatesToTheDerivedQuery() {
        when(collectionRepository.findAllTagNames()).thenReturn(List.of("10.0", "9.3.1"));

        assertThat(collectionService.listAllTags()).containsExactly("10.0", "9.3.1");
        verify(collectionRepository).findAllTagNames();
    }

    @Test
    public void listTagsOfReturnsOneCollectionsDeclaredTags() {
        Collection col = new Collection(UUID.randomUUID(), "OIM", "Desc", List.of("9.3.1", "10.0"),
            OffsetDateTime.now());
        when(collectionRepository.findByName("OIM")).thenReturn(Optional.of(col));

        assertThat(collectionService.listTagsOf("OIM")).containsExactly("9.3.1", "10.0");
    }

    @Test
    public void listTagsOfIsEmptyForAnUnknownOrBlankCollection() {
        when(collectionRepository.findByName("nope")).thenReturn(Optional.empty());

        assertThat(collectionService.listTagsOf("nope")).isEmpty();
        assertThat(collectionService.listTagsOf(null)).isEmpty();
        assertThat(collectionService.listTagsOf("   ")).isEmpty();
    }
}
