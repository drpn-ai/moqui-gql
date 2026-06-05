import spock.lang.Specification
import spock.lang.Shared
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.gql.GqlEngine

/** New entity-backed root: shipments. hcsd_notnaked has 0 shipments until the C6 generator seeds
 *  them, so these assert the root wires up and handles the empty case cleanly; C6 adds data-bearing
 *  cases. Written to stay green both before and after seeding. */
class ShipmentRootTests extends Specification {
    @Shared ExecutionContext ec

    def setupSpec() { ec = Moqui.getExecutionContext(); ec.artifactExecution.disableAuthz() }
    def cleanupSpec() { if (ec != null) { ec.artifactExecution.enableAuthz(); ec.destroy() } }

    def "shipments root resolves to a Relay connection (empty now, data after C6 seeding)"() {
        when:
        def r = new GqlEngine(ec).execute(
                'query { shipments(first:5){ edges{ cursor node{ shipmentId statusId } } pageInfo{ hasNextPage } } }', [:], null)
        then:
        r.errors.isEmpty()
        r.data.shipments != null
        r.data.shipments.edges instanceof List
        r.data.shipments.edges.every { it.node.shipmentId != null }
    }

    def "shipment(shipmentId:) returns null for a non-existent id without error"() {
        when:
        def r = new GqlEngine(ec).execute('query { shipment(shipmentId:"__none__"){ shipmentId } }', [:], null)
        then:
        r.errors.isEmpty()
        r.data.shipment == null
    }

    def "shipments query: filters by declared keys without error (statusId)"() {
        when:
        def r = new GqlEngine(ec).execute(
                'query { shipments(first:5, query:"statusId:SHIPMENT_SHIPPED", sortKey: SHIPPED_DATE){ edges{ node{ shipmentId } } } }', [:], null)
        then:
        r.errors.isEmpty()
        r.data.shipments.edges instanceof List
    }
}
