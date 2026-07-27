package de.palsoftware.yvoke.shared.security;

/**
 * Raised when a stored secret cannot be read back: either no key is configured while the value is
 * ciphertext, or the configured key/salt is not the one it was encrypted with.
 *
 * <p>
 * {@code Encryptors.delux} is authenticated AES-256-GCM, so a wrong key <em>always</em> raises
 * rather than returning garbage. Callers that need the secret must surface that signal — swallowing
 * it turns one key rotation into N silently blank credentials that still look configured.
 * Deliberately carries no secret material and no ciphertext in its message.
 */
public class SecretDecryptionException extends RuntimeException {

    public SecretDecryptionException(String message) {
        super(message);
    }

    public SecretDecryptionException(String message, Throwable cause) {
        super(message, cause);
    }
}
