package de.palsoftware.yvoke.ingest.core.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.springframework.stereotype.Component;

/**
 * Extracts ZIP archives with zip-slip confinement and zip-bomb defenses (max entry count, max
 * uncompressed bytes per entry, max total uncompressed bytes, and max compression ratio measured on
 * actual decompressed bytes rather than untrusted ZIP headers).
 */
@Component
public class SafeZipExtractor {

    /**
     * Only check compression ratio once an entry expands past 1 MiB so tiny repetitive config/text
     * files do not trigger false positives.
     */
    static final long RATIO_CHECK_MIN_BYTES = 1024L * 1024L;

    private final ZipExtractionProperties properties;

    public SafeZipExtractor(ZipExtractionProperties properties) {
        this.properties = properties != null ? properties : ZipExtractionProperties.defaults();
    }

    public void extract(Path zipFile, Path destDir) throws IOException {
        Path normalizedDest = destDir.toAbsolutePath().normalize();
        int entryCount = 0;
        long totalBytes = 0;
        byte[] buffer = new byte[8192];

        try (ZipFile zip = new ZipFile(zipFile.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                Path entryPath = normalizedDest.resolve(name).normalize();
                if (!entryPath.startsWith(normalizedDest)) {
                    throw new IOException("Bad zip entry (zip-slip): " + name);
                }
                if (name.contains("__MACOSX") || name.contains("/.") || name.startsWith(".")) {
                    continue;
                }
                entryCount++;
                if (entryCount > properties.maxEntries()) {
                    throw new IOException(
                            "ZIP archive exceeds maximum allowed entry count ("
                                    + properties.maxEntries()
                                    + ")");
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    Files.createDirectories(entryPath.getParent());
                    long compressedSize = entry.getCompressedSize();
                    long entryBytes = 0;
                    try (InputStream is = zip.getInputStream(entry);
                            OutputStream os = Files.newOutputStream(entryPath)) {
                        int read;
                        while ((read = is.read(buffer)) != -1) {
                            entryBytes += read;
                            totalBytes += read;
                            if (entryBytes > properties.maxEntryBytes()) {
                                throw new IOException(
                                        "ZIP entry '"
                                                + name
                                                + "' exceeds maximum uncompressed size ("
                                                + properties.maxEntryBytes()
                                                + " bytes)");
                            }
                            if (totalBytes > properties.maxTotalBytes()) {
                                throw new IOException(
                                        "ZIP archive exceeds maximum total uncompressed size ("
                                                + properties.maxTotalBytes()
                                                + " bytes)");
                            }
                            if (compressedSize > 0
                                    && entryBytes >= RATIO_CHECK_MIN_BYTES
                                    && (entryBytes / compressedSize)
                                            > properties.maxCompressionRatio()) {
                                throw new IOException(
                                        "ZIP entry '"
                                                + name
                                                + "' exceeds maximum compression ratio ("
                                                + properties.maxCompressionRatio()
                                                + ":1)");
                            }
                            os.write(buffer, 0, read);
                        }
                    }
                }
            }
        }
    }
}
