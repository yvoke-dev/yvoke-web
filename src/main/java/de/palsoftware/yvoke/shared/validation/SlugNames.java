package de.palsoftware.yvoke.shared.validation;

import java.util.regex.Pattern;

/**
 * Validates resource names for prompts, playbooks, and orchestrator profiles so control characters,
 * quotes, HTML markup, and path separators cannot be stored.
 */
public final class SlugNames {

    private static final int MAX_LENGTH = 128;

    private static final Pattern SAFE_NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9 ._-]{0,127}$");

    private SlugNames() {}

    public static String requireValidName(String name, String label) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException(label + " name cannot be empty.");
        }
        String trimmed = name.trim();
        if (trimmed.length() > MAX_LENGTH || !SAFE_NAME.matcher(trimmed).matches()) {
            throw new IllegalArgumentException(label
                + " name must start with a letter or digit and contain only letters, digits, spaces, '.', '_', or '-' (max "
                + MAX_LENGTH + " chars).");
        }
        return trimmed;
    }
}
