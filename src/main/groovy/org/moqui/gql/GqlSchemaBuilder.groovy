package org.moqui.gql

import graphql.Scalars
import graphql.schema.*
import org.moqui.gql.scalars.GqlScalars

/** Result of a schema build: the graphql-java schema + the cost model populated from the artifact. */
class BuiltSchema {
    GraphQLSchema schema
    CostModel costModel
    SchemaArtifact artifact
}

/**
 * Builds a graphql-java GraphQLSchema from a SchemaArtifact: object types (our OMS field names),
 * Relay connection/edge/PageInfo types for list edges + root list queries, root Query with the
 * Shopify-shaped args (query / sortKey / reverse / first / after / last / before), sortKey enums,
 * and custom scalars. Populates the CostModel (listFields, serviceBackedFields). Declared search
 * keys are written into each connection field's description (introspectable; review G5).
 * Data fetchers are attached later by the executor (C3). Not @CompileStatic — graphql-java builder
 * chains are cleaner dynamic, and this runs once at startup.
 */
class GqlSchemaBuilder {

    BuiltSchema build(SchemaArtifact art) {
        CostModel cm = new CostModel()
        List<GraphQLType> additional = new ArrayList<>()

        // PageInfo (shared)
        additional.add(GraphQLObjectType.newObject().name("PageInfo")
                .description("Where a page of a connection sits: whether more pages follow or precede, and the cursors to ask for them.")
                .field(scalarField("hasNextPage", Scalars.GraphQLBoolean, "True when another page follows; ask with after: endCursor."))
                .field(scalarField("hasPreviousPage", Scalars.GraphQLBoolean, "True when a page precedes this one; ask with before: startCursor."))
                .field(scalarField("startCursor", Scalars.GraphQLString, "The cursor of the first edge on this page."))
                .field(scalarField("endCursor", Scalars.GraphQLString, "The cursor of the last edge on this page."))
                .build())
        additional.add(GqlScalars.DATE_TIME)
        additional.add(GqlScalars.DECIMAL)

        // which target types need a Relay connection (used by a connection edge or list root query;
        // plain-list edges return [Type!]! and need no Connection wrapper)
        Set<String> connTargets = new LinkedHashSet<>()
        for (GqlType t in art.types.values()) for (GqlEdge e in t.edges.values()) if (e.isConnection()) connTargets.add(e.targetType)
        for (GqlRootQuery q in art.rootQueries.values()) if (q.list) connTargets.add(q.targetType)

        // object types
        for (GqlType t in art.types.values()) {
            GraphQLObjectType.Builder b = GraphQLObjectType.newObject().name(t.name).description(t.description)
            for (GqlField fld in t.fields.values()) {
                GraphQLOutputType ft = fld.list ? GraphQLNonNull.nonNull(GraphQLList.list(GraphQLNonNull.nonNull(scalarType(fld.type)))) :
                        (GraphQLOutputType) scalarType(fld.type)
                b.field(GraphQLFieldDefinition.newFieldDefinition().name(fld.name).type(ft).description(fld.description).build())
                if (fld.isServiceBacked()) cm.serviceBackedFields.add(fld.name)
            }
            for (GqlEdge e in t.edges.values()) {
                if (e.isEmbedded()) {
                    // In-memory: arrays are never null ([T!]!), a nested object may be.
                    b.field(GraphQLFieldDefinition.newFieldDefinition().name(e.name).description(e.description).type(e.list ?
                            GraphQLNonNull.nonNull(GraphQLList.list(GraphQLNonNull.nonNull(GraphQLTypeReference.typeRef(e.targetType)))) :
                            GraphQLTypeReference.typeRef(e.targetType)).build())
                } else if (e.isPlainList()) {
                    cm.listFields.add(e.name)
                    b.field(plainListField(e.name, e.targetType, e.description))
                } else if (e.isConnection()) {
                    cm.listFields.add(e.name)
                    b.field(connectionField(e.name, e.targetType, false, null, null, e.description))
                } else {
                    b.field(GraphQLFieldDefinition.newFieldDefinition().name(e.name).description(e.description)
                            .type(GraphQLTypeReference.typeRef(e.targetType)).build())
                }
            }
            additional.add(b.build())
        }

        // connection + edge types per target
        for (String tgt in connTargets) {
            additional.add(GraphQLObjectType.newObject().name(tgt + "Edge")
                    .description("One " + tgt + " in a connection, with the cursor that marks its place.")
                    .field(scalarField("cursor", Scalars.GraphQLString, "Pass as after: or before: to page from this " + tgt + "."))
                    .field(GraphQLFieldDefinition.newFieldDefinition().name("node").description("The " + tgt + ".")
                            .type(GraphQLTypeReference.typeRef(tgt)).build())
                    .build())
            additional.add(GraphQLObjectType.newObject().name(tgt + "Connection")
                    .description("A page of " + tgt + " records.")
                    .field(GraphQLFieldDefinition.newFieldDefinition().name("edges").description("The records on this page, in order.")
                            .type(GraphQLList.list(GraphQLTypeReference.typeRef(tgt + "Edge"))).build())
                    .field(GraphQLFieldDefinition.newFieldDefinition().name("pageInfo").description("Whether more pages follow or precede.")
                            .type(GraphQLTypeReference.typeRef("PageInfo")).build())
                    .build())
        }

        // root Query
        Map<String, GraphQLEnumType> enums = new LinkedHashMap<>()
        GraphQLObjectType.Builder qb = GraphQLObjectType.newObject().name("Query")
        for (GqlRootQuery q in art.rootQueries.values()) {
            if (q.serviceBacked) {
                GraphQLOutputType ret = q.returnsList ?
                        GraphQLNonNull.nonNull(GraphQLList.list(GraphQLNonNull.nonNull(GraphQLTypeReference.typeRef(q.targetType)))) :
                        GraphQLTypeReference.typeRef(q.targetType)
                GraphQLFieldDefinition.Builder fb = GraphQLFieldDefinition.newFieldDefinition().name(q.name).type(ret).description(q.description)
                for (GqlArg a in q.args) fb.argument(arg(a.name, inputType(a.type, a.required), a.description))
                qb.field(fb.build())
                cm.serviceBackedFields.add(q.name)   // opaque to static analysis -> fixed high cost
            } else if (q.list) {
                cm.listFields.add(q.name)
                GraphQLEnumType sortEnum = null
                if (!q.sortKeys.isEmpty()) {
                    String enumName = (q.targetType ?: q.name) + "SortKey"
                    sortEnum = enums.get(enumName)
                    if (sortEnum == null) {
                        GraphQLEnumType.Builder eb = GraphQLEnumType.newEnum().name(enumName)
                                .description("The orders a " + q.name + " list can be sorted in; reverse: true flips it.")
                        for (String v in q.sortKeys.keySet()) eb.value(v)
                        sortEnum = eb.build(); enums.put(enumName, sortEnum); additional.add(sortEnum)
                    }
                }
                qb.field(connectionField(q.name, q.targetType, true, q.searchKeys.keySet(), sortEnum, q.description))
            } else if (q.byIdentification) {
                GraphQLFieldDefinition.Builder fb = GraphQLFieldDefinition.newFieldDefinition()
                        .name(q.name).type(GraphQLTypeReference.typeRef(q.targetType)).description(q.description)
                if (q.identTypeArg) fb.argument(arg(q.identTypeArg, Scalars.GraphQLString))
                if (q.identValueArg) fb.argument(arg(q.identValueArg, Scalars.GraphQLString))
                qb.field(fb.build())
            } else {
                GraphQLFieldDefinition.Builder fb = GraphQLFieldDefinition.newFieldDefinition()
                        .name(q.name).type(GraphQLTypeReference.typeRef(q.targetType)).description(q.description)
                if (q.pkArg) fb.argument(arg(q.pkArg, Scalars.GraphQLID))
                if (q.externalId) fb.argument(arg("externalId", Scalars.GraphQLString))
                qb.field(fb.build())
            }
        }

        GraphQLSchema schema = GraphQLSchema.newSchema()
                .query(qb.build())
                .additionalTypes(new LinkedHashSet<GraphQLType>(additional))
                .build()
        return new BuiltSchema(schema: schema, costModel: cm, artifact: art)
    }

    /** A plain bounded list field [Type!]! with a `first` cap arg (no Relay wrapper). */
    private static GraphQLFieldDefinition plainListField(String name, String targetType, String description) {
        return GraphQLFieldDefinition.newFieldDefinition().name(name).description(description)
                .type(GraphQLNonNull.nonNull(GraphQLList.list(GraphQLNonNull.nonNull(GraphQLTypeReference.typeRef(targetType)))))
                .argument(arg("first", Scalars.GraphQLInt, "At most this many, capped by the server.")).build()
    }

    // ---- helpers ----
    private static GraphQLFieldDefinition scalarField(String name, GraphQLScalarType type, String description = null) {
        return GraphQLFieldDefinition.newFieldDefinition().name(name).type(type).description(description).build()
    }
    private static GraphQLArgument arg(String name, GraphQLInputType type, String description = null) {
        return GraphQLArgument.newArgument().name(name).type(type).description(description).build()
    }
    private static GraphQLInputType inputType(String type, boolean required) {
        GraphQLInputType base
        switch (type) {
            case "IDList": base = GraphQLList.list(GraphQLNonNull.nonNull(Scalars.GraphQLID)); break
            case "StringList": base = GraphQLList.list(GraphQLNonNull.nonNull(Scalars.GraphQLString)); break
            case "Int": base = Scalars.GraphQLInt; break
            case "ID": base = Scalars.GraphQLID; break
            default: base = Scalars.GraphQLString
        }
        return required ? GraphQLNonNull.nonNull(base) : base
    }
    private static GraphQLOutputType scalarType(String type) {
        switch (type) {
            case "DateTime": return GqlScalars.DATE_TIME
            case "Decimal": return GqlScalars.DECIMAL
            case "Int": return Scalars.GraphQLInt
            case "Boolean": return Scalars.GraphQLBoolean
            case "ID": return Scalars.GraphQLID
            default: return Scalars.GraphQLString
        }
    }
    /** A connection field: returns <target>Connection with first/after/last/before (+ query/sortKey/reverse for roots). */
    private static GraphQLFieldDefinition connectionField(String name, String targetType, boolean root,
                                                          Set<String> searchKeys, GraphQLEnumType sortEnum, String description) {
        GraphQLFieldDefinition.Builder fb = GraphQLFieldDefinition.newFieldDefinition()
                .name(name).type(GraphQLTypeReference.typeRef(targetType + "Connection")).description(description)
                .argument(arg("first", Scalars.GraphQLInt, "Return the first n records (required unless last is given)."))
                .argument(arg("after", Scalars.GraphQLString, "Return records after this cursor."))
                .argument(arg("last", Scalars.GraphQLInt, "Return the last n records."))
                .argument(arg("before", Scalars.GraphQLString, "Return records before this cursor."))
        if (root) {
            fb.argument(arg("query", Scalars.GraphQLString, "A search string of key:value terms; see the field description for the keys."))
                    .argument(arg("reverse", Scalars.GraphQLBoolean, "Reverse the sort order."))
            if (sortEnum != null) fb.argument(arg("sortKey", sortEnum, "The field to sort by."))
            if (searchKeys != null && !searchKeys.isEmpty())
                fb.description((description ? description + " " : "") + "Filter via `query:` search string. Allowed keys: " + searchKeys.join(", "))
        }
        return fb.build()
    }
}
