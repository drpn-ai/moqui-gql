package org.moqui.gql.scope

import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityCondition

/**
 * Row-scope seam for GraphQL reads. The engine consults the current ScopeFilter before EVERY entity
 * find and ANDs any returned condition into the query. Phase 1 is a no-op: clients are isolated by a
 * dedicated database per client (decision: no multi-tenant DB), so there is nothing to add. The seam
 * exists so phase-2 row-level scoping (e.g. caller → productStore/facility restriction) plugs in
 * without touching every resolver. ScopeSeamTests proves the hook is live.
 */
interface ScopeFilter {
    /** Additional scope condition for an entity find, or null for no restriction (phase-1 no-op). */
    EntityCondition conditionFor(String entityName, ExecutionContext ec)
}
