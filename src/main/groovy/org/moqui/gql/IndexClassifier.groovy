package org.moqui.gql

import groovy.transform.CompileStatic
import org.moqui.context.ExecutionContext
import org.moqui.impl.entity.EntityDefinition
import org.moqui.impl.entity.EntityFacadeImpl
import org.moqui.util.MNode

/** Classifies which fields of an entity are index-backed (PK fields + fields named in any <index>).
 *  Used to validate that declared search keys are index-backed and to penalize unindexed filters
 *  (governance layer 3). Cached per process (immutable schema). */
@CompileStatic
class IndexClassifier {
    private final ExecutionContext ec
    private final Map<String, Set<String>> cache = new HashMap<String, Set<String>>()

    IndexClassifier(ExecutionContext ec) { this.ec = ec }

    Set<String> indexedFields(String entityName) {
        Set<String> cached = cache.get(entityName)
        if (cached != null) return cached
        EntityFacadeImpl efi = (EntityFacadeImpl) ec.entity
        EntityDefinition ed = efi.getEntityDefinition(entityName)
        Set<String> result = new LinkedHashSet<String>()
        if (ed == null) { cache.put(entityName, result); return result }
        result.addAll(ed.getPkFieldNames())
        MNode node = ed.getEntityNode()
        for (MNode idx in node.children("index")) {
            for (MNode idxField in idx.children("index-field")) {
                String fn = idxField.attribute("name")
                if (fn != null && !fn.isEmpty()) result.add(fn)
            }
        }
        cache.put(entityName, result)
        return result
    }

    boolean isIndexed(String entityName, String fieldName) { return indexedFields(entityName).contains(fieldName) }
}
