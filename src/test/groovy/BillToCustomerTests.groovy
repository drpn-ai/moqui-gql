import spock.lang.Specification
import spock.lang.Shared
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.gql.GqlEngine
import org.moqui.gql.scope.ScopeFilter
import org.moqui.gql.scope.ScopeFilters

/** Phase 3 C2 — Order.billToCustomer: a single-object (has-one) leaf edge replacing the service-backed
 *  customerName. Exposes the BILL_TO customer's leaf columns (view-backed) for the client to compose;
 *  resolved by a BATCHED has-one loader (one query per level, no N+1). Vs real hcsd_notnaked. */
class BillToCustomerTests extends Specification {
    @Shared ExecutionContext ec
    @Shared String orderWithCustomer, expectedFirst, expectedLast

    def setupSpec() {
        ec = Moqui.getExecutionContext(); ec.artifactExecution.disableAuthz()
        // the view inner-joins BILL_TO Person, so any row is an order that HAS a customer; read via the
        // engine's source (useClone) so the by-pk round-trip is guaranteed.
        def v = ec.entity.find("moqui.gql.OrderBillToCustomer")
                .selectField("orderId").selectField("firstName").selectField("lastName")
                .orderBy("orderId").useClone(true).maxRows(1).fetchSize(1).list()
        if (!v.isEmpty()) { orderWithCustomer = v.get(0).orderId; expectedFirst = v.get(0).firstName; expectedLast = v.get(0).lastName }
    }
    def cleanupSpec() { if (ec != null) { ScopeFilters.reset(); ec.artifactExecution.enableAuthz(); ec.destroy() } }
    def cleanup() { ScopeFilters.reset() }

    def "order.billToCustomer resolves the BILL_TO customer's leaf fields (client composes the name)"() {
        when:
        def r = new GqlEngine(ec).execute(
                'query Q($id:ID!){ order(orderId:$id){ orderId billToCustomer{ partyId firstName lastName } } }',
                [id: orderWithCustomer], "Q")
        then:
        r.errors.isEmpty()
        r.data.order.billToCustomer != null
        r.data.order.billToCustomer.partyId != null
        r.data.order.billToCustomer.firstName == expectedFirst
        r.data.order.billToCustomer.lastName == expectedLast
    }

    def "billToCustomer is batched across orders (one has-one query per level, no N+1)"() {
        given: "a filter that records each entity find"
        def seen = Collections.synchronizedList([])
        ScopeFilters.set({ String en, ec2 -> seen.add(en); return null } as ScopeFilter)
        when:
        def r = new GqlEngine(ec).execute(
                'query { orders(first:5){ edges{ node{ orderId billToCustomer{ partyId } } } } }', [:], null)
        then:
        r.errors.isEmpty()
        seen.count { it == "org.apache.ofbiz.order.order.OrderHeader" } == 1     // one root find
        seen.count { it == "moqui.gql.OrderBillToCustomer" } == 1               // ONE batched has-one find for 5 orders
    }
}
