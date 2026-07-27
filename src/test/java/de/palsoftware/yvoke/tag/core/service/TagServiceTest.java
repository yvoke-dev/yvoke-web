package de.palsoftware.yvoke.tag.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import de.palsoftware.yvoke.tag.core.repository.TagRepository;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pins Wave 3.5: {@link TagService} is the transactional entry point for tag mutations, delegating
 * verbatim to {@link TagRepository} (which no longer carries {@code @Transactional}).
 */
class TagServiceTest {

    private final TagRepository repository = Mockito.mock(TagRepository.class);
    private final TagService service = new TagService(repository);

    @Test
    void addTagToCollectionDelegates() {
        UUID id = UUID.randomUUID();
        service.addTagToCollection(id, "v1");
        verify(repository).addTagToCollection(id, "v1");
        verifyNoMoreInteractions(repository);
    }

    @Test
    void addTagToDocumentDelegates() {
        UUID id = UUID.randomUUID();
        service.addTagToDocument(id, "v1");
        verify(repository).addTagToDocument(id, "v1");
        verifyNoMoreInteractions(repository);
    }

    @Test
    void removeTagFromDocumentDelegates() {
        UUID id = UUID.randomUUID();
        service.removeTagFromDocument(id, "v1");
        verify(repository).removeTagFromDocument(id, "v1");
        verifyNoMoreInteractions(repository);
    }

    /**
     * The tag slice no longer answers "which tags exist" — that vocabulary is derived from
     * {@code collections.tags} by {@code CollectionService.listAllTags()}.
     *
     * <p>
     * It used to come from a {@code tags} registry table whose only writer was this slice's
     * {@code getOrCreateTag}, so it recorded a tag exactly when it arrived through an admin form or
     * the ingest enqueue and missed every other writer — most visibly the corpus import, which sets
     * {@code collections.tags} directly. Reintroducing a lookup here would reintroduce the drift.
     */
    @Test
    void theServiceExposesNoTagVocabularyLookup() {
        assertThat(TagService.class.getMethods())
            .noneMatch(m -> m.getName().toLowerCase().contains("findall"));
        assertThat(TagRepository.class.getMethods())
            .noneMatch(m -> m.getName().toLowerCase().contains("findall"));
    }

    @Test
    void writeMethodsCarryTheTransactionBoundary() throws Exception {
        // The array-append must be atomic with its guard; the tx lives on the service, not the
        // repository.
        for (String name : List.of("addTagToCollection", "addTagToDocument",
            "removeTagFromDocument")) {
            Method m = TagService.class.getMethod(name, UUID.class, String.class);
            assertThat(m.isAnnotationPresent(Transactional.class))
                .as("%s must be @Transactional", name).isTrue();
        }
        // The repository must NOT carry its own @Transactional any more.
        assertThat(TagRepository.class.getMethod("addTagToCollection", UUID.class, String.class)
            .isAnnotationPresent(Transactional.class)).isFalse();
    }
}
