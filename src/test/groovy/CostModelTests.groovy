import spock.lang.Specification
import org.moqui.gql.CostModel

/** Pure unit test (no EC) for the cost model's fan-out, capping, service cost, and long-saturation. */
class CostModelTests extends Specification {

    def "fan-out multiplies, caps at maxFirst, service is fixed, and cost saturates (no int overflow)"() {
        given:
        def cm = new CostModel(serviceFixedCost: 25, maxFirst: 100, costCeiling: 1_000_000L)
        expect:
        cm.listComplexity(50, 3) == 200          // 50 * (1+3)
        cm.listComplexity(99999, 3) == 400       // first capped to maxFirst=100 -> 100 * 4
        cm.serviceCost(0) == 25
        cm.serviceCost(10) == 35
        cm.saturate(250L) == 250
        cm.saturate(5_000_000L) == 1_000_000     // clamped to ceiling
        cm.saturate(-1L) == 1_000_000            // overflow-ish negative also clamps up (fails the gate)
    }
}
