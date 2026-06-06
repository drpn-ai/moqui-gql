import spock.lang.Specification
import spock.lang.Shared
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.gql.GqlEngine

/** Phase 1.5 C2 — Product + Facility roots (entity-backed). Declarative-only addition; exercises by-pk,
 *  external-id (Facility has the column), and list/search/sort. Vs real hcsd_notnaked data. */
class ProductFacilityTests extends Specification {
    @Shared ExecutionContext ec
    @Shared GqlEngine engine
    @Shared String productId, productTypeId, facilityId, facilityExtId

    def setupSpec() {
        ec = Moqui.getExecutionContext(); ec.artifactExecution.disableAuthz(); engine = new GqlEngine(ec)
        def p = ec.entity.find("org.apache.ofbiz.product.product.Product")
                .selectField("productId").selectField("productTypeId").maxRows(1).fetchSize(1).list().get(0)
        productId = p.productId; productTypeId = p.productTypeId
        def fRows = ec.entity.find("org.apache.ofbiz.product.facility.Facility")
                .selectField("facilityId").selectField("externalId").maxRows(50).fetchSize(50).list()
        facilityId = fRows.get(0).facilityId
        for (f in fRows) { if (f.externalId) { facilityExtId = f.externalId; break } }
    }
    def cleanupSpec() { if (ec != null) { ec.artifactExecution.enableAuthz(); ec.destroy() } }

    def "product by-pk + products list filtered by productTypeId"() {
        when:
        def byPk = engine.execute('query Q($id:ID!){ product(productId:$id){ productId productName } }', [id: productId], "Q")
        def list = engine.execute('query Q($q:String){ products(first:5, query:$q, sortKey: PRODUCT_ID){ edges{ node{ productId productTypeId } } pageInfo{ hasNextPage } } }',
                [q: "productTypeId:" + productTypeId], "Q")
        then:
        byPk.errors.isEmpty()
        byPk.data.product.productId == productId
        list.errors.isEmpty()
        !list.data.products.edges.isEmpty()
        list.data.products.edges.every { it.node.productTypeId == productTypeId }
    }

    def "facility by-pk + by externalId + facilities list"() {
        when:
        def byPk = engine.execute('query Q($id:ID!){ facility(facilityId:$id){ facilityId facilityName } }', [id: facilityId], "Q")
        def byExt = engine.execute('query Q($x:String){ facility(externalId:$x){ facilityId externalId } }', [x: facilityExtId], "Q")
        def list = engine.execute('query { facilities(first:5){ edges{ node{ facilityId facilityTypeId } } pageInfo{ hasNextPage } } }', [:], null)
        then:
        byPk.errors.isEmpty()
        byPk.data.facility.facilityId == facilityId
        byExt.errors.isEmpty()
        byExt.data.facility != null
        byExt.data.facility.externalId == facilityExtId
        list.errors.isEmpty()
        !list.data.facilities.edges.isEmpty()
    }
}
