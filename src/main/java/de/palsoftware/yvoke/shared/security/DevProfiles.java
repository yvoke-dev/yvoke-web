package de.palsoftware.yvoke.shared.security;

import java.util.Locale;
import java.util.Set;
import org.springframework.core.env.Environment;

/**
 * Single source of truth for the development-only Spring profiles. Settings that must fail closed
 * outside local development — mocked authentication (SEC-09), the plaintext-secret fallback
 * (SEC-05) and the provider gateway credentials — decide whether they are allowed by consulting
 * this set, so the definition of "this is a dev box" never drifts between them.
 */
public final class DevProfiles {

    public static final Set<String> NAMES = Set.of("dev", "local", "test");

    private DevProfiles() {}

    /** @return true if any active profile is one of the recognised development profiles. */
    public static boolean anyActive(Environment environment) {
        for (String profile : environment.getActiveProfiles()) {
            if (NAMES.contains(profile.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
