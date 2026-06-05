import spock.lang.Specification
import spock.lang.Shared
import spock.lang.Unroll
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.gql.GqlEngine

/** C6 §M — connection-walk property tests: paging through a connection covers exactly the ground-truth
 *  ordered set with no overlap and no skip, for arbitrary page sizes, over single-PK (orders) and
 *  composite-PK (parties) keysets. Run against real hcsd_notnaked data. */
class ConnectionWalkTests extends Specification {
    @Shared ExecutionContext ec
    @Shared GqlEngine engine

    def setupSpec() { ec = Moqui.getExecutionContext(); ec.artifactExecution.disableAuthz(); engine = new GqlEngine(ec) }
    def cleanupSpec() { if (ec != null) { ec.artifactExecution.enableAuthz(); ec.destroy() } }

    @Unroll
    def "walking orders in pages of #pageSize covers the same ordered set, no overlap/skip"() {
        given: "ground truth: first 30 orderIds in ascending PK order"
        def truth = ec.entity.find("org.apache.ofbiz.order.order.OrderHeader")
                .selectField("orderId").orderBy("orderId").maxRows(30).fetchSize(30).list().collect { it.orderId }

        when: "walk the connection forward, accumulating until we have >= 30"
        def walked = []; def after = null
        while (walked.size() < 30) {
            def r = after == null ?
                    engine.execute('query { orders(first:' + pageSize + '){ edges{ node{ orderId } } pageInfo{ endCursor hasNextPage } } }', [:], null) :
                    engine.execute('query Q($a:String){ orders(first:' + pageSize + ', after:$a){ edges{ node{ orderId } } pageInfo{ endCursor hasNextPage } } }', [a: after], "Q")
            assert r.errors.isEmpty()
            def conn = r.data.orders
            if (conn.edges.isEmpty()) break
            walked.addAll(conn.edges.collect { it.node.orderId })
            after = conn.pageInfo.endCursor
            if (!conn.pageInfo.hasNextPage) break
        }

        then:
        walked.size() >= 30
        walked.take(30) == truth                      // same set, same order — no overlap, no skip
        walked.unique().size() == walked.size()        // no duplicates anywhere in the walk

        where:
        pageSize << [1, 3, 7]
    }

    def "walking parties over a composite PK never repeats a (partyId,roleTypeId) key"() {
        given:
        def truth = ec.entity.find("co.hotwax.party.party.PartyNameAndRoleDetail")
                .selectField("partyId").selectField("roleTypeId").orderBy("partyId").orderBy("roleTypeId")
                .maxRows(20).fetchSize(20).list().collect { it.partyId + "/" + it.roleTypeId }

        when:
        def walked = []; def after = null
        while (walked.size() < 20) {
            def r = after == null ?
                    engine.execute('query { parties(first:6){ edges{ node{ partyId roleTypeId } } pageInfo{ endCursor hasNextPage } } }', [:], null) :
                    engine.execute('query Q($a:String){ parties(first:6, after:$a){ edges{ node{ partyId roleTypeId } } pageInfo{ endCursor hasNextPage } } }', [a: after], "Q")
            assert r.errors.isEmpty()
            def conn = r.data.parties
            if (conn.edges.isEmpty()) break
            walked.addAll(conn.edges.collect { it.node.partyId + "/" + it.node.roleTypeId })
            after = conn.pageInfo.endCursor
            if (!conn.pageInfo.hasNextPage) break
        }

        then:
        walked.size() >= 20
        walked.take(20) == truth
        walked.unique().size() == walked.size()
    }
}
