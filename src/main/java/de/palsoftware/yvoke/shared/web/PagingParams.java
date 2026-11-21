package de.palsoftware.yvoke.shared.web;

/**
 * Shared boundary clamping for page/size and limit/offset query parameters across admin and API
 * endpoints (prevents negative OFFSET/LIMIT 500s, div-by-zero {@code size=0}, {@code int} overflow
 * on {@code page * size}, and unbounded heap materialization).
 */
public final class PagingParams {

    public static final int DEFAULT_ADMIN_PAGE_SIZE = 20;
    public static final int MAX_ADMIN_PAGE_SIZE = 100;
    public static final int MAX_OFFSET = 1_000_000;

    private PagingParams() {}

    public static int clampSize(int size, int defaultSize, int maxSize) {
        if (size <= 0) {
            return defaultSize;
        }
        return Math.min(size, maxSize);
    }

    public static int clampLimit(int limit, int defaultLimit, int maxLimit) {
        if (limit <= 0) {
            return defaultLimit;
        }
        return Math.min(limit, maxLimit);
    }

    public static int clampPage(int page) {
        return Math.max(0, page);
    }

    public static int clampPage(int page, int totalPages) {
        int maxPage = Math.max(0, totalPages - 1);
        return Math.max(0, Math.min(page, maxPage));
    }

    public static int clampPageForOffset(int page, int clampedSize) {
        int safeSize = Math.max(1, clampedSize);
        int maxPage = MAX_OFFSET / safeSize;
        return Math.max(0, Math.min(page, maxPage));
    }

    public static int clampOffset(int offset) {
        return Math.max(0, Math.min(offset, MAX_OFFSET));
    }

    public static int totalPages(long totalCount, int clampedSize) {
        if (totalCount <= 0 || clampedSize <= 0) {
            return 0;
        }
        return (int) Math.ceil((double) totalCount / clampedSize);
    }

    public static int safeOffset(int clampedPage, int clampedSize) {
        long raw = (long) Math.max(0, clampedPage) * Math.max(1, clampedSize);
        return (int) Math.min(raw, MAX_OFFSET);
    }
}
