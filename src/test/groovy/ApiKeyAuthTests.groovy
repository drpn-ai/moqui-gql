import spock.lang.Specification
import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityCondition
import org.moqui.entity.EntityConditionFactory
import org.moqui.entity.EntityFacade
import org.moqui.gql.auth.ApiKeyHasher
import org.moqui.gql.scope.DarpanTenantScopeFilter

/**
 * Pure unit test (no Moqui boot) for the public API-key auth realm primitives:
 *  - ApiKeyHasher.sha256Hex is the canonical SHA-256 hex (deterministic, lowercase, 64 chars) and never
 *    returns the raw input.
 *  - ApiKeyHasher.generateRawKey is prefixed, high-entropy, and unique per call.
 *  - DarpanTenantScopeFilter's fixed-tenant variant is fail-closed: a blank/null fixed tenant resolves
 *    to the NO_TENANT deny sentinel (zero rows), a real tenant to itself, and the no-arg (session)
 *    variant is unchanged.
 */
class ApiKeyAuthTests extends Specification {

    /** The companyUserGroupId each conditionFor handed to makeCondition, in order. */
    private static final List<Object> chosen = []

    /**
     * A minimal ExecutionContext whose conditionFactory records the value the filter conditions on, so the test can read
     * back exactly which companyUserGroupId it chose. Typed proxies: DarpanTenantScopeFilter is @CompileStatic, so a
     * plain Map standing in for EntityFacade fails with a ClassCastException (DAR-BE-101: this suite never ran before).
     */
    private static ExecutionContext stubEc() {
        EntityConditionFactory condFactory = [makeCondition: { String f, EntityCondition.ComparisonOperator op, Object v ->
            chosen << v; return (EntityCondition) null }] as EntityConditionFactory
        EntityFacade entity = [getConditionFactory: { -> condFactory }] as EntityFacade
        return ([getEntity: { -> entity }] as ExecutionContext)
    }

    private static Object tenantChosenBy(DarpanTenantScopeFilter filter) {
        chosen.clear()
        filter.conditionFor("OrderHeader", stubEc())
        return chosen.size() == 1 ? chosen[0] : chosen
    }

    def "sha256Hex is the known SHA-256 hex of a known input"() {
        expect: // echo -n "abc" | sha256sum
        ApiKeyHasher.sha256Hex("abc") ==
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    }

    def "sha256Hex is deterministic, 64-hex, lowercase, and never echoes the raw key"() {
        given:
        String raw = "dgql_secretvalue"
        when:
        String h = ApiKeyHasher.sha256Hex(raw)
        then:
        h == ApiKeyHasher.sha256Hex(raw)        // deterministic
        h.length() == 64
        h ==~ /[0-9a-f]{64}/                      // lowercase hex only
        h != raw                                  // never the raw key
        !h.contains(raw)
    }

    def "sha256Hex tolerates null without NPE (hashes empty string)"() {
        expect: // sha256 of the empty string
        ApiKeyHasher.sha256Hex(null) ==
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    }

    def "generateRawKey is prefixed, long, and unique per call"() {
        when:
        String a = ApiKeyHasher.generateRawKey()
        String b = ApiKeyHasher.generateRawKey()
        then:
        a.startsWith("dgql_")
        b.startsWith("dgql_")
        a != b                                    // 256 bits of entropy -> effectively never collide
        a.length() > 40                           // prefix + ~43 base64url chars for 32 bytes
    }

    def "fixed-tenant filter pins a real tenant"() {
        given:
        def filter = new DarpanTenantScopeFilter("HotwaxTenantA")
        expect: // stub conditionFactory returns the value -> the tenant the filter chose
        tenantChosenBy(filter) == "HotwaxTenantA"
    }

    def "fixed-tenant filter DENIES on blank/null tenant (fail-closed: NO_TENANT sentinel)"() {
        given:
        def blankFilter = new DarpanTenantScopeFilter("")
        def spaceFilter = new DarpanTenantScopeFilter("   ")
        def nullFilter = new DarpanTenantScopeFilter((String) null)
        expect: "the deny sentinel, never a real/empty tenant that could widen the scope"
        tenantChosenBy(blankFilter) == "__DARPAN_NO_ACTIVE_TENANT__"
        tenantChosenBy(spaceFilter) == "__DARPAN_NO_ACTIVE_TENANT__"
        tenantChosenBy(nullFilter) == "__DARPAN_NO_ACTIVE_TENANT__"
    }
}
