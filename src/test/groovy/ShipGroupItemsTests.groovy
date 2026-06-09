import spock.lang.Specification
import spock.lang.Shared
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.gql.GqlEngine

/** order -> shipGroups -> orderItems via the composite key (orderId, shipGroupSeqId) — the #38
 *  composite-key nested batching.
 *
 *  Test 1 (multi-order): a small list query (within the default C4 cost budget) proves items batch
 *  correctly across several orders with no cross-ORDER leakage.
 *  Test 2 (ground truth): a cheaper by-PK root on an order with MULTIPLE ship groups lets us page items
 *  deeply and assert the GraphQL item-set per ship group equals the DB OrderItem rows for that exact
 *  (orderId, shipGroupSeqId) — proving one batched query per level, correct grouping, no cross-GROUP
 *  leakage, and keyset ordering on orderItemSeqId. */
class ShipGroupItemsTests extends Specification {
    @Shared ExecutionContext ec
    @Shared String multiSgOrderId    // an order with >= 2 ship groups, if any exists
    @Shared String emptySgOrderId    // an order with >= 1 EMPTY ship group (OISG row, no OrderItem), if any exists
    @Shared int emptyShipGroupCount  // total empty ship groups found in the sampled data (diagnostic)

    def setupSpec() {
        ec = Moqui.getExecutionContext(); ec.artifactExecution.disableAuthz()
        // sample ship groups; find (a) an order with >= 2 groups, (b) any order with an EMPTY group.
        def oisg = ec.entity.find("org.apache.ofbiz.order.order.OrderItemShipGroup")
                .selectField("orderId").selectField("shipGroupSeqId").orderBy(["orderId", "shipGroupSeqId"])
                .maxRows(2000).fetchSize(2000).list()
        Map<String, Integer> counts = [:]
        oisg.each { ev -> String oid = ev.orderId; counts[oid] = (counts[oid] ?: 0) + 1 }
        multiSgOrderId = counts.find { it.value >= 2 }?.key ?: (oisg ? oisg.get(0).orderId : null)
        // OrderItem.shipGroupSeqId is never null (total join) -> an empty ship group = OISG row with no OrderItem
        for (def sg in oisg) {
            long n = ec.entity.find("org.apache.ofbiz.order.order.OrderItem")
                    .condition("orderId", sg.orderId).condition("shipGroupSeqId", sg.shipGroupSeqId).count()
            if (n == 0L) { emptyShipGroupCount++; if (emptySgOrderId == null) emptySgOrderId = sg.orderId }
        }
        ec.logger.info("gql-test: ShipGroupItemsTests — empty ship groups in sample = ${emptyShipGroupCount}" +
                (emptySgOrderId != null ? " (e.g. order ${emptySgOrderId})" : " (exclusion path runs but is not data-exercised)"))
    }
    def cleanupSpec() { if (ec != null) { ec.artifactExecution.enableAuthz(); ec.destroy() } }
    def cleanup() { System.clearProperty("gql.maxCost") }   // undo any per-test budget bump

    def "order shipGroups orderItems resolves via the composite key, grouped per order (no cross-order leakage)"() {
        when: "small multi-order list query (fits the default maxCost 1000 budget)"
        def r = new GqlEngine(ec).execute(
            'query { orders(first:4){ edges{ node{ orderId ' +
            'shipGroups(first:3){ edges{ node{ shipGroupSeqId orderItems(first:10){ edges{ node{ orderId orderItemSeqId } } } } } } } } } }',
            [:], null)
        then:
        r.errors.isEmpty()
        r.data.orders.edges instanceof List
        // every item under a ship group belongs to that order (no cross-order leakage from the composite condition)
        r.data.orders.edges.every { oe ->
            def oid = oe.node.orderId
            (oe.node.shipGroups?.edges ?: []).every { sge ->
                (sge.node.orderItems?.edges ?: []).every { ie -> ie.node.orderId == oid }
            }
        }
    }

    def "items under each ship group equal ground truth for its (orderId, shipGroupSeqId) — no cross-group leakage"() {
        given: "raise the cost budget for this one deep, single-order ground-truth query (cleared in cleanup)"
        org.junit.jupiter.api.Assumptions.assumeTrue(multiSgOrderId != null)
        System.setProperty("gql.maxCost", "100000")

        when: "by-PK root (fanout 1); page ship groups + items to the maxFirst cap so the item-set is complete"
        def r = new GqlEngine(ec).execute(
            'query Q($id:ID!){ order(orderId:$id){ orderId ' +
            'shipGroups(first:100){ edges{ node{ shipGroupSeqId orderItems(first:100){ edges{ node{ orderItemSeqId } } } } } } } }',
            [id: multiSgOrderId], "Q")
        then:
        r.errors.isEmpty()
        and: "for each returned ship group, the GraphQL item-set equals the DB OrderItem rows for that exact composite key, in keyset order"
        (r.data.order.shipGroups?.edges ?: []).every { sge ->
            String sgSeqId = sge.node.shipGroupSeqId
            List<String> fromGql = (sge.node.orderItems?.edges ?: []).collect { it.node.orderItemSeqId }
            List<String> truth = ec.entity.find("org.apache.ofbiz.order.order.OrderItem")
                    .condition("orderId", multiSgOrderId).condition("shipGroupSeqId", sgSeqId)
                    .selectField("orderItemSeqId").orderBy("orderItemSeqId")
                    .maxRows(100).fetchSize(100).list().collect { it.orderItemSeqId }
            fromGql == truth
        }
        and: "the by-PK order actually returned at least one ship group with items (the check is not vacuous)"
        (r.data.order.shipGroups?.edges ?: []).any { (it.node.orderItems?.edges ?: []).size() >= 1 }
    }

    def "order.shipGroups excludes empty ship groups (returned groups == DB non-empty groups)"() {
        given: "raise the budget so first:100 never truncates paging (cleared in cleanup)"
        org.junit.jupiter.api.Assumptions.assumeTrue(multiSgOrderId != null)
        System.setProperty("gql.maxCost", "100000")

        when: "by-PK root, page ship groups to the cap; orderItems(first:1) just needs presence"
        def r = new GqlEngine(ec).execute(
            'query Q($id:ID!){ order(orderId:$id){ orderId shipGroups(first:100){ edges{ node{ shipGroupSeqId ' +
            'orderItems(first:1){ edges{ node{ orderItemSeqId } } } } } } } }', [id: multiSgOrderId], "Q")
        then:
        r.errors.isEmpty()
        and: "every ship group the engine returned has at least one item"
        (r.data.order.shipGroups?.edges ?: []).every { sge -> (sge.node.orderItems?.edges ?: []).size() >= 1 }
        and: "the returned ship-group set equals EXACTLY the DB's non-empty ship groups for the order (empties dropped)"
        Set<String> fromGql = ((r.data.order.shipGroups?.edges ?: []).collect { it.node.shipGroupSeqId }) as Set
        Set<String> nonEmptyTruth = (ec.entity.find("org.apache.ofbiz.order.order.OrderItem")
                .condition("orderId", multiSgOrderId).selectField("shipGroupSeqId").distinct(true)
                .maxRows(1000).fetchSize(1000).list().collect { it.shipGroupSeqId }) as Set
        fromGql == nonEmptyTruth
    }

    def "exclude-empty actually drops a known empty ship group when the dataset has one"() {
        given: "skip (not fail) when the sampled data has no empty ship group to exercise the path"
        org.junit.jupiter.api.Assumptions.assumeTrue(emptySgOrderId != null,
                "no empty ship group in hcsd_notnaked sample — exclusion path runs but cannot be data-exercised")
        System.setProperty("gql.maxCost", "100000")

        when: "fetch the order that owns the empty ship group"
        def r = new GqlEngine(ec).execute(
            'query Q($id:ID!){ order(orderId:$id){ orderId shipGroups(first:100){ edges{ node{ shipGroupSeqId } } } } }',
            [id: emptySgOrderId], "Q")
        Set<String> returned = ((r.data.order.shipGroups?.edges ?: []).collect { it.node.shipGroupSeqId }) as Set
        // the empty OISG seqIds for this order (exist in OrderItemShipGroup but absent from OrderItem)
        Set<String> allSg = (ec.entity.find("org.apache.ofbiz.order.order.OrderItemShipGroup")
                .condition("orderId", emptySgOrderId).selectField("shipGroupSeqId")
                .maxRows(1000).fetchSize(1000).list().collect { it.shipGroupSeqId }) as Set
        Set<String> nonEmpty = (ec.entity.find("org.apache.ofbiz.order.order.OrderItem")
                .condition("orderId", emptySgOrderId).selectField("shipGroupSeqId").distinct(true)
                .maxRows(1000).fetchSize(1000).list().collect { it.shipGroupSeqId }) as Set
        Set<String> emptySg = new HashSet<String>(allSg); emptySg.removeAll(nonEmpty)

        then:
        r.errors.isEmpty()
        and: "there is at least one empty ship group on this order, and NONE of them are returned"
        !emptySg.isEmpty()
        returned.disjoint(emptySg)
        and: "the returned set is exactly the non-empty groups"
        returned == nonEmpty
    }
}
