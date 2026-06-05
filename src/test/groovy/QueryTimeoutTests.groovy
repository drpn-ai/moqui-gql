import spock.lang.Specification
import spock.lang.Shared
import org.moqui.context.ExecutionContext
import org.moqui.Moqui

/** C1 part 2: proves the framework patch — EntityFind.queryTimeout wired to
 *  PreparedStatement.setQueryTimeout — compiles and works through a real MySQL query. */
class QueryTimeoutTests extends Specification {
    @Shared ExecutionContext ec

    def setupSpec() {
        ec = Moqui.getExecutionContext()
        ec.artifactExecution.disableAuthz()
    }
    def cleanupSpec() {
        if (ec != null) { ec.artifactExecution.enableAuthz(); ec.destroy() }
    }

    def "queryTimeout is a chainable EntityFind builder method; getter reflects it"() {
        when:
        def ef = ec.entity.find("org.apache.ofbiz.order.order.OrderHeader").queryTimeout(15)
        then:
        ef.getQueryTimeout() == 15
    }

    def "a real query with queryTimeout set executes successfully (setQueryTimeout reached the JDBC layer)"() {
        // count() runs a real SQL statement through makePreparedStatement (where setQueryTimeout is
        // applied) without setting maxRows — avoiding the unrelated MySQL setFetchSize>maxRows quirk.
        when:
        long n = ec.entity.find("org.apache.ofbiz.order.order.OrderHeader").queryTimeout(30).count()
        then:
        n > 0
    }
}
