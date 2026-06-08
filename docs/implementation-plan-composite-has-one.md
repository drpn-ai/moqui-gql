# Composite-key has-ONE edges (generalize `NestedSingleLoader`) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax. Tasks 1–3 are engine generalizations verified by **regression** (existing suite stays green — `BillToCustomer` and every single-key has-one edge behave identically); Task 4 adds the feature, verified by a **new** test.

**Goal:** Let a has-**one** (single-object) nested edge be keyed by a **composite (multi-field) key**, then expose `ShipGroup.shippingMethod → CarrierShipmentMethod` (composite PK). This is the has-one half of the composite-key nested-batching work — the sibling of #38 (has-many), applied to `NestedSingleLoader`.

**Architecture:** Mirror #38 on the has-one path. A `single` edge's `fk` (and `parent-key`) become **comma-separated lists** parsed into `GqlEdge.fkFields`/`GqlEdge.parentKeyFields`; `nestedEdgeMetas()`'s single-edge branch populates `NestedEdgeMeta.fkFields`/`parentKeyFields` from them (1-field = the existing single-key case); `NestedSingleLoader` batches with the same row-tuple condition shape as #38's `NestedConnectionLoader` (one fk field → `IN`; composite → OR-of-ANDs), groups rows by the key tuple, and returns the **first** row per parent; the has-one fetcher in `withFetchers` builds the parent key as a single raw value (1 field) or a `List` tuple (composite), matching the loader's group key. The validating edge uses the parent's explicit composite FK to `CarrierShipmentMethod` — parent fields `shipmentMethodTypeId,carrierPartyId,carrierRoleTypeId` mapping to child PK fields `shipmentMethodTypeId,partyId,roleTypeId` (the field names differ on the carrier-party axis, which is exactly why the single-edge path uses **explicit** `fk`/`parent-key` attributes rather than a Moqui relationship key-map).

**Tech stack:** moqui-gql executor (java-dataloader `MappedBatchLoaderWithContext`, graphql-java), Moqui `EntityCondition` / `EntityConditionFactory`, Spock vs MySQL `hcsd_notnaked` booting `Moqui.getExecutionContext()`.

**Tracking:** #42 (sibling of #38). **Prerequisite:** #38 (has-many composite batching) — #42 reuses the `NestedEdgeMeta.parentKeyFields`/`fkFields` list fields and the fetcher tuple shape #38 introduces. This plan still adds the **has-one-specific** pieces (the single-edge branch parses explicit comma `fk`/`parent-key`; `NestedSingleLoader` gains the composite condition + tuple group-key). If #38 is not yet merged when this runs, Task 1 adds the `NestedEdgeMeta` list fields itself (noted inline) so #42 is self-contained. **Scope:** has-one (`single`) edges only — `NestedConnectionLoader` (has-many) is #38's concern and is untouched here. **No DB/index change** (the child composite PK serves the batched lookup). **Additive:** single-key has-one (`billToCustomer`) keeps its raw-value key and `IN` query — identical SQL/behavior.

---

## File structure

| File | Change |
|---|---|
| `src/main/groovy/org/moqui/gql/SchemaArtifact.groovy` | `GqlEdge`: add `List<String> fkFields`, `List<String> parentKeyFields` (parsed from comma `fk`/`parent-key`); keep `fk`/`parentKey`/`childEntity`/`single` |
| `src/main/groovy/org/moqui/gql/SchemaArtifactParser.groovy` | edge loop: populate `fkFields`/`parentKeyFields` by splitting `fk`/`parent-key` on commas |
| `src/main/groovy/org/moqui/gql/exec/NestedEdgeMeta.groovy` | add `List<String> parentKeyFields`, `List<String> fkFields` (if #38 has not already added them); keep the single `parentKeyField`/`fkField` |
| `src/main/groovy/org/moqui/gql/exec/NestedSingleLoader.groovy` | constructor takes `List<String> fkFields`; `load()` builds the tuple condition (1 field → `IN`, composite → OR-of-ANDs) and groups first-row-per-parent by the key tuple |
| `src/main/groovy/org/moqui/gql/GqlEngine.groovy` | `nestedEdgeMetas()` single-edge branch: populate `parentKeyFields`/`fkFields`; the has-one fetcher in `withFetchers`: build the tuple parent key; `buildRegistry`: pass `meta.fkFields` to `NestedSingleLoader` |
| `graphql/OmsSchema.gql.xml` | add `ShipGroup.shippingMethod` (`single="true"`, composite `fk`/`parent-key`) + a `ShippingMethod` leaf `gql-type` |
| `src/test/groovy/ShippingMethodTests.groovy` (NEW) | `order → shipGroups → shippingMethod` composite-key has-one resolves + is batched |
| `src/test/groovy/MoquiSuite.groovy` | register `ShippingMethodTests` |
| `docs/{schema.graphql,examples.md,STATUS.md}` | document the composite has-one edge |

---

## Task 1: parse composite `fk`/`parent-key` on a `single` edge + carry the lists in metadata

**Files:** `SchemaArtifact.groovy`, `SchemaArtifactParser.groovy`, `exec/NestedEdgeMeta.groovy`

- [ ] **Step 1 — add list fields to `GqlEdge`** (keep the single `fk`/`parentKey` for backward compat). In `SchemaArtifact.groovy`, replace the `GqlEdge` single-object block:
```groovy
    /** Single-object (has-one) edge: resolved by a batched WHERE fk IN(keys) against childEntity.
     *  Explicit child entity + join field(s) (no Moqui relationship needed); parentKey defaults to fk.
     *  `fk`/`parentKey` may be comma-separated for a COMPOSITE key — split into fkFields/parentKeyFields
     *  (size 1 = single-key; >1 = composite). Single fields kept for backward compat. */
    boolean single = false
    String childEntity = null
    String fk = null
    String parentKey = null
    List<String> fkFields = []
    List<String> parentKeyFields = []
```
- [ ] **Step 2 — populate the lists in `SchemaArtifactParser`.** In the `for (MNode en in tn.children("edge"))` loop, replace the `single`/`childEntity`/`fk`/`parentKey` line of the `new GqlEdge(...)` with the lines below, and after constructing the edge derive the parallel lists (single = the 1-element case; `parentKey` defaults to `fk`):
```groovy
                for (MNode en in tn.children("edge")) {
                    GqlEdge edge = new GqlEdge(
                            name: en.attribute("name"), entityRelationship: en.attribute("entity-relationship"),
                            targetType: en.attribute("target-type"), list: "true".equals(en.attribute("list")),
                            kind: en.attribute("kind") ?: "connection", firstDefault: asInt(en.attribute("first-default")),
                            costOverride: asInt(en.attribute("cost")),
                            resolverService: en.attribute("resolver-service"),
                            resolverIn: splitList(en.attribute("resolver-in")),
                            single: "true".equals(en.attribute("single")), childEntity: en.attribute("entity-name"),
                            fk: en.attribute("fk"), parentKey: en.attribute("parent-key"))
                    edge.fkFields = splitList(en.attribute("fk"))
                    edge.parentKeyFields = en.attribute("parent-key") != null && !en.attribute("parent-key").isEmpty() ?
                            splitList(en.attribute("parent-key")) : new ArrayList<String>(edge.fkFields)
                    t.edges.put(en.attribute("name"), edge)
                }
```
- [ ] **Step 3 — add the list fields to `NestedEdgeMeta`** (skip this step if #38 already added them — confirm with `grep -n "parentKeyFields" src/main/groovy/org/moqui/gql/exec/NestedEdgeMeta.groovy`; the field must exist for Task 2/3). Replace the `parentKeyField`/`fkField` lines, keeping the singles:
```groovy
    String parentKeyField  // field on the parent entity that joins to the child fk (single-key; first of parentKeyFields)
    String childEntityName // full child entity name
    String fkField         // child field holding the parent key (single-key; first of fkFields)
    List<String> parentKeyFields = new ArrayList<String>() // parent-side join fields (size 1 = single-key; >1 = composite)
    List<String> fkFields = new ArrayList<String>()        // child-side fk fields, parallel to parentKeyFields
    List<String> intraGroupFields // child PK minus the fk: natural order of children within a parent
```
- [ ] **Step 4 — compile:** `./gradlew :runtime:component:moqui-gql:compileGroovy` → BUILD SUCCESSFUL.
- [ ] **Step 5 — commit:** `git add src/main/groovy/org/moqui/gql/SchemaArtifact.groovy src/main/groovy/org/moqui/gql/SchemaArtifactParser.groovy src/main/groovy/org/moqui/gql/exec/NestedEdgeMeta.groovy && git commit -m "feat(gql): single-edge fk/parent-key may be composite; carry key lists (#42)"`

---

## Task 2: composite-key batching in `NestedSingleLoader`

**Files:** `exec/NestedSingleLoader.groovy`

- [ ] **Step 1 — write a failing test** that drives a composite has-one loader directly (no schema needed), so the loader's tuple logic is proven in isolation. Create `src/test/groovy/NestedSingleLoaderCompositeTests.groovy`:
```groovy
import spock.lang.Specification
import spock.lang.Shared
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.gql.exec.NestedSingleLoader

/** Unit-level: NestedSingleLoader batches a COMPOSITE-key has-one over CarrierShipmentMethod
 *  (PK shipmentMethodTypeId+partyId+roleTypeId) in ONE OR-of-ANDs query, first row per parent tuple. */
class NestedSingleLoaderCompositeTests extends Specification {
    @Shared ExecutionContext ec
    @Shared List<Object> keyA, keyB
    def setupSpec() {
        ec = Moqui.getExecutionContext(); ec.artifactExecution.disableAuthz()
        // take two real CarrierShipmentMethod rows to key by their full composite PK
        def rows = ec.entity.find("org.apache.ofbiz.shipment.shipment.CarrierShipmentMethod")
                .selectField("shipmentMethodTypeId").selectField("partyId").selectField("roleTypeId")
                .useClone(true).maxRows(2).fetchSize(2).list()
        if (rows.size() >= 1) keyA = [rows.get(0).shipmentMethodTypeId, rows.get(0).partyId, rows.get(0).roleTypeId]
        if (rows.size() >= 2) keyB = [rows.get(1).shipmentMethodTypeId, rows.get(1).partyId, rows.get(1).roleTypeId]
    }
    def cleanupSpec() { if (ec != null) { ec.artifactExecution.enableAuthz(); ec.destroy() } }

    def "composite has-one loader returns the matching row per key tuple"() {
        given:
        def loader = new NestedSingleLoader(ec, "org.apache.ofbiz.shipment.shipment.CarrierShipmentMethod",
                ["shipmentMethodTypeId", "partyId", "roleTypeId"], true, 20, 5000)
        Set<Object> keys = (keyB != null ? [keyA, keyB] : [keyA]) as Set<Object>
        when:
        Map<Object, Object> out = loader.load(keys, null).toCompletableFuture().get()
        then:
        out.keySet() == keys
        out.get(keyA) != null
        out.get(keyA).shipmentMethodTypeId == keyA.get(0)
        out.get(keyA).partyId == keyA.get(1)
        out.get(keyA).roleTypeId == keyA.get(2)
        keyB == null || (out.get(keyB) != null && out.get(keyB).partyId == keyB.get(1))
    }
}
```
- [ ] **Step 2 — run it, expect FAIL** (compile error: the 3-arg-key `NestedSingleLoader(ec, name, List, ...)` constructor does not exist yet — current constructor takes a single `String fkField`):
`./gradlew :runtime:component:moqui-gql:test --tests NestedSingleLoaderCompositeTests` → **FAILS** (BUILD FAILED, compilation/constructor error).
- [ ] **Step 3 — implement composite batching in `NestedSingleLoader`.** Replace the entire file with:
```groovy
package org.moqui.gql.exec

import groovy.transform.CompileStatic
import org.dataloader.BatchLoaderEnvironment
import org.dataloader.MappedBatchLoaderWithContext
import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityCondition
import org.moqui.entity.EntityConditionFactory
import org.moqui.entity.EntityFind
import org.moqui.entity.EntityList
import org.moqui.entity.EntityValue
import org.moqui.gql.scope.ScopeFilters

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/**
 * Batched loader for a single-object (has-one) nested edge — e.g. Order.billToCustomer, or the
 * composite-keyed ShipGroup.shippingMethod -> CarrierShipmentMethod. ONE query for every parent at the
 * level (no N+1): a single fk field -> `WHERE fk IN(:keys)`; a COMPOSITE key -> `WHERE (a=? AND b=? AND c=?)
 * OR (...)` (OR-of-ANDs, same shape as NestedConnectionLoader for has-many, #38). Returns the FIRST child
 * row per parent (or null), grouped by the key tuple. Reads go through the replica clone inside the
 * request transaction; the row-scope seam applies.
 */
@CompileStatic
class NestedSingleLoader implements MappedBatchLoaderWithContext<Object, Object> {
    private final ExecutionContext ec
    private final String childEntityName
    private final List<String> fkFields
    private final boolean useClone
    private final int queryTimeoutSeconds
    private final int maxRowsPerLevel

    NestedSingleLoader(ExecutionContext ec, String childEntityName, List<String> fkFields,
                       boolean useClone, int queryTimeoutSeconds, int maxRowsPerLevel) {
        this.ec = ec; this.childEntityName = childEntityName; this.fkFields = fkFields
        this.useClone = useClone; this.queryTimeoutSeconds = queryTimeoutSeconds; this.maxRowsPerLevel = maxRowsPerLevel
    }

    @Override
    CompletionStage<Map<Object, Object>> load(Set<Object> keys, BatchLoaderEnvironment env) {
        EntityConditionFactory ecf = ec.entity.getConditionFactory()
        EntityFind ef = ec.entity.find(childEntityName)
        if (fkFields.size() == 1) {
            List<Object> vals = new ArrayList<Object>()
            for (Object k in keys) vals.add(k instanceof List ? ((List) k).get(0) : k)   // key may be raw or 1-tuple
            ef.condition(fkFields.get(0), EntityCondition.IN, vals)
        } else {
            List<EntityCondition> ors = new ArrayList<EntityCondition>()
            for (Object k in keys) {
                List tuple = (List) k
                List<EntityCondition> ands = new ArrayList<EntityCondition>()
                for (int i = 0; i < fkFields.size(); i++) ands.add(ecf.makeCondition(fkFields.get(i), EntityCondition.EQUALS, tuple.get(i)))
                ors.add(ecf.makeCondition(ands, EntityCondition.AND))
            }
            ef.condition(ecf.makeCondition(ors, EntityCondition.OR))
        }
        ef.useClone(useClone).queryTimeout(queryTimeoutSeconds)
                .maxRows(maxRowsPerLevel).fetchSize(Math.min(maxRowsPerLevel, 1000))
        ScopeFilters.apply(ef, childEntityName, ec)   // row-scope seam (phase-1 no-op)
        EntityList rows = ef.list()

        Map<Object, Object> out = new LinkedHashMap<Object, Object>()
        for (Object k in keys) out.put(k, null)            // default: no related object
        for (EntityValue ev in rows) {
            Object k = groupKey(ev)
            if (out.containsKey(k) && out.get(k) == null) out.put(k, ev.getMap())   // first row per parent
        }
        return CompletableFuture.completedFuture(out)
    }

    /** Group key matching the DataLoader key shape: a single raw value for one fk field, else a List tuple. */
    private Object groupKey(EntityValue ev) {
        if (fkFields.size() == 1) return ev.get(fkFields.get(0))
        List<Object> t = new ArrayList<Object>(fkFields.size())
        for (String f in fkFields) t.add(ev.get(f))
        return t
    }
}
```
- [ ] **Step 4 — run it, expect PASS:** `./gradlew :runtime:component:moqui-gql:test --tests NestedSingleLoaderCompositeTests` → **PASS** (BUILD SUCCESSFUL).
- [ ] **Step 5 — register the unit test** in `MoquiSuite.groovy` (`@SelectClasses`): add `NestedSingleLoaderCompositeTests.class` to the array.
- [ ] **Step 6 — commit:** `git add src/main/groovy/org/moqui/gql/exec/NestedSingleLoader.groovy src/test/groovy/NestedSingleLoaderCompositeTests.groovy src/test/groovy/MoquiSuite.groovy && git commit -m "feat(gql): NestedSingleLoader batches composite parent keys (#42)"`

---

## Task 3: wire the single-edge metadata + fetcher + registry for composite keys

**Files:** `GqlEngine.groovy`

> After this task the existing `BillToCustomerTests` (single-key has-one) must still pass — it now flows through 1-element `fkFields` (raw key, `IN` query): identical SQL/behavior. This task is verified by **regression** (the full suite stays green); Task 4 adds the new composite end-to-end test.

- [ ] **Step 1 — `nestedEdgeMetas()` single-edge branch: populate the key lists.** Replace the `if (e.single) { ... continue }` block with:
```groovy
                if (e.single) {   // single-object (has-one) edge: explicit child entity + fk (may be composite), no relationship
                    List<String> fkFields = (e.fkFields != null && !e.fkFields.isEmpty()) ? e.fkFields :
                            (e.fk != null ? [e.fk] as List<String> : new ArrayList<String>())
                    List<String> parentKeyFields = (e.parentKeyFields != null && !e.parentKeyFields.isEmpty()) ? e.parentKeyFields :
                            (e.parentKey != null ? [e.parentKey] as List<String> : new ArrayList<String>(fkFields))
                    if (fkFields.isEmpty() || parentKeyFields.size() != fkFields.size()) {
                        ec.logger.warn("gql: single edge ${t.name}.${e.name} not batchable (fk/parent-key " +
                                "missing or mismatched arity); skipping nested resolution")
                        continue
                    }
                    out.add(new NestedEdgeMeta(typeName: t.name, edgeName: e.name, loaderName: t.name + "." + e.name,
                            parentKeyField: parentKeyFields.get(0), childEntityName: e.childEntity,
                            fkField: fkFields.get(0), parentKeyFields: parentKeyFields, fkFields: fkFields,
                            intraGroupFields: new ArrayList<String>(), plain: false, single: true))
                    continue
                }
```
- [ ] **Step 2 — `buildRegistry`: pass `meta.fkFields` (list) to `NestedSingleLoader`.** Replace the `meta.single ? ...` ternary's has-one arm:
```groovy
            def loader = meta.single ?
                    new NestedSingleLoader(ec, meta.childEntityName, meta.fkFields, useClone, queryTimeoutSeconds, maxRowsPerLevel) :
                    new NestedConnectionLoader(ec, meta.childEntityName, meta.fkField, meta.intraGroupFields,
                            useClone, queryTimeoutSeconds, maxFirst, maxRowsPerLevel, meta.plain)
```
- [ ] **Step 3 — the nested-edge fetcher in `withFetchers`: build the parent key as raw value (1 field) or tuple (composite).** Replace the body of the `for (NestedEdgeMeta meta in metas)` fetcher closure:
```groovy
        // nested edge fetchers (has-many and has-one): defer to the per-request DataLoader (batched, no N+1)
        for (NestedEdgeMeta meta in metas) {
            final NestedEdgeMeta m = meta   // capture per-iteration value, not the loop variable
            code.dataFetcher(FieldCoordinates.coordinates(m.typeName, m.edgeName),
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
        }
```
- [ ] **Step 4 — compile:** `./gradlew :runtime:component:moqui-gql:compileGroovy` → BUILD SUCCESSFUL.
- [ ] **Step 5 — run the full suite, expect PASS (regression).** The single-key has-one (`billToCustomer`) flows through 1-element `fkFields` (raw key, `IN`) — identical behavior; `BillToCustomerTests` both cases stay green (including the "ONE batched has-one find for 5 orders" assertion).
`./gradlew :runtime:component:moqui-gql:test` → BUILD SUCCESSFUL.
- [ ] **Step 6 — commit:** `git add src/main/groovy/org/moqui/gql/GqlEngine.groovy && git commit -m "feat(gql): tuple keys for composite has-one edges; regression-clean for single-key (#42)"`

---

## Task 4: expose `ShipGroup.shippingMethod → CarrierShipmentMethod` + end-to-end test

**Files:** `graphql/OmsSchema.gql.xml`, `src/test/groovy/ShippingMethodTests.groovy` (NEW), `src/test/groovy/MoquiSuite.groovy`

The `ShipGroup` type is entity `org.apache.ofbiz.order.order.OrderItemShipGroup`, whose composite FK to `CarrierShipmentMethod` (the `ORDER_ITSG_CSHM` relationship) maps parent fields `shipmentMethodTypeId,carrierPartyId,carrierRoleTypeId` → child PK `shipmentMethodTypeId,partyId,roleTypeId`. Because the carrier-party axis is renamed between parent and child, the edge declares **explicit** `fk` (child side) and `parent-key` (parent side) lists — the single-edge path, not a relationship.

- [ ] **Step 1 — write a failing test.** Create `src/test/groovy/ShippingMethodTests.groovy`:
```groovy
import spock.lang.Specification
import spock.lang.Shared
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.gql.GqlEngine
import org.moqui.gql.scope.ScopeFilter
import org.moqui.gql.scope.ScopeFilters

/** #42 — ShipGroup.shippingMethod: a COMPOSITE-key has-one edge (parent
 *  shipmentMethodTypeId+carrierPartyId+carrierRoleTypeId -> CarrierShipmentMethod PK
 *  shipmentMethodTypeId+partyId+roleTypeId). Resolved by ONE batched OR-of-ANDs query (no N+1).
 *  Self-checking against real hcsd_notnaked: pick a ship group that HAS a carrier method. */
class ShippingMethodTests extends Specification {
    @Shared ExecutionContext ec
    @Shared String orderWithMethod, shipGroupWithMethod, expectMethodType, expectPartyId, expectRoleTypeId

    def setupSpec() {
        ec = Moqui.getExecutionContext(); ec.artifactExecution.disableAuthz()
        // a ship group whose composite FK points at an existing CarrierShipmentMethod row (inner join)
        def v = ec.entity.find("org.apache.ofbiz.order.order.OrderItemShipGroup")
                .condition("shipmentMethodTypeId", org.moqui.entity.EntityCondition.NOT_EQUAL, null)
                .condition("carrierPartyId", org.moqui.entity.EntityCondition.NOT_EQUAL, null)
                .condition("carrierRoleTypeId", org.moqui.entity.EntityCondition.NOT_EQUAL, null)
                .useClone(true).orderBy("orderId").orderBy("shipGroupSeqId").maxRows(50).fetchSize(50).list()
        for (def sg in v) {
            def csm = ec.entity.find("org.apache.ofbiz.shipment.shipment.CarrierShipmentMethod")
                    .condition("shipmentMethodTypeId", sg.shipmentMethodTypeId)
                    .condition("partyId", sg.carrierPartyId)
                    .condition("roleTypeId", sg.carrierRoleTypeId)
                    .useClone(true).maxRows(1).fetchSize(1).list()
            if (!csm.isEmpty()) {
                orderWithMethod = sg.orderId; shipGroupWithMethod = sg.shipGroupSeqId
                expectMethodType = sg.shipmentMethodTypeId; expectPartyId = sg.carrierPartyId; expectRoleTypeId = sg.carrierRoleTypeId
                break
            }
        }
    }
    def cleanupSpec() { if (ec != null) { ScopeFilters.reset(); ec.artifactExecution.enableAuthz(); ec.destroy() } }
    def cleanup() { ScopeFilters.reset() }

    def "shipGroup.shippingMethod resolves the composite-keyed CarrierShipmentMethod"() {
        given: "a ship group with a carrier method exists in this database"
        orderWithMethod != null

        when:
        def r = new GqlEngine(ec).execute(
                'query Q($id:ID!){ order(orderId:$id){ orderId shipGroups(first:50){ edges{ node{ ' +
                'shipGroupSeqId shippingMethod{ shipmentMethodTypeId partyId roleTypeId carrierServiceCode } } } } } }',
                [id: orderWithMethod], "Q")
        then:
        r.errors.isEmpty()
        def sg = r.data.order.shipGroups.edges.find { it.node.shipGroupSeqId == shipGroupWithMethod }
        sg != null
        sg.node.shippingMethod != null
        sg.node.shippingMethod.shipmentMethodTypeId == expectMethodType
        sg.node.shippingMethod.partyId == expectPartyId
        sg.node.shippingMethod.roleTypeId == expectRoleTypeId
    }

    def "shippingMethod is batched across ship groups (one composite has-one query per level, no N+1)"() {
        given: "a filter that records each entity find"
        orderWithMethod != null
        def seen = Collections.synchronizedList([])
        ScopeFilters.set({ String en, ec2 -> seen.add(en); return null } as ScopeFilter)
        when:
        def r = new GqlEngine(ec).execute(
                'query Q($id:ID!){ order(orderId:$id){ shipGroups(first:50){ edges{ node{ shipGroupSeqId ' +
                'shippingMethod{ shipmentMethodTypeId } } } } } }', [id: orderWithMethod], "Q")
        then:
        r.errors.isEmpty()
        // ONE batched has-one find over CarrierShipmentMethod for all ship groups in the level
        seen.count { it == "org.apache.ofbiz.shipment.shipment.CarrierShipmentMethod" } == 1
    }
}
```
- [ ] **Step 2 — run it, expect FAIL:** `./gradlew :runtime:component:moqui-gql:test --tests ShippingMethodTests` → **FAILS** (the `shippingMethod` field is not in the schema yet → graphql validation error, `r.errors` non-empty).
- [ ] **Step 3 — add the edge + leaf type to `graphql/OmsSchema.gql.xml`.** Inside the `ShipGroup` `<gql-type>` add the composite has-one edge:
```xml
    <gql-type name="ShipGroup" entity-name="org.apache.ofbiz.order.order.OrderItemShipGroup">
        <field name="shipGroupSeqId" type="ID"/>
        <field name="shipmentMethodTypeId"/>
        <field name="carrierPartyId"/>
        <field name="facilityId"/>
        <field name="contactMechId"/>
        <!-- composite-key has-one (#42): the carrier-specific shipping method. Parent FK
             (shipmentMethodTypeId, carrierPartyId, carrierRoleTypeId) -> CarrierShipmentMethod PK
             (shipmentMethodTypeId, partyId, roleTypeId). fk = child fields, parent-key = parent fields. -->
        <edge name="shippingMethod" target-type="ShippingMethod"
              entity-name="org.apache.ofbiz.shipment.shipment.CarrierShipmentMethod" single="true"
              fk="shipmentMethodTypeId,partyId,roleTypeId"
              parent-key="shipmentMethodTypeId,carrierPartyId,carrierRoleTypeId"/>
    </gql-type>
```
  and add the leaf `gql-type` (place it immediately after the `ShipGroup` type):
```xml
    <!-- Leaf columns of a carrier-specific shipping method (composite PK). Resolved as a has-one leaf
         object via the composite-key batched loader (#42). -->
    <gql-type name="ShippingMethod" entity-name="org.apache.ofbiz.shipment.shipment.CarrierShipmentMethod">
        <field name="shipmentMethodTypeId" type="ID"/>
        <field name="partyId" type="ID"/>
        <field name="roleTypeId" type="ID"/>
        <field name="sequenceNumber" type="Int"/>
        <field name="carrierServiceCode"/>
    </gql-type>
```
- [ ] **Step 4 — register the test** in `MoquiSuite.groovy` (`@SelectClasses`): add `ShippingMethodTests.class` to the array.
- [ ] **Step 5 — run it, expect PASS:** `./gradlew :runtime:component:moqui-gql:test --tests ShippingMethodTests` → **PASS** (with Tasks 1–3 in place the composite has-one loader resolves and batches).
- [ ] **Step 6 — run the full suite, expect PASS (no regression):** `./gradlew :runtime:component:moqui-gql:test` → BUILD SUCCESSFUL.
- [ ] **Step 7 — commit:** `git add graphql/OmsSchema.gql.xml src/test/groovy/ShippingMethodTests.groovy src/test/groovy/MoquiSuite.groovy && git commit -m "feat(gql): ShipGroup.shippingMethod via composite-key has-one batching (#42)"`

---

## Task 5: documentation

**Files:** `docs/schema.graphql`, `docs/examples.md`, `docs/STATUS.md`

- [ ] **Step 1 — `schema.graphql`:** add `ShipGroup.shippingMethod: ShippingMethod` and the `ShippingMethod` type (`shipmentMethodTypeId`, `partyId`, `roleTypeId`, `sequenceNumber`, `carrierServiceCode`); note it is a **composite-key has-one** edge (parent `shipmentMethodTypeId+carrierPartyId+carrierRoleTypeId` → `CarrierShipmentMethod`).
- [ ] **Step 2 — `examples.md`:** add an `order → shipGroups → shippingMethod` example; note that `shippingMethod` is resolved by one batched OR-of-ANDs query per level (no N+1), first row per parent tuple.
- [ ] **Step 3 — `STATUS.md`:** in the schema-surface / batching notes, record that **has-one** edges now batch **composite** parent keys (single + multi-field), validated by `ShipGroup.shippingMethod → CarrierShipmentMethod` (#42), the sibling of #38's has-many composite batching.
- [ ] **Step 4 — commit:** `git add docs/schema.graphql docs/examples.md docs/STATUS.md && git commit -m "docs(gql): composite-key has-one + ShipGroup.shippingMethod (#42)"`

---

## Acceptance criteria

- A composite-keyed has-one edge — `ShipGroup.shippingMethod → CarrierShipmentMethod` — resolves via **one** batched query (OR-of-ANDs over `shipmentMethodTypeId+partyId+roleTypeId`), returning the first row per parent tuple; **no N+1** (`ShippingMethodTests` asserts exactly one `CarrierShipmentMethod` find for all ship groups in the level).
- `Order.billToCustomer` and every single-key has-one edge are **unchanged** (1-element `fkFields` → raw key + `IN` query): `BillToCustomerTests` (both cases, including the one-find batching assertion) stays green.
- Composite key correctness: the fetcher's parent-key tuple (`parentKeyFields` read from the parent Map) and the loader's group key (`fkFields` read from each child row) are parallel and positionally aligned, even though parent and child field names differ on the carrier-party axis.
- No DB/index change. Full suite green vs MySQL `hcsd_notnaked`.

## Self-review notes (author)

- **Spec coverage (#42):** schema/parser accept composite `fk`/`parent-key` on a `single` edge (Task 1); `nestedEdgeMetas()` single-edge branch populates `parentKeyFields`/`fkFields` (Task 3 Step 1); `NestedSingleLoader` batches with the #38 tuple-condition shape and groups first-row-per-parent by the key tuple (Task 2); the has-one fetcher builds the tuple key, mirroring #38 (Task 3 Step 3); validating use case `ShipGroup.shippingMethod → CarrierShipmentMethod` composite PK (Task 4). All issue items mapped.
- **Sibling-of-#38 fidelity:** the condition shape (1 field → `IN`; composite → OR-of-ANDs), the tuple group-key, and the fetcher's raw-vs-tuple key construction are the **same** patterns #38 introduces for `NestedConnectionLoader` — applied here to the has-one path. `NestedEdgeMeta.parentKeyFields`/`fkFields` are reused (added by #38; Task 1 Step 3 adds them defensively if #38 has not yet merged — verify with the grep before editing to avoid a duplicate-field compile error). A future cleanup could factor the parent-key-tuple construction into one shared helper used by both loaders' fetchers; left out here to keep the change additive and the has-many path (owned by #38) untouched.
- **Explicit attributes vs relationship key-map:** the has-one single-edge path resolves from explicit `fk`/`parent-key` attributes (NOT a Moqui relationship) — this is load-bearing for the carrier case, where the parent (`OrderItemShipGroup`) names the join fields `carrierPartyId`/`carrierRoleTypeId` but the child (`CarrierShipmentMethod`) PK is `partyId`/`roleTypeId`. `parent-key` (parent side, read by the fetcher) and `fk` (child side, used in the WHERE) are therefore distinct lists; `parent-key` defaults to `fk` only when they coincide (the single-key `billToCustomer` case, `orderId`=`orderId`).
- **Regression safety:** single-key has-one runs the `fkFields.size() == 1` branch → exactly the previous `WHERE fk IN(:keys)` query with a raw group key — identical SQL/behavior; verified by the existing suite in Task 3 Step 5 and by `BillToCustomerTests`' explicit one-find assertion.
- **Test realism:** both new tests are self-checking against live `hcsd_notnaked` — the unit test keys off two real `CarrierShipmentMethod` rows; the e2e test first locates a ship group whose composite FK resolves to an existing carrier method (inner-join guarantee) and is guarded (`orderWithMethod != null`) so it is meaningful only when such data exists, never a false green on an empty result.
- **Prerequisite ordering:** build after (or alongside) #38. If executed before #38, Task 1 Step 3 supplies the `NestedEdgeMeta` list fields; if #38 already merged them, skip that step (grep first). Everything else in #42 is has-one-specific and independent of #38's `NestedConnectionLoader` changes.
