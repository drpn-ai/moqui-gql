package org.moqui.gql.policy

import groovy.transform.CompileStatic
import org.moqui.context.ExecutionContext

import javax.cache.Cache

/**
 * Per-caller cost throttle — a token/leaky bucket (Shopify-style). Each caller has a bucket of at most
 * `bucketSize` points that refills at `restoreRate` points/second; a request is allowed if it can debit
 * its estimated cost. Phase-2 replacement for the static `throttleStatus`. Buckets live by reference in
 * a Moqui cache so they persist across requests.
 *
 * {@link #decide} is the pure refill+debit step (unit-tested with injected time); {@link #check} adds
 * the per-caller cache lookup. Debit is applied only when the request will actually execute, so a
 * query rejected by another gate (or by the throttle itself) does not consume budget.
 */
@CompileStatic
class ThrottleGate {
    static final String CACHE_NAME = "gql.throttle.bucket"

    static class Bucket { double available; long lastMillis }
    static class Decision { boolean allowed; double currentlyAvailable; int maximumAvailable; int restoreRate }

    /** Refill the bucket up to `now`, then debit `cost` iff allowed && apply. Mutates the bucket. */
    static Decision decide(Bucket b, long cost, int bucketSize, int restoreRate, long now, boolean apply) {
        double refill = ((now - b.lastMillis) / 1000.0d) * restoreRate
        if (refill > 0) { b.available = Math.min((double) bucketSize, b.available + refill); b.lastMillis = now }
        boolean allowed = b.available >= cost
        if (allowed && apply) b.available -= cost
        return new Decision(allowed: allowed, currentlyAvailable: b.available,
                maximumAvailable: bucketSize, restoreRate: restoreRate)
    }

    /** Production entry: resolve (or create) the caller's bucket from the cache and decide. */
    static Decision check(ExecutionContext ec, String callerKey, long cost, int bucketSize, int restoreRate, boolean apply) {
        Cache cache = ec.cache.getCache(CACHE_NAME)
        long now = System.currentTimeMillis()
        Bucket b
        synchronized (ThrottleGate.class) {
            b = (Bucket) cache.get(callerKey)
            if (b == null) { b = new Bucket(available: (double) bucketSize, lastMillis: now); cache.put(callerKey, b) }
        }
        synchronized (b) { return decide(b, cost, bucketSize, restoreRate, now, apply) }
    }
}
