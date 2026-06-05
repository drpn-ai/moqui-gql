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
    boolean isServiceBacked() { return resolverService != null && !resolverService.isEmpty() }
}

@CompileStatic
class GqlEdge {
    String name, entityRelationship, targetType
    boolean list = false
    Integer costOverride = null
    String resolverService = null
    List<String> resolverIn = []
    boolean isServiceBacked() { return resolverService != null && !resolverService.isEmpty() }
}

@CompileStatic
class GqlType {
    String name, entityName
    Map<String, GqlField> fields = [:]
    Map<String, GqlEdge> edges = [:]
}

@CompileStatic
class GqlRootQuery {
    String name, targetType, entityName, pkArg
    boolean byPk = false, externalId = false, list = false
    /** search key (our field name) -> allowed comparators (eq, in, gt, gte, lt, lte). */
    Map<String, Set<String>> searchKeys = [:]
    /** sortKey enum value -> entity field it sorts by. */
    Map<String, String> sortKeys = [:]
}

@CompileStatic
class SchemaArtifact {
    Map<String, GqlType> types = [:]
    Map<String, GqlRootQuery> rootQueries = [:]
}
