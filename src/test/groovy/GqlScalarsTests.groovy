import spock.lang.Specification
import org.moqui.gql.scalars.GqlScalars
import graphql.GraphQLContext

/** Pure unit test (no EC) for the custom scalars' serialization. */
class GqlScalarsTests extends Specification {

    def "DateTime serializes to ISO-8601; Decimal serializes BigDecimal to a string preserving scale"() {
        given:
        def ctx = GraphQLContext.newContext().build()
        def loc = Locale.US
        expect:
        GqlScalars.DATE_TIME.coercing.serialize(java.sql.Timestamp.valueOf("2026-05-14 09:32:00"), ctx, loc) ==~ /\d{4}-\d{2}-\d{2}T.*/
        GqlScalars.DECIMAL.coercing.serialize(new BigDecimal("129.00"), ctx, loc) == "129.00"
        GqlScalars.DECIMAL.coercing.serialize(new BigDecimal("7"), ctx, loc) == "7"
    }
}
