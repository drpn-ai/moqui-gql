import spock.lang.Specification
import org.moqui.gql.policy.QueryStats
import org.moqui.gql.policy.QueryStats.ShapeStats

/** Query-log v2 — pure per-shape stats: adaptive slow detection (avg + 2.6σ after warm-up, absolute
 *  floor — the framework ArtifactStatsInfo math) and stats-bin rollover. No ec, injected time. */
class QueryStatsTests extends Specification {

    private static QueryStats.Outcome rec(ShapeStats s, long durMs, long now = 0L,
            int warmup = 50, long floor = 1000L, long binMs = 900000L, long cost = 10L, long rows = 5L) {
        return QueryStats.record(s, durMs, cost, rows, now, warmup, floor, binMs)
    }

    def "no slow verdict during warm-up regardless of how slow the hit is"() {
        given:
        def s = new ShapeStats()
        when:
        49.times { rec(s, 10L) }
        def out = rec(s, 10000L)
        then:
        !out.slow                          // 49 prior hits < warmupHits(50)
        s.hitCount == 50L
    }

    def "a statistical outlier after warm-up is slow"() {
        given:
        def s = new ShapeStats()
        when:
        50.times { rec(s, 100L) }          // tight distribution, sigma ~0
        def out = rec(s, 10000L)
        then:
        out.slow
    }

    def "the absolute floor wins: statistically slow but under slowMinMillis is not flagged"() {
        given:
        def s = new ShapeStats()
        when:
        50.times { rec(s, 10L) }
        def out = rec(s, 500L)             // >> avg + 2.6 sigma, but < floor(1000)
        then:
        !out.slow
    }

    def "a normal hit after warm-up is not slow"() {
        given:
        def s = new ShapeStats()
        when:
        50.times { rec(s, 100L + (it % 7)) }
        def out = rec(s, 103L)
        then:
        !out.slow
    }

    def "bin rolls over once aged: finished bin returned with the first hit's numbers"() {
        given:
        def s = new ShapeStats()
        when:
        def first = rec(s, 40L, 0L, 50, 1000L, 1000L, 7L, 3L)
        def second = rec(s, 60L, 1500L, 50, 1000L, 1000L, 9L, 4L)
        then:
        first.finishedBin == null          // first hit opens the bin
        second.finishedBin != null         // 1500ms > binMillis(1000) -> rolled
        second.finishedBin.hitCount == 1L
        second.finishedBin.totalMs == 40.0d
        second.finishedBin.minMs == 40L
        second.finishedBin.maxMs == 40L
        second.finishedBin.totalCost == 7L
        second.finishedBin.totalRows == 3L
        s.curBin.hitCount == 1L            // the second hit landed in the fresh bin
        s.curBin.totalCost == 9L
    }

    def "a bin accumulates duration, cost, and rows across hits"() {
        given:
        def s = new ShapeStats()
        when:
        rec(s, 10L, 0L, 50, 1000L, 60000L, 5L, 2L)
        rec(s, 30L, 100L, 50, 1000L, 60000L, 6L, 3L)
        then:
        s.curBin.hitCount == 2L
        s.curBin.totalMs == 40.0d
        s.curBin.minMs == 10L
        s.curBin.maxMs == 30L
        s.curBin.totalCost == 11L
        s.curBin.totalRows == 5L
    }
}
