import spock.lang.Specification
import spock.lang.Shared
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.gql.GqlEngine

/** C5 — endpoint service layer + extensions.cost + observability. execute#Query/get#Sdl services,
 *  Shopify-shaped cost envelope (static throttleStatus in phase 1), and one GqlQueryLog row per
 *  request (verdict/cost/rows/duration). Verified against real hcsd_notnaked. */
class EndpointTests extends Specification {
    @Shared ExecutionContext ec
    @Shared String sampleOrderId

    def setupSpec() {
        ec = Moqui.getExecutionContext(); ec.artifactExecution.disableAuthz()
        ec.user.pushUser("john.doe")   // establish a user (no Shiro) so authenticate="true" services run
        sampleOrderId = ec.entity.find("org.apache.ofbiz.order.order.OrderHeader")
                .selectField("orderId").maxRows(1).fetchSize(1).list().get(0).orderId
    }
    def cleanupSpec() { if (ec != null) { ec.artifactExecution.enableAuthz(); ec.destroy() } }

    def "execute#Query service returns data + Shopify-shaped extensions.cost"() {
        when:
        def r = ec.service.sync().name("gql.QueryServices.execute#Query").parameters([
                query: 'query Q($id:ID!){ order(orderId:$id){ orderId } }',
                variables: [id: sampleOrderId], operationName: "Q"]).call()
        then:
        r.errors.isEmpty()
        r.data.order.orderId == sampleOrderId
        and: "cost envelope shape (values illustrative; throttleStatus static in phase 1)"
        def cost = r.extensions.cost
        cost.requestedQueryCost != null
        cost.actualQueryCost == cost.requestedQueryCost
        cost.throttleStatus.maximumAvailable != null
        cost.throttleStatus.currentlyAvailable == cost.throttleStatus.maximumAvailable
        cost.throttleStatus.restoreRate != null
    }

    def "get#Sdl publishes the schema SDL"() {
        when:
        def r = ec.service.sync().name("gql.QueryServices.get#Sdl").call()
        then:
        r.sdl != null
        r.sdl.contains("type Order")
        r.sdl.contains("type Query")
        r.sdl.contains("orderByIdentification")
    }

    def "an allowed request writes a GqlQueryLog row (verdict ALLOWED + cost + duration)"() {
        given:
        def qtext = 'query LogAllow { order(orderId:"' + sampleOrderId + '"){ orderId } }'
        when:
        new GqlEngine(ec).execute(qtext, [:], null)
        def row = ec.entity.find("moqui.gql.GqlQueryLog").condition("queryText", qtext)
                .orderBy("-queryLogId").maxRows(1).fetchSize(1).list()
        then:
        !row.isEmpty()
        row.get(0).verdict == "ALLOWED"
        row.get(0).durationMs != null
        row.get(0).estimatedCost != null
    }

    def "a rejected request is logged with verdict REJECTED + the rejection code"() {
        given:
        def qtext = 'query LogReject { orders{ edges{ node{ orderId } } } }'   // no first -> FIRST_REQUIRED
        when:
        new GqlEngine(ec).execute(qtext, [:], null)
        def row = ec.entity.find("moqui.gql.GqlQueryLog").condition("queryText", qtext)
                .orderBy("-queryLogId").maxRows(1).fetchSize(1).list()
        then:
        !row.isEmpty()
        row.get(0).verdict == "REJECTED"
        row.get(0).rejectReason == "FIRST_REQUIRED"
    }
}
