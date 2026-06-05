package org.moqui.gql.search

import groovy.transform.CompileStatic
import org.moqui.gql.GqlRootQuery
import org.moqui.gql.GqlValidationException

/** One parsed term of a `query:` search string, e.g. statusId IN [ORDER_APPROVED, ORDER_HELD]. */
@CompileStatic
class SearchTerm {
    String key
    String comparator   // eq, in, gt, gte, lt, lte
    List<String> values = []
}

/**
 * Parses a Shopify-style `query:` search string into validated SearchTerms (D-A).
 * Grammar (phase 1 subset): whitespace-separated `key:value` terms (implicit AND).
 *   key:val           -> eq;  key:a,b -> in;  key:>v >=v <v <=v -> gt/gte/lt/lte
 *   values may be double-quoted to strip quotes (spaces inside quotes NOT supported in phase 1)
 * Only declared search keys + their declared comparators are honored (Q3); otherwise it throws a
 * GqlValidationException with FIELD_NOT_FILTERABLE / OPERATOR_NOT_ALLOWED / MALFORMED_QUERY.
 * Split is on the FIRST ':' so values may themselves contain ':' (e.g. externalId:shopify:4567890).
 */
@CompileStatic
class SearchQueryParser {

    List<SearchTerm> parse(String query, GqlRootQuery rq) {
        List<SearchTerm> out = new ArrayList<SearchTerm>()
        if (query == null || query.trim().isEmpty()) return out
        for (String raw in query.trim().split("\\s+")) {
            int c = raw.indexOf((int) (':' as char))
            if (c < 0) throw new GqlValidationException("MALFORMED_QUERY",
                    "search term '" + raw + "' must be key:value", ['term': (Object) raw])
            String key = raw.substring(0, c)
            String rest = raw.substring(c + 1)

            Set<String> allowed = rq.searchKeys.get(key)
            if (allowed == null) throw new GqlValidationException("FIELD_NOT_FILTERABLE",
                    "search key '" + key + "' is not filterable (allowed: " + rq.searchKeys.keySet().join(", ") + ")",
                    ['key': (Object) key])

            String comparator = "eq"
            String valuePart = rest
            if (rest.startsWith(">=")) { comparator = "gte"; valuePart = rest.substring(2) }
            else if (rest.startsWith("<=")) { comparator = "lte"; valuePart = rest.substring(2) }
            else if (rest.startsWith(">")) { comparator = "gt"; valuePart = rest.substring(1) }
            else if (rest.startsWith("<")) { comparator = "lt"; valuePart = rest.substring(1) }

            List<String> values
            if (comparator == "eq" && valuePart.indexOf((int) (',' as char)) >= 0) {
                comparator = "in"
                values = new ArrayList<String>()
                for (String p in valuePart.split(",")) values.add(unquote(p.trim()))
            } else {
                values = [unquote(valuePart)] as List<String>
            }

            if (!allowed.contains(comparator)) throw new GqlValidationException("OPERATOR_NOT_ALLOWED",
                    "comparator '" + comparator + "' not allowed on search key '" + key + "' (allowed: " + allowed.join(", ") + ")",
                    ['key': (Object) key, 'allowed': (Object) new ArrayList<String>(allowed)])

            out.add(new SearchTerm(key: key, comparator: comparator, values: values))
        }
        return out
    }

    private static String unquote(String s) {
        return (s.length() >= 2 && s.startsWith('"') && s.endsWith('"')) ? s.substring(1, s.length() - 1) : s
    }
}
