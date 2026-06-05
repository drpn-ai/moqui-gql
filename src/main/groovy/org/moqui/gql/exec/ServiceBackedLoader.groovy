package org.moqui.gql.exec

import groovy.transform.CompileStatic
import org.dataloader.BatchLoaderEnvironment
import org.dataloader.MappedBatchLoaderWithContext
import org.moqui.context.ExecutionContext

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/**
 * Batched resolver for a service-backed field (decision 12). graphql-java collects the parent keys at
 * one query level; this calls the Moqui service once per UNIQUE key-tuple — dedup'ing repeats within a
 * request and capping at serviceBatchKeyLimit (the cap is enforced as a hard governor error in C4; here
 * it is a logged backstop, never a silent truncation). The OUT value is read by a possibly-dotted path
 * so nested result maps work (e.g. orderDetail.customerFirstName).
 *
 * Each key is the List of resolver-in values pulled from the parent row; DataLoader dedups by list
 * equality. The service inherits the request's authz/transaction (one read-only txn on this thread).
 */
@CompileStatic
class ServiceBackedLoader implements MappedBatchLoaderWithContext<Object, Object> {
    private final ExecutionContext ec
    private final String serviceName
    private final List<String> inParams
    private final List<String> outPath
    private final int serviceBatchKeyLimit
    private final String label

    ServiceBackedLoader(ExecutionContext ec, String serviceName, List<String> inParams, String outParam,
                        int serviceBatchKeyLimit, String label) {
        this.ec = ec; this.serviceName = serviceName; this.inParams = inParams
        this.outPath = Arrays.asList((outParam ?: "").split("\\."))
        this.serviceBatchKeyLimit = serviceBatchKeyLimit; this.label = label
    }

    @Override
    CompletionStage<Map<Object, Object>> load(Set<Object> keys, BatchLoaderEnvironment env) {
        Map<Object, Object> out = new LinkedHashMap<Object, Object>()
        int processed = 0
        for (Object key in keys) {
            if (processed >= serviceBatchKeyLimit) {
                ec.logger.warn("gql: service-backed field ${label} exceeded serviceBatchKeyLimit=${serviceBatchKeyLimit}; " +
                        "${keys.size() - serviceBatchKeyLimit} key(s) left unresolved (becomes a hard error in the governor)")
                break
            }
            processed++
            try {
                Map<String, Object> params = new LinkedHashMap<String, Object>()
                List<Object> keyVals = (key instanceof List) ? (List<Object>) key : Collections.singletonList(key)
                for (int i = 0; i < inParams.size(); i++) params.put(inParams.get(i), i < keyVals.size() ? keyVals.get(i) : null)
                Map result = ec.service.sync().name(serviceName).parameters(params).call()
                out.put(key, navigate(result))
            } catch (Throwable t) {
                ec.logger.error("gql: service-backed field ${label} failed for key ${key}: ${t.message}", t)
                out.put(key, null)
            }
        }
        return CompletableFuture.completedFuture(out)
    }

    private Object navigate(Map result) {
        Object cur = result
        for (String seg in outPath) {
            if (seg.isEmpty()) continue
            cur = (cur instanceof Map) ? ((Map) cur).get(seg) : null
            if (cur == null) return null
        }
        return cur
    }
}
