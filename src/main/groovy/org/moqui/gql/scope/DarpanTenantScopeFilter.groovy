package org.moqui.gql.scope

import groovy.transform.CompileStatic
import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityCondition
import darpan.facade.common.TenantAccessSupport

/**
 * Fail-CLOSED tenant row-scope for Darpan's shared-DB multi-tenancy.
 *
 * moqui-gql's default {@link NoOpScopeFilter} assumes one database per client (no row restriction).
 * Darpan is the opposite: a single shared database partitioned by {@code companyUserGroupId}. So every
 * GraphQL entity read MUST be constrained to the caller's active tenant, or it would read across all
 * tenants. The engine consults this filter before EVERY find (see {@link ScopeFilters#apply}).
 *
 * Behaviour (fail-closed by construction):
 *  - active tenant resolved (via Darpan's authoritative {@link TenantAccessSupport}) → AND
 *    {@code companyUserGroupId = <activeTenant>} into the find.
 *  - no active tenant (unauthenticated / no tenant selected) → AND an impossible value, so ZERO rows.
 *  - an entity with no {@code companyUserGroupId} column → the condition references a non-existent field
 *    and the read errors out rather than returning unscoped rows. That is intentional: a transitively
 *    owned entity must be exposed only via a gated parent edge, never as an unscoped root. Adding such a
 *    type to the schema fails loudly here instead of leaking silently.
 */
@CompileStatic
class DarpanTenantScopeFilter implements ScopeFilter {
    /** Sentinel that cannot match any real companyUserGroupId — used to deny when there is no tenant. */
    private static final String NO_TENANT = "__DARPAN_NO_ACTIVE_TENANT__"

    @Override
    EntityCondition conditionFor(String entityName, ExecutionContext ec) {
        String activeTenant = TenantAccessSupport.currentActiveTenantUserGroupId(ec)
        String value = activeTenant ?: NO_TENANT
        return ec.entity.conditionFactory.makeCondition(
                "companyUserGroupId", EntityCondition.ComparisonOperator.EQUALS, value)
    }
}
