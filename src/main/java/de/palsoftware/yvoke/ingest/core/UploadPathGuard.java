package de.palsoftware.yvoke.ingest.core;

import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class UploadPathGuard {

    private final Path root;

    public UploadPathGuard(@Value("${app.upload-dir}") String uploadDir) {
        this.root = Path.of(uploadDir).toAbsolutePath().normalize();
    }

    public Path resolve(String sourceRef) {
        if (sourceRef == null || sourceRef.isBlank()) {
            throw new IllegalArgumentException("sourceRef must not be blank");
        }
        Path candidate = Path.of(sourceRef);
        Path resolved = (candidate.isAbsolute() ? candidate : root.resolve(candidate)).normalize();
        if (!resolved.startsWith(root)) {
            throw new SecurityException(
                "sourceRef resolves outside the permitted upload directory");
        }
        return resolved;
    }
}
