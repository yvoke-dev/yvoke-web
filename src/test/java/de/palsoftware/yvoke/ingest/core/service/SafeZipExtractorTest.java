package de.palsoftware.yvoke.ingest.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SafeZipExtractorTest {

    @TempDir
    Path tempDir;

    @Test
    void extractsNormalArchive() throws Exception {
        Path zip = tempDir.resolve("ok.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            zos.putNextEntry(new ZipEntry("docs/a.md"));
            zos.write("# Hello".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        Path out = tempDir.resolve("out");
        Files.createDirectories(out);

        new SafeZipExtractor(ZipExtractionProperties.defaults()).extract(zip, out);

        assertThat(Files.readString(out.resolve("docs/a.md"))).isEqualTo("# Hello");
    }

    @Test
    void rejectsZipSlip() throws Exception {
        Path zip = tempDir.resolve("slip.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            zos.putNextEntry(new ZipEntry("../escape.txt"));
            zos.write("boom".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        Path out = tempDir.resolve("out");
        Files.createDirectories(out);

        assertThatThrownBy(
            () -> new SafeZipExtractor(ZipExtractionProperties.defaults()).extract(zip, out))
            .isInstanceOf(IOException.class).hasMessageContaining("zip-slip");
    }

    @Test
    void rejectsTooManyEntries() throws Exception {
        Path zip = tempDir.resolve("many.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (int i = 0; i < 5; i++) {
                zos.putNextEntry(new ZipEntry("f" + i + ".txt"));
                zos.write("x".getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        Path out = tempDir.resolve("out");
        Files.createDirectories(out);

        SafeZipExtractor extractor = new SafeZipExtractor(
            new ZipExtractionProperties(3, 1024 * 1024, 10 * 1024 * 1024, 100));
        assertThatThrownBy(() -> extractor.extract(zip, out)).isInstanceOf(IOException.class)
            .hasMessageContaining("entry count");
    }

    @Test
    void rejectsEntryExceedingMaxEntryBytes() throws Exception {
        Path zip = tempDir.resolve("big-entry.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            zos.putNextEntry(new ZipEntry("big.txt"));
            zos.write(new byte[2048]);
            zos.closeEntry();
        }
        Path out = tempDir.resolve("out");
        Files.createDirectories(out);

        SafeZipExtractor extractor =
            new SafeZipExtractor(new ZipExtractionProperties(100, 1024, 10 * 1024 * 1024, 100));
        assertThatThrownBy(() -> extractor.extract(zip, out)).isInstanceOf(IOException.class)
            .hasMessageContaining("maximum uncompressed size");
    }

    @Test
    void rejectsArchiveExceedingMaxTotalBytes() throws Exception {
        Path zip = tempDir.resolve("big-total.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (int i = 0; i < 3; i++) {
                zos.putNextEntry(new ZipEntry("p" + i + ".txt"));
                zos.write(new byte[600]);
                zos.closeEntry();
            }
        }
        Path out = tempDir.resolve("out");
        Files.createDirectories(out);

        SafeZipExtractor extractor =
            new SafeZipExtractor(new ZipExtractionProperties(100, 1024, 1500, 100));
        assertThatThrownBy(() -> extractor.extract(zip, out)).isInstanceOf(IOException.class)
            .hasMessageContaining("total uncompressed size");
    }

    @Test
    void rejectsHighCompressionRatioBomb() throws Exception {
        // 2 MiB of zeros compresses to ~2 KiB -> ratio ~1000:1
        Path zip = tempDir.resolve("bomb.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            zos.putNextEntry(new ZipEntry("zeros.txt"));
            byte[] zeros = new byte[2 * 1024 * 1024];
            zos.write(zeros);
            zos.closeEntry();
        }
        Path out = tempDir.resolve("out");
        Files.createDirectories(out);

        SafeZipExtractor extractor = new SafeZipExtractor(
            new ZipExtractionProperties(100, 10 * 1024 * 1024, 50 * 1024 * 1024, 50));
        assertThatThrownBy(() -> extractor.extract(zip, out)).isInstanceOf(IOException.class)
            .hasMessageContaining("compression ratio");
    }
}
