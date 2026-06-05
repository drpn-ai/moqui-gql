# moqui-gql — Capability Examples & Test-Case Catalog

**Status:** Requirements / design phase. This is the **source of truth for what the API must do**,
and the **basis for our test suite** — every example is written to double as a test case.
**Grounded in:** the real Maarg/HotWax OMS surface (see `requirements.md`), validated against
`notnaked`, `gorjana-maarg`, and the published REST/service/DataDocument inventory.

> These examples are illustrative GraphQL using clean type/field names. The actual schema
> artifacts are authored per deployment against the entities present (vanilla `mantle.*` or
> HotWax `org.apache.ofbiz.order.order.*`). Names here are the *contract*, not the wire entities.

---

## How to read an example

Each example carries:
- **Query** — the GraphQL a consumer sends.
- **Returns** — the expected response shape (abbreviated).
- **Maps to** — backing entity / view-entity / service.
- **Kinds** — field kinds exercised: `[DB]` entity field, `[VIEW]` view-entity type, `[SERVICE]`
  service-backed resolver, `[AGG]` aggregation (deferred — see Q2).
- **Cost** — `cheap` (indexed, bounded) · `moderate` (bounded nested lists) · `high` (capped,
  watch fan-out) · `rejected` (must be blocked by the governor).
- **Test asserts** — what an automated test verifies. This is why the catalog exists.

Cost classes map to governance: `cheap`/`moderate`/`high` must ALLOW and return; `rejected` must
return a structured, agent-actionable error (see §N).

**Resolved decisions baked into these examples (see requirements.md Part 4):**
- **All reads are DB-backed (Q1)** — no search-index entry point; product queries are structured
  DB filters, full-text search stays on existing Solr endpoints.
- **Analytics deferred (Q2)** — §K is a later opportunity, not initial scope.
- **Declare-and-control filtering (Q3)** — only schema-declared fields are filterable/sortable,
  each with an allowed operator set; undeclared/arbitrary filters are rejected (§N2).
- **Relay connections (Q4)** — every list field is a connection:
  `field(first, after) { edges { node { … } } pageInfo { hasNextPage endCursor } }`. For
  readability the nested examples below use the shorthand `field(first:N){ … }` to mean the
  `edges { node { … } }` selection; top-level feeds (§A2, §M) show the full connection form.
- **External-id is first-class (Q5)** — see §J.

---

## A. Order

### A1 — Order detail (CS agent / order screen)
```graphql
{ order(id:"10001"){
    name status grandTotal currency customerName
    billingAddress{ address1 city stateProvince postalCode }
    items(first:50){ productId quantity unitPrice fulfillmentStatus promisedDate }
    shipGroups(first:10){ shipmentMethod carrierPartyId trackingCode facility{ name } }
    payments(first:10){ methodType amount status }
    statuses(first:20){ statusId statusDate } } }
```
**Returns:** one order object with nested `items[]`, `shipGroups[]`, `payments[]`, `statuses[]`, `billingAddress{}`.
**Maps to:** OrderHeader + `items`/`shipGroups`/`paymentPreferences`/`statuses`/`contactMechs` edges; `customerName`+`fulfillmentStatus` service-backed; billingAddress via OrderContactMech→PostalAddress (purpose-joined view).
**Kinds:** `[DB][VIEW][SERVICE]` · **Cost:** moderate
**Test asserts:** order 10001 returns; `items.length == seeded count`; `customerName` non-null; each `items[].fulfillmentStatus ∈ {PROCESSING,COMPLETED,CANCELLED,...}`; billingAddress.city present.

### A2 — Open-orders queue (ops), indexed filter + pagination
```graphql
{ orders(filter:{ statusId:"ORDER_APPROVED", placedAfter:"2026-05-01" }, first:25){
    id name orderDate grandTotal customerName } }
```
**Maps to:** OrderHeader; filter on `statusId`+`placedDate` (both indexed). **Kinds:** `[DB][SERVICE]` · **Cost:** cheap
**Test asserts:** only ORDER_APPROVED & placedDate≥date returned; `length ≤ 25`; results sorted deterministically.

---

## B. Fulfillment & shipping

### B1 — Shipment with tracking
```graphql
{ shipment(id:"SH900"){ status originFacility{ name }
    packages(first:20){ trackingCode labelImageUrl contents(first:50){ productId quantity } }
    routeSegments(first:5){ carrierPartyId trackingIdNumber trackingUrl } } }
```
**Maps to:** Shipment → ShipmentPackage → ShipmentPackageContent; ShipmentRouteSegment (tracking). *(Tracking lives on the route segment — there is no AfterShip tracking object in OMS.)*
**Kinds:** `[DB][VIEW]` · **Cost:** moderate
**Test asserts:** packages nest contents; `routeSegments[].trackingIdNumber` present when shipped.

### B2 — BOPIS pickup queue (store dashboard)
```graphql
{ pickupOrders(facilityId:"STORE_07", first:50){ id name customerName
    shipGroups(first:5){ picklistId picker{ firstName lastName } items(first:50){ productId quantity } } } }
```
**Maps to:** `get#PickupOrders` / `OrderHeaderShipGroupShipment` + `PicklistShipmentAndRole` views. **Kinds:** `[VIEW][SERVICE]` · **Cost:** moderate
**Test asserts:** only orders for STORE_07; each shipGroup has picklist/picker when assigned.

### B3 — Ready-to-pick warehouse queue (Gorjana) — dynamic filter + sort
```graphql
{ readyToPickOrders(filter:{ facilityId:"WH1", brandName:"Fine", isGift:true,
    shipmentMethod:"STANDARD" }, sortBy:["-orderDate"], first:100){
    orderId orderName netsuiteOrderName shipToState } }
```
**Maps to:** `ReadyToPickWarehouseOrder` view (custom fields `isGift`, `brandName`; `netsuiteOrderName` via OrderIdentification).
**Kinds:** `[VIEW]` + dynamic filter (requirements Q3) · **Cost:** moderate (filter fields must be declared/indexed)
**Test asserts:** filter by custom fields works; sort applied; client-specific fields resolve only in Gorjana schema.

### B4 — Picklist with items
```graphql
{ picklist(id:"PICK55"){ statusId picklistDate items(first:200){ orderName productId quantity itemStatus } } }
```
**Maps to:** `PicklistItemView`. **Kinds:** `[VIEW]` · **Cost:** moderate
**Test asserts:** items belong to PICK55; itemStatus reflects order item status.

---

## C. Returns

### C1 — RMA detail / returns feed
```graphql
{ returns(filter:{ statusId:"RETURN_REQUESTED" }, first:100){
    returnId status identifications(first:5){ type value }
    items(first:100){ orderId productId returnQuantity refundAmount } } }
```
**Maps to:** ReturnHeader → `items`, `identifications` (incl. AFTERSHIP_RTN_ID, NetSuite RMA). **Kinds:** `[DB]` · **Cost:** moderate
**Test asserts:** only RETURN_REQUESTED; identifications expose external ids; refundAmount present.

---

## D. Transfers & purchasing

### D1 — Transfer orders awaiting approval
```graphql
{ transferOrders(filter:{ statusId:"ORDER_CREATED", facilityId:"WH1" }, first:25){
    orderId items(first:100){ productId quantity } shipments(first:10){ shipmentId status } } }
```
**Maps to:** `get#TransferOrders` + TransferOrder views. **Kinds:** `[VIEW][SERVICE]` · **Cost:** moderate
**Test asserts:** status+facility filter; items and shipments nest.

### D2 — Purchase order receipts
```graphql
{ purchaseOrder(id:"PO77"){ orderId receipts(first:100){ shipmentReceiptId productId quantityAccepted } } }
```
**Maps to:** PurchaseOrder → ShipmentReceipt. **Kinds:** `[DB]` · **Cost:** cheap
**Test asserts:** receipts belong to PO77; quantityAccepted numeric.

---

## E. Inventory & ATP (service-backed)

### E1/E2/E3 — availability checks + on-hand rollup
```graphql
{ checkBopisInventory(facilityId:"STORE_07", productId:"P1", quantity:2){ available atp }
  productOnlineAtp(productId:"P1", productStoreId:"ONLINE"){ atp }
  facilityInventory(facilityId:"WH1", productId:"P1"){ quantityOnHand atp safetyStock } }
```
**Maps to:** `get#BopisInventory`, `get#ProductOnlineAtp` (service); `ProductFacilityView` (view/agg).
**Kinds:** `[SERVICE][VIEW][AGG]` · **Cost:** high (service-backed → fixed high cost; not statically analyzable)
**Test asserts:** `available` boolean; `atp` numeric; service-backed fields carry high cost in the estimate.

---

## F. Catalog (DB-backed structured filters — Q1 resolved: DB only)

### F1 — Product lookup by category / identification / type (structured DB filter)
```graphql
{ products(filter:{ primaryCategoryId:"TOPS", productTypeId:"FINISHED_GOOD" }, first:20){
    edges{ node{ productId productName identifications(first:5){ type value } } }
    pageInfo{ hasNextPage endCursor } } }
```
**Maps to:** Product + GoodIdentification (SKU/UPC), filtered on declared+indexed fields. **Kinds:** `[DB]` · **Cost:** cheap
**Test asserts:** filters by declared fields (category/type); look up by `identifications.value` = SKU works; only declared-filterable fields accepted (others → §N2).
**Note (Q1):** keyword / faceted **full-text** product search is NOT exposed via GraphQL — it stays
on the existing Solr endpoints (`run#SolrQuery`, facet autocomplete). GraphQL does structured DB
filtering only.

### F2 — Product detail
```graphql
{ product(id:"P1"){ productName categories(first:10){ categoryId }
    identifications(first:10){ type value } variants(first:50){ productId } } }
```
**Maps to:** Product + `OmsProduct` DataDocument shape. **Kinds:** `[DB][VIEW]` · **Cost:** moderate
**Test asserts:** identifications include SKU/UPC; variants list non-empty for a virtual product.

---

## G. Facility & store config

### G1/G2
```graphql
{ facility(id:"WH1"){ name type address{ city stateProvince }
    locations(first:50){ locationSeqId } carriers(first:20){ partyId } productStores(first:10){ productStoreId } }
  productStore(id:"ONLINE"){ shipmentMethods(first:20){ shipmentMethodType carrierPartyId } } }
```
**Maps to:** Facility (+ FacilityCarrier view, locations, groups); ProductStore + ProductStoreShipmentMethod. **Kinds:** `[DB][VIEW]` · **Cost:** moderate
**Test asserts:** carriers via FacilityParty(role=CARRIER) view; shipmentMethods resolve.

---

## H. Cycle count
```graphql
{ cycleCount(id:"CC1"){ status facilityId items(first:500){ locationSeqId productId countedQuantity variance } } }
```
**Maps to:** WorkEffort/InventoryCountImport → InventoryCountImportItem. **Kinds:** `[DB]` · **Cost:** moderate (large `first:` — watch row cap)
**Test asserts:** items ≤ row cap; variance computed.

---

## I. Order routing
```graphql
{ orderRoutingGroup(id:"RG1"){ status
    routings(first:20){ orderRoutingId rules(first:50){ field operator value } }
    runs(first:10){ runId runDate orderCount } } }
```
**Maps to:** OrderRoutingGroup → OrderRouting → OrderRoutingRule + runs. **Kinds:** `[DB][VIEW]` · **Cost:** moderate
**Test asserts:** rules nest under routings; runs ordered by date.

---

## J. External-ID lookup (requirements Q5)
```graphql
{ orderByIdentification(type:"NETSUITE_ORDER", value:"SO12345"){ id name status }
  order(externalId:"shopify:4567890"){ id status } }
```
**Maps to:** OrderIdentification lookup; OrderHeader.externalId. **Kinds:** `[DB]` · **Cost:** cheap (indexed id)
**Test asserts:** resolves the same order via NetSuite id and Shopify external id.

---

## K. Analytics / BI — DEFERRED (Q2 resolved: pick up later)
> **Out of the initial scope.** We revisit analytics after we have good usage examples from the
> user group. `oms-bi` has fact/dimension tables but **no query API today**, so this is a clean
> build-new opportunity when we get to it — kept here only to record the shape.
```graphql
# illustrative only — NOT in initial scope
{ fulfillmentMetrics(facilityId:"WH1", dateFrom:"2026-05-01", dateTo:"2026-05-31", groupBy:DAY){
    date unitsShipped avgFulfillmentHours cancelRate } }
```
**Maps to:** `OrderItemFulfillmentFact` (aggregated). **Kinds:** `[AGG]` · **Cost:** high (distinct cost model)
**Facts that will back it later:** cancel rate, return rate (`ReturnItemFact`), inventory velocity (`InventoryItemDetailFact`), carrier performance.

---

## L. AI-agent composites

### L1 — "which of this customer's orders are stuck in processing?"
```graphql
{ orders(filter:{ customerId:"CUST_88", statusId:"ORDER_APPROVED" }, first:20){
    id orderDate items(first:50){ productId promisedDate fulfillmentStatus } } }
```
**Test asserts:** agent-composed query validates; computed `fulfillmentStatus` present for reasoning.

### L2 — one-shot multi-root context
```graphql
{ order(id:"10042"){ name status } customer(id:"CUST_88"){ name email orderCount } }
```
**Test asserts:** two root fields resolve in one request; `orderCount` is service-backed/aggregate.

---

## M. Partner feeds (Relay connection — Q4 resolved: in scope)
```graphql
{ orders(filter:{ statusId:"ORDER_APPROVED" }, first:100, after:"$cursor"){
    pageInfo{ hasNextPage endCursor }
    edges{ cursor node{
      id externalId
      shipGroups(first:10){ edges{ node{ facility{ id } items(first:100){ edges{ node{ productId quantity } } } } } } } } } }
```
**Maps to:** `get#BrokeredOrders` feed pattern; `WebhookOrderStatus` DataDocument shape. **Kinds:** `[DB][VIEW]` · **Cost:** high (capped)
**Test asserts:** cursor pages don't overlap or skip; `hasNextPage` false on last page; stable ordering; `edges[].cursor` round-trips as `after`.

---

## N. Guardrail boundary — MUST be rejected

| # | Query | Expected error (agent-actionable) |
|---|-------|-----------------------------------|
| N1 | `{ orders(first:1000){ items(first:1000){ adjustments(first:1000){ id } } } }` | `query cost 1,000,000,000 exceeds max 1000` |
| N2 | `{ orders(filter:{ orderName:"Gift" }, first:50){ id } }` | `filter field 'orderName' is not declared filterable — allowed: statusId, placedDate, customerId` (Q3 declare-and-control) |
| N3 | `{ orders { items { productId } } }` | `list field 'items' requires 'first:' (≤100)` |
| N4 | query depth 8 | `depth 8 exceeds max 6` |

**Test asserts:** each returns a structured error (not data), error text names the offending field/limit, and nothing executes against the DB.

---

## Coverage matrix (domain × capability)

| Domain | by-id | list+filter | nested | computed | view | ext-id | feed |
|---|---|---|---|---|---|---|---|
| Order | A1 | A2 | A1 | A1 | A1 | J | M |
| Shipment | B1 | — | B1 | — | B1 | — | M |
| Picklist/Pick | B4 | B3 | B4 | — | B3,B4 | — | — |
| Returns | — | C1 | C1 | — | — | C1 | — |
| Transfer/PO | D2 | D1 | D1,D2 | — | D1 | — | — |
| Inventory/ATP | — | — | — | E1,E2 | E3 | — | — |
| Catalog | F2 | F1 | F2 | — | F2 | F2 | — |
| Facility/Store | G1 | — | G1 | — | G1 | — | — |
| CycleCount | H1 | — | H1 | — | — | — | — |
| Routing | I1 | — | I1 | — | I1 | — | — |

Empty cells = not applicable. **Analytics/aggregation (§K) and full-text search are out of the
initial scope** (Q1: DB-only; Q2: analytics deferred), so they are not columns here.

---

## Decisions baked in (resolved 2026-06-03 — see requirements.md Part 4)

- **Q1 — DB-backed only.** F1 is a structured DB filter; full-text search stays on Solr endpoints.
- **Q2 — analytics deferred.** §K illustrative only, out of initial scope.
- **Q3 — declare-and-control.** Only declared fields filter/sort, with allowed operators (§N2).
- **Q4 — Relay connections in scope.** List fields are connections (§M full form; nested examples shorthand).
- **Q5 — external-id is a must-have.** §J first-class.

## Out of scope (not represented as queries)
Writes/mutations, analytics/aggregation (deferred), full-text/faceted Solr search, `unigate` RPC
(rate/label/refund/email), print/export (PDF/CSV), inbound webhooks (Shopify/ADP/AfterShip), live
external assembly (Shopify GraphQL pass-through).
