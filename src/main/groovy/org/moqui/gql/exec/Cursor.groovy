package org.moqui.gql.exec

import groovy.transform.CompileStatic

import java.nio.charset.StandardCharsets
import java.sql.Timestamp

/**
 * Opaque, type-tagged keyset cursor for Relay connections. Encodes the row's sort-field value plus
 * its full primary key (one or many fields) so the next page resumes immediately after it — keyset
 * pagination, no OFFSET, so deep pages stay flat-cost. Supports composite PKs (e.g. view entities,
 * OrderItem) which the keyset predicate compares lexicographically.
 *
 * Wire form: base64url( typeTag ':' base64url(sortValue) ':' base64url(pk1) [':' base64url(pkN)...] )
 * Inner components are themselves base64url (alphabet excludes ':'), so they can never contain the
 * delimiter; split(':', -1) round-trips empty values positionally.
 *
 * typeTag drives how the sort value is re-typed for the predicate: "T" timestamp (epoch millis,
 * exact), "N" number/decimal, "S" string (default). PK values are decoded as Strings; the resolver
 * re-types each via the entity field definition so numeric PKs compare numerically.
 */
@CompileStatic
class Cursor {
    String typeTag
    String sortValue
    List<String> pkValues = new ArrayList<String>()

    static String encode(Object sortVal, List<Object> pkVals) {
        String tag, sv
        if (sortVal instanceof Timestamp) { tag = "T"; sv = Long.toString(((Timestamp) sortVal).getTime()) }
        else if (sortVal instanceof Date) { tag = "T"; sv = Long.toString(((Date) sortVal).getTime()) }
        else if (sortVal instanceof Number) { tag = "N"; sv = sortVal.toString() }
        else { tag = "S"; sv = sortVal != null ? sortVal.toString() : "" }
        StringBuilder payload = new StringBuilder(tag).append(':').append(b64(sv))
        for (Object pv in pkVals) payload.append(':').append(b64(pv != null ? pv.toString() : ""))
        return b64(payload.toString())
    }

    static Cursor decode(String cursor) {
        if (cursor == null || cursor.isEmpty()) throw new IllegalArgumentException("empty cursor")
        String payload
        try { payload = unb64(cursor) } catch (Exception e) { throw new IllegalArgumentException("malformed cursor", e) }
        String[] parts = payload.split(":", -1)
        if (parts.length < 2) throw new IllegalArgumentException("malformed cursor")
        Cursor c = new Cursor(typeTag: parts[0], sortValue: unb64(parts[1]))
        for (int i = 2; i < parts.length; i++) c.pkValues.add(unb64(parts[i]))
        return c
    }

    /** Re-type the encoded sort value for the keyset predicate (exact for timestamps via epoch millis). */
    Object sortValueTyped() {
        switch (typeTag) {
            case "T": return new Timestamp(Long.parseLong(sortValue))
            case "N": return new BigDecimal(sortValue)
            default: return sortValue
        }
    }

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((s ?: "").getBytes(StandardCharsets.UTF_8))
    }
    private static String unb64(String s) {
        return new String(Base64.getUrlDecoder().decode(s), StandardCharsets.UTF_8)
    }
}
