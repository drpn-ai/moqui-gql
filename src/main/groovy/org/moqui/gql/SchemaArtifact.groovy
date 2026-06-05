package org.moqui.gql

import groovy.transform.CompileStatic

/** Parsed model of a `graphql/*.gql.xml` schema artifact. Field/type names are the OMS data model;
 *  the query language (search keys, sort keys, connections) is Shopify-shaped. See docs/schema.graphql. */
@CompileStatic
class GqlField {
    String name, entityField, type
    boolean filterable = false, sortable = false
    Integer costOverride = null
    String resolverService = null
    List<String> resolverIn = []
    /** OUT param to read from the service result; may be a dotted path (e.g. orderDetail.customerFirstName).
     *  Defaults to the field name when unset. */
    String resolverOut = null
    boolean isServiceBacked() { return resolverService != null && !resolverService.isEmpty() }
}

@CompileStatic
class GqlEdge {
    String name, entityRelationship, targetType
    boolean list = false
    /** "connection" (Relay, default) or "list" (plain bounded [Type!]! with a `first` cap). */
    String kind = "connection"
    Integer firstDefault = null
    Integer costOverride = null
    String resolverService = null
    List<String> resolverIn = []
    boolean isServiceBacked() { return resolverService != null && !resolverService.isEmpty() }
    boolean isPlainList() { return list && "list".equals(kind) }
    boolean isConnection() { return list && !"list".equals(kind) }
}

@CompileStatic
class GqlType {
    String name, entityName
    Map<String, GqlField> fields = [:]
    Map<String, GqlEdge> edges = [:]
}

/** A declared argument for a service-backed root query. type: ID|String|Int|Decimal|Boolean|IDList|StringList. */
@CompileStatic
class GqlArg {
    String name, type
    boolean required = false
}

@CompileStatic
class GqlRootQuery {
    String name, targetType, entityName, pkArg
    boolean byPk = false, externalId = false, list = false
    /** Service-backed root (decision 12 at root level): the field's value is a service call result. */
    boolean serviceBacked = false
    String serviceName, serviceOut
    boolean returnsList = false
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
