package org.moqui.gql

import graphql.ExecutionResult
import graphql.GraphQLError
import graphql.GraphqlErrorBuilder
import graphql.execution.AbortExecutionException
import graphql.execution.ExecutionContext as GraphQLExecutionContext
import graphql.execution.instrumentation.InstrumentationContext
import graphql.execution.instrumentation.InstrumentationState
import graphql.execution.instrumentation.SimplePerformantInstrumentation
import graphql.execution.instrumentation.parameters.InstrumentationExecuteOperationParameters
import graphql.execution.instrumentation.parameters.InstrumentationFieldFetchParameters
import graphql.language.ArrayValue
import graphql.language.Field
import graphql.language.FragmentDefinition
import graphql.language.FragmentSpread
import graphql.language.InlineFragment
import graphql.language.IntValue
import graphql.language.OperationDefinition
import graphql.language.Selection
import graphql.language.SelectionSet
import graphql.language.StringValue
import graphql.language.VariableReference
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLList
import graphql.schema.GraphQLNonNull
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLType
import org.moqui.context.ExecutionContext
import org.moqui.gql.policy.ThrottleGate
import org.moqui.gql.search.SearchQueryParser

/**
 * Pre-execution query governor (C4, decision 8). Runs in beginExecuteOperation — after parse/validate,
 * before any data fetching — so a rejected query NEVER touches the DB. Walks the operation once with
 * coerced variables and enforces, with stable agent-actionable error codes (extensions.code):
 *   DEPTH_EXCEEDED · COST_EXCEEDED · FIRST_REQUIRED · FIRST_TOO_LARGE · FIELD_NOT_FILTERABLE ·
 *   OPERATOR_NOT_ALLOWED · BATCH_LIMIT_EXCEEDED
 * Depth counts entity (node) levels, skipping Relay plumbing (Connection/Edge/PageInfo). Cost mirrors
 * the CostModel: list fan-out multiplies (first * (1 + childCost)), service-backed fields are a fixed
 * high cost, unindexed declared filters add a penalty. Fan-out is threaded down so a service-backed
 * field under a wide list trips the batch-key cap. All violations are collected and thrown together.
 * Not @CompileStatic — AST/closure navigation is cleaner dynamic; runs once per request.
 */
class GovernorInstrumentation extends SimplePerformantInstrumentation {
    private final ExecutionContext ec
    private final BuiltSchema built
    private final SchemaArtifact art
    private final IndexClassifier indexClassifier
    private final SearchQueryParser searchParser = new SearchQueryParser()
    final int maxDepth, maxCost, maxFirst, serviceBatchKeyLimit, maxInventoryKeys, unindexedFilterPenalty, serviceFixedCost
    private final long wallClockBudgetMs
    private final long deadlineNanos
    private final int bucketSize, restoreRate
    /** Static cost estimate computed during the pre-execution walk; read by the engine for extensions.cost. */
    volatile long estimatedCost = 0L
    /** Live throttle decision (per-caller bucket) computed during the walk; read by the engine for throttleStatus. */
    volatile ThrottleGate.Decision throttleDecision = null
    private static final long COST_CEILING = 100_000_000L

    GovernorInstrumentation(ExecutionContext ec, BuiltSchema built, Map<String, Integer> cfg) {
        this.ec = ec; this.built = built; this.art = built.artifact
        this.indexClassifier = new IndexClassifier(ec)
        this.maxDepth = cfg.maxDepth; this.maxCost = cfg.maxCost; this.maxFirst = cfg.maxFirst
        this.serviceBatchKeyLimit = cfg.serviceBatchKeyLimit; this.maxInventoryKeys = cfg.maxInventoryKeys
        this.unindexedFilterPenalty = cfg.unindexedFilterPenalty; this.serviceFixedCost = cfg.serviceFixedCost
        this.wallClockBudgetMs = (cfg.wallClockBudgetMs != null ? cfg.wallClockBudgetMs : 30000)
        this.bucketSize = (cfg.bucketSize != null ? cfg.bucketSize : maxCost)
        this.restoreRate = (cfg.restoreRate != null ? cfg.restoreRate : 50)
        // per-request deadline (instance is built per request); System.nanoTime is fine in component code
        this.deadlineNanos = System.nanoTime() + wallClockBudgetMs * 1_000_000L
    }

    /** Runtime backstop: abort mid-fetch if the request blows its wall-clock budget (a query that
     *  passed the static gate but still runs long). Pairs with the per-statement queryTimeout. */
    @Override
    InstrumentationContext<Object> beginFieldFetch(InstrumentationFieldFetchParameters params, InstrumentationState state) {
        if (System.nanoTime() > deadlineNanos)
            throw new AbortExecutionException([(GraphQLError) err(
                    "request exceeded wall-clock budget of " + wallClockBudgetMs + "ms", "DEADLINE_EXCEEDED",
                    [maxMillis: (Object) wallClockBudgetMs])])
        return super.beginFieldFetch(params, state)
    }

    @Override
    InstrumentationContext<ExecutionResult> beginExecuteOperation(
            InstrumentationExecuteOperationParameters params, InstrumentationState state) {
        GraphQLExecutionContext gqlCtx = params.getExecutionContext()
        OperationDefinition op = gqlCtx.getOperationDefinition()
        if (op == null || op.getOperation() != OperationDefinition.Operation.QUERY) {
            return super.beginExecuteOperation(params, state)   // reads only; mutations/subscriptions out of scope
        }
        Walk w = new Walk(vars: gqlCtx.getCoercedVariables().toMap(), frags: gqlCtx.getFragmentsByName())
        this.currentVars = w.vars
        long cost = w.cost(op.getSelectionSet(), built.schema.getQueryType(), 0, 1L)
        this.estimatedCost = cost

        if (w.maxDepthSeen > maxDepth)
            w.errors.add(err("query depth " + w.maxDepthSeen + " exceeds max " + maxDepth, "DEPTH_EXCEEDED",
                    [depth: w.maxDepthSeen, maxDepth: maxDepth]))
        if (cost > maxCost)
            w.errors.add(err("query cost " + cost + " exceeds max " + maxCost, "COST_EXCEEDED",
                    [estimatedCost: cost, maxCost: maxCost]))

        // throttle is the LAST gate: debit the per-caller bucket only if the query would otherwise execute
        boolean willExecute = w.errors.isEmpty()
        String callerKey = ec.user?.userId ?: "anonymous"
        ThrottleGate.Decision td = ThrottleGate.check(ec, callerKey, cost, bucketSize, restoreRate, willExecute)
        this.throttleDecision = td
        if (willExecute && !td.allowed)
            w.errors.add(err("throttled: query cost " + cost + " exceeds available " + (long) td.currentlyAvailable +
                    " (max " + bucketSize + ", restore " + restoreRate + "/s)", "THROTTLED",
                    [cost: cost, currentlyAvailable: (long) td.currentlyAvailable, maximumAvailable: bucketSize, restoreRate: restoreRate]))

        if (!w.errors.isEmpty()) throw new AbortExecutionException(w.errors)
        return super.beginExecuteOperation(params, state)
    }

    /** Per-request walk state + the recursive cost/limit evaluator. */
    private class Walk {
        Map<String, Object> vars
        Map<String, FragmentDefinition> frags
        List<GraphQLError> errors = new ArrayList<>()
        int maxDepthSeen = 0

        /** Cost of a selection under `type`; `depth` = entity nesting so far, `fanout` = product of
         *  enclosing list `first`s (the service-backed blast radius). Records violations into errors. */
        long cost(SelectionSet sel, GraphQLObjectType type, int depth, long fanout) {
            if (sel == null || type == null) return 0L
            long total = 0L
            for (Selection s in sel.getSelections()) {
                if (s instanceof Field) {
                    total = sat(total + fieldCost((Field) s, type, depth, fanout))
                } else if (s instanceof InlineFragment) {
                    InlineFragment inf = (InlineFragment) s
                    GraphQLObjectType ft = fragType(inf.getTypeCondition()?.getName(), type)
                    total = sat(total + cost(inf.getSelectionSet(), ft, depth, fanout))
                } else if (s instanceof FragmentSpread) {
                    FragmentDefinition fd = frags?.get(((FragmentSpread) s).getName())
                    if (fd != null) {
                        GraphQLObjectType ft = fragType(fd.getTypeCondition()?.getName(), type)
                        total = sat(total + cost(fd.getSelectionSet(), ft, depth, fanout))
                    }
                }
            }
            return total
        }

        private long fieldCost(Field field, GraphQLObjectType parentType, int depth, long fanout) {
            GraphQLFieldDefinition fd = parentType.getFieldDefinition(field.getName())
            if (fd == null) return 0L
            boolean atRoot = "Query".equals(parentType.getName())
            GraphQLType named = unwrapAll(fd.getType())
            String typeName = (named instanceof GraphQLObjectType) ? ((GraphQLObjectType) named).getName() : null

            // ---- root inventory-style service root: cap product*facility pairs ----
            GqlRootQuery rootQ = atRoot ? art.rootQueries.get(field.getName()) : null
            if (rootQ != null && rootQ.serviceBacked) {
                checkInventoryKeyCap(field, rootQ)
                long child = (named instanceof GraphQLObjectType) ? cost(nodeSelOrSelf(field), (GraphQLObjectType) named, depth + 1, fanout) : 0L
                bumpDepth(depth + 1)
                return sat((long) serviceFixedCost + child)
            }

            // ---- service-backed scalar/object field (decision 12) ----
            GqlField gf = (!atRoot) ? art.types.get(parentType.getName())?.fields?.get(field.getName()) : null
            if (gf != null && gf.isServiceBacked()) {
                if (fanout > serviceBatchKeyLimit) errors.add(err(
                        "service-backed field '" + field.getName() + "' would resolve " + fanout + " keys; exceeds batch limit " + serviceBatchKeyLimit,
                        "BATCH_LIMIT_EXCEEDED", [field: field.getName(), keys: fanout, limit: serviceBatchKeyLimit]))
                return (long) serviceFixedCost
            }

            // ---- connection field (Relay): requires first/last; multiplies fan-out ----
            if (typeName != null && typeName.endsWith("Connection")) {
                Integer first = intArg(field, "first"), last = intArg(field, "last")
                if (first == null && last == null) errors.add(err(
                        "connection field '" + field.getName() + "' requires 'first:' or 'last:' (1.." + maxFirst + ")",
                        "FIRST_REQUIRED", [field: field.getName(), maxFirst: maxFirst]))
                checkTooLarge(field, first, last)
                int eff = clampFirst(first != null ? first : last)
                if (atRoot) validateQuery(field, rootQ)
                GraphQLObjectType nodeType = nodeType((GraphQLObjectType) named)
                long child = nodeType != null ? cost(nodeSel(field), nodeType, depth + 1, sat(fanout * eff)) : 0L
                bumpDepth(depth + 1)
                long c = sat((long) eff * (1L + child))
                if (atRoot && rootQ != null) c = sat(c + unindexedPenalty(field, rootQ))
                return c
            }

            // ---- plain bounded list ([T!]!): optional first (has a default), still capped ----
            if (isListType(fd.getType()) && (named instanceof GraphQLObjectType)) {
                Integer first = intArg(field, "first")
                checkTooLarge(field, first, null)
                Integer dflt = (!atRoot) ? art.types.get(parentType.getName())?.edges?.get(field.getName())?.firstDefault : null
                int eff = clampFirst(first != null ? first : dflt)
                long child = cost(field.getSelectionSet(), (GraphQLObjectType) named, depth + 1, sat(fanout * eff))
                bumpDepth(depth + 1)
                return sat((long) eff * (1L + child))
            }

            // ---- single object (by-pk root, single edge, by-identification) ----
            if (named instanceof GraphQLObjectType) {
                long child = cost(field.getSelectionSet(), (GraphQLObjectType) named, depth + 1, fanout)
                bumpDepth(depth + 1)
                return sat(1L + child)
            }

            // ---- scalar leaf ----
            return 1L
        }

        /** Apply a root connection's query: validation (declared keys/ops) — pre-execution. */
        private void validateQuery(Field field, GqlRootQuery rootQ) {
            if (rootQ == null) return
            String q = strArg(field, "query")
            if (q == null || q.trim().isEmpty()) return
            try {
                searchParser.parse(q, rootQ)
            } catch (GqlValidationException e) {
                errors.add(err(e.getMessage(), e.code, e.ext ?: [:]))
            }
        }

        /** Unindexed declared filters add a cost penalty (config: unindexedFilterPenalty). */
        private long unindexedPenalty(Field field, GqlRootQuery rootQ) {
            String q = strArg(field, "query")
            if (q == null || q.trim().isEmpty()) return 0L
            String entityName = rootQ.entityName ?: art.types.get(rootQ.targetType)?.entityName
            if (entityName == null) return 0L
            long penalty = 0L
            List<?> terms
            try { terms = searchParser.parse(q, rootQ) } catch (Exception ignored) { return 0L }
            for (def t in terms) {
                String ef = art.types.get(rootQ.targetType)?.fields?.get(t.key)?.entityField ?: t.key
                if (!indexClassifier.isIndexed(entityName, ef)) penalty = sat(penalty + unindexedFilterPenalty)
            }
            return penalty
        }

        private void checkInventoryKeyCap(Field field, GqlRootQuery rootQ) {
            int prod = listArgSize(field, "productIds")
            int fac = listArgSize(field, "facilityIds")
            if (prod < 0) return
            long pairs = (long) prod * (fac > 0 ? fac : 1)
            if (pairs > maxInventoryKeys) errors.add(err(
                    "inventoryLevels would resolve " + pairs + " product*facility pairs; exceeds limit " + maxInventoryKeys,
                    "BATCH_LIMIT_EXCEEDED", [field: field.getName(), keys: pairs, limit: maxInventoryKeys]))
        }

        private void checkTooLarge(Field field, Integer first, Integer last) {
            if ((first != null && first > maxFirst) || (last != null && last > maxFirst)) {
                int bad = (first != null && first > maxFirst) ? first : last
                errors.add(err("'first: " + bad + "' on '" + field.getName() + "' exceeds maxFirst " + maxFirst,
                        "FIRST_TOO_LARGE", [field: field.getName(), maxFirst: maxFirst]))
            }
        }

        private void bumpDepth(int d) { if (d > maxDepthSeen) maxDepthSeen = d }

        private GraphQLObjectType fragType(String name, GraphQLObjectType fallback) {
            if (name == null) return fallback
            def t = built.schema.getType(name)
            return (t instanceof GraphQLObjectType) ? (GraphQLObjectType) t : fallback
        }
    }

    // ---- AST/type helpers ----
    private static SelectionSet nodeSel(Field connField) {
        Field edges = subField(connField.getSelectionSet(), "edges")
        Field node = edges != null ? subField(edges.getSelectionSet(), "node") : null
        return node != null ? node.getSelectionSet() : null
    }
    private static SelectionSet nodeSelOrSelf(Field f) { return f.getSelectionSet() }

    private GraphQLObjectType nodeType(GraphQLObjectType connType) {
        GraphQLFieldDefinition edges = connType.getFieldDefinition("edges")
        if (edges == null) return null
        GraphQLType edgeT = unwrapAll(edges.getType())
        if (!(edgeT instanceof GraphQLObjectType)) return null
        GraphQLFieldDefinition node = ((GraphQLObjectType) edgeT).getFieldDefinition("node")
        if (node == null) return null
        GraphQLType nt = unwrapAll(node.getType())
        return (nt instanceof GraphQLObjectType) ? (GraphQLObjectType) nt : null
    }

    private static Field subField(SelectionSet sel, String name) {
        if (sel == null) return null
        for (Selection s in sel.getSelections()) if (s instanceof Field && name.equals(((Field) s).getName())) return (Field) s
        return null
    }

    private static GraphQLType unwrapAll(GraphQLType t) {
        GraphQLType cur = t
        while (cur instanceof GraphQLNonNull || cur instanceof GraphQLList) {
            cur = (cur instanceof GraphQLNonNull) ? ((GraphQLNonNull) cur).getWrappedType() : ((GraphQLList) cur).getWrappedType()
        }
        return cur
    }
    private static boolean isListType(GraphQLType t) {
        GraphQLType cur = t
        while (cur instanceof GraphQLNonNull || cur instanceof GraphQLList) {
            if (cur instanceof GraphQLList) return true
            cur = ((GraphQLNonNull) cur).getWrappedType()
        }
        return false
    }

    private Integer intArg(Field field, String name) {
        def a = field.getArguments().find { it.getName() == name }
        if (a == null) return null
        def v = a.getValue()
        if (v instanceof IntValue) return ((IntValue) v).getValue().intValue()
        if (v instanceof VariableReference) { def cv = vars0(((VariableReference) v).getName()); return (cv instanceof Number) ? ((Number) cv).intValue() : null }
        return null
    }
    private String strArg(Field field, String name) {
        def a = field.getArguments().find { it.getName() == name }
        if (a == null) return null
        def v = a.getValue()
        if (v instanceof StringValue) return ((StringValue) v).getValue()
        if (v instanceof VariableReference) { def cv = vars0(((VariableReference) v).getName()); return cv != null ? cv.toString() : null }
        return null
    }
    private int listArgSize(Field field, String name) {
        def a = field.getArguments().find { it.getName() == name }
        if (a == null) return -1
        def v = a.getValue()
        if (v instanceof ArrayValue) return ((ArrayValue) v).getValues().size()
        if (v instanceof VariableReference) { def cv = vars0(((VariableReference) v).getName()); return (cv instanceof Collection) ? ((Collection) cv).size() : -1 }
        return -1
    }

    // current-walk variables, stashed for the arg helpers (single-threaded per request)
    private Map<String, Object> currentVars
    private Object vars0(String n) { return currentVars != null ? currentVars.get(n) : null }

    private int clampFirst(Integer n) {
        int f = (n != null) ? n.intValue() : maxFirst
        if (f <= 0 || f > maxFirst) f = maxFirst
        return f
    }
    private static long sat(long v) { return v < 0L ? COST_CEILING : Math.min(v, COST_CEILING) }

    private static GraphQLError err(String message, String code, Map<String, Object> ext) {
        Map<String, Object> ex = new LinkedHashMap<>(); ex.put("code", code); if (ext != null) ex.putAll(ext)
        return GraphqlErrorBuilder.newError().message(message).extensions(ex).build()
    }
}
