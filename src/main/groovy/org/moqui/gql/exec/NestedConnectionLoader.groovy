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
 * Batched loader for a nested has-many edge. graphql-java collects every parent key at one level of
 * the query, then calls this once: a SINGLE `WHERE fk IN (:keys)` query replaces N per-parent queries
 * (kills the N+1). Children come back ordered by (fk, intra-group key); we group in memory and slice
 * each parent into its own Relay connection.
 *
 * The field args (first / after) are identical for every parent in the level (one selection), so they
 * are read from any key context and applied per parent in memory. The whole batch is capped at
 * maxRowsPerLevel as a runtime guard (logged by the caller's governor in C4).
 *
 * Phase-1 scope: single-key relationships (one fk field); the intra-group key is the child PK minus
 * the fk, which is the natural ordering of children within a parent (e.g. orderItemSeqId under orderId).
 */
@CompileStatic
class NestedConnectionLoader implements MappedBatchLoaderWithContext<Object, Object> {
    private final ExecutionContext ec
    private final String childEntityName
    private final String fkField
    private final List<String> intraGroupFields
    private final boolean useClone
    private final int queryTimeoutSeconds
    private final int maxFirst
    private final int maxRowsPerLevel
    /** true -> return a plain [Type!]! node list per parent; false -> a Relay connection map. */
    private final boolean plainList

    NestedConnectionLoader(ExecutionContext ec, String childEntityName, String fkField,
                           List<String> intraGroupFields, boolean useClone, int queryTimeoutSeconds,
                           int maxFirst, int maxRowsPerLevel, boolean plainList) {
        this.ec = ec; this.childEntityName = childEntityName; this.fkField = fkField
        this.intraGroupFields = intraGroupFields; this.useClone = useClone
        this.queryTimeoutSeconds = queryTimeoutSeconds; this.maxFirst = maxFirst
        this.maxRowsPerLevel = maxRowsPerLevel; this.plainList = plainList
    }

    @Override
    CompletionStage<Map<Object, Object>> load(Set<Object> keys, BatchLoaderEnvironment env) {
        Map argsMap = pickArgs(env.getKeyContexts())
        int first = clampN(argsMap != null ? argsMap.get("first") : null)
        String afterStr = argsMap != null ? (String) argsMap.get("after") : null

        // one batched query for ALL parent keys in this level
        EntityFind cf = ec.entity.find(childEntityName)
                .condition(fkField, EntityCondition.IN, new ArrayList<Object>(keys))
                .orderBy(orderByList())
                .useClone(useClone).queryTimeout(queryTimeoutSeconds)
                .maxRows(maxRowsPerLevel).fetchSize(Math.min(maxRowsPerLevel, 1000))
        ScopeFilters.apply(cf, childEntityName, ec)   // row-scope seam (phase-1 no-op)
        EntityList rows = cf.list()

        // group children by parent key, preserving the (fk, intra-group) fetch order within each group
        Map<Object, List<EntityValue>> grouped = new LinkedHashMap<Object, List<EntityValue>>()
        for (Object k in keys) grouped.put(k, new ArrayList<EntityValue>())
        for (EntityValue ev in rows) {
            List<EntityValue> g = grouped.get(ev.get(fkField))
            if (g != null) g.add(ev)
        }

        Map<Object, Object> out = new LinkedHashMap<Object, Object>()
        for (Map.Entry<Object, List<EntityValue>> e in grouped.entrySet()) {
            out.put(e.getKey(), buildResult(e.getValue(), first, afterStr))
        }
        return CompletableFuture.completedFuture(out)
    }

    private Object buildResult(List<EntityValue> all, int first, String afterStr) {
        if (plainList) {
            List<Map> nodes = new ArrayList<Map>()
            for (EntityValue ev in all) { if (nodes.size() >= first) break; nodes.add(ev.getMap()) }
            return nodes
        }
        String afterKey = null
        if (afterStr != null && !afterStr.isEmpty()) {
            try { afterKey = Cursor.decode(afterStr).sortValue } catch (Exception ignored) { afterKey = null }
        }
        List<Map> edges = new ArrayList<Map>()
        boolean more = false
        for (EntityValue ev in all) {
            String key = intraKey(ev)
            if (afterKey != null && key.compareTo(afterKey) <= 0) continue
            if (edges.size() >= first) { more = true; break }
            edges.add([cursor: Cursor.encode(key, [(Object) key]), node: ev.getMap()] as Map)
        }
        Map pageInfo = [
                hasNextPage    : (Object) more,
                hasPreviousPage: (Object) (afterStr != null && !afterStr.isEmpty()),
                startCursor    : edges.isEmpty() ? null : edges.get(0).get("cursor"),
                endCursor      : edges.isEmpty() ? null : edges.get(edges.size() - 1).get("cursor")
        ] as Map
        return [edges: edges, pageInfo: pageInfo] as Map
    }

    /** Ordering key of a child within its parent group (child PK minus the fk). */
    private String intraKey(EntityValue ev) {
        if (intraGroupFields.size() == 1) {
            Object v = ev.get(intraGroupFields.get(0)); return v != null ? v.toString() : ""
        }
        StringBuilder sb = new StringBuilder()
        for (int i = 0; i < intraGroupFields.size(); i++) {
            if (i > 0) sb.append((char) 1)   // control-char separator that won't collide with field data
            Object v = ev.get(intraGroupFields.get(i)); sb.append(v != null ? v.toString() : "")
        }
        return sb.toString()
    }

    private List<String> orderByList() {
        List<String> ob = new ArrayList<String>()
        ob.add(fkField)
        ob.addAll(intraGroupFields)
        return ob
    }

    private static Map pickArgs(Map<Object, Object> keyContexts) {
        if (keyContexts == null) return null
        for (Object v in keyContexts.values()) if (v instanceof Map) return (Map) v
        return null
    }

    private int clampN(Object requested) {
        int f = (requested instanceof Number) ? ((Number) requested).intValue() : maxFirst
        if (f <= 0 || f > maxFirst) f = maxFirst
        return f
    }
}
