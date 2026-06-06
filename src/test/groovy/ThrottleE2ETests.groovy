import spock.lang.Specification
import spock.lang.Shared
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.gql.GqlEngine

/** Phase 2 C1 — end-to-end live throttle: a small per-caller bucket depletes across requests and the
 *  next request is rejected with THROTTLED (data null); extensions.cost.throttleStatus reflects the
 *  LIVE bucket. Bucket size/rate are overridden locally and save/restored so the rest of the suite
 *  (which runs with the throttle effectively disabled, see build.gradle) is unaffected. */
class ThrottleE2ETests extends Specification {
    @Shared ExecutionContext ec
    String oldBucket, oldRate

    def setupSpec() { ec = Moqui.getExecutionContext(); ec.artifactExecution.disableAuthz() }
    def cleanupSpec() { if (ec != null) { ec.artifactExecution.enableAuthz(); ec.destroy() } }

    def setup() {
        oldBucket = System.getProperty("gql.throttle.bucketSize")
        oldRate = System.getProperty("gql.throttleRestoreRate")
        System.setProperty("gql.throttle.bucketSize", "25")
        System.setProperty("gql.throttleRestoreRate", "0")          // no refill within the test -> deterministic
        ec.cache.getCache("gql.throttle.bucket").clear()            // fresh bucket
    }
    def cleanup() {
        oldBucket != null ? System.setProperty("gql.throttle.bucketSize", oldBucket) : System.clearProperty("gql.throttle.bucketSize")
        oldRate != null ? System.setProperty("gql.throttleRestoreRate", oldRate) : System.clearProperty("gql.throttleRestoreRate")
        ec.cache.getCache("gql.throttle.bucket").clear()           // don't leave a depleted bucket for other classes
    }

    def "the per-caller bucket depletes, the next request is THROTTLED, and throttleStatus is live"() {
        when: "first request (cost 20) debits the 25-point bucket"
        def r1 = new GqlEngine(ec).execute('query { orders(first:10){ edges{ node{ orderId } } } }', [:], null)
        then:
        r1.errors.isEmpty()
        def t1 = r1.extensions.cost.throttleStatus
        t1.maximumAvailable == 25
        t1.currentlyAvailable < 25                                  // LIVE: reflects the debit
        t1.restoreRate == 0

        when: "second request can't afford cost 20 with ~5 left (no refill) -> THROTTLED"
        def r2 = new GqlEngine(ec).execute('query { orders(first:10){ edges{ node{ orderId } } } }', [:], null)
        then:
        r2.errors.any { it.extensions?.code == "THROTTLED" }
        r2.data?.orders == null
    }
}
