import spock.lang.Specification
import spock.lang.Shared
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.gql.GqlEngine

/** C6 — catalog contract tests (examples.md). Each runs the exact-shaped query and asserts the
 *  documented output SHAPE (fields/edges/pageInfo) against real hcsd_notnaked data. Cost numbers are
 *  illustrative, so only shape is asserted. Covers the implemented surface (A2, E, J, L, Q3a, shipments,
 *  returns); examples needing unbuilt types (Product/Facility/Picklist/ShipGroup) are deferred. */
class CatalogContractTests extends Specification {
    @Shared ExecutionContext ec
    @Shared GqlEngine engine
    @Shared String orderWithItems, extOrderId, extValue, identType, identValue, identOrderId
    @Shared List productIds

    def setupSpec() {
        ec = Moqui.getExecutionContext(); ec.artifactExecution.disableAuthz()
        // ensure shipment data exists so the shipments catalog example returns rows
        ec.service.sync().name("GqlExampleServices.ensure#TestShipments").parameters([count: 5]).call()
        engine = new GqlEngine(ec)

        orderWithItems = ec.entity.find("org.apache.ofbiz.order.order.OrderItem")
                .selectField("orderId").maxRows(1).fetchSize(1).list().get(0).orderId
        def oh = ec.entity.find("org.apache.ofbiz.order.order.OrderHeader")
                .selectField("orderId").selectField("externalId").maxRows(50).fetchSize(50).list()
        for (r in oh) { if (r.externalId) { extOrderId = r.orderId; extValue = r.externalId; break } }
        def idRow = ec.entity.find("co.hotwax.order.OrderIdentification")
                .selectField("orderIdentificationTypeId").selectField("idValue").selectField("orderId")
                .maxRows(1).fetchSize(1).list().get(0)
        identType = idRow.orderIdentificationTypeId; identValue = idRow.idValue; identOrderId = idRow.orderId
        def pr = ec.entity.find("org.apache.ofbiz.order.order.OrderItem").selectField("productId")
                .distinct(true).maxRows(10).fetchSize(10).list()
        productIds = []
        for (r in pr) { if (r.productId && !productIds.contains(r.productId)) productIds.add(r.productId); if (productIds.size() >= 2) break }
    }
    def cleanupSpec() { if (ec != null) { ec.artifactExecution.enableAuthz(); ec.destroy() } }

    def "A2 — open-orders queue page: orders(query, sortKey, first) -> edges{cursor,node} + pageInfo"() {
        when:
        def r = engine.execute('''query { orders(query: "statusId:ORDER_APPROVED", sortKey: ORDER_DATE, first: 2) {
            edges { cursor node { orderId orderName orderDate grandTotal billToCustomer { firstName lastName } } }
            pageInfo { hasNextPage hasPreviousPage startCursor endCursor } } }''', [:], null)
        then:
        r.errors.isEmpty()
        def c = r.data.orders
        c.edges.size() <= 2
        c.edges.every { it.cursor != null && it.node.orderId != null }
        c.pageInfo.containsKey("hasNextPage") && c.pageInfo.containsKey("endCursor")
        r.extensions.cost.requestedQueryCost != null
    }

    def "L — order(orderId) with orderItems connection + identifications plain list + service field"() {
        when:
        def r = engine.execute('''query Q($id:ID!){ order(orderId:$id){
            orderId orderName itemCount billToCustomer { firstName lastName }
            orderItems(first:5){ edges{ node{ orderItemSeqId productId quantity unitPrice } } }
            identifications(first:10){ orderIdentificationTypeId idValue } } }''', [id: orderWithItems], "Q")
        then:
        r.errors.isEmpty()
        r.data.order.orderId == orderWithItems
        r.data.order.orderItems.edges instanceof List
        r.data.order.orderItems.edges.every { it.node.orderItemSeqId != null }
        r.data.order.identifications instanceof List   // plain bounded list, not a connection
    }

    def "J — external-id lookups: order(externalId) and orderByIdentification"() {
        when:
        def byExt = engine.execute('query Q($x:String){ order(externalId:$x){ orderId externalId } }', [x: extValue], "Q")
        def byIdent = engine.execute('query Q($t:String,$v:String){ orderByIdentification(identificationTypeId:$t, idValue:$v){ orderId } }',
                [t: identType, v: identValue], "Q")
        then:
        byExt.errors.isEmpty() && byExt.data.order.orderId == extOrderId
        byIdent.errors.isEmpty() && byIdent.data.orderByIdentification.orderId == identOrderId
    }

    def "E — inventoryLevels service-backed root returns InventoryLevel list with Decimal-as-string"() {
        when:
        def r = engine.execute('query Q($p:[ID!]!){ inventoryLevels(productIds:$p){ productId availableToPromise quantityOnHand } }',
                [p: productIds], "Q")
        then:
        r.errors.isEmpty()
        r.data.inventoryLevels.size() == productIds.size()
        r.data.inventoryLevels.every { it.availableToPromise instanceof String }   // Decimal serialized as string
    }

    def "shipments catalog example returns rows after the generator seeds data"() {
        when:
        def r = engine.execute('query { shipments(first:5, sortKey: SHIPMENT_ID){ edges{ node{ shipmentId statusId primaryOrderId } } pageInfo{ hasNextPage } } }', [:], null)
        then:
        r.errors.isEmpty()
        !r.data.shipments.edges.isEmpty()
        r.data.shipments.edges.every { it.node.shipmentId != null }
    }

    def "returns root returns the existing returns"() {
        when:
        def r = engine.execute('query { returns(first:5){ edges{ node{ returnId statusId } } pageInfo{ hasNextPage } } }', [:], null)
        then:
        r.errors.isEmpty()
        r.data.returns.edges instanceof List
        r.data.returns.edges.every { it.node.returnId != null }
    }
}
