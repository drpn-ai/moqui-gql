import spock.lang.Specification
import org.moqui.gql.GqlRootQuery
import org.moqui.gql.GqlValidationException
import org.moqui.gql.search.SearchQueryParser

/** Pure unit test (no EC) for the Shopify-style `query:` parser + declare-and-control (Q3). */
class SearchQueryParserTests extends Specification {

    private GqlRootQuery rq() {
        def q = new GqlRootQuery(name: "orders")
        q.searchKeys = [
                statusId  : (["eq", "in"] as Set),
                orderDate : (["gt", "gte", "lt", "lte"] as Set),
                externalId: (["eq", "in"] as Set),
                orderName : (["eq"] as Set)
        ]
        return q
    }

    def "Q3a: comma=in and date range comparators parse"() {
        when:
        def terms = new SearchQueryParser().parse(
                "statusId:ORDER_APPROVED,ORDER_HELD orderDate:>=2026-05-01 orderDate:<=2026-05-31", rq())
        then:
        terms.size() == 3
        terms[0].key == "statusId" && terms[0].comparator == "in" && terms[0].values == ["ORDER_APPROVED", "ORDER_HELD"]
        terms[1].comparator == "gte" && terms[1].values == ["2026-05-01"]
        terms[2].comparator == "lte"
    }

    def "Q3b: disallowed comparator -> OPERATOR_NOT_ALLOWED"() {
        when:
        new SearchQueryParser().parse("statusId:>ORDER_APPROVED", rq())
        then:
        def e = thrown(GqlValidationException)
        e.code == "OPERATOR_NOT_ALLOWED"
        e.ext.key == "statusId"
    }

    def "N2: undeclared key -> FIELD_NOT_FILTERABLE"() {
        when:
        new SearchQueryParser().parse("orderName2:Gift", rq())
        then:
        def e = thrown(GqlValidationException)
        e.code == "FIELD_NOT_FILTERABLE"
        e.ext.key == "orderName2"
    }

    def "J3: value containing ':' splits on the first colon only"() {
        when:
        def terms = new SearchQueryParser().parse("externalId:shopify:4567890,shopify:4567999", rq())
        then:
        terms.size() == 1
        terms[0].key == "externalId"
        terms[0].comparator == "in"
        terms[0].values == ["shopify:4567890", "shopify:4567999"]
    }
}
