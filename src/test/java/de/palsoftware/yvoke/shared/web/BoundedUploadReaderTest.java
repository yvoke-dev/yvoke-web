package de.palsoftware.yvoke.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

class BoundedUploadReaderTest {

    @Test
    void readsValidSmallUpload() throws Exception {
        MockMultipartFile file =
                new MockMultipartFile(
                        "file",
                        "pb.md",
                        "text/markdown",
                        "hello world".getBytes(StandardCharsets.UTF_8));
        assertThat(BoundedUploadReader.readUtf8(file, 1024)).isEqualTo("hello world");
    }

    @Test
    void rejectsOversizedUploadByDeclaredSize() {
        byte[] payload = new byte[2048];
        MockMultipartFile file = new MockMultipartFile("file", "big.md", "text/markdown", payload);
        assertThatThrownBy(() -> BoundedUploadReader.readUtf8(file, 1024))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maximum allowed");
    }

    @Test
    void rejectsOversizedStreamEvenWhenDeclaredSizeIsUnknown() {
        byte[] payload = new byte[2048];
        MultipartFile unknownSizeFile =
                new MockMultipartFile("file", "stream.md", "text/markdown", payload) {
                    @Override
                    public long getSize() {
                        return -1;
                    }

                    @Override
                    public InputStream getInputStream() {
                        return new ByteArrayInputStream(payload);
                    }
                };
        assertThatThrownBy(() -> BoundedUploadReader.readUtf8(unknownSizeFile, 1024))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maximum allowed");
    }

    @Test
    void sanitizesPathTraversalFilenames() {
        assertThat(BoundedUploadReader.safeUploadName("../../etc/passwd")).isEqualTo("passwd");
        assertThat(BoundedUploadReader.safeUploadName("a\\b\\..\\secret.zip"))
                .isEqualTo("secret.zip");
        assertThat(BoundedUploadReader.safeUploadName("..")).isEqualTo("upload.bin");
        assertThat(BoundedUploadReader.safeUploadName("   ")).isEqualTo("upload.bin");
        assertThat(BoundedUploadReader.safeUploadName(null)).isEqualTo("upload.bin");
    }
}
