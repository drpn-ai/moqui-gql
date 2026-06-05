import spock.lang.Specification
import spock.lang.Shared
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.entity.EntityCondition
import org.moqui.gql.GqlEngine
import org.moqui.gql.scope.ScopeFilter
import org.moqui.gql.scope.ScopeFilters

/** C6 — the row-scope seam (phase-2 multi-tenant hook). Proves it is live: consulted on every entity
 *  find, ANDs a condition when one is returned, and — via the per-find counter — that nested edges are
 *  DataLoader-batched (one child find per level, no N+1). One DB per client today, so it is a no-op. */
class ScopeSeamTests extends Specification {
    @Shared ExecutionContext ec
    @Shared String sampleOrderId

    def setupSpec() {
        ec = Moqui.getExecutionContext(); ec.artifactExecution.disableAuthz()
        sampleOrderId = ec.entity.find("org.apache.ofbiz.order.order.OrderHeader")
                .selectField("orderId").orderBy("orderId").maxRows(1).fetchSize(1).list().get(0).orderId
    }
    def cleanupSpec() { if (ec != null) { ScopeFilters.reset(); ec.artifactExecution.enableAuthz(); ec.destroy() } }
    def cleanup() { ScopeFilters.reset() }   // never leak the injected filter into other suite classes

    def "ScopeFilter.conditionFor is invoked for every entity find, and nested edges are batched (no N+1)"() {
        given: "a no-op filter that records the entity of each find"
        def seen = Collections.synchronizedList([])
        ScopeFilters.set({ String en, ec2 -> seen.add(en); return null } as ScopeFilter)
        when: "one order with its (batched) order items"
        def r = new GqlEngine(ec).execute(
                'query Q($id:ID!){ order(orderId:$id){ orderId orderItems(first:5){ edges{ node{ orderId } } } } }',
                [id: sampleOrderId], "Q")
        then:
        r.errors.isEmpty()
        seen.contains("org.apache.ofbiz.order.order.OrderHeader")
        seen.contains("org.apache.ofbiz.order.order.OrderItem")
    }

    def "nested edges batch to ONE child find per level regardless of parent count"() {
        given:
        def seen = Collections.synchronizedList([])
        ScopeFilters.set({ String en, ec2 -> seen.add(en); return null } as ScopeFilter)
        when: "5 orders, each with order items"
        def r = new GqlEngine(ec).execute(
                'query { orders(first:5){ edges{ node{ orderId orderItems(first:5){ edges{ node{ orderId } } } } } } }', [:], null)
        then:
        r.errors.isEmpty()
        seen.count { it == "org.apache.ofbiz.order.order.OrderHeader" } == 1   // one root find
        seen.count { it == "org.apache.ofbiz.order.order.OrderItem" } == 1     // ONE batched child find, not 5
    }

    def "a scoping condition is ANDed into finds (the seam restricts rows)"() {
        given: "a filter restricting OrderHeader to a single orderId"
        ScopeFilters.set({ String en, ec2 ->
            en == "org.apache.ofbiz.order.order.OrderHeader" ?
                    ec.entity.conditionFactory.makeCondition("orderId", EntityCondition.EQUALS, sampleOrderId) : null
        } as ScopeFilter)
        when: "an unfiltered orders list"
        def r = new GqlEngine(ec).execute('query { orders(first:50){ edges{ node{ orderId } } } }', [:], null)
        then: "only the scoped order is returned"
        r.errors.isEmpty()
        r.data.orders.edges.collect { it.node.orderId } == [sampleOrderId]
    }
}
