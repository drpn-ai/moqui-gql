import org.junit.jupiter.api.AfterAll
import org.junit.platform.suite.api.SelectClasses
import org.junit.platform.suite.api.Suite
import org.moqui.Moqui

@Suite
@SelectClasses([ ScaffoldSmokeTests.class, QueryTimeoutTests.class, SchemaArtifactParserTests.class,
        SearchQueryParserTests.class, CostModelTests.class, GqlScalarsTests.class, IndexClassifierTests.class,
        GqlSchemaBuilderTests.class, GqlToolFactoryTests.class ])
class MoquiSuite {
    @AfterAll
    static void destroyMoqui() { Moqui.destroyActiveExecutionContextFactory() }
}
