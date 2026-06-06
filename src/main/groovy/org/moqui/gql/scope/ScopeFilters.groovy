package org.moqui.gql.scope

import groovy.transform.CompileStatic
import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityCondition
import org.moqui.entity.EntityFind

import java.util.function.Supplier

/** Phase-1 no-op scope filter: clients are isolated by one DB per client, so no row restriction. */
@CompileStatic
class NoOpScopeFilter implements ScopeFilter {
    EntityCondition conditionFor(String entityName, ExecutionContext ec) { return null }
}

/**
 * Holder + applicator for the active {@link ScopeFilter}. The default is a no-op; phase 2 sets a
 * caller-aware filter per request. Stored in a ThreadLocal because one request = one thread (decision
 * 10) and concurrent requests must not see each other's scope. {@link #apply} is called at every
 * entity-find site in the executor so the seam is provably live (ScopeSeamTests).
 */
@CompileStatic
class ScopeFilters {
    private static final ThreadLocal<ScopeFilter> ACTIVE =
            ThreadLocal.withInitial({ new NoOpScopeFilter() } as Supplier<ScopeFilter>)

    static ScopeFilter current() { return ACTIVE.get() }
    static void set(ScopeFilter f) { ACTIVE.set(f != null ? f : new NoOpScopeFilter()) }
    static void reset() { ACTIVE.remove() }

    /** Consult the active filter and AND any returned condition into the find. */
    static void apply(EntityFind ef, String entityName, ExecutionContext ec) {
        EntityCondition c = ACTIVE.get().conditionFor(entityName, ec)
        if (c != null) ef.condition(c)
    }
}
