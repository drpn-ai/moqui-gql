import spock.lang.Specification
import spock.lang.Shared
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.gql.GqlEngine

/** Service-backed field (decision 12): Order.customerName is produced by calling
 *  GqlExampleServices.get#OrderCustomerName (BILL_TO customer display name) — not read from a column —
 *  resolved through a batched DataLoader. Verified against the service's own output on real
 *  hcsd_notnaked orders, including correct per-order grouping (no cross-talk). */
class ServiceBackedTests extends Specification {
    static final String SVC = "GqlExampleServices.get#OrderCustomerName"
    @Shared ExecutionContext ec
    @Shared String namedOrderId
    @Shared String expectedName
    @Shared List sampleOrderIds

    def setupSpec() {
        ec = Moqui.getExecutionContext(); ec.artifactExecution.disableAuthz()
        // find orders whose BILL_TO_CUSTOMER is a named Person (445/448 in hcsd_notnaked)
        def roleRows = ec.entity.find("org.apache.ofbiz.order.order.OrderRole")
                .condition("roleTypeId", "BILL_TO_CUSTOMER")
                .selectField("orderId").selectField("partyId").orderBy("orderId").maxRows(40).fetchSize(40).list()
        sampleOrderIds = []
        for (r in roleRows) {
            def d = ec.service.sync().name(SVC).parameters([orderId: r.orderId]).call()
            if (namedOrderId == null && d?.customerName) { namedOrderId = r.orderId; expectedName = d.customerName }
            if (sampleOrderIds.size() < 5) sampleOrderIds.add(r.orderId)
        }
    }
    def cleanupSpec() { if (ec != null) { ec.artifactExecution.enableAuthz(); ec.destroy() } }

    def "Order.customerName is resolved by the service, matching get#OrderCustomerName output"() {
        when:
        def r = new GqlEngine(ec).execute(
                'query Q($id:ID!){ order(orderId:$id){ orderId customerName } }', [id: namedOrderId], "Q")
        then:
        r.errors.isEmpty()
        r.data.order.customerName == expectedName
        expectedName != null && !expectedName.isEmpty()    // sanity: a real customer name flowed through
    }

    def "orders batch resolves each customerName to its own order (no service cross-talk)"() {
        given: "expected value per order, straight from the service"
        def expected = [:]
        for (oid in sampleOrderIds) {
            def d = ec.service.sync().name(SVC).parameters([orderId: oid]).call()
            expected[oid] = d?.customerName
        }
        when:
        def q = "orderId:" + sampleOrderIds.join(",")
        def r = new GqlEngine(ec).execute(
                'query Q($q:String){ orders(first:20, query:$q){ edges{ node{ orderId customerName } } } }', [q: q], "Q")
        then:
        r.errors.isEmpty()
        def nodes = r.data.orders.edges.collect { it.node }
        nodes.size() == sampleOrderIds.size()
        nodes.every { it.customerName == expected[it.orderId] }   // each order keeps its own service result
    }
}
