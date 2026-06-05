import spock.lang.Specification
import spock.lang.Shared
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.gql.IndexClassifier

/** EC-backed: index classifier derives index-backed fields from the live entity definitions. */
class IndexClassifierTests extends Specification {
    @Shared ExecutionContext ec

    def setupSpec() { ec = Moqui.getExecutionContext(); ec.artifactExecution.disableAuthz() }
    def cleanupSpec() { if (ec != null) { ec.artifactExecution.enableAuthz(); ec.destroy() } }

    def "primary-key fields are classified as indexed"() {
        when:
        def idx = new IndexClassifier(ec).indexedFields("org.apache.ofbiz.order.order.OrderHeader")
        then:
        idx.contains("orderId")   // PK is always index-backed
    }
}
