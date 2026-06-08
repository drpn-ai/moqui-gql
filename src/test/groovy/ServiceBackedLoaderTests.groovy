import spock.lang.Specification
import spock.lang.Shared
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.gql.exec.ServiceBackedLoader

/** Keeps the service-backed-field capability covered after itemCount was dropped (#37). Exercises the
 *  batched loader directly against a real Moqui service that maps inputs->outputs, proving it keys,
 *  calls, and maps results back correctly — without needing a production service-backed schema field.
 *
 *  Uses the test-only GqlExampleServices.echo#GqlField (value in == value out, authenticate="false").
 *  The plan's BasicServices.echo does not exist; core BasicServices.echo#Data requires a logged-in user,
 *  so an authenticate="false" echo service is used instead (see GqlExampleServices.xml). */
class ServiceBackedLoaderTests extends Specification {
    @Shared ExecutionContext ec
    def setupSpec() { ec = Moqui.getExecutionContext(); ec.artifactExecution.disableAuthz() }
    def cleanupSpec() { if (ec != null) { ec.artifactExecution.enableAuthz(); ec.destroy() } }

    def "ServiceBackedLoader batches keys through a Moqui service and maps results back"() {
        given:
        def loader = new ServiceBackedLoader(ec, "GqlExampleServices.echo#GqlField",
                ["value"] as List, "value", 1000, "test.echo")
        def key = ["hi"]   // one key tuple [value="hi"]
        when:
        def future = loader.load([key] as Set, null)
        def out = future.toCompletableFuture().get()
        then:
        out instanceof Map
        out.size() == 1
        out.get(key) == "hi"   // service echoed value -> value, navigated back to the key
    }
}
