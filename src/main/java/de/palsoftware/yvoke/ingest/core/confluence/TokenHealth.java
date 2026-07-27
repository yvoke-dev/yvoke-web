package de.palsoftware.yvoke.ingest.core.confluence;

/**
 * Readability of a Confluence instance's stored API token, derived without ever attempting a
 * decryption (a list page must not do crypto per row, and must not be able to fail on one row).
 */
public enum TokenHealth {
    /** A ciphertext is on file and was produced with the key currently configured. */
    OK,
    /** No token has been set yet — the instance cannot sync. */
    MISSING,
    /** A ciphertext is on file but the key that produced it is gone; it must be re-entered. */
    UNDECRYPTABLE
}
