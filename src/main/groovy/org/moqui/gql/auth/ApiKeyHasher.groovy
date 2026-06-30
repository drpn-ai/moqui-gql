package org.moqui.gql.auth

import groovy.transform.CompileStatic

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * SHA-256 hashing + key-minting helper for the public GraphQL API-key realm.
 *
 * SECURITY: the raw API key is NEVER stored. Only its SHA-256 hex digest (from {@link #sha256Hex}) is
 * persisted, and resolution looks the key up BY that hash (never a raw compare or like/substring match),
 * which also avoids timing leaks on the raw key. Uses only JDK primitives (MessageDigest, SecureRandom,
 * Base64) — no new dependencies. Pure/static so it is unit-testable without a Moqui boot.
 */
@CompileStatic
class ApiKeyHasher {
    /** Prefix on every minted raw key, for identification (the prefix is part of the hashed input). */
    static final String KEY_PREFIX = "dgql_"
    /** Raw entropy in bytes (256 bits). */
    private static final int RAW_BYTES = 32
    private static final SecureRandom SECURE_RANDOM = new SecureRandom()

    /** SHA-256 hex digest of the input. A null input hashes the empty string (still a valid, non-matching
     *  digest) — callers MUST reject blank keys before hashing; this never returns null. */
    static String sha256Hex(String s) {
        byte[] d = MessageDigest.getInstance("SHA-256").digest((s ?: "").getBytes("UTF-8"))
        StringBuilder sb = new StringBuilder(d.length * 2)
        for (byte b in d) sb.append(String.format("%02x", b))
        return sb.toString()
    }

    /** Generate a fresh cryptographically-random raw key: KEY_PREFIX + URL-safe base64 of 256 random bits. */
    static String generateRawKey() {
        byte[] raw = new byte[RAW_BYTES]
        SECURE_RANDOM.nextBytes(raw)
        return KEY_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
    }
}
