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
 *
 * Two construction modes (both fail-closed):
 *  - no-arg (session path): tenant is resolved per-find from {@link TenantAccessSupport}.
 *  - fixed-tenant (public API-key path): tenant is pinned at construction from the resolved API key, so
 *    the public realm never consults the session/{@code TenantAccessSupport}. A blank/null fixed tenant
 *    constructs a DENY filter (the same NO_TENANT sentinel) so zero rows are ever returned.
 */
@CompileStatic
class DarpanTenantScopeFilter implements ScopeFilter {
    /** Sentinel that cannot match any real companyUserGroupId — used to deny when there is no tenant. */
    private static final String NO_TENANT = "__DARPAN_NO_ACTIVE_TENANT__"

    /** When non-null, the tenant is pinned to this value for every find (public API-key path). When
     *  null, the tenant is resolved per-find from the session (TenantAccessSupport). */
    private final String fixedTenantId
    /** Marks the fixed-tenant variant so a blank fixedTenantId denies (zero rows) instead of falling
     *  through to the session path. */
    private final boolean fixedMode

    /** Session path: resolve the active tenant per-find via TenantAccessSupport. Unchanged behaviour. */
    DarpanTenantScopeFilter() {
        this.fixedTenantId = null
        this.fixedMode = false
    }

    /** Public API-key path: pin the tenant at construction. A blank/null fixedTenantId is fail-closed —
     *  it produces the NO_TENANT deny condition (zero rows), never the session fallback. */
    DarpanTenantScopeFilter(String fixedTenantId) {
        this.fixedTenantId = (fixedTenantId != null && !fixedTenantId.trim().isEmpty()) ? fixedTenantId : null
        this.fixedMode = true
    }

    @Override
    EntityCondition conditionFor(String entityName, ExecutionContext ec) {
        String value
        if (fixedMode) {
            // Fixed-tenant variant: use the pinned tenant; blank/null -> deny (fail-closed). NEVER read
            // the session here — the public realm must not inherit a logged-in user's active tenant.
            value = fixedTenantId ?: NO_TENANT
        } else {
            // Session variant (unchanged): resolve the caller's active tenant; none -> deny.
            String activeTenant = TenantAccessSupport.currentActiveTenantUserGroupId(ec)
            value = activeTenant ?: NO_TENANT
        }
        return ec.entity.conditionFactory.makeCondition(
                "companyUserGroupId", EntityCondition.ComparisonOperator.EQUALS, value)
    }
}
