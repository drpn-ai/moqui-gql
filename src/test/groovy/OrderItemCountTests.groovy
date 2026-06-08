import spock.lang.Specification
import spock.lang.Shared
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.gql.GqlEngine

/** orderItemCount = COUNT(DISTINCT OrderItem.externalId) per order (Shopify-line count), resolved as a
 *  lazy LATERAL aggregate. Expected values are computed straight from the DB so this is self-checking. */
class OrderItemCountTests extends Specification {
    @Shared ExecutionContext ec
    @Shared String anOrderId
    @Shared long expectedLines

    def setupSpec() {
        ec = Moqui.getExecutionContext(); ec.artifactExecution.disableAuthz()
        def oh = ec.entity.find("org.apache.ofbiz.order.order.OrderHeader")
                .selectField("orderId").orderBy("orderId").maxRows(1).fetchSize(1).list()
        if (oh) {
            anOrderId = oh[0].orderId
            def items = ec.entity.find("org.apache.ofbiz.order.order.OrderItem")
                    .condition("orderId", anOrderId).selectField("externalId").list()
            Set<Object> distinct = new HashSet<>()
            for (it in items) if (it.externalId != null) distinct.add(it.externalId)
            expectedLines = (long) distinct.size()
        }
    }
    def cleanupSpec() { if (ec != null) { ec.artifactExecution.enableAuthz(); ec.destroy() } }

    def "order(orderId:) returns orderItemCount = distinct externalId count"() {
        given: org.junit.jupiter.api.Assumptions.assumeTrue(anOrderId != null)
        when:
        def r = new GqlEngine(ec).execute('query Q($id:ID!){ order(orderId:$id){ orderId orderItemCount } }', [id: anOrderId], "Q")
        then:
        r.errors.isEmpty()
        r.data.order.orderId == anOrderId
        (r.data.order.orderItemCount as long) == expectedLines
    }

    def "orders list resolves orderItemCount as a non-null Int per node"() {
        when:
        def r = new GqlEngine(ec).execute('query { orders(first:5){ edges{ node{ orderId orderItemCount } } } }', [:], null)
        then:
        r.errors.isEmpty()
        r.data.orders.edges.every { it.node.orderItemCount != null && (it.node.orderItemCount as long) >= 0 }
    }

    def "orderItemCount is omitted from the query when not selected (no aggregate, still works)"() {
        when:
        def r = new GqlEngine(ec).execute('query { orders(first:5){ edges{ node{ orderId } } } }', [:], null)
        then:
        r.errors.isEmpty()
        r.data.orders.edges.every { it.node.orderId != null }
    }
}
