# Inventory Levels (view-backed) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Convert the `inventoryLevels` GraphQL root from a service-backed list into an entity/view-backed **Relay connection** over `ProductFacility → current InventoryItem`, returning current ATP/QOH per **configured** product+facility (0, never null, when unstocked).

**Architecture:** Reuse the canonical `oms` view `ProductFacilityInventoryItemView` (ProductFacility LEFT-joined to the current InventoryItem via `ProductFacility.inventoryItemId`), extended with a `quantityOnHand` alias and COALESCE-to-0 on both totals. Flip the `InventoryLevel` gql-type to entity-backed and the root to a `list` connection with `productId`/`facilityId` search keys (both `ProductFacility` PK columns → index-backed, no cost penalty). Retire the example service and the inventory-specific governor cap.

**Tech Stack:** Moqui view-entity XML (`oms`), moqui-gql schema artifact (`graphql/*.gql.xml`), graphql-java executor, Spock 2.1 tests vs MySQL `hcsd_notnaked`.

**Tracking issue:** hotwax/moqui-gql#35. **Cross-repo:** Task 1 is in `hotwax/oms`; Tasks 2–6 in `hotwax/moqui-gql`. **Land Task 1 first** so the gql `entity-name` resolves at schema-build.

---

## Why (read before starting)

Inventory availability here is **not** a sum over `InventoryItem` rows. The OMS keeps **one current item per (product, facility)**, addressed by `ProductFacility.inventoryItemId`; the `rolloverInventoryItems` job periodically consolidates older items (sums them, zeroes them, repoints the pointer). So the correct, OMS-consistent read follows the pointer — exactly the join `ProductFacilityInventoryItemView` already makes. The root is **driven by `ProductFacility`** (one row per configured product+facility); ATP/QOH are `0` (never null) when no inventory was ever received or the item is depleted; **no row means the product is not configured at that facility.**

---

## File Structure

| File | Repo | Responsibility | Change |
|---|---|---|---|
| `entity/OmsViewEntities.xml` | hotwax/oms | the backing view-entity | add `quantityOnHand` alias; COALESCE both totals to 0 |
| `graphql/OmsSchema.gql.xml` | hotwax/moqui-gql | schema artifact | `InventoryLevel` → entity-backed; root → `list` connection |
| `src/main/groovy/org/moqui/gql/GovernorInstrumentation.groovy` | hotwax/moqui-gql | pre-exec governor | remove now-dead `checkInventoryKeyCap` + `maxInventoryKeys` |
| `service/GqlExampleServices.xml` | hotwax/moqui-gql | example services | delete `get#InventoryLevels` (keep `get#OrderItemCount`) |
| `src/test/groovy/InventoryLevelsTests.groovy` | hotwax/moqui-gql | the suite | rewrite for the connection + 0-not-null + filters |
| `docs/{schema.graphql,examples.md,STATUS.md,design.md}` | hotwax/moqui-gql | docs | reflect entity-backed `inventoryLevels` |

---

## Task 0: Pre-flight checks (no code)

**Files:** none — read-only DB + grep.

- [ ] **Step 1: Confirm the consumer-safety of COALESCE-ing `availableToPromise` in the shared view.**

The shared `ProductFacilityInventoryItemView` is also read by OMS services. Confirm they treat a null ATP the same as 0 (so changing the alias to COALESCE-0 is behavior-preserving):

Run:
```bash
grep -rn "ProductFacilityInventoryItemView" /Users/anilpatel/maarg-sd/notnaked/runtime/component/oms/service /Users/anilpatel/maarg-sd/notnaked/runtime/component/poorti/service
```
Expected: a small number of `entity-find-one` reads keyed by `(productId, facilityId)` that use `availableToPromise` in numeric comparisons (`> 0`, `?: 0`). If any consumer branches on `availableToPromise == null` specifically, do **not** COALESCE `availableToPromise` in the view — instead leave it plain and skip its COALESCE in Task 1 Step 2 (the new `quantityOnHand` alias is additive and always safe). The 0-not-null contract for ATP is then covered by the data reality that configured+pointer-set rows have a real number, and the test in Task 5 should assert `quantityOnHand != null` only. Record the finding in the PR description.

- [ ] **Step 2: Size the unstocked case (informational).**

In MySQL Workbench against `hcsd_notnaked`:
```sql
USE hcsd_notnaked;
SELECT COUNT(*) AS total,
       SUM(CASE WHEN INVENTORY_ITEM_ID IS NULL THEN 1 ELSE 0 END) AS unstocked
FROM PRODUCT_FACILITY;
ANALYZE TABLE PRODUCT_FACILITY;   -- index stats currently show cardinality 0
```
Expected: `unstocked` is small (findOrCreate + rollover populate the pointer). This validates that the COALESCE-to-0 path is exercised but not dominant. No code depends on the number; it just confirms the design assumption.

---

## Task 1: Extend the `oms` view with `quantityOnHand` (+ COALESCE) — `hotwax/oms`

**Files:**
- Modify: `runtime/component/oms/entity/OmsViewEntities.xml` (the `ProductFacilityInventoryItemView` block, ~lines 10-23)

This view-entity change has no unit test of its own; its behavior is verified by the moqui-gql suite in Task 5 (which boots an EC that loads the `oms` component). Land this PR first.

- [ ] **Step 1: Replace the view-entity body.**

Current:
```xml
<view-entity entity-name="ProductFacilityInventoryItemView" package="co.hotwax.oms.product.inventory">
    <member-entity entity-alias="PF" entity-name="org.apache.ofbiz.product.facility.ProductFacility"/>
    <member-entity entity-alias="II" entity-name="org.apache.ofbiz.product.inventory.InventoryItem" join-from-alias="PF" join-optional="true">
        <key-map field-name="inventoryItemId" />
    </member-entity>
    <alias-all entity-alias="PF"/>
    <alias entity-alias="II" name="availableToPromise" field="availableToPromiseTotal" />
    <alias name="computedInventoryCount" type="number-decimal">
        <complex-alias operator="-">
            <complex-alias-field entity-alias="II" field="availableToPromiseTotal" default-value="0"/>
            <complex-alias-field entity-alias="PF" field="minimumStock" default-value="0"/>
        </complex-alias>
    </alias>
</view-entity>
```

New (adds `quantityOnHand`; COALESCEs both totals to 0 via the same `default-value` idiom the view already uses for `computedInventoryCount`):
```xml
<view-entity entity-name="ProductFacilityInventoryItemView" package="co.hotwax.oms.product.inventory">
    <member-entity entity-alias="PF" entity-name="org.apache.ofbiz.product.facility.ProductFacility"/>
    <member-entity entity-alias="II" entity-name="org.apache.ofbiz.product.inventory.InventoryItem" join-from-alias="PF" join-optional="true">
        <key-map field-name="inventoryItemId" />
    </member-entity>
    <alias-all entity-alias="PF"/>
    <!-- COALESCE to 0: a configured product+facility with no/depleted inventory reports 0, never null -->
    <alias name="availableToPromise" type="number-decimal">
        <complex-alias><complex-alias-field entity-alias="II" field="availableToPromiseTotal" default-value="0"/></complex-alias>
    </alias>
    <alias name="quantityOnHand" type="number-decimal">
        <complex-alias><complex-alias-field entity-alias="II" field="quantityOnHandTotal" default-value="0"/></complex-alias>
    </alias>
    <alias name="computedInventoryCount" type="number-decimal">
        <complex-alias operator="-">
            <complex-alias-field entity-alias="II" field="availableToPromiseTotal" default-value="0"/>
            <complex-alias-field entity-alias="PF" field="minimumStock" default-value="0"/>
        </complex-alias>
    </alias>
</view-entity>
```
> If Task 0 Step 1 found a consumer that needs `availableToPromise == null`, keep the original plain `<alias entity-alias="II" name="availableToPromise" field="availableToPromiseTotal" />` line instead of the COALESCE block, and add only the `quantityOnHand` COALESCE alias.

- [ ] **Step 2: Verify the component still loads (no XML/schema error).**

Run (from the `notmaked` moqui-framework root):
```bash
./gradlew :runtime:component:moqui-gql:test --tests ScaffoldSmokeTests -DskipBuild=false 2>&1 | tail -20
```
Expected: PASS — the EC boots and builds the entity model including the modified view (a malformed view-entity fails EC startup, so a green boot confirms the view parses). (Full inventory assertions come in Task 5.)

- [ ] **Step 3: Commit (in the `hotwax/oms` repo, on a feature branch).**
```bash
cd /Users/anilpatel/maarg-sd/notnaked/runtime/component/oms
git checkout -b feature/inventory-level-qoh
git add entity/OmsViewEntities.xml
git commit -m "feat: add quantityOnHand + COALESCE-0 to ProductFacilityInventoryItemView (moqui-gql#35)"
```
Open a PR in `hotwax/oms`, get it merged, then `git pull` the component in the dev env before Task 5.

---

## Task 2: Flip `InventoryLevel` to entity-backed + connection root — `hotwax/moqui-gql`

**Files:**
- Modify: `graphql/OmsSchema.gql.xml` (the `InventoryLevel` `<gql-type>` block, ~lines 148-159)

- [ ] **Step 1: Replace the `InventoryLevel` type + root.**

Current:
```xml
<gql-type name="InventoryLevel">
    <field name="productId" type="ID"/>
    <field name="facilityId" type="ID"/>
    <field name="availableToPromise" type="Decimal"/>
    <field name="quantityOnHand" type="Decimal"/>
    <root-query name="inventoryLevels" service="true"
        service-name="GqlExampleServices.get#InventoryLevels" service-out="inventoryLevels"
        returns-list="true">
        <arg name="productIds" type="IDList" required="true"/>
        <arg name="facilityIds" type="IDList"/>
    </root-query>
</gql-type>
```

New (entity-backed; root becomes a Relay connection with declared search/sort keys — mirrors the `Shipment` root):
```xml
<gql-type name="InventoryLevel" entity-name="co.hotwax.oms.product.inventory.ProductFacilityInventoryItemView">
    <field name="productId" entity-field="productId" type="ID"/>
    <field name="facilityId" entity-field="facilityId" type="ID"/>
    <field name="availableToPromise" entity-field="availableToPromise" type="Decimal"/>
    <field name="quantityOnHand" entity-field="quantityOnHand" type="Decimal"/>
    <root-query name="inventoryLevels" list="true"
        search-keys="productId:eq,in facilityId:eq,in"
        sort-keys="PRODUCT_ID:productId FACILITY_ID:facilityId"/>
</gql-type>
```
Notes for the implementer: the view's PK is the composite `(productId, facilityId)` (from `alias-all PF`), which the executor uses as the keyset cursor (same machinery as the composite-PK `parties` root). `availableToPromise`/`quantityOnHand` are intentionally **not** in `search-keys`/`sort-keys` (unindexed decimals).

- [ ] **Step 2: Commit.**
```bash
git add graphql/OmsSchema.gql.xml
git commit -m "feat(inventory): InventoryLevel entity-backed via ProductFacilityInventoryItemView (#35)"
```

---

## Task 3: Rewrite the test suite (TDD anchor) — `hotwax/moqui-gql`

**Files:**
- Modify: `src/test/groovy/InventoryLevelsTests.groovy` (full rewrite)

- [ ] **Step 1: Replace the test file.**
```groovy
import spock.lang.Specification
import spock.lang.Shared
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.gql.GqlEngine

/** Entity/view-backed root: inventoryLevels over ProductFacilityInventoryItemView
 *  (ProductFacility LEFT-joined to the current InventoryItem via ProductFacility.inventoryItemId).
 *  Driven by ProductFacility: a row per configured product+facility; ATP/QOH are 0 (never null)
 *  when unstocked/depleted. "every{}"/"instanceof" assertions stay green even if a slice is empty. */
class InventoryLevelsTests extends Specification {
    @Shared ExecutionContext ec
    @Shared String aFacilityId
    @Shared String aProductId

    def setupSpec() {
        ec = Moqui.getExecutionContext(); ec.artifactExecution.disableAuthz()
        def pf = ec.entity.find("org.apache.ofbiz.product.facility.ProductFacility")
                .selectField("productId").selectField("facilityId")
                .orderBy("productId").orderBy("facilityId").maxRows(1).fetchSize(1).list()
        if (pf) { aProductId = pf[0].productId; aFacilityId = pf[0].facilityId }
    }
    def cleanupSpec() { if (ec != null) { ec.artifactExecution.enableAuthz(); ec.destroy() } }

    def "inventoryLevels resolves to a Relay connection with non-null totals"() {
        when:
        def r = new GqlEngine(ec).execute(
                'query { inventoryLevels(first:5){ edges{ cursor node{ productId facilityId availableToPromise quantityOnHand } } pageInfo{ hasNextPage } } }', [:], null)
        then:
        r.errors.isEmpty()
        r.data.inventoryLevels != null
        r.data.inventoryLevels.edges instanceof List
        r.data.inventoryLevels.edges.every { it.node.productId != null && it.node.facilityId != null }
        // COALESCE contract: ATP/QOH are 0, never null
        r.data.inventoryLevels.edges.every { it.node.availableToPromise != null && it.node.quantityOnHand != null }
    }

    def "inventoryLevels filters by facilityId (declared key, index-backed)"() {
        given:
        org.junit.jupiter.api.Assumptions.assumeTrue(aFacilityId != null)
        when:
        def r = new GqlEngine(ec).execute(
                'query Q($q:String!){ inventoryLevels(first:25, query:$q){ edges{ node{ productId facilityId } } } }',
                [q: "facilityId:" + aFacilityId], "Q")
        then:
        r.errors.isEmpty()
        r.data.inventoryLevels.edges.every { it.node.facilityId == aFacilityId }
    }

    def "inventoryLevels filters by productId (declared key)"() {
        given:
        org.junit.jupiter.api.Assumptions.assumeTrue(aProductId != null)
        when:
        def r = new GqlEngine(ec).execute(
                'query Q($q:String!){ inventoryLevels(first:25, query:$q){ edges{ node{ productId } } } }',
                [q: "productId:" + aProductId], "Q")
        then:
        r.errors.isEmpty()
        r.data.inventoryLevels.edges.every { it.node.productId == aProductId }
    }

    def "inventoryLevels keyset pagination does not overlap across pages"() {
        when:
        def p1 = new GqlEngine(ec).execute('query { inventoryLevels(first:3){ edges{ cursor node{ productId facilityId } } pageInfo{ endCursor hasNextPage } } }', [:], null)
        then:
        p1.errors.isEmpty()
        def e1 = p1.data.inventoryLevels.edges
        e1.size() <= 3

        when:
        def hasNext = p1.data.inventoryLevels.pageInfo.hasNextPage
        def after = p1.data.inventoryLevels.pageInfo.endCursor
        def p2 = hasNext ? new GqlEngine(ec).execute(
                'query Q($a:String!){ inventoryLevels(first:3, after:$a){ edges{ node{ productId facilityId } } } }', [a: after], "Q") : null
        then:
        if (p2 != null) {
            p2.errors.isEmpty()
            def key = { n -> n.productId + "|" + n.facilityId }
            def s1 = e1.collect { key(it.node) } as Set
            p2.data.inventoryLevels.edges.every { !s1.contains(key(it.node)) }
        } else { true }
    }

    def "inventoryLevels requires first/last (governor)"() {
        when:
        def r = new GqlEngine(ec).execute('query { inventoryLevels{ edges{ node{ productId } } } }', [:], null)
        then:
        !r.errors.isEmpty()
        r.errors[0].extensions.code == "FIRST_REQUIRED"
    }
}
```

- [ ] **Step 2: Run — expect FAIL before the schema flip lands in the test JVM.**

Run:
```bash
./gradlew :runtime:component:moqui-gql:test --tests InventoryLevelsTests 2>&1 | tail -30
```
Expected: FAIL (the old service-backed root has no `edges`/`first`; the new query shape doesn't resolve) — confirming the tests exercise the new contract. (If Task 2 is already applied in your working tree, this may instead pass; that's fine — the TDD intent is that these tests define the target.)

- [ ] **Step 3: Run — expect PASS with Task 2 applied.**

Run:
```bash
./gradlew :runtime:component:moqui-gql:test --tests InventoryLevelsTests 2>&1 | tail -30
```
Expected: PASS (all 5 features; data-dependent ones are skipped via `assumeTrue` if `hcsd_notnaked` has no `ProductFacility` rows — it does, so they run).

- [ ] **Step 4: Commit.**
```bash
git add src/test/groovy/InventoryLevelsTests.groovy
git commit -m "test(inventory): rewrite InventoryLevelsTests for the view-backed connection (#35)"
```

---

## Task 4: Retire `get#InventoryLevels` + governor cleanup — `hotwax/moqui-gql`

**Files:**
- Modify: `service/GqlExampleServices.xml` (delete the `get#InventoryLevels` service, ~lines 23-49)
- Modify: `src/main/groovy/org/moqui/gql/GovernorInstrumentation.groovy` (remove `checkInventoryKeyCap` + its call; remove `maxInventoryKeys`)
- Modify: `MoquiConf.xml` (remove the `gql.maxInventoryKeys` default)

`inventoryLevels` is no longer service-backed, so the service-root cost branch and the inventory-key cap are dead code. Removing them is hygiene; flipping the schema (Task 2) already changed the cost path to a normal connection.

- [ ] **Step 1: Delete `get#InventoryLevels` from `service/GqlExampleServices.xml`.**

Remove the entire `<service verb="get" noun="InventoryLevels" ...> ... </service>` block (the one that iterates `productIds` and sums `InventoryItem`). Keep `get#OrderItemCount` and `ensure#TestShipments`.

- [ ] **Step 2: Remove the inventory-key cap from `GovernorInstrumentation.groovy`.**

Delete the `checkInventoryKeyCap(field, rootQ)` call inside the service-root branch of `fieldCost` (the `if (rootQ != null && rootQ.serviceBacked) { ... }` block) and delete the `private void checkInventoryKeyCap(...)` method (~lines 241-249). Remove the `maxInventoryKeys` field declaration, its constructor assignment (`this.maxInventoryKeys = cfg.maxInventoryKeys`), and the `maxInventoryKeys` entry in `GqlEngine`'s `govCfg` map. Leave the general service-root cost branch (`serviceFixedCost + child`) intact for any future service root.

- [ ] **Step 3: Remove the `gql.maxInventoryKeys` default from `MoquiConf.xml`.**

Delete the line `<default-property name="gql.maxInventoryKeys" value="500"/>`.

- [ ] **Step 4: Run the full suite — expect PASS.**

Run:
```bash
./gradlew :runtime:component:moqui-gql:test 2>&1 | tail -40
```
Expected: BUILD SUCCESSFUL; all suites green (no test references `get#InventoryLevels`, `maxInventoryKeys`, or `BATCH_LIMIT_EXCEEDED` for inventory after this). If `GovernorTests` had an inventory-cap case (N7-style), update it to the new connection behavior or remove it in this step.

- [ ] **Step 5: Commit.**
```bash
git add service/GqlExampleServices.xml src/main/groovy/org/moqui/gql/GovernorInstrumentation.groovy MoquiConf.xml
git commit -m "refactor(inventory): retire get#InventoryLevels + maxInventoryKeys cap (#35)"
```

---

## Task 5: Documentation — `hotwax/moqui-gql`

**Files:**
- Modify: `docs/schema.graphql`, `docs/examples.md`, `docs/STATUS.md`, `docs/design.md`, `README.md`

- [ ] **Step 1: `docs/schema.graphql`** — change the `inventoryLevels` root to a connection and `InventoryLevel` to a normal entity-backed type. Replace the `inventoryLevels(productIds:[ID!]!, facilityIds:[ID]): [InventoryLevel!]! @service(...)` root with:
```graphql
  "Inventory levels (ATP/QOH) per configured product+facility. query: keys: productId(eq,in) facilityId(eq,in)."
  inventoryLevels(query: String, sortKey: InventoryLevelSortKey = PRODUCT_ID, reverse: Boolean = false,
    first: Int, after: String, last: Int, before: String): InventoryLevelConnection!
    @search(keys: ["productId","facilityId"])
```
Add `enum InventoryLevelSortKey { PRODUCT_ID FACILITY_ID }`, an `InventoryLevelConnection`/`InventoryLevelEdge` pair (mirror the others), and drop the `@service`/`@cost` annotations from `InventoryLevel`. Remove `inventoryLevels` from the header's "service fields / not implemented" note if listed there.

- [ ] **Step 2: `docs/examples.md`** — update the bulk-ATP (`inventoryLevels`) example to the connection form (`inventoryLevels(query:"productId:SKU1,SKU2", first:50){ edges{ node{ productId facilityId availableToPromise quantityOnHand } } }`) and the "maps:" annotation to view-backed (`ProductFacilityInventoryItemView`), not service-backed. Note 0-not-null for unstocked.

- [ ] **Step 3: `docs/STATUS.md`** — in the Resolvers row, move `inventoryLevels` from "service-backed roots" to "view-backed"; in the schema-surface list note `InventoryLevel` is view-backed (`ProductFacilityInventoryItemView`).

- [ ] **Step 4: `docs/design.md`** — in decision 12, add `inventoryLevels` to the service→view reclassification note alongside `customerName`→`billToCustomer` (a service root replaced by a view-entity root).

- [ ] **Step 5: `README.md`** — add `docs/implementation-plan-inventory.md` to the Documents table.

- [ ] **Step 6: Commit.**
```bash
git add docs/schema.graphql docs/examples.md docs/STATUS.md docs/design.md README.md
git commit -m "docs(inventory): inventoryLevels is now an entity-backed connection (#35)"
```

---

## Acceptance criteria (verify before closing #35)

- `inventoryLevels` resolves via `ProductFacilityInventoryItemView` (no service call); returns `{productId, facilityId, availableToPromise, quantityOnHand}` as a Relay connection.
- A row is returned for **every configured product+facility**; ATP/QOH are **0, never null** when unstocked/depleted; **no row** when the product is not configured at the facility.
- Filter by `facilityId` and/or `productId` (eq/in); both index-backed; no unindexed-filter penalty.
- Composite-PK keyset pagination correct (no overlap/skip across pages).
- `get#InventoryLevels`, `checkInventoryKeyCap`, and `gql.maxInventoryKeys` are gone.
- Full suite green vs `hcsd_notnaked`.

## Self-review notes (author)
- Spec coverage: Task 1 = view alias/COALESCE (#35 design "Backing view" + "Row semantics"); Tasks 2–4 = schema/governor/service (#35 "Schema shape" + "Cost model"); Task 3 = acceptance tests; Task 5 = docs. All #35 plan items mapped.
- Cross-repo ordering enforced: Task 1 (`oms`) merges before Task 5 test run (which boots an EC loading `oms`).
- Open contingency (not a placeholder): Task 0 Step 1 gates whether `availableToPromise` is COALESCEd in the shared view or left plain; both branches are fully specified.
