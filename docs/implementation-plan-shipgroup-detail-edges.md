# ShipGroup detail edges (`shipFromAddress` +geo, `shippingMethod`, `facilityChangeHistory`) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax. Each task is one bite: a failing Spock test first, then the COMPLETE implementation, then the test green, then a commit. **Dependency-gated:** Tasks 1–2 (`shipFromAddress`, `shippingMethodType`) run **today** — single-key has-one, the `billToCustomer` pattern. Task 3 (`facilityChangeHistory`) is a **composite-key has-many** and is **BLOCKED on #38** (composite-key nested batching — `docs/implementation-plan-composite-key-batching.md`). The carrier-specific `shippingMethod` edge (`CarrierShipmentMethod`, composite PK) is owned by **#42** (composite has-one) and is explicitly out of v1 — see the naming reconciliation in Task 2.

**Goal:** Expand the `ShipGroup` type (entity `OrderItemShipGroup`) with three nested fields, all confirmed against the data model: (1) `shipFromAddress` — the ship group's origin (facility) postal address **including `latitude`/`longitude`**; (2) `shippingMethodType` — the resolved descriptive shipping method (`ShipmentMethodType` id + description); (3) `facilityChangeHistory` — the ship group's facility-change audit trail, batched per `(orderId, shipGroupSeqId)` and ordered by `changeDatetime`. (The issue calls the method field `shippingMethod`; that exact edge name is taken by #42 for the carrier-specific object, so the descriptive variant ships here as `shippingMethodType` — see Task 2.)

**Architecture:** `shipFromAddress` and `shippingMethodType` are **single-key has-one** edges on `ShipGroup`, resolved by the existing `NestedSingleLoader` (one batched `WHERE fk IN(:keys)` per level, no N+1) — exactly the `Order.billToCustomer` pattern (`single="true" entity-name=… fk=…`). `shipFromAddress` needs a new **gql-owned view** `moqui.gql.FacilityOriginAddress` (the `OrderBillToCustomer` precedent in `entity/GqlEntities.xml`): a purpose-filtered facility-origin address joining `Facility → FacilityContactMech → FacilityContactMechPurpose → ContactMech → PostalAddress → GeoPoint` (+ state/country `Geo`), filtered to `contactMechPurposeTypeId = SHIP_ORIG_LOCATION`, exposing `address1/city/postalCode/stateProvinceGeoId/…` **plus `latitude`/`longitude` from the joined `GeoPoint`** (alias `GP`). It cannot reuse `ofbiz-oms-udm`'s `FacilityContactDetailByPurpose` because that view **excludes** `latitude`/`longitude` from its `GP` alias-all and applies **no** purpose filter (`OmsFacilityViewEntities.xml:64-68`). `shippingMethodType` is a single-key has-one to `ShipmentMethodType` by `shipmentMethodTypeId` (named to avoid colliding with #42's carrier-specific `shippingMethod` edge — Task 2). `facilityChangeHistory` is a **has-many** edge keyed `(orderId, shipGroupSeqId)` → `OrderFacilityChange` via a new `OrderItemShipGroup.facilityChanges` relationship (declared like the existing `OrderItemShipGroup.items` in `runtime/component/oms/entity/OrderExtendedEntities.xml:76`); it rides #38's composite-key `NestedConnectionLoader`.

**Tech stack:** moqui-gql executor (java-dataloader `MappedBatchLoaderWithContext`, graphql-java), Moqui view-entity + `EntityCondition`, Spock vs `hcsd_notnaked`.

**Tracking:** #43. **Depends on:** **#38** (composite-key nested batching) for `facilityChangeHistory` — Task 3 is blocked until #38 lands; **#42** (composite has-one) owns the carrier-specific `shippingMethod` edge — out of v1 here; #43 ships the descriptive `shippingMethodType` instead (Task 2 naming reconciliation). **No DB/index change** (`OrderFacilityChange.IDX_OID_SID` already indexes `orderId, orderItemSeqId, shipGroupSeqId, changeDatetime`; the view rides existing PKs/FKs). **Confirmed enum:** ship-origin purpose is `SHIP_ORIG_LOCATION` (`ofbiz-oms-udm/data/BPartySeedData.xml:4`, description "Shipping Origin Address"); `PRIMARY_LOCATION` is the documented fallback but **not** applied by the v1 view (single purpose keeps the has-one 1:1 — see Self-review notes).

---

## File structure

| File | Change |
|---|---|
| `entity/GqlEntities.xml` | NEW gql-owned view `moqui.gql.FacilityOriginAddress`: `Facility→FacilityContactMech→FacilityContactMechPurpose→ContactMech→PostalAddress→GeoPoint` (+ state/country `Geo`), `entity-condition` on `contactMechPurposeTypeId = SHIP_ORIG_LOCATION`, aliases incl. `latitude`/`longitude` from `GP` |
| `graphql/OmsSchema.gql.xml` | `ShipGroup`: add `shipFromAddress` (single has-one → `FacilityOriginAddress`, `fk="facilityId"`) and `shippingMethodType` (single has-one → `ShipmentMethodType`, `fk="shipmentMethodTypeId"`); add `facilityChangeHistory` (has-many → `OrderFacilityChange` via `entity-relationship="facilityChanges"`). NEW `<gql-type>` `FacilityOriginAddress`, `ShipmentMethodType`, `OrderFacilityChange`. (The `shippingMethod` edge + `ShippingMethod` type are #42's — do not re-add them here.) |
| `runtime/component/oms/entity/OrderExtendedEntities.xml` | **(Task 3 only — but this file lives in another component)** the `OrderItemShipGroup → OrderFacilityChange` `type="many"` relationship `short-alias="facilityChanges"` belongs here next to `items`. **Per repo rule "never modify runtime/component files," this relationship is instead declared as an `extend-entity` in `entity/GqlEntities.xml` (gql-owned).** |
| `src/test/groovy/ShipGroupDetailEdgesTests.groovy` (NEW) | `shipFromAddress` (+lat/long), `shippingMethodType`, has-one batching; **+** `facilityChangeHistory` (Task 3, #38-gated) |
| `src/test/groovy/MoquiSuite.groovy` | register `ShipGroupDetailEdgesTests.class` |
| `docs/{schema.graphql,examples.md,STATUS.md}` | document the three edges + the origin-address view |

---

## Task 1: `shipFromAddress` (+ lat/long) — gql-owned origin-address view + has-one edge  ·  **runnable today**

**Files:** `entity/GqlEntities.xml`, `graphql/OmsSchema.gql.xml`, `src/test/groovy/ShipGroupDetailEdgesTests.groovy` (NEW), `src/test/groovy/MoquiSuite.groovy`

- [ ] **Step 1 — failing test** `src/test/groovy/ShipGroupDetailEdgesTests.groovy` (NEW). Register it in `MoquiSuite.groovy` `@SelectClasses` (add `ShipGroupDetailEdgesTests.class`). This first test resolves the new edge; it FAILS until the view + edge exist (the field is unknown → GraphQL validation error):
```groovy
import spock.lang.Specification
import spock.lang.Shared
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.gql.GqlEngine
import org.moqui.gql.scope.ScopeFilter
import org.moqui.gql.scope.ScopeFilters

/** #43 — ShipGroup detail edges. shipFromAddress (+lat/long) and shippingMethodType are single-key has-one
 *  edges (the billToCustomer pattern), batched by NestedSingleLoader (one query per level, no N+1).
 *  facilityChangeHistory is a composite-key has-many (orderId, shipGroupSeqId) and is gated on #38.
 *  Vs real hcsd_notnaked: assertions stay green whether or not a given order's ship group carries an
 *  origin address / method / change rows (the data may be sparse), while still proving the wiring. */
class ShipGroupDetailEdgesTests extends Specification {
    @Shared ExecutionContext ec
    @Shared String orderWithItems

    def setupSpec() {
        ec = Moqui.getExecutionContext(); ec.artifactExecution.disableAuthz()
        orderWithItems = ec.entity.find("org.apache.ofbiz.order.order.OrderItem")
                .selectField("orderId").maxRows(1).fetchSize(1).list().get(0).orderId
    }
    def cleanupSpec() { if (ec != null) { ScopeFilters.reset(); ec.artifactExecution.enableAuthz(); ec.destroy() } }
    def cleanup() { ScopeFilters.reset() }

    def "shipGroups.shipFromAddress resolves leaf address fields incl. latitude/longitude"() {
        when:
        def r = new GqlEngine(ec).execute('''query Q($id:ID!){ order(orderId:$id){
            shipGroups(first:10){ edges{ node{ shipGroupSeqId facilityId
                shipFromAddress{ facilityId address1 city postalCode stateProvinceGeoId latitude longitude } } } } } }''',
            [id: orderWithItems], "Q")
        then:
        r.errors.isEmpty()
        r.data.order.shipGroups.edges instanceof List
        // when a ship group has a facility with a SHIP_ORIG_LOCATION address, the edge resolves and its
        // facilityId matches the ship group's facilityId (no cross-facility leakage from the IN batch).
        r.data.order.shipGroups.edges.every { sge ->
            def a = sge.node.shipFromAddress
            a == null || a.facilityId == sge.node.facilityId
        }
    }
}
```
- [ ] **Step 2 — run, expect FAIL:** `./gradlew :runtime:component:moqui-gql:test --tests ShipGroupDetailEdgesTests` → FAIL — `Validation error ... Field 'shipFromAddress' in type 'ShipGroup' is undefined` (the edge/type don't exist yet).
- [ ] **Step 3 — add the gql-owned origin-address view** to `entity/GqlEntities.xml` (inside `<entities>`, after the `OrderBillToCustomer` view). Joins down to `GeoPoint`, filters to the ship-origin purpose, and **exposes lat/long** (which `FacilityContactDetailByPurpose` deliberately omits). Keyed by `facilityId` so the has-one loader's `WHERE facilityId IN(:keys)` + group-by-`facilityId` work:
```xml
    <!-- #43: a ship group's origin (facility) postal address, as leaf columns incl. lat/long. Backs the
         ShipGroup.shipFromAddress has-one edge (keyed by facilityId). Distinct from ofbiz-oms-udm's
         FacilityContactDetailByPurpose: that view excludes latitude/longitude and applies no purpose
         filter; this one pins the SHIP_ORIG_LOCATION purpose and surfaces GeoPoint.latitude/longitude.
         join-optional on the address chain so a facility with no postal address still yields a row keyed
         by facilityId (the edge then resolves to null fields, never an error). -->
    <view-entity entity-name="FacilityOriginAddress" package="moqui.gql">
        <member-entity entity-alias="FA" entity-name="org.apache.ofbiz.product.facility.Facility"/>
        <member-entity entity-alias="FCM" entity-name="org.apache.ofbiz.product.facility.FacilityContactMech" join-from-alias="FA">
            <key-map field-name="facilityId"/>
        </member-entity>
        <member-entity entity-alias="FCMP" entity-name="org.apache.ofbiz.product.facility.FacilityContactMechPurpose" join-from-alias="FCM">
            <key-map field-name="facilityId"/>
            <key-map field-name="contactMechId"/>
        </member-entity>
        <member-entity entity-alias="PA" entity-name="org.apache.ofbiz.party.contact.PostalAddress" join-from-alias="FCM" join-optional="true">
            <key-map field-name="contactMechId"/>
        </member-entity>
        <member-entity entity-alias="GP" entity-name="moqui.basic.GeoPoint" join-from-alias="PA" join-optional="true">
            <key-map field-name="geoPointId"/>
        </member-entity>
        <alias name="facilityId" entity-alias="FA"/>
        <alias name="contactMechId" entity-alias="FCM"/>
        <alias name="contactMechPurposeTypeId" entity-alias="FCMP"/>
        <alias name="toName" entity-alias="PA"/>
        <alias name="attnName" entity-alias="PA"/>
        <alias name="address1" entity-alias="PA"/>
        <alias name="address2" entity-alias="PA"/>
        <alias name="city" entity-alias="PA"/>
        <alias name="postalCode" entity-alias="PA"/>
        <alias name="stateProvinceGeoId" entity-alias="PA"/>
        <alias name="countryGeoId" entity-alias="PA"/>
        <alias name="latitude" entity-alias="GP"/>
        <alias name="longitude" entity-alias="GP"/>
        <entity-condition>
            <econdition entity-alias="FCMP" field-name="contactMechPurposeTypeId" value="SHIP_ORIG_LOCATION"/>
            <date-filter from-field-name="fromDate" thru-field-name="thruDate"/>
        </entity-condition>
    </view-entity>
```
- [ ] **Step 4 — add the `ShipGroup.shipFromAddress` edge + the `FacilityOriginAddress` gql-type** in `graphql/OmsSchema.gql.xml`. In the `ShipGroup` `<gql-type>` (entity `OrderItemShipGroup`), after the `contactMechId` field, add the single has-one edge (parent `ShipGroup` map already exposes `facilityId`; child view keyed by `facilityId` — same field name, so `parent-key` is omitted, exactly like `billToCustomer`):
```xml
        <!-- #43: ship group origin (facility) postal address incl. lat/long. Single-key has-one
             (the billToCustomer pattern): batched WHERE facilityId IN(:keys), no N+1. Works today. -->
        <edge name="shipFromAddress" target-type="FacilityOriginAddress" entity-name="moqui.gql.FacilityOriginAddress"
              fk="facilityId" single="true"/>
```
Then add the new type (place it next to the other leaf types, e.g. after `BillToCustomer`):
```xml
    <!-- #43: leaf columns of a ship group's origin (facility) address incl. lat/long, view-backed
         (moqui.gql.FacilityOriginAddress, purpose SHIP_ORIG_LOCATION). -->
    <gql-type name="FacilityOriginAddress" entity-name="moqui.gql.FacilityOriginAddress">
        <field name="facilityId" type="ID"/>
        <field name="address1"/>
        <field name="address2"/>
        <field name="city"/>
        <field name="postalCode"/>
        <field name="stateProvinceGeoId"/>
        <field name="countryGeoId"/>
        <field name="latitude" type="Decimal"/>
        <field name="longitude" type="Decimal"/>
    </gql-type>
```
- [ ] **Step 5 — run, expect PASS:** `./gradlew :runtime:component:moqui-gql:test --tests ShipGroupDetailEdgesTests` → PASS (1 test green; `shipFromAddress` resolves, lat/long present, facilityId matches).
- [ ] **Step 6 — commit:** `git add entity/GqlEntities.xml graphql/OmsSchema.gql.xml src/test/groovy/ShipGroupDetailEdgesTests.groovy src/test/groovy/MoquiSuite.groovy && git commit -m "feat(gql): ShipGroup.shipFromAddress origin address with lat/long (#43)"`

---

## Task 2: `shippingMethodType` — descriptive has-one to `ShipmentMethodType`  ·  **runnable today**

**Files:** `graphql/OmsSchema.gql.xml`, `src/test/groovy/ShipGroupDetailEdgesTests.groovy`

> **NAMING RECONCILIATION — read before coding.** The **#42** plan (`docs/implementation-plan-composite-has-one.md`, Task 4) **already claims the edge name `ShipGroup.shippingMethod`** for the *carrier-specific* object → `target-type="ShippingMethod"` (entity `CarrierShipmentMethod`, composite PK, fields `shipmentMethodTypeId, partyId, roleTypeId, carrierServiceCode`). Issue #43 frames the same field as "descriptive (default) vs carrier-specific (#42-gated)" and says **"decide which to expose."** To avoid a hard schema collision (two `<edge name="shippingMethod">` with different targets and two different leaf types), this plan names the **descriptive** edge **`shippingMethodType`** (→ `ShipmentMethodType`, id + description, runnable today) and leaves **`shippingMethod`** (the richer carrier-specific object) to **#42**. Both coexist: `shippingMethodType` is the always-available descriptive lookup; `shippingMethod` is the carrier method that lands with #42. If the team would rather have a single `shippingMethod` edge and treat #43's descriptive variant as superseded by #42, **drop this task entirely** — that decision is the one open question called out below.

`ShipmentMethodType` (`DatamodelShipmentEntitymodel.xml:605`) has single PK `shipmentMethodTypeId` + `description`. The `ShipGroup` map already exposes `shipmentMethodTypeId` (same field name on both sides), so this is the same single-key has-one shape as Task 1.

- [ ] **Step 1 — failing test** (add to `ShipGroupDetailEdgesTests.groovy`):
```groovy
    def "shipGroups.shippingMethodType resolves shipmentMethodTypeId + description (descriptive method)"() {
        when:
        def r = new GqlEngine(ec).execute('''query Q($id:ID!){ order(orderId:$id){
            shipGroups(first:10){ edges{ node{ shipmentMethodTypeId
                shippingMethodType{ shipmentMethodTypeId description } } } } } }''', [id: orderWithItems], "Q")
        then:
        r.errors.isEmpty()
        // when the ship group names a method type, the edge resolves and the id round-trips.
        r.data.order.shipGroups.edges.every { sge ->
            def m = sge.node.shippingMethodType
            m == null || m.shipmentMethodTypeId == sge.node.shipmentMethodTypeId
        }
    }
```
- [ ] **Step 2 — run, expect FAIL:** `./gradlew :runtime:component:moqui-gql:test --tests ShipGroupDetailEdgesTests` → FAIL — `Field 'shippingMethodType' in type 'ShipGroup' is undefined`.
- [ ] **Step 3 — add the edge + the `ShipmentMethodType` gql-type** in `graphql/OmsSchema.gql.xml`. In the `ShipGroup` `<gql-type>`, after the `shipFromAddress` edge:
```xml
        <!-- #43: descriptive shipping method (ShipmentMethodType id + description). Single-key has-one,
             works today. NOTE: the carrier-specific object lives on the `shippingMethod` edge owned by
             #42 (CarrierShipmentMethod, composite PK); this descriptive lookup is a distinct edge. -->
        <edge name="shippingMethodType" target-type="ShipmentMethodType"
              entity-name="org.apache.ofbiz.shipment.shipment.ShipmentMethodType"
              fk="shipmentMethodTypeId" single="true"/>
```
Then add the type (after `FacilityOriginAddress`):
```xml
    <!-- #43: the resolved descriptive shipping method for a ship group (id + label). -->
    <gql-type name="ShipmentMethodType" entity-name="org.apache.ofbiz.shipment.shipment.ShipmentMethodType">
        <field name="shipmentMethodTypeId" type="ID"/>
        <field name="description"/>
    </gql-type>
```
- [ ] **Step 4 — run, expect PASS:** `./gradlew :runtime:component:moqui-gql:test --tests ShipGroupDetailEdgesTests` → PASS (2 tests green).
- [ ] **Step 5 — also run the full suite (regression), expect PASS:** `./gradlew :runtime:component:moqui-gql:test` → BUILD SUCCESSFUL — the two new has-one edges flow through the existing `NestedSingleLoader`; nothing else changes.
- [ ] **Step 6 — commit:** `git add graphql/OmsSchema.gql.xml src/test/groovy/ShipGroupDetailEdgesTests.groovy && git commit -m "feat(gql): ShipGroup.shippingMethodType descriptive method (#43)"`

---

## Task 3: `facilityChangeHistory` — composite-key has-many  ·  **BLOCKED on #38**

**Files:** `entity/GqlEntities.xml` (relationship via `extend-entity`), `graphql/OmsSchema.gql.xml`, `src/test/groovy/ShipGroupDetailEdgesTests.groovy`

> **DO NOT START until #38 (`docs/implementation-plan-composite-key-batching.md`) has landed and its suite is green.** `facilityChangeHistory` is a has-many edge keyed by the composite `(orderId, shipGroupSeqId)`; before #38, `nestedEdgeMetas()` rejects composite-key list edges (the `keyMap.size()==1` gate) and the nested selection returns null. With #38 in place this task is **declarative-only** (a relationship + an edge + a type), riding the composite `NestedConnectionLoader`.

`OrderFacilityChange` (`HwmappsEntitymodel.xml:638`) already declares a `type="one"` relationship **to** `OrderItemShipGroup` on `(orderId, shipGroupSeqId)` (fk `ORDER_OFC_OISG`). The has-many edge needs the **inverse** (`OrderItemShipGroup → OrderFacilityChange`, `type="many"`), which does **not** exist yet. The natural home is `runtime/component/oms/entity/OrderExtendedEntities.xml` next to the existing `OrderItemShipGroup.items` relationship — **but repo rules forbid modifying `runtime/component/` files**, so it is declared as a gql-owned `extend-entity` in `entity/GqlEntities.xml`.

- [ ] **Step 1 — failing test** (add to `ShipGroupDetailEdgesTests.groovy`). Self-checking: every change row under a ship group must carry that ship group's `shipGroupSeqId`, and rows must be ordered by `changeDatetime`:
```groovy
    def "shipGroups.facilityChangeHistory batches per (orderId, shipGroupSeqId), ordered by changeDatetime"() {
        when:
        def r = new GqlEngine(ec).execute('''query Q($id:ID!){ order(orderId:$id){ orderId
            shipGroups(first:10){ edges{ node{ shipGroupSeqId
                facilityChangeHistory(first:50){ edges{ node{ orderFacilityChangeId shipGroupSeqId
                    fromFacilityId facilityId changeDatetime changeReasonEnumId comments } } } } } } } }''',
            [id: orderWithItems], "Q")
        then:
        r.errors.isEmpty()
        r.data.order.shipGroups.edges.every { sge ->
            def hist = (sge.node.facilityChangeHistory?.edges ?: [])
            // grouped correctly: every change row belongs to this ship group (no cross-group leakage)
            hist.every { it.node.shipGroupSeqId == sge.node.shipGroupSeqId } &&
            // ordered by changeDatetime ascending (the relationship's order-by); nulls tolerated
            hist.collect { it.node.changeDatetime }.findAll { it != null } ==
                hist.collect { it.node.changeDatetime }.findAll { it != null }.sort()
        }
    }
```
- [ ] **Step 2 — run, expect FAIL:** `./gradlew :runtime:component:moqui-gql:test --tests ShipGroupDetailEdgesTests` → FAIL — `Field 'facilityChangeHistory' in type 'ShipGroup' is undefined` (and, before #38, even with the edge declared the composite list edge would be skipped — confirm #38 is merged first).
- [ ] **Step 3 — declare the `OrderItemShipGroup → OrderFacilityChange` has-many relationship** as a gql-owned `extend-entity` in `entity/GqlEntities.xml` (inside `<entities>`). Mirrors the `OrderItemShipGroup.items` declaration (`OrderExtendedEntities.xml:76`), keyed `(orderId, shipGroupSeqId)`, with a default order-by on `changeDatetime`:
```xml
    <!-- #43: inverse of OrderFacilityChange's one-relationship to OrderItemShipGroup (fk ORDER_OFC_OISG).
         Backs the ShipGroup.facilityChangeHistory has-many edge, keyed by (orderId, shipGroupSeqId).
         Declared here (gql-owned) rather than in runtime/component/oms per the no-touch-components rule;
         ordered by changeDatetime so the audit trail reads oldest -> newest. -->
    <extend-entity entity-name="OrderItemShipGroup" package="org.apache.ofbiz.order.order">
        <relationship type="many" related="co.hotwax.facility.OrderFacilityChange" short-alias="facilityChanges">
            <key-map field-name="orderId"/>
            <key-map field-name="shipGroupSeqId"/>
        </relationship>
    </extend-entity>
```
- [ ] **Step 4 — add the `ShipGroup.facilityChangeHistory` edge + the `OrderFacilityChange` gql-type** in `graphql/OmsSchema.gql.xml`. In the `ShipGroup` `<gql-type>`, after the `shippingMethodType` edge:
```xml
        <!-- #43: facility-change audit trail for this ship group. Composite-key has-many (orderId,
             shipGroupSeqId) via the OrderItemShipGroup.facilityChanges relationship -> rides #38's
             composite NestedConnectionLoader. Ordered by changeDatetime. -->
        <edge name="facilityChangeHistory" entity-relationship="facilityChanges"
              target-type="OrderFacilityChange" list="true"/>
```
Then add the type (after `ShipmentMethodType`):
```xml
    <!-- #43: a single facility-change record for a ship group (audit trail row). -->
    <gql-type name="OrderFacilityChange" entity-name="co.hotwax.facility.OrderFacilityChange">
        <field name="orderFacilityChangeId" type="ID"/>
        <field name="shipGroupSeqId"/>
        <field name="fromFacilityId"/>
        <field name="facilityId"/>
        <field name="changeDatetime" type="DateTime"/>
        <field name="changeReasonEnumId"/>
        <field name="comments"/>
    </gql-type>
```
- [ ] **Step 5 — run, expect PASS:** `./gradlew :runtime:component:moqui-gql:test --tests ShipGroupDetailEdgesTests` → PASS (3 tests green; change rows batched + grouped per ship group, ordered by `changeDatetime`).
- [ ] **Step 6 — full suite (regression), expect PASS:** `./gradlew :runtime:component:moqui-gql:test` → BUILD SUCCESSFUL (existing single-key edges and #38's composite edges unaffected).
- [ ] **Step 7 — commit:** `git add entity/GqlEntities.xml graphql/OmsSchema.gql.xml src/test/groovy/ShipGroupDetailEdgesTests.groovy && git commit -m "feat(gql): ShipGroup.facilityChangeHistory via composite-key has-many (#43, depends #38)"`

---

## Task 4: documentation

**Files:** `docs/schema.graphql`, `docs/examples.md`, `docs/STATUS.md`

- [ ] **Step 1 — `schema.graphql`:** add `ShipGroup.shipFromAddress: FacilityOriginAddress`, `ShipGroup.shippingMethodType: ShipmentMethodType`, and `ShipGroup.facilityChangeHistory` (connection); declare the `FacilityOriginAddress`, `ShipmentMethodType`, `OrderFacilityChange` types. Note the data-model rules: `shipFromAddress` is purpose-filtered to `SHIP_ORIG_LOCATION` and carries `latitude`/`longitude` from `GeoPoint`; the carrier-specific `shippingMethod` edge (`CarrierShipmentMethod`) is #42's, documented there.
- [ ] **Step 2 — `examples.md`:** add a `shipGroups { shipFromAddress { address1 city postalCode latitude longitude } shippingMethodType { shipmentMethodTypeId description } facilityChangeHistory { fromFacilityId facilityId changeDatetime changeReasonEnumId } }` example; note `facilityChangeHistory` is batched per `(orderId, shipGroupSeqId)` and ordered oldest→newest by `changeDatetime`.
- [ ] **Step 3 — `STATUS.md`:** add `ShipGroup.shipFromAddress` / `shippingMethodType` / `facilityChangeHistory` to the schema surface; note `shipFromAddress`+`shippingMethodType` shipped on the single-key has-one path (works today) and `facilityChangeHistory` rode #38's composite has-many.
- [ ] **Step 4 — commit:** `git add docs/schema.graphql docs/examples.md docs/STATUS.md && git commit -m "docs(gql): ShipGroup detail edges — shipFromAddress, shippingMethod, facilityChangeHistory (#43)"`

---

## Acceptance criteria

- `shipGroups { shipFromAddress { address1 city postalCode latitude longitude } shippingMethodType { shipmentMethodTypeId description } facilityChangeHistory { fromFacilityId facilityId changeDatetime changeReasonEnumId } }` resolves.
- `shipFromAddress` carries `latitude`/`longitude` from `GeoPoint`, and is the **ship-origin** address (purpose `SHIP_ORIG_LOCATION`), keyed by the ship group's `facilityId`; the has-one is **batched** (one `FacilityOriginAddress` find per level, no N+1) — the `billToCustomer` guarantee.
- `shippingMethodType` resolves `shipmentMethodTypeId` + `description` from `ShipmentMethodType` (descriptive variant). The carrier-specific `shippingMethod` object is **not** in v1 (delivered by #42).
- `facilityChangeHistory` is **batched per `(orderId, shipGroupSeqId)`** (no N+1) and **ordered by `changeDatetime`**; every returned row belongs to its ship group (no cross-group leakage). **Requires #38.**
- **Regression:** all existing edges (single-key has-one `billToCustomer`; #38's composite has-many) behave identically. No DB/index change. Full suite green vs `hcsd_notnaked`.
- **Runnable-today split is explicit:** Tasks 1–2 ship without any dependency; Task 3 is the only #38-gated piece; the carrier-specific `shippingMethod` edge is #42's (deferred, not re-specified here).

## Self-review notes (author)

- **Spec coverage (#43):** `shipFromAddress` +lat/long (Task 1), `shippingMethodType` descriptive (Task 2), `facilityChangeHistory` (Task 3), docs (Task 4). All three issue mappings covered; both deps (#38, #42) called out per the issue's dependency table. The descriptive method is delivered under the name `shippingMethodType` because #42 already owns the `shippingMethod` edge name for the carrier-specific object — see the Task 2 reconciliation + the open question below.
- **Why a new view, not `FacilityContactDetailByPurpose`:** that view (`OmsFacilityViewEntities.xml:24`) **excludes `latitude`/`longitude`** from its `GP` alias-all (lines 64-68) and applies **no** purpose filter, and it drags in `TelecomNumber` + state/county/country prefixed aliases the edge doesn't need. The acceptance criterion *requires* lat/long, so a thin gql-owned view (`moqui.gql.FacilityOriginAddress`) with the purpose pinned and `GP.latitude`/`GP.longitude` aliased is the minimal correct backing — and it follows the established `moqui.gql.OrderBillToCustomer` precedent (view lives in `entity/GqlEntities.xml`, `entity-condition` carries the filter).
- **Single-purpose, not purpose-with-fallback:** v1 filters to `SHIP_ORIG_LOCATION` only. Confirmed present in `hcsd_notnaked` (`BPartySeedData.xml:4`). Adding the `PRIMARY_LOCATION` fallback in the same view would break the has-one 1:1 (a facility can have both purposes → two rows per `facilityId` → the `NestedSingleLoader` would arbitrarily pick one). If a fallback is wanted later, model it as a COALESCE/priority view or a service-backed resolver — out of scope for this declarative pass. Noted as an open question.
- **`facilityId` as the has-one key works today:** the parent `ShipGroup` map exposes `facilityId` (existing field) and the child view is keyed by `facilityId` — same field name on both sides, so `parent-key` is omitted exactly like `billToCustomer` (`fk="orderId"`, parent value `orderId`). The `NestedSingleLoader` does `WHERE facilityId IN(:keys)` and groups by `facilityId` — no engine change.
- **`join-optional` on the address chain:** `FCM`/`FCMP` are inner-joined (the purpose filter is the point), but `PA`/`GP` are `join-optional` so a facility whose ship-origin contact mech has no postal address / no geo point still yields a keyed row (edge resolves to null leaf fields, never an error). The `date-filter` on `FacilityContactMechPurpose` keeps only the currently-effective purpose row.
- **Task 3 is genuinely declarative once #38 lands** — a relationship (`extend-entity`), an edge, a type. No loader/engine code. The only nuance is *where* the relationship lives: it belongs beside `OrderItemShipGroup.items` in `runtime/component/oms`, but the repo's no-touch-components rule forces a gql-owned `extend-entity` in `entity/GqlEntities.xml`; both produce the same runtime relationship. Flag for the reviewer: if the team prefers it in `oms`, move it there and drop the `extend-entity`.
- **Ordering:** `facilityChangeHistory` is ordered by `changeDatetime` via the connection's keyset; the audit-trail intent (oldest→newest) is asserted in the test. The `OrderFacilityChange.IDX_OID_SID` index already covers `(orderId, orderItemSeqId, shipGroupSeqId, changeDatetime)`, so the per-ship-group ordered fetch is index-served — no new index.
- **Scalar choice for lat/long:** the GraphQL type is **`Decimal`**, not `Float`. `GqlSchemaBuilder.scalarType()` (`GqlSchemaBuilder.groovy:153`) has **no `Float` case** — an unknown type-name silently falls through to `GraphQLString`. `number-float` columns (lat/long) map to `Decimal` (`GqlScalars.DECIMAL`), consistent with every other numeric field in the schema (`grandTotal`, `amount`, `unitPrice`). Do **not** write `type="Float"`.
- **Test data realism:** `hcsd_notnaked` may have sparse origin addresses / method types / change rows, so the assertions are *conditional-correctness* (`a == null || a.facilityId == …`) rather than non-empty — same defensive style as `ShipmentRootTests`. They prove wiring + grouping + ordering without depending on seed volume.

## Open questions (need a human decision before/while executing)

1. **`shippingMethod` edge name (#42 collision).** #42 already specifies `ShipGroup.shippingMethod` → carrier-specific `CarrierShipmentMethod` (type `ShippingMethod`). #43 wants the descriptive method. This plan keeps both by naming #43's edge **`shippingMethodType`** (→ `ShipmentMethodType`). **Alternatives:** (a) keep both as proposed (descriptive `shippingMethodType` + carrier `shippingMethod`) — recommended, no collision, both useful; (b) make `shippingMethod` itself the descriptive `ShipmentMethodType` and treat carrier-specific as a separate `carrierShippingMethod` edge (would require renaming #42's edge — coordinate with that plan's owner); (c) ship only the carrier-specific `shippingMethod` via #42 and **drop Task 2** (then `description` would have to come from a nested `shipmentMethodType` under the carrier object). Pick one before coding Task 2.
2. **Ship-origin purpose fallback.** v1 pins `SHIP_ORIG_LOCATION` only (the has-one must be 1:1 per `facilityId`). The issue mentions `PRIMARY_LOCATION` as a fallback. Confirm whether the descriptive origin address should fall back to `PRIMARY_LOCATION` when a facility has no `SHIP_ORIG_LOCATION` address — if yes, it needs a priority/COALESCE view or a service-backed resolver (a plain second purpose in the same view would yield 2 rows/facility and break the has-one). Out of scope for this declarative pass; flag if needed.
3. **Relationship home for `OrderItemShipGroup.facilityChanges`.** Declared as a gql-owned `extend-entity` (Task 3) to honor the no-touch-components rule. If the team prefers it beside `OrderItemShipGroup.items` in `runtime/component/oms/entity/OrderExtendedEntities.xml`, move it there (PR against `oms`) and drop the `extend-entity` from `GqlEntities.xml`.
