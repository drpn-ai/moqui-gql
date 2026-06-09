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
 * Keys may be single-key OR composite (multi-field): a one-field fk batches with `WHERE fk IN (:keys)`,
 * a composite fk batches with `WHERE (fk1=? AND fk2=?) OR (...)` — one OR-of-ANDs branch per parent key
 * tuple — and children are grouped in memory by that key tuple. The intra-group key is the child PK minus
 * the fk fields, which is the natural ordering of children within a parent (e.g. orderItemSeqId under
 * (orderId, shipGroupSeqId)).
 */
@CompileStatic
class NestedConnectionLoader implements MappedBatchLoaderWithContext<Object, Object> {
    private final ExecutionContext ec
    private final String childEntityName
    /** child-side fk fields (parallel to the parent key). size 1 = single-key (IN); size > 1 = composite (OR-of-ANDs). */
    private final List<String> fkFields
    private final List<String> intraGroupFields
    private final boolean useClone
    private final int queryTimeoutSeconds
    private final int maxFirst
    private final int maxRowsPerLevel
    /** true -> return a plain [Type!]! node list per parent; false -> a Relay connection map. */
    private final boolean plainList
    /** #38: optional — name of a child relationship ON childEntityName; when set, fetched parent rows
     *  with zero rows in that relationship are dropped (resolved via one extra batched DISTINCT query). */
    private final String excludeEmptyRelationship

    NestedConnectionLoader(ExecutionContext ec, String childEntityName, List<String> fkFields,
                           List<String> intraGroupFields, boolean useClone, int queryTimeoutSeconds,
                           int maxFirst, int maxRowsPerLevel, boolean plainList, String excludeEmptyRelationship) {
        this.ec = ec; this.childEntityName = childEntityName; this.fkFields = fkFields
        this.intraGroupFields = intraGroupFields; this.useClone = useClone
        this.queryTimeoutSeconds = queryTimeoutSeconds; this.maxFirst = maxFirst
        this.maxRowsPerLevel = maxRowsPerLevel; this.plainList = plainList
        this.excludeEmptyRelationship = excludeEmptyRelationship
    }

    @Override
    CompletionStage<Map<Object, Object>> load(Set<Object> keys, BatchLoaderEnvironment env) {
        Map argsMap = pickArgs(env.getKeyContexts())
        int first = clampN(argsMap != null ? argsMap.get("first") : null)
        String afterStr = argsMap != null ? (String) argsMap.get("after") : null

        // one batched query for ALL parent keys in this level: single fk field -> WHERE fk IN(:keys);
        // composite key -> WHERE (fk1=? AND fk2=?) OR (...) — one OR-of-ANDs per parent key tuple.
        org.moqui.entity.EntityConditionFactory ecf = ec.entity.getConditionFactory()
        EntityFind cf = ec.entity.find(childEntityName)
        if (fkFields.size() == 1) {
            List<Object> vals = new ArrayList<Object>()
            for (Object k in keys) vals.add(k instanceof List ? ((List) k).get(0) : k)   // key may be raw or 1-tuple
            cf.condition(fkFields.get(0), EntityCondition.IN, vals)
        } else {
            List<EntityCondition> ors = new ArrayList<EntityCondition>()
            for (Object k in keys) {
                List tuple = (List) k
                List<EntityCondition> ands = new ArrayList<EntityCondition>()
                for (int i = 0; i < fkFields.size(); i++) ands.add(ecf.makeCondition(fkFields.get(i), EntityCondition.EQUALS, tuple.get(i)))
                ors.add(ecf.makeCondition(ands, EntityCondition.AND))
            }
            cf.condition(ecf.makeCondition(ors, EntityCondition.OR))
        }
        cf.orderBy(orderByList()).useClone(useClone).queryTimeout(queryTimeoutSeconds)
                .maxRows(maxRowsPerLevel).fetchSize(Math.min(maxRowsPerLevel, 1000))
        ScopeFilters.apply(cf, childEntityName, ec)   // row-scope seam (phase-1 no-op)
        EntityList rows = cf.list()

        // #38 exclude-empty: drop fetched rows (e.g. ship groups) that have zero rows in the named child
        // relationship (e.g. "items"), resolved with ONE extra batched DISTINCT query (no N+1).
        Set<Object> nonEmpty = null
        if (excludeEmptyRelationship != null && !excludeEmptyRelationship.isEmpty() && !rows.isEmpty()) {
            nonEmpty = nonEmptyIdentities(rows)
        }

        // group children by parent key (raw value for single fk, else a List tuple), preserving fetch order
        Map<Object, List<EntityValue>> grouped = new LinkedHashMap<Object, List<EntityValue>>()
        for (Object k in keys) grouped.put(k, new ArrayList<EntityValue>())
        for (EntityValue ev in rows) {
            if (nonEmpty != null && !nonEmpty.contains(rowIdentity(ev))) continue   // empty -> excluded entirely
            List<EntityValue> g = grouped.get(groupKey(ev))
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
        List<String> ob = new ArrayList<String>(fkFields)
        ob.addAll(intraGroupFields)
        return ob
    }

    /** Group key matching the DataLoader key shape: a single raw value for one fk field, else a List tuple. */
    private Object groupKey(EntityValue ev) {
        if (fkFields.size() == 1) return ev.get(fkFields.get(0))
        List<Object> t = new ArrayList<Object>(fkFields.size())
        for (String f in fkFields) t.add(ev.get(f))
        return t
    }

    // ----- #38 exclude-empty support -----
    // Parent-side identity fields of the exclude-empty relationship (on childEntityName), resolved once per
    // load() in nonEmptyIdentities() and read by rowIdentity(). Single-threaded per request, so a field is safe.
    private List<String> parentIdentityFields

    /** Identity tuple of a fetched parent row (e.g. a ship group's (orderId, shipGroupSeqId)), matching the
     *  shape returned by nonEmptyIdentities(): a single raw value for one identity field, else a List tuple. */
    private Object rowIdentity(EntityValue ev) {
        if (parentIdentityFields.size() == 1) return ev.get(parentIdentityFields.get(0))
        List<Object> t = new ArrayList<Object>(parentIdentityFields.size())
        for (String f in parentIdentityFields) t.add(ev.get(f))
        return t
    }

    /** One extra batched DISTINCT query: of the identities present in `rows`, which have >= 1 child row in
     *  the exclude-empty relationship. Returns the set of non-empty identity keys (raw value / List tuple). */
    private Set<Object> nonEmptyIdentities(EntityList rows) {
        org.moqui.impl.entity.EntityFacadeImpl efi = (org.moqui.impl.entity.EntityFacadeImpl) ec.entity
        def ri = efi.getEntityDefinition(childEntityName).getRelationshipInfo(excludeEmptyRelationship)
        // parent-side fields (read from the OISG row) and child-side fields (queried on OrderItem), parallel
        this.parentIdentityFields = new ArrayList<String>(ri.keyMap.keySet())
        List<String> childFks = new ArrayList<String>()
        for (String pf in parentIdentityFields) childFks.add(ri.keyMap.get(pf))

        // distinct candidate identities present in the fetched parent rows (de-dup the OR-of-ANDs branches)
        Set<Object> candidates = new LinkedHashSet<Object>()
        for (EntityValue ev in rows) candidates.add(rowIdentity(ev))

        org.moqui.entity.EntityConditionFactory ecf = ec.entity.getConditionFactory()
        EntityFind ef = ec.entity.find(ri.relatedEntityName)
        if (childFks.size() == 1) {
            List<Object> vals = new ArrayList<Object>()
            for (Object c in candidates) vals.add(c instanceof List ? ((List) c).get(0) : c)
            ef.condition(childFks.get(0), EntityCondition.IN, vals)
        } else {
            List<EntityCondition> ors = new ArrayList<EntityCondition>()
            for (Object c in candidates) {
                List tuple = (List) c
                List<EntityCondition> ands = new ArrayList<EntityCondition>()
                for (int i = 0; i < childFks.size(); i++) ands.add(ecf.makeCondition(childFks.get(i), EntityCondition.EQUALS, tuple.get(i)))
                ors.add(ecf.makeCondition(ands, EntityCondition.AND))
            }
            ef.condition(ecf.makeCondition(ors, EntityCondition.OR))
        }
        for (String cf in childFks) ef.selectField(cf)
        ef.distinct(true).useClone(useClone).queryTimeout(queryTimeoutSeconds)
                .maxRows(maxRowsPerLevel).fetchSize(Math.min(maxRowsPerLevel, 1000))
        ScopeFilters.apply(ef, ri.relatedEntityName, ec)
        EntityList found = ef.list()

        Set<Object> out = new HashSet<Object>()
        for (EntityValue ev in found) {
            if (childFks.size() == 1) { out.add(ev.get(childFks.get(0))) }
            else { List<Object> t = new ArrayList<Object>(childFks.size()); for (String cf in childFks) t.add(ev.get(cf)); out.add(t) }
        }
        return out
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
