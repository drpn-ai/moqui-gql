package org.moqui.gql.exec

import groovy.transform.CompileStatic
import org.dataloader.BatchLoaderEnvironment
import org.dataloader.MappedBatchLoaderWithContext
import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityCondition
import org.moqui.entity.EntityFind
import org.moqui.entity.EntityList
import org.moqui.entity.EntityValue
import org.moqui.gql.scope.ScopeFilters

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/**
 * Batched loader for a single-object (has-one) nested edge — e.g. Order.billToCustomer. One
 * `WHERE fk IN(:keys)` query for every parent at the level (no N+1), returning the first child row per
 * parent (or null). The has-one analogue of NestedConnectionLoader; no ordering/paging, just one per
 * parent. Reads go through the replica clone inside the request transaction; the row-scope seam applies.
 */
@CompileStatic
class NestedSingleLoader implements MappedBatchLoaderWithContext<Object, Object> {
    private final ExecutionContext ec
    private final String childEntityName
    private final String fkField
    private final boolean useClone
    private final int maxRowsPerLevel

    NestedSingleLoader(ExecutionContext ec, String childEntityName, String fkField,
                       boolean useClone, int maxRowsPerLevel) {
        this.ec = ec; this.childEntityName = childEntityName; this.fkField = fkField
        this.useClone = useClone; this.maxRowsPerLevel = maxRowsPerLevel
    }

    @Override
    CompletionStage<Map<Object, Object>> load(Set<Object> keys, BatchLoaderEnvironment env) {
        EntityFind ef = ec.entity.find(childEntityName)
                .condition(fkField, EntityCondition.IN, new ArrayList<Object>(keys))
                .useClone(useClone)
                .maxRows(maxRowsPerLevel).fetchSize(Math.min(maxRowsPerLevel, 1000))
        ScopeFilters.apply(ef, childEntityName, ec)   // row-scope seam (phase-1 no-op)
        EntityList rows = ef.list()

        Map<Object, Object> out = new LinkedHashMap<Object, Object>()
        for (Object k in keys) out.put(k, null)            // default: no related object
        for (EntityValue ev in rows) {
            Object k = ev.get(fkField)
            if (out.containsKey(k) && out.get(k) == null) out.put(k, ev.getMap())   // first row per parent
        }
        return CompletableFuture.completedFuture(out)
    }
}
