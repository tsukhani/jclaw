package utils;

import org.jspecify.annotations.Nullable;
import play.Play;

import javax.crypto.Cipher;
import javax.crypto.KDF;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.HKDFParameterSpec;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Third-party credentials at rest (JCLAW-1455): AES-256-GCM under a key HKDF-SHA256 derives from the application
 * secret, a random 96-bit IV per value, and the field's name as additional authenticated data, so a ciphertext moved
 * into another field does not open. The stored form is {@link #PREFIX} then Base64 of IV, ciphertext and tag; a value
 * without the prefix is plaintext written before encryption.
 *
 * <p>While {@value #PREVIOUS_SECRET_VAR} holds the outgoing secret of a rotation, a value only that secret opens reads
 * as {@link Source#PREVIOUS}, for the caller to re-encrypt. {@link #lookup} is an HMAC-SHA256 under a second derived
 * key, for a column that is queried or kept unique by its value.
 */
public final class CredentialCipher {

    public static final String PREFIX = "enc:v1:";

    /** The environment variable, or system property, that carries the outgoing secret during a rotation. */
    public static final String PREVIOUS_SECRET_VAR = "PLAY_SECRET_PREVIOUS";

    private static final byte[] KEY_INFO = "jclaw.credentials.v1".getBytes(UTF_8);
    private static final byte[] LOOKUP_INFO = "jclaw.credentials.lookup.v1".getBytes(UTF_8);
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Where a stored value opened. */
    public enum Source { PLAINTEXT, CURRENT, PREVIOUS, UNREADABLE }

    /** A stored value as read: where it opened, and its plaintext unless {@link Source#UNREADABLE}. */
    public record Opened(Source source, @Nullable String plaintext) {

        public boolean readable() {
            return source != Source.UNREADABLE;
        }

        /** The plaintext of a readable value. */
        public String resolvedPlaintext() {
            if (plaintext == null) throw new IllegalStateException("the stored value did not decrypt");
            return plaintext;
        }
    }

    private static final Opened UNREADABLE = new Opened(Source.UNREADABLE, null);

    /**
     * One boot pass over the stored credentials: what it sealed from plaintext, re-sealed from the previous secret, and
     * could not open. Running it again over its own output changes nothing.
     */
    public static final class Reseal {

        private final CredentialCipher cipher;
        private int sealed;
        private int rekeyed;
        private int unreadable;

        public Reseal(CredentialCipher cipher) {
            this.cipher = cipher;
        }

        /**
         * {@code stored} as the current secret seals it, or null when it needs no write: already sealed so, empty, or
         * opened by no secret, which is left for the right one to recover.
         */
        public @Nullable String reseal(String field, String stored) {
            if (stored.isEmpty()) return null;
            var opened = cipher.open(field, stored);
            return switch (opened.source()) {
                case PLAINTEXT -> {
                    sealed++;
                    yield cipher.encrypt(field, stored);
                }
                case PREVIOUS -> {
                    rekeyed++;
                    yield cipher.encrypt(field, opened.resolvedPlaintext());
                }
                case UNREADABLE -> {
                    unreadable++;
                    yield null;
                }
                case CURRENT -> null;
            };
        }

        public int sealed() {
            return sealed;
        }

        public int rekeyed() {
            return rekeyed;
        }

        public int unreadable() {
            return unreadable;
        }
    }

    private record Keys(SecretKey cipher, SecretKey lookup) {}

    private record Installed(String secret, @Nullable String previousSecret, CredentialCipher cipher) {}

    private static volatile @Nullable Installed installed;

    private final Keys current;
    private final @Nullable Keys previous;

    private CredentialCipher(Keys current, @Nullable Keys previous) {
        this.current = current;
        this.previous = previous;
    }

    /** A cipher over {@code secret}, opening values {@code previousSecret} sealed as well when it is set. */
    public static CredentialCipher of(String secret, @Nullable String previousSecret) {
        var previousKeys = previousSecret == null || previousSecret.isBlank() || previousSecret.equals(secret)
                ? null : derive(previousSecret);
        return new CredentialCipher(derive(secret), previousKeys);
    }

    /**
     * This installation's cipher: {@code application.secret}, plus {@value #PREVIOUS_SECRET_VAR} when set. The keys are
     * derived once and kept until either secret changes.
     */
    public static CredentialCipher current() {
        var secret = Play.secretKey;
        var previousSecret = previousSecret();
        var held = installed;
        if (held != null && held.secret().equals(secret) && Objects.equals(held.previousSecret(), previousSecret)) {
            return held.cipher();
        }
        var cipher = of(secret, previousSecret);
        installed = new Installed(secret, previousSecret, cipher);
        return cipher;
    }

    /** Resolved as Play resolves {@code ${PLAY_SECRET}}: a system property first, then the environment. */
    private static @Nullable String previousSecret() {
        var value = System.getProperty(PREVIOUS_SECRET_VAR);
        if (value == null) value = System.getenv(PREVIOUS_SECRET_VAR);
        return value == null || value.isBlank() ? null : value.trim();
    }

    public static boolean isEncrypted(@Nullable String stored) {
        return stored != null && stored.startsWith(PREFIX);
    }

    public boolean hasPrevious() {
        return previous != null;
    }

    /** {@code plaintext} sealed for {@code field} under the current secret. */
    public String encrypt(String field, String plaintext) {
        var iv = new byte[IV_BYTES];
        RANDOM.nextBytes(iv);
        try {
            var cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, current.cipher(), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(field.getBytes(UTF_8));
            var sealed = cipher.doFinal(plaintext.getBytes(UTF_8));
            var out = ByteBuffer.allocate(IV_BYTES + sealed.length).put(iv).put(sealed).array();
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM is unavailable", e);
        }
    }

    /** What {@code stored} holds for {@code field}. Never throws: a value neither secret opens is unreadable. */
    public Opened open(String field, String stored) {
        if (!isEncrypted(stored)) return new Opened(Source.PLAINTEXT, stored);
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
        } catch (IllegalArgumentException _) {
            return UNREADABLE;
        }
        if (bytes.length < IV_BYTES + TAG_BITS / 8) return UNREADABLE;
        var plaintext = decrypt(current.cipher(), field, bytes);
        if (plaintext != null) return new Opened(Source.CURRENT, plaintext);
        var previousKeys = previous;
        if (previousKeys != null) {
            plaintext = decrypt(previousKeys.cipher(), field, bytes);
            if (plaintext != null) return new Opened(Source.PREVIOUS, plaintext);
        }
        return UNREADABLE;
    }

    /** HMAC-SHA256 of {@code value} under the current secret's lookup key, as lowercase hex. */
    public String lookup(String value) {
        return hmac(current.lookup(), value);
    }

    /** {@link #lookup} under the previous secret, or null when none is set. */
    public @Nullable String previousLookup(String value) {
        var previousKeys = previous;
        return previousKeys == null ? null : hmac(previousKeys.lookup(), value);
    }

    /** Every lookup a row may hold for {@code value}: the previous secret's too, for a row the boot pass has not re-keyed. */
    public List<String> lookups(String value) {
        var previousLookup = previousLookup(value);
        return previousLookup == null ? List.of(lookup(value)) : List.of(lookup(value), previousLookup);
    }

    private static Keys derive(String secret) {
        if (secret.isBlank()) throw new IllegalArgumentException("no application secret to derive credential keys from");
        var ikm = secret.getBytes(UTF_8);
        try {
            var hkdf = KDF.getInstance("HKDF-SHA256");
            return new Keys(
                    hkdf.deriveKey("AES", HKDFParameterSpec.ofExtract().addIKM(ikm).thenExpand(KEY_INFO, KEY_BYTES)),
                    hkdf.deriveKey("HmacSHA256",
                            HKDFParameterSpec.ofExtract().addIKM(ikm).thenExpand(LOOKUP_INFO, KEY_BYTES)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HKDF-SHA256 is unavailable", e);
        }
    }

    // Any failure reads as unreadable: a boot pass or a turn that reads a credential must never throw on one.
    private static @Nullable String decrypt(SecretKey key, String field, byte[] bytes) {
        try {
            var cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES));
            cipher.updateAAD(field.getBytes(UTF_8));
            return new String(cipher.doFinal(bytes, IV_BYTES, bytes.length - IV_BYTES), UTF_8);
        } catch (GeneralSecurityException _) {
            return null;
        }
    }

    private static String hmac(SecretKey key, String value) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", e);
        }
    }
}
