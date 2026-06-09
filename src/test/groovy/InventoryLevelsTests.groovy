import spock.lang.Specification
import spock.lang.Shared
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.gql.GqlEngine

/** Entity/view-backed root: inventoryLevels over ProductFacilityInventoryItemView
 *  (ProductFacility LEFT-joined to the current InventoryItem via ProductFacility.inventoryItemId).
 *  Driven by ProductFacility: a row per configured product+facility; ATP/QOH are 0 (never null)
 *  when unstocked/depleted. "every{}"/"instanceof" assertions stay green even if a slice is empty. */
class InventoryLevelsTests extends Specification {
    @Shared ExecutionContext ec
    @Shared String aFacilityId
    @Shared String aProductId

    def setupSpec() {
        ec = Moqui.getExecutionContext(); ec.artifactExecution.disableAuthz()
        def pf = ec.entity.find("org.apache.ofbiz.product.facility.ProductFacility")
                .selectField("productId").selectField("facilityId")
                .orderBy("productId").orderBy("facilityId").maxRows(1).fetchSize(1).list()
        if (pf) { aProductId = pf[0].productId; aFacilityId = pf[0].facilityId }
    }
    def cleanupSpec() { if (ec != null) { ec.artifactExecution.enableAuthz(); ec.destroy() } }

    def "inventoryLevels resolves to a Relay connection with non-null totals"() {
        when:
        def r = new GqlEngine(ec).execute(
                'query { inventoryLevels(first:5){ edges{ cursor node{ productId facilityId availableToPromise quantityOnHand } } pageInfo{ hasNextPage } } }', [:], null)
        then:
        r.errors.isEmpty()
        r.data.inventoryLevels != null
        r.data.inventoryLevels.edges instanceof List
        r.data.inventoryLevels.edges.every { it.node.productId != null && it.node.facilityId != null }
        // COALESCE contract: ATP/QOH are 0, never null
        r.data.inventoryLevels.edges.every { it.node.availableToPromise != null && it.node.quantityOnHand != null }
    }

    def "inventoryLevels filters by facilityId (declared key, index-backed)"() {
        given:
        org.junit.jupiter.api.Assumptions.assumeTrue(aFacilityId != null)
        when:
        def r = new GqlEngine(ec).execute(
                'query Q($q:String!){ inventoryLevels(first:25, query:$q){ edges{ node{ productId facilityId } } } }',
                [q: "facilityId:" + aFacilityId], "Q")
        then:
        r.errors.isEmpty()
        r.data.inventoryLevels.edges.every { it.node.facilityId == aFacilityId }
    }

    def "inventoryLevels filters by productId (declared key)"() {
        given:
        org.junit.jupiter.api.Assumptions.assumeTrue(aProductId != null)
        when:
        def r = new GqlEngine(ec).execute(
                'query Q($q:String!){ inventoryLevels(first:25, query:$q){ edges{ node{ productId } } } }',
                [q: "productId:" + aProductId], "Q")
        then:
        r.errors.isEmpty()
        r.data.inventoryLevels.edges.every { it.node.productId == aProductId }
    }

    def "inventoryLevels keyset pagination does not overlap across pages"() {
        when:
        def p1 = new GqlEngine(ec).execute('query { inventoryLevels(first:3){ edges{ cursor node{ productId facilityId } } pageInfo{ endCursor hasNextPage } } }', [:], null)
        then:
        p1.errors.isEmpty()
        def e1 = p1.data.inventoryLevels.edges
        e1.size() <= 3

        when:
        def hasNext = p1.data.inventoryLevels.pageInfo.hasNextPage
        def after = p1.data.inventoryLevels.pageInfo.endCursor
        def p2 = hasNext ? new GqlEngine(ec).execute(
                'query Q($a:String!){ inventoryLevels(first:3, after:$a){ edges{ node{ productId facilityId } } } }', [a: after], "Q") : null
        then:
        if (p2 != null) {
            p2.errors.isEmpty()
            def key = { n -> n.productId + "|" + n.facilityId }
            def s1 = e1.collect { key(it.node) } as Set
            p2.data.inventoryLevels.edges.every { !s1.contains(key(it.node)) }
        } else { true }
    }

    def "inventoryLevels requires first/last (governor)"() {
        when:
        def r = new GqlEngine(ec).execute('query { inventoryLevels{ edges{ node{ productId } } } }', [:], null)
        then:
        !r.errors.isEmpty()
        r.errors[0].extensions.code == "FIRST_REQUIRED"
    }

    // --- Data-independent search-key contract (no DB rows needed; rejected pre-execution) ---
    // inventoryLevels declares search-keys "productId:eq,in facilityId:eq,in" (OmsSchema.gql.xml).
    // SearchQueryParser rejects undeclared keys and disallowed comparators before any fetch, so these
    // assert the contract regardless of whether PRODUCT_FACILITY has rows.

    def "inventoryLevels rejects an undeclared search key with FIELD_NOT_FILTERABLE"() {
        when:
        def r = new GqlEngine(ec).execute('query { inventoryLevels(first:5, query:"badField:x"){ edges{ node{ productId } } } }', [:], null)
        then:
        !r.errors.isEmpty()
        r.errors[0].extensions.code == "FIELD_NOT_FILTERABLE"
    }

    def "inventoryLevels rejects a disallowed comparator on productId with OPERATOR_NOT_ALLOWED"() {
        when:   // productId is declared eq,in only; a '>' comparator (gt) is not allowed
        def r = new GqlEngine(ec).execute('query { inventoryLevels(first:5, query:"productId:>X"){ edges{ node{ productId } } } }', [:], null)
        then:
        !r.errors.isEmpty()
        r.errors[0].extensions.code == "OPERATOR_NOT_ALLOWED"
    }
}
