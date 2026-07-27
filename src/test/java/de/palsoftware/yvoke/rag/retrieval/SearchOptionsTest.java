package de.palsoftware.yvoke.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.*;
import org.junit.jupiter.api.Test;

public class SearchOptionsTest {

    @Test
    public void testDefaultValues() {
        SearchOptions opts = new SearchOptions("OIM", null, null, null, null, null);

        assertThat(opts.collection()).isEqualTo("OIM");
        assertThat(opts.limit()).isNull(); // null stays null
        assertThat(opts.semantic()).isTrue(); // null defaults to true
        assertThat(opts.fulltext()).isTrue(); // null defaults to true
        assertThat(opts.tag()).isNull(); // stays null
        assertThat(opts.offset()).isEqualTo(0); // null normalizes to 0
    }

    @Test
    public void testLimitCappingNull() {
        SearchOptions opts = new SearchOptions("OIM", null, true, true, null, 0);
        assertThat(opts.limit()).isNull();
    }

    @Test
    public void testLimitCappingNegative() {
        SearchOptions opts = new SearchOptions("OIM", -5, true, true, null, 0);
        assertThat(opts.limit()).isEqualTo(10); // negative normalizes to 10
    }

    @Test
    public void testLimitCappingZero() {
        SearchOptions opts = new SearchOptions("OIM", 0, true, true, null, 0);
        assertThat(opts.limit()).isEqualTo(10); // zero normalizes to 10
    }

    @Test
    public void testLimitCappingOver200() {
        SearchOptions opts = new SearchOptions("OIM", 500, true, true, null, 0);
        assertThat(opts.limit()).isEqualTo(200); // capped at 200
    }

    @Test
    public void testLimitExactly200() {
        SearchOptions opts = new SearchOptions("OIM", 200, true, true, null, 0);
        assertThat(opts.limit()).isEqualTo(200); // boundary — not capped
    }

    @Test
    public void testLimitValidValue() {
        SearchOptions opts = new SearchOptions("OIM", 50, true, true, null, 0);
        assertThat(opts.limit()).isEqualTo(50); // stays as-is
    }

    @Test
    public void testOffsetNormalization() {
        SearchOptions optsNull = new SearchOptions("OIM", 10, true, true, null, null);
        assertThat(optsNull.offset()).isEqualTo(0);

        SearchOptions optsNeg = new SearchOptions("OIM", 10, true, true, null, -3);
        assertThat(optsNeg.offset()).isEqualTo(0);

        SearchOptions optsPos = new SearchOptions("OIM", 10, true, true, null, 5);
        assertThat(optsPos.offset()).isEqualTo(5);
    }

    @Test
    public void testDefaultOptionsFactory() {
        SearchOptions opts = SearchOptions.defaultOptions("OIM-TEST");

        assertThat(opts.collection()).isEqualTo("OIM-TEST");
        assertThat(opts.limit()).isNull();
        assertThat(opts.semantic()).isTrue();
        assertThat(opts.fulltext()).isTrue();
        assertThat(opts.tag()).isNull();
        assertThat(opts.offset()).isEqualTo(0);
    }

    @Test
    public void testAllParametersSpecified() {
        SearchOptions opts = new SearchOptions("MY-COLL", 42, false, true, "3.2", 7);

        assertThat(opts.collection()).isEqualTo("MY-COLL");
        assertThat(opts.limit()).isEqualTo(42);
        assertThat(opts.semantic()).isFalse();
        assertThat(opts.fulltext()).isTrue();
        assertThat(opts.tag()).isEqualTo("3.2");
        assertThat(opts.offset()).isEqualTo(7);
        assertThat(opts.rerank()).isTrue();
    }

    @Test
    public void testRerankDefaultsToTrue() {
        SearchOptions opts = new SearchOptions("OIM", 10, true, true, null, 0);
        assertThat(opts.rerank()).isTrue();
    }

    @Test
    public void testRerankSpecified() {
        SearchOptions opts = new SearchOptions("OIM", 10, true, true, null, 0, false);
        assertThat(opts.rerank()).isFalse();
    }

    @Test
    public void testCollectionNullOrEmptyAndTagsEmptyThrows() {
        assertThatThrownBy(() -> new SearchOptions((String) null, 10, true, true, null, 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Either collections or tags must be specified");

        assertThatThrownBy(() -> new SearchOptions("  ", 10, true, true, null, 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Either collections or tags must be specified");
    }

    @Test
    public void testTagsOnlyAllowed() {
        SearchOptions opts = new SearchOptions(Collections.emptyList(), 10, true, true, 0, true,
            List.of("tag1", "tag2"));
        assertThat(opts.tags()).containsExactly("tag1", "tag2");
        assertThat(opts.collections()).isEmpty();
    }
}
