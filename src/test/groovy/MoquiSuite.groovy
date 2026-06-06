import org.junit.jupiter.api.AfterAll
import org.junit.platform.suite.api.SelectClasses
import org.junit.platform.suite.api.Suite
import org.moqui.Moqui

@Suite
@SelectClasses([ ScaffoldSmokeTests.class, QueryTimeoutTests.class, SchemaArtifactParserTests.class,
        SearchQueryParserTests.class, CostModelTests.class, GqlScalarsTests.class, IndexClassifierTests.class,
        GqlSchemaBuilderTests.class, GqlToolFactoryTests.class, GqlEngineTests.class, PartyConnectionTests.class,
        ExternalIdTests.class, ServiceBackedTests.class, ShipmentRootTests.class, InventoryLevelsTests.class,
        GovernorTests.class, EndpointTests.class, ScopeSeamTests.class, ConnectionWalkTests.class,
        CatalogContractTests.class, OrderDetailEdgesTests.class, ProductFacilityTests.class,
        ThrottleGateTests.class, ThrottleE2ETests.class, CallerProfileTests.class, PreparsedCacheTests.class,
        BillToCustomerTests.class ])
class MoquiSuite {
    @AfterAll
    static void destroyMoqui() { Moqui.destroyActiveExecutionContextFactory() }
}
