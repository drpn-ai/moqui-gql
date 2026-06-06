package org.moqui.gql

import graphql.ExecutionInput
import graphql.execution.preparsed.PreparsedDocumentEntry
import graphql.execution.preparsed.PreparsedDocumentProvider
import groovy.transform.CompileStatic
import org.moqui.context.ExecutionContext

import javax.cache.Cache
import java.util.concurrent.CompletableFuture
import java.util.function.Function

/**
 * Prepared-statement-style query cache (the JDBC `PreparedStatement` model applied to GraphQL).
 *
 *   PREPARE once   — graphql-java parses + validates a query string into a Document on first sight.
 *                    We cache that Document keyed by the query string, so repeated query SHAPES skip
 *                    parse + validate. (The query string is the parameterized template — the analogue
 *                    of `… WHERE id = ?`.)
 *   EXECUTE many   — the request's variables are bound per execution and the governor runs per
 *                    execution. Caching the parsed plan therefore NEVER bypasses the cost/limit gate,
 *                    exactly as a PreparedStatement still evaluates its WHERE against each bind.
 *
 * Correctness: validation depends only on (query string, schema) — not on variable VALUES — so keying
 * the cache by the query string is sound. Validation/parse errors are cached too (an invalid query is
 * invalid regardless of binds). The Document is immutable; the Moqui cache holds it by reference.
 */
@CompileStatic
class CachingPreparsedDocumentProvider implements PreparsedDocumentProvider {
    static final String CACHE_NAME = "gql.preparsed.document"
    private final ExecutionContext ec

    CachingPreparsedDocumentProvider(ExecutionContext ec) { this.ec = ec }

    @Override
    CompletableFuture<PreparsedDocumentEntry> getDocumentAsync(
            ExecutionInput input, Function<ExecutionInput, PreparsedDocumentEntry> computeFn) {
        Cache cache = ec.cache.getCache(CACHE_NAME)
        String key = input.getQuery()
        PreparsedDocumentEntry entry = (PreparsedDocumentEntry) cache.get(key)
        if (entry == null) {
            entry = computeFn.apply(input)   // PREPARE: parse + validate (done by graphql-java)
            cache.put(key, entry)
        }
        return CompletableFuture.completedFuture(entry)
    }
}
