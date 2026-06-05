import spock.lang.Specification
import spock.lang.Shared
import org.moqui.context.ExecutionContext
import org.moqui.Moqui

/** EC-backed: the tool factory scans graphql/*.gql.xml, builds the schema, and caches it at startup. */
class GqlToolFactoryTests extends Specification {
    @Shared ExecutionContext ec

    def setupSpec() { ec = Moqui.getExecutionContext(); ec.artifactExecution.disableAuthz() }
    def cleanupSpec() { if (ec != null) { ec.artifactExecution.enableAuthz(); ec.destroy() } }

    def "GraphQL tool factory builds + caches the schema from the component artifact"() {
        when:
        def built = ec.factory.getToolFactory("GraphQL").getInstance()
        then:
        built != null
        built.schema.getObjectType("Order") != null
        built.schema.getObjectType("OrderItem") != null
        built.schema.getQueryType().getFieldDefinition("orders") != null
        built.schema.getQueryType().getFieldDefinition("order").getArgument("externalId") != null
        built.costModel.listFields.contains("orders")
        // cached: same instance each call
        built.is(ec.factory.getToolFactory("GraphQL").getInstance())
    }
}
