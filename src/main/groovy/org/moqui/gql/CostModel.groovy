package org.moqui.gql

import graphql.analysis.FieldComplexityCalculator
import graphql.analysis.FieldComplexityEnvironment
import groovy.transform.CompileStatic

/**
 * Per-field cost for graphql-java's MaxQueryComplexityInstrumentation (decision 8).
 *  - connection/list edge: first * (1 + childComplexity)  (fan-out multiplies down the tree)
 *  - service-backed field:  serviceFixedCost + childComplexity  (opaque to static analysis — never cheap)
 *  - scalar leaf:           1
 * All accumulation is in `long` and saturated to `costCeiling` so a pathological query can never
 * overflow `int` and wrap negative past the gate (review C3). `first` is clamped to `maxFirst`.
 * The builder (Task 7) populates listFields / serviceBackedFields.
 */
@CompileStatic
class CostModel implements FieldComplexityCalculator {
    int serviceFixedCost = 25
    int maxFirst = 100
    int unindexedPenalty = 50
    long costCeiling = 1_000_000L
    Set<String> serviceBackedFields = new HashSet<String>()
    Set<String> listFields = new HashSet<String>()

    int serviceCost(int childComplexity) { return saturate((long) serviceFixedCost + (long) childComplexity) }

    int listComplexity(int first, int childComplexity) {
        int f = (first <= 0 || first > maxFirst) ? maxFirst : first
        return saturate((long) f * (1L + (long) childComplexity))
    }

    /** Clamp to [0, costCeiling]; negative (overflow) also clamps up to the ceiling so it fails the gate. */
    int saturate(long v) { return (int) Math.min(v < 0L ? costCeiling : v, costCeiling) }

    @Override
    int calculate(FieldComplexityEnvironment env, int childComplexity) {
        String name = env.getField() != null ? env.getField().getName() : null
        if (name != null && serviceBackedFields.contains(name)) return serviceCost(childComplexity)
        if (name != null && listFields.contains(name)) {
            Object f = env.getArguments() != null ? env.getArguments().get("first") : null
            int first = (f instanceof Number) ? ((Number) f).intValue() : maxFirst
            return listComplexity(first, childComplexity)
        }
        return saturate(1L + (long) childComplexity)
    }
}
