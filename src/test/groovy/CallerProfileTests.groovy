import spock.lang.Specification
import spock.lang.Shared
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.gql.GqlEngine
import org.moqui.gql.scope.ScopeFilters

/** Phase 2 C2 — caller profiles + scope activation. A GqlCallerProfile assigned to the caller overrides
 *  governor limits and may activate the (phase-1) ScopeFilter seam. Verified vs real hcsd_notnaked:
 *  a low-maxCost profile rejects a query the default allows; a store-scoped profile only returns that
 *  store's orders. Profile/member rows are created + cleaned up in their own transactions. */
class CallerProfileTests extends Specification {
    @Shared ExecutionContext ec
    @Shared String uid, scopedStore

    def setupSpec() {
        ec = Moqui.getExecutionContext(); ec.artifactExecution.disableAuthz()
        ec.user.pushUser("john.doe"); uid = ec.user.userId
        def rows = ec.entity.find("org.apache.ofbiz.order.order.OrderHeader")
                .selectField("productStoreId").maxRows(50).fetchSize(50).list()
        for (r in rows) { if (r.productStoreId) { scopedStore = r.productStoreId; break } }
    }
    def cleanupSpec() {
        if (ec != null) {
            ec.transaction.runRequireNew(30, "gql profile-test cleanup", {
                ec.entity.find("moqui.gql.GqlCallerProfileMember").condition("userId", uid).disableAuthz().deleteAll()
                ec.entity.find("moqui.gql.GqlCallerProfile").condition("profileId", "restricted").disableAuthz().deleteAll()
                ec.entity.find("moqui.gql.GqlCallerProfile").condition("profileId", "store-scoped").disableAuthz().deleteAll()
            })
            ScopeFilters.reset(); ec.artifactExecution.enableAuthz(); ec.destroy()
        }
    }
    def cleanup() { ScopeFilters.reset() }

    private void assign(String profileId, Map fields) {
        ec.transaction.runRequireNew(30, "gql profile-test setup", {
            ec.entity.makeValue("moqui.gql.GqlCallerProfile").setAll([profileId: profileId] + fields).createOrUpdate()
            ec.entity.makeValue("moqui.gql.GqlCallerProfileMember").setAll([userId: uid, profileId: profileId]).createOrUpdate()
        })
    }

    def "a restrictive profile lowers the cost ceiling for its caller"() {
        given: "profile maxCost 50 (bucket huge so only cost gates)"
        assign("restricted", [maxCost: 50, bucketSize: 1000000000, restoreRate: 50])
        when: "a query costing 100 (orders first:50 -> 50*(1+1))"
        def r = new GqlEngine(ec).execute('query { orders(first:50){ edges{ node{ orderId } } } }', [:], null)
        then:
        r.errors.any { it.extensions?.code == "COST_EXCEEDED" }
        r.errors.find { it.extensions?.code == "COST_EXCEEDED" }.extensions.maxCost == 50   // profile limit, not global 1000
    }

    def "a scoped profile restricts rows to its productStore (ScopeFilter seam activated)"() {
        given:
        assign("store-scoped", [scopeProductStoreId: scopedStore, bucketSize: 1000000000])
        when:
        def r = new GqlEngine(ec).execute('query { orders(first:50){ edges{ node{ orderId productStoreId } } } }', [:], null)
        then:
        r.errors.isEmpty()
        !r.data.orders.edges.isEmpty()
        r.data.orders.edges.every { it.node.productStoreId == scopedStore }
    }
}
