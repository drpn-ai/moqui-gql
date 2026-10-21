package org.moqui.gql

import groovy.transform.CompileStatic
import org.moqui.util.MNode

/** Parses `graphql/*.gql.xml` artifact MNodes into a SchemaArtifact. Pure transformation (no EC). */
@CompileStatic
class SchemaArtifactParser {

    SchemaArtifact parse(List<MNode> schemaNodes) {
        SchemaArtifact art = new SchemaArtifact()
        for (MNode root in schemaNodes) {
            for (MNode tn in root.children("gql-type")) {
                GqlType t = new GqlType(name: tn.attribute("name"), entityName: tn.attribute("entity-name"), description: describe(tn))
                for (MNode fn in tn.children("field")) {
                    t.fields.put(fn.attribute("name"), new GqlField(
                            name: fn.attribute("name"), entityField: fn.attribute("entity-field"), description: describe(fn),
                            type: fn.attribute("type"),
                            filterable: "true".equals(fn.attribute("filterable")),
                            sortable: "true".equals(fn.attribute("sortable")),
                            list: "true".equals(fn.attribute("list")),
                            costOverride: asInt(fn.attribute("cost")),
                            resolverService: fn.attribute("resolver-service"),
                            resolverIn: splitList(fn.attribute("resolver-in")),
                            resolverOut: fn.attribute("resolver-out"),
                            aggregateFunction: fn.attribute("aggregate"),
                            aggregateEntity: fn.attribute("aggregate-entity"),
                            aggregateFk: fn.attribute("aggregate-fk"),
                            aggregateField: fn.attribute("aggregate-field")))
                }
                for (MNode en in tn.children("edge")) {
                    t.edges.put(en.attribute("name"), new GqlEdge(
                            name: en.attribute("name"), entityRelationship: en.attribute("entity-relationship"), description: describe(en),
                            targetType: en.attribute("target-type"), list: "true".equals(en.attribute("list")),
                            kind: en.attribute("kind") ?: "connection", firstDefault: asInt(en.attribute("first-default")),
                            sourceKey: en.attribute("source-key"),
                            costOverride: asInt(en.attribute("cost")),
                            resolverService: en.attribute("resolver-service"),
                            resolverIn: splitList(en.attribute("resolver-in")),
                            single: "true".equals(en.attribute("single")), childEntity: en.attribute("entity-name"),
                            fk: en.attribute("fk"), parentKey: en.attribute("parent-key"),
                            excludeEmpty: en.attribute("exclude-empty")))
                }
                art.types.put(t.name, t)
                // root-query declared inside a gql-type: target-type defaults to the enclosing type
                for (MNode qn in tn.children("root-query")) addRootQuery(art, qn, t.name)
            }
            // root-query declared at schema level: target-type must be explicit
            for (MNode qn in root.children("root-query")) addRootQuery(art, qn, null)
        }
        return art
    }

    private static void addRootQuery(SchemaArtifact art, MNode qn, String defaultTargetType) {
        String tgt = qn.attribute("target-type") ?: defaultTargetType
        GqlRootQuery rq = new GqlRootQuery(
                name: qn.attribute("name"), targetType: tgt, description: describe(qn),
                entityName: qn.attribute("entity-name"), pkArg: qn.attribute("pk-arg"),
                byPk: "true".equals(qn.attribute("by-pk")),
                externalId: "true".equals(qn.attribute("external-id")),
                list: "true".equals(qn.attribute("list")),
                searchKeys: parseSearchKeys(qn.attribute("search-keys")),
                sortKeys: parseSortKeys(qn.attribute("sort-keys")),
                byIdentification: "true".equals(qn.attribute("by-identification")),
                identEntity: qn.attribute("identification-entity"),
                identTypeArg: qn.attribute("type-arg"), identTypeField: qn.attribute("type-field"),
                identValueArg: qn.attribute("value-arg"), identValueField: qn.attribute("value-field"),
                identFkField: qn.attribute("fk-field"),
                serviceBacked: "true".equals(qn.attribute("service")),
                serviceName: qn.attribute("service-name"), serviceOut: qn.attribute("service-out"),
                returnsList: "true".equals(qn.attribute("returns-list")),
                tenantParam: qn.attribute("tenant-param"))
        for (MNode an in qn.children("arg")) rq.args.add(new GqlArg(
                name: an.attribute("name"), type: an.attribute("type") ?: "String", description: describe(an),
                required: "true".equals(an.attribute("required"))))
        // A tenant a caller could name would be a cross-tenant read: refuse the schema rather than serve it.
        if (rq.tenantParam && rq.args.any { it.name == rq.tenantParam })
            throw new IllegalArgumentException("root-query ${rq.name}: tenant-param ${rq.tenantParam} may not also be an arg")
        art.rootQueries.put(qn.attribute("name"), rq)
    }

    /** DAR-BE-099: a description attribute, or a <description> child for longer text; whitespace runs collapse to one space. */
    private static String describe(MNode n) {
        String d = n.attribute("description")
        if (d == null || d.trim().isEmpty()) {
            MNode child = n.first("description")
            d = child != null ? child.getText() : null
        }
        return (d == null || d.trim().isEmpty()) ? null : d.trim().replaceAll("\\s+", " ")
    }

    private static Integer asInt(String s) { return (s != null && !s.isEmpty()) ? Integer.valueOf(s) : (Integer) null }

    private static List<String> splitList(String s) {
        if (s == null || s.isEmpty()) return new ArrayList<String>()
        List<String> out = new ArrayList<String>()
        for (String p in s.split(",")) out.add(p.trim())
        return out
    }

    /** "statusId:eq,in orderDate:gt,gte,lt,lte orderName:eq" -> {statusId:[eq,in], orderDate:[gt,gte,lt,lte], orderName:[eq]} */
    private static Map<String, Set<String>> parseSearchKeys(String s) {
        Map<String, Set<String>> m = new LinkedHashMap<String, Set<String>>()
        if (s == null || s.trim().isEmpty()) return m
        for (String term in s.trim().split("\\s+")) {
            int c = term.indexOf((int) (':' as char))
            if (c < 0) { m.put(term, ['eq'] as Set<String>); continue }
            Set<String> ops = new LinkedHashSet<String>()
            for (String op in term.substring(c + 1).split(",")) ops.add(op.trim())
            m.put(term.substring(0, c), ops)
        }
        return m
    }

    /** "ORDER_DATE:orderDate ORDER_NAME:orderName" -> {ORDER_DATE:orderDate, ORDER_NAME:orderName} */
    private static Map<String, String> parseSortKeys(String s) {
        Map<String, String> m = new LinkedHashMap<String, String>()
        if (s == null || s.trim().isEmpty()) return m
        for (String term in s.trim().split("\\s+")) {
            int c = term.indexOf((int) (':' as char))
            if (c > 0) m.put(term.substring(0, c), term.substring(c + 1))
        }
        return m
    }
}
