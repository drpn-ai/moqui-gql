import spock.lang.Specification
import spock.lang.Shared
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.gql.GqlEngine

/** End-to-end: execute a GraphQL query against the cached schema + real MySQL hcsd_notnaked data. */
class GqlEngineTests extends Specification {
    @Shared ExecutionContext ec
    @Shared String sampleOrderId
    @Shared String orderWithItems
    @Shared int itemCount
    @Shared List twoOrderIdsWithItems

    def setupSpec() {
        ec = Moqui.getExecutionContext(); ec.artifactExecution.disableAuthz()
        sampleOrderId = ec.entity.find("org.apache.ofbiz.order.order.OrderHeader")
                .selectField("orderId").list().get(0).getString("orderId")
        orderWithItems = ec.entity.find("org.apache.ofbiz.order.order.OrderItem")
                .selectField("orderId").list().get(0).getString("orderId")
        itemCount = ec.entity.find("org.apache.ofbiz.order.order.OrderItem")
                .condition("orderId", orderWithItems).count() as int
        twoOrderIdsWithItems = ec.entity.find("org.apache.ofbiz.order.order.OrderItem")
                .selectField("orderId").distinct(true).orderBy("orderId").maxRows(2).fetchSize(2).list()
                .collect { it.orderId }
    }
    def cleanupSpec() { if (ec != null) { ec.artifactExecution.enableAuthz(); ec.destroy() } }

    def "executes order(orderId:) by-pk against real MySQL data"() {
        when:
        def r = new GqlEngine(ec).execute(
                'query Q($id: ID!){ order(orderId: $id){ orderId orderName statusId } }',
                [id: sampleOrderId], "Q")
        then:
        r.errors.isEmpty()
        r.data != null
        r.data.order != null
        r.data.order.orderId == sampleOrderId
    }

    def "orders(first:2) returns a Relay connection with edges + pageInfo"() {
        when:
        def r = new GqlEngine(ec).execute(
                'query { orders(first:2){ edges{ cursor node{ orderId } } pageInfo{ hasNextPage endCursor } } }',
                [:], null)
        then:
        r.errors.isEmpty()
        def conn = r.data.orders
        conn.edges.size() == 2
        conn.edges[0].node.orderId != null
        conn.edges[0].cursor != null
        conn.pageInfo.hasNextPage == true
        conn.pageInfo.endCursor != null
    }

    def "forward pagination via after matches DB keyset order with no overlap"() {
        given: "the DB's first 6 orderIds in ascending PK order (default sort)"
        def expected = ec.entity.find("org.apache.ofbiz.order.order.OrderHeader")
                .selectField("orderId").orderBy("orderId").maxRows(6).fetchSize(6).list()
                .collect { it.orderId }

        when: "page 1 (first 3)"
        def p1 = new GqlEngine(ec).execute(
                'query { orders(first:3){ edges{ node{ orderId } } pageInfo{ endCursor hasNextPage } } }', [:], null)
        def page1Ids = p1.data.orders.edges.collect { it.node.orderId }
        def after = p1.data.orders.pageInfo.endCursor

        and: "page 2 (next 3 via after)"
        def p2 = new GqlEngine(ec).execute(
                'query Q($after:String){ orders(first:3, after:$after){ edges{ node{ orderId } } } }',
                [after: after], "Q")
        def page2Ids = p2.data.orders.edges.collect { it.node.orderId }

        then:
        p1.errors.isEmpty() && p2.errors.isEmpty()
        page1Ids == expected[0..2]
        page2Ids == expected[3..5]
        page1Ids.intersect(page2Ids).isEmpty()
        p1.data.orders.pageInfo.hasNextPage == true
    }

    def "query: search string filters to matching statusId only (Q3 declare-and-control)"() {
        given:
        def status = ec.entity.find("org.apache.ofbiz.order.order.OrderHeader")
                .selectField("statusId").list().get(0).getString("statusId")
        when:
        def r = new GqlEngine(ec).execute(
                'query Q($q:String){ orders(first:10, query:$q){ edges{ node{ orderId statusId } } } }',
                [q: ("statusId:" + status)], "Q")
        then:
        r.errors.isEmpty()
        def nodes = r.data.orders.edges.collect { it.node }
        nodes.size() > 0
        nodes.every { it.statusId == status }
    }

    def "undeclared search key surfaces FIELD_NOT_FILTERABLE as a GraphQL error"() {
        when:
        def r = new GqlEngine(ec).execute(
                'query { orders(first:5, query:"bogusKey:X"){ edges{ node{ orderId } } } }', [:], null)
        then:
        !r.errors.isEmpty()
        r.errors.any { it.contains("FIELD_NOT_FILTERABLE") || it.contains("not filterable") }
    }

    def "backward pagination (last/before) returns the items immediately before the cursor, in order"() {
        given: "the DB's first 6 orderIds in ascending order"
        def expected = ec.entity.find("org.apache.ofbiz.order.order.OrderHeader")
                .selectField("orderId").orderBy("orderId").maxRows(6).fetchSize(6).list().collect { it.orderId }

        and: "a forward page to obtain the cursor at index 5"
        def fwd = new GqlEngine(ec).execute(
                'query { orders(first:6){ pageInfo{ endCursor } } }', [:], null)
        def cursorAt5 = fwd.data.orders.pageInfo.endCursor

        when: "page backward: the last 3 before index 5"
        def back = new GqlEngine(ec).execute(
                'query Q($b:String){ orders(last:3, before:$b){ edges{ node{ orderId } } pageInfo{ hasPreviousPage hasNextPage } } }',
                [b: cursorAt5], "Q")
        def ids = back.data.orders.edges.collect { it.node.orderId }

        then:
        back.errors.isEmpty()
        ids == expected[2..4]
        back.data.orders.pageInfo.hasPreviousPage == true
        back.data.orders.pageInfo.hasNextPage == true
    }

    def "by-pk nested orderItems resolve and all belong to the parent order"() {
        when:
        def r = new GqlEngine(ec).execute(
                'query Q($id:ID!){ order(orderId:$id){ orderId orderItems(first:100){ edges{ node{ orderId orderItemSeqId } } pageInfo{ hasNextPage } } } }',
                [id: orderWithItems], "Q")
        then:
        r.errors.isEmpty()
        def items = r.data.order.orderItems.edges.collect { it.node }
        items.size() == Math.min(itemCount, 100)
        items.every { it.orderId == orderWithItems }
    }

    def "list path batches nested orderItems across multiple parents, each grouped to its own order"() {
        given: "filter to two orders that both have items (one IN-batch over two parent keys)"
        def q = "orderId:" + twoOrderIdsWithItems.join(",")
        when:
        def r = new GqlEngine(ec).execute(
                'query Q($q:String){ orders(first:10, query:$q){ edges{ node{ orderId orderItems(first:50){ edges{ node{ orderId } } } } } } }',
                [q: q], "Q")
        then:
        r.errors.isEmpty()
        def nodes = r.data.orders.edges.collect { it.node }
        nodes.size() == twoOrderIdsWithItems.size()
        nodes.every { on -> !on.orderItems.edges.isEmpty() }                               // both parents got their items
        nodes.every { on -> on.orderItems.edges.every { it.node.orderId == on.orderId } }  // grouped to the correct parent
    }
}
