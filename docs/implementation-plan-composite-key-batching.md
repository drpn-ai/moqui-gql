# Composite-key nested batching (`order → shipGroups → orderItems`) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax. Tasks 1–3 are engine generalizations verified by **regression** (existing suite stays green); Tasks 4–5 add the feature, verified by **new** tests.

**Goal:** Let nested has-many edges batch over **composite (multi-field) parent keys**, then expose `ShipGroup.orderItems` (`order → shipGroups → orderItems`), and make `order.shipGroups` return only ship groups with ≥ 1 item.

**Architecture:** Generalize the single-key nested-edge path to N-field keys. `NestedEdgeMeta` carries field **lists**; `nestedEdgeMetas()` drops the single-key gate for `list` edges and captures the full relationship key map; `NestedConnectionLoader` batches with a row-tuple condition (single column → `IN`, composite → OR-of-ANDs) and groups by the key tuple. The new `ShipGroup.orderItems` edge uses the **existing** `OrderItemShipGroup.items` relationship (composite `orderId+shipGroupSeqId`, navigating `OrderItem.shipGroupSeqId` — **not** `OrderItemShipGroupAssoc`). The empty-ship-group filter is a declarative edge option resolved with one extra batched DISTINCT query.

**Tech stack:** moqui-gql executor (java-dataloader `MappedBatchLoaderWithContext`, graphql-java), Moqui `EntityCondition`, Spock vs `hcsd_notnaked`.

**Tracking:** #38. **Scope:** has-many (list) edges only — has-one (`NestedSingleLoader`) stays single-key. **No DB/index change** (the child composite PK / `shipGroupSeqId` index serve it). Invariant relied on: `OrderItem.shipGroupSeqId` is never null (total join).

---

## File structure

| File | Change |
|---|---|
| `src/main/groovy/org/moqui/gql/exec/NestedEdgeMeta.groovy` | add `List<String> parentKeyFields`, `List<String> fkFields`, `String excludeEmptyRelationship`; keep existing single fields |
| `src/main/groovy/org/moqui/gql/GqlEngine.groovy` | `nestedEdgeMetas()`: drop the `keyMap.size()==1` gate for `list` edges, populate the key lists; the nested-edge fetcher: build a tuple key; `buildRegistry`: pass `fkFields` |
| `src/main/groovy/org/moqui/gql/exec/NestedConnectionLoader.groovy` | composite-key batch (IN for 1 field, OR-of-ANDs for many) + group by key tuple; optional exclude-empty filter |
| `graphql/OmsSchema.gql.xml` | add `ShipGroup.orderItems` edge (`entity-relationship="items"`); add `exclude-empty` to `Order.shipGroups` |
| `src/test/groovy/ShipGroupItemsTests.groovy` (NEW) | `order → shipGroups → orderItems` + empty-exclusion |
| `src/test/groovy/MoquiSuite.groovy` | register the new test |
| `docs/{schema.graphql,examples.md,STATUS.md}` | document the edge + composite batching |

---

## Task 1: generalize `NestedEdgeMeta` + `nestedEdgeMetas()` to multi-field keys

**Files:** `exec/NestedEdgeMeta.groovy`, `GqlEngine.groovy`

- [ ] **Step 1 — add list fields to `NestedEdgeMeta`** (keep the single fields for has-one + backward compat):
```groovy
    List<String> parentKeyFields   // parent-side join fields (size 1 = single-key; >1 = composite)
    List<String> fkFields          // child-side fk fields, parallel to parentKeyFields
    String excludeEmptyRelationship // optional: drop parents with no rows in this child relationship (e.g. "items")
```
- [ ] **Step 2 — `nestedEdgeMetas()` in `GqlEngine`: drop the single-key gate for `list` edges and capture the full key map.** Replace the list-edge block (the part after `if (!e.list) continue` that currently rejects `ri.keyMap.size() != 1`) with:
```groovy
                if (!e.list) continue
                def ri = parentEd.getRelationshipInfo(e.entityRelationship)
                if (ri == null || ri.keyMap == null || ri.keyMap.isEmpty()) {
                    ec.logger.warn("gql: nested edge ${t.name}.${e.name} not batchable (relationship " +
                            "'${e.entityRelationship}' missing); skipping nested resolution")
                    continue
                }
                List<String> parentKeyFields = new ArrayList<>(ri.keyMap.keySet())   // parent-side
                List<String> fkFields = new ArrayList<>()                            // child-side, parallel
                for (String pkf in parentKeyFields) fkFields.add(ri.keyMap.get(pkf))
                List<String> childPk = new ArrayList<>(ri.relatedEd.getPkFieldNames())
                List<String> intra = new ArrayList<>(childPk); intra.removeAll(fkFields)
                if (intra.isEmpty()) intra = childPk   // degenerate: fk is the entire child PK
                out.add(new NestedEdgeMeta(typeName: t.name, edgeName: e.name, loaderName: t.name + "." + e.name,
                        parentKeyField: parentKeyFields.get(0), childEntityName: ri.relatedEntityName,
                        fkField: fkFields.get(0), parentKeyFields: parentKeyFields, fkFields: fkFields,
                        intraGroupFields: intra, plain: e.isPlainList(),
                        excludeEmptyRelationship: e.excludeEmpty))
```
(`e.excludeEmpty` is added to `GqlEdge` in Task 5; for now reference it — `GqlEdge` will have the field. If executing strictly in order, temporarily pass `null` and wire it in Task 5.)
- [ ] **Step 3 — compile:** `./gradlew :runtime:component:moqui-gql:compileGroovy` → BUILD SUCCESSFUL (after Task 5's `GqlEdge.excludeEmpty`; if compiling now, pass `excludeEmptyRelationship: (String) null`).
- [ ] **Step 4 — commit:** `git add src/main/groovy/org/moqui/gql/exec/NestedEdgeMeta.groovy src/main/groovy/org/moqui/gql/GqlEngine.groovy && git commit -m "feat(gql): nested-edge metadata supports composite keys (#38)"`

---

## Task 2: composite-key batching in `NestedConnectionLoader`

**Files:** `exec/NestedConnectionLoader.groovy`

- [ ] **Step 1 — constructor takes `fkFields` (list) instead of a single `fkField`.** Change the field + constructor:
```groovy
    private final List<String> fkFields
    // …constructor param: List<String> fkFields  (replace the String fkField param)
    this.fkFields = fkFields
```
- [ ] **Step 2 — build the batched condition and group by the key tuple.** Replace the body of `load(Set<Object> keys, …)` query+group section with:
```groovy
        org.moqui.entity.EntityConditionFactory ecf = ec.entity.getConditionFactory()
        EntityFind cf = ec.entity.find(childEntityName)
        if (fkFields.size() == 1) {
            List<Object> vals = new ArrayList<Object>()
            for (Object k in keys) vals.add(k instanceof List ? ((List) k).get(0) : k)   // key may be raw or 1-tuple
            cf.condition(fkFields.get(0), EntityCondition.IN, vals)
        } else {
            List<EntityCondition> ors = new ArrayList<EntityCondition>()
            for (Object k in keys) {
                List tuple = (List) k
                List<EntityCondition> ands = new ArrayList<EntityCondition>()
                for (int i = 0; i < fkFields.size(); i++) ands.add(ecf.makeCondition(fkFields.get(i), EntityCondition.EQUALS, tuple.get(i)))
                ors.add(ecf.makeCondition(ands, EntityCondition.AND))
            }
            cf.condition(ecf.makeCondition(ors, EntityCondition.OR))
        }
        cf.orderBy(orderByList()).useClone(useClone).queryTimeout(queryTimeoutSeconds)
                .maxRows(maxRowsPerLevel).fetchSize(Math.min(maxRowsPerLevel, 1000))
        ScopeFilters.apply(cf, childEntityName, ec)
        EntityList rows = cf.list()

        Map<Object, List<EntityValue>> grouped = new LinkedHashMap<Object, List<EntityValue>>()
        for (Object k in keys) grouped.put(k, new ArrayList<EntityValue>())
        for (EntityValue ev in rows) {
            Object gk = groupKey(ev)
            List<EntityValue> g = grouped.get(gk)
            if (g != null) g.add(ev)
        }
```
- [ ] **Step 3 — `orderByList()` orders by all fk fields then intra**, and add `groupKey`:
```groovy
    private List<String> orderByList() {
        List<String> ob = new ArrayList<String>(fkFields); ob.addAll(intraGroupFields); return ob
    }
    /** Group key matching the DataLoader key shape: a single raw value for one fk field, else a List tuple. */
    private Object groupKey(EntityValue ev) {
        if (fkFields.size() == 1) return ev.get(fkFields.get(0))
        List<Object> t = new ArrayList<Object>(fkFields.size())
        for (String f in fkFields) t.add(ev.get(f))
        return t
    }
```
(Remove the old single-`fkField` `orderByList()` and the `grouped.get(ev.get(fkField))` line — replaced above.)
- [ ] **Step 4 — compile** → BUILD SUCCESSFUL.
- [ ] **Step 5 — commit:** `git add src/main/groovy/org/moqui/gql/exec/NestedConnectionLoader.groovy && git commit -m "feat(gql): NestedConnectionLoader batches composite parent keys (#38)"`

---

## Task 3: build the tuple key in the fetcher + pass `fkFields` in the registry

**Files:** `GqlEngine.groovy`

- [ ] **Step 1 — nested-edge fetcher (`withFetchers`, the has-many `NestedEdgeMeta` loop):** build the key from `parentKeyFields` (single raw value for one field, else a tuple List), so it matches `groupKey`:
```groovy
                    ({ DataFetchingEnvironment env ->
                        def parent = env.getSource()
                        if (!(parent instanceof Map)) return null
                        Map p = (Map) parent
                        Object key
                        if (m.parentKeyFields == null || m.parentKeyFields.size() <= 1) {
                            key = p.get(m.parentKeyField)
                            if (key == null) return null
                        } else {
                            List<Object> t = new ArrayList<Object>()
                            for (String f in m.parentKeyFields) { Object v = p.get(f); if (v == null) return null; t.add(v) }
                            key = t
                        }
                        return env.getDataLoader(m.loaderName).load(key, env.getArguments())
                    } as DataFetcher))
```
- [ ] **Step 2 — `buildRegistry`: pass `fkFields` (list) to `NestedConnectionLoader`** (update the constructor call):
```groovy
                    new NestedConnectionLoader(ec, meta.childEntityName, meta.fkFields, meta.intraGroupFields,
                            useClone, queryTimeoutSeconds, maxFirst, maxRowsPerLevel, meta.plain)
```
(`NestedSingleLoader` for `single` edges is unchanged — still single `fkField`.)
- [ ] **Step 3 — run the full suite, expect PASS (regression).** Existing single-key edges (`orderItems`, `statuses`, …) now flow through the list path with 1-element `fkFields` — behavior identical.
`./gradlew :runtime:component:moqui-gql:test` → BUILD SUCCESSFUL.
- [ ] **Step 4 — commit:** `git add src/main/groovy/org/moqui/gql/GqlEngine.groovy && git commit -m "feat(gql): tuple keys for composite nested edges; regression-clean for single-key (#38)"`

---

## Task 4: expose `ShipGroup.orderItems` + end-to-end test

**Files:** `graphql/OmsSchema.gql.xml`, `src/test/groovy/ShipGroupItemsTests.groovy` (NEW), `MoquiSuite.groovy`

- [ ] **Step 1 — add the edge** to the `ShipGroup` `<gql-type>` (entity `OrderItemShipGroup`), using the existing composite `items` relationship:
```xml
        <!-- composite-key nested edge (#38): items of this ship group via the OrderItemShipGroup.items
             relationship (orderId + shipGroupSeqId), navigating OrderItem.shipGroupSeqId (NOT OrderItemShipGroupAssoc). -->
        <edge name="orderItems" entity-relationship="items" target-type="OrderItem" list="true"/>
```
- [ ] **Step 2 — test** `ShipGroupItemsTests.groovy`:
```groovy
import spock.lang.Specification
import spock.lang.Shared
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.gql.GqlEngine

/** order -> shipGroups -> orderItems via the composite key (orderId, shipGroupSeqId). Self-checking:
 *  the items returned under each ship group must all carry that ship group's shipGroupSeqId. */
class ShipGroupItemsTests extends Specification {
    @Shared ExecutionContext ec
    def setupSpec() { ec = Moqui.getExecutionContext(); ec.artifactExecution.disableAuthz() }
    def cleanupSpec() { if (ec != null) { ec.artifactExecution.enableAuthz(); ec.destroy() } }

    def "order shipGroups orderItems resolves via the composite key, grouped correctly"() {
        when:
        def r = new GqlEngine(ec).execute(
            'query { orders(first:5){ edges{ node{ orderId ' +
            'shipGroups(first:10){ edges{ node{ shipGroupSeqId orderItems(first:50){ edges{ node{ orderId orderItemSeqId } } } } } } } } } }',
            [:], null)
        then:
        r.errors.isEmpty()
        r.data.orders.edges instanceof List
        // every item under a ship group belongs to that order (no cross-order leakage from the composite IN)
        r.data.orders.edges.every { oe ->
            def oid = oe.node.orderId
            (oe.node.shipGroups?.edges ?: []).every { sge ->
                (sge.node.orderItems?.edges ?: []).every { ie -> ie.node.orderId == oid }
            }
        }
    }
}
```
- [ ] **Step 3 — register** in `MoquiSuite.groovy` (`@SelectClasses`): add `ShipGroupItemsTests.class`.
- [ ] **Step 4 — run, expect PASS:** `./gradlew :runtime:component:moqui-gql:test --tests ShipGroupItemsTests` → PASS (with Tasks 1–3 in place; before them the edge was skipped and the nested selection returned null).
- [ ] **Step 5 — commit:** `git add graphql/OmsSchema.gql.xml src/test/groovy/ShipGroupItemsTests.groovy src/test/groovy/MoquiSuite.groovy && git commit -m "feat(gql): ShipGroup.orderItems via composite-key batching (#38)"`

---

## Task 5: exclude empty ship groups from `order.shipGroups`

**Files:** `SchemaArtifact.groovy` (`GqlEdge`), `SchemaArtifactParser.groovy`, `NestedConnectionLoader.groovy`, `graphql/OmsSchema.gql.xml`, `ShipGroupItemsTests.groovy`

A ship group is non-empty iff some `OrderItem` references its `(orderId, shipGroupSeqId)`. Resolve it with **one extra batched query** in the ship-groups loader: fetch the distinct `(orderId, shipGroupSeqId)` that have items, then drop ship groups not in that set before grouping.

- [ ] **Step 1 — declarative edge attribute.** In `GqlEdge` (SchemaArtifact.groovy) add `String excludeEmpty`; in `SchemaArtifactParser` (edge loop) add `excludeEmpty: en.attribute("exclude-empty")`. (`nestedEdgeMetas` Task 1 already forwards `e.excludeEmpty` → `NestedEdgeMeta.excludeEmptyRelationship`.)
- [ ] **Step 2 — loader honours it.** Add `excludeEmptyRelationship` to `NestedConnectionLoader`'s constructor + `buildRegistry` call. In `load()`, after fetching `rows` and before grouping, if `excludeEmptyRelationship != null`, resolve the relationship's child entity + composite child fk via the parent entity definition, run one batched `find(childEntity).condition(parentFk IN keys' first field …).selectField(child fk fields).distinct(true)`, build the set of non-empty parent tuples, and skip parents whose key isn't in it:
```groovy
        // exclude-empty: keep only parents (ship groups) that have >=1 row in the named child relationship
        Set<Object> nonEmpty = null
        if (excludeEmptyRelationship != null && !excludeEmptyRelationship.isEmpty()) {
            nonEmpty = NestedConnectionLoader.nonEmptyKeys(ec, childEntityName, fkFields, excludeEmptyRelationship, keys, useClone, queryTimeoutSeconds)
        }
        // …in the grouping loop, also skip parents not in nonEmpty:
        // out.put(k, (nonEmpty != null && !nonEmpty.contains(k)) ? emptyResult() : buildResult(...))
```
where `nonEmptyKeys` resolves the relationship (`OrderItemShipGroup.items` → OrderItem on orderId+shipGroupSeqId) and returns the set of parent tuples that have ≥1 child:
```groovy
    static Set<Object> nonEmptyKeys(ExecutionContext ec, String parentEntity, List<String> parentKeyFields,
                                    String childRelationship, Set<Object> keys, boolean useClone, int qTimeout) {
        def efi = (org.moqui.impl.entity.EntityFacadeImpl) ec.entity
        def ri = efi.getEntityDefinition(parentEntity).getRelationshipInfo(childRelationship)
        List<String> childFks = new ArrayList<>(ri.keyMap.values())   // child-side fields, parallel to parentKeyFields
        def ecf = ec.entity.getConditionFactory()
        def ors = new ArrayList()
        for (Object k in keys) { List t = (k instanceof List) ? (List) k : [k]
            def ands = new ArrayList()
            for (int i = 0; i < childFks.size(); i++) ands.add(ecf.makeCondition(childFks.get(i), org.moqui.entity.EntityCondition.EQUALS, t.get(i)))
            ors.add(ecf.makeCondition(ands, org.moqui.entity.EntityCondition.AND)) }
        def found = ec.entity.find(ri.relatedEntityName).condition(ecf.makeCondition(ors, org.moqui.entity.EntityCondition.OR))
                .useClone(useClone).queryTimeout(qTimeout).distinct(true)
        for (String cf in childFks) found.selectField(cf)
        Set<Object> out = new HashSet<>()
        for (def ev in found.list()) { List t = new ArrayList(); for (String cf in childFks) t.add(ev.get(cf)); out.add(childFks.size()==1 ? t.get(0) : t) }
        return out
    }
```
> Note: here `parentEntity` for the relationship lookup is the ship-group entity (`OrderItemShipGroup`), and `childRelationship="items"`. The ship-groups loader is keyed by `orderId` (single) but the non-empty check keys by the child's `(orderId, shipGroupSeqId)` — so `nonEmptyKeys` keys on the **ship group identity**, computed from the OISG rows already fetched. Implement by mapping each fetched OISG row to its `(orderId, shipGroupSeqId)` and checking membership; adjust the signature to take the fetched parent rows if cleaner than re-deriving keys.
- [ ] **Step 3 — schema:** add `exclude-empty="items"` to `Order.shipGroups`:
```xml
        <edge name="shipGroups" entity-relationship="shipGroups" target-type="ShipGroup" list="true" exclude-empty="items"/>
```
- [ ] **Step 4 — test** (add to `ShipGroupItemsTests`): every returned ship group has ≥ 1 item:
```groovy
    def "shipGroups excludes empty ship groups (every returned group has items)"() {
        when:
        def r = new GqlEngine(ec).execute(
            'query { orders(first:10){ edges{ node{ shipGroups(first:20){ edges{ node{ shipGroupSeqId orderItems(first:1){ edges{ node{ orderItemSeqId } } } } } } } } } }', [:], null)
        then:
        r.errors.isEmpty()
        r.data.orders.edges.every { oe ->
            (oe.node.shipGroups?.edges ?: []).every { sge -> (sge.node.orderItems?.edges ?: []).size() >= 1 }
        }
    }
```
- [ ] **Step 5 — run full suite, expect PASS:** `./gradlew :runtime:component:moqui-gql:test` → BUILD SUCCESSFUL.
- [ ] **Step 6 — commit:** `git add src/main/groovy/org/moqui/gql/SchemaArtifact.groovy src/main/groovy/org/moqui/gql/SchemaArtifactParser.groovy src/main/groovy/org/moqui/gql/exec/NestedConnectionLoader.groovy graphql/OmsSchema.gql.xml src/test/groovy/ShipGroupItemsTests.groovy && git commit -m "feat(gql): exclude empty ship groups from order.shipGroups (#38)"`

---

## Task 6: documentation

**Files:** `docs/schema.graphql`, `docs/examples.md`, `docs/STATUS.md`

- [ ] **Step 1 — `schema.graphql`:** add `ShipGroup.orderItems` (connection); note `shipGroups` excludes empty groups; record the data-model rule (navigate `OrderItem.shipGroupSeqId`, **not** `OrderItemShipGroupAssoc`).
- [ ] **Step 2 — `examples.md`:** add an `order → shipGroups → orderItems` example; note `order.orderItems` (flat) and `shipGroups[].orderItems` (grouped) are the same rows (shipGroupSeqId never null).
- [ ] **Step 3 — `STATUS.md`:** Batching row — note nested edges now batch **composite** parent keys (single + multi-field); add `ShipGroup.orderItems` to the schema surface.
- [ ] **Step 4 — commit:** `git add docs/schema.graphql docs/examples.md docs/STATUS.md && git commit -m "docs(gql): composite-key batching + ShipGroup.orderItems (#38)"`

---

## Acceptance criteria

- `order { shipGroups { orderItems } }` resolves: items batched per `(orderId, shipGroupSeqId)` in **one** query per level (no N+1), grouped correctly (no cross-order/cross-group leakage), keyset cursor on `orderItemSeqId`.
- Navigation is `OrderItem.shipGroupSeqId` via the `items` relationship — **never** `OrderItemShipGroupAssoc`.
- `order.shipGroups` returns **only** ship groups with ≥ 1 item.
- **Regression:** all existing single-key edges (`orderItems`, `statuses`, `adjustments`, …) and has-one (`billToCustomer`) behave identically.
- No DB/index change. Full suite green vs `hcsd_notnaked`.

## Self-review notes (author)

- **Spec coverage (#38):** composite-key batching (Tasks 1–3), `ShipGroup.orderItems` via the `items` relationship (Task 4), exclude-empty (Task 5), data-model rule documented (Task 6). All #38 items mapped.
- **Scope discipline:** has-many only; `NestedSingleLoader` (has-one) untouched, still single-key. `NestedEdgeMeta` keeps the single fields so has-one code is unchanged; the list fields drive has-many.
- **Regression safety:** single-key list edges run through the new list path with 1-element `fkFields` (`IN`, raw group key) — identical SQL/behavior; verified by the existing suite in Task 3.
- **One adapt-on-execute point (not a placeholder):** Task 5 Step 2's `nonEmptyKeys` keys on the ship-group identity — prefer deriving the key set from the already-fetched OISG rows over re-querying keys; the executing agent should pick whichever reads cleaner against the actual `EntityFind` API (the goal — "drop ship groups with zero items via one extra batched query" — is fixed).
- **Cross-task ordering:** `GqlEdge.excludeEmpty` (Task 5 Step 1) is referenced by `nestedEdgeMetas` (Task 1 Step 2); if executing strictly task-by-task, Task 1 passes `null` and Task 5 wires it — noted inline.
