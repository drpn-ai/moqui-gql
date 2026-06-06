import spock.lang.Specification
import spock.lang.Shared
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.gql.GqlEngine

/** Phase 1.5 C1 — Order detail edges (completes catalog A1). shipGroups is a Relay connection;
 *  statuses/adjustments/paymentPreferences are plain bounded lists. All via OrderHeader short-alias
 *  relationships, resolved by the existing engine (declarative-only addition). Vs hcsd_notnaked. */
class OrderDetailEdgesTests extends Specification {
    @Shared ExecutionContext ec
    @Shared String orderWithItems

    def setupSpec() {
        ec = Moqui.getExecutionContext(); ec.artifactExecution.disableAuthz()
        orderWithItems = ec.entity.find("org.apache.ofbiz.order.order.OrderItem")
                .selectField("orderId").maxRows(1).fetchSize(1).list().get(0).orderId
    }
    def cleanupSpec() { if (ec != null) { ec.artifactExecution.enableAuthz(); ec.destroy() } }

    def "order resolves shipGroups (connection) + statuses/adjustments/paymentPreferences (plain lists)"() {
        when:
        def r = new GqlEngine(ec).execute('''query Q($id:ID!){ order(orderId:$id){
            orderId
            shipGroups(first:10){ edges{ node{ shipGroupSeqId shipmentMethodTypeId carrierPartyId } } pageInfo{ hasNextPage } }
            statuses(first:50){ statusId statusDatetime }
            adjustments(first:50){ orderAdjustmentId orderAdjustmentTypeId amount }
            paymentPreferences(first:20){ orderPaymentPreferenceId paymentMethodTypeId } } }''', [id: orderWithItems], "Q")
        then:
        r.errors.isEmpty()
        and: "shipGroups is a connection with at least one group for an order with items"
        r.data.order.shipGroups.edges instanceof List
        !r.data.order.shipGroups.edges.isEmpty()
        r.data.order.shipGroups.edges.every { it.node.shipGroupSeqId != null }
        and: "statuses present as a plain list (order has status history)"
        r.data.order.statuses instanceof List
        !r.data.order.statuses.isEmpty()
        r.data.order.statuses.every { it.statusId != null }
        and: "adjustments + paymentPreferences resolve as plain lists (may be empty)"
        r.data.order.adjustments instanceof List
        r.data.order.paymentPreferences instanceof List
    }
}
