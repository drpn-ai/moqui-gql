import spock.lang.Specification
import spock.lang.Shared
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.gql.GqlEngine
import org.moqui.gql.policy.QueryStats

import java.security.MessageDigest

/** Query-log v2 — the logging POLICY (vs hcsd_notnaked): REJECTED always writes a raw row; ALLOWED
 *  writes only when slow or sampled (slow path is deterministic-tested in QueryStatsTests); every
 *  hit feeds the per-shape stats cache and aged bins persist to GqlQueryStatsBin. Each test pins the
 *  gql.queryLog.* system properties it depends on (save/restore — the ThrottleE2ETests pattern). */
class QueryLogPolicyTests extends Specification {
    @Shared ExecutionContext ec
    @Shared String sampleOrderId
    @Shared Map<String, String> savedProps = [:]
    @Shared String nonce = Long.toString(System.currentTimeMillis(), 36)   // unique per run: DB rows persist across suite runs
    static final List<String> PROPS = ["gql.queryLog.sampleRate", "gql.queryLog.binSeconds"]

    def setupSpec() {
        ec = Moqui.getExecutionContext(); ec.artifactExecution.disableAuthz()
        sampleOrderId = ec.entity.find("org.apache.ofbiz.order.order.OrderHeader")
                .selectField("orderId").orderBy("orderId").maxRows(1).fetchSize(1).list().get(0).orderId
        for (String p in PROPS) savedProps[p] = System.getProperty(p)
    }
    def cleanupSpec() {
        for (String p in PROPS) { savedProps[p] != null ? System.setProperty(p, savedProps[p]) : System.clearProperty(p) }
        if (ec != null) { ec.artifactExecution.enableAuthz(); ec.destroy() }
    }

    private static String sha256(String s) {
        return MessageDigest.getInstance("SHA-256").digest(s.getBytes("UTF-8"))
                .collect { String.format("%02x", it) }.join("")
    }
    private List logRows(String qtext) {
        return ec.entity.find("moqui.gql.GqlQueryLog").condition("queryText", qtext)
                .orderBy("-queryLogId").disableAuthz().maxRows(10).fetchSize(10).list()
    }

    def "a REJECTED query always writes a raw row with queryHash and creates the shape row"() {
        given:
        def qtext = 'query PolicyReject1_' + nonce + ' { orders{ edges{ node{ orderId } } } }'   // no first -> FIRST_REQUIRED
        System.setProperty("gql.queryLog.sampleRate", "0")                          // not via sampling
        when:
        new GqlEngine(ec).execute(qtext, [:], null)
        def rows = logRows(qtext)
        def shape = ec.entity.find("moqui.gql.GqlQueryShape").condition("queryHash", sha256(qtext)).disableAuthz().one()
        then:
        !rows.isEmpty()
        rows.get(0).verdict == "REJECTED"
        rows.get(0).queryHash == sha256(qtext)
        rows.get(0).slowHit == "N"
        shape != null
        shape.queryText == qtext
    }

    def "an ALLOWED query at sampleRate=0 writes NO raw row but the shape stats are tracked"() {
        given:
        def qtext = 'query PolicyAllow0_' + nonce + ' { order(orderId:"' + sampleOrderId + '"){ orderId } }'
        System.setProperty("gql.queryLog.sampleRate", "0")
        System.setProperty("gql.queryLog.binSeconds", "900")
        when:
        new GqlEngine(ec).execute(qtext, [:], null)
        then:
        logRows(qtext).isEmpty()
        ec.cache.getCache(QueryStats.CACHE_NAME).get(sha256(qtext)) != null
    }

    def "an ALLOWED query at sampleRate=1 writes a raw row: hash, slowHit N, callerProfile default"() {
        given:
        def qtext = 'query PolicyAllow1_' + nonce + ' { order(orderId:"' + sampleOrderId + '"){ orderName } }'
        System.setProperty("gql.queryLog.sampleRate", "1")
        when:
        new GqlEngine(ec).execute(qtext, [:], null)
        def rows = logRows(qtext)
        then:
        !rows.isEmpty()
        rows.get(0).verdict == "ALLOWED"
        rows.get(0).queryHash == sha256(qtext)
        rows.get(0).slowHit == "N"
        rows.get(0).callerProfile == "default"      // no GqlCallerProfileMember for this user
    }

    def "purge#QueryLog removes raw rows and bins past their retention, keeps fresh ones"() {
        given: "an old + a fresh raw row and an old + a fresh bin"
        def oldTs = new java.sql.Timestamp(System.currentTimeMillis() - 200L * 86400000L)
        def hash = sha256("purge-fixture-" + nonce)
        ec.transaction.runRequireNew(30, "purge fixture", {
            ec.entity.makeValue("moqui.gql.GqlQueryLog").setAll([queryDate: oldTs, verdict: "ALLOWED",
                    queryHash: hash, queryText: "purge-old-" + nonce]).setSequencedIdPrimary().create()
            ec.entity.makeValue("moqui.gql.GqlQueryLog").setAll([queryDate: ec.user.nowTimestamp, verdict: "ALLOWED",
                    queryHash: hash, queryText: "purge-fresh-" + nonce]).setSequencedIdPrimary().create()
            ec.entity.makeValue("moqui.gql.GqlQueryShape").setAll([queryHash: hash,
                    queryText: "purge-fixture-" + nonce, firstSeenDate: oldTs]).create()
            ec.entity.makeValue("moqui.gql.GqlQueryStatsBin").setAll([queryHash: hash, binStartDate: oldTs,
                    binEndDate: oldTs, hitCount: 1, totalDurationMs: 1]).setSequencedIdPrimary().create()
            ec.entity.makeValue("moqui.gql.GqlQueryStatsBin").setAll([queryHash: hash,
                    binStartDate: ec.user.nowTimestamp, binEndDate: ec.user.nowTimestamp,
                    hitCount: 1, totalDurationMs: 1]).setSequencedIdPrimary().create()
        })
        when:
        ec.user.pushUser("john.doe")   // authenticate="true" needs a logged-in user
        def res
        try {
            res = ec.service.sync().name("gql.QueryServices.purge#QueryLog")
                    .parameters([retainDays: 90, binRetainDays: 90]).call()
        } finally { ec.user.popUser() }
        def rawLeft = logRows("purge-old-" + nonce) + logRows("purge-fresh-" + nonce)
        def binsLeft = ec.entity.find("moqui.gql.GqlQueryStatsBin").condition("queryHash", hash)
                .disableAuthz().maxRows(10).fetchSize(10).list()
        then:
        res.rawPurged >= 1
        res.binsPurged >= 1
        rawLeft.size() == 1
        rawLeft.get(0).queryText == "purge-fresh-" + nonce
        binsLeft.size() == 1
        cleanup:
        ec.transaction.runRequireNew(30, "purge fixture cleanup", {
            ec.entity.find("moqui.gql.GqlQueryLog").condition("queryHash", hash).disableAuthz().deleteAll()
            ec.entity.find("moqui.gql.GqlQueryStatsBin").condition("queryHash", hash).disableAuthz().deleteAll()
            ec.entity.find("moqui.gql.GqlQueryShape").condition("queryHash", hash).disableAuthz().deleteAll()
        })
    }

    def "an aged bin persists to GqlQueryStatsBin on the next hit (binSeconds=0 -> every hit rolls)"() {
        given:
        def qtext = 'query PolicyBin_' + nonce + ' { order(orderId:"' + sampleOrderId + '"){ orderId statusId } }'
        System.setProperty("gql.queryLog.sampleRate", "0")
        System.setProperty("gql.queryLog.binSeconds", "0")
        ec.cache.getCache(QueryStats.CACHE_NAME).remove(sha256(qtext))   // fresh shape stats
        when:
        def r1 = new GqlEngine(ec).execute(qtext, [:], null)             // opens the bin
        new GqlEngine(ec).execute(qtext, [:], null)                      // rolls + persists it
        def bins = ec.entity.find("moqui.gql.GqlQueryStatsBin").condition("queryHash", sha256(qtext))
                .orderBy("-binStartDate").disableAuthz().maxRows(10).fetchSize(10).list()
        then:
        r1.errors.isEmpty()
        !bins.isEmpty()
        bins.get(0).hitCount == 1L
        bins.get(0).totalCost == ((Map) r1.extensions.cost).requestedQueryCost as Long
        bins.get(0).minDurationMs == bins.get(0).maxDurationMs
        bins.get(0).binEndDate != null
    }
}
