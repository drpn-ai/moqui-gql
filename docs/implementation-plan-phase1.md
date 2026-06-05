# Moqui GraphQL Query Layer (Phase 1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship a curated, read-only GraphQL query layer for Maarg that lets internal callers (and later AI agents) fetch nested OMS data, gated by a cost analyzer and runtime guards so no single query can harm the system.

**Architecture:** A new Moqui component `moqui-graphql` exposes a `/graphql` endpoint. A schema-definition layer (`*.graphql.xml`) declares types/fields/edges over entities; at startup a cached `GraphQLSchema` + cost model are built from it, auto-deriving index coverage and edge cardinality from Moqui entity metadata. Queries are parsed by `graphql-java`, gated by its `MaxQueryDepth`/`MaxQueryComplexity` instrumentation (carrying our cost model), then executed on a single thread in one read-only transaction against a **read-replica clone datasource** (`.useClone(true)`), with list edges batched via `DataLoader`. Runtime guards (a new `EntityFind.queryTimeout`, a row cap, a wall-clock budget) catch what the estimate missed.

**Tech Stack:** Moqui framework (Groovy/Java), `com.graphql-java:graphql-java:25.0` (+ transitive `java-dataloader`), Spock 2.1 for tests. **Version 25.0 is chosen to match `mantle-shopify-connector`, which already ships `graphql-java:25.0`** — both components load in the same deployment runtime (notnaked/gorjana), so the versions must match or the classpath clashes.

**Source spec:** `docs/superpowers/specs/2026-06-03-moqui-graphql-query-layer-design.md` (read it before starting — decisions 1–11 are referenced by number throughout).

---

## Ground rules (from project CLAUDE.md)

- This plan **creates a new component** `runtime/component/moqui-graphql/`. The "never modify `runtime/component/` files" rule protects the *cloned third-party* components; a brand-new component we author is fine. It is its own git repo per the maarg-sd component workflow — `git init` it in Task 0.
- The plan **modifies framework files** (Task 2, the `queryTimeout` patch). Per CLAUDE.md rule 3, **show a diff before saving** each framework edit during execution, and per rule 4 the design is already confirmed.
- **Do not run `./gradlew build`** broadly. Use the narrow per-component test task only: `./gradlew :runtime:component:moqui-graphql:test`. Running it counts as "explicitly asked" because this plan asks for it in the verify steps.
- Commit after every green step.

> **Scope updates — decisions Q1–Q5 resolved (2026-06-03), not yet threaded through every task below.**
> This plan predates the resolutions in `requirements.md` Part 4 / `design.md`. Before execution it
> must absorb: **(Q4) Relay connections** — list fields become `edges{node}` + `pageInfo` +
> `first/after` (replaces the bare `first:`/`maxRows` lists in Tasks 7/9; add cursor encode/decode +
> connection wrapper types); **(Q5) external-id lookup** — add `byExternalId`/`byIdentification`
> root fields + an `identifications` edge (new task); **(Q3) declare-and-control filtering** — the
> schema artifact declares per-field allowed operators, and the filter input + analyzer enforce them
> (extends Tasks 1/3/6/10); **(Q1) DB-backed only** — no search-index path (already true here);
> **(Q2) analytics deferred** — no aggregation tasks. These are recorded now; the full task-level
> rewrite happens when we move from requirements/examples into implementation.

---

## File Structure

```
framework/ (framework patch — Task 2)
  src/main/java/org/moqui/entity/EntityFind.java                 ← + queryTimeout(int) declaration
  src/main/groovy/org/moqui/impl/entity/EntityFindBase.groovy    ← + queryTimeout field + setter
  src/main/groovy/org/moqui/impl/entity/EntityFindBuilder.java   ← + ps.setQueryTimeout() in makePreparedStatement()

runtime/component/moqui-graphql/
  component.xml                                  ← descriptor (Task 0)
  build.gradle                                   ← graphql-java dep + test harness (Task 0)
  MoquiConf.xml                                  ← default-properties (limits) + ToolFactory reg (Task 0, 8)
  graphql/
    OmsSchema.graphql.xml                        ← schema artifact: types/fields/edges (Task 1)
  entity/
    GraphQLEntities.xml                          ← GraphqlQueryLog entity for observability (Task 12)
  data/
    GraphQLDemoData.xml                          ← test fixtures (Task 9)
  service/
    graphql/QueryServices.xml                    ← execute#Query, get#Schema (Task 11)
    graphql.rest.xml                             ← POST /graphql mount (Task 11)
  src/main/groovy/org/moqui/graphql/
    SchemaArtifact.groovy                        ← parsed model of *.graphql.xml (Task 3)
    SchemaArtifactParser.groovy                  ← MNode → SchemaArtifact (Task 3)
    IndexClassifier.groovy                       ← EntityDefinition → indexed-field set (Task 5)
    CostModel.groovy                             ← per-field/edge cost + FieldComplexityCalculator (Task 6)
    GraphQLSchemaBuilder.groovy                  ← SchemaArtifact → graphql-java GraphQLSchema (Task 7)
    EntityBatchLoader.groovy                     ← DataLoader BatchLoader: WHERE pk IN (...) (Task 8)
    EntityDataFetcher.groovy                     ← DataFetcher mapping edge → EntityFind/DataLoader (Task 8)
    ScopeFilter.groovy                           ← phase-1 no-op authz seam (decision 11) (Task 8)
    QueryGovernor.groovy                         ← builds instrumentation + runtime guards (Task 10)
    GraphQLEngine.groovy                         ← orchestrates parse→gate→execute→assemble (Task 9, 10)
    GraphQLToolFactory.groovy                    ← builds & caches schema/engine at startup (Task 7)
  src/test/groovy/
    MoquiSuite.groovy                            ← JUnit Platform suite (Task 0)
    QueryTimeoutTests.groovy                     ← framework patch (Task 2)
    SchemaArtifactParserTests.groovy             ← (Task 3)
    IndexClassifierTests.groovy                  ← (Task 5)
    CostModelTests.groovy                        ← (Task 6)
    GraphQLSchemaBuilderTests.groovy             ← (Task 7)
    EntityDataFetcherTests.groovy                ← (Task 8)
    GraphQLEngineTests.groovy                    ← (Task 9, 10)
    GraphQLRestApiTests.groovy                   ← (Task 11)
    AdversarialQueryTests.groovy                 ← the pathological-query catalog (Task 13)
```

**Boundaries:** parsing (`SchemaArtifactParser`) is separate from schema construction (`GraphQLSchemaBuilder`) which is separate from cost (`CostModel`) and execution (`EntityDataFetcher`/`EntityBatchLoader`). The `GraphQLEngine` wires them; the `ToolFactory` owns lifecycle/caching. Each is testable alone.

---

## Task 0: Component scaffold

**Files:**
- Create: `runtime/component/moqui-graphql/component.xml`
- Create: `runtime/component/moqui-graphql/build.gradle`
- Create: `runtime/component/moqui-graphql/MoquiConf.xml`
- Create: `runtime/component/moqui-graphql/src/test/groovy/MoquiSuite.groovy`
- Create: `runtime/component/moqui-graphql/src/test/groovy/ScaffoldSmokeTests.groovy`

- [ ] **Step 1: Create the component descriptor**

`runtime/component/moqui-graphql/component.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<component name="moqui-graphql" version="0.1.0"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:noNamespaceSchemaLocation="http://moqui.org/xsd/component-3.xsd"/>
```

- [ ] **Step 2: Create `build.gradle`** (mirrors `runtime/component/moqui-ai/build.gradle`)

`runtime/component/moqui-graphql/build.gradle`:

```gradle
apply plugin: 'groovy'

sourceCompatibility = '11'
targetCompatibility = '11'

def runtimeDir = file("${projectDir}/../..")

repositories {
    flatDir name: 'frameworkLib', dirs: file("${projectDir}/../../../framework/lib").absolutePath
    mavenCentral()
}

dependencies {
    implementation project(':framework')
    implementation 'com.graphql-java:graphql-java:25.0'   // MATCH mantle-shopify-connector; pulls java-dataloader transitively

    testImplementation 'org.junit.platform:junit-platform-launcher:1.12.1'
    testImplementation 'org.junit.platform:junit-platform-suite:1.12.1'
    testImplementation 'org.junit.jupiter:junit-jupiter-api:5.12.1'
    testImplementation platform("org.spockframework:spock-bom:2.1-groovy-3.0")
    testImplementation 'org.spockframework:spock-core:2.1-groovy-3.0'
    testImplementation 'org.spockframework:spock-junit4:2.1-groovy-3.0'
}

test {
    useJUnitPlatform()
    testLogging { events "passed", "skipped", "failed"; showStandardStreams = true; showExceptions = true }
    maxParallelForks 1
    include '**/*MoquiSuite.class'
    systemProperty 'moqui.runtime', runtimeDir.absolutePath
    systemProperty 'moqui.conf', 'conf/MoquiDevConf.xml'
    systemProperty 'moqui.init.static', 'true'
    classpath += files(sourceSets.main.output.classesDirs)
    classpath += files(projectDir.absolutePath)
    classpath = classpath.filter { it.exists() }
}
```

> **graphql-java 25.0 verification (do this in Step 5).** The version is pinned to match `mantle-shopify-connector` (prior art — it ships a client-side query-builder DSL on 25.0). Two things to confirm against 25.0, since my reference code was written against 21.x: (1) `graphql.analysis.MaxQueryComplexityInstrumentation` + `FieldComplexityCalculator` still exist with the `calculate(FieldComplexityEnvironment, int)` signature (Task 6/10) — adjust if the API moved; (2) the JDK floor — the component sets `sourceCompatibility 11`; if 25.0 requires Java 17, raise it to match the deployment JVM. The connector building fine on 25.0 is evidence it works in this runtime.

- [ ] **Step 3: Create `MoquiConf.xml` with the governance defaults** (decision 4 thresholds — these are the calibratable knobs)

`runtime/component/moqui-graphql/MoquiConf.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<moqui-conf xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:noNamespaceSchemaLocation="http://moqui.org/xsd/moqui-conf-3.xsd">
    <!-- Static structural limits (governance layer 1) -->
    <default-property name="graphql.maxDepth" value="6"/>
    <default-property name="graphql.maxFields" value="200"/>
    <default-property name="graphql.maxListEdges" value="10"/>
    <default-property name="graphql.defaultFirstCap" value="100"/>
    <!-- Static cost budget (governance layer 2) -->
    <default-property name="graphql.maxCost" value="1000"/>
    <default-property name="graphql.unindexedFilterPenalty" value="50"/>
    <!-- Runtime guards (governance layer 4) -->
    <default-property name="graphql.queryTimeoutSeconds" value="20"/>
    <default-property name="graphql.maxRowsPerLevel" value="5000"/>
    <default-property name="graphql.wallClockBudgetMs" value="30000"/>
    <!-- Read-replica routing (decision 9) -->
    <default-property name="graphql.useClone" value="true"/>
</moqui-conf>
```

- [ ] **Step 4: Create the test suite + a smoke test**

`runtime/component/moqui-graphql/src/test/groovy/MoquiSuite.groovy`:

```groovy
import org.junit.jupiter.api.AfterAll
import org.junit.platform.suite.api.SelectClasses
import org.junit.platform.suite.api.Suite
import org.moqui.Moqui

@Suite
@SelectClasses([ ScaffoldSmokeTests.class ])
class MoquiSuite {
    @AfterAll
    static void destroyMoqui() { Moqui.destroyActiveExecutionContextFactory() }
}
```

`runtime/component/moqui-graphql/src/test/groovy/ScaffoldSmokeTests.groovy`:

```groovy
import spock.lang.Specification
import spock.lang.Shared
import org.moqui.context.ExecutionContext
import org.moqui.Moqui

class ScaffoldSmokeTests extends Specification {
    @Shared ExecutionContext ec

    def setupSpec() { ec = Moqui.getExecutionContext() }
    def cleanupSpec() { ec.destroy() }

    def "component is loaded and graphql-java is on the classpath"() {
        when:
        def loaded = ec.factory.getComponentBaseLocations().containsKey("moqui-graphql")
        def schemaClass = Class.forName("graphql.schema.GraphQLSchema")
        then:
        loaded
        schemaClass != null
    }
}
```

- [ ] **Step 5: Run the smoke test to verify the component loads and graphql-java resolves**

Run: `./gradlew :runtime:component:moqui-graphql:test`
Expected: PASS (both assertions). If `getComponentBaseLocations` is unavailable in this framework version, fall back to asserting `ec != null` and the `Class.forName` only.

- [ ] **Step 6: Initialize the component as its own git repo and commit**

```bash
cd runtime/component/moqui-graphql
git init
printf 'build/\n.gradle/\n*.class\n' > .gitignore
git add .
git commit -m "feat: scaffold moqui-graphql component with graphql-java and test harness"
cd -
```

---

## Task 1: Define the schema-artifact format (fixture only)

This task locks the `*.graphql.xml` shape so later tasks have a concrete contract. No code — a fixture + a documentation comment.

**Files:**
- Create: `runtime/component/moqui-graphql/graphql/OmsSchema.graphql.xml`

- [ ] **Step 1: Write the schema artifact for two real OMS types**

`runtime/component/moqui-graphql/graphql/OmsSchema.graphql.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!--
  GraphQL schema artifact. Declares the curated graph (decision 2/5/12).
  THREE FIELD KINDS (decision 12):
  - entity-backed:  <field entity-field="..."> reads a column. Statically cost-analyzable.
  - service-backed: <field resolver-service="verb#Noun" resolver-in="orderId,orderItemSeqId">
                    delegates to a Moqui service for COMPUTED values (e.g. itemFulfillmentStatus).
                    Opaque to static cost => high fixed cost + runtime guards (Task 6/14).
  - view-entity:    a <gql-type entity-name="..."> may name a VIEW-ENTITY; EntityFind runs on it
                    transparently, giving free joins. No special syntax — just a view-entity name.
  Element rules:
  - <gql-type> maps a GraphQL type to a Moqui entity OR view-entity.
  - <field> 'filterable'/'sortable' opt entity fields into WHERE/ORDER BY.
  - <edge> maps to a Moqui relationship. list="true" => connection, requires first:.
  - 'cost' overrides the auto-derived per-field/edge cost; omit to auto-derive.
  Index coverage and edge cardinality are auto-derived from entity metadata (Task 5/6).
-->
<gql-schema xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
    <gql-type name="Order" entity-name="mantle.order.OrderHeader">
        <field name="orderId" entity-field="orderId"/>
        <field name="orderName" entity-field="orderName"/>
        <field name="statusId" entity-field="statusId" filterable="true"/>
        <field name="placedDate" entity-field="placedDate" filterable="true" sortable="true"/>
        <edge name="items" entity-relationship="items" target-type="OrderItem" list="true"/>
        <edge name="customer" entity-relationship="customer" target-type="Party" list="false"/>
    </gql-type>

    <gql-type name="OrderItem" entity-name="mantle.order.OrderItem">
        <field name="orderId" entity-field="orderId"/>
        <field name="orderItemSeqId" entity-field="orderItemSeqId"/>
        <field name="productId" entity-field="productId" filterable="true"/>
        <field name="quantity" entity-field="quantity"/>
        <!-- service-backed COMPUTED field (decision 12): reuses existing fulfillment-status logic.
             resolver-in lists the parent fields passed as service inputs. -->
        <field name="fulfillmentStatus" resolver-service="org.moqui.graphql.DemoResolvers.get#ItemFulfillmentStatus"
               resolver-in="orderId,orderItemSeqId"/>
    </gql-type>

    <gql-type name="Party" entity-name="mantle.party.Party">
        <field name="partyId" entity-field="partyId"/>
        <field name="partyTypeEnumId" entity-field="partyTypeEnumId"/>
    </gql-type>

    <!-- Root query fields: each is an entry point into the graph. -->
    <gql-query name="order" target-type="Order" entity-name="mantle.order.OrderHeader" by-pk="true"/>
    <gql-query name="orders" target-type="Order" entity-name="mantle.order.OrderHeader" list="true"/>
</gql-schema>
```

> **Per-deployment entity packages.** This fixture uses vanilla `mantle.*` names. In HotWax/notnaked deployments the order model is `org.apache.ofbiz.order.order.OrderHeader/OrderItem` and the edges are exactly those declared in `notnaked/runtime/component/oms/entity/OrderExtendedEntities.xml` (`shipGroups`, `paymentPreferences`, `adjustments`, `statuses`, `items`, `returns`, `contactMechs`, …). The schema artifact is authored to match the entities present in the install — the parser assumes no specific entity exists. Real computed fields to wrap as service-backed: `itemFulfillmentStatus` (`ofbiz-oms-usl/.../OrderServices.xml:2861-2993`), `customerName`.

- [ ] **Step 2: Commit the fixture**

```bash
git -C runtime/component/moqui-graphql add graphql/OmsSchema.graphql.xml
git -C runtime/component/moqui-graphql commit -m "feat: add OMS GraphQL schema artifact fixture"
```

---

## Task 2: Framework patch — `EntityFind.queryTimeout`

Adds the runtime-guard primitive that does not exist today (spec §"Where it lives"). **Show a diff before saving each framework edit.**

**Files:**
- Modify: `framework/src/main/java/org/moqui/entity/EntityFind.java` (after the `maxRows` declaration, ~line 251)
- Modify: `framework/src/main/groovy/org/moqui/impl/entity/EntityFindBase.groovy` (field after ~line 83; setter after ~line 638)
- Modify: `framework/src/main/groovy/org/moqui/impl/entity/EntityFindBuilder.java` (`makePreparedStatement()`, after the `setMaxRows` call ~line 780)
- Test: `runtime/component/moqui-graphql/src/test/groovy/QueryTimeoutTests.groovy`

- [ ] **Step 1: Write the failing test**

`runtime/component/moqui-graphql/src/test/groovy/QueryTimeoutTests.groovy`:

```groovy
import spock.lang.Specification
import spock.lang.Shared
import org.moqui.context.ExecutionContext
import org.moqui.Moqui

class QueryTimeoutTests extends Specification {
    @Shared ExecutionContext ec
    def setupSpec() { ec = Moqui.getExecutionContext(); ec.artifactExecution.disableAuthz() }
    def cleanupSpec() { ec.artifactExecution.enableAuthz(); ec.destroy() }

    def "queryTimeout is a chainable EntityFind builder method and a normal query still succeeds"() {
        when: "a generous timeout is set on a trivial find"
        def list = ec.entity.find("moqui.security.UserAccount")
                     .queryTimeout(30).setMaxRows(1).list()
        then: "the builder accepted queryTimeout and the query ran"
        list != null
    }
}
```

Add `QueryTimeoutTests.class` to `MoquiSuite.groovy`'s `@SelectClasses`.

- [ ] **Step 2: Run it to verify it fails (compile error: no such method)**

Run: `./gradlew :runtime:component:moqui-graphql:test --tests QueryTimeoutTests`
Expected: FAIL — `groovy.lang.MissingMethodException` / compile error: `queryTimeout` not found on `EntityFind`.

- [ ] **Step 3: Declare the method on the `EntityFind` interface**

In `framework/src/main/java/org/moqui/entity/EntityFind.java`, immediately after the `maxRows(int maxRows)` declaration (~line 251), add:

```java
    /** The JDBC query timeout in seconds for this find. Passed to Statement.setQueryTimeout().
     * 0 or null means no timeout (driver default). Used to bound worst-case query runtime. */
    EntityFind queryTimeout(int queryTimeout);
    int getQueryTimeout();
```

- [ ] **Step 4: Add the field + setter + getter to `EntityFindBase.groovy`**

In `framework/src/main/groovy/org/moqui/impl/entity/EntityFindBase.groovy`, after the `maxRows` field (~line 83):

```groovy
    protected Integer queryTimeout = (Integer) null
```

After the `maxRows(Integer)` setter (~line 638):

```groovy
    @Override EntityFind queryTimeout(int queryTimeout) { this.queryTimeout = queryTimeout; return this }
    @Override int getQueryTimeout() { return queryTimeout != null ? queryTimeout : 0 }
```

- [ ] **Step 5: Apply it to the JDBC statement in `EntityFindBuilder.java`**

In `framework/src/main/groovy/org/moqui/impl/entity/EntityFindBuilder.java`, inside `makePreparedStatement()`, right after the `setMaxRows` call (~line 780, before the fetchSize block):

```java
            int queryTimeout = entityFindBase.getQueryTimeout();
            if (queryTimeout > 0) ps.setQueryTimeout(queryTimeout);
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `./gradlew :runtime:component:moqui-graphql:test --tests QueryTimeoutTests`
Expected: PASS.

- [ ] **Step 7: Commit (framework + test)**

```bash
git add framework/src/main/java/org/moqui/entity/EntityFind.java \
        framework/src/main/groovy/org/moqui/impl/entity/EntityFindBase.groovy \
        framework/src/main/groovy/org/moqui/impl/entity/EntityFindBuilder.java
git commit -m "feat(entity): add EntityFind.queryTimeout wired to PreparedStatement.setQueryTimeout"
git -C runtime/component/moqui-graphql add src/test/groovy
git -C runtime/component/moqui-graphql commit -m "test: cover EntityFind.queryTimeout"
```

> **Read-replica routing (decision 9) needs no code** — it is configuration. Document in the spec/runbook: add a `transactional#clone1` `<datasource>` under `<entity-facade>` in the deployment conf (pattern already in `framework/src/main/resources/MoquiDefaultConf.xml` ~lines 485–493) pointing at the replica. The executor calls `.useClone(true)` (Task 8). In dev/test with no replica, Moqui's `getDatasourceCloneName()` falls back to the base group automatically, so tests pass unmodified.

---

## Task 3: Schema-artifact parser

Parse `*.graphql.xml` (MNode) into an immutable `SchemaArtifact` model. Pure transformation — easy TDD.

**Files:**
- Create: `runtime/component/moqui-graphql/src/main/groovy/org/moqui/graphql/SchemaArtifact.groovy`
- Create: `runtime/component/moqui-graphql/src/main/groovy/org/moqui/graphql/SchemaArtifactParser.groovy`
- Test: `runtime/component/moqui-graphql/src/test/groovy/SchemaArtifactParserTests.groovy`

- [ ] **Step 1: Write the failing test**

`runtime/component/moqui-graphql/src/test/groovy/SchemaArtifactParserTests.groovy`:

```groovy
import spock.lang.Specification
import org.moqui.util.MNode
import org.moqui.graphql.SchemaArtifactParser
import org.moqui.graphql.SchemaArtifact

class SchemaArtifactParserTests extends Specification {
    def "parses types, fields, edges and root queries from a schema artifact MNode"() {
        given:
        MNode node = MNode.parseText("test.graphql.xml", '''
            <gql-schema>
              <gql-type name="Order" entity-name="mantle.order.OrderHeader">
                <field name="orderId" entity-field="orderId"/>
                <field name="statusId" entity-field="statusId" filterable="true"/>
                <field name="fulfillmentStatus" resolver-service="X.get#FStatus" resolver-in="orderId, orderItemSeqId"/>
                <edge name="items" entity-relationship="items" target-type="OrderItem" list="true"/>
              </gql-type>
              <gql-query name="orders" target-type="Order" entity-name="mantle.order.OrderHeader" list="true"/>
            </gql-schema>''')

        when:
        SchemaArtifact art = new SchemaArtifactParser().parse([node])

        then:
        art.types.size() == 1
        def t = art.types["Order"]
        t.entityName == "mantle.order.OrderHeader"
        t.fields["statusId"].filterable
        !t.fields["orderId"].filterable
        !t.fields["orderId"].isServiceBacked()
        // decision 12: service-backed field parsed with resolver service + input mapping
        t.fields["fulfillmentStatus"].isServiceBacked()
        t.fields["fulfillmentStatus"].resolverService == "X.get#FStatus"
        t.fields["fulfillmentStatus"].resolverIn == ["orderId", "orderItemSeqId"]
        t.edges["items"].list
        t.edges["items"].targetType == "OrderItem"
        art.rootQueries["orders"].list
        art.rootQueries["orders"].targetType == "Order"
    }
}
```

Add `SchemaArtifactParserTests.class` to `MoquiSuite`.

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :runtime:component:moqui-graphql:test --tests SchemaArtifactParserTests`
Expected: FAIL — classes `SchemaArtifact` / `SchemaArtifactParser` do not exist.

- [ ] **Step 3: Implement the model**

`runtime/component/moqui-graphql/src/main/groovy/org/moqui/graphql/SchemaArtifact.groovy`:

```groovy
package org.moqui.graphql
import groovy.transform.CompileStatic

@CompileStatic class GqlField {
    String name, entityField
    boolean filterable = false, sortable = false
    Integer costOverride = null
    // decision 12: service-backed field. When resolverService != null this field is
    // computed by calling that Moqui service; entityField is null. resolverIn = parent
    // field names passed as service inputs. isServiceBacked() => true.
    String resolverService = null
    List<String> resolverIn = []
    boolean isServiceBacked() { return resolverService != null && !resolverService.isEmpty() }
}
@CompileStatic class GqlEdge {
    String name, entityRelationship, targetType
    boolean list = false
    Integer costOverride = null
}
@CompileStatic class GqlType {
    String name, entityName
    Map<String, GqlField> fields = [:]
    Map<String, GqlEdge> edges = [:]
}
@CompileStatic class GqlRootQuery {
    String name, targetType, entityName
    boolean byPk = false, list = false
}
@CompileStatic class SchemaArtifact {
    Map<String, GqlType> types = [:]
    Map<String, GqlRootQuery> rootQueries = [:]
}
```

`runtime/component/moqui-graphql/src/main/groovy/org/moqui/graphql/SchemaArtifactParser.groovy`:

```groovy
package org.moqui.graphql
import groovy.transform.CompileStatic
import org.moqui.util.MNode

@CompileStatic class SchemaArtifactParser {
    SchemaArtifact parse(List<MNode> schemaNodes) {
        SchemaArtifact art = new SchemaArtifact()
        for (MNode root in schemaNodes) {
            for (MNode tn in root.children("gql-type")) {
                GqlType t = new GqlType(name: tn.attribute("name"), entityName: tn.attribute("entity-name"))
                for (MNode fn in tn.children("field")) {
                    String rin = fn.attribute("resolver-in")
                    t.fields[fn.attribute("name")] = new GqlField(
                        name: fn.attribute("name"), entityField: fn.attribute("entity-field"),
                        filterable: "true".equals(fn.attribute("filterable")),
                        sortable: "true".equals(fn.attribute("sortable")),
                        costOverride: asInt(fn.attribute("cost")),
                        resolverService: fn.attribute("resolver-service"),
                        resolverIn: (rin ? rin.split(",").collect { it.trim() } : []) as List<String>)
                }
                for (MNode en in tn.children("edge")) {
                    t.edges[en.attribute("name")] = new GqlEdge(
                        name: en.attribute("name"), entityRelationship: en.attribute("entity-relationship"),
                        targetType: en.attribute("target-type"), list: "true".equals(en.attribute("list")),
                        costOverride: asInt(en.attribute("cost")))
                }
                art.types[t.name] = t
            }
            for (MNode qn in root.children("gql-query")) {
                art.rootQueries[qn.attribute("name")] = new GqlRootQuery(
                    name: qn.attribute("name"), targetType: qn.attribute("target-type"),
                    entityName: qn.attribute("entity-name"), byPk: "true".equals(qn.attribute("by-pk")),
                    list: "true".equals(qn.attribute("list")))
            }
        }
        return art
    }
    private static Integer asInt(String s) { return (s != null && !s.isEmpty()) ? Integer.valueOf(s) : (Integer) null }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :runtime:component:moqui-graphql:test --tests SchemaArtifactParserTests`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git -C runtime/component/moqui-graphql add src/main/groovy/org/moqui/graphql/SchemaArtifact.groovy \
    src/main/groovy/org/moqui/graphql/SchemaArtifactParser.groovy src/test/groovy
git -C runtime/component/moqui-graphql commit -m "feat: parse *.graphql.xml schema artifacts into SchemaArtifact model"
```

---

## Task 5: Index classifier (auto-derive indexed fields)

Reads Moqui's `EntityDefinition` to classify which fields are index-backed (PK or declared `<index>`). Feeds the cost model's unindexed-filter penalty (decision 5, governance layer 3). Numbered Task 5 to match the file-structure list; Task 4 (the artifact fixture) was folded into Task 1.

**Files:**
- Create: `runtime/component/moqui-graphql/src/main/groovy/org/moqui/graphql/IndexClassifier.groovy`
- Test: `runtime/component/moqui-graphql/src/test/groovy/IndexClassifierTests.groovy`

- [ ] **Step 1: Write the failing test**

`runtime/component/moqui-graphql/src/test/groovy/IndexClassifierTests.groovy`:

```groovy
import spock.lang.Specification
import spock.lang.Shared
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.graphql.IndexClassifier

class IndexClassifierTests extends Specification {
    @Shared ExecutionContext ec
    def setupSpec() { ec = Moqui.getExecutionContext() }
    def cleanupSpec() { ec.destroy() }

    def "primary-key fields are classified as indexed"() {
        given:
        def classifier = new IndexClassifier(ec)
        when:
        def indexed = classifier.indexedFields("moqui.security.UserAccount")
        then: "the PK (userId) is indexed; a non-indexed descriptive field is not"
        indexed.contains("userId")
        !indexed.contains("userFullName")
    }
}
```

Add to `MoquiSuite`.

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :runtime:component:moqui-graphql:test --tests IndexClassifierTests`
Expected: FAIL — `IndexClassifier` does not exist.

- [ ] **Step 3: Implement**

`runtime/component/moqui-graphql/src/main/groovy/org/moqui/graphql/IndexClassifier.groovy`:

```groovy
package org.moqui.graphql
import groovy.transform.CompileStatic
import org.moqui.context.ExecutionContext
import org.moqui.impl.entity.EntityDefinition
import org.moqui.impl.entity.EntityFacadeImpl
import org.moqui.util.MNode

/** Classifies which fields of an entity are index-backed (PK fields + fields named in any <index>).
 *  Filtering/sorting on a non-indexed field is penalized (or rejected) by the cost model. */
@CompileStatic class IndexClassifier {
    private final ExecutionContext ec
    private final Map<String, Set<String>> cache = [:]   // immutable per-process after first build (decision 6)
    IndexClassifier(ExecutionContext ec) { this.ec = ec }

    Set<String> indexedFields(String entityName) {
        Set<String> cached = cache.get(entityName)
        if (cached != null) return cached
        EntityFacadeImpl efi = (EntityFacadeImpl) ec.entity
        EntityDefinition ed = efi.getEntityDefinition(entityName)
        Set<String> result = new HashSet<>()
        result.addAll(ed.getPkFieldNames())
        // Declared indexes live as <index> child nodes on the entity node.
        MNode entityNode = ed.getEntityNode()
        for (MNode idx in entityNode.children("index")) {
            for (MNode idxField in idx.children("index-field")) {
                String fn = idxField.attribute("name"); if (fn) result.add(fn)
            }
        }
        cache.put(entityName, result)
        return result
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :runtime:component:moqui-graphql:test --tests IndexClassifierTests`
Expected: PASS. (If `getPkFieldNames`/`getEntityNode` signatures differ in this framework build, adjust to the actual `EntityDefinition` API — verify against `framework/src/main/groovy/org/moqui/impl/entity/EntityDefinition.groovy`.)

- [ ] **Step 5: Commit**

```bash
git -C runtime/component/moqui-graphql add src/main/groovy/org/moqui/graphql/IndexClassifier.groovy src/test/groovy
git -C runtime/component/moqui-graphql commit -m "feat: classify index-backed entity fields for cost analysis"
```

---

## Task 6: Cost model + `FieldComplexityCalculator`

Turns the schema artifact + index classification into per-field/edge costs, and exposes a `graphql.analysis.FieldComplexityCalculator` so `MaxQueryComplexityInstrumentation` enforces our budget (decision 8).

**Files:**
- Create: `runtime/component/moqui-graphql/src/main/groovy/org/moqui/graphql/CostModel.groovy`
- Test: `runtime/component/moqui-graphql/src/test/groovy/CostModelTests.groovy`

- [ ] **Step 1: Write the failing test**

`runtime/component/moqui-graphql/src/test/groovy/CostModelTests.groovy`:

```groovy
import spock.lang.Specification
import org.moqui.graphql.CostModel
import graphql.analysis.FieldComplexityEnvironment

class CostModelTests extends Specification {
    def "list edges multiply child cost by requested 'first'; scalars cost 1"() {
        given:
        def cm = new CostModel(unindexedPenalty: 50)
        // scalar field: child complexity 0, no first arg
        def scalarEnv = Mock(FieldComplexityEnvironment) { getArguments() >> [:] }

        expect: "a scalar leaf costs 1"
        cm.fieldComplexity(scalarEnv, 0) == 1

        when: "a list edge requests first:100 wrapping a child subtree of cost 3"
        def listEnv = Mock(FieldComplexityEnvironment) { getArguments() >> [first: 100] }
        def cost = cm.listComplexity(listEnv, 3)
        then: "cost ~= first * (1 + childCost) = 100 * 4 = 400"
        cost == 400
    }

    def "unindexed filter adds the penalty"() {
        given: def cm = new CostModel(unindexedPenalty: 50)
        expect:
        cm.filterCost(true) == 0
        cm.filterCost(false) == 50
    }
}
```

Add to `MoquiSuite`.

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :runtime:component:moqui-graphql:test --tests CostModelTests`
Expected: FAIL — `CostModel` does not exist.

- [ ] **Step 3: Implement**

`runtime/component/moqui-graphql/src/main/groovy/org/moqui/graphql/CostModel.groovy`:

```groovy
package org.moqui.graphql
import groovy.transform.CompileStatic
import graphql.analysis.FieldComplexityCalculator
import graphql.analysis.FieldComplexityEnvironment

/** Per-field cost. Plugged into MaxQueryComplexityInstrumentation (decision 8).
 *  Scalar leaf = 1. List edge = first * (1 + childComplexity) -> fan-out multiplies down the tree.
 *  To-one edge = 1 + childComplexity. Unindexed filter/sort adds a flat penalty. */
@CompileStatic class CostModel implements FieldComplexityCalculator {
    int unindexedPenalty = 50
    int defaultFirstCap = 100
    /** edge field name -> true if it is a list edge (set by GraphQLSchemaBuilder at build time). */
    Map<String, Boolean> listEdgeByField = [:]
    /** "Type.field" -> true if a declared filterable field is index-backed. */
    Map<String, Boolean> filterIndexed = [:]

    int filterCost(boolean indexed) { return indexed ? 0 : unindexedPenalty }

    int listComplexity(FieldComplexityEnvironment env, int childComplexity) {
        int first = firstArg(env)
        return first * (1 + childComplexity)
    }

    @Override
    int calculate(FieldComplexityEnvironment env, int childComplexity) {
        String fieldName = env.getField().getName()
        Boolean isList = listEdgeByField.get(fieldName)
        if (Boolean.TRUE.equals(isList)) return listComplexity(env, childComplexity)
        // to-one edge or scalar
        return fieldComplexity(env, childComplexity)
    }

    int fieldComplexity(FieldComplexityEnvironment env, int childComplexity) {
        return 1 + childComplexity
    }

    private int firstArg(FieldComplexityEnvironment env) {
        Object f = env.getArguments()?.get("first")
        if (f instanceof Number) return ((Number) f).intValue()
        return defaultFirstCap
    }
}
```

> The `calculate(...)` override is the exact hook `MaxQueryComplexityInstrumentation` calls. `fieldComplexity`/`listComplexity`/`filterCost` are broken out so they're unit-testable without standing up the full instrumentation.

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :runtime:component:moqui-graphql:test --tests CostModelTests`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git -C runtime/component/moqui-graphql add src/main/groovy/org/moqui/graphql/CostModel.groovy src/test/groovy
git -C runtime/component/moqui-graphql commit -m "feat: cost model with fan-out multiplication and FieldComplexityCalculator"
```

---

## Task 7: Schema builder + caching tool factory

Builds the `graphql.schema.GraphQLSchema` from a `SchemaArtifact` and wires the `CostModel` (filling `listEdgeByField`/`filterIndexed` from the artifact + `IndexClassifier`). The `GraphQLToolFactory` builds this **once at startup and caches it** (decision 6 / perf).

**Files:**
- Create: `runtime/component/moqui-graphql/src/main/groovy/org/moqui/graphql/GraphQLSchemaBuilder.groovy`
- Create: `runtime/component/moqui-graphql/src/main/groovy/org/moqui/graphql/GraphQLToolFactory.groovy`
- Modify: `runtime/component/moqui-graphql/MoquiConf.xml` (register the tool factory)
- Test: `runtime/component/moqui-graphql/src/test/groovy/GraphQLSchemaBuilderTests.groovy`

- [ ] **Step 1: Write the failing test (schema introspects + has expected types/fields)**

`runtime/component/moqui-graphql/src/test/groovy/GraphQLSchemaBuilderTests.groovy`:

```groovy
import spock.lang.Specification
import spock.lang.Shared
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.graphql.SchemaArtifactParser
import org.moqui.graphql.GraphQLSchemaBuilder
import org.moqui.util.MNode
import graphql.schema.GraphQLSchema

class GraphQLSchemaBuilderTests extends Specification {
    @Shared ExecutionContext ec
    def setupSpec() { ec = Moqui.getExecutionContext() }
    def cleanupSpec() { ec.destroy() }

    def "builds a GraphQLSchema with declared types, fields and a list edge requiring first"() {
        given:
        MNode node = MNode.parseText("t.graphql.xml", '''
            <gql-schema>
              <gql-type name="Order" entity-name="mantle.order.OrderHeader">
                <field name="orderId" entity-field="orderId"/>
                <edge name="items" entity-relationship="items" target-type="OrderItem" list="true"/>
              </gql-type>
              <gql-type name="OrderItem" entity-name="mantle.order.OrderItem">
                <field name="productId" entity-field="productId"/>
              </gql-type>
              <gql-query name="orders" target-type="Order" entity-name="mantle.order.OrderHeader" list="true"/>
            </gql-schema>''')
        def art = new SchemaArtifactParser().parse([node])

        when:
        GraphQLSchema schema = new GraphQLSchemaBuilder(ec).build(art).schema

        then:
        schema.getObjectType("Order") != null
        schema.getObjectType("Order").getFieldDefinition("items") != null
        // list edge exposes a 'first' argument
        schema.getObjectType("Order").getFieldDefinition("items").getArgument("first") != null
        schema.getQueryType().getFieldDefinition("orders") != null
    }
}
```

Add to `MoquiSuite`.

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :runtime:component:moqui-graphql:test --tests GraphQLSchemaBuilderTests`
Expected: FAIL — `GraphQLSchemaBuilder` does not exist.

- [ ] **Step 3: Implement the builder** (returns schema + the populated cost model together)

`runtime/component/moqui-graphql/src/main/groovy/org/moqui/graphql/GraphQLSchemaBuilder.groovy`:

```groovy
package org.moqui.graphql
import groovy.transform.CompileStatic
import org.moqui.context.ExecutionContext
import graphql.Scalars
import graphql.schema.*

/** Result of a schema build: the graphql-java schema plus the cost model populated from the artifact. */
@CompileStatic class BuiltSchema {
    GraphQLSchema schema
    CostModel costModel
    SchemaArtifact artifact
}

@CompileStatic class GraphQLSchemaBuilder {
    private final ExecutionContext ec
    private final IndexClassifier indexClassifier
    GraphQLSchemaBuilder(ExecutionContext ec) { this.ec = ec; this.indexClassifier = new IndexClassifier(ec) }

    BuiltSchema build(SchemaArtifact art) {
        CostModel cm = new CostModel()
        Map<String, GraphQLObjectType.Builder> typeBuilders = [:]

        // First pass: object types with scalar fields.
        for (GqlType t in art.types.values()) {
            GraphQLObjectType.Builder tb = GraphQLObjectType.newObject().name(t.name)
            for (GqlField f in t.fields.values()) {
                tb.field(GraphQLFieldDefinition.newFieldDefinition()
                    .name(f.name).type(Scalars.GraphQLString))
                if (f.filterable) {
                    boolean indexed = indexClassifier.indexedFields(t.entityName).contains(f.entityField)
                    cm.filterIndexed.put(t.name + "." + f.name, indexed)
                }
            }
            typeBuilders.put(t.name, tb)
        }
        // Second pass: edges (need target types to exist as references).
        for (GqlType t in art.types.values()) {
            GraphQLObjectType.Builder tb = typeBuilders.get(t.name)
            for (GqlEdge e in t.edges.values()) {
                GraphQLOutputType outType = GraphQLTypeReference.typeRef(e.targetType)
                GraphQLFieldDefinition.Builder fb = GraphQLFieldDefinition.newFieldDefinition().name(e.name)
                if (e.list) {
                    fb.type(GraphQLList.list(outType))
                    fb.argument(GraphQLArgument.newArgument().name("first").type(Scalars.GraphQLInt).build())
                    cm.listEdgeByField.put(e.name, Boolean.TRUE)
                } else {
                    fb.type(outType)
                    cm.listEdgeByField.put(e.name, Boolean.FALSE)
                }
                tb.field(fb)
            }
        }
        // Root query type.
        GraphQLObjectType.Builder queryB = GraphQLObjectType.newObject().name("Query")
        for (GqlRootQuery q in art.rootQueries.values()) {
            GraphQLOutputType outType = GraphQLTypeReference.typeRef(q.targetType)
            GraphQLFieldDefinition.Builder fb = GraphQLFieldDefinition.newFieldDefinition().name(q.name)
            if (q.list) {
                fb.type(GraphQLList.list(outType))
                fb.argument(GraphQLArgument.newArgument().name("first").type(Scalars.GraphQLInt).build())
                cm.listEdgeByField.put(q.name, Boolean.TRUE)
            } else {
                fb.type(outType)
                fb.argument(GraphQLArgument.newArgument().name("id").type(Scalars.GraphQLString).build())
                cm.listEdgeByField.put(q.name, Boolean.FALSE)
            }
            queryB.field(fb)
        }

        GraphQLSchema.Builder sb = GraphQLSchema.newSchema().query(queryB.build())
        for (GraphQLObjectType.Builder tb in typeBuilders.values()) sb.additionalType(tb.build())
        return new BuiltSchema(schema: sb.build(), costModel: cm, artifact: art)
    }
}
```

> Data fetchers are attached in Task 8/9 via a `GraphQLCodeRegistry`; this task only builds the type system + cost model so introspection works.

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :runtime:component:moqui-graphql:test --tests GraphQLSchemaBuilderTests`
Expected: PASS.

- [ ] **Step 5: Write the tool factory that builds + caches at startup**

`runtime/component/moqui-graphql/src/main/groovy/org/moqui/graphql/GraphQLToolFactory.groovy`:

```groovy
package org.moqui.graphql
import groovy.transform.CompileStatic
import org.moqui.context.ExecutionContextFactory
import org.moqui.context.ToolFactory
import org.moqui.context.ResourceReference
import org.moqui.util.MNode

/** Builds the GraphQLSchema + cost model ONCE at startup and caches them (decision 6 / perf).
 *  Scans every component's graphql/*.graphql.xml. getInstance() returns the cached BuiltSchema. */
@CompileStatic class GraphQLToolFactory implements ToolFactory<BuiltSchema> {
    private ExecutionContextFactory ecf
    private volatile BuiltSchema built

    @Override String getName() { return "GraphQL" }

    @Override void init(ExecutionContextFactory ecf) {
        this.ecf = ecf
        List<MNode> schemaNodes = []
        for (String compName in ecf.getComponentBaseLocations().keySet()) {
            ResourceReference dir = ecf.resource.getLocationReference("component://${compName}/graphql")
            if (dir != null && dir.getExists() && dir.isDirectory()) {
                for (ResourceReference rr in dir.getDirectoryEntries()) {
                    if (rr.fileName.endsWith(".graphql.xml")) schemaNodes.add(MNode.parse(rr))
                }
            }
        }
        SchemaArtifact art = new SchemaArtifactParser().parse(schemaNodes)
        this.built = new GraphQLSchemaBuilder(ecf.getExecutionContext()).build(art)
    }

    @Override BuiltSchema getInstance(Object... parameters) { return built }
    @Override void destroy() { }
}
```

- [ ] **Step 6: Register the tool factory in `MoquiConf.xml`**

Add inside `<moqui-conf>` in `runtime/component/moqui-graphql/MoquiConf.xml`:

```xml
    <tools>
        <tool-factory class="org.moqui.graphql.GraphQLToolFactory" init-priority="50"/>
    </tools>
```

- [ ] **Step 7: Add a startup-cache test and run**

Append to `GraphQLSchemaBuilderTests.groovy`:

```groovy
    def "tool factory exposes a cached BuiltSchema after startup"() {
        when:
        def built = ec.factory.getToolFactory("GraphQL").getInstance()
        then:
        built != null
        built.schema.getObjectType("Order") != null
        // same instance each call => cached, not rebuilt
        built.is(ec.factory.getToolFactory("GraphQL").getInstance())
    }
```

Run: `./gradlew :runtime:component:moqui-graphql:test --tests GraphQLSchemaBuilderTests`
Expected: PASS (requires the real `graphql/OmsSchema.graphql.xml` from Task 1 to define `Order`).

- [ ] **Step 8: Commit**

```bash
git -C runtime/component/moqui-graphql add src/main/groovy/org/moqui/graphql/GraphQLSchemaBuilder.groovy \
    src/main/groovy/org/moqui/graphql/GraphQLToolFactory.groovy MoquiConf.xml src/test/groovy
git -C runtime/component/moqui-graphql commit -m "feat: build graphql-java schema from artifacts; cache at startup via ToolFactory"
```

---

## Task 8: Entity data fetcher + DataLoader batch loader + scope seam

Resolves fields/edges against entities. To-one and root-by-pk use a direct find; **list edges batch via `DataLoader`** (`WHERE parentKey IN (...)`, decision 8) to avoid cartesian explosion. All finds use `.useClone(true)` (decision 9) and run inside the request's read-only transaction (decision 10). `ScopeFilter` is the phase-1 no-op authz seam (decision 11).

**Files:**
- Create: `runtime/component/moqui-graphql/src/main/groovy/org/moqui/graphql/ScopeFilter.groovy`
- Create: `runtime/component/moqui-graphql/src/main/groovy/org/moqui/graphql/EntityBatchLoader.groovy`
- Create: `runtime/component/moqui-graphql/src/main/groovy/org/moqui/graphql/EntityDataFetcher.groovy`
- Test: `runtime/component/moqui-graphql/src/test/groovy/EntityDataFetcherTests.groovy`

- [ ] **Step 1: Implement the no-op scope seam first** (tiny, no test needed beyond compile)

`runtime/component/moqui-graphql/src/main/groovy/org/moqui/graphql/ScopeFilter.groovy`:

```groovy
package org.moqui.graphql
import groovy.transform.CompileStatic
import org.moqui.entity.EntityFind

/** Authorization seam (decision 11). Phase 1: no-op (one DB per client => no cross-client scoping).
 *  Phase 2: apply a party/row-scope condition for partner callers. Kept as a seam so the executor
 *  always routes finds through here and phase 2 needs no executor surgery. */
@CompileStatic class ScopeFilter {
    EntityFind apply(EntityFind find, String entityName, String callerProfile) {
        return find   // phase 1: unmodified
    }
}
```

- [ ] **Step 2: Write the failing test for batched list resolution**

`runtime/component/moqui-graphql/src/test/groovy/EntityDataFetcherTests.groovy`:

```groovy
import spock.lang.Specification
import spock.lang.Shared
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.graphql.EntityBatchLoader

class EntityDataFetcherTests extends Specification {
    @Shared ExecutionContext ec
    def setupSpec() {
        ec = Moqui.getExecutionContext(); ec.artifactExecution.disableAuthz()
        // two users so the IN-clause batch returns >1 group
        ec.entity.makeValue("moqui.security.UserAccount").setAll([userId:"GQLT1", username:"gqlt1", userFullName:"T One"]).createOrUpdate()
        ec.entity.makeValue("moqui.security.UserAccount").setAll([userId:"GQLT2", username:"gqlt2", userFullName:"T Two"]).createOrUpdate()
    }
    def cleanupSpec() {
        ec.entity.find("moqui.security.UserAccount").condition("userId","in",["GQLT1","GQLT2"]).deleteAll()
        ec.artifactExecution.enableAuthz(); ec.destroy()
    }

    def "batch loader fetches all keys in one IN query, grouped by key"() {
        given:
        def loader = new EntityBatchLoader(ec, "moqui.security.UserAccount", "userId", true, 5000)
        when:
        def result = loader.loadByKeys(["GQLT1","GQLT2"])
        then: "one entry per requested key, each holding the matching row(s)"
        result["GQLT1"][0].username == "gqlt1"
        result["GQLT2"][0].username == "gqlt2"
    }
}
```

Add to `MoquiSuite`.

- [ ] **Step 3: Run to verify it fails**

Run: `./gradlew :runtime:component:moqui-graphql:test --tests EntityDataFetcherTests`
Expected: FAIL — `EntityBatchLoader` does not exist.

- [ ] **Step 4: Implement the batch loader** (the core anti-explosion primitive)

`runtime/component/moqui-graphql/src/main/groovy/org/moqui/graphql/EntityBatchLoader.groovy`:

```groovy
package org.moqui.graphql
import groovy.transform.CompileStatic
import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityList
import org.moqui.entity.EntityValue

/** Loads child rows for many parent keys in ONE query (WHERE keyField IN (:keys)), then groups
 *  by key. This is the DataLoader batch function for a list edge — bounded rows per level,
 *  no cartesian join. Routed to the read replica via useClone (decision 9). */
@CompileStatic class EntityBatchLoader {
    private final ExecutionContext ec
    private final String entityName, keyField
    private final boolean useClone
    private final int maxRows
    EntityBatchLoader(ExecutionContext ec, String entityName, String keyField, boolean useClone, int maxRows) {
        this.ec = ec; this.entityName = entityName; this.keyField = keyField
        this.useClone = useClone; this.maxRows = maxRows
    }

    Map<Object, List<Map>> loadByKeys(List<Object> keys) {
        Map<Object, List<Map>> grouped = [:]
        for (Object k in keys) grouped.put(k, new ArrayList<Map>())
        EntityList rows = ec.entity.find(entityName)
            .condition(keyField, "in", keys)
            .useClone(useClone)
            .queryTimeout(0)            // engine sets the real timeout per request (Task 10)
            .maxRows(maxRows)
            .list()
        if (rows.size() >= maxRows)
            throw new RuntimeException("graphql.rowcap: level for ${entityName} exceeded ${maxRows} rows")
        for (EntityValue ev in rows) {
            Object k = ev.get(keyField)
            List<Map> bucket = grouped.get(k)
            if (bucket != null) bucket.add(ev.getMap())
        }
        return grouped
    }
}
```

`runtime/component/moqui-graphql/src/main/groovy/org/moqui/graphql/EntityDataFetcher.groovy` (the `DataFetcher` glue that registers batch loaders per edge and reads from them):

```groovy
package org.moqui.graphql
import groovy.transform.CompileStatic
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import org.dataloader.DataLoader
import org.dataloader.DataLoaderFactory

/** DataFetcher for a list edge: returns a CompletableFuture from the per-edge DataLoader so
 *  sibling keys batch together. Registered against the schema's GraphQLCodeRegistry in Task 9. */
@CompileStatic class EntityDataFetcher {
    static DataFetcher listEdgeFetcher(String dataLoaderKey, String parentKeyField) {
        return { DataFetchingEnvironment env ->
            Map source = (Map) env.getSource()
            DataLoader<Object, List<Map>> dl = env.getDataLoader(dataLoaderKey)
            return dl.load(source.get(parentKeyField))
        } as DataFetcher
    }

    static DataFetcher scalarFetcher(String fieldName) {
        return { DataFetchingEnvironment env -> ((Map) env.getSource())?.get(fieldName) } as DataFetcher
    }
}
```

- [ ] **Step 5: Run to verify it passes**

Run: `./gradlew :runtime:component:moqui-graphql:test --tests EntityDataFetcherTests`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git -C runtime/component/moqui-graphql add src/main/groovy/org/moqui/graphql/ScopeFilter.groovy \
    src/main/groovy/org/moqui/graphql/EntityBatchLoader.groovy \
    src/main/groovy/org/moqui/graphql/EntityDataFetcher.groovy src/test/groovy
git -C runtime/component/moqui-graphql commit -m "feat: DataLoader batch loader + data fetchers (IN-clause batching, useClone, row cap)"
```

---

## Task 9: GraphQL engine — wire schema + fetchers + execution (happy path)

Assembles an executable schema (attaches fetchers + per-edge DataLoaders to the cached `BuiltSchema`), runs a query on **one thread inside one read-only transaction** (decision 10), and returns data. Instrumentation gating is added in Task 10.

**Files:**
- Create: `runtime/component/moqui-graphql/src/main/groovy/org/moqui/graphql/GraphQLEngine.groovy`
- Create: `runtime/component/moqui-graphql/data/GraphQLDemoData.xml`
- Test: `runtime/component/moqui-graphql/src/test/groovy/GraphQLEngineTests.groovy`

- [ ] **Step 1: Create demo data** (so a root query returns something deterministic)

`runtime/component/moqui-graphql/data/GraphQLDemoData.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<entity-facade-xml type="install">
    <!-- minimal: one order + two items. Adjust entity/field names to this install's mantle model. -->
    <mantle.order.OrderHeader orderId="GQLDEMO1" orderName="GraphQL Demo" statusId="OrderPlaced"/>
    <mantle.order.OrderItem orderId="GQLDEMO1" orderItemSeqId="01" productId="DEMO_P1" quantity="2"/>
    <mantle.order.OrderItem orderId="GQLDEMO1" orderItemSeqId="02" productId="DEMO_P2" quantity="1"/>
</entity-facade-xml>
```

- [ ] **Step 2: Write the failing test (root list + nested list edge)**

`runtime/component/moqui-graphql/src/test/groovy/GraphQLEngineTests.groovy`:

```groovy
import spock.lang.Specification
import spock.lang.Shared
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.graphql.GraphQLEngine

class GraphQLEngineTests extends Specification {
    @Shared ExecutionContext ec
    def setupSpec() {
        ec = Moqui.getExecutionContext(); ec.artifactExecution.disableAuthz()
        ec.entity.makeDataLoader().location("component://moqui-graphql/data/GraphQLDemoData.xml").load()
    }
    def cleanupSpec() { ec.artifactExecution.enableAuthz(); ec.destroy() }

    def "executes a nested query returning an order and its items"() {
        given:
        def engine = new GraphQLEngine(ec)
        def query = '{ orders(first: 10) { orderId items(first: 50) { productId quantity } } }'
        when:
        def result = engine.execute(query, [:], "internal")
        then:
        result.errors.isEmpty()
        def orders = result.data["orders"] as List
        orders.find { it.orderId == "GQLDEMO1" }.items.size() == 2
    }
}
```

Add to `MoquiSuite`.

- [ ] **Step 3: Run to verify it fails**

Run: `./gradlew :runtime:component:moqui-graphql:test --tests GraphQLEngineTests`
Expected: FAIL — `GraphQLEngine` does not exist.

- [ ] **Step 4: Implement the engine** (root fetchers + per-edge DataLoader registry + single read-only txn)

`runtime/component/moqui-graphql/src/main/groovy/org/moqui/graphql/GraphQLEngine.groovy`:

```groovy
package org.moqui.graphql
import groovy.transform.CompileStatic
import org.moqui.context.ExecutionContext
import graphql.GraphQL
import graphql.ExecutionInput
import graphql.ExecutionResult
import graphql.schema.*
import org.dataloader.DataLoader
import org.dataloader.DataLoaderFactory
import org.dataloader.DataLoaderRegistry
import org.dataloader.DataLoaderOptions

/** Orchestrates a single GraphQL request: build executable schema (cached parts + fetchers),
 *  register per-edge DataLoaders, run within ONE read-only transaction on the calling thread. */
@CompileStatic class GraphQLEngine {
    private final ExecutionContext ec
    private final BuiltSchema built
    private final ScopeFilter scopeFilter = new ScopeFilter()
    private final boolean useClone
    private final int maxRowsPerLevel

    GraphQLEngine(ExecutionContext ec) {
        this.ec = ec
        this.built = (BuiltSchema) ec.factory.getToolFactory("GraphQL").getInstance()
        this.useClone = "true".equals(ec.factory.confResource.getLocationText("graphql.useClone", false) ?: "true")
        this.maxRowsPerLevel = (ec.factory.confResource ? 5000 : 5000)  // read from conf in Task 10 wiring
    }

    Map execute(String query, Map<String,Object> variables, String callerProfile) {
        GraphQLSchema executable = withFetchers(built)
        DataLoaderRegistry registry = buildRegistry(callerProfile)
        GraphQL graphQL = GraphQL.newGraphQL(executable).build()

        ExecutionInput input = ExecutionInput.newExecutionInput()
            .query(query).variables(variables ?: [:])
            .dataLoaderRegistry(registry).build()

        // decision 10: one read-only transaction, calling thread. DataLoader dispatches synchronously
        // because each batch fetch is a blocking entity find executed inline.
        ExecutionResult er = (ExecutionResult) ec.transaction.callUseOrBegin(null, {
            return graphQL.execute(input)
        })
        return [data: er.getData(), errors: er.getErrors().collect { it.getMessage() }]
    }

    /** Attach scalar + edge + root fetchers to the cached type system via a code registry. */
    private GraphQLSchema withFetchers(BuiltSchema bs) {
        GraphQLCodeRegistry.Builder code = GraphQLCodeRegistry.newCodeRegistry()
        for (GqlType t in bs.artifact.types.values()) {
            for (GqlField f in t.fields.values())
                code.dataFetcher(FieldCoordinates.coordinates(t.name, f.name), EntityDataFetcher.scalarFetcher(f.name))
            for (GqlEdge e in t.edges.values()) {
                if (e.list) {
                    code.dataFetcher(FieldCoordinates.coordinates(t.name, e.name),
                        EntityDataFetcher.listEdgeFetcher("edge:${t.name}.${e.name}".toString(), keyFieldFor(t)))
                } else {
                    code.dataFetcher(FieldCoordinates.coordinates(t.name, e.name), toOneFetcher(t, e))
                }
            }
        }
        for (GqlRootQuery q in bs.artifact.rootQueries.values())
            code.dataFetcher(FieldCoordinates.coordinates("Query", q.name), rootFetcher(q))
        return bs.schema.transform({ b -> b.codeRegistry(code.build()) })
    }

    private DataLoaderRegistry buildRegistry(String callerProfile) {
        DataLoaderRegistry reg = new DataLoaderRegistry()
        DataLoaderOptions opts = DataLoaderOptions.newOptions()   // per-request cache on
        for (GqlType t in built.artifact.types.values()) {
            for (GqlEdge e in t.edges.values()) {
                if (!e.list) continue
                GqlType target = built.artifact.types.get(e.targetType)
                String childEntity = target.entityName
                String childKey = keyFieldFor(t)   // FK on child = parent key (simplified; see note)
                EntityBatchLoader bl = new EntityBatchLoader(ec, childEntity, childKey, useClone, maxRowsPerLevel)
                DataLoader<Object, List<Map>> dl = DataLoaderFactory.newDataLoader({ keys ->
                    java.util.concurrent.CompletableFuture.completedFuture(
                        keys.collect { Object k -> bl.loadByKeys([k]).get(k) })
                }, opts)
                reg.register("edge:${t.name}.${e.name}".toString(), dl)
            }
        }
        return reg
    }

    private graphql.schema.DataFetcher rootFetcher(GqlRootQuery q) {
        return { graphql.schema.DataFetchingEnvironment env ->
            def find = ec.entity.find(q.entityName).useClone(useClone)
            find = scopeFilter.apply(find, q.entityName, "internal")
            if (q.byPk) {
                String id = env.getArgument("id")
                def one = find.condition(keyFieldForEntity(q.entityName), id).one()
                return one?.getMap()
            } else {
                Integer first = (Integer) env.getArgument("first")
                if (first != null) find.maxRows(first)
                return find.list().collect { it.getMap() }
            }
        } as graphql.schema.DataFetcher
    }

    private graphql.schema.DataFetcher toOneFetcher(GqlType t, GqlEdge e) {
        return { graphql.schema.DataFetchingEnvironment env ->
            Map src = (Map) env.getSource()
            def rel = ec.entity.find(built.artifact.types.get(e.targetType).entityName).useClone(useClone)
            // simplified: resolve via the related-entity relationship using the FK on the source
            return rel.condition(env.getArgumentOrDefault("id", src.get(keyFieldFor(t)))).one()?.getMap()
        } as graphql.schema.DataFetcher
    }

    private String keyFieldFor(GqlType t) { return keyFieldForEntity(t.entityName) }
    private String keyFieldForEntity(String entityName) {
        def pks = ((org.moqui.impl.entity.EntityFacadeImpl) ec.entity).getEntityDefinition(entityName).getPkFieldNames()
        return pks.get(0)
    }
}
```

> **Implementation note (relationship resolution):** the to-one/edge key wiring above is intentionally simplified to the common single-PK foreign-key case so the plan stays concrete. During execution, resolve the actual key-map from the Moqui relationship (`EntityDefinition.getRelationshipInfo(relName)` exposes the FK field map) instead of assuming the child FK equals the parent PK name. Add a test for a relationship whose FK name differs from the parent PK before generalizing. This is the one place to slow down and verify against the real mantle relationships.

- [ ] **Step 5: Run to verify it passes**

Run: `./gradlew :runtime:component:moqui-graphql:test --tests GraphQLEngineTests`
Expected: PASS. If the mantle entity/relationship names in this install differ, fix `OmsSchema.graphql.xml` + `GraphQLDemoData.xml` to match real entities, then re-run.

- [ ] **Step 6: Commit**

```bash
git -C runtime/component/moqui-graphql add src/main/groovy/org/moqui/graphql/GraphQLEngine.groovy \
    data/GraphQLDemoData.xml src/test/groovy
git -C runtime/component/moqui-graphql commit -m "feat: GraphQL engine executes nested queries in one read-only txn with DataLoader batching"
```

---

## Task 10: Query governor — instrumentation gate + runtime guards + structured rejections

Adds the analyzer gate (depth + complexity via graphql-java instrumentation carrying our `CostModel`) and the runtime guards (per-request `queryTimeout`, wall-clock budget), plus agent-actionable rejection messages (decision 6).

**Files:**
- Create: `runtime/component/moqui-graphql/src/main/groovy/org/moqui/graphql/QueryGovernor.groovy`
- Modify: `GraphQLEngine.groovy` (apply instrumentation + read conf thresholds + set per-request queryTimeout/wall-clock)
- Test: append to `GraphQLEngineTests.groovy`

- [ ] **Step 1: Write failing tests for depth + complexity rejection with actionable messages**

Append to `runtime/component/moqui-graphql/src/test/groovy/GraphQLEngineTests.groovy`:

```groovy
    def "rejects a query exceeding max depth with an actionable message"() {
        given: def engine = new GraphQLEngine(ec)   // maxDepth=6 from conf
        def deep = '{ orders { items { ' * 4 + 'productId' + ' } }' * 4 + ' }'
        when: def r = engine.execute(deep, [:], "internal")
        then:
        !r.errors.isEmpty()
        r.errors.any { it.toLowerCase().contains("depth") }
    }

    def "rejects a fan-out bomb exceeding the cost budget"() {
        given: def engine = new GraphQLEngine(ec)   // maxCost=1000 from conf
        def bomb = '{ orders(first: 1000) { items(first: 1000) { productId } } }'  // ~1000*1001
        when: def r = engine.execute(bomb, [:], "internal")
        then:
        !r.errors.isEmpty()
        r.errors.any { it.toLowerCase().contains("complex") || it.toLowerCase().contains("cost") }
    }
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :runtime:component:moqui-graphql:test --tests GraphQLEngineTests`
Expected: FAIL — deep/bomb queries currently execute (no gate yet).

- [ ] **Step 3: Implement the governor**

`runtime/component/moqui-graphql/src/main/groovy/org/moqui/graphql/QueryGovernor.groovy`:

```groovy
package org.moqui.graphql
import groovy.transform.CompileStatic
import graphql.execution.instrumentation.Instrumentation
import graphql.execution.instrumentation.ChainedInstrumentation
import graphql.analysis.MaxQueryDepthInstrumentation
import graphql.analysis.MaxQueryComplexityInstrumentation

/** Builds the chained instrumentation that gates a query BEFORE execution (decision 8):
 *  depth limit + complexity limit (complexity uses our CostModel). graphql-java throws
 *  AbortExecutionException with a clear message when a limit is exceeded; that message is
 *  agent-actionable (decision 6). */
@CompileStatic class QueryGovernor {
    static Instrumentation build(CostModel costModel, int maxDepth, int maxCost) {
        List<Instrumentation> chain = [
            new MaxQueryDepthInstrumentation(maxDepth),
            new MaxQueryComplexityInstrumentation(maxCost, costModel)
        ] as List<Instrumentation>
        return new ChainedInstrumentation(chain)
    }
}
```

- [ ] **Step 4: Wire it into the engine** — in `GraphQLEngine.groovy`, read conf thresholds and apply instrumentation + per-request timeout.

Replace the conf/threshold lines in the constructor and the `GraphQL.newGraphQL(...)` line:

```groovy
    private final int maxDepth, maxCost, queryTimeoutSeconds, wallClockBudgetMs

    GraphQLEngine(ExecutionContext ec) {
        this.ec = ec
        this.built = (BuiltSchema) ec.factory.getToolFactory("GraphQL").getInstance()
        def cr = ec.factory.confResource
        this.useClone           = "true".equals(prop(cr,"graphql.useClone","true"))
        this.maxRowsPerLevel    = Integer.parseInt(prop(cr,"graphql.maxRowsPerLevel","5000"))
        this.maxDepth           = Integer.parseInt(prop(cr,"graphql.maxDepth","6"))
        this.maxCost            = Integer.parseInt(prop(cr,"graphql.maxCost","1000"))
        this.queryTimeoutSeconds= Integer.parseInt(prop(cr,"graphql.queryTimeoutSeconds","20"))
        this.wallClockBudgetMs  = Integer.parseInt(prop(cr,"graphql.wallClockBudgetMs","30000"))
        this.built.costModel.unindexedPenalty = Integer.parseInt(prop(cr,"graphql.unindexedFilterPenalty","50"))
        this.built.costModel.defaultFirstCap  = Integer.parseInt(prop(cr,"graphql.defaultFirstCap","100"))
    }
    private static String prop(def confRes, String name, String dflt) {
        try { return System.getProperty(name) ?: dflt } catch (ignored) { return dflt }
    }
```

> Use the framework's actual conf-property accessor here (`ec.factory.getExecutionContext()` exposes `ec.resource`/properties; mirror how `moqui-ai` reads `default-property` values). The `prop(...)` shim above keeps the test green if the accessor differs; replace it with the real lookup during execution and add an assertion that a conf override changes `maxDepth`.

Update `execute(...)` to build the GraphQL with instrumentation and set the per-request query timeout on every find (pass `queryTimeoutSeconds` into `EntityBatchLoader` and the root/to-one finds instead of `0`):

```groovy
        GraphQL graphQL = GraphQL.newGraphQL(executable)
            .instrumentation(QueryGovernor.build(built.costModel, maxDepth, maxCost))
            .build()
```

And thread `queryTimeoutSeconds` through `buildRegistry` → `EntityBatchLoader(... , queryTimeoutSeconds, maxRowsPerLevel)` and the root/to-one fetchers (`find.queryTimeout(queryTimeoutSeconds)`). Add a wall-clock guard by wrapping `graphQL.execute(input)` start time and, after completion, recording elapsed (hard wall-clock interruption is a phase-2 refinement; phase 1 records + logs elapsed and relies on `queryTimeout` for hard stops — note this limitation explicitly).

- [ ] **Step 5: Run to verify the rejection tests pass (and earlier happy-path still passes)**

Run: `./gradlew :runtime:component:moqui-graphql:test --tests GraphQLEngineTests`
Expected: PASS (happy path + depth rejection + cost rejection).

- [ ] **Step 6: Emit Shopify-shaped `extensions.cost` in the response** (validated against `mantle-shopify-connector`)

The connector consumes `extensions.cost.{requestedQueryCost, actualQueryCost, throttleStatus.{maximumAvailable, currentlyAvailable, restoreRate}}`. Emit the **same shape** so Shopify-integrated consumers understand our throttling and the phase-2 bucket maps 1:1. graphql-java's `MaxQueryComplexityInstrumentation` already computes the complexity; capture it via an `Instrumentation` that records the total into a per-request holder. Add to the `execute(...)` result:

```groovy
        // requestedQueryCost = static estimate (from the complexity instrumentation);
        // actualQueryCost = estimate in phase 1 (refine to measured rows*weight later).
        int requested = built.costModel ? lastEstimatedCost : 0   // captured by the complexity instrumentation
        Map cost = [ requestedQueryCost: requested, actualQueryCost: requested,
                     throttleStatus: [ maximumAvailable: maxCost, currentlyAvailable: maxCost, restoreRate: 0 ] ]
        return [data: er.getData(), errors: errs, extensions: [cost: cost]]
```

> `throttleStatus` is static in phase 1 (no bucket yet); `currentlyAvailable == maximumAvailable == maxCost`, `restoreRate: 0`. Phase 2's leaky bucket fills these with live values. To capture `lastEstimatedCost`, wrap a tiny custom `Instrumentation` alongside the governor's chain that reads the computed complexity — or compute the estimate once up front with `graphql.analysis.QueryComplexityInstrumentation`'s calculator and reuse it for both the gate and the response.

Add a test asserting the shape:

```groovy
    def "response carries Shopify-shaped extensions.cost"() {
        given: def engine = new GraphQLEngine(ec)
        when: def r = engine.execute('{ orders(first: 3) { orderId } }', [:], "internal")
        then:
        r.extensions.cost.requestedQueryCost instanceof Integer
        r.extensions.cost.throttleStatus.maximumAvailable instanceof Integer
    }
```

Run: `./gradlew :runtime:component:moqui-graphql:test --tests GraphQLEngineTests`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git -C runtime/component/moqui-graphql add src/main/groovy/org/moqui/graphql/QueryGovernor.groovy \
    src/main/groovy/org/moqui/graphql/GraphQLEngine.groovy src/test/groovy
git -C runtime/component/moqui-graphql commit -m "feat: query governor gates depth+cost; per-request timeout; Shopify-shaped extensions.cost"
```

---

## Task 11: `/graphql` REST endpoint

Exposes the engine over HTTP via a Moqui service + `rest.xml` (mirrors `moqui-ai` service/REST patterns).

**Files:**
- Create: `runtime/component/moqui-graphql/service/graphql/QueryServices.xml`
- Create: `runtime/component/moqui-graphql/service/graphql.rest.xml`
- Test: `runtime/component/moqui-graphql/src/test/groovy/GraphQLRestApiTests.groovy`

- [ ] **Step 1: Write the failing test (call the service)**

`runtime/component/moqui-graphql/src/test/groovy/GraphQLRestApiTests.groovy`:

```groovy
import spock.lang.Specification
import spock.lang.Shared
import org.moqui.context.ExecutionContext
import org.moqui.Moqui

class GraphQLRestApiTests extends Specification {
    @Shared ExecutionContext ec
    def setupSpec() {
        ec = Moqui.getExecutionContext(); ec.artifactExecution.disableAuthz()
        ec.entity.makeDataLoader().location("component://moqui-graphql/data/GraphQLDemoData.xml").load()
        ((org.moqui.impl.context.UserFacadeImpl) ec.user).internalLoginUser("john.doe")
    }
    def cleanupSpec() { ec.artifactExecution.enableAuthz(); ec.destroy() }

    def "execute#Query service returns data for a valid query"() {
        when:
        Map out = ec.service.sync().name("moqui.graphql.QueryServices.execute#Query")
            .parameters([query: '{ orders(first: 5) { orderId } }', variables: [:]]).call()
        then:
        out.errors == null || (out.errors as List).isEmpty()
        out.data != null
    }
}
```

Add to `MoquiSuite`. (Uses the seeded user `john.doe` present in framework seed data; swap for any seeded user if absent.)

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :runtime:component:moqui-graphql:test --tests GraphQLRestApiTests`
Expected: FAIL — service `execute#Query` not found.

- [ ] **Step 3: Implement the service**

`runtime/component/moqui-graphql/service/graphql/QueryServices.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<services xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:noNamespaceSchemaLocation="http://moqui.org/xsd/service-definition-3.xsd">
    <service verb="execute" noun="Query" type="inline" authenticate="true" allow-remote="true" transaction="ignore">
        <description>Execute a GraphQL query against the curated OMS graph.</description>
        <in-parameters>
            <parameter name="query" required="true"/>
            <parameter name="variables" type="Map"/>
        </in-parameters>
        <out-parameters>
            <parameter name="data" type="Map"/>
            <parameter name="errors" type="List"/>
            <parameter name="extensions" type="Map"/>   <!-- carries Shopify-shaped cost (Task 10) -->
        </out-parameters>
        <actions>
            <script><![CDATA[
                def engine = new org.moqui.graphql.GraphQLEngine(ec)
                def r = engine.execute(query, (variables ?: [:]) as Map, "internal")
                data = r.data
                errors = r.errors
                extensions = r.extensions
            ]]></script>
        </actions>
    </service>

    <service verb="get" noun="Schema" type="inline" authenticate="true" allow-remote="true">
        <description>Return the GraphQL SDL for introspection/tooling.</description>
        <out-parameters><parameter name="sdl"/></out-parameters>
        <actions>
            <script><![CDATA[
                def built = ec.factory.getToolFactory("GraphQL").getInstance()
                sdl = new graphql.schema.idl.SchemaPrinter().print(built.schema)
            ]]></script>
        </actions>
    </service>
</services>
```

`runtime/component/moqui-graphql/service/graphql.rest.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<resource xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:noNamespaceSchemaLocation="http://moqui.org/xsd/rest-api-3.xsd"
        name="graphql" displayName="GraphQL API" version="1.0.0"
        description="Curated read-only GraphQL endpoint">
    <method type="post"><service name="moqui.graphql.QueryServices.execute#Query"/></method>
    <resource name="schema">
        <method type="get"><service name="moqui.graphql.QueryServices.get#Schema"/></method>
    </resource>
</resource>
```

> `transaction="ignore"` on the service: the engine opens its own read-only transaction (decision 10) via `ec.transaction.callUseOrBegin`, so the service wrapper must not impose one. Confirm the endpoint is reachable at `POST /rest/s1/graphql` (Moqui's REST mount) during manual verification.

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :runtime:component:moqui-graphql:test --tests GraphQLRestApiTests`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git -C runtime/component/moqui-graphql add service src/test/groovy
git -C runtime/component/moqui-graphql commit -m "feat: expose /graphql POST endpoint + schema SDL service"
```

---

## Task 12: Observability log

Records every query's estimated cost, actual rows/time, and verdict (spec unit 7) — the data that calibrates thresholds and shows why agent queries get rejected.

**Files:**
- Create: `runtime/component/moqui-graphql/entity/GraphQLEntities.xml`
- Modify: `GraphQLEngine.groovy` (write a log row per request)
- Test: `runtime/component/moqui-graphql/src/test/groovy/GraphQLEngineTests.groovy` (append)

- [ ] **Step 1: Define the log entity**

`runtime/component/moqui-graphql/entity/GraphQLEntities.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<entities xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:noNamespaceSchemaLocation="http://moqui.org/xsd/entity-definition-3.xsd">
    <entity entity-name="GraphqlQueryLog" package="moqui.graphql">
        <field name="queryLogId" type="id" is-pk="true"/>
        <field name="userId" type="id"/>
        <field name="callerProfile" type="text-short"/>
        <field name="queryText" type="text-very-long"/>
        <field name="estimatedCost" type="number-integer"/>
        <field name="rowsFetched" type="number-integer"/>
        <field name="durationMs" type="number-integer"/>
        <field name="verdict" type="text-short"/>   <!-- ALLOWED | REJECTED -->
        <field name="rejectReason" type="text-long"/>
        <field name="queryDate" type="date-time"/>
        <relationship type="one" related="moqui.security.UserAccount" short-alias="user"/>
    </entity>
</entities>
```

- [ ] **Step 2: Write the failing test**

Append to `GraphQLEngineTests.groovy`:

```groovy
    def "every executed query writes a GraphqlQueryLog row with a verdict"() {
        given: def engine = new GraphQLEngine(ec)
        def before = ec.entity.find("moqui.graphql.GraphqlQueryLog").count()
        when:
        engine.execute('{ orders(first: 3) { orderId } }', [:], "internal")
        def after = ec.entity.find("moqui.graphql.GraphqlQueryLog").count()
        then:
        after == before + 1
        def last = ec.entity.find("moqui.graphql.GraphqlQueryLog").orderBy("-queryDate").list().first()
        last.verdict == "ALLOWED"
        last.durationMs != null
    }
```

- [ ] **Step 3: Run to verify it fails**

Run: `./gradlew :runtime:component:moqui-graphql:test --tests GraphQLEngineTests`
Expected: FAIL — entity unknown / no log written.

- [ ] **Step 4: Write the log row in `GraphQLEngine.execute(...)`**

Wrap execution with timing + a log write (use a separate `force-new` transaction so the log persists even when the query's read-only txn is rolled back). Add at the end of `execute(...)`:

```groovy
        long started = System.currentTimeMillis()
        // ... run query (existing code) ...
        long durationMs = System.currentTimeMillis() - started
        List errs = er.getErrors().collect { it.getMessage() }
        ec.service.sync().name("create#moqui.graphql.GraphqlQueryLog").parameters([
            userId: ec.user.userId, callerProfile: callerProfile, queryText: query,
            rowsFetched: null, durationMs: (int) durationMs,
            verdict: errs.isEmpty() ? "ALLOWED" : "REJECTED",
            rejectReason: errs.isEmpty() ? null : errs.join("; "),
            queryDate: ec.user.nowTimestamp
        ]).requireNewTransaction(true).call()
```

> Per CLAUDE.md SECA/logging guidance, this log write must never fail the user's request — `requireNewTransaction(true)` isolates it; wrap in try/catch and `ec.logger.warn` on failure rather than `ec.message.addError`.

- [ ] **Step 5: Run to verify it passes**

Run: `./gradlew :runtime:component:moqui-graphql:test --tests GraphQLEngineTests`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git -C runtime/component/moqui-graphql add entity src/main/groovy/org/moqui/graphql/GraphQLEngine.groovy src/test/groovy
git -C runtime/component/moqui-graphql commit -m "feat: log every GraphQL query with verdict, duration, cost for observability"
```

---

## Task 13: Adversarial query catalog (regression guard)

The explicit pathological-query suite (spec Testing strategy). Each must be rejected. This is the regression guard the cost work is judged against.

**Files:**
- Create: `runtime/component/moqui-graphql/src/test/groovy/AdversarialQueryTests.groovy`

- [ ] **Step 1: Write the catalog (all expected to be rejected)**

`runtime/component/moqui-graphql/src/test/groovy/AdversarialQueryTests.groovy`:

```groovy
import spock.lang.Specification
import spock.lang.Shared
import spock.lang.Unroll
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.graphql.GraphQLEngine

class AdversarialQueryTests extends Specification {
    @Shared ExecutionContext ec
    @Shared GraphQLEngine engine
    def setupSpec() {
        ec = Moqui.getExecutionContext(); ec.artifactExecution.disableAuthz()
        ec.entity.makeDataLoader().location("component://moqui-graphql/data/GraphQLDemoData.xml").load()
        engine = new GraphQLEngine(ec)
    }
    def cleanupSpec() { ec.artifactExecution.enableAuthz(); ec.destroy() }

    @Unroll
    def "pathological query is rejected: #label"() {
        when: def r = engine.execute(query, [:], "internal")
        then: !r.errors.isEmpty()
        where:
        label                | query
        "depth bomb"         | '{ orders { items { ' * 5 + 'productId' + ' } }' * 5 + ' }'
        "fan-out bomb"       | '{ orders(first: 1000) { items(first: 1000) { productId } } }'
        "missing first"      | '{ orders { items { productId } } }'   // list edge with no first: => over default cap or rejected
        "unindexed filter"   | '{ orders(first: 10, filter: {orderName: "x"}) { orderId } }' // orderName not indexed (when filter args added)
    }
}
```

> The "missing first" and "unindexed filter" cases assert behaviors finalized in this plan's governor + a follow-up filter-argument task. If filter arguments are not yet wired, mark those two rows pending (`@Ignore` with a note) rather than asserting — do not delete them; they are the contract for the filter work.

- [ ] **Step 2: Run; tighten the governor until all active rows pass**

Run: `./gradlew :runtime:component:moqui-graphql:test --tests AdversarialQueryTests`
Expected: depth bomb + fan-out bomb REJECTED (PASS). If "missing first" passes through, add a rule in `QueryGovernor`/builder that a list edge without `first:` is treated as `defaultFirstCap` and still cost-checked (or rejected). Iterate until green.

- [ ] **Step 3: Commit**

```bash
git -C runtime/component/moqui-graphql add src/test/groovy/AdversarialQueryTests.groovy src/test/groovy/MoquiSuite.groovy
git -C runtime/component/moqui-graphql commit -m "test: adversarial pathological-query catalog (regression guard)"
```

- [ ] **Step 4: Run the full suite**

Run: `./gradlew :runtime:component:moqui-graphql:test`
Expected: all suite classes PASS.

---

## Task 14: Service-backed resolvers + view-entity types (decision 12)

> **Depends on Tasks 6, 7, 9, 10. Sequence it right after Task 10** (before or after Task 13 is fine). Numbered 14 only to avoid renumbering earlier tasks.

Lets the graph return **computed** data consumers actually need (validated against notnaked: `itemFulfillmentStatus`, `customerName`) by delegating a field to a Moqui service, and lets a type map to a **view-entity** for free joins. Service-backed fields are an analyzer blind spot, so they carry a **high fixed cost** and are **batched inside lists** (decision 12 governance carve-out).

**Files:**
- Modify: `src/main/groovy/org/moqui/graphql/CostModel.groovy` (high fixed cost for service-backed fields)
- Modify: `src/main/groovy/org/moqui/graphql/GraphQLSchemaBuilder.groovy` (wire service-backed fields; register cost)
- Modify: `src/main/groovy/org/moqui/graphql/GraphQLEngine.groovy` (attach a service-backed DataFetcher, batched)
- Create: `service/graphql/DemoResolvers.xml` (a tiny batch-capable resolver service for the test)
- Test: `src/test/groovy/ServiceBackedFieldTests.groovy`

- [ ] **Step 1: Add the demo resolver service** (batch form: accepts a key list, returns a value per key)

`runtime/component/moqui-graphql/service/graphql/DemoResolvers.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<services xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:noNamespaceSchemaLocation="http://moqui.org/xsd/service-definition-3.xsd">
    <!-- BATCH resolver: input is a List of key Maps; output 'values' is a Map keyed by the same
         key (here orderItemSeqId) -> computed value. A service-backed field MUST expose this
         batch shape to be used inside a list edge (decision 12). -->
    <service verb="get" noun="ItemFulfillmentStatus" type="inline" authenticate="true">
        <in-parameters><parameter name="keys" type="List"/></in-parameters>
        <out-parameters><parameter name="values" type="Map"/></out-parameters>
        <actions>
            <script><![CDATA[
                values = [:]
                for (def k in (keys ?: [])) {
                    // demo: derive a status from item quantity; real impl reuses the OMS
                    // itemFulfillmentStatus logic (ofbiz-oms-usl OrderServices.xml:2861-2993)
                    values[k.orderItemSeqId] = (k.quantity && (k.quantity as BigDecimal) > 0) ? "PROCESSING" : "COMPLETED"
                }
            ]]></script>
        </actions>
    </service>
</services>
```

- [ ] **Step 2: Write the failing test** (service-backed field resolves; high cost; view-entity type works)

`runtime/component/moqui-graphql/src/test/groovy/ServiceBackedFieldTests.groovy`:

```groovy
import spock.lang.Specification
import spock.lang.Shared
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.graphql.GraphQLEngine

class ServiceBackedFieldTests extends Specification {
    @Shared ExecutionContext ec
    def setupSpec() {
        ec = Moqui.getExecutionContext(); ec.artifactExecution.disableAuthz()
        ec.entity.makeDataLoader().location("component://moqui-graphql/data/GraphQLDemoData.xml").load()
    }
    def cleanupSpec() { ec.artifactExecution.enableAuthz(); ec.destroy() }

    def "a service-backed computed field resolves via its Moqui service, batched across list items"() {
        given: def engine = new GraphQLEngine(ec)
        def query = '{ orders(first: 5) { orderId items(first: 50) { orderItemSeqId fulfillmentStatus } } }'
        when: def r = engine.execute(query, [:], "internal")
        then:
        r.errors.isEmpty()
        def items = (r.data["orders"] as List).find { it.orderId == "GQLDEMO1" }.items
        items.every { it.fulfillmentStatus in ["PROCESSING","COMPLETED"] }
    }

    def "a service-backed field carries a high fixed cost so it cannot be treated as cheap"() {
        given: def built = ec.factory.getToolFactory("GraphQL").getInstance()
        expect: built.costModel.serviceFixedCost >= 20
    }
}
```

Add `ServiceBackedFieldTests.class` to `MoquiSuite`.

- [ ] **Step 3: Run to verify it fails**

Run: `./gradlew :runtime:component:moqui-graphql:test --tests ServiceBackedFieldTests`
Expected: FAIL — `fulfillmentStatus` unresolved / `serviceFixedCost` undefined.

- [ ] **Step 4: Add the fixed service cost to `CostModel.groovy`**

Add fields + use them in `calculate(...)`:

```groovy
    int serviceFixedCost = 25                       // service-backed fields are opaque => never cheap
    Set<String> serviceBackedFields = new HashSet<>()  // field names that are service-backed
```

In `calculate(...)`, before the list/scalar branch:

```groovy
        if (serviceBackedFields.contains(env.getField().getName())) return serviceFixedCost + childComplexity
```

- [ ] **Step 5: Wire service-backed fields in `GraphQLSchemaBuilder.build(...)`**

In the first pass over `t.fields.values()`, replace the single `tb.field(...)` with a branch:

```groovy
            for (GqlField f in t.fields.values()) {
                tb.field(GraphQLFieldDefinition.newFieldDefinition().name(f.name).type(Scalars.GraphQLString))
                if (f.isServiceBacked()) {
                    cm.serviceBackedFields.add(f.name)
                } else if (f.filterable) {
                    boolean indexed = indexClassifier.indexedFields(t.entityName).contains(f.entityField)
                    cm.filterIndexed.put(t.name + "." + f.name, indexed)
                }
            }
```

> View-entity types need **no builder change**: a `<gql-type entity-name="...">` may already name a view-entity, and `EntityFind`/`IndexClassifier` operate on it transparently. (Index classification on a view falls back to the view's declared PK/indexes; refine per member-entity if a view type proves filter-heavy.)

- [ ] **Step 6: Attach the service-backed fetcher in `GraphQLEngine.withFetchers(...)`**

When iterating a type's fields, register a DataLoader-batched fetcher for service-backed fields. Add a per-(type.field) DataLoader in `buildRegistry(...)` whose batch function calls the resolver service once with the whole key list:

```groovy
// in withFetchers(), inside the fields loop:
for (GqlField f in t.fields.values()) {
    if (f.isServiceBacked()) {
        code.dataFetcher(FieldCoordinates.coordinates(t.name, f.name),
            { graphql.schema.DataFetchingEnvironment env ->
                Map src = (Map) env.getSource()
                Map key = [:]; for (String inF in f.resolverIn) key.put(inF, src.get(inF))
                return env.getDataLoader("svc:${t.name}.${f.name}".toString()).load(key)
            } as graphql.schema.DataFetcher)
    } else {
        code.dataFetcher(FieldCoordinates.coordinates(t.name, f.name), EntityDataFetcher.scalarFetcher(f.name))
    }
}
// in buildRegistry(), register one DataLoader per service-backed field:
for (GqlType t in built.artifact.types.values()) for (GqlField f in t.fields.values()) {
    if (!f.isServiceBacked()) continue
    String svc = f.resolverService
    String keyOut = f.resolverIn ? f.resolverIn.last() : "id"   // demo keys results by last input field
    DataLoader<Map, Object> dl = DataLoaderFactory.newDataLoader({ List<Map> keys ->
        Map out = (Map) ec.service.sync().name(svc).parameter("keys", keys).call().get("values")
        return java.util.concurrent.CompletableFuture.completedFuture(
            keys.collect { Map k -> out?.get(k.get(keyOut)) })
    }, opts)
    reg.register("svc:${t.name}.${f.name}".toString(), dl)
}
```

> The `keyOut` convention (results keyed by the last `resolver-in` field) is the simplest workable contract for the demo. During execution, formalize the parent-key→service-input→result-key mapping (spec open item) and add a test for a resolver whose result key differs from its inputs. This is the service-resolver analogue of the relationship-key note in Task 9 — verify against a real service before generalizing.

- [ ] **Step 7: Run to verify it passes**

Run: `./gradlew :runtime:component:moqui-graphql:test --tests ServiceBackedFieldTests`
Expected: PASS (both: computed field resolves; `serviceFixedCost >= 20`).

- [ ] **Step 8: Add a view-entity-backed type test** (proves a type can map to a view-entity)

Append to `ServiceBackedFieldTests.groovy` a test that adds a small view-entity-backed type to a throwaway artifact and asserts a joined field resolves. If no suitable seed view-entity exists in this install, assert instead that `IndexClassifier.indexedFields(viewEntityName)` returns the view's PK without throwing — proving view-entities are handled by the same path. Keep the stronger join test where seed data allows.

- [ ] **Step 9: Commit**

```bash
git -C runtime/component/moqui-graphql add src/main/groovy/org/moqui/graphql/CostModel.groovy \
    src/main/groovy/org/moqui/graphql/GraphQLSchemaBuilder.groovy \
    src/main/groovy/org/moqui/graphql/GraphQLEngine.groovy service/graphql/DemoResolvers.xml src/test/groovy
git -C runtime/component/moqui-graphql commit -m "feat: service-backed resolver fields (batched, high fixed cost) + view-entity types (decision 12)"
```

---

## Deferred to Phase 2 (NOT in this plan)

- Leaky-bucket per-caller rate budget (governance layer 5) — the cost score is already a real number; meter it then.
- Persisted/allow-listed queries via `graphql-java` `PersistedQuerySupport`.
- Per-caller field/type visibility + party/row-scope population of `ScopeFilter` (decision 11).
- Hard wall-clock interruption (phase 1 records elapsed + relies on `queryTimeout` for hard stops).
- DataDocument-backed (type B) heavy types — schema layer supports it; wire when a real heavy report needs it.
- Analytics / aggregation (Q2) — deferred until user-group usage examples.
- Full-text / faceted Solr search (Q1) — stays on existing Solr endpoints; GraphQL is DB-backed only.

## Self-review notes (author)

- **Spec coverage:** decisions 1–12 each map to a task — curated schema (T3/T7), real GraphQL (T7/T9), governance layers 1–4 (T6/T10), schema A+B (T3/T7; B deferred), agent-actionable errors (T10), read-only (whole design), reuse graphql-java (T6/T7/T9/T10), pool isolation (T2 note + T8 useClone), single-txn execution (T9), authz seam (T8 ScopeFilter), **computed/service-assembled data — entity/view-entity/service-backed field kinds (T1/T3/T6/T14)**. ✓
- **Decision 12 validated against real code:** notnaked `OrderExtendedEntities.xml` edges map 1:1 to GraphQL edges; computed fields (`itemFulfillmentStatus`, `customerName`) are covered by T14 service-backed resolvers; live external (Shopify) ruled out of scope.
- **Known soft spots flagged inline, not hidden:** relationship key-map resolution (T9 note), service-resolver key mapping (T14 note), conf-property accessor shim (T10 note), wall-clock hard interruption deferred (T10/Phase 2). These are the parts to verify against the live framework API during execution.
- **Type consistency:** `BuiltSchema{schema,costModel,artifact}`, `CostModel.calculate(...)`/`serviceFixedCost`/`serviceBackedFields`, `GqlField.isServiceBacked()`/`resolverService`/`resolverIn`, `EntityBatchLoader.loadByKeys(...)`, `GraphQLEngine.execute(query,variables,callerProfile)` returning `{data, errors, extensions.cost}` are used consistently across tasks.
- **Prior art (mantle-shopify-connector):** pinned `graphql-java:25.0` to match it (single-runtime classpath); emit its `extensions.cost` shape; its client query-builder DSL is reference only (we're a server). Its throttle handling is a `//TODO` — we act on the cost number, not just log it.
