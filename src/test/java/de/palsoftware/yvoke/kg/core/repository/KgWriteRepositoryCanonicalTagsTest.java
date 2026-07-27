package de.palsoftware.yvoke.kg.core.repository;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The tag SET is part of graph entity identity (V2), so what we store must be a set — not whatever
 * order the caller happened to pass. The database indexes {@code kg_canonical_tags(tags)} rather
 * than the raw column, so identity is safe either way; this keeps the stored arrays tidy and pins
 * the contract the SQL function mirrors.
 */
class KgWriteRepositoryCanonicalTagsTest {

    @Test
    void nullBecomesTheEmptySet() {
        assertThat(KgWriteRepository.canonicalTags(null)).isEmpty();
    }

    @Test
    void emptyListBecomesTheEmptySet() {
        assertThat(KgWriteRepository.canonicalTags(List.of())).isEmpty();
    }

    @Test
    void orderIsNormalised() {
        assertThat(KgWriteRepository.canonicalTags(List.of("10.0", "9.3.1")))
            .isEqualTo(KgWriteRepository.canonicalTags(List.of("9.3.1", "10.0")));
    }

    @Test
    void duplicatesCollapse() {
        assertThat(KgWriteRepository.canonicalTags(List.of("9.3.1", "9.3.1")))
            .containsExactly("9.3.1");
    }

    @Test
    void whitespaceIsTrimmed() {
        assertThat(KgWriteRepository.canonicalTags(List.of("  9.3.1  "))).containsExactly("9.3.1");
    }

    @Test
    void trimmingCanRevealADuplicate() {
        assertThat(KgWriteRepository.canonicalTags(Arrays.asList("9.3.1", " 9.3.1")))
            .containsExactly("9.3.1");
    }

    @Test
    void blankAndNullEntriesAreDropped() {
        assertThat(KgWriteRepository.canonicalTags(Arrays.asList("9.3.1", "", "   ", null)))
            .containsExactly("9.3.1");
    }

    @Test
    void aMultiTagScopeIsKeptWhole() {
        // A job may legitimately carry more than one tag; that combination is its own scope, not
        // two separate ones.
        assertThat(KgWriteRepository.canonicalTags(List.of("10.0", "9.3.1")))
            .containsExactly("10.0", "9.3.1");
    }
}
