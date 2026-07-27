package de.palsoftware.yvoke.shared.security;

import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.util.HexFormat;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.stereotype.Component;

@Component
public class SecretCipher {

    private static final Logger log = LoggerFactory.getLogger(SecretCipher.class);
    private static final String PREFIX = "enc:";
    /** 64 bits of the derived key — collision-safe for the handful of keys one deployment holds. */
    private static final int KEY_ID_LENGTH = 16;
    private static final String KEY_ID_KDF = "PBKDF2WithHmacSHA256";
    /**
     * Domain separation: the fingerprint must never be derivable from — or usable against — the
     * encryption key {@link Encryptors#delux} derives from the same material.
     */
    private static final String KEY_ID_SALT_PREFIX = "yvoke-key-id:";
    /**
     * The fingerprint is persisted and outlives the ciphertext it describes, so it must be no
     * cheaper to attack than the ciphertext. Paid once, in the constructor.
     */
    private static final int KEY_ID_ITERATIONS = 200_000;
    private static final int KEY_ID_BITS = 256;

    private final TextEncryptor encryptor;
    /** Fingerprint of the key material behind {@link #encryptor}; null when encryption is off. */
    private final String keyId;

    public SecretCipher(@Value("${app.security.secret-key}") String secretKey,
        @Value("${app.security.secret-salt}") String salt, Environment environment) {
        boolean keyConfigured =
            secretKey != null && !secretKey.isBlank() && !secretKey.contains("placeholder");
        if (!keyConfigured) {
            // SEC-05: without a key, stored secrets (e.g. the Confluence API token) would be kept
            // in
            // PLAINTEXT. That is only tolerable on a developer's machine — outside a dev/local/test
            // profile, fail closed and refuse to start rather than silently persisting plaintext.
            if (!DevProfiles.anyActive(environment)) {
                throw new IllegalStateException(
                    "app.security.secret-key is not configured. Refusing to start outside the "
                        + "development profiles " + DevProfiles.NAMES + " because stored secrets "
                        + "would be kept in plaintext. Set APP_SECRET_KEY (and APP_SECRET_SALT).");
            }
            log.warn("app.security.secret-key is not configured; secrets (e.g. the Confluence API "
                + "token) will be stored in PLAINTEXT. This is permitted only under a development "
                + "profile. Set APP_SECRET_KEY to enable at-rest encryption.");
            this.encryptor = null;
            this.keyId = null;
        } else {
            // SEC-15: no shared/default salt — each deployment must supply its own so the KDF salt
            // is
            // not identical across installations.
            if (salt == null || salt.isBlank()) {
                throw new IllegalStateException(
                    "app.security.secret-salt must be set when app.security.secret-key is configured. "
                        + "Set APP_SECRET_SALT to a per-deployment hex value (no shared default).");
            }
            this.encryptor = Encryptors.delux(secretKey, salt);
            this.keyId = fingerprint(secretKey, salt);
        }
    }

    /**
     * A stable, non-reversible fingerprint of the configured key material (never the key itself),
     * so a stored secret can record which key produced it and a rotation can tell which rows still
     * need re-encryption. Null when encryption is disabled.
     */
    public String keyId() {
        return keyId;
    }

    /**
     * Derives the fingerprint with a deliberately expensive KDF. A single SHA-256 over the key
     * would be a free offline oracle: the fingerprint is persisted (e.g.
     * {@code confluence_instances.token_key_id}), survives the ciphertext it describes, and would
     * let anyone with a DB dump test a candidate {@code APP_SECRET_KEY} for one hash instead of the
     * KDF cost of attacking the ciphertext. Called once per instance, from the constructor.
     */
    private static String fingerprint(String secretKey, String salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(secretKey.toCharArray(),
                (KEY_ID_SALT_PREFIX + salt).getBytes(StandardCharsets.UTF_8), KEY_ID_ITERATIONS,
                KEY_ID_BITS);
            byte[] derived =
                SecretKeyFactory.getInstance(KEY_ID_KDF).generateSecret(spec).getEncoded();
            return HexFormat.of().formatHex(derived).substring(0, KEY_ID_LENGTH);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            // PBKDF2WithHmacSHA256 is mandated by the JCA spec; unreachable on any supported JVM.
            throw new IllegalStateException("Unable to derive the key fingerprint", e);
        }
    }

    /** Whether a stored value is ciphertext (as opposed to legacy plaintext or nothing at all). */
    public static boolean isCiphertext(String stored) {
        return stored != null && stored.startsWith(PREFIX);
    }

    public boolean isEnabled() {
        return encryptor != null;
    }

    public String encrypt(String plaintext) {
        if (plaintext != null && plaintext.startsWith(PREFIX)) {
            // Encrypting a value that is already ciphertext used to be a silent no-op, which stored
            // an un-decryptable "enc:"-prefixed plaintext whenever a caller got the flow wrong.
            throw new IllegalArgumentException(
                "Refusing to encrypt a value that is already encrypted (starts with \"" + PREFIX
                    + "\").");
        }
        if (plaintext == null || plaintext.isBlank() || encryptor == null) {
            return plaintext;
        }
        return PREFIX + encryptor.encrypt(plaintext);
    }

    public String decrypt(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) {
            return stored;
        }
        if (encryptor == null) {
            log.error(
                "Encountered an encrypted secret but app.security.secret-key is not configured; cannot decrypt.");
            return "";
        }
        try {
            return encryptor.decrypt(stored.substring(PREFIX.length()));
        } catch (Exception e) {
            log.error(
                "Failed to decrypt a stored secret (wrong app.security.secret-key/secret-salt?)",
                e);
            return "";
        }
    }

    /**
     * Like {@link #decrypt(String)} but raises instead of degrading an unreadable secret to
     * {@code ""}. Use this on every path that will actually authenticate with the secret: a blank
     * credential fails far away from the cause, whereas a key mismatch is detectable right here. A
     * value without the {@value #PREFIX} prefix is legacy plaintext and is returned unchanged.
     *
     * @throws SecretDecryptionException if the value is ciphertext and cannot be decrypted
     */
    public String decryptOrThrow(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) {
            return stored;
        }
        if (encryptor == null) {
            throw new SecretDecryptionException(
                "Encountered an encrypted secret but app.security.secret-key is not configured.");
        }
        try {
            return encryptor.decrypt(stored.substring(PREFIX.length()));
        } catch (Exception e) {
            // The message carries neither the ciphertext nor any key material.
            throw new SecretDecryptionException(
                "Failed to decrypt a stored secret (wrong app.security.secret-key/secret-salt?).",
                e);
        }
    }

    /**
     * No-throw probe for read-only health checks (e.g. an admin list that must render every row
     * even when one of them was encrypted with a retired key). Legacy plaintext counts as readable.
     */
    public boolean canDecrypt(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) {
            return true;
        }
        if (encryptor == null) {
            return false;
        }
        try {
            encryptor.decrypt(stored.substring(PREFIX.length()));
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
