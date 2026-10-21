import org.junit.platform.suite.api.SelectClasses
import org.junit.platform.suite.api.Suite

/**
 * DAR-BE-101. The suite Darpan runs: the engine's pure units (no Moqui boot, no OMS data) and the Darpan additions.
 * The inherited MoquiSuite targets the deleted OMS schema and a MySQL OMS database; it runs only with -PomsSuite.
 * The Darpan GraphQL behaviour that needs Darpan's own entities and services (tenant isolation over two API keys,
 * a refused tenant argument, mutation rejection, the cost ceiling, SDL descriptions, key management) is tested in the
 * darpan component's suite: AuditGraphQlSmokeTests, GraphQlSchemaDescriptionTests, GqlApiKeyManagementTests.
 */
@Suite
@SelectClasses([ApiKeyAuthTests.class, SchemaArtifactParserTests.class, GqlSchemaBuilderTests.class, CostModelTests.class,
        SearchQueryParserTests.class, GqlScalarsTests.class, ThrottleGateTests.class, QueryStatsTests.class,
        DarpanEngineAdditionsTests.class])
class DarpanSuite {
}
