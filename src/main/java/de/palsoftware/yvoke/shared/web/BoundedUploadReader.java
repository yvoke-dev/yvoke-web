package de.palsoftware.yvoke.shared.web;

import de.palsoftware.yvoke.shared.io.BoundedStreams;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.springframework.web.multipart.MultipartFile;

/**
 * Utility for safely reading small configuration uploads (markdown playbooks/prompts, JSON
 * profiles/pricing) without risking OOM under the global 200 MB corpus-upload ceiling, and for
 * sanitizing client-supplied upload filenames against path traversal.
 */
public final class BoundedUploadReader {

    /** 1 MiB ceiling for text/JSON configuration imports. */
    public static final long MAX_CONFIG_IMPORT_BYTES = 1024L * 1024L;

    private BoundedUploadReader() {}

    public static String readUtf8(MultipartFile file, long maxBytes) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file is empty.");
        }
        long declaredSize = file.getSize();
        if (declaredSize > maxBytes) {
            throw new IllegalArgumentException(
                    "Uploaded file exceeds maximum allowed size of " + maxBytes + " bytes.");
        }
        byte[] bytes = readBounded(file.getInputStream(), maxBytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public static byte[] readBounded(InputStream in, long maxBytes) throws IOException {
        return BoundedStreams.readBounded(in, maxBytes);
    }

    /**
     * Strips any directory components and traversal sequences from a client-supplied filename,
     * returning a bare filename safe to resolve inside a staging directory.
     */
    public static String safeUploadName(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            return "upload.bin";
        }
        String normalized = originalFilename.replace('\\', '/');
        Path fileName = Path.of(normalized).getFileName();
        String bare = fileName == null ? "" : fileName.toString().trim();
        if (bare.isEmpty() || ".".equals(bare) || "..".equals(bare)) {
            return "upload.bin";
        }
        // Strip control characters and path separators defensively
        bare = bare.replaceAll("[\\p{Cntrl}/\\\\]", "_");
        return bare.isEmpty() ? "upload.bin" : bare;
    }
}
