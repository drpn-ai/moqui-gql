# Order.orderItemCount (lazy LATERAL aggregate field) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the service-backed `Order.itemCount` with `Order.orderItemCount: Int` = `COUNT(DISTINCT OrderItem.externalId)` per order — the number of **Shopify order lines** (exploded OMS rows share the Shopify line id in `externalId`) — resolved by a **lazy** SQL aggregate added to the order query **only when the field is selected**.

**Architecture:** A new declarative **aggregate-field** kind. The field renders as an ordinary scalar (no schema-builder or fetcher change — the value rides in as a column). When it's in the selection, the Order root resolvers build an `EntityDynamicView` = `OrderHeader` (alias-all) + a `sub-select="true"` `OrderItem` member with a `count-distinct(externalId)` alias correlated on `orderId`. On `mysql8` (`from-lateral-style="lateral"`) Moqui emits a page-bounded **LATERAL** subquery (confirmed in `EntityFindBuilder.makeSqlMemberSubSelect`), so the count is one scalar per order, `0` for orders with no/no-Shopify items, no GROUP BY.

**Tech stack:** Moqui `EntityDynamicView`, moqui-gql schema artifact, graphql-java selection set, Spock 2.1 vs MySQL `hcsd_notnaked`.

**Tracking:** #37. **No DB/index change** (uses `OrderItem`'s `order_id` PK-leading index). **mysql8/LATERAL-dependent** (documented caveat).

---

## File structure

| File | Repo | Change |
|---|---|---|
| `src/main/groovy/org/moqui/gql/SchemaArtifact.groovy` (`GqlField`) | moqui-gql | add aggregate metadata + `isAggregate()` |
| `src/main/groovy/org/moqui/gql/SchemaArtifactParser.groovy` | moqui-gql | parse `aggregate*` attributes |
| `src/main/groovy/org/moqui/gql/exec/AggregateViewBuilder.groovy` (NEW) | moqui-gql | build the dynamic-view find with LATERAL sub-select member(s) |
| `src/main/groovy/org/moqui/gql/exec/ConnectionResolver.groovy` | moqui-gql | use the dynamic-view find when aggregate fields are requested |
| `src/main/groovy/org/moqui/gql/GqlEngine.groovy` | moqui-gql | root-list + by-pk fetchers detect requested aggregate fields from the selection and pass them down |
| `src/main/groovy/org/moqui/gql/GovernorInstrumentation.groovy` | moqui-gql | cost: an aggregate field = `aggregateFieldCost` (not a free scalar) |
| `MoquiConf.xml` | moqui-gql | `gql.aggregateFieldCost` default |
| `graphql/OmsSchema.gql.xml` | moqui-gql | **+`orderItemCount`**, **−`itemCount`** |
| `service/GqlExampleServices.xml` | moqui-gql | **−`get#OrderItemCount`** |
| `src/test/groovy/OrderItemCountTests.groovy` (NEW) | moqui-gql | end-to-end (list + by-pk + 0 case + distinct semantics) |
| `src/test/groovy/ServiceBackedLoaderTests.groovy` (NEW) | moqui-gql | keep the service-backed **capability** regression-covered |
| `src/test/groovy/ServiceBackedTests.groovy` (REMOVE) | moqui-gql | (tested the now-removed `itemCount`) |
| `src/test/groovy/MoquiSuite.groovy` | moqui-gql | register `OrderItemCountTests`, `ServiceBackedLoaderTests`; drop `ServiceBackedTests` |
| `docs/{schema.graphql,examples.md,STATUS.md,design.md}` | moqui-gql | reflect `orderItemCount` |

---

## Task 1: aggregate-field metadata + parsing

**Files:** `SchemaArtifact.groovy` (the `GqlField` class), `SchemaArtifactParser.groovy`, `src/test/groovy/SchemaArtifactParserTests.groovy`

- [ ] **Step 1 — failing test** in `SchemaArtifactParserTests`:
```groovy
def "parses an aggregate field"() {
    when:
    def art = new org.moqui.gql.SchemaArtifactParser().parse([ new org.moqui.util.MNode(
        '<gql-schema><gql-type name="Order" entity-name="org.apache.ofbiz.order.order.OrderHeader">' +
        '<field name="orderItemCount" type="Int" aggregate="count-distinct" ' +
        'aggregate-entity="org.apache.ofbiz.order.order.OrderItem" aggregate-fk="orderId" aggregate-field="externalId"/>' +
        '</gql-type></gql-schema>') ])
    def f = art.types.get("Order").fields.get("orderItemCount")
    then:
    f.isAggregate()
    f.aggregateFunction == "count-distinct"
    f.aggregateEntity == "org.apache.ofbiz.order.order.OrderItem"
    f.aggregateFk == "orderId"
    f.aggregateField == "externalId"
    !f.isServiceBacked()
}
```
- [ ] **Step 2 — run, expect FAIL** (no such properties):
`./gradlew :runtime:component:moqui-gql:test --tests SchemaArtifactParserTests` → FAIL (`isAggregate`/`aggregate*` unknown).
- [ ] **Step 3 — add the metadata.** In `SchemaArtifact.groovy`, add to `class GqlField`:
```groovy
    String aggregateFunction      // e.g. "count-distinct", "count", "sum"
    String aggregateEntity        // fully-qualified child entity, e.g. org.apache.ofbiz.order.order.OrderItem
    String aggregateFk            // child field that joins to the parent PK, e.g. orderId
    String aggregateField         // child field the function is applied to, e.g. externalId
    boolean isAggregate() { return aggregateFunction != null && !aggregateFunction.isEmpty() }
```
In `SchemaArtifactParser.parse`, extend the `new GqlField(...)` map (the field loop, ~line 16) with:
```groovy
                            aggregateFunction: fn.attribute("aggregate"),
                            aggregateEntity: fn.attribute("aggregate-entity"),
                            aggregateFk: fn.attribute("aggregate-fk"),
                            aggregateField: fn.attribute("aggregate-field")))
```
- [ ] **Step 4 — run, expect PASS.**
- [ ] **Step 5 — commit:** `git add src/main/groovy/org/moqui/gql/SchemaArtifact.groovy src/main/groovy/org/moqui/gql/SchemaArtifactParser.groovy src/test/groovy/SchemaArtifactParserTests.groovy && git commit -m "feat(gql): aggregate-field metadata + parsing (#37)"`

---

## Task 2: AggregateViewBuilder (the dynamic-view find)

**Files:** `src/main/groovy/org/moqui/gql/exec/AggregateViewBuilder.groovy` (NEW)

The 7-arg `addMemberEntity(...subSelect)` and `addAlias(alias,name,field,function)` are on `EntityDynamicViewImpl` (not all on the interface), so type the view as the impl.

- [ ] **Step 1 — create the file:**
```groovy
package org.moqui.gql.exec

import groovy.transform.CompileStatic
import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityFind
import org.moqui.gql.GqlField
import org.moqui.impl.entity.EntityDynamicViewImpl

/**
 * Builds an EntityFind over a dynamic view: the base entity (alias-all, alias PRIME) plus one
 * sub-select member per requested aggregate field. Each aggregate member is `sub-select="true"`,
 * join-optional, correlated on the fk; on a lateral-capable DB (mysql8: from-lateral-style="lateral")
 * Moqui renders it as a page-bounded LATERAL subquery, so e.g. orderItemCount becomes
 * `LEFT JOIN LATERAL (SELECT COUNT(DISTINCT external_id) AS order_item_count FROM order_item
 *  WHERE order_id = PRIME.order_id) ON 1=1` — one scalar per row, 0 over empty.
 *
 * Conditions/ordering/keyset still operate on the base fields, which alias-all exposes under their own
 * names; the aggregate value comes back as the `<field name>` column on each row's map.
 */
@CompileStatic
class AggregateViewBuilder {
    static EntityFind aggregateFind(ExecutionContext ec, String baseEntity, List<GqlField> aggFields) {
        EntityFind ef = ec.entity.find(baseEntity)
        EntityDynamicViewImpl dv = (EntityDynamicViewImpl) ef.makeEntityDynamicView()
        dv.addMemberEntity("PRIME", baseEntity, (String) null, (Boolean) null, (Map<String, String>) null)
        dv.addAliasAll("PRIME", (String) null)
        int i = 0
        for (GqlField af in aggFields) {
            String alias = "AGG" + (i++)
            Map<String, String> keyMap = new LinkedHashMap<String, String>()
            keyMap.put(af.aggregateFk, af.aggregateFk)   // child.fk = PRIME.fk  (e.g. orderId = orderId)
            dv.addMemberEntity(alias, af.aggregateEntity, "PRIME", Boolean.TRUE, keyMap,
                    (List<Map<String, String>>) null, "true")             // sub-select -> LATERAL on mysql8
            dv.addAlias(alias, af.name, af.aggregateField, af.aggregateFunction)  // orderItemCount = count-distinct(externalId)
        }
        dv.setEntityName(baseEntity.replace((char) '.', (char) '_') + "_Agg")
        return ef
    }
}
```
- [ ] **Step 2 — compile check** (no isolated unit test; exercised end-to-end in Task 5):
`./gradlew :runtime:component:moqui-gql:compileGroovy` → BUILD SUCCESSFUL.
- [ ] **Step 3 — commit:** `git add src/main/groovy/org/moqui/gql/exec/AggregateViewBuilder.groovy && git commit -m "feat(gql): AggregateViewBuilder — dynamic view with LATERAL sub-select (#37)"`

---

## Task 3: wire aggregate resolution into the root resolvers (lazy detection)

**Files:** `ConnectionResolver.groovy`, `GqlEngine.groovy`

- [ ] **Step 1 — `ConnectionResolver.resolveRoot` takes the requested aggregate fields and builds the right find.**
Change the signature:
```groovy
    Map resolveRoot(GqlRootQuery q, GqlType type, Map<String, Object> args, List<GqlField> aggFields) {
```
and replace the find construction (currently `EntityFind ef = ec.entity.find(entityName)`, ~line 120) with:
```groovy
        EntityFind ef = (aggFields == null || aggFields.isEmpty()) ?
                ec.entity.find(entityName) :
                AggregateViewBuilder.aggregateFind(ec, entityName, aggFields)
```
Everything else (conditions, ordering, keyset, `ev.getMap()`) is unchanged — they reference field names, which `alias-all PRIME` exposes; `getMap()` now also carries the aggregate column. (`ed`/`pkFields` keep coming from the **base** entity definition — correct, since the dynamic view aliases the same field names/types.)

- [ ] **Step 2 — `GqlEngine`: a helper to read requested aggregate fields from the selection.** Add to `GqlEngine`:
```groovy
    /** Aggregate fields of `tt` that appear in this selection (lazy: only these get a sub-select member).
     *  Connection roots select under edges/node/<f>; by-pk/by-id select <f> directly. */
    private static List<GqlField> requestedAggregates(GqlType tt, graphql.schema.DataFetchingEnvironment env, boolean connection) {
        List<GqlField> out = new ArrayList<GqlField>()
        if (tt == null) return out
        def sel = env.getSelectionSet()
        for (GqlField f in tt.fields.values()) {
            if (!f.isAggregate()) continue
            if (sel.contains(connection ? ("edges/node/" + f.name) : f.name)) out.add(f)
        }
        return out
    }
```
- [ ] **Step 3 — root list fetcher** (`withFetchers`, the `rq.list` branch): pass the requested aggregates:
```groovy
                        ({ DataFetchingEnvironment env ->
                            new ConnectionResolver(ec, useClone, queryTimeoutSeconds, maxFirst)
                                    .resolveRoot(rq, tt, env.getArguments(), requestedAggregates(tt, env, true))
                        } as DataFetcher))
```
- [ ] **Step 4 — by-pk fetcher** (`withFetchers`, the `rq.byPk` branch): build the find via the aggregate view when requested. Replace `def find = ec.entity.find(entityName)` with:
```groovy
                            List<GqlField> aggs = requestedAggregates(tt, env, false)
                            def find = aggs.isEmpty() ? ec.entity.find(entityName) :
                                    AggregateViewBuilder.aggregateFind(ec, entityName, aggs)
```
(`tt` is in scope as the root query's target type; add `import org.moqui.gql.exec.AggregateViewBuilder` and `import graphql.schema.DataFetchingEnvironment` if not already imported. The `byIdentification` and nested-Order paths are out of v1 scope — they return `orderItemCount: null`; note in docs.)
- [ ] **Step 5 — compile:** `./gradlew :runtime:component:moqui-gql:compileGroovy` → BUILD SUCCESSFUL.
- [ ] **Step 6 — commit:** `git add src/main/groovy/org/moqui/gql/exec/ConnectionResolver.groovy src/main/groovy/org/moqui/gql/GqlEngine.groovy && git commit -m "feat(gql): resolve aggregate fields via dynamic view when selected (#37)"`

---

## Task 4: cost model — an aggregate field is not free

**Files:** `MoquiConf.xml`, `GqlEngine.groovy`, `GovernorInstrumentation.groovy`

- [ ] **Step 1 — config default.** In `MoquiConf.xml` add: `<default-property name="gql.aggregateFieldCost" value="5"/>`
- [ ] **Step 2 — thread it through.** In `GqlEngine` `govCfg` map add: `aggregateFieldCost: (sysOr("gql.aggregateFieldCost", "5")) as int`. In `GovernorInstrumentation`: add field `final int aggregateFieldCost`; in the constructor `this.aggregateFieldCost = (cfg.aggregateFieldCost != null ? cfg.aggregateFieldCost : 5)`.
- [ ] **Step 3 — charge it.** In `Walk.fieldCost`, in the non-root service-backed-field block (where `gf` is resolved: `GqlField gf = (!atRoot) ? art.types.get(parentType.getName())?.fields?.get(field.getName()) : null`), before the service check add:
```groovy
            if (gf != null && gf.isAggregate()) return (long) aggregateFieldCost
```
- [ ] **Step 4 — test** (`CostModelTests` or `GovernorTests`): a query selecting `orderItemCount` costs `aggregateFieldCost` more than the same query without it. Run the relevant suite → PASS.
- [ ] **Step 5 — commit:** `git add MoquiConf.xml src/main/groovy/org/moqui/gql/GqlEngine.groovy src/main/groovy/org/moqui/gql/GovernorInstrumentation.groovy src/test/groovy/CostModelTests.groovy && git commit -m "feat(gql): cost weight for aggregate fields (#37)"`

---

## Task 5: schema swap + end-to-end tests

**Files:** `graphql/OmsSchema.gql.xml`, `src/test/groovy/OrderItemCountTests.groovy` (NEW), `MoquiSuite.groovy`

- [ ] **Step 1 — data pre-check** (record a verification order). In Workbench:
```sql
USE hcsd_notnaked;
SELECT order_id, COUNT(*) rows, COUNT(DISTINCT external_id) lines
FROM order_item GROUP BY order_id ORDER BY rows DESC LIMIT 5;
```
Note an `orderId` and its `lines`. (The test derives the expected value itself, so this is just a sanity look.)
- [ ] **Step 2 — schema swap** in `graphql/OmsSchema.gql.xml`. Remove the `itemCount` field block and add:
```xml
        <!-- aggregate field (lazy LATERAL): number of distinct Shopify order lines. Exploded OMS items
             from one Shopify line share OrderItem.externalId, so COUNT(DISTINCT externalId) = line count.
             Resolved by a sub-select member added to the dynamic view only when this field is selected. -->
        <field name="orderItemCount" type="Int"
               aggregate="count-distinct" aggregate-entity="org.apache.ofbiz.order.order.OrderItem"
               aggregate-fk="orderId" aggregate-field="externalId"/>
```
- [ ] **Step 3 — failing/then-passing test** `OrderItemCountTests.groovy`:
```groovy
import spock.lang.Specification
import spock.lang.Shared
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.gql.GqlEngine

/** orderItemCount = COUNT(DISTINCT OrderItem.externalId) per order (Shopify-line count), resolved as a
 *  lazy LATERAL aggregate. Expected values are computed straight from the DB so this is self-checking. */
class OrderItemCountTests extends Specification {
    @Shared ExecutionContext ec
    @Shared String anOrderId
    @Shared long expectedLines

    def setupSpec() {
        ec = Moqui.getExecutionContext(); ec.artifactExecution.disableAuthz()
        def oh = ec.entity.find("org.apache.ofbiz.order.order.OrderHeader")
                .selectField("orderId").orderBy("orderId").maxRows(1).fetchSize(1).list()
        if (oh) {
            anOrderId = oh[0].orderId
            def items = ec.entity.find("org.apache.ofbiz.order.order.OrderItem")
                    .condition("orderId", anOrderId).selectField("externalId").list()
            Set<Object> distinct = new HashSet<>()
            for (it in items) if (it.externalId != null) distinct.add(it.externalId)
            expectedLines = (long) distinct.size()
        }
    }
    def cleanupSpec() { if (ec != null) { ec.artifactExecution.enableAuthz(); ec.destroy() } }

    def "order(orderId:) returns orderItemCount = distinct externalId count"() {
        given: org.junit.jupiter.api.Assumptions.assumeTrue(anOrderId != null)
        when:
        def r = new GqlEngine(ec).execute('query Q($id:ID!){ order(orderId:$id){ orderId orderItemCount } }', [id: anOrderId], "Q")
        then:
        r.errors.isEmpty()
        r.data.order.orderId == anOrderId
        (r.data.order.orderItemCount as long) == expectedLines
    }

    def "orders list resolves orderItemCount as a non-null Int per node"() {
        when:
        def r = new GqlEngine(ec).execute('query { orders(first:5){ edges{ node{ orderId orderItemCount } } } }', [:], null)
        then:
        r.errors.isEmpty()
        r.data.orders.edges.every { it.node.orderItemCount != null && (it.node.orderItemCount as long) >= 0 }
    }

    def "orderItemCount is omitted from the query when not selected (no aggregate, still works)"() {
        when:
        def r = new GqlEngine(ec).execute('query { orders(first:5){ edges{ node{ orderId } } } }', [:], null)
        then:
        r.errors.isEmpty()
        r.data.orders.edges.every { it.node.orderId != null }
    }
}
```
- [ ] **Step 4 — register in `MoquiSuite.groovy`**: add `OrderItemCountTests.class` to `@SelectClasses`.
- [ ] **Step 5 — run, expect PASS:** `./gradlew :runtime:component:moqui-gql:test --tests OrderItemCountTests` → PASS (3 features; by-pk skips via `assumeTrue` only if the DB has zero orders — it doesn't).
- [ ] **Step 6 — commit:** `git add graphql/OmsSchema.gql.xml src/test/groovy/OrderItemCountTests.groovy src/test/groovy/MoquiSuite.groovy && git commit -m "feat(gql): Order.orderItemCount aggregate field; drop itemCount (#37)"`

---

## Task 6: retire `get#OrderItemCount`; keep the service-backed capability covered

**Files:** `service/GqlExampleServices.xml`, `src/test/groovy/ServiceBackedLoaderTests.groovy` (NEW), `src/test/groovy/ServiceBackedTests.groovy` (REMOVE), `MoquiSuite.groovy`

`itemCount` was the only service-backed field, so `ServiceBackedTests` (end-to-end via `itemCount`) no longer has a schema field to exercise. Keep the **capability** (engine untouched) via a focused unit test of `ServiceBackedLoader`.

- [ ] **Step 1 — delete** the `<service verb="get" noun="OrderItemCount" …>` block from `service/GqlExampleServices.xml` (keep `ensure#TestShipments`). Leave the service-backed engine code (`ServiceBackedLoader`, the `resolver-service` field-kind, the governor service-field branch) **unchanged**.
- [ ] **Step 2 — add `ServiceBackedLoaderTests.groovy`** — exercises the loader directly with a stub service so the kind stays regression-covered without a production field:
```groovy
import spock.lang.Specification
import spock.lang.Shared
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.gql.exec.ServiceBackedLoader

/** Keeps the service-backed-field capability covered after itemCount was dropped. Uses an existing
 *  Moqui service that maps inputs->outputs to prove the batched loader keys/calls/maps correctly. */
class ServiceBackedLoaderTests extends Specification {
    @Shared ExecutionContext ec
    def setupSpec() { ec = Moqui.getExecutionContext(); ec.artifactExecution.disableAuthz() }
    def cleanupSpec() { if (ec != null) { ec.artifactExecution.enableAuthz(); ec.destroy() } }

    def "ServiceBackedLoader batches keys through a Moqui service and maps results back"() {
        given:
        // GqlExampleServices.ensure#TestShipments is unrelated; use a trivial echo via a core service.
        def loader = new ServiceBackedLoader(ec, "org.moqui.impl.BasicServices.echo",
                ["message"] as List, "message", 1000, "test.echo")
        when:
        def future = loader.load([["hi"]] as Set, null)   // one key tuple [message="hi"]
        def out = future.toCompletableFuture().get()
        then:
        out instanceof Map
        out.size() == 1
    }
}
```
> If `org.moqui.impl.BasicServices.echo` is not available in this runtime, substitute any single-in/single-out core service, or restore a **test-only** `get#OrderItemCount` in `GqlExampleServices.xml` and load it here. The point is to keep the loader's batch→call→map path exercised; adapt the service reference to what the runtime provides.
- [ ] **Step 3 — remove `ServiceBackedTests.groovy`** and its entry in `MoquiSuite.groovy`; add `ServiceBackedLoaderTests.class`.
- [ ] **Step 4 — run the full suite, expect PASS:** `./gradlew :runtime:component:moqui-gql:test` → BUILD SUCCESSFUL (no test references `itemCount` or `get#OrderItemCount`).
- [ ] **Step 5 — commit:** `git add service/GqlExampleServices.xml src/test/groovy/ServiceBackedLoaderTests.groovy src/test/groovy/MoquiSuite.groovy && git rm src/test/groovy/ServiceBackedTests.groovy && git commit -m "refactor(gql): retire get#OrderItemCount; cover service-backed kind via loader unit test (#37)"`

---

## Task 7: documentation

**Files:** `docs/schema.graphql`, `docs/examples.md`, `docs/STATUS.md`, `docs/design.md`

- [ ] **Step 1 — `schema.graphql`:** replace `Order.itemCount` with `orderItemCount: Int` and a doc comment ("number of distinct Shopify order lines; lazy LATERAL `COUNT(DISTINCT externalId)`; not filterable/sortable").
- [ ] **Step 2 — `examples.md`:** update any `itemCount` example to `orderItemCount`; note the exploded-units-vs-Shopify-lines distinction and that it's `0` for non-Shopify items.
- [ ] **Step 3 — `STATUS.md`:** Resolvers row — add the **aggregate-field** kind (lazy LATERAL); note the service-backed kind is retained but now has no schema user (covered by `ServiceBackedLoaderTests`).
- [ ] **Step 4 — `design.md` decision 12:** add the aggregate-field kind alongside entity/view/service; record `itemCount`→`orderItemCount` (service→SQL aggregate) next to `customerName`→`billToCustomer` and `inventoryLevels`→view; note the mysql8/LATERAL dependency and the v1 scope (root + by-pk; `orderByIdentification`/nested-Order return null).
- [ ] **Step 5 — commit:** `git add docs/schema.graphql docs/examples.md docs/STATUS.md docs/design.md && git commit -m "docs(gql): orderItemCount aggregate field (#37)"`

---

## Acceptance criteria

- `Order.orderItemCount` resolves on the `orders` list **and** `order(orderId:)`, returning `COUNT(DISTINCT OrderItem.externalId)` for the order; verified == the DB value; `0` for an order with no/no-Shopify items.
- **Lazy:** when `orderItemCount` is not selected, no sub-select member is added (the order query is unchanged) — confirmed by the no-aggregate test still passing and (manually) the absence of a LATERAL in the query log.
- `itemCount` and `GqlExampleServices.get#OrderItemCount` are gone; the service-backed-field **capability** remains and is covered by `ServiceBackedLoaderTests`.
- Governor charges `aggregateFieldCost` when `orderItemCount` is selected.
- Full suite green vs `hcsd_notnaked`. No DB/index change.

## Self-review notes (author)

- **Spec coverage (#37):** semantics + grouping field (Task 5 schema + test); lazy LATERAL via dynamic view (Tasks 2–3); cost (Task 4); drop itemCount + keep service capability (Tasks 5–6); docs (Task 7). All #37 items mapped.
- **No new fetcher/schema-builder code needed** — confirmed: `GqlSchemaBuilder` renders any field by `type` as a scalar (line 49), and `GqlEngine`'s scalar fetcher reads `source.get(name)`; the dynamic view aliases the column as the field name. The novelty is entirely in the root resolvers' find construction.
- **CompileStatic caveat captured:** the 7-arg `addMemberEntity(…subSelect)` and `addAlias(…function)` require typing the view as `EntityDynamicViewImpl` (Task 2).
- **Known v1 limitations documented:** `orderByIdentification` and nested-`Order` nodes return `orderItemCount: null` (the dynamic-view assembly is at the list/by-pk root fetchers only); mysql8/LATERAL dependency.
- **One adapt-on-execute point (not a placeholder):** Task 6 Step 2's stub service reference — adapt to whatever single-in/single-out core service the runtime exposes; the capability-coverage intent is fixed.
