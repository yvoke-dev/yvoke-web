package de.palsoftware.yvoke.ingest.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link UploadPathGuard}, the sole confinement guard that keeps ingest {@code
 * sourceRef} file paths inside {@code app.upload-dir}. Its logic is security-critical (a regression
 * enables local-file-inclusion via tainted job payloads) and was previously only ever mocked, so
 * these tests assert the invariants directly. Pure path math — no filesystem access beyond the temp
 * root.
 */
class UploadPathGuardTest {

    private static UploadPathGuard guardRootedAt(Path root) {
        return new UploadPathGuard(root.toString());
    }

    private static Path normalizedRoot(Path root) {
        return root.toAbsolutePath().normalize();
    }

    @Test
    void resolvesSimpleRelativeRefUnderRoot(@TempDir Path root) {
        Path resolved = guardRootedAt(root).resolve("file.txt");

        assertThat(resolved).isEqualTo(normalizedRoot(root).resolve("file.txt"));
        assertThat(resolved.startsWith(normalizedRoot(root))).isTrue();
    }

    @Test
    void resolvesNestedRelativeRefUnderRoot(@TempDir Path root) {
        Path resolved = guardRootedAt(root).resolve("a/b/c.txt");

        assertThat(resolved).isEqualTo(normalizedRoot(root).resolve("a/b/c.txt"));
    }

    @Test
    void normalizesInnerDotDotThatStaysWithinRoot(@TempDir Path root) {
        // "a/../b.txt" is legitimate: it normalizes to <root>/b.txt, still inside root.
        Path resolved = guardRootedAt(root).resolve("a/../b.txt");

        assertThat(resolved).isEqualTo(normalizedRoot(root).resolve("b.txt"));
    }

    @Test
    void allowsAbsolutePathThatIsInsideRoot(@TempDir Path root) {
        Path inside = normalizedRoot(root).resolve("inside.txt");

        assertThat(guardRootedAt(root).resolve(inside.toString())).isEqualTo(inside);
    }

    @Test
    void rejectsRelativeTraversalEscape(@TempDir Path root) {
        assertThatThrownBy(() -> guardRootedAt(root).resolve("../evil.txt"))
            .isInstanceOf(SecurityException.class);
    }

    @Test
    void rejectsDeepTraversalToSystemFile(@TempDir Path root) {
        assertThatThrownBy(() -> guardRootedAt(root).resolve("../../../../../../etc/passwd"))
            .isInstanceOf(SecurityException.class);
    }

    @Test
    void rejectsAbsolutePathOutsideRoot(@TempDir Path root) {
        assertThatThrownBy(() -> guardRootedAt(root).resolve("/etc/passwd"))
            .isInstanceOf(SecurityException.class);
    }

    @Test
    void rejectsSiblingDirectoryWithSharedNamePrefix(@TempDir Path root) {
        // Classic prefix trick: a sibling like "<root>-evil" must NOT count as inside "<root>".
        // Path.startsWith compares name elements, not raw string prefixes — this locks that in.
        String siblingRef = "../" + normalizedRoot(root).getFileName() + "-evil/x.txt";

        assertThatThrownBy(() -> guardRootedAt(root).resolve(siblingRef))
            .isInstanceOf(SecurityException.class);
    }

    @Test
    void rejectsNullRef(@TempDir Path root) {
        assertThatThrownBy(() -> guardRootedAt(root).resolve(null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBlankRef(@TempDir Path root) {
        UploadPathGuard guard = guardRootedAt(root);

        assertThatThrownBy(() -> guard.resolve("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> guard.resolve("   ")).isInstanceOf(IllegalArgumentException.class);
    }
}
