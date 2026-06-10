import spock.lang.Specification
import spock.lang.Shared
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.gql.GqlEngine
import org.moqui.gql.CachingPreparsedDocumentProvider

/** Phase 3 C1 — prepared-statement document cache. A query STRING is parsed+validated once (PREPARE),
 *  its Document reused across executions that bind different variables (EXECUTE), and the governor
 *  still gates every execution (caching the plan never bypasses the gate). Vs hcsd_notnaked. */
class PreparsedCacheTests extends Specification {
    @Shared ExecutionContext ec
    @Shared GqlEngine engine
    @Shared String oid1, oid2

    def setupSpec() {
        ec = Moqui.getExecutionContext(); ec.artifactExecution.disableAuthz(); engine = new GqlEngine(ec)
        def ids = ec.entity.find("org.apache.ofbiz.order.order.OrderHeader")
                .selectField("orderId").orderBy("orderId").maxRows(2).fetchSize(2).list().collect { it.orderId }
        oid1 = ids[0]; oid2 = ids[1]
    }
    def cleanupSpec() { if (ec != null) { ec.artifactExecution.enableAuthz(); ec.destroy() } }

    private cache() { ec.cache.getCache(CachingPreparsedDocumentProvider.CACHE_NAME) }

    def "a query string is parsed once and its Document reused on repeat (PREPARE once)"() {
        given:
        def q1 = 'query A { orders(first:1){ edges{ node{ orderId } } } }'
        def q2 = 'query B { orders(first:2){ edges{ node{ orderName } } } }'
        cache().clear()
        when:
        engine.execute(q1, [:], null)
        def e1 = cache().get(q1)
        engine.execute(q1, [:], null)      // repeat -> cache hit, no re-parse
        engine.execute(q2, [:], null)      // different shape
        then:
        e1 != null
        cache().get(q1).is(e1)             // same cached Document entry reused
        cache().get(q2) != null
        !cache().get(q2).is(e1)            // distinct query string -> distinct entry
    }

    def "the same prepared query binds different variables across executions (EXECUTE many)"() {
        given:
        def q = 'query Q($id:ID!){ order(orderId:$id){ orderId } }'
        cache().clear()
        when:
        def r1 = engine.execute(q, [id: oid1], "Q")
        def r2 = engine.execute(q, [id: oid2], "Q")
        then:
        r1.errors.isEmpty() && r1.data.order.orderId == oid1
        r2.errors.isEmpty() && r2.data.order.orderId == oid2
        cache().get(q) != null             // one prepared entry, two binds
    }

    def "the document cache has the configured size cap (MoquiConf max-elements, not unbounded)"() {
        expect: // cap from component MoquiConf <cache name="gql.preparsed.document" max-elements="1000"/>
        cache().unwrap(org.moqui.jcache.MCache) != null
        cache().unwrap(org.moqui.jcache.MCache).getMaxEntries() == 1000
    }

    def "caching the document does not bypass the governor (gate runs per execution)"() {
        given:
        def bad = 'query { orders{ edges{ node{ orderId } } } }'   // valid GraphQL, but no first -> FIRST_REQUIRED
        cache().clear()
        when:
        def r1 = engine.execute(bad, [:], null)
        def r2 = engine.execute(bad, [:], null)                    // 2nd run hits the doc cache, still gated
        then:
        r1.errors.any { it.extensions?.code == "FIRST_REQUIRED" }
        r2.errors.any { it.extensions?.code == "FIRST_REQUIRED" }
        r1.data?.orders == null && r2.data?.orders == null
    }
}
