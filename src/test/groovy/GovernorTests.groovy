import spock.lang.Specification
import spock.lang.Shared
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.gql.GqlEngine

/** C4 query governor — adversarial cases (examples §N / §Q3b). Each must be rejected PRE-execution:
 *  stable extensions.code, data:null, nothing hits the DB. Threshold cases (depth, batch caps) use
 *  system-property overrides since the phase-1 schema isn't deep enough to trip them naturally. */
class GovernorTests extends Specification {
    @Shared ExecutionContext ec
    @Shared String sampleOrderId

    def setupSpec() {
        ec = Moqui.getExecutionContext(); ec.artifactExecution.disableAuthz()
        sampleOrderId = ec.entity.find("org.apache.ofbiz.order.order.OrderHeader")
                .selectField("orderId").maxRows(1).fetchSize(1).list().get(0).orderId
    }
    def cleanupSpec() { if (ec != null) { ec.artifactExecution.enableAuthz(); ec.destroy() } }
    // never leak threshold overrides into other suite classes
    def cleanup() { ["gql.maxDepth", "gql.maxCost", "gql.serviceBatchKeyLimit", "gql.wallClockBudgetMs"].each { System.clearProperty(it) } }

    private static boolean hasCode(Map r, String c) { return r.errors.any { it.extensions?.code == c } }

    def "N3 — connection without first/last is rejected with FIRST_REQUIRED, data null"() {
        when:
        def r = new GqlEngine(ec).execute('query { orders{ edges{ node{ orderId } } } }', [:], null)
        then:
        hasCode(r, "FIRST_REQUIRED")
        r.errors.find { it.extensions?.code == "FIRST_REQUIRED" }.extensions.field == "orders"
        r.data?.orders == null
    }

    def "N5 — first over the cap on a nested edge is rejected with FIRST_TOO_LARGE"() {
        when:
        def r = new GqlEngine(ec).execute(
                'query Q($id:ID!){ order(orderId:$id){ orderItems(first:5000){ edges{ node{ orderId } } } } }', [id: sampleOrderId], "Q")
        then:
        hasCode(r, "FIRST_TOO_LARGE")
        r.errors.find { it.extensions?.code == "FIRST_TOO_LARGE" }.extensions.maxFirst == 100
        r.data?.order == null
    }

    def "N2 — undeclared search key rejected pre-execution with FIELD_NOT_FILTERABLE"() {
        when:
        def r = new GqlEngine(ec).execute('query { orders(first:5, query:"bogusKey:X"){ edges{ node{ orderId } } } }', [:], null)
        then:
        hasCode(r, "FIELD_NOT_FILTERABLE")
        r.data?.orders == null
    }

    def "Q3b — disallowed comparator rejected pre-execution with OPERATOR_NOT_ALLOWED"() {
        when:
        def r = new GqlEngine(ec).execute('query { orders(first:5, query:"statusId:>ORDER_APPROVED"){ edges{ node{ orderId } } } }', [:], null)
        then:
        hasCode(r, "OPERATOR_NOT_ALLOWED")
        r.data?.orders == null
    }

    def "N1 — fan-out bomb (nested connections multiplied) is rejected with COST_EXCEEDED"() {
        when:
        def r = new GqlEngine(ec).execute(
                'query { orders(first:100){ edges{ node{ orderItems(first:100){ edges{ node{ orderId } } } } } } }', [:], null)
        then:
        hasCode(r, "COST_EXCEEDED")
        r.errors.find { it.extensions?.code == "COST_EXCEEDED" }.extensions.maxCost == 1000
        r.data?.orders == null
    }

    def "N4 — query depth over the limit is rejected with DEPTH_EXCEEDED"() {
        given:
        System.setProperty("gql.maxDepth", "1")   // Order=1, OrderItem=2 -> 2 > 1
        when:
        def r = new GqlEngine(ec).execute(
                'query Q($id:ID!){ order(orderId:$id){ orderItems(first:5){ edges{ node{ orderId } } } } }', [id: sampleOrderId], "Q")
        then:
        hasCode(r, "DEPTH_EXCEEDED")
        r.data?.order == null
    }

    // BATCH_LIMIT_EXCEEDED is no longer exercised by any governor test. Two code paths emitted it:
    //   (a) the inventory-key cap on the inventoryLevels SERVICE root (maxInventoryKeys) — REMOVED with
    //       that root in #35 (inventoryLevels is now a view-backed connection, governed like any other);
    //   (b) the general service-backed-FIELD cap (fanout > serviceBatchKeyLimit) — still live code, but
    //       unreachable via the built schema since the last service-backed field (itemCount) was retired
    //       in #37 (the old N6 case was dropped then). The capability is unit-covered by
    //       ServiceBackedLoaderTests; the governor's service-field branch has no schema field to drive it.

    def "selecting an aggregate field (orderItemCount) adds aggregateFieldCost to the query cost"() {
        when:
        def without = new GqlEngine(ec).execute('query Q($id:ID!){ order(orderId:$id){ orderId } }', [id: sampleOrderId], "Q")
        def with = new GqlEngine(ec).execute('query Q($id:ID!){ order(orderId:$id){ orderId orderItemCount } }', [id: sampleOrderId], "Q")
        then:
        without.errors.isEmpty() && with.errors.isEmpty()
        // aggregate field is charged aggregateFieldCost (default 5), not a free/scalar(1) cost
        (with.extensions.cost.requestedQueryCost as long) - (without.extensions.cost.requestedQueryCost as long) == 5L
    }

    def "wall-clock budget exceeded aborts mid-fetch with DEADLINE_EXCEEDED"() {
        given:
        System.setProperty("gql.wallClockBudgetMs", "0")   // deadline already passed at field fetch
        when:
        def r = new GqlEngine(ec).execute('query { orders(first:2){ edges{ node{ orderId } } } }', [:], null)
        then:
        hasCode(r, "DEADLINE_EXCEEDED")
    }
}
