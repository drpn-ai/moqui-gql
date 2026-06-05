import spock.lang.Specification
import org.moqui.util.MNode
import org.moqui.gql.SchemaArtifactParser
import org.moqui.gql.GqlSchemaBuilder
import graphql.schema.GraphQLEnumType

/** Pure unit test (no EC) for the graphql-java schema builder (Task 7). */
class GqlSchemaBuilderTests extends Specification {

    def "builds object types, Relay connections, root args, sortKey enum, scalars; populates cost model"() {
        given:
        MNode node = MNode.parseText("t.gql.xml", '''
          <gql-schema>
            <gql-type name="Order" entity-name="org.apache.ofbiz.order.order.OrderHeader">
              <field name="orderId" entity-field="orderId"/>
              <field name="orderDate" entity-field="orderDate" type="DateTime"/>
              <field name="grandTotal" entity-field="grandTotal" type="Decimal"/>
              <field name="customerName" resolver-service="X.get#Cust" resolver-in="orderId"/>
              <edge name="orderItems" entity-relationship="items" target-type="OrderItem" list="true"/>
              <root-query name="order" by-pk="true" pk-arg="orderId" external-id="true"/>
              <root-query name="orders" list="true"
                  search-keys="statusId:eq,in orderDate:gt,gte,lt,lte"
                  sort-keys="ORDER_DATE:orderDate ORDER_NAME:orderName"/>
            </gql-type>
            <gql-type name="OrderItem" entity-name="org.apache.ofbiz.order.order.OrderItem">
              <field name="productId" entity-field="productId"/>
            </gql-type>
          </gql-schema>''')
        def art = new SchemaArtifactParser().parse([node])

        when:
        def built = new GqlSchemaBuilder().build(art)
        def s = built.schema

        then: "object types + custom scalars"
        s.getObjectType("Order") != null
        s.getObjectType("Order").getFieldDefinition("orderDate") != null
        s.getType("DateTime") != null
        s.getType("Decimal") != null

        and: "Relay connection wiring + full PageInfo + nested paging args"
        s.getObjectType("OrderConnection") != null
        s.getObjectType("OrderEdge").getFieldDefinition("cursor") != null
        s.getObjectType("PageInfo").getFieldDefinition("hasPreviousPage") != null
        s.getObjectType("PageInfo").getFieldDefinition("startCursor") != null
        def items = s.getObjectType("Order").getFieldDefinition("orderItems")
        items.getArgument("first") != null && items.getArgument("after") != null
        items.getArgument("last") != null && items.getArgument("before") != null

        and: "root Query: list field with query/sortKey/reverse; by-pk with id + externalId"
        def q = s.getQueryType()
        def ordersF = q.getFieldDefinition("orders")
        ordersF.getArgument("query") != null
        ordersF.getArgument("sortKey") != null
        ordersF.getArgument("reverse") != null
        q.getFieldDefinition("order").getArgument("orderId") != null
        q.getFieldDefinition("order").getArgument("externalId") != null

        and: "sortKey enum built from declared keys"
        s.getType("OrderSortKey") instanceof GraphQLEnumType
        ((GraphQLEnumType) s.getType("OrderSortKey")).getValues()*.name.containsAll(["ORDER_DATE", "ORDER_NAME"])

        and: "cost model populated (list fields + service-backed fields)"
        built.costModel.listFields.contains("orderItems")
        built.costModel.listFields.contains("orders")
        built.costModel.serviceBackedFields.contains("customerName")
    }
}
