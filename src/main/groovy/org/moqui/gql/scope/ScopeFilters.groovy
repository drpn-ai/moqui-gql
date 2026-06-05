package org.moqui.gql.scope

import groovy.transform.CompileStatic
import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityCondition
import org.moqui.entity.EntityFind

/** Phase-1 no-op scope filter: clients are isolated by one DB per client, so no row restriction. */
@CompileStatic
class NoOpScopeFilter implements ScopeFilter {
    EntityCondition conditionFor(String entityName, ExecutionContext ec) { return null }
}

/**
 * Holder + applicator for the active {@link ScopeFilter}. The default is a no-op; phase 2 swaps in a
 * caller-aware filter. {@link #apply} is called at every entity-find site in the executor so the seam
 * is provably live (ScopeSeamTests) and so phase-2 scoping needs no resolver changes.
 */
@CompileStatic
class ScopeFilters {
    private static volatile ScopeFilter active = new NoOpScopeFilter()

    static ScopeFilter current() { return active }
    static void set(ScopeFilter f) { active = (f != null ? f : new NoOpScopeFilter()) }
    static void reset() { active = new NoOpScopeFilter() }

    /** Consult the active filter and AND any returned condition into the find. */
    static void apply(EntityFind ef, String entityName, ExecutionContext ec) {
        EntityCondition c = active.conditionFor(entityName, ec)
        if (c != null) ef.condition(c)
    }
}
