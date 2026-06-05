package org.moqui.gql.exec

import groovy.transform.CompileStatic
import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityCondition
import org.moqui.entity.EntityCondition.ComparisonOperator
import org.moqui.entity.EntityConditionFactory
import org.moqui.entity.EntityFind
import org.moqui.entity.EntityList
import org.moqui.entity.EntityValue
import org.moqui.gql.GqlField
import org.moqui.gql.GqlRootQuery
import org.moqui.gql.GqlType
import org.moqui.gql.GqlValidationException
import org.moqui.gql.scope.ScopeFilters
import org.moqui.gql.search.SearchQueryParser
import org.moqui.gql.search.SearchTerm
import org.moqui.impl.context.ExecutionContextImpl
import org.moqui.impl.entity.EntityDefinition
import org.moqui.impl.entity.EntityFacadeImpl

/**
 * Resolves a root list (Relay connection) query against the DB:
 *   1. translate declared `query:` search terms into typed EntityConditions (Q3 declare-and-control),
 *   2. order by sortKey (+ PK tiebreaker) honoring `reverse`,
 *   3. keyset-paginate with the opaque cursor (no OFFSET — deep pages stay cheap),
 *   4. return a Relay connection map {edges:[{cursor,node}], pageInfo}.
 *
 * Reads go through the replica clone (useClone) inside the engine's single read-only transaction.
 * Search values are converted to each field's Java type via EntityDefinition.convertFieldString —
 * Moqui does NOT auto-convert condition values (FieldInfo binds a String as-is against a Timestamp
 * column), so this conversion is required for correct comparisons, not cosmetic.
 *
 * Forward paging (first/after) in this slice; backward (last/before) is layered next.
 */
@CompileStatic
class ConnectionResolver {
    private final ExecutionContext ec
    private final boolean useClone
    private final int queryTimeoutSeconds
    private final int maxFirst
    private final SearchQueryParser searchParser = new SearchQueryParser()

    ConnectionResolver(ExecutionContext ec, boolean useClone, int queryTimeoutSeconds, int maxFirst) {
        this.ec = ec
        this.useClone = useClone
        this.queryTimeoutSeconds = queryTimeoutSeconds
        this.maxFirst = maxFirst
    }

    Map resolveRoot(GqlRootQuery q, GqlType type, Map<String, Object> args) {
        String entityName = q.entityName ?: (type != null ? type.entityName : null)
        if (entityName == null) throw new GqlValidationException("SCHEMA_ERROR",
                "no entity for root query " + q.name, ['query': (Object) q.name])

        EntityFacadeImpl efi = (EntityFacadeImpl) ec.entity
        EntityDefinition ed = efi.getEntityDefinition(entityName)
        if (ed == null) throw new GqlValidationException("SCHEMA_ERROR",
                "unknown entity " + entityName, ['entity': (Object) entityName])
        List<String> pkFields = new ArrayList<String>(ed.getPkFieldNames())
        if (pkFields.isEmpty()) throw new GqlValidationException("UNSUPPORTED_PK",
                "root connection entity has no primary key: " + entityName, ['entity': (Object) entityName])

        EntityConditionFactory ecf = ec.entity.getConditionFactory()

        // ----- ordering: sortKey enum -> entity field, default first PK; reverse flips connection order.
        //   The deterministic total order is (sortField, then every remaining PK field) so keyset paging
        //   is stable even with non-unique sort values and composite PKs (e.g. the parties view). -----
        String sortKeyArg = (String) args.get("sortKey")
        String sortField = (sortKeyArg != null && q.sortKeys.containsKey(sortKeyArg)) ? q.sortKeys.get(sortKeyArg) : pkFields.get(0)
        boolean connDescending = Boolean.TRUE.equals(args.get("reverse"))
        List<String> orderingFields = new ArrayList<String>()
        orderingFields.add(sortField)
        for (String pf in pkFields) if (pf != sortField) orderingFields.add(pf)

        // ----- declared search terms -> typed conditions (AND) — direction-independent -----
        List<SearchTerm> terms = searchParser.parse((String) args.get("query"), q)
        List<EntityCondition> conds = new ArrayList<EntityCondition>()
        for (SearchTerm term in terms) {
            GqlField fld = type != null ? type.fields.get(term.key) : null
            String entityField = (fld != null && fld.entityField) ? fld.entityField : term.key
            if (term.comparator == "in") {
                List<Object> vals = new ArrayList<Object>()
                for (String v in term.values) vals.add(convVal(ed, entityField, v))
                conds.add(ecf.makeCondition(entityField, EntityCondition.IN, vals))
            } else {
                Object v = convVal(ed, entityField, term.values.isEmpty() ? null : term.values.get(0))
                conds.add(ecf.makeCondition(entityField, opFor(term.comparator), v))
            }
        }

        // ----- forward (first/after) vs backward (last/before): backward scans in the reverse of the
        //       connection order from the `before` cursor, then the slice is reversed back to order. -----
        Integer first = asInt(args.get("first"))
        Integer last = asInt(args.get("last"))
        boolean backward = (last != null && first == null)
        int limit = clampN(backward ? last : first)
        boolean scanDescending = backward ? !connDescending : connDescending
        String cursorStr = (String) (backward ? args.get("before") : args.get("after"))
        if (cursorStr != null && !cursorStr.isEmpty()) {
            Cursor cur
            try { cur = Cursor.decode(cursorStr) }
            catch (Exception e) { throw new GqlValidationException("INVALID_CURSOR", "invalid cursor", ['cursor': (Object) cursorStr]) }
            // re-type the cursor tuple to match orderingFields: [sortField] + remaining PK fields
            List<Object> orderingValues = new ArrayList<Object>()
            orderingValues.add((cur.typeTag == "T") ? cur.sortValueTyped() : convVal(ed, sortField, cur.sortValue))
            for (int i = 1; i < orderingFields.size(); i++) {
                String f = orderingFields.get(i)
                int idx = pkFields.indexOf(f)
                String raw = (idx >= 0 && idx < cur.pkValues.size()) ? cur.pkValues.get(idx) : null
                orderingValues.add(convVal(ed, f, raw))
            }
            conds.add(keysetAfter(ecf, orderingFields, orderingValues, scanDescending))
        }

        EntityCondition where = conds.isEmpty() ? null :
                (conds.size() == 1 ? conds.get(0) : ecf.makeCondition(conds, EntityCondition.AND))

        // ----- execute: fetch limit+1 in scan order to detect a further page; fetchSize<=maxRows (MySQL quirk) -----
        EntityFind ef = ec.entity.find(entityName)
        if (where != null) ef.condition(where)
        ScopeFilters.apply(ef, entityName, ec)   // row-scope seam (phase-1 no-op)
        String dir = scanDescending ? "-" : ""
        for (String f in orderingFields) ef.orderBy(dir + f)
        ef.useClone(useClone).queryTimeout(queryTimeoutSeconds).maxRows(limit + 1).fetchSize(limit + 1)
        EntityList rows = ef.list()

        // ----- build edges (in connection order) + pageInfo -----
        boolean moreBeyond = rows.size() > limit
        int n = Math.min(rows.size(), limit)
        List<EntityValue> slice = new ArrayList<EntityValue>(rows.subList(0, n))
        if (backward) Collections.reverse(slice)   // scan was reversed; restore connection order
        List<Map> edges = new ArrayList<Map>(n)
        for (EntityValue ev in slice) {
            edges.add([cursor: Cursor.encode(ev.get(sortField), pkVals(ev, pkFields)), node: ev.getMap()] as Map)
        }
        boolean hasNextPage = backward ? (cursorStr != null && !cursorStr.isEmpty()) : moreBeyond
        boolean hasPreviousPage = backward ? moreBeyond : (cursorStr != null && !cursorStr.isEmpty())
        Map pageInfo = [
                hasNextPage    : (Object) hasNextPage,
                hasPreviousPage: (Object) hasPreviousPage,
                startCursor    : edges.isEmpty() ? null : edges.get(0).get("cursor"),
                endCursor      : edges.isEmpty() ? null : edges.get(edges.size() - 1).get("cursor")
        ] as Map
        return [edges: edges, pageInfo: pageInfo] as Map
    }

    /** Keyset "row strictly after the cursor" over an ordered tuple of fields (sortField + PK tiebreakers),
     *  as the lexicographic OR-form: for each i,  f0=v0 AND .. AND f{i-1}=v{i-1} AND fi STRICT vi.
     *  Collapses to a single comparison for a one-field tuple (e.g. sort by single PK). */
    private static EntityCondition keysetAfter(EntityConditionFactory ecf, List<String> fields,
                                               List<Object> values, boolean descending) {
        ComparisonOperator strict = descending ? EntityCondition.LESS_THAN : EntityCondition.GREATER_THAN
        List<EntityCondition> ors = new ArrayList<EntityCondition>()
        for (int i = 0; i < fields.size(); i++) {
            List<EntityCondition> ands = new ArrayList<EntityCondition>()
            for (int j = 0; j < i; j++) ands.add(ecf.makeCondition(fields.get(j), EntityCondition.EQUALS, values.get(j)))
            ands.add(ecf.makeCondition(fields.get(i), strict, values.get(i)))
            ors.add(ands.size() == 1 ? ands.get(0) : ecf.makeCondition(ands, EntityCondition.AND))
        }
        return ors.size() == 1 ? ors.get(0) : ecf.makeCondition(ors, EntityCondition.OR)
    }

    private static List<Object> pkVals(EntityValue ev, List<String> pkFields) {
        List<Object> out = new ArrayList<Object>(pkFields.size())
        for (String f in pkFields) out.add(ev.get(f))
        return out
    }

    private Object convVal(EntityDefinition ed, String field, String value) {
        if (value == null) return null
        return ed.convertFieldString(field, value, (ExecutionContextImpl) ec)
    }

    private int clampN(Integer requested) {
        int f = (requested != null) ? requested.intValue() : maxFirst
        if (f <= 0 || f > maxFirst) f = maxFirst
        return f
    }

    private static Integer asInt(Object v) { return (v instanceof Number) ? Integer.valueOf(((Number) v).intValue()) : (Integer) null }

    private static ComparisonOperator opFor(String comparator) {
        switch (comparator) {
            case "eq": return EntityCondition.EQUALS
            case "in": return EntityCondition.IN
            case "gt": return EntityCondition.GREATER_THAN
            case "gte": return EntityCondition.GREATER_THAN_EQUAL_TO
            case "lt": return EntityCondition.LESS_THAN
            case "lte": return EntityCondition.LESS_THAN_EQUAL_TO
            default: throw new GqlValidationException("OPERATOR_NOT_ALLOWED",
                    "unknown comparator " + comparator, ['comparator': (Object) comparator])
        }
    }
}
