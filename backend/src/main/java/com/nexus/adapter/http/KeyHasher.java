package com.nexus.adapter.http;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Map;
import java.util.function.Function;

/**
 * {@code HMAC-SHA-256(pepper, secret)} (adapter-contract.md sections 9 and 12): a fast keyed one-way hash is enough because the secret is 256
 * bits of randomness, and the pepper (from the environment, never the database, the repository or a log) means a database leak alone does not
 * allow offline verification. Comparison is constant time. Used by the backend and by the administration CLI, so both compute the same value.
 */
public class KeyHasher {
    public static final String ALGORITHM = "HMAC-SHA256";
    public static final String PEPPER_ENV = "NEXUS_DEVICE_KEY_PEPPER";
    public static final String PEPPER_FILE_ENV = "NEXUS_DEVICE_KEY_PEPPER_FILE";
    public static final int MIN_PEPPER_BYTES = 16;

    private final byte[] pepper;

    public KeyHasher(byte[] pepper) {
        if (pepper == null || pepper.length < MIN_PEPPER_BYTES) {
            throw new IllegalStateException("the device-key pepper is missing or shorter than " + MIN_PEPPER_BYTES + " bytes; credentials cannot be used");
        }
        this.pepper = pepper.clone();
    }

    /**
     * Reads the pepper: {@code NEXUS_DEVICE_KEY_PEPPER}, or the file named by {@code NEXUS_DEVICE_KEY_PEPPER_FILE}. Without either there is no hasher,
     * so neither the backend nor the tool can start credential work (fail closed). The error never contains pepper material.
     */
    public static KeyHasher fromEnvironment(Function<String, String> env) {
        String direct = env.apply(PEPPER_ENV);
        if (direct != null && !direct.isBlank()) return new KeyHasher(direct.strip().getBytes(StandardCharsets.UTF_8));
        String file = env.apply(PEPPER_FILE_ENV);
        if (file != null && !file.isBlank()) {
            try {
                return new KeyHasher(Files.readString(Path.of(file.strip()), StandardCharsets.UTF_8).strip().getBytes(StandardCharsets.UTF_8));
            } catch (java.io.IOException e) {
                throw new IllegalStateException("the device-key pepper file cannot be read; credentials cannot be used");
            }
        }
        throw new IllegalStateException("no device-key pepper: set " + PEPPER_ENV + " or " + PEPPER_FILE_ENV + "; credentials cannot be used");
    }

    public static KeyHasher fromEnvironment(Map<String, String> env) {
        return fromEnvironment(env::get);
    }

    public byte[] hash(String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper, "HmacSHA256"));
            return mac.doFinal(secret.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is required by the JDK", e);
        }
    }

    /** Constant-time comparison of the stored hash with the hash of the presented secret. */
    public boolean matches(byte[] stored, String presentedSecret) {
        return MessageDigest.isEqual(stored, hash(presentedSecret));
    }
}
