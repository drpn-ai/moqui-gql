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
import org.moqui.gql.exec.ConnectionResolver
import org.moqui.gql.exec.NestedConnectionLoader
import org.moqui.gql.exec.NestedEdgeMeta
import org.moqui.gql.exec.ServiceBackedLoader
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

    GqlEngine(ExecutionContext ec) {
        this.ec = ec
        this.built = (BuiltSchema) ec.factory.getToolFactory("GraphQL").getInstance()
        this.useClone = "true".equalsIgnoreCase(sysOr("gql.useClone", "true"))
        this.queryTimeoutSeconds = (sysOr("gql.queryTimeoutSeconds", "20")) as int
        this.maxFirst = (sysOr("gql.maxFirst", "100")) as int
        this.maxRowsPerLevel = (sysOr("gql.maxRowsPerLevel", "5000")) as int
        this.serviceBatchKeyLimit = (sysOr("gql.serviceBatchKeyLimit", "1000")) as int
    }

    private static String sysOr(String n, String d) { String v = System.getProperty(n); return v != null ? v : d }

    Map execute(String query, Map variables, String operationName) {
        List<NestedEdgeMeta> metas = nestedEdgeMetas()
        GraphQLSchema executable = withFetchers(metas)
        GraphQL graphQL = GraphQL.newGraphQL(executable).build()
        ExecutionInput.Builder inB = ExecutionInput.newExecutionInput().query(query)
                .dataLoaderRegistry(buildRegistry(metas))
        if (variables != null) inB.variables(variables)
        if (operationName) inB.operationName(operationName)
        ExecutionInput input = inB.build()
        // decision 10: one read-only transaction on the calling thread
        ExecutionResult er = (ExecutionResult) ec.transaction.runUseOrBegin(queryTimeoutSeconds, "gql execute error", { graphQL.execute(input) })
        return [data: er.getData(), errors: er.getErrors().collect { it.getMessage() }]
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
                if (!e.list) continue
                def ri = parentEd.getRelationshipInfo(e.entityRelationship)
                if (ri == null || ri.keyMap == null || ri.keyMap.size() != 1) {
                    ec.logger.warn("gql: nested edge ${t.name}.${e.name} not batchable (relationship " +
                            "'${e.entityRelationship}' missing or composite-key); skipping nested resolution")
                    continue
                }
                String parentKeyField = ri.keyMap.keySet().iterator().next()
                String fkField = ri.keyMap.get(parentKeyField)
                List<String> childPk = new ArrayList<>(ri.relatedEd.getPkFieldNames())
                List<String> intra = new ArrayList<>(childPk); intra.remove(fkField)
                if (intra.isEmpty()) intra = childPk   // degenerate: fk is the entire PK
                out.add(new NestedEdgeMeta(typeName: t.name, edgeName: e.name, loaderName: t.name + "." + e.name,
                        parentKeyField: parentKeyField, childEntityName: ri.relatedEntityName,
                        fkField: fkField, intraGroupFields: intra, plain: e.isPlainList()))
            }
        }
        return out
    }

    private DataLoaderRegistry buildRegistry(List<NestedEdgeMeta> metas) {
        DataLoaderRegistry reg = new DataLoaderRegistry()
        for (NestedEdgeMeta meta in metas) {
            def loader = new NestedConnectionLoader(ec, meta.childEntityName, meta.fkField, meta.intraGroupFields,
                    useClone, queryTimeoutSeconds, maxFirst, maxRowsPerLevel, meta.plain)
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
                        def pkVal = (parent instanceof Map) ? ((Map) parent).get(m.parentKeyField) : null
                        if (pkVal == null) return null
                        return env.getDataLoader(m.loaderName).load(pkVal, env.getArguments())
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
                            new ConnectionResolver(ec, useClone, queryTimeoutSeconds, maxFirst)
                                    .resolveRoot(rq, tt, env.getArguments())
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
                            def assoc = af.useClone(useClone).queryTimeout(queryTimeoutSeconds).maxRows(1).fetchSize(1).list()
                            if (!assoc) return null
                            def fkVal = assoc.get(0).get(rq.identFkField)
                            if (fkVal == null) return null
                            def tgtPk = ((EntityFacadeImpl) ec.entity).getEntityDefinition(targetEntity).getPkFieldNames().get(0)
                            def tgt = ec.entity.find(targetEntity).condition(tgtPk, fkVal)
                                    .useClone(useClone).queryTimeout(queryTimeoutSeconds).maxRows(1).fetchSize(1).list()
                            return tgt ? tgt.get(0).getMap() : null
                        } as DataFetcher))
            } else if (rq.byPk) {
                String entityName = rq.entityName ?: (tt != null ? tt.entityName : null)
                String pkArg = rq.pkArg ?: "id"
                boolean extId = rq.externalId
                code.dataFetcher(FieldCoordinates.coordinates("Query", rq.name),
                        ({ DataFetchingEnvironment env ->
                            if (entityName == null) return null
                            def find = ec.entity.find(entityName)
                            def idVal = env.getArgument(pkArg)
                            if (idVal != null) {
                                find.condition(pkArg, idVal)
                            } else if (extId && env.getArgument("externalId") != null) {
                                find.condition("externalId", env.getArgument("externalId"))   // external-id lookup (Q5)
                            } else {
                                return null
                            }
                            // first-row semantics: composite-PK views (e.g. parties) can match >1 row
                            def rows = find.useClone(useClone).queryTimeout(queryTimeoutSeconds)
                                    .maxRows(1).fetchSize(1).list()
                            return rows ? rows.get(0).getMap() : null
                        } as DataFetcher))
            }
        }

        return built.schema.transform({ b -> b.codeRegistry(code.build()) })
    }
}
