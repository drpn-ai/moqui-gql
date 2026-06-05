import spock.lang.Specification
import spock.lang.Shared
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.gql.GqlEngine

/** External-id resolution (Q5 must-have), all three forms against real hcsd_notnaked data:
 *   - order(externalId:)              -> by the OrderHeader.externalId column
 *   - order.identifications           -> plain bounded [OrderIdentification!]! list (not a connection)
 *   - orderByIdentification(type,val) -> indirect lookup via the OrderIdentification association. */
class ExternalIdTests extends Specification {
    @Shared ExecutionContext ec
    @Shared String extOrderId, extValue
    @Shared String identType, identValue, identOrderId

    def setupSpec() {
        ec = Moqui.getExecutionContext(); ec.artifactExecution.disableAuthz()
        def oh = ec.entity.find("org.apache.ofbiz.order.order.OrderHeader")
                .selectField("orderId").selectField("externalId").maxRows(200).fetchSize(200).list()
        for (r in oh) { if (r.externalId) { extOrderId = r.orderId; extValue = r.externalId; break } }

        def idRow = ec.entity.find("co.hotwax.order.OrderIdentification")
                .selectField("orderIdentificationTypeId").selectField("idValue").selectField("orderId")
                .maxRows(1).fetchSize(1).list().get(0)
        identType = idRow.orderIdentificationTypeId
        identValue = idRow.idValue
        identOrderId = idRow.orderId
    }
    def cleanupSpec() { if (ec != null) { ec.artifactExecution.enableAuthz(); ec.destroy() } }

    def "order(externalId:) resolves an order by its external-id column"() {
        when:
        def r = new GqlEngine(ec).execute(
                'query Q($x:String){ order(externalId:$x){ orderId externalId } }', [x: extValue], "Q")
        then:
        r.errors.isEmpty()
        r.data.order != null
        r.data.order.orderId == extOrderId
        r.data.order.externalId == extValue
    }

    def "order.identifications returns a plain bounded list of typed external ids"() {
        when:
        def r = new GqlEngine(ec).execute(
                'query Q($id:ID!){ order(orderId:$id){ orderId identifications(first:10){ orderIdentificationTypeId idValue } } }',
                [id: identOrderId], "Q")
        then:
        r.errors.isEmpty()
        def ids = r.data.order.identifications
        ids instanceof List                                  // plain list, not a connection wrapper
        ids.size() > 0
        ids.every { it.orderIdentificationTypeId != null && it.idValue != null }
        ids.any { it.idValue == identValue }
    }

    def "orderByIdentification resolves the order via its typed external id"() {
        when:
        def r = new GqlEngine(ec).execute(
                'query Q($t:String,$v:String){ orderByIdentification(identificationTypeId:$t, idValue:$v){ orderId } }',
                [t: identType, v: identValue], "Q")
        then:
        r.errors.isEmpty()
        r.data.orderByIdentification != null
        r.data.orderByIdentification.orderId == identOrderId
    }

    def "orderByIdentification returns null for an unknown external id (no error)"() {
        when:
        def r = new GqlEngine(ec).execute(
                'query { orderByIdentification(identificationTypeId:"SHOPIFY_ORD_NO", idValue:"__nope__999999"){ orderId } }',
                [:], null)
        then:
        r.errors.isEmpty()
        r.data.orderByIdentification == null
    }
}
