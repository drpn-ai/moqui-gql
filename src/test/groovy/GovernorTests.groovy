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
    @Shared List productIds

    def setupSpec() {
        ec = Moqui.getExecutionContext(); ec.artifactExecution.disableAuthz()
        sampleOrderId = ec.entity.find("org.apache.ofbiz.order.order.OrderHeader")
                .selectField("orderId").maxRows(1).fetchSize(1).list().get(0).orderId
        def rows = ec.entity.find("org.apache.ofbiz.order.order.OrderItem")
                .selectField("productId").distinct(true).maxRows(10).fetchSize(10).list()
        productIds = []
        for (r in rows) { if (r.productId && !productIds.contains(r.productId)) productIds.add(r.productId); if (productIds.size() >= 2) break }
    }
    def cleanupSpec() { if (ec != null) { ec.artifactExecution.enableAuthz(); ec.destroy() } }
    // never leak threshold overrides into other suite classes
    def cleanup() { ["gql.maxDepth", "gql.maxCost", "gql.serviceBatchKeyLimit", "gql.maxInventoryKeys", "gql.wallClockBudgetMs"].each { System.clearProperty(it) } }

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

    def "N6 — service-backed field under a wide list trips BATCH_LIMIT_EXCEEDED"() {
        given:
        System.setProperty("gql.serviceBatchKeyLimit", "5")   // fan-out 10 > 5
        when:
        def r = new GqlEngine(ec).execute('query { orders(first:10){ edges{ node{ orderId itemCount } } } }', [:], null)
        then:
        hasCode(r, "BATCH_LIMIT_EXCEEDED")
        r.errors.find { it.extensions?.code == "BATCH_LIMIT_EXCEEDED" }.extensions.field == "itemCount"
        r.data?.orders == null
    }

    def "wall-clock budget exceeded aborts mid-fetch with DEADLINE_EXCEEDED"() {
        given:
        System.setProperty("gql.wallClockBudgetMs", "0")   // deadline already passed at field fetch
        when:
        def r = new GqlEngine(ec).execute('query { orders(first:2){ edges{ node{ orderId } } } }', [:], null)
        then:
        hasCode(r, "DEADLINE_EXCEEDED")
    }

    def "inventoryLevels over maxInventoryKeys is rejected with BATCH_LIMIT_EXCEEDED"() {
        given:
        System.setProperty("gql.maxInventoryKeys", "1")   // 2 products > 1
        when:
        def r = new GqlEngine(ec).execute(
                'query Q($p:[ID!]!){ inventoryLevels(productIds:$p){ productId } }', [p: productIds], "Q")
        then:
        hasCode(r, "BATCH_LIMIT_EXCEEDED")
        r.data?.inventoryLevels == null
    }
}
