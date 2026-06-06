import spock.lang.Specification
import spock.lang.Shared
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.gql.GqlEngine

/** Service-backed field (decision 12): Order.itemCount is produced by GqlExampleServices.get#OrderItemCount
 *  (an aggregation — count of order items), resolved through a batched DataLoader. Verified against direct
 *  entity counts on real hcsd_notnaked orders, including correct per-order grouping (no cross-talk). */
class ServiceBackedTests extends Specification {
    @Shared ExecutionContext ec
    @Shared List sampleOrderIds

    def setupSpec() {
        ec = Moqui.getExecutionContext(); ec.artifactExecution.disableAuthz()
        // orders that actually have items, so counts are non-zero and meaningful
        sampleOrderIds = []
        def oi = ec.entity.find("org.apache.ofbiz.order.order.OrderItem")
                .selectField("orderId").distinct(true).orderBy("orderId").maxRows(5).fetchSize(5).list()
        for (r in oi) sampleOrderIds.add(r.orderId)
    }
    def cleanupSpec() { if (ec != null) { ec.artifactExecution.enableAuthz(); ec.destroy() } }

    private long directCount(String orderId) {
        return ec.entity.find("org.apache.ofbiz.order.order.OrderItem").condition("orderId", orderId).count()
    }

    def "Order.itemCount is resolved by the service, matching a direct entity count"() {
        given:
        def oid = sampleOrderIds.get(0)
        def expected = directCount(oid)
        when:
        def r = new GqlEngine(ec).execute('query Q($id:ID!){ order(orderId:$id){ orderId itemCount } }', [id: oid], "Q")
        then:
        r.errors.isEmpty()
        r.data.order.itemCount == expected
        expected > 0
    }

    def "orders batch resolves each itemCount to its own order (no service cross-talk)"() {
        given:
        def expected = [:]
        for (oid in sampleOrderIds) expected[oid] = directCount(oid)
        when:
        def q = "orderId:" + sampleOrderIds.join(",")
        def r = new GqlEngine(ec).execute(
                'query Q($q:String){ orders(first:20, query:$q){ edges{ node{ orderId itemCount } } } }', [q: q], "Q")
        then:
        r.errors.isEmpty()
        def nodes = r.data.orders.edges.collect { it.node }
        nodes.size() == sampleOrderIds.size()
        nodes.every { it.itemCount == expected[it.orderId] }   // each order keeps its own service result
    }
}
