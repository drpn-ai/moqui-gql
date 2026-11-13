package org.moqui.gql.auth

import groovy.transform.CompileStatic

import java.sql.Timestamp

/**
 * DAR-BE-267: the longest a public GraphQL API key may last, from mint or rotation. Read from the
 * gql.apiKey.maxDays property (MoquiConf default 90, overridable per deployment); a missing, unparsable
 * or non-positive value falls back to the default rather than lifting the cap.
 */
@CompileStatic
class ApiKeyLifetime {
    static final int DEFAULT_MAX_DAYS = 90
    static final String MAX_DAYS_PROPERTY = "gql.apiKey.maxDays"

    static int maxDays() {
        String configured = System.getProperty(MAX_DAYS_PROPERTY)
        if (configured == null || configured.trim().isEmpty()) return DEFAULT_MAX_DAYS
        try {
            int days = Integer.parseInt(configured.trim())
            return days > 0 ? days : DEFAULT_MAX_DAYS
        } catch (NumberFormatException ignored) {
            return DEFAULT_MAX_DAYS
        }
    }

    /** The latest expiry a key minted or rotated at {@code now} may have. */
    static Timestamp latestExpireDate(Timestamp now) {
        return new Timestamp(now.time + maxDays() * 86_400_000L)
    }
}
