package org.moqui.gql

import groovy.transform.CompileStatic

/** Parsed model of a `graphql/*.gql.xml` schema artifact. Field/type names are the OMS data model;
 *  the query language (search keys, sort keys, connections) is Shopify-shaped. See docs/schema.graphql. */
@CompileStatic
class GqlField {
    String name, entityField, type
    /** DAR-BE-099: printed in the SDL (and the generated GraphQL reference); every declared one must have it. */
    String description
    boolean filterable = false, sortable = false
    /** DAR-BE-098: a list of scalars ([String!]!) read from the parent map, e.g. a report's notCovered ids. */
    boolean list = false
    Integer costOverride = null
    String resolverService = null
    List<String> resolverIn = []
    /** OUT param to read from the service result; may be a dotted path (e.g. orderDetail.customerFirstName).
     *  Defaults to the field name when unset. */
    String resolverOut = null
    boolean isServiceBacked() { return resolverService != null && !resolverService.isEmpty() }
    // Aggregate-field kind (decision 12): the value is a lazy SQL aggregate added to the parent query as a
    // sub-select (LATERAL on mysql8) only when the field is selected — not a column, service, or relationship.
    String aggregateFunction      // e.g. "count-distinct", "count", "sum"
    String aggregateEntity        // fully-qualified child entity, e.g. org.apache.ofbiz.order.order.OrderItem
    String aggregateFk            // child field that joins to the parent PK, e.g. orderId
    String aggregateField         // child field the function is applied to, e.g. externalId
    boolean isAggregate() { return aggregateFunction != null && !aggregateFunction.isEmpty() }
}

@CompileStatic
class GqlEdge {
    String name, entityRelationship, targetType
    /** DAR-BE-099: printed in the SDL (and the generated GraphQL reference); every declared one must have it. */
    String description
    boolean list = false
    /** "connection" (Relay, default), "list" (plain bounded [Type!]! with a `first` cap), or "embedded"
     *  (DAR-BE-098): a nested map, or list of maps, read from the parent row itself. No entity read, so no
     *  `first` and no fan-out cost; the service that produced the parent already did the work. */
    String kind = "connection"
    /** Embedded edge: the parent map key to read; defaults to the edge name. */
    String sourceKey = null
    Integer firstDefault = null
    Integer costOverride = null
    String resolverService = null
    List<String> resolverIn = []
    /** Single-object (has-one) edge: resolved by a batched WHERE fk IN(keys) against childEntity.
     *  Explicit child entity + join field (no Moqui relationship needed); parentKey defaults to fk. */
    boolean single = false
    String childEntity = null
    String fk = null
    String parentKey = null
    /** #38: when set, this has-many list edge returns only parents that have >= 1 row in the named child
     *  relationship (e.g. "items"); resolved with one extra batched DISTINCT query. Parsed in Task 5. */
    String excludeEmpty = null
    boolean isServiceBacked() { return resolverService != null && !resolverService.isEmpty() }
    boolean isEmbedded() { return "embedded".equals(kind) }
    boolean isPlainList() { return list && "list".equals(kind) }
    boolean isConnection() { return list && !"list".equals(kind) && !isEmbedded() }
}

@CompileStatic
class GqlType {
    String name, entityName
    /** DAR-BE-099: printed in the SDL (and the generated GraphQL reference); every declared one must have it. */
    String description
    Map<String, GqlField> fields = [:]
    Map<String, GqlEdge> edges = [:]
}

/** A declared argument for a service-backed root query. type: ID|String|Int|Decimal|Boolean|IDList|StringList. */
@CompileStatic
class GqlArg {
    String name, type
    /** DAR-BE-099: printed in the SDL (and the generated GraphQL reference); every declared one must have it. */
    String description
    boolean required = false
}

@CompileStatic
class GqlRootQuery {
    String name, targetType, entityName, pkArg
    /** DAR-BE-099: printed in the SDL (and the generated GraphQL reference); every declared one must have it. */
    String description
    boolean byPk = false, externalId = false, list = false
    /** Service-backed root (decision 12 at root level): the field's value is a service call result. */
    boolean serviceBacked = false
    String serviceName, serviceOut
    boolean returnsList = false
    /** DAR-BE-098: the service IN parameter that receives the request's pinned tenant (the API key's, or the
     *  session's active tenant). Never a caller argument; with no tenant the service is not called. */
    String tenantParam
    List<GqlArg> args = []
    /** search key (our field name) -> allowed comparators (eq, in, gt, gte, lt, lte). */
    Map<String, Set<String>> searchKeys = [:]
    /** sortKey enum value -> entity field it sorts by. */
    Map<String, String> sortKeys = [:]
    /** Indirect external-id lookup: match a row in an association entity, then follow an fk to the
     *  target. e.g. orderByIdentification(identificationTypeId:, idValue:) -> Order. */
    boolean byIdentification = false
    String identEntity, identTypeArg, identTypeField, identValueArg, identValueField, identFkField
}

@CompileStatic
class SchemaArtifact {
    Map<String, GqlType> types = [:]
    Map<String, GqlRootQuery> rootQueries = [:]
}
