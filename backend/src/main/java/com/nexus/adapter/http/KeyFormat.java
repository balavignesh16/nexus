package com.nexus.adapter.http;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

/**
 * The device key format {@code nxk1_<lookupId>_<secret>} (adapter-contract.md section 9.2): a public, non-secret lookup id (12 random bytes as
 * 24 lower-case hex characters, so one indexed read finds the credential) and a 256-bit random secret (32 random bytes, 43 base64url
 * characters). Both fields have fixed lengths, so the underscore inside a base64url secret can never be mistaken for the separator. Neither
 * value contains a timestamp, counter, device id or other predictable material.
 */
public final class KeyFormat {
    public static final String PREFIX = "nxk1_";
    public static final int LOOKUP_LENGTH = 24;
    public static final int SECRET_LENGTH = 43;
    public static final int KEY_LENGTH = PREFIX.length() + LOOKUP_LENGTH + 1 + SECRET_LENGTH;

    public record Parsed(String lookupId, String secret) {
        /** Never prints the secret. */
        @Override
        public String toString() {
            return "Parsed[lookupId=" + lookupId + "]";
        }
    }

    private KeyFormat() {}

    public static String newLookupId(SecureRandom random) {
        byte[] bytes = new byte[12];
        random.nextBytes(bytes);
        StringBuilder hex = new StringBuilder(LOOKUP_LENGTH);
        for (byte b : bytes) hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        return hex.toString();
    }

    public static String newSecret(SecureRandom random) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String format(String lookupId, String secret) {
        return PREFIX + lookupId + "_" + secret;
    }

    /** Parses a presented key; anything that is not exactly the format is empty (the caller reports a generic failure). */
    public static Optional<Parsed> parse(String key) {
        if (key == null || key.length() != KEY_LENGTH || !key.startsWith(PREFIX)) return Optional.empty();
        String lookup = key.substring(PREFIX.length(), PREFIX.length() + LOOKUP_LENGTH);
        if (key.charAt(PREFIX.length() + LOOKUP_LENGTH) != '_') return Optional.empty();
        String secret = key.substring(PREFIX.length() + LOOKUP_LENGTH + 1);
        for (int i = 0; i < lookup.length(); i++) {
            char c = lookup.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return Optional.empty();
        }
        for (int i = 0; i < secret.length(); i++) {
            char c = secret.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_';
            if (!ok) return Optional.empty();
        }
        return Optional.of(new Parsed(lookup, secret));
    }
}
