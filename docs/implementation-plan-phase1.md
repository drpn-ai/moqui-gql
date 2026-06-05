# moqui-gql (Phase 1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Ship a curated, read-only GraphQL query layer for Maarg OMS — **our OMS field names** with
the **Shopify query language** (`query:` search string, `sortKey`+`reverse`, Relay connections),
gated by a cost governor so no query can harm the system.

**Architecture:** A Moqui component (`moqui-gql`, this repo) exposes `/graphql`. Schema artifacts
(`graphql/*.gql.xml`) declare types→entities/view-entities, fields, edges, **service-backed**
fields (decision 12), and **declared search keys + comparators + sort keys** (Q3). At startup a
cached `GraphQLSchema` + cost model are built and the SDL is emitted (must match
`docs/schema.graphql`). Requests are parsed by `graphql-java`; the `query:` string is parsed to DB
conditions restricted to declared keys; the governor gates depth + cost; the executor runs on one
thread in one read-only transaction against a **read-replica clone** (`.useClone(true)`), batching
list edges with `DataLoader` and paginating via Relay cursors. Runtime guards (a new
`EntityFind.queryTimeout`, row cap, wall-clock) catch what the estimate missed.

**Tech Stack:** Moqui framework (Groovy/Java), `com.graphql-java:graphql-java:25.0` (matches
`mantle-shopify-connector`; + transitive `java-dataloader`), Spock 2.1.

**Contract / test source:**
- `docs/schema.graphql` — the SDL the builder must produce.
- `docs/examples.md` — every `Need → Query → Output` triple is a test case; §N + §Q3b are the
  guardrail tests; §M is the cursor-walk property test.
- `docs/design.md`, `docs/requirements.md`, `docs/shopify-alignment.md` — rationale + decisions Q1–Q5, D-A…D-D.

---

## Ground rules

- **This repo (`hotwax/moqui-gql`) is the component.** Component source (`component.xml`,
  `build.gradle`, `graphql/`, `service/`, `entity/`, `src/`) lives at the repo root alongside
  `docs/`. For build/test it is cloned/symlinked into a Moqui runtime at
  `runtime/component/moqui-gql`. Run the narrow test task only: `./gradlew :runtime:component:moqui-gql:test`.
- **Framework patch (Task 1)** lands in the `moqui-framework` fork, not this repo. Per project
  CLAUDE.md: **show a diff before saving** each framework edit.
- **Do not run a broad `./gradlew build`.** The verify steps' per-component test task counts as "asked".
- **Field/type names are our OMS data model** (D-D). **No global IDs / `Node`** (D-B). **DB-backed
  only** (Q1). **No analytics/aggregation** (Q2 deferred). **No full-text/Solr** (stays on existing endpoints).
- Package: `org.moqui.gql`. Commit after every green step.

---

## File Structure

```
# moqui-framework fork (Task 1 only):
framework/src/main/java/org/moqui/entity/EntityFind.java                ← + queryTimeout(int)
framework/src/main/groovy/org/moqui/impl/entity/EntityFindBase.groovy   ← + field + setter
framework/src/main/groovy/org/moqui/impl/entity/EntityFindBuilder.java  ← + ps.setQueryTimeout()

# this repo (component root):
component.xml · build.gradle · MoquiConf.xml
graphql/OmsSchema.gql.xml                          ← schema artifact (our names, declared search/sort keys)
service/gql/QueryServices.xml · service/gql.rest.xml
entity/GqlEntities.xml                             ← GqlQueryLog (observability)
src/main/groovy/org/moqui/gql/
  scalars/DateTimeScalar.groovy  scalars/DecimalScalar.groovy        (T2)
  SchemaArtifact.groovy  SchemaArtifactParser.groovy                 (T3)
  IndexClassifier.groovy                                             (T4)
  CostModel.groovy                                                   (T5)
  search/SearchQueryParser.groovy  search/SearchAst.groovy           (T6)  ← `query:` string → conditions
  GqlSchemaBuilder.groovy                                            (T7)  ← emits SDL, wires args/directives
  GqlToolFactory.groovy                                              (T8)
  exec/Cursor.groovy  exec/ConnectionResolver.groovy                 (T9)  ← Relay cursors + pagination
  exec/EntityBatchLoader.groovy  exec/EntityDataFetcher.groovy
  exec/ServiceBackedFetcher.groovy  exec/ScopeFilter.groovy          (T10)
  exec/ExternalIdResolvers.groovy                                    (T10) ← order(externalId:)/orderByIdentification
  govern/QueryGovernor.groovy  govern/GqlError.groovy                (T11)
  GqlEngine.groovy                                                   (T9-T12 wiring)
src/test/groovy/  MoquiSuite.groovy  + *Tests.groovy per task        (+ CatalogContractTests, T14)
```

---

## Task 0 — Component scaffold

**Files:** `component.xml`, `build.gradle`, `MoquiConf.xml`, `src/test/groovy/MoquiSuite.groovy`, `src/test/groovy/ScaffoldSmokeTests.groovy`

- [ ] **Step 1 — `component.xml`**
```xml
<?xml version="1.0" encoding="UTF-8"?>
<component name="moqui-gql" version="0.1.0"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:noNamespaceSchemaLocation="http://moqui.org/xsd/component-3.xsd"/>
```
- [ ] **Step 2 — `build.gradle`** (mirror `mantle-shopify-connector`; graphql-java 25.0)
```gradle
apply plugin: 'groovy'
sourceCompatibility = '11'; targetCompatibility = '11'
def runtimeDir = file("${projectDir}/../..")
repositories { flatDir name: 'frameworkLib', dirs: file("${projectDir}/../../../framework/lib").absolutePath; mavenCentral() }
dependencies {
  implementation project(':framework')
  implementation 'com.graphql-java:graphql-java:25.0'   // == mantle-shopify-connector; pulls java-dataloader
  testImplementation 'org.junit.platform:junit-platform-launcher:1.12.1'
  testImplementation 'org.junit.platform:junit-platform-suite:1.12.1'
  testImplementation 'org.junit.jupiter:junit-jupiter-api:5.12.1'
  testImplementation platform("org.spockframework:spock-bom:2.1-groovy-3.0")
  testImplementation 'org.spockframework:spock-core:2.1-groovy-3.0'
  testImplementation 'org.spockframework:spock-junit4:2.1-groovy-3.0'
}
test {
  useJUnitPlatform(); maxParallelForks 1; include '**/*MoquiSuite.class'
  systemProperty 'moqui.runtime', runtimeDir.absolutePath
  systemProperty 'moqui.conf', 'conf/MoquiDevConf.xml'
  systemProperty 'moqui.init.static', 'true'
  classpath += files(sourceSets.main.output.classesDirs) + files(projectDir.absolutePath)
  classpath = classpath.filter { it.exists() }
}
```
- [ ] **Step 3 — `MoquiConf.xml`** — governance defaults (calibratable knobs) + ToolFactory reg (added in T8):
```xml
<moqui-conf xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:noNamespaceSchemaLocation="http://moqui.org/xsd/moqui-conf-3.xsd">
  <default-property name="gql.maxDepth" value="6"/>
  <default-property name="gql.maxCost" value="1000"/>
  <default-property name="gql.maxFirst" value="100"/>
  <default-property name="gql.unindexedFilterPenalty" value="50"/>
  <default-property name="gql.serviceFixedCost" value="25"/>
  <default-property name="gql.queryTimeoutSeconds" value="20"/>
  <default-property name="gql.maxRowsPerLevel" value="5000"/>
  <default-property name="gql.wallClockBudgetMs" value="30000"/>
  <default-property name="gql.useClone" value="true"/>
</moqui-conf>
```
- [ ] **Step 4 — `MoquiSuite.groovy` + `ScaffoldSmokeTests.groovy`** — boot EC; assert component loaded + `Class.forName("graphql.schema.GraphQLSchema")` resolves (graphql-java 25.0 on classpath).
- [ ] **Step 5 — Run:** `./gradlew :runtime:component:moqui-gql:test` → PASS.
- [ ] **Step 6 — Commit** `feat: scaffold moqui-gql component (graphql-java 25.0, test harness)`.

> **Read-replica routing (Q1/decision 9) needs no code** — config only: add a `transactional#clone1`
> `<datasource>` (read replica) under `<entity-facade>` in the deployment conf (pattern in
> `MoquiDefaultConf.xml`). The executor calls `.useClone(true)`; dev/test without a replica falls
> back to the base group automatically.

## Task 1 — Framework patch: `EntityFind.queryTimeout`

Adds the runtime-guard primitive (absent today). **Show a diff before saving each framework edit.**

- [ ] **Step 1 — failing test** (`QueryTimeoutTests`): `ec.entity.find("moqui.security.UserAccount").queryTimeout(30).setMaxRows(1).list()` returns without error.
- [ ] **Step 2 — run → FAIL** (no such method).
- [ ] **Step 3 — `EntityFind.java`** (after `maxRows`, ~line 251): `EntityFind queryTimeout(int queryTimeout); int getQueryTimeout();`
- [ ] **Step 4 — `EntityFindBase.groovy`**: field `protected Integer queryTimeout = (Integer) null` (~:83); setter+getter (~:638): `@Override EntityFind queryTimeout(int q){ this.queryTimeout=q; return this }` / `@Override int getQueryTimeout(){ queryTimeout!=null?queryTimeout:0 }`.
- [ ] **Step 5 — `EntityFindBuilder.java` `makePreparedStatement()`** (after `setMaxRows`, ~:780): `int qt = entityFindBase.getQueryTimeout(); if (qt > 0) ps.setQueryTimeout(qt);`
- [ ] **Step 6 — run → PASS. Step 7 — Commit** (framework: `feat(entity): EntityFind.queryTimeout → PreparedStatement.setQueryTimeout`; component: test).

## Task 2 — Custom scalars `DateTime`, `Decimal`

graphql-java `Coercing` implementations (used by the SDL contract).

- [ ] **Step 1 — failing test** (`ScalarTests`): `DateTimeScalar` serializes a `java.sql.Timestamp` → ISO-8601 string `2026-05-14T09:32:00Z`; `DecimalScalar` serializes `BigDecimal("129.00")` → `"129.00"` (string, precision preserved) and parses back.
- [ ] **Step 2 — run → FAIL.**
- [ ] **Step 3 — implement** `DateTimeScalar`/`DecimalScalar` as `GraphQLScalarType` with `Coercing` (serialize/parseValue/parseLiteral). Decimal serializes to String to preserve precision.
- [ ] **Step 4 — run → PASS. Step 5 — Commit.**

## Task 3 — Schema artifact format + parser

`graphql/*.gql.xml` declares the curated graph in **our field names**, plus the **declared search
keys/comparators**, **sort keys**, **service-backed** fields, and **view-entity** types.

- [ ] **Step 1 — author `graphql/OmsSchema.gql.xml`** (fixture + real contract). Element shape:
```xml
<gql-schema>
  <gql-type name="Order" entity-name="org.apache.ofbiz.order.order.OrderHeader">
    <field name="orderId" entity-field="orderId"/>
    <field name="orderName" entity-field="orderName"/>
    <field name="statusId" entity-field="statusId"/>
    <field name="orderDate" entity-field="orderDate" type="DateTime"/>
    <field name="grandTotal" entity-field="grandTotal" type="Decimal"/>
    <field name="currencyUomId" entity-field="currencyUomId"/>
    <field name="customerName" resolver-service="org.moqui.gql.Resolvers.get#OrderCustomerName" resolver-in="orderId"/>
    <edge name="orderItems" entity-relationship="items" target-type="OrderItem" list="true"/>
    <edge name="billingAddress" target-type="PostalAddress" list="false"
          resolver-service="org.moqui.gql.Resolvers.get#OrderBillingAddress" resolver-in="orderId"/>
    <!-- root entry points + declared search/sort grammar (Q3) -->
    <root-query name="order" by-pk="true" pk-arg="orderId" external-id="true"/>
    <root-query name="orders" list="true"
        search-keys="orderId:eq,in externalId:eq,in orderName:eq statusId:eq,in orderDate:gt,gte,lt,lte customerPartyId:eq,in productStoreId:eq,in"
        sort-keys="ORDER_DATE:orderDate ORDER_NAME:orderName GRAND_TOTAL:grandTotal ORDER_ID:orderId"/>
  </gql-type>
  <gql-type name="OrderItem" entity-name="org.apache.ofbiz.order.order.OrderItem"> ... </gql-type>
  <!-- view-entity-backed type: entity-name names a view-entity (decision 12) -->
  <gql-type name="ReadyToPickOrder" entity-name="co.hotwax.gorjana.ReadyToPickWarehouseOrder"> ... </gql-type>
</gql-schema>
```
- [ ] **Step 2 — failing test** (`SchemaArtifactParserTests`): parse the above MNode; assert types/fields/edges; `orderId` field; `customerName.isServiceBacked()`; edge `orderItems.list`; root `orders` parsed with search keys map (`statusId -> [eq,in]`, `orderDate -> [gt,gte,lt,lte]`) and sort keys map (`ORDER_DATE -> orderDate`).
- [ ] **Step 3 — run → FAIL.**
- [ ] **Step 4 — implement** `SchemaArtifact` model (`GqlType{name,entityName,fields,edges}`, `GqlField{name,entityField,type,resolverService,resolverIn,isServiceBacked()}`, `GqlEdge{name,entityRelationship,targetType,list,resolverService,resolverIn}`, `GqlRootQuery{name,byPk,pkArg,externalId,list,searchKeys:Map<String,Set<Comparator>>,sortKeys:Map<String,String>}`) + `SchemaArtifactParser` (MNode → model; parse `search-keys`/`sort-keys` mini-syntax).
- [ ] **Step 5 — run → PASS. Step 6 — Commit.**

## Task 4 — Index classifier

Auto-derive index-backed fields from `EntityDefinition` (PK + `<index>`); used to validate every
declared search key is index-backed and to penalize/forbid unindexed keys.

- [ ] **Steps** — TDD `IndexClassifier(ec).indexedFields(entityName)` returns PK + declared index fields (test: `UserAccount` → contains `userId`, not `userFullName`). Cache per process. Commit.

## Task 5 — Cost model + `FieldComplexityCalculator`

- [ ] **Step 1 — failing test** (`CostModelTests`): scalar = 1; list edge = `first * (1 + childComplexity)` (fan-out); service-backed field = `serviceFixedCost (25) + childComplexity`; unindexed search key adds penalty.
- [ ] **Step 2 → FAIL. Step 3 — implement** `CostModel implements graphql.analysis.FieldComplexityCalculator` (`calculate(env, childComplexity)`): list edges multiply by `first` (default `maxFirst`); `serviceBackedFields` set → `serviceFixedCost`; expose `listEdgeByField`, `serviceFixedCost`, `unindexedPenalty` (populated by the builder). **Step 4 → PASS. Step 5 — Commit.**

## Task 6 — `query:` search-string parser (D-A)

Pure function: Shopify search syntax → condition AST, **restricted to declared keys + comparators**
(Q3). Great for TDD; the guardrail tests come straight from `examples.md` §Q3b/§N2.

- [ ] **Step 1 — failing tests** (`SearchQueryParserTests`) from the catalog:
  - `"statusId:ORDER_APPROVED,ORDER_HELD orderDate:>=2026-05-01 orderDate:<=2026-05-31"` → conditions: `statusId IN [...]`, `orderDate >= ...`, `orderDate <= ...` (Q3a).
  - `"statusId:>ORDER_APPROVED"` with declared `statusId:[eq,in]` → throws `OPERATOR_NOT_ALLOWED` (key=statusId, allowed=[:,in]) (Q3b).
  - `"orderName2:Gift"` (undeclared) → `FIELD_NOT_FILTERABLE` (N2).
  - quoting: `'note:"gift order"'` keeps the phrase; comma = IN; `>`,`>=`,`<`,`<=` comparators.
- [ ] **Step 2 → FAIL. Step 3 — implement** `SearchQueryParser.parse(queryString, GqlRootQuery)` → `SearchAst` (list of `{key, comparator, values}`), validating each term against the root's declared `searchKeys`; map to `EntityCondition` in the executor. Throw typed `GqlError` (code + key + allowed) on violations. **Step 4 → PASS. Step 5 — Commit.**

## Task 7 — Schema builder → `GraphQLSchema` (emits SDL matching `schema.graphql`)

Builds the graphql-java schema in **our names** with the **Shopify query-language shape**:
connection/edge/`PageInfo` types, root list args `query/sortKey/reverse/first/after/last/before`,
`DateTime`/`Decimal` scalars, sortKey enums, and `@search`/`@service`/`@cost` directives. Wires the
cost model (`listEdgeByField`, `serviceBackedFields`, search-key index flags via `IndexClassifier`).

- [ ] **Step 1 — failing test** (`GqlSchemaBuilderTests`): build from `OmsSchema.gql.xml`; assert
  `schema.getObjectType("OrderConnection")`, `OrderEdge`, `PageInfo` with `hasPreviousPage`/`startCursor`;
  `Query.orders` has args `query, sortKey (OrderSortKey enum), reverse, first, after, last, before`;
  `Order.orderItems` returns `OrderItemConnection`; scalar `DateTime` registered.
- [ ] **Step 2 — contract test:** `new SchemaPrinter().print(schema)` ⊇ the types/fields in
  `docs/schema.graphql` (assert key types/args present; this keeps the build and the contract in sync).
- [ ] **Step 3 → FAIL. Step 4 — implement** `GqlSchemaBuilder.build(artifact) -> BuiltSchema{schema, costModel, artifact}`:
  generate object types (scalar fields via `type` attr → DateTime/Decimal/String), connection+edge+PageInfo
  per list edge/root, root Query with the full arg set + sortKey enums, attach directives, populate cost model.
- [ ] **Step 5 → PASS. Step 6 — Commit.**

## Task 8 — ToolFactory: build + cache at startup

- [ ] **Steps** — `GqlToolFactory implements ToolFactory<BuiltSchema>`: at `init`, scan every component's
  `graphql/*.gql.xml`, parse, build once, cache. `getInstance()` returns the cached `BuiltSchema` (same
  instance each call). Register in `MoquiConf.xml` `<tools>`. TDD: cached instance identity + `Order` type present. Commit.

## Task 9 — Relay connections + cursors + executor (Q4)

The pagination engine: stable cursors, `first/after/last/before`, full `PageInfo`, DataLoader-batched
edges, single read-only transaction, `.useClone(true)`.

- [ ] **Step 1 — failing tests** (`ConnectionTests`) = the §M cursor walk from `examples.md`:
  - page 1 (`first:2`) then page 2 (`after: endCursor`): **no overlap, no skip**; `union(pages) == unpaged set`.
  - `hasNextPage` false on final page; `hasPreviousPage` true on page 2; `startCursor`/`endCursor` correct.
  - **stability:** sort by `orderDate` with `orderId` PK tiebreaker; inserting a row ahead of the cursor mid-walk does NOT cause a re-seen id.
  - backward paging (`last`/`before`) mirrors forward.
- [ ] **Step 2 → FAIL. Step 3 — implement**
  - `Cursor` — opaque base64 of `(sortKeyValue, pk)`; `encode/decode`.
  - `ConnectionResolver` — translate `(query, sortKey, reverse, first/after/last/before)` into an
    `EntityFind` (`orderBy [sortField, pk]`, `WHERE (sortField,pk) > cursor`, `limit first+1` to compute
    `hasNextPage`), `.useClone(useClone)`, `.queryTimeout(...)`, `.maxRows(maxRowsPerLevel)`; build edges+PageInfo.
  - `EntityBatchLoader` (DataLoader batch: `WHERE parentKey IN (...)`) for nested list edges; `EntityDataFetcher` wiring.
  - Run the whole request in `ec.transaction.callUseOrBegin(null, { graphQL.execute(input) })` (decision 10), DataLoader sync dispatch.
- [ ] **Step 4 → PASS. Step 5 — Commit.**

## Task 10 — Resolvers: entity / view-entity / service-backed (decision 12) + external-id (Q5)

- [ ] **Step 1 — failing tests** from `examples.md`:
  - **A1** order detail (entity + edges + billingAddress + service-backed `customerName`/`fulfillmentStatus`).
  - **decision 12:** `fulfillmentStatus` resolves via its service, **batched** across list items; carries `serviceFixedCost`.
  - **view-entity type:** `ReadyToPickOrder` (B3) resolves from its view-entity; deployment-only search keys (`brandName`,`isGift`).
  - **Q5:** `order(externalId:)` (J1), `orderByIdentification(...)` (J2), `orders(query:"externalId:a,b")` batch (J3), `facility(externalId:)` (J4); `identifications` edge.
- [ ] **Step 2 → FAIL. Step 3 — implement** `EntityDataFetcher` (scalar), `ServiceBackedFetcher`
  (DataLoader-batched service call, high fixed cost), `ScopeFilter` (phase-1 no-op seam, decision 11),
  `ExternalIdResolvers` (`externalId` find + `OrderIdentification` lookup + `identifications` edge).
  View-entity types need no special path (EntityFind runs on views). Resolve relationship key-maps via
  `EntityDefinition.getRelationshipInfo` (do **not** assume child FK == parent PK — verify against real mantle/ofbiz relationships; add a test for a differing FK).
- [ ] **Step 4 → PASS. Step 5 — Commit.**

## Task 11 — Query governor: gate + runtime guards + structured errors

- [ ] **Step 1 — failing tests** = `examples.md` §N + §Q3b:
  - depth bomb → `DEPTH_EXCEEDED`; fan-out bomb → `COST_EXCEEDED`; missing `first/last` → `FIRST_REQUIRED`;
    undeclared key → `FIELD_NOT_FILTERABLE`; disallowed comparator → `OPERATOR_NOT_ALLOWED`.
  - each: `data:null`, structured `extensions.code`, message names the offending key/limit, **nothing hits the DB**.
- [ ] **Step 2 → FAIL. Step 3 — implement** `QueryGovernor`:
  - structural/cost gate via `graphql-java` `MaxQueryDepthInstrumentation` + `MaxQueryComplexityInstrumentation(maxCost, costModel)` (decision 8).
  - query-grammar validation via `SearchQueryParser` (T6) before execution.
  - runtime guards: per-request `queryTimeout` on every find, row cap (`EntityBatchLoader`/`ConnectionResolver`), wall-clock budget (record elapsed; hard interrupt deferred to phase 2 — note).
  - `GqlError` → graphql-java `GraphQLError` with stable `extensions.code` (Shopify-style; `THROTTLED` reserved for phase-2 rate budget).
- [ ] **Step 4 → PASS. Step 5 — Commit.**

## Task 12 — `/graphql` endpoint + `extensions.cost`

- [ ] **Steps** — `service/gql/QueryServices.xml` `execute#Query` (in: `query`,`variables`,`operationName`; out: `data`,`errors`,`extensions`) calling `GqlEngine`; `get#Sdl` returns `SchemaPrinter` output. `service/gql.rest.xml` mounts `POST /graphql`. Engine populates `extensions.cost` Shopify-shaped (`requestedQueryCost`,`actualQueryCost`,`throttleStatus{maximumAvailable,currentlyAvailable,restoreRate}`; static in phase 1). TDD via `ec.service.sync()` (examples §A2) asserting `extensions.cost.requestedQueryCost` present. Commit.

## Task 13 — Observability log

- [ ] **Steps** — `entity/GqlEntities.xml` `GqlQueryLog` (queryLogId, userId, callerProfile, queryText, estimatedCost, rowsFetched, durationMs, verdict, rejectReason, queryDate). Engine writes one row per request in a `requireNewTransaction(true)` (never fail the user's request; `ec.logger.warn` on log failure). TDD: a run writes a row with `verdict=ALLOWED` + durationMs. Commit.

## Task 14 — Catalog contract + adversarial test suite

Turn `examples.md` into the executable test suite — this is the payoff of the test-case catalog.

- [ ] **Step 1** — `CatalogContractTests` (+ data fixtures): for each catalog example A–J, L, run the
  exact query and assert the documented `Output` shape (key fields/edges/pageInfo). `@Unroll` over a table.
- [ ] **Step 2** — `AdversarialQueryTests` — §N1–N4 + §Q3b all rejected with the exact `extensions.code`.
- [ ] **Step 3** — `ConnectionWalkTests` — §M property test (no overlap/no skip/same-set + insert-ahead stability).
- [ ] **Step 4 — run the full suite** `./gradlew :runtime:component:moqui-gql:test` → all PASS. **Commit.**

---

## Deferred (NOT in this plan)

- Leaky-bucket per-caller rate budget (`THROTTLED`, cost score already a real number) — phase 2.
- Persisted/allow-listed queries (`graphql-java` `PersistedQuerySupport`) — phase 2.
- Per-caller field/type visibility + party/row-scope (`ScopeFilter` population, decision 11) — phase 2.
- Hard wall-clock interruption (phase 1 records elapsed; relies on `queryTimeout`).
- **Analytics/aggregation (Q2)** — until user-group usage examples.
- **Full-text/faceted Solr search (Q1)** — stays on existing endpoints.
- **Global IDs / `Node`/`node()` (D-B)** — not built; raw ids.
- DataDocument-backed (type B) heavy types — schema layer supports; wire when needed.

## Self-review notes

- **Decisions covered:** Q1 DB-only (whole plan; useClone T0/T9) · Q2 analytics deferred (out) · Q3
  declare-and-control (T3 search-keys, T6 parser, T11 gate) · Q4 Relay connections (T9) · Q5 external-id
  (T10) · D-A `query:` string (T6) · D-B raw ids (no GID anywhere) · D-C `sortKey`+`reverse` (T3/T7/T9) ·
  D-D our field names (T3 artifact, T7 builder, validated against `schema.graphql`). Decision 12
  service-backed + view-entity types (T3/T5/T10). graphql-java 25.0 (T0). Cost envelope (T12). queryTimeout (T1).
- **Contract-bound:** T7 asserts the built SDL ⊇ `docs/schema.graphql`; T14 runs `examples.md` as tests —
  so the docs and the code can't silently drift.
- **Soft spots flagged for build-time verification:** relationship key-map resolution (T10 — use
  `getRelationshipInfo`, don't assume FK==PK), conf-property accessor (T0/T11 — use the real Moqui lookup),
  graphql-java 25.0 instrumentation API + JDK floor (T0 verify), hard wall-clock interrupt (deferred).
- **Type consistency:** `BuiltSchema{schema,costModel,artifact}`, `CostModel.calculate/serviceFixedCost`,
  `SearchQueryParser.parse → SearchAst`, `Cursor.encode/decode`, `ConnectionResolver`,
  `GqlEngine.execute(query,variables,operationName,callerProfile) → {data,errors,extensions}`.
