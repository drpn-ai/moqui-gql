package org.moqui.gql

import groovy.transform.CompileStatic

/** Pre-execution validation failure with a stable, agent-actionable error code
 *  (FIELD_NOT_FILTERABLE, OPERATOR_NOT_ALLOWED, MALFORMED_QUERY, ...). The governor (C4) maps these
 *  to graphql-java GraphQLErrors with the code in `extensions`. */
@CompileStatic
class GqlValidationException extends RuntimeException {
    final String code
    final Map<String, Object> ext

    GqlValidationException(String code, String message, Map<String, Object> ext) {
        super(message)
        this.code = code
        this.ext = ext != null ? ext : new LinkedHashMap<String, Object>()
    }
}
