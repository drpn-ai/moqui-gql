package org.moqui.gql

import graphql.ExecutionInput
import graphql.ExecutionResult
import graphql.GraphQL
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import graphql.schema.FieldCoordinates
import graphql.schema.GraphQLCodeRegistry
import graphql.schema.GraphQLSchema
import org.dataloader.DataLoaderFactory
import org.dataloader.DataLoaderRegistry
import org.moqui.context.ExecutionContext
import org.moqui.gql.exec.AggregateViewBuilder
import org.moqui.gql.exec.ConnectionResolver
import org.moqui.gql.exec.NestedConnectionLoader
import org.moqui.gql.exec.NestedEdgeMeta
import org.moqui.gql.exec.NestedSingleLoader
import org.moqui.gql.exec.ServiceBackedLoader
import org.moqui.gql.scope.ScopeFilters
import org.moqui.impl.entity.EntityFacadeImpl

/**
 * Executes a GraphQL query against the cached schema, on the calling thread inside one read-only
 * transaction (decision 10), reads routed to the replica clone (decision 9, useClone). Attaches data
 * fetchers to the cached type system per request:
 *   - scalar fields  -> read source[entityField]
 *   - root by-pk     -> find one, return the entity Map
 *   - root list      -> ConnectionResolver (Relay keyset connection, single/composite PK)
 *   - root by-id     -> first-row find by PK or externalId; by-identification = indirect assoc lookup
 *   - root service   -> a service-backed root (e.g. inventoryLevels) returning the service result
 *   - nested list    -> DataLoader-batched NestedConnectionLoader (connection or plain list; no N+1)
 *   - service field  -> DataLoader-batched ServiceBackedLoader (decision 12)
 * A fresh DataLoaderRegistry is built per request (loaders cache within a single query); graphql-java
 * auto-dispatches it via PerLevelDataLoaderDispatchStrategy.
 * Not @CompileStatic — DataFetcher/closure coercion is cleaner dynamic.
 */
class GqlEngine {
    private final ExecutionContext ec
    private final BuiltSchema built
    private final boolean useClone
    private final int queryTimeoutSeconds
    private final int maxFirst
    private final int maxRowsPerLevel
    private final int serviceBatchKeyLimit
    private final Map<String, Integer> govCfg

    // query-log v2 policy knobs (see docs/implementation-plan-query-log-v2.md)
    private final long slowMinMillis
    private final int warmupHits
    private final double sampleRate
    private final long binMillis

    GqlEngine(ExecutionContext ec) {
        this.ec = ec
        this.built = (BuiltSchema) ec.factory.getToolFactory("GraphQL").getInstance()
        this.useClone = "true".equalsIgnoreCase(sysOr("gql.useClone", "true"))
        this.queryTimeoutSeconds = (sysOr("gql.queryTimeoutSeconds", "20")) as int
        this.maxFirst = (sysOr("gql.maxFirst", "100")) as int
        this.maxRowsPerLevel = (sysOr("gql.maxRowsPerLevel", "5000")) as int
        this.serviceBatchKeyLimit = (sysOr("gql.serviceBatchKeyLimit", "1000")) as int
        this.slowMinMillis = (sysOr("gql.queryLog.slowMinMillis", "1000")) as long
        this.warmupHits = (sysOr("gql.queryLog.warmupHits", "50")) as int
        this.sampleRate = (sysOr("gql.queryLog.sampleRate", "0.01")) as double
        this.binMillis = ((sysOr("gql.queryLog.binSeconds", "900")) as long) * 1000L
        this.govCfg = [
                maxDepth             : (sysOr("gql.maxDepth", "6")) as int,
                maxCost              : (sysOr("gql.maxCost", "1000")) as int,
                maxFirst             : maxFirst,
                serviceBatchKeyLimit : serviceBatchKeyLimit,
                unindexedFilterPenalty: (sysOr("gql.unindexedFilterPenalty", "50")) as int,
                serviceFixedCost     : (sysOr("gql.serviceFixedCost", "25")) as int,
                aggregateFieldCost   : (sysOr("gql.aggregateFieldCost", "5")) as int,
                wallClockBudgetMs    : (sysOr("gql.wallClockBudgetMs", "30000")) as int,
                bucketSize           : (sysOr("gql.throttle.bucketSize", sysOr("gql.maxCost", "1000"))) as int,
                restoreRate          : (sysOr("gql.throttleRestoreRate", "50")) as int]
    }

    private static String sysOr(String n, String d) { String v = System.getProperty(n); return v != null ? v : d }

    /** Aggregate fields of `tt` that appear in this selection (lazy: only these get a sub-select member).
     *  Connection roots select under edges/node/<f>; by-pk/by-id select <f> directly. */
    private static List<GqlField> requestedAggregates(GqlType tt, graphql.schema.DataFetchingEnvironment env, boolean connection) {
        List<GqlField> out = new ArrayList<GqlField>()
        if (tt == null) return out
        def sel = env.getSelectionSet()
        for (GqlField f in tt.fields.values()) {
            if (!f.isAggregate()) continue
            if (sel.contains(connection ? ("edges/node/" + f.name) : f.name)) out.add(f)
        }
        return out
    }

    Map execute(String query, Map variables, String operationName) {
        long startMs = System.currentTimeMillis()
        // phase-2 caller policy: a profile overrides governor/throttle limits + may activate a row scope
        Map<String, Integer> effectiveCfg = govCfg
        Map profile = resolveCallerProfile()
        String scopeStore = null
        if (profile != null) {
            effectiveCfg = new LinkedHashMap<String, Integer>(govCfg)
            for (String k in ["maxCost", "maxFirst", "bucketSize", "restoreRate"]) {
                def v = profile.get(k); if (v != null) effectiveCfg.put(k, ((Number) v).intValue())
            }
            scopeStore = profile.get("scopeProductStoreId")
        }
        boolean scoped = false
        try {
            // Darpan fork: tenant row-scope is MANDATORY (shared-DB multi-tenancy). Default EVERY request to
            // the fail-closed DarpanTenantScopeFilter so reads can never span tenants; a caller profile's
            // productStore scope (OMS heritage) still overrides when explicitly set.
            if (scopeStore) { ScopeFilters.set(new org.moqui.gql.scope.ProductStoreScopeFilter(scopeStore)) }
            else { ScopeFilters.set(new org.moqui.gql.scope.DarpanTenantScopeFilter()) }
            scoped = true
            List<NestedEdgeMeta> metas = nestedEdgeMetas()
            GraphQLSchema executable = withFetchers(metas)
            // C4 governor: depth/cost/first/query/batch limits enforced pre-execution (nothing hits the DB)
            GovernorInstrumentation governor = new GovernorInstrumentation(ec, built, effectiveCfg)
            GraphQL graphQL = GraphQL.newGraphQL(executable).instrumentation(governor)
                    .preparsedDocumentProvider(new CachingPreparsedDocumentProvider(ec)).build()
            ExecutionInput.Builder inB = ExecutionInput.newExecutionInput().query(query)
                    .dataLoaderRegistry(buildRegistry(metas))
            if (variables != null) inB.variables(variables)
            if (operationName) inB.operationName(operationName)
            ExecutionInput input = inB.build()
            // decision 10: one read-only transaction on the calling thread
            ExecutionResult er = (ExecutionResult) ec.transaction.runUseOrBegin(queryTimeoutSeconds, "gql execute error", { graphQL.execute(input) })

            Object data = er.getData()
            List errors = er.getErrors().collect { [message: it.getMessage(), extensions: it.getExtensions()] }
            long cost = governor.estimatedCost
            long durationMs = System.currentTimeMillis() - startMs
            int rows = countRows(data)
            // Shopify-shaped cost. actualQueryCost == requested (not separately measured). throttleStatus is
            // LIVE from the per-caller bucket (phase 2): currentlyAvailable reflects debits + time refills.
            def td = governor.throttleDecision
            Map throttle = (td != null) ?
                    [maximumAvailable: td.maximumAvailable, currentlyAvailable: (long) td.currentlyAvailable, restoreRate: td.restoreRate] :
                    [maximumAvailable: effectiveCfg.bucketSize, currentlyAvailable: effectiveCfg.bucketSize, restoreRate: effectiveCfg.restoreRate]
            Map extensions = [cost: [requestedQueryCost: cost, actualQueryCost: cost, throttleStatus: throttle]]
            logQuery(query, (String) (profile?.profileId ?: "default"), cost, rows, durationMs, errors)
            return [data: data, errors: errors, extensions: extensions]
        } finally {
            if (scoped) ScopeFilters.reset()
        }
    }

    /** Resolve the caller's policy profile (userId -> member -> profile). Null = the global default.
     *  Defensive: any lookup failure falls back to global config, never failing the request. */
    private Map resolveCallerProfile() {
        try {
            String userId = ec.user?.userId
            if (!userId) return null
            def member = ec.entity.find("moqui.gql.GqlCallerProfileMember").condition("userId", userId).disableAuthz().one()
            if (!member?.profileId) return null
            def p = ec.entity.find("moqui.gql.GqlCallerProfile").condition("profileId", member.profileId).disableAuthz().one()
            return p?.getMap()
        } catch (Throwable t) {
            ec.logger.warn("gql: caller-profile lookup failed: " + t.message); return null
        }
    }

    /** Best-effort row count from the result tree: each Map element of any list (edges/plain lists). */
    private int countRows(Object o) {
        if (!(o instanceof Map)) return 0
        int sum = 0
        for (Object v in ((Map) o).values()) {
            if (v instanceof List) { for (Object e in (List) v) { if (e instanceof Map) { sum++; sum += countRows(e) } } }
            else if (v instanceof Map) sum += countRows(v)
        }
        return sum
    }

    /** Query-log v2 policy. Raw rows: every REJECTED query; ALLOWED queries that are slow for their
     *  shape (QueryStats: avg + 2.6 sigma after warm-up, over slowMinMillis); plus a random sample.
     *  Every ALLOWED hit feeds the per-shape stats; aged bins persist to GqlQueryStatsBin. All writes
     *  in one separate transaction — a log failure never fails the user's request. */
    private void logQuery(String query, String profileId, long cost, int rows, long durationMs, List errors) {
        try {
            boolean rejected = !errors.isEmpty()
            String queryHash = sha256Hex(query)
            org.moqui.gql.policy.QueryStats.Outcome outcome = null
            boolean writeRow = rejected
            if (!rejected) {
                outcome = org.moqui.gql.policy.QueryStats.track(ec, queryHash, durationMs, cost, (long) rows,
                        warmupHits, slowMinMillis, binMillis)
                writeRow = outcome.slow ||
                        (sampleRate > 0.0d && java.util.concurrent.ThreadLocalRandom.current().nextDouble() < sampleRate)
            }
            if (!writeRow && outcome?.finishedBin == null) return

            String rejectReason = null
            if (rejected) {
                def ex = errors.get(0)?.extensions
                rejectReason = (ex?.code) ?: errors.get(0)?.message
            }
            String q = query != null && query.length() > 4000 ? query.substring(0, 4000) : query
            def fin = outcome?.finishedBin
            long finEndMs = outcome != null ? outcome.finishedBinEndMs : 0L
            boolean slow = outcome != null && outcome.slow
            boolean doWriteRow = writeRow
            ec.transaction.runRequireNew(30, "gql query-log error", {
                // shape row first sight (find-then-create; raw rows + bins join here by hash)
                if (ec.entity.find("moqui.gql.GqlQueryShape").condition("queryHash", queryHash)
                        .disableAuthz().useCache(false).one() == null)
                    ec.entity.makeValue("moqui.gql.GqlQueryShape").setAll([
                            queryHash: queryHash, queryText: q, firstSeenDate: ec.user.nowTimestamp]).create()
                if (fin != null)
                    ec.entity.makeValue("moqui.gql.GqlQueryStatsBin").setAll([
                            queryHash: queryHash,
                            binStartDate: new java.sql.Timestamp(fin.binStartMs),
                            binEndDate: new java.sql.Timestamp(finEndMs),
                            hitCount: fin.hitCount, slowHitCount: fin.slowHitCount,
                            totalDurationMs: (long) fin.totalMs, totalSquaredDuration: fin.totalSqMs,
                            minDurationMs: fin.minMs, maxDurationMs: fin.maxMs,
                            totalCost: fin.totalCost, totalRows: fin.totalRows])
                            .setSequencedIdPrimary().create()
                if (doWriteRow)
                    ec.entity.makeValue("moqui.gql.GqlQueryLog").setAll([
                            queryDate: ec.user.nowTimestamp, userId: ec.user.userId,
                            callerProfile: profileId, verdict: rejected ? "REJECTED" : "ALLOWED",
                            rejectReason: rejectReason, estimatedCost: cost, rowsFetched: rows,
                            durationMs: durationMs, queryHash: queryHash, slowHit: slow ? "Y" : "N",
                            queryText: q])
                            .setSequencedIdPrimary().create()
            })
        } catch (Throwable t) {
            ec.logger.warn("gql: failed to write query log: " + t.message)
        }
    }

    private static String sha256Hex(String s) {
        byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest((s ?: "").getBytes("UTF-8"))
        StringBuilder sb = new StringBuilder(d.length * 2)
        for (byte b in d) sb.append(String.format("%02x", b))
        return sb.toString()
    }

    /** Resolve batching metadata for every nested has-many (list) edge from the Moqui relationship model. */
    private List<NestedEdgeMeta> nestedEdgeMetas() {
        EntityFacadeImpl efi = (EntityFacadeImpl) ec.entity
        List<NestedEdgeMeta> out = new ArrayList<>()
        for (GqlType t in built.artifact.types.values()) {
            if (!t.entityName) continue
            def parentEd = efi.getEntityDefinition(t.entityName)
            if (parentEd == null) continue
            for (GqlEdge e in t.edges.values()) {
                if (e.single) {   // single-object (has-one) edge: explicit child entity + fk, no relationship
                    out.add(new NestedEdgeMeta(typeName: t.name, edgeName: e.name, loaderName: t.name + "." + e.name,
                            parentKeyField: e.parentKey ?: e.fk, childEntityName: e.childEntity,
                            fkField: e.fk, intraGroupFields: new ArrayList<String>(), plain: false, single: true))
                    continue
                }
                if (!e.list) continue
                def ri = parentEd.getRelationshipInfo(e.entityRelationship)
                if (ri == null || ri.keyMap == null || ri.keyMap.isEmpty()) {
                    ec.logger.warn("gql: nested edge ${t.name}.${e.name} not batchable (relationship " +
                            "'${e.entityRelationship}' missing); skipping nested resolution")
                    continue
                }
                // #38: capture the FULL join key (size 1 = single-key, identical behavior; size > 1 = composite).
                List<String> parentKeyFields = new ArrayList<>(ri.keyMap.keySet())          // parent-side, key-map order
                List<String> fkFields = new ArrayList<>()                                   // child-side, parallel
                for (String pkf in parentKeyFields) fkFields.add(ri.keyMap.get(pkf))
                List<String> childPk = new ArrayList<>(ri.relatedEd.getPkFieldNames())
                List<String> intra = new ArrayList<>(childPk); intra.removeAll(fkFields)
                if (intra.isEmpty()) intra = childPk   // degenerate: fk is the entire child PK
                out.add(new NestedEdgeMeta(typeName: t.name, edgeName: e.name, loaderName: t.name + "." + e.name,
                        parentKeyField: parentKeyFields.get(0), childEntityName: ri.relatedEntityName,
                        fkField: fkFields.get(0), parentKeyFields: parentKeyFields, fkFields: fkFields,
                        intraGroupFields: intra, plain: e.isPlainList(),
                        excludeEmptyRelationship: e.excludeEmpty))
            }
        }
        return out
    }

    private DataLoaderRegistry buildRegistry(List<NestedEdgeMeta> metas) {
        DataLoaderRegistry reg = new DataLoaderRegistry()
        for (NestedEdgeMeta meta in metas) {
            def loader = meta.single ?
                    new NestedSingleLoader(ec, meta.childEntityName, meta.fkField, useClone, maxRowsPerLevel) :
                    new NestedConnectionLoader(ec, meta.childEntityName, meta.fkFields, meta.intraGroupFields,
                            useClone, maxFirst, maxRowsPerLevel, meta.plain,
                            meta.excludeEmptyRelationship)
            reg.register(meta.loaderName, DataLoaderFactory.newMappedDataLoader(loader))
        }
        // one batched loader per service-backed field (decision 12), keyed by its resolver-in tuple
        for (GqlType t in built.artifact.types.values()) {
            for (GqlField f in t.fields.values()) {
                if (!f.isServiceBacked()) continue
                String loaderName = t.name + "." + f.name
                def loader = new ServiceBackedLoader(ec, f.resolverService, f.resolverIn,
                        f.resolverOut ?: f.name, serviceBatchKeyLimit, loaderName)
                reg.register(loaderName, DataLoaderFactory.newMappedDataLoader(loader))
            }
        }
        return reg
    }

    private GraphQLSchema withFetchers(List<NestedEdgeMeta> metas) {
        SchemaArtifact art = built.artifact
        GraphQLCodeRegistry.Builder code = GraphQLCodeRegistry.newCodeRegistry()

        // field fetchers: scalar reads source[entityField]; service-backed defers to a batched DataLoader
        for (GqlType t in art.types.values()) {
            final String typeName = t.name
            for (GqlField f in t.fields.values()) {
                if (f.isServiceBacked()) {
                    final List<String> inFields = f.resolverIn
                    final String loaderName = typeName + "." + f.name
                    code.dataFetcher(FieldCoordinates.coordinates(typeName, f.name),
                            ({ DataFetchingEnvironment env ->
                                def src = env.getSource()
                                if (!(src instanceof Map)) return null
                                List<Object> key = new ArrayList<Object>()
                                for (String inf in inFields) key.add(((Map) src).get(inf))
                                return env.getDataLoader(loaderName).load(key)
                            } as DataFetcher))
                } else {
                    final String entityField = f.entityField ?: f.name
                    code.dataFetcher(FieldCoordinates.coordinates(typeName, f.name),
                            ({ DataFetchingEnvironment env ->
                                def s = env.getSource(); (s instanceof Map) ? ((Map) s).get(entityField) : null
                            } as DataFetcher))
                }
            }
        }

        // nested has-many edge fetchers: defer to the per-request DataLoader (batched, no N+1)
        for (NestedEdgeMeta meta in metas) {
            final NestedEdgeMeta m = meta   // capture per-iteration value, not the loop variable
            code.dataFetcher(FieldCoordinates.coordinates(m.typeName, m.edgeName),
                    ({ DataFetchingEnvironment env ->
                        def parent = env.getSource()
                        if (!(parent instanceof Map)) return null
                        Map p = (Map) parent
                        // #38: single-key -> raw value (identical to before); composite -> a List tuple matching groupKey().
                        Object key
                        if (m.parentKeyFields == null || m.parentKeyFields.size() <= 1) {
                            key = p.get(m.parentKeyField)
                            if (key == null) return null
                        } else {
                            List<Object> t = new ArrayList<Object>()
                            for (String f in m.parentKeyFields) { Object v = p.get(f); if (v == null) return null; t.add(v) }
                            key = t
                        }
                        return env.getDataLoader(m.loaderName).load(key, env.getArguments())
                    } as DataFetcher))
        }

        // root fetchers: by-pk -> one entity Map; list -> a Relay connection map (ConnectionResolver)
        for (GqlRootQuery q in art.rootQueries.values()) {
            final GqlRootQuery rq = q       // capture per-iteration value, not the loop variable
            GqlType tt = art.types.get(rq.targetType)
            if (rq.serviceBacked) {
                code.dataFetcher(FieldCoordinates.coordinates("Query", rq.name),
                        ({ DataFetchingEnvironment env ->
                            if (rq.serviceName == null) return null
                            Map<String, Object> params = new LinkedHashMap<String, Object>()
                            for (GqlArg a in rq.args) { def v = env.getArgument(a.name); if (v != null) params.put(a.name, v) }
                            def result = ec.service.sync().name(rq.serviceName).parameters(params).call()
                            return rq.serviceOut ? (result != null ? result.get(rq.serviceOut) : null) : result
                        } as DataFetcher))
            } else if (rq.list) {
                code.dataFetcher(FieldCoordinates.coordinates("Query", rq.name),
                        ({ DataFetchingEnvironment env ->
                            new ConnectionResolver(ec, useClone, maxFirst)
                                    .resolveRoot(rq, tt, env.getArguments(), requestedAggregates(tt, env, true))
                        } as DataFetcher))
            } else if (rq.byIdentification) {
                String targetEntity = rq.entityName ?: (tt != null ? tt.entityName : null)
                code.dataFetcher(FieldCoordinates.coordinates("Query", rq.name),
                        ({ DataFetchingEnvironment env ->
                            if (targetEntity == null || rq.identEntity == null) return null
                            def typeVal = rq.identTypeArg ? env.getArgument(rq.identTypeArg) : null
                            def valueVal = rq.identValueArg ? env.getArgument(rq.identValueArg) : null
                            if (valueVal == null) return null
                            // 1) match the association row (typed external id), 2) follow the fk to the target
                            def af = ec.entity.find(rq.identEntity).condition(rq.identValueField, valueVal)
                            if (typeVal != null && rq.identTypeField) af.condition(rq.identTypeField, typeVal)
                            ScopeFilters.apply(af, rq.identEntity, ec)
                            def assoc = af.useClone(useClone).maxRows(1).fetchSize(1).list()
                            if (!assoc) return null
                            def fkVal = assoc.get(0).get(rq.identFkField)
                            if (fkVal == null) return null
                            def tgtPk = ((EntityFacadeImpl) ec.entity).getEntityDefinition(targetEntity).getPkFieldNames().get(0)
                            def tgtFind = ec.entity.find(targetEntity).condition(tgtPk, fkVal)
                            ScopeFilters.apply(tgtFind, targetEntity, ec)
                            def tgt = tgtFind.useClone(useClone).maxRows(1).fetchSize(1).list()
                            return tgt ? tgt.get(0).getMap() : null
                        } as DataFetcher))
            } else if (rq.byPk) {
                String entityName = rq.entityName ?: (tt != null ? tt.entityName : null)
                String pkArg = rq.pkArg ?: "id"
                boolean extId = rq.externalId
                code.dataFetcher(FieldCoordinates.coordinates("Query", rq.name),
                        ({ DataFetchingEnvironment env ->
                            if (entityName == null) return null
                            List<GqlField> aggs = requestedAggregates(tt, env, false)
                            def find = aggs.isEmpty() ? ec.entity.find(entityName) :
                                    AggregateViewBuilder.aggregateFind(ec, entityName, aggs)
                            def idVal = env.getArgument(pkArg)
                            if (idVal != null) {
                                find.condition(pkArg, idVal)
                            } else if (extId && env.getArgument("externalId") != null) {
                                find.condition("externalId", env.getArgument("externalId"))   // external-id lookup (Q5)
                            } else {
                                return null
                            }
                            ScopeFilters.apply(find, entityName, ec)   // row-scope seam (phase-1 no-op)
                            // first-row semantics: composite-PK views (e.g. parties) can match >1 row
                            def rows = find.useClone(useClone)
                                    .maxRows(1).fetchSize(1).list()
                            return rows ? rows.get(0).getMap() : null
                        } as DataFetcher))
            }
        }

        return built.schema.transform({ b -> b.codeRegistry(code.build()) })
    }
}
