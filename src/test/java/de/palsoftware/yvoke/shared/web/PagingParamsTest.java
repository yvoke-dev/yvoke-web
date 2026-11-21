package de.palsoftware.yvoke.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PagingParamsTest {

    @Test
    void clampsNegativeZeroAndOversizedSizesAndLimits() {
        assertThat(PagingParams.clampSize(0, 20, 100)).isEqualTo(20);
        assertThat(PagingParams.clampSize(-5, 20, 100)).isEqualTo(20);
        assertThat(PagingParams.clampSize(500, 20, 100)).isEqualTo(100);
        assertThat(PagingParams.clampSize(50, 20, 100)).isEqualTo(50);

        assertThat(PagingParams.clampLimit(0, 100, 500)).isEqualTo(100);
        assertThat(PagingParams.clampLimit(-1, 100, 500)).isEqualTo(100);
        assertThat(PagingParams.clampLimit(10_000, 100, 500)).isEqualTo(500);
        assertThat(PagingParams.clampLimit(250, 100, 500)).isEqualTo(250);
    }

    @Test
    void clampsNegativePageOffsetAndPreventsIntOverflow() {
        assertThat(PagingParams.clampPage(-10)).isEqualTo(0);
        assertThat(PagingParams.clampOffset(-1)).isEqualTo(0);
        assertThat(PagingParams.clampOffset(Integer.MAX_VALUE)).isEqualTo(PagingParams.MAX_OFFSET);

        int hugePage = PagingParams.clampPageForOffset(Integer.MAX_VALUE, 100);
        assertThat((long) hugePage * 100L).isLessThanOrEqualTo(PagingParams.MAX_OFFSET);
        assertThat(PagingParams.safeOffset(Integer.MAX_VALUE, 100))
            .isEqualTo(PagingParams.MAX_OFFSET);
        assertThat(PagingParams.safeOffset(-5, 20)).isEqualTo(0);
    }

    @Test
    void totalPagesHandlesZeroCountAndZeroSizeSafely() {
        assertThat(PagingParams.totalPages(0, 20)).isEqualTo(0);
        assertThat(PagingParams.totalPages(10, 0)).isEqualTo(0);
        assertThat(PagingParams.totalPages(41, 20)).isEqualTo(3);
    }
}
