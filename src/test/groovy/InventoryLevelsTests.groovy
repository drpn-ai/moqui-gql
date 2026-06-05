import spock.lang.Specification
import spock.lang.Shared
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.gql.GqlEngine

/** Service-backed ROOT (decision 12 at root level, R3): inventoryLevels(productIds, facilityIds) is
 *  produced by GqlExampleServices.get#InventoryLevels and returns [InventoryLevel!]!. hcsd_notnaked
 *  has 0 inventory items, so ATP is 0 — but the mechanism (list args in, list of result maps out,
 *  scalar fields resolved from those maps) is fully exercised. */
class InventoryLevelsTests extends Specification {
    @Shared ExecutionContext ec
    @Shared List productIds

    def setupSpec() {
        ec = Moqui.getExecutionContext(); ec.artifactExecution.disableAuthz()
        def rows = ec.entity.find("org.apache.ofbiz.order.order.OrderItem")
                .selectField("productId").distinct(true).maxRows(10).fetchSize(10).list()
        productIds = []
        for (r in rows) { if (r.productId && !productIds.contains(r.productId)) productIds.add(r.productId); if (productIds.size() >= 2) break }
    }
    def cleanupSpec() { if (ec != null) { ec.artifactExecution.enableAuthz(); ec.destroy() } }

    def "inventoryLevels service-backed root returns one InventoryLevel per requested product"() {
        when:
        def r = new GqlEngine(ec).execute(
                'query Q($p:[ID!]!){ inventoryLevels(productIds:$p){ productId availableToPromise quantityOnHand } }',
                [p: productIds], "Q")
        then:
        r.errors.isEmpty()
        def levels = r.data.inventoryLevels
        levels instanceof List
        levels.size() == productIds.size()
        (levels.collect { it.productId } as Set) == (productIds as Set)
        levels.every { it.availableToPromise != null }   // typed Decimal, 0 with no inventory but present
    }
}
