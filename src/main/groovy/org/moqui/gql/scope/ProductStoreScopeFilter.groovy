package org.moqui.gql.scope

import groovy.transform.CompileStatic
import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityCondition
import org.moqui.impl.entity.EntityFacadeImpl

/**
 * Phase-2 caller scope: restrict reads to a single productStore. Applied only to entities that have a
 * productStoreId field (e.g. OrderHeader); others are unrestricted. Activated per request by the
 * engine when the caller's profile sets scopeProductStoreId.
 */
@CompileStatic
class ProductStoreScopeFilter implements ScopeFilter {
    private final String productStoreId
    ProductStoreScopeFilter(String productStoreId) { this.productStoreId = productStoreId }

    EntityCondition conditionFor(String entityName, ExecutionContext ec) {
        def ed = ((EntityFacadeImpl) ec.entity).getEntityDefinition(entityName)
        if (ed != null && ed.isField("productStoreId"))
            return ec.entity.conditionFactory.makeCondition("productStoreId", EntityCondition.EQUALS, productStoreId)
        return null
    }
}
