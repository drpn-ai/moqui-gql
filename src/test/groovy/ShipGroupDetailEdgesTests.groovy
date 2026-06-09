import spock.lang.Specification
import spock.lang.Shared
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.gql.GqlEngine
import org.moqui.gql.scope.ScopeFilter
import org.moqui.gql.scope.ScopeFilters

/** #43 — ShipGroup detail edges. shipFromAddress (+lat/long) and shippingMethod are single-key has-one
 *  edges (the billToCustomer pattern), batched by NestedSingleLoader (one query per level, no N+1).
 *  facilityChangeHistory is a composite-key has-many (orderId, shipGroupSeqId) and is gated on #38.
 *  Vs real hcsd_notnaked: assertions stay green whether or not a given order's ship group carries an
 *  origin address / method / change rows (the data may be sparse), while still proving the wiring. */
class ShipGroupDetailEdgesTests extends Specification {
    @Shared ExecutionContext ec
    @Shared String orderWithItems
    @Shared String reachableChangeOrderId      // order whose change-bearing ship group ALSO has items, if any
    @Shared String reachableChangeSgSeqId      // that ship group's seqId (survives Order.shipGroups exclude-empty)
    @Shared long totalChangeRows               // diagnostic: total OrderFacilityChange rows in the sample

    def setupSpec() {
        ec = Moqui.getExecutionContext(); ec.artifactExecution.disableAuthz()
        orderWithItems = ec.entity.find("org.apache.ofbiz.order.order.OrderItem")
                .selectField("orderId").maxRows(1).fetchSize(1).list().get(0).orderId
        // Ground-truth seed for facilityChangeHistory. The edge is only reachable via Order.shipGroups,
        // which applies exclude-empty="items" (#38) — so a change-bearing ship group is only REACHABLE if it
        // also has >= 1 OrderItem. Find such a (orderId, shipGroupSeqId); may be null on sparse data, in which
        // case the ground-truth test skips (the edge is then wiring-verified by the conditional test above).
        def changes = ec.entity.find("co.hotwax.facility.OrderFacilityChange")
                .selectField("orderId").selectField("shipGroupSeqId").distinct(true)
                .maxRows(2000).fetchSize(2000).list()
        totalChangeRows = ec.entity.find("co.hotwax.facility.OrderFacilityChange").count()
        for (def c in changes) {
            long items = ec.entity.find("org.apache.ofbiz.order.order.OrderItem")
                    .condition("orderId", c.orderId).condition("shipGroupSeqId", c.shipGroupSeqId).count()
            if (items > 0L) { reachableChangeOrderId = c.orderId; reachableChangeSgSeqId = c.shipGroupSeqId; break }
        }
        ec.logger.info("gql-test: ShipGroupDetailEdgesTests — OrderFacilityChange rows = ${totalChangeRows}; " +
                (reachableChangeOrderId != null
                        ? "reachable change-bearing ship group = (${reachableChangeOrderId}, ${reachableChangeSgSeqId})"
                        : "NO change-bearing ship group has items -> all are dropped by exclude-empty; " +
                          "facilityChangeHistory is wiring-verified through Order.shipGroups but not data-exercised"))
    }
    def cleanupSpec() { if (ec != null) { ScopeFilters.reset(); ec.artifactExecution.enableAuthz(); ec.destroy() } }
    def cleanup() { ScopeFilters.reset(); System.clearProperty("gql.maxCost") }   // also undo any per-test budget bump

    def "shipGroups.shipFromAddress resolves leaf address fields incl. latitude/longitude"() {
        when:
        def r = new GqlEngine(ec).execute('''query Q($id:ID!){ order(orderId:$id){
            shipGroups(first:10){ edges{ node{ shipGroupSeqId facilityId
                shipFromAddress{ facilityId address1 city postalCode stateProvinceGeoId latitude longitude } } } } } }''',
            [id: orderWithItems], "Q")
        then:
        r.errors.isEmpty()
        r.data.order.shipGroups.edges instanceof List
        // when a ship group has a facility with a SHIP_ORIG_LOCATION address, the edge resolves and its
        // facilityId matches the ship group's facilityId (no cross-facility leakage from the IN batch).
        r.data.order.shipGroups.edges.every { sge ->
            def a = sge.node.shipFromAddress
            a == null || a.facilityId == sge.node.facilityId
        }
    }

    def "shipGroups.shippingMethod resolves shipmentMethodTypeId + description (descriptive method)"() {
        when:
        def r = new GqlEngine(ec).execute('''query Q($id:ID!){ order(orderId:$id){
            shipGroups(first:10){ edges{ node{ shipmentMethodTypeId
                shippingMethod{ shipmentMethodTypeId description } } } } } }''', [id: orderWithItems], "Q")
        then:
        r.errors.isEmpty()
        // when the ship group names a method type, the edge resolves and the id round-trips.
        r.data.order.shipGroups.edges.every { sge ->
            def m = sge.node.shippingMethod
            m == null || m.shipmentMethodTypeId == sge.node.shipmentMethodTypeId
        }
    }

    def "shipGroups.facilityChangeHistory batches per (orderId, shipGroupSeqId), ordered by orderFacilityChangeId (child PK)"() {
        given: "raise the cost budget — a 3-level nested connection (shipGroups -> facilityChangeHistory first:50) exceeds the default 1000 (cleared in cleanup)"
        System.setProperty("gql.maxCost", "100000")

        when:
        def r = new GqlEngine(ec).execute('''query Q($id:ID!){ order(orderId:$id){ orderId
            shipGroups(first:10){ edges{ node{ shipGroupSeqId
                facilityChangeHistory(first:50){ edges{ node{ orderFacilityChangeId shipGroupSeqId
                    fromFacilityId facilityId changeDatetime changeReasonEnumId comments } } } } } } } }''',
            [id: orderWithItems], "Q")
        then:
        r.errors.isEmpty()
        r.data.order.shipGroups.edges.every { sge ->
            def hist = (sge.node.facilityChangeHistory?.edges ?: [])
            // grouped correctly: every change row belongs to this ship group (no cross-group leakage)
            hist.every { it.node.shipGroupSeqId == sge.node.shipGroupSeqId } &&
            // ordered by the child PK orderFacilityChangeId ascending: NestedConnectionLoader orders this
            // composite has-many by fkFields + intraGroupFields = [orderId, shipGroupSeqId, orderFacilityChangeId]
            hist.collect { it.node.orderFacilityChangeId } ==
                hist.collect { it.node.orderFacilityChangeId }.sort()
        }
    }

    def "facilityChangeHistory equals ground truth for a REACHABLE change-bearing ship group (data-exercised)"() {
        given: "skip (not fail) when no change-bearing ship group also has items — exclude-empty makes it unreachable"
        org.junit.jupiter.api.Assumptions.assumeTrue(reachableChangeOrderId != null,
                "no change-bearing ship group in hcsd_notnaked has items (all dropped by Order.shipGroups exclude-empty) " +
                "— facilityChangeHistory is wiring-verified through Order.shipGroups but not data-exercised")
        System.setProperty("gql.maxCost", "100000")

        when: "by-PK root on that order; page ship groups + history to the cap"
        def r = new GqlEngine(ec).execute('''query Q($id:ID!){ order(orderId:$id){ orderId
            shipGroups(first:100){ edges{ node{ shipGroupSeqId
                facilityChangeHistory(first:100){ edges{ node{ orderFacilityChangeId shipGroupSeqId } } } } } } } }''',
            [id: reachableChangeOrderId], "Q")
        then:
        r.errors.isEmpty()
        and: "for each returned ship group, the GraphQL change-id set equals the DB rows for that exact composite key, in keyset order"
        (r.data.order.shipGroups?.edges ?: []).every { sge ->
            String sgSeqId = sge.node.shipGroupSeqId
            List<String> fromGql = (sge.node.facilityChangeHistory?.edges ?: []).collect { it.node.orderFacilityChangeId }
            List<String> truth = ec.entity.find("co.hotwax.facility.OrderFacilityChange")
                    .condition("orderId", reachableChangeOrderId).condition("shipGroupSeqId", sgSeqId)
                    .selectField("orderFacilityChangeId").orderBy("orderFacilityChangeId")
                    .maxRows(100).fetchSize(100).list().collect { it.orderFacilityChangeId }
            // grouping: every returned row belongs to this ship group; and the set matches ground truth exactly
            (sge.node.facilityChangeHistory?.edges ?: []).every { it.node.shipGroupSeqId == sgSeqId } && fromGql == truth
        }
        and: "the seeded ship group actually returned its change history (the check is not vacuous)"
        (r.data.order.shipGroups?.edges ?: []).find { it.node.shipGroupSeqId == reachableChangeSgSeqId }
                ?.node?.facilityChangeHistory?.edges?.size() >= 1
    }
}
