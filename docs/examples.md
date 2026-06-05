# moqui-gql — Capability Examples & Test-Case Catalog

**Status:** Requirements / design phase. This is the **source of truth for what the API does**
and the **basis for the test suite**. Every example is a `Need → Query → Output` triple so the
"after the engine is in place" world is unambiguous — and so each one drops straight into a test.
**Grounded in:** the real Maarg/HotWax OMS surface (`requirements.md`). Decisions Q1–Q5 are
resolved (DB-backed only; analytics deferred; declare-and-control filtering; Relay connections;
external-id must-have).

> Type/field names are the *contract* (clean GraphQL names). The wire entities differ per
> deployment (`mantle.*` vs HotWax `org.apache.ofbiz.order.order.*`); schema artifacts map them.

---

## How to read an example

Each example is:
- **Need** — the real job to be done (and, where useful, what it takes *today* without this engine).
- **Query** — the exact GraphQL sent.
- **Output** — the exact JSON returned (or the error, for rejected cases).
- **Footer** — `maps:` backing entity/view/service · `kind:` `[DB|VIEW|SERVICE]` · `cost:` class ·
  `test:` the assertion(s) a test makes.

### Global response conventions (apply to every example)

1. **Envelope.** Success → `{ "data": {…}, "extensions": {…} }`. Error → `{ "errors": [...], "data": null }`.
2. **Relay connections (Q4).** Every list field is a connection:
   ```graphql
   field(first: N, after: "<cursor>") { edges { cursor node { … } } pageInfo { hasNextPage endCursor } }
   ```
   In nested examples we use the shorthand `field(first:N){ … }` to mean the `edges{ node{ … } }`
   selection (so deep queries stay readable); §M shows the full connection form and cursoring.
3. **`extensions.cost` (Shopify-shaped)** rides on every successful response:
   ```json
   "extensions": { "cost": { "requestedQueryCost": 41, "actualQueryCost": 38,
     "throttleStatus": { "maximumAvailable": 1000, "currentlyAvailable": 962, "restoreRate": 50 } } }
   ```
   Shown in full for A1/A2; omitted below for brevity but **assume it is always present**.
4. **Errors are agent-actionable** — `message` names the offending field/limit; `extensions.code`
   is a stable machine code (`COST_EXCEEDED`, `FIELD_NOT_FILTERABLE`, `OPERATOR_NOT_ALLOWED`,
   `FIRST_REQUIRED`, `DEPTH_EXCEEDED`).

---

## Declared filter & sort operators (Q3 — declare-and-control)

A field is filterable/sortable **only if the schema artifact declares it**, and the declaration
fixes **which operators** are allowed. Anything else is rejected (see §N). Operators:
`eq`, `in`, `gte`, `lte`, `between`. Example declaration for the **Order** root:

| Field | Filter operators | Sort | Index backing | Notes |
|---|---|---|---|---|
| `orderId` | `eq`, `in` | — | PK | |
| `externalId` | `eq`, `in` | — | indexed | Shopify/host id |
| `statusId` | `eq`, `in` | — | indexed | |
| `placedDate` | `eq`, `gte`, `lte`, `between` | `asc`, `desc` | indexed | date-range |
| `customerId` | `eq`, `in` | — | indexed (OrderRole BILL_TO) | |
| `productStoreId` | `eq`, `in` | — | indexed | |
| `grandTotal` | — | `desc` | — | **sortable only**, not filterable |
| `orderName` | — | — | — | **retrievable only** — not filterable/sortable |

**Rule:** filtering on an undeclared field → `FIELD_NOT_FILTERABLE`; using an operator not in the
field's set (e.g. `placedDate: { like: … }` or `statusId: { gte: … }`) → `OPERATOR_NOT_ALLOWED`.
Each type declares its own table; this is the analyzer's primary control surface.

### Q3a — using allowed operators (in + between)
**Need:** "Approved or held orders for store ONLINE placed in May, newest first."
```graphql
{ orders(
    filter: { statusId: { in: ["ORDER_APPROVED","ORDER_HELD"] },
              productStoreId: { eq: "ONLINE" },
              placedDate: { between: ["2026-05-01","2026-05-31"] } },
    sort: [{ field: placedDate, dir: desc }], first: 2 ) {
    edges { node { id name statusId placedDate } } pageInfo { hasNextPage endCursor } } }
```
**Output:**
```json
{ "data": { "orders": {
  "edges": [
    { "node": { "id": "10042", "name": "NN10042", "statusId": "ORDER_APPROVED", "placedDate": "2026-05-28T16:10:00Z" } },
    { "node": { "id": "10039", "name": "NN10039", "statusId": "ORDER_HELD",     "placedDate": "2026-05-27T11:02:00Z" } }
  ],
  "pageInfo": { "hasNextPage": true, "endCursor": "b3JkZXI6MTAwMzk=" } } } }
```
**maps:** OrderHeader (declared+indexed filters) · **kind:** `[DB]` · **cost:** cheap ·
**test:** `in`/`between` honored; results sorted desc by placedDate; both statuses present; cursor returned.

### Q3b — disallowed operator is rejected
**Need:** guardrail — a caller tries a range on a field that only allows equality.
```graphql
{ orders(filter: { statusId: { gte: "ORDER_APPROVED" } }, first: 5){ edges { node { id } } } }
```
**Output:**
```json
{ "errors": [ { "message": "operator 'gte' not allowed on filter field 'statusId' (allowed: eq, in)",
  "extensions": { "code": "OPERATOR_NOT_ALLOWED", "field": "statusId", "allowed": ["eq","in"] } } ],
  "data": null }
```
**test:** rejected pre-execution; message lists allowed operators; nothing hits the DB.

---

## A. Order

### A1 — Order detail (CS agent / order screen)
**Need:** open one order with everything an agent needs. *Today:* `get#SalesOrder` + a few extra
calls; *after:* one query, exactly the fields wanted.
```graphql
{ order(id: "10001") {
    name statusId grandTotal currency customerName
    billingAddress { address1 city stateProvince postalCode }
    items(first: 50) { productId quantity unitPrice fulfillmentStatus promisedDate }
    shipGroups(first: 10) { shipmentMethod carrierPartyId trackingCode facility { name } }
    payments(first: 10) { methodType amount statusId } } }
```
**Output:**
```json
{ "data": { "order": {
  "name": "NN10001", "statusId": "ORDER_APPROVED", "grandTotal": 129.00, "currency": "USD",
  "customerName": "Jordan Lee",
  "billingAddress": { "address1": "123 Main St", "city": "Austin", "stateProvince": "TX", "postalCode": "78701" },
  "items": [
    { "productId": "NN-HOODIE-BLK-M", "quantity": 1, "unitPrice": 89.00, "fulfillmentStatus": "PROCESSING", "promisedDate": "2026-05-20T00:00:00Z" },
    { "productId": "NN-SOCK-3PK",     "quantity": 2, "unitPrice": 20.00, "fulfillmentStatus": "COMPLETED",   "promisedDate": "2026-05-18T00:00:00Z" }
  ],
  "shipGroups": [ { "shipmentMethod": "STANDARD", "carrierPartyId": "USPS", "trackingCode": "9400111899560000000000", "facility": { "name": "Dallas DC" } } ],
  "payments": [ { "methodType": "CREDIT_CARD", "amount": 129.00, "statusId": "PMNT_SETTLED" } ] } },
  "extensions": { "cost": { "requestedQueryCost": 64, "actualQueryCost": 61,
    "throttleStatus": { "maximumAvailable": 1000, "currentlyAvailable": 939, "restoreRate": 50 } } } }
```
**maps:** OrderHeader + `items`/`shipGroups`/`paymentPreferences` edges; `customerName`+`fulfillmentStatus` service-backed; billingAddress via OrderContactMech→PostalAddress (purpose view) · **kind:** `[DB][VIEW][SERVICE]` · **cost:** moderate ·
**test:** order 10001 resolves; 2 items; `customerName=="Jordan Lee"`; each `fulfillmentStatus` ∈ enum; `extensions.cost` present.

### A2 — Open-orders queue (ops), first page
**Need:** paged worklist of approved orders. *Today:* `/oms/orders` list + manual paging.
```graphql
{ orders(filter: { statusId: { eq: "ORDER_APPROVED" } }, sort: [{ field: placedDate, dir: asc }], first: 2) {
    edges { cursor node { id name placedDate grandTotal customerName } }
    pageInfo { hasNextPage endCursor } } }
```
**Output:**
```json
{ "data": { "orders": {
  "edges": [
    { "cursor": "b3JkZXI6MTAwMDE=", "node": { "id": "10001", "name": "NN10001", "placedDate": "2026-05-14T09:32:00Z", "grandTotal": 129.00, "customerName": "Jordan Lee" } },
    { "cursor": "b3JkZXI6MTAwMDU=", "node": { "id": "10005", "name": "NN10005", "placedDate": "2026-05-14T10:05:00Z", "grandTotal": 54.00,  "customerName": "Priya Shah" } }
  ],
  "pageInfo": { "hasNextPage": true, "endCursor": "b3JkZXI6MTAwMDU=" } } },
  "extensions": { "cost": { "requestedQueryCost": 12, "actualQueryCost": 12,
    "throttleStatus": { "maximumAvailable": 1000, "currentlyAvailable": 988, "restoreRate": 50 } } } }
```
**maps:** OrderHeader, indexed filter+sort · **kind:** `[DB]` · **cost:** cheap ·
**test:** ≤2 edges; all `ORDER_APPROVED`; ascending placedDate; `endCursor` == last edge cursor; `hasNextPage==true`.

---

## B. Fulfillment & shipping

### B1 — Shipment with tracking
**Need:** show a shipment, its packages, and carrier tracking.
```graphql
{ shipment(id: "SH900") { statusId originFacility { name }
    packages(first: 20) { trackingCode labelImageUrl contents(first: 50) { productId quantity } }
    routeSegments(first: 5) { carrierPartyId trackingIdNumber trackingUrl } } }
```
**Output:**
```json
{ "data": { "shipment": {
  "statusId": "SHIPMENT_SHIPPED", "originFacility": { "name": "Dallas DC" },
  "packages": [ { "trackingCode": "9400111899560000000000", "labelImageUrl": "https://cdn.example/labels/SH900-1.png",
    "contents": [ { "productId": "NN-HOODIE-BLK-M", "quantity": 1 } ] } ],
  "routeSegments": [ { "carrierPartyId": "USPS", "trackingIdNumber": "9400111899560000000000", "trackingUrl": "https://tools.usps.com/go/TrackConfirmAction?tLabels=9400111899560000000000" } ] } } }
```
**maps:** Shipment → ShipmentPackage → ShipmentPackageContent; ShipmentRouteSegment (tracking) · **kind:** `[DB][VIEW]` · **cost:** moderate ·
**test:** packages nest contents; `routeSegments[].trackingIdNumber` present when SHIPPED.
*Note:* tracking lives on the route segment — there is no AfterShip tracking object in OMS.

### B2 — BOPIS pickup queue
**Need:** store associate's pickup list for their store.
```graphql
{ pickupOrders(facilityId: "STORE_07", first: 25) {
    edges { node { id name customerName
      shipGroups(first: 5) { picklistId picker { firstName lastName } items(first: 50) { productId quantity } } } }
    pageInfo { hasNextPage endCursor } } }
```
**Output:**
```json
{ "data": { "pickupOrders": {
  "edges": [ { "node": {
    "id": "10077", "name": "NN10077", "customerName": "Sam Rivera",
    "shipGroups": [ { "picklistId": "PICK55", "picker": { "firstName": "Dana", "lastName": "Cole" },
      "items": [ { "productId": "NN-TEE-WHT-L", "quantity": 1 } ] } ] } } ],
  "pageInfo": { "hasNextPage": false, "endCursor": "b3JkZXI6MTAwNzc=" } } } }
```
**maps:** `get#PickupOrders` / `OrderHeaderShipGroupShipment` + `PicklistShipmentAndRole` views · **kind:** `[VIEW][SERVICE]` · **cost:** moderate ·
**test:** only STORE_07 orders; picker present when assigned; single page (`hasNextPage==false`).

### B3 — Ready-to-pick warehouse queue (Gorjana) — declared client fields
**Need:** Gorjana warehouse queue filtered by brand tier + gift, custom to that deployment.
```graphql
{ readyToPickOrders(
    filter: { facilityId: { eq: "WH1" }, brandName: { eq: "Fine" }, isGift: { eq: true } },
    sort: [{ field: orderDate, dir: desc }], first: 2 ) {
    edges { node { orderId orderName netsuiteOrderName shipToState } } pageInfo { hasNextPage endCursor } } }
```
**Output:**
```json
{ "data": { "readyToPickOrders": {
  "edges": [
    { "node": { "orderId": "10088", "orderName": "NN10088", "netsuiteOrderName": "SO20088", "shipToState": "CA" } },
    { "node": { "orderId": "10081", "orderName": "NN10081", "netsuiteOrderName": "SO20081", "shipToState": "NY" } }
  ],
  "pageInfo": { "hasNextPage": false, "endCursor": "b3JkZXI6MTAwODE=" } } } }
```
**maps:** `ReadyToPickWarehouseOrder` view; custom `isGift`/`brandName`; `netsuiteOrderName` via OrderIdentification · **kind:** `[VIEW]` · **cost:** moderate ·
**test:** `brandName`/`isGift` filterable only in the Gorjana schema; undeclared in NotNaked → `FIELD_NOT_FILTERABLE`.

### B4 — Picklist with items
**Need:** the contents of one picklist for the floor.
```graphql
{ picklist(id: "PICK55") { statusId picklistDate items(first: 200) { orderName productId quantity itemStatus } } }
```
**Output:**
```json
{ "data": { "picklist": { "statusId": "PICKLIST_PICKING", "picklistDate": "2026-05-15T08:00:00Z",
  "items": [
    { "orderName": "NN10077", "productId": "NN-TEE-WHT-L", "quantity": 1, "itemStatus": "ITEM_APPROVED" },
    { "orderName": "NN10079", "productId": "NN-HAT-RED",   "quantity": 1, "itemStatus": "ITEM_APPROVED" } ] } } }
```
**maps:** `PicklistItemView` · **kind:** `[VIEW]` · **cost:** moderate · **test:** items belong to PICK55; itemStatus mirrors order item status.

---

## C. Returns

### C1 — RMA detail / returns worklist
**Need:** requested returns with their external ids and line items.
```graphql
{ returns(filter: { statusId: { eq: "RETURN_REQUESTED" } }, first: 1) {
    edges { node { returnId statusId identifications(first: 5) { type value }
      items(first: 100) { orderId productId returnQuantity refundAmount } } } pageInfo { hasNextPage endCursor } } }
```
**Output:**
```json
{ "data": { "returns": {
  "edges": [ { "node": {
    "returnId": "RT5001", "statusId": "RETURN_REQUESTED",
    "identifications": [ { "type": "AFTERSHIP_RTN_ID", "value": "AS-99812" } ],
    "items": [ { "orderId": "10001", "productId": "NN-SOCK-3PK", "returnQuantity": 1, "refundAmount": 20.00 } ] } } ],
  "pageInfo": { "hasNextPage": true, "endCursor": "cmV0dXJuOlJUNTAwMQ==" } } } }
```
**maps:** ReturnHeader → `items`, `identifications` · **kind:** `[DB]` · **cost:** moderate · **test:** only RETURN_REQUESTED; external ids exposed; refundAmount present.

---

## D. Transfers & purchasing

### D1 — Transfer orders awaiting approval
**Need:** inter-facility transfers pending approval with their lines.
```graphql
{ transferOrders(filter: { statusId: { eq: "ORDER_CREATED" }, facilityId: { eq: "WH1" } }, first: 1) {
    edges { node { orderId items(first: 100) { productId quantity } shipments(first: 10) { shipmentId statusId } } }
    pageInfo { hasNextPage endCursor } } }
```
**Output:**
```json
{ "data": { "transferOrders": { "edges": [ { "node": {
  "orderId": "TO3001",
  "items": [ { "productId": "NN-HOODIE-BLK-M", "quantity": 12 } ],
  "shipments": [ { "shipmentId": "TS7001", "statusId": "SHIPMENT_INPUT" } ] } } ],
  "pageInfo": { "hasNextPage": false, "endCursor": "dG86VE8zMDAx" } } } }
```
**maps:** `get#TransferOrders` + TransferOrder views · **kind:** `[VIEW][SERVICE]` · **cost:** moderate · **test:** status+facility filter; items & shipments nest.

### D2 — Purchase order receipts
**Need:** what's been received against a PO.
```graphql
{ purchaseOrder(id: "PO77") { orderId receipts(first: 100) { shipmentReceiptId productId quantityAccepted } } }
```
**Output:**
```json
{ "data": { "purchaseOrder": { "orderId": "PO77",
  "receipts": [ { "shipmentReceiptId": "SR9001", "productId": "NN-HOODIE-BLK-M", "quantityAccepted": 100 } ] } } }
```
**maps:** PurchaseOrder → ShipmentReceipt · **kind:** `[DB]` · **cost:** cheap · **test:** receipts belong to PO77; quantityAccepted numeric.

---

## E. Inventory & ATP (service-backed)

### E1/E2/E3 — availability checks + on-hand rollup
**Need:** can I promise this item here, online, and what's on hand.
```graphql
{ checkBopisInventory(facilityId: "STORE_07", productId: "NN-TEE-WHT-L", quantity: 2) { available atp }
  productOnlineAtp(productId: "NN-TEE-WHT-L", productStoreId: "ONLINE") { atp }
  facilityInventory(facilityId: "WH1", productId: "NN-TEE-WHT-L") { quantityOnHand atp safetyStock } }
```
**Output:**
```json
{ "data": {
  "checkBopisInventory": { "available": true, "atp": 7 },
  "productOnlineAtp": { "atp": 142 },
  "facilityInventory": { "quantityOnHand": 160, "atp": 142, "safetyStock": 18 } } }
```
**maps:** `get#BopisInventory`, `get#ProductOnlineAtp` (service); `ProductFacilityView` (view) · **kind:** `[SERVICE][VIEW]` · **cost:** high (service-backed → fixed high cost) ·
**test:** `available` boolean; `atp` numeric; service-backed fields carry high fixed cost in the estimate.

---

## F. Catalog (DB-backed structured filters — Q1: DB only)

### F1 — Product lookup by category / type / SKU
**Need:** find finished-goods in a category, or resolve a SKU. *Full-text keyword search stays on
the existing Solr endpoints — not GraphQL.*
```graphql
{ products(filter: { primaryCategoryId: { eq: "TOPS" }, productTypeId: { eq: "FINISHED_GOOD" } }, first: 2) {
    edges { node { productId productName identifications(first: 5) { type value } } } pageInfo { hasNextPage endCursor } } }
```
**Output:**
```json
{ "data": { "products": {
  "edges": [
    { "node": { "productId": "NN-HOODIE-BLK-M", "productName": "Black Hoodie / M",
        "identifications": [ { "type": "SKU", "value": "NN-HOODIE-BLK-M" }, { "type": "UPC", "value": "0810000000019" } ] } },
    { "node": { "productId": "NN-TEE-WHT-L", "productName": "White Tee / L",
        "identifications": [ { "type": "SKU", "value": "NN-TEE-WHT-L" } ] } }
  ],
  "pageInfo": { "hasNextPage": true, "endCursor": "cHJvZHVjdDpOTi1URUUtV0hULUw=" } } } }
```
**maps:** Product + GoodIdentification, declared+indexed filters · **kind:** `[DB]` · **cost:** cheap · **test:** category/type filters; SKU resolvable via identifications; undeclared filter → `FIELD_NOT_FILTERABLE`.

### F2 — Product detail
**Need:** one product with categories, ids, and variants.
```graphql
{ product(id: "NN-HOODIE") { productName categories(first: 10) { categoryId }
    identifications(first: 10) { type value } variants(first: 50) { productId } } }
```
**Output:**
```json
{ "data": { "product": { "productName": "Black Hoodie",
  "categories": [ { "categoryId": "TOPS" } ],
  "identifications": [ { "type": "BRAND", "value": "NotNaked" } ],
  "variants": [ { "productId": "NN-HOODIE-BLK-M" }, { "productId": "NN-HOODIE-BLK-L" } ] } } }
```
**maps:** Product + `OmsProduct` shape · **kind:** `[DB][VIEW]` · **cost:** moderate · **test:** identifications include SKU/UPC; variants non-empty for a virtual product.

---

## G. Facility & store config

### G1/G2
**Need:** a facility's locations/carriers/stores; a store's shipment methods.
```graphql
{ facility(id: "WH1") { name type address { city stateProvince }
    locations(first: 50) { locationSeqId } carriers(first: 20) { partyId } productStores(first: 10) { productStoreId } }
  productStore(id: "ONLINE") { shipmentMethods(first: 20) { shipmentMethodType carrierPartyId } } }
```
**Output:**
```json
{ "data": {
  "facility": { "name": "Dallas DC", "type": "WAREHOUSE", "address": { "city": "Dallas", "stateProvince": "TX" },
    "locations": [ { "locationSeqId": "A-01-01" } ], "carriers": [ { "partyId": "USPS" }, { "partyId": "FEDEX" } ],
    "productStores": [ { "productStoreId": "ONLINE" } ] },
  "productStore": { "shipmentMethods": [ { "shipmentMethodType": "STANDARD", "carrierPartyId": "USPS" } ] } } }
```
**maps:** Facility (+ FacilityCarrier view, locations); ProductStore + ProductStoreShipmentMethod · **kind:** `[DB][VIEW]` · **cost:** moderate · **test:** carriers via FacilityParty(role=CARRIER); methods resolve.

---

## H. Cycle count
**Need:** a count session and its line variances.
```graphql
{ cycleCount(id: "CC1") { statusId facilityId items(first: 500) { locationSeqId productId countedQuantity variance } } }
```
**Output:**
```json
{ "data": { "cycleCount": { "statusId": "INV_COUNT_COMPLETED", "facilityId": "WH1",
  "items": [ { "locationSeqId": "A-01-01", "productId": "NN-HOODIE-BLK-M", "countedQuantity": 98, "variance": -2 } ] } } }
```
**maps:** WorkEffort/InventoryCountImport → items · **kind:** `[DB]` · **cost:** moderate (large `first:` → watch row cap) · **test:** items ≤ row cap; variance computed.

---

## I. Order routing
**Need:** a routing group with its rules and recent runs.
```graphql
{ orderRoutingGroup(id: "RG1") { statusId
    routings(first: 20) { orderRoutingId rules(first: 50) { field operator value } }
    runs(first: 10) { runId runDate orderCount } } }
```
**Output:**
```json
{ "data": { "orderRoutingGroup": { "statusId": "ROUTING_ACTIVE",
  "routings": [ { "orderRoutingId": "OR1", "rules": [ { "field": "facilityId", "operator": "eq", "value": "WH1" } ] } ],
  "runs": [ { "runId": "RUN9001", "runDate": "2026-05-15T02:00:00Z", "orderCount": 312 } ] } } }
```
**maps:** OrderRoutingGroup → OrderRouting → OrderRoutingRule + runs · **kind:** `[DB][VIEW]` · **cost:** moderate · **test:** rules nest under routings; runs ordered by date.

---

## J. External-ID lookup (Q5 — must-have)

### J1 — Order by host external id
**Need:** a webhook/partner has the Shopify id, needs our order.
```graphql
{ order(externalId: "shopify:4567890") { id name statusId } }
```
**Output:** `{ "data": { "order": { "id": "10001", "name": "NN10001", "statusId": "ORDER_APPROVED" } } }`
**maps:** OrderHeader.externalId (indexed) · **kind:** `[DB]` · **cost:** cheap · **test:** resolves the right order; unknown id → `data.order == null` (not an error).

### J2 — Order by typed identification (NetSuite)
**Need:** ERP has its own order number; map back to OMS.
```graphql
{ orderByIdentification(type: "NETSUITE_ORDER", value: "SO12345") { id name identifications(first: 5) { type value } } }
```
**Output:**
```json
{ "data": { "orderByIdentification": { "id": "10001", "name": "NN10001",
  "identifications": [ { "type": "NETSUITE_ORDER", "value": "SO12345" }, { "type": "SHOPIFY_ORDER", "value": "4567890" } ] } } }
```
**maps:** OrderIdentification lookup + `identifications` edge · **kind:** `[DB]` · **cost:** cheap · **test:** same order reachable by NetSuite id and Shopify id; identifications edge lists all ids.

### J3 — Batch resolve many external ids (sync reconciliation)
**Need:** a partner sync holds 100 host ids; fetch them in one call.
```graphql
{ orders(filter: { externalId: { in: ["shopify:4567890","shopify:4567999"] } }, first: 100) {
    edges { node { id externalId statusId } } pageInfo { hasNextPage endCursor } } }
```
**Output:**
```json
{ "data": { "orders": { "edges": [
  { "node": { "id": "10001", "externalId": "shopify:4567890", "statusId": "ORDER_APPROVED" } },
  { "node": { "id": "10090", "externalId": "shopify:4567999", "statusId": "ORDER_COMPLETED" } } ],
  "pageInfo": { "hasNextPage": false, "endCursor": "b3JkZXI6MTAwOTA=" } } } }
```
**maps:** `externalId` with `in` operator (declared) · **kind:** `[DB]` · **cost:** cheap · **test:** both ids resolve; missing ids simply absent (no error); order independent of input order.

### J4 — Facility by external id
**Need:** map a WMS/host facility code to our facility.
```graphql
{ facility(externalId: "wms:DC-DALLAS") { id name type } }
```
**Output:** `{ "data": { "facility": { "id": "WH1", "name": "Dallas DC", "type": "WAREHOUSE" } } }`
**maps:** Facility.externalId · **kind:** `[DB]` · **cost:** cheap · **test:** external-id lookup works across core types, not just Order.

---

## K. Analytics / BI — DEFERRED (Q2: pick up later)
> **Not in the initial scope.** Recorded only to capture the shape; revisited after user-group
> usage examples. `oms-bi` has facts but no query API today, so this is a clean build-new later.
```graphql
# illustrative only — NOT in initial scope
{ fulfillmentMetrics(facilityId: "WH1", dateFrom: "2026-05-01", dateTo: "2026-05-31", groupBy: DAY) {
    date unitsShipped avgFulfillmentHours cancelRate } }
```
**Backed later by:** `OrderItemFulfillmentFact`, `ReturnItemFact`, `InventoryItemDetailFact`.

---

## L. AI-agent composites

### L1 — "which of this customer's orders are stuck in processing?"
**Need:** an agent composes a query from a goal and reasons over computed status.
```graphql
{ orders(filter: { customerId: { eq: "CUST_88" }, statusId: { eq: "ORDER_APPROVED" } }, first: 20) {
    edges { node { id placedDate items(first: 50) { productId promisedDate fulfillmentStatus } } } } }
```
**Output:**
```json
{ "data": { "orders": { "edges": [ { "node": {
  "id": "10001", "placedDate": "2026-05-14T09:32:00Z",
  "items": [ { "productId": "NN-HOODIE-BLK-M", "promisedDate": "2026-05-20T00:00:00Z", "fulfillmentStatus": "PROCESSING" } ] } } ] } } }
```
**test:** agent query validates and runs; computed `fulfillmentStatus` present so the agent can flag "PROCESSING past promisedDate."

### L2 — one-shot multi-root context for a support reply
**Need:** order + customer context in a single round-trip.
```graphql
{ order(id: "10042") { name statusId } customer(id: "CUST_88") { name email orderCount } }
```
**Output:**
```json
{ "data": { "order": { "name": "NN10042", "statusId": "ORDER_APPROVED" },
  "customer": { "name": "Jordan Lee", "email": "jordan@example.com", "orderCount": 7 } } }
```
**test:** two root fields resolve in one request; `orderCount` is service-backed/aggregate.

---

## M. Connection pagination (Q4) — full cursor walk

### M1 — page 1
**Need:** stable feed paging for a partner sync.
```graphql
{ orders(filter: { statusId: { eq: "ORDER_APPROVED" } }, sort: [{ field: placedDate, dir: asc }], first: 2) {
    edges { cursor node { id externalId } } pageInfo { hasNextPage endCursor } } }
```
**Output:**
```json
{ "data": { "orders": {
  "edges": [ { "cursor": "b3JkZXI6MTAwMDE=", "node": { "id": "10001", "externalId": "shopify:4567890" } },
             { "cursor": "b3JkZXI6MTAwMDU=", "node": { "id": "10005", "externalId": "shopify:4567901" } } ],
  "pageInfo": { "hasNextPage": true, "endCursor": "b3JkZXI6MTAwMDU=" } } } }
```

### M2 — page 2 (pass the previous `endCursor` as `after`)
```graphql
{ orders(filter: { statusId: { eq: "ORDER_APPROVED" } }, sort: [{ field: placedDate, dir: asc }],
    first: 2, after: "b3JkZXI6MTAwMDU=") {
    edges { cursor node { id externalId } } pageInfo { hasNextPage endCursor } } }
```
**Output:**
```json
{ "data": { "orders": {
  "edges": [ { "cursor": "b3JkZXI6MTAwMDk=", "node": { "id": "10009", "externalId": "shopify:4567912" } } ],
  "pageInfo": { "hasNextPage": false, "endCursor": "b3JkZXI6MTAwMDk=" } } } }
```
**maps:** OrderHeader; cursor encodes the stable sort key (placedDate, id) · **kind:** `[DB]` · **cost:** moderate ·
**test:** page 2 starts after page 1's last edge — no overlap, no skip; `hasNextPage==false` on the final page; same total set as an unpaged read.

### M3 — nested connection
**Need:** page the items inside an order (large orders).
```graphql
{ order(id: "10001") { name items(first: 1) { edges { cursor node { productId quantity } } pageInfo { hasNextPage endCursor } } } }
```
**Output:**
```json
{ "data": { "order": { "name": "NN10001", "items": {
  "edges": [ { "cursor": "aXRlbToxMDAwMTowMDAwMQ==", "node": { "productId": "NN-HOODIE-BLK-M", "quantity": 1 } } ],
  "pageInfo": { "hasNextPage": true, "endCursor": "aXRlbToxMDAwMTowMDAwMQ==" } } } } }
```
**test:** nested list fields are connections too; child cursor pages independently of the parent.

---

## N. Guardrail boundary — MUST be rejected (with the exact error)

### N1 — fan-out bomb
```graphql
{ orders(first: 1000) { edges { node { items(first: 1000) { edges { node { adjustments(first: 1000) { edges { node { id } } } } } } } } } }
```
```json
{ "errors": [ { "message": "query cost 1,000,000,000 exceeds max 1000",
  "extensions": { "code": "COST_EXCEEDED", "estimatedCost": 1000000000, "maxCost": 1000 } } ], "data": null }
```

### N2 — undeclared filter field
```graphql
{ orders(filter: { orderName: { eq: "Gift order" } }, first: 50) { edges { node { id } } } }
```
```json
{ "errors": [ { "message": "filter field 'orderName' is not declared filterable (allowed: orderId, externalId, statusId, placedDate, customerId, productStoreId)",
  "extensions": { "code": "FIELD_NOT_FILTERABLE", "field": "orderName" } } ], "data": null }
```

### N3 — missing required `first:` on a list
```graphql
{ orders(filter: { statusId: { eq: "ORDER_APPROVED" } }) { edges { node { id } } } }
```
```json
{ "errors": [ { "message": "list field 'orders' requires 'first:' (1..100)",
  "extensions": { "code": "FIRST_REQUIRED", "field": "orders", "maxFirst": 100 } } ], "data": null }
```

### N4 — depth limit
```json
{ "errors": [ { "message": "query depth 8 exceeds max 6", "extensions": { "code": "DEPTH_EXCEEDED", "depth": 8, "maxDepth": 6 } } ], "data": null }
```

**test (all N):** structured error, `data: null`, `extensions.code` stable, message names the
offending field/limit, and **nothing executes against the DB** (assert no query issued).

---

## Coverage matrix (domain × capability)

| Domain | by-id | list+filter | nested | computed | view | ext-id | connection |
|---|---|---|---|---|---|---|---|
| Order | A1 | A2,Q3a | A1 | A1,L1 | A1 | J1,J2,J3 | A2,M1-3 |
| Shipment | B1 | — | B1 | — | B1 | — | — |
| Picklist/Pick | B4 | B3 | B4 | — | B3,B4 | — | B3 |
| Returns | — | C1 | C1 | — | — | C1 | C1 |
| Transfer/PO | D2 | D1 | D1,D2 | — | D1 | — | D1 |
| Inventory/ATP | — | — | — | E1,E2 | E3 | — | — |
| Catalog | F2 | F1 | F2 | — | F2 | F1 | F1 |
| Facility/Store | G1 | — | G1 | — | G1 | J4 | — |
| CycleCount | H1 | — | H1 | — | — | — | — |
| Routing | I1 | — | I1 | — | I1 | — | — |

Operators/guardrails: Q3a/Q3b (operator control), N1–N4 (cost/field/first/depth).
**Out of initial scope (no column):** analytics/aggregation (Q2 deferred), full-text/faceted Solr search (Q1 DB-only).

---

## Decisions baked in (resolved 2026-06-03 — see requirements.md Part 4)
- **Q1 DB-backed only** — F1 is a structured DB filter; full-text search stays on Solr endpoints.
- **Q2 analytics deferred** — §K illustrative only.
- **Q3 declare-and-control** — only declared fields filter/sort, with allowed operators (Q3a/Q3b, N2).
- **Q4 Relay connections** — all list fields are connections (§M full cursor walk).
- **Q5 external-id must-have** — §J1–J4 first-class across types.

## Out of scope (not represented as queries)
Writes/mutations, analytics/aggregation (deferred), full-text/faceted Solr search, `unigate` RPC
(rate/label/refund/email), print/export (PDF/CSV), inbound webhooks (Shopify/ADP/AfterShip), live
external assembly (Shopify GraphQL pass-through).
