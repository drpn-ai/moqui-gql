package org.moqui.gql.policy

import groovy.transform.CompileStatic
import org.moqui.context.ExecutionContext

import javax.cache.Cache

/**
 * Query-log v2 — per-shape (queryHash) running execution stats. Mirrors the framework's
 * ArtifactStatsInfo/ArtifactHitBin pattern: a hit is "slow" when, after a warm-up count, its
 * duration exceeds the shape's running average + 2.6 standard deviations AND an absolute floor
 * (slowMinMillis) — adaptive per shape, no global threshold to tune. Hits also accumulate into a
 * current Bin that is rolled (returned for persistence) inline when older than binMillis — no
 * scheduler, the ArtifactHitBin advance-on-hit approach.
 *
 * Structure mirrors {@link ThrottleGate}: {@link #record} is the pure step (unit-tested with
 * injected time); {@link #track} adds the per-shape cache lookup. State lives by reference in the
 * capped Moqui cache `gql.query.stats` — eviction of a cold shape loses its un-flushed bin and
 * resets warm-up, an accepted tradeoff for bounded memory.
 */
@CompileStatic
class QueryStats {
    static final String CACHE_NAME = "gql.query.stats"

    static class ShapeStats {
        long hitCount = 0L
        double totalMs = 0.0d, totalSqMs = 0.0d
        Bin curBin = null
    }
    static class Bin {
        long binStartMs
        long hitCount = 0L, slowHitCount = 0L
        double totalMs = 0.0d, totalSqMs = 0.0d
        long minMs = Long.MAX_VALUE, maxMs = 0L
        long totalCost = 0L, totalRows = 0L
    }
    static class Outcome { boolean slow; Bin finishedBin; long finishedBinEndMs }

    /** Pure step: slow verdict from the PRIOR distribution, then lifetime + bin accumulation,
     *  rolling the bin when aged. Mutates the ShapeStats. */
    static Outcome record(ShapeStats s, long durationMs, long cost, long rows, long now,
                          int warmupHits, long slowMinMillis, long binMillis) {
        boolean slow = false
        if (s.hitCount >= warmupHits && durationMs >= slowMinMillis) {
            double avg = s.totalMs / s.hitCount
            double stdDev = Math.sqrt(Math.abs(s.totalSqMs - ((s.totalMs * s.totalMs) / s.hitCount)) / (s.hitCount - 1L))
            double slowTime = avg + (stdDev * 2.6d)
            if (slowTime != 0.0d && durationMs > slowTime) slow = true
        }
        s.hitCount++
        s.totalMs += durationMs
        s.totalSqMs += ((double) durationMs) * durationMs

        Bin finished = null
        if (s.curBin != null && (now - s.curBin.binStartMs) >= binMillis) { finished = s.curBin; s.curBin = null }
        if (s.curBin == null) s.curBin = new Bin(binStartMs: now)
        Bin b = s.curBin
        b.hitCount++
        if (slow) b.slowHitCount++
        b.totalMs += durationMs
        b.totalSqMs += ((double) durationMs) * durationMs
        if (durationMs < b.minMs) b.minMs = durationMs
        if (durationMs > b.maxMs) b.maxMs = durationMs
        b.totalCost += cost
        b.totalRows += rows
        return new Outcome(slow: slow, finishedBin: finished, finishedBinEndMs: now)
    }

    /** Production entry: resolve (or create) the shape's stats from the cache and record. */
    static Outcome track(ExecutionContext ec, String queryHash, long durationMs, long cost, long rows,
                         int warmupHits, long slowMinMillis, long binMillis) {
        Cache cache = ec.cache.getCache(CACHE_NAME)
        long now = System.currentTimeMillis()
        ShapeStats s
        synchronized (QueryStats.class) {
            s = (ShapeStats) cache.get(queryHash)
            if (s == null) { s = new ShapeStats(); cache.put(queryHash, s) }
        }
        synchronized (s) { return record(s, durationMs, cost, rows, now, warmupHits, slowMinMillis, binMillis) }
    }
}
