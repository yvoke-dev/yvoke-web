package de.palsoftware.yvoke.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

class SecretCipherTest {

    private static final String SALT = "5c0744940b5c369b";

    private static Environment devEnv() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("test");
        return env;
    }

    private static Environment prodEnv() {
        return new MockEnvironment(); // no active profile == not a dev box
    }

    @Test
    void encryptThenDecryptRoundTripsWhenKeyConfigured() {
        SecretCipher cipher = new SecretCipher("a-strong-secret-key", SALT, prodEnv());
        assertThat(cipher.isEnabled()).isTrue();

        String encrypted = cipher.encrypt("hunter2");
        assertThat(encrypted).startsWith("enc:").isNotEqualTo("hunter2");
        assertThat(cipher.decrypt(encrypted)).isEqualTo("hunter2");
    }

    @Test
    void encryptIsNoOpForBlankValues() {
        SecretCipher cipher = new SecretCipher("a-strong-secret-key", SALT, prodEnv());
        assertThat(cipher.encrypt("")).isEmpty();
        assertThat(cipher.encrypt(null)).isNull();
    }

    @Test
    void encryptRejectsAlreadyEncryptedValue() {
        // Silently returning the input stored an un-decryptable value under an "enc:" prefix as
        // soon
        // as any caller double-encrypted; the caller must decide, not the cipher.
        SecretCipher cipher = new SecretCipher("a-strong-secret-key", SALT, prodEnv());
        String once = cipher.encrypt("token");
        assertThatThrownBy(() -> cipher.encrypt(once)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("already encrypted");
    }

    @Test
    void encryptRejectsAlreadyEncryptedValueEvenWithoutKey() {
        SecretCipher cipher = new SecretCipher("", SALT, devEnv());
        assertThatThrownBy(() -> cipher.encrypt("enc:whatever"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void decryptReturnsLegacyPlaintextUnchanged() {
        SecretCipher cipher = new SecretCipher("a-strong-secret-key", SALT, prodEnv());
        assertThat(cipher.decrypt("plain-legacy-value")).isEqualTo("plain-legacy-value");
    }

    @Test
    void passThroughModeWhenKeyMissingUnderDevProfile() {
        SecretCipher cipher = new SecretCipher("", SALT, devEnv());
        assertThat(cipher.isEnabled()).isFalse();
        assertThat(cipher.encrypt("token")).isEqualTo("token"); // stored in plaintext (dev only)
        assertThat(cipher.decrypt("plain")).isEqualTo("plain");
        // An encrypted value cannot be read without a key — fail closed to empty, not leak
        // ciphertext.
        assertThat(cipher.decrypt("enc:whatever")).isEmpty();
    }

    @Test
    void missingKeyOutsideDevProfileFailsClosed() {
        // SEC-05: refuse to start (rather than persist plaintext) when no key is set in a real env.
        assertThatThrownBy(() -> new SecretCipher("", SALT, prodEnv()))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("secret-key");
    }

    @Test
    void placeholderKeyOutsideDevProfileFailsClosed() {
        assertThatThrownBy(() -> new SecretCipher("placeholder-secret-key", SALT, prodEnv()))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("secret-key");
    }

    @Test
    void placeholderKeyUnderDevProfileIsDisabled() {
        SecretCipher cipher = new SecretCipher("placeholder-secret-key", SALT, devEnv());
        assertThat(cipher.isEnabled()).isFalse();
    }

    @Test
    void configuredKeyWithoutSaltFailsClosed() {
        // SEC-15: a key with no per-deployment salt must not fall back to a shared default.
        assertThatThrownBy(() -> new SecretCipher("a-strong-secret-key", "", prodEnv()))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("secret-salt");
    }

    @Test
    void decryptWithWrongKeyFailsClosed() {
        String encrypted = new SecretCipher("key-one", SALT, prodEnv()).encrypt("secret");
        String wrongKeyResult = new SecretCipher("key-two", SALT, prodEnv()).decrypt(encrypted);
        assertThat(wrongKeyResult).isEmpty();
    }

    @Test
    void decryptOrThrowRoundTrips() {
        SecretCipher cipher = new SecretCipher("a-strong-secret-key", SALT, prodEnv());
        assertThat(cipher.decryptOrThrow(cipher.encrypt("hunter2"))).isEqualTo("hunter2");
    }

    @Test
    void decryptOrThrowPassesLegacyPlaintextThrough() {
        SecretCipher cipher = new SecretCipher("a-strong-secret-key", SALT, prodEnv());
        assertThat(cipher.decryptOrThrow("plain-legacy-value")).isEqualTo("plain-legacy-value");
        assertThat(cipher.decryptOrThrow(null)).isNull();
    }

    @Test
    void decryptOrThrowRaisesForCiphertextFromAnotherKey() {
        // Encryptors.delux is authenticated AES-GCM: a wrong key always raises. Turning that signal
        // into "" is how one key rotation becomes N silently blank credentials.
        String encrypted = new SecretCipher("key-one", SALT, prodEnv()).encrypt("secret");
        SecretCipher other = new SecretCipher("key-two", SALT, prodEnv());
        assertThatThrownBy(() -> other.decryptOrThrow(encrypted))
            .isInstanceOf(SecretDecryptionException.class);
    }

    @Test
    void decryptOrThrowRaisesForCiphertextFromAnotherSalt() {
        String encrypted = new SecretCipher("same-key", SALT, prodEnv()).encrypt("secret");
        SecretCipher other = new SecretCipher("same-key", "9f2b1a4c8e0d6357", prodEnv());
        assertThatThrownBy(() -> other.decryptOrThrow(encrypted))
            .isInstanceOf(SecretDecryptionException.class);
    }

    @Test
    void decryptOrThrowRaisesWhenNoKeyIsConfigured() {
        SecretCipher cipher = new SecretCipher("", SALT, devEnv());
        assertThatThrownBy(() -> cipher.decryptOrThrow("enc:whatever"))
            .isInstanceOf(SecretDecryptionException.class);
        // ...but plaintext is still readable in the key-less dev mode.
        assertThat(cipher.decryptOrThrow("plain")).isEqualTo("plain");
    }

    @Test
    void canDecryptProbesWithoutThrowing() {
        SecretCipher owner = new SecretCipher("key-one", SALT, prodEnv());
        String encrypted = owner.encrypt("secret");

        assertThat(owner.canDecrypt(encrypted)).isTrue();
        assertThat(owner.canDecrypt("plain-legacy-value")).isTrue();
        assertThat(owner.canDecrypt(null)).isTrue();
        // The admin list must render a row with a foreign ciphertext, not blow up on it.
        assertThat(new SecretCipher("key-two", SALT, prodEnv()).canDecrypt(encrypted)).isFalse();
        assertThat(new SecretCipher("", SALT, devEnv()).canDecrypt(encrypted)).isFalse();
    }

    @Test
    void keyIdIsStableForTheSameKeyMaterial() {
        String first = new SecretCipher("a-strong-secret-key", SALT, prodEnv()).keyId();
        String second = new SecretCipher("a-strong-secret-key", SALT, prodEnv()).keyId();
        assertThat(first).isNotNull().hasSize(16).isEqualTo(second);
    }

    @Test
    void keyIdDiffersForDifferentSaltOrKey() {
        String base = new SecretCipher("a-strong-secret-key", SALT, prodEnv()).keyId();
        assertThat(new SecretCipher("a-strong-secret-key", "9f2b1a4c8e0d6357", prodEnv()).keyId())
            .isNotEqualTo(base);
        assertThat(new SecretCipher("another-secret-key", SALT, prodEnv()).keyId())
            .isNotEqualTo(base);
    }

    @Test
    void keyIdNeverLeaksTheKeyMaterial() {
        String keyId = new SecretCipher("a-strong-secret-key", SALT, prodEnv()).keyId();
        assertThat(keyId).doesNotContain("a-strong-secret-key").doesNotContain(SALT)
            .matches("[0-9a-f]{16}");
    }

    @Test
    void keyIdIsNullWhenEncryptionIsDisabled() {
        assertThat(new SecretCipher("", SALT, devEnv()).keyId()).isNull();
    }

    @Test
    void keyIdIsNotACheapHashOfTheKeyMaterial() {
        // The fingerprint is PERSISTED (confluence_instances.token_key_id) and survives ciphertext
        // rotation, so a single-pass digest of the key would be a free offline oracle: one SHA-256
        // per candidate key instead of the KDF cost of attacking the ciphertext itself. Pin that
        // the obvious cheap constructions do not reproduce it.
        String key = "a-strong-secret-key";
        String keyId = new SecretCipher(key, SALT, prodEnv()).keyId();

        assertThat(keyId).isNotEqualTo(sha256Hex(key + ":" + SALT).substring(0, 16));
        assertThat(keyId).isNotEqualTo(sha256Hex(key + SALT).substring(0, 16));
        assertThat(keyId).isNotEqualTo(sha256Hex(key).substring(0, 16));
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void malformedCiphertextIsHandledLikeAnUnreadableSecret() {
        // "enc:" only marks the value as encrypted; the payload can be truncated, non-hex or
        // garbage after a bad copy/paste or a partial restore. Every read path must degrade or
        // raise deliberately instead of leaking a decoder exception up the stack.
        SecretCipher cipher = new SecretCipher("a-strong-secret-key", SALT, prodEnv());
        String malformed = "enc:not-hex-at-all!!";

        assertThat(cipher.decrypt(malformed)).isEmpty();
        assertThat(cipher.canDecrypt(malformed)).isFalse();
        assertThatThrownBy(() -> cipher.decryptOrThrow(malformed))
            .isInstanceOf(SecretDecryptionException.class).hasMessageNotContaining(malformed);
    }
}
