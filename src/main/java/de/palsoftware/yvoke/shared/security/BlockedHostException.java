package de.palsoftware.yvoke.shared.security;

/**
 * Raised when an outbound URL or resolved host address is refused by SSRF or host-allowlist policy.
 *
 * <p>Carries detailed diagnostic context for server-side logs while allowing user-facing endpoints
 * (such as connection tests) to catch this specific type and return a generic message without
 * exposing internal IP addresses or allowlist internals.
 */
public class BlockedHostException extends IllegalArgumentException {

    public BlockedHostException(String message) {
        super(message);
    }

    public BlockedHostException(String message, Throwable cause) {
        super(message, cause);
    }
}
