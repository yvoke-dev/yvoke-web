package de.palsoftware.yvoke.shared.security;

import java.util.Locale;
import java.util.Set;
import org.springframework.core.env.Environment;

/**
 * Single source of truth for the development-only Spring profiles. Security features that must fail
 * closed outside local development — mocked authentication (SEC-09) and the plaintext-secret
 * fallback (SEC-05) — decide whether they are allowed by consulting this set, so the definition of
 * "this is a dev box" never drifts between them.
 */
final class DevProfiles {

    static final Set<String> NAMES = Set.of("dev", "local", "test");

    private DevProfiles() {}

    /** @return true if any active profile is one of the recognised development profiles. */
    static boolean anyActive(Environment environment) {
        for (String profile : environment.getActiveProfiles()) {
            if (NAMES.contains(profile.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
