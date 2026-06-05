import spock.lang.Specification
import spock.lang.Shared
import org.moqui.context.ExecutionContext
import org.moqui.Moqui

/** C1 scaffold smoke test: proves the component loads, graphql-java 25.0 is on the classpath,
 *  and the EC boots against the populated MySQL `hcsd_notnaked` DB (real order data present). */
class ScaffoldSmokeTests extends Specification {
    @Shared ExecutionContext ec

    def setupSpec() {
        ec = Moqui.getExecutionContext()
        ec.artifactExecution.disableAuthz()
    }
    def cleanupSpec() {
        if (ec != null) { ec.artifactExecution.enableAuthz(); ec.destroy() }
    }

    def "graphql-java 25.0 + java-dataloader are on the classpath"() {
        expect:
        Class.forName("graphql.schema.GraphQLSchema") != null
        Class.forName("graphql.GraphQL") != null
        Class.forName("org.dataloader.DataLoader") != null
    }

    def "moqui-gql component is loaded"() {
        expect:
        ec.factory.getComponentBaseLocations().containsKey("moqui-gql")
    }

    def "booted against populated MySQL hcsd_notnaked (real order data present)"() {
        when:
        long orderCount = ec.entity.find("org.apache.ofbiz.order.order.OrderHeader").count()
        then:
        orderCount > 0
    }
}
