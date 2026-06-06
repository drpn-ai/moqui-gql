import spock.lang.Specification
import org.moqui.gql.policy.ThrottleGate

/** Phase 2 C1 — pure unit test of the token-bucket math (refill / cap / debit / block / peek),
 *  time injected so it is deterministic and needs no EC. */
class ThrottleGateTests extends Specification {

    def "decide debits, refills over time, caps at bucketSize, blocks over-cost, and peeks without debit"() {
        given:
        def b = new ThrottleGate.Bucket(available: 100d, lastMillis: 1000L)

        when: "debit 30 with no elapsed time"
        def d1 = ThrottleGate.decide(b, 30, 100, 10, 1000L, true)
        then:
        d1.allowed; d1.currentlyAvailable == 70d; d1.maximumAvailable == 100; d1.restoreRate == 10

        when: "request 80 > available 70 -> blocked, nothing debited"
        def d2 = ThrottleGate.decide(b, 80, 100, 10, 1000L, true)
        then:
        !d2.allowed; d2.currentlyAvailable == 70d

        when: "5s later at 10/s = +50 -> capped at 100; 80 affordable -> 20 left"
        def d3 = ThrottleGate.decide(b, 80, 100, 10, 6000L, true)
        then:
        d3.allowed; d3.currentlyAvailable == 20d

        when: "peek (apply=false) does not debit"
        def d4 = ThrottleGate.decide(b, 5, 100, 10, 6000L, false)
        then:
        d4.allowed; d4.currentlyAvailable == 20d
    }
}
