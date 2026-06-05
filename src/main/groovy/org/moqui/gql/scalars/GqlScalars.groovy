package org.moqui.gql.scalars

import graphql.GraphQLContext
import graphql.schema.Coercing
import graphql.schema.GraphQLScalarType

/** Custom scalars used by the SDL contract. Output-focused (read-only API): `serialize` is the path
 *  that matters; Decimal serializes to a String to preserve scale, DateTime to ISO-8601.
 *  (Not @CompileStatic: anonymous Coercing with graphql-java default methods is cleaner dynamic.) */
class GqlScalars {

    static String fmtDateTime(Object input) {
        if (input == null) return null
        if (input instanceof java.util.Date) return ((java.util.Date) input).toInstant().toString()
        if (input instanceof Number) return new java.util.Date(((Number) input).longValue()).toInstant().toString()
        return input.toString()
    }

    static String fmtDecimal(Object input) {
        if (input == null) return null
        if (input instanceof BigDecimal) return ((BigDecimal) input).toPlainString()
        return input.toString()
    }

    static final GraphQLScalarType DATE_TIME = GraphQLScalarType.newScalar()
            .name("DateTime")
            .description("ISO-8601 timestamp, e.g. 2026-05-14T09:32:00Z")
            .coercing(new Coercing() {
                Object serialize(Object input, GraphQLContext c, Locale l) { return fmtDateTime(input) }
                Object serialize(Object input) { return fmtDateTime(input) }
                Object parseValue(Object input, GraphQLContext c, Locale l) { return input }
                Object parseValue(Object input) { return input }
            })
            .build()

    static final GraphQLScalarType DECIMAL = GraphQLScalarType.newScalar()
            .name("Decimal")
            .description("Arbitrary-precision decimal serialized as a string, e.g. \"129.00\".")
            .coercing(new Coercing() {
                Object serialize(Object input, GraphQLContext c, Locale l) { return fmtDecimal(input) }
                Object serialize(Object input) { return fmtDecimal(input) }
                Object parseValue(Object input, GraphQLContext c, Locale l) { return input }
                Object parseValue(Object input) { return input }
            })
            .build()
}
