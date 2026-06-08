import spock.lang.Specification
import org.moqui.util.MNode
import org.moqui.gql.SchemaArtifact
import org.moqui.gql.SchemaArtifactParser

/** Pure unit test (no EC) for the schema-artifact parser. */
class SchemaArtifactParserTests extends Specification {

    def "parses types, fields, edges, service-backed fields, and root query search/sort keys"() {
        given:
        MNode node = MNode.parseText("t.gql.xml", '''
            <gql-schema>
              <gql-type name="Order" entity-name="org.apache.ofbiz.order.order.OrderHeader">
                <field name="orderId" entity-field="orderId"/>
                <field name="orderDate" entity-field="orderDate" type="DateTime"/>
                <field name="customerName" resolver-service="X.get#Cust" resolver-in="orderId"/>
                <edge name="orderItems" entity-relationship="items" target-type="OrderItem" list="true"/>
                <root-query name="order" by-pk="true" pk-arg="orderId" external-id="true"/>
                <root-query name="orders" list="true"
                    search-keys="statusId:eq,in orderDate:gt,gte,lt,lte orderName:eq"
                    sort-keys="ORDER_DATE:orderDate ORDER_NAME:orderName"/>
              </gql-type>
            </gql-schema>''')

        when:
        SchemaArtifact art = new SchemaArtifactParser().parse([node])

        then: "types / fields / edges"
        art.types.size() == 1
        def t = art.types["Order"]
        t.entityName == "org.apache.ofbiz.order.order.OrderHeader"
        t.fields["orderDate"].type == "DateTime"
        !t.fields["orderId"].isServiceBacked()
        t.fields["customerName"].isServiceBacked()
        t.fields["customerName"].resolverService == "X.get#Cust"
        t.fields["customerName"].resolverIn == ["orderId"]
        t.edges["orderItems"].list
        t.edges["orderItems"].targetType == "OrderItem"

        and: "root queries with declared search + sort grammar (Q3); target-type defaults to enclosing type"
        def q = art.rootQueries["orders"]
        q.list
        q.targetType == "Order"
        q.searchKeys["statusId"] == (["eq", "in"] as Set)
        q.searchKeys["orderDate"] == (["gt", "gte", "lt", "lte"] as Set)
        q.searchKeys["orderName"] == (["eq"] as Set)
        q.sortKeys["ORDER_DATE"] == "orderDate"
        q.sortKeys["ORDER_NAME"] == "orderName"
        art.rootQueries["order"].byPk
        art.rootQueries["order"].externalId
        art.rootQueries["order"].pkArg == "orderId"
    }

    def "parses an aggregate field"() {
        when:
        def art = new org.moqui.gql.SchemaArtifactParser().parse([ org.moqui.util.MNode.parseText("t.gql.xml",
            '<gql-schema><gql-type name="Order" entity-name="org.apache.ofbiz.order.order.OrderHeader">' +
            '<field name="orderItemCount" type="Int" aggregate="count-distinct" ' +
            'aggregate-entity="org.apache.ofbiz.order.order.OrderItem" aggregate-fk="orderId" aggregate-field="externalId"/>' +
            '</gql-type></gql-schema>') ])
        def f = art.types.get("Order").fields.get("orderItemCount")
        then:
        f.isAggregate()
        f.aggregateFunction == "count-distinct"
        f.aggregateEntity == "org.apache.ofbiz.order.order.OrderItem"
        f.aggregateFk == "orderId"
        f.aggregateField == "externalId"
        !f.isServiceBacked()
    }
}
