import spock.lang.Specification
import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityCondition
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

    /** A minimal ExecutionContext whose conditionFactory.makeCondition(field, op, value) returns the
     *  `value` it was handed, so the test can read back exactly which companyUserGroupId the filter chose. */
    private static ExecutionContext stubEc(Closure<Void> tenantSupplier = null) {
        def condFactory = [ makeCondition: { String f, EntityCondition.ComparisonOperator op, Object v -> v } ]
        def entity = [ conditionFactory: condFactory ]
        // For the no-arg (session) path conditionFor reads TenantAccessSupport (a static), which we do not
        // exercise here; the fixed-tenant path never touches ec beyond the conditionFactory.
        return ([ getEntity: { -> entity }, entity: entity ] as ExecutionContext)
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
        filter.conditionFor("OrderHeader", stubEc()) == "HotwaxTenantA"
    }

    def "fixed-tenant filter DENIES on blank/null tenant (fail-closed: NO_TENANT sentinel)"() {
        given:
        def blankFilter = new DarpanTenantScopeFilter("")
        def spaceFilter = new DarpanTenantScopeFilter("   ")
        def nullFilter = new DarpanTenantScopeFilter((String) null)
        expect: "the deny sentinel, never a real/empty tenant that could widen the scope"
        blankFilter.conditionFor("OrderHeader", stubEc()) == "__DARPAN_NO_ACTIVE_TENANT__"
        spaceFilter.conditionFor("OrderHeader", stubEc()) == "__DARPAN_NO_ACTIVE_TENANT__"
        nullFilter.conditionFor("OrderHeader", stubEc()) == "__DARPAN_NO_ACTIVE_TENANT__"
    }
}
