# moqui-gql — Capability Examples & Test-Case Catalog

**Status:** Requirements / design phase. Source of truth for what the API does, and the basis for
the test suite. Every example is a `Need → Query → Output` triple.

**Field names are OUR OMS data model** (`orderId`, `orderDate`, `statusId`, `grandTotal`,
`orderItems`, `fulfillmentStatus`, …) — consumers see Maarg's model, not Shopify's. From **Shopify
we adopt only the query language**: `query:` search-string filtering, `sortKey`+`reverse` sorting,
Relay cursor connections, and the cost/error envelope. See `shopify-alignment.md`.
**IDs are raw entity keys** (D-B — no `gid://`/`Node`). DB-backed only (Q1); analytics deferred (Q2).

> Names here are the *contract*. Wire entities differ per deployment (HotWax
> `org.apache.ofbiz.order.order.*` vs vanilla `mantle.*`); schema artifacts map them.

---

## How to read an example
- **Need** — the job to be done (and what it takes *today* without this engine, where useful).
- **Query** — exact GraphQL sent.
- **Output** — exact JSON returned (or the error, for rejected cases).
- **Footer** — `maps:` backing entity/view/service · `kind:` `[DB|VIEW|SERVICE]` · `cost:` class · `test:` assertions.

### Conventions (from Shopify's query language; field names are ours)

1. **Envelope.** Success → `{ "data": {…}, "extensions": {…} }`; error → `{ "errors": [...], "data": null }`. HTTP 200 either way.
2. **IDs are raw entity keys** (D-B): `orderId: "10001"`, composite like `"10001:00001"` where needed. No global IDs, no `node()`.
3. **Filtering = `query:` search string** (Shopify syntax, our field names as keys):
   `query: "statusId:ORDER_APPROVED orderDate:>=2026-05-01"`. Only **declared search keys** are
   accepted, each with a **declared comparator set** (matrix below). DB-backed (Q1), parsed
   server-side, governed by Q3.
4. **Sorting = `sortKey: <Type>SortKeys` enum + `reverse: Boolean`** (single key; enum values are
   our fields, e.g. `ORDER_DATE`; `RELEVANCE` when a `query` is present).
5. **Relay connections.** `field(first, after, last, before)` →
   `{ edges { cursor node { … } } pageInfo { hasNextPage hasPreviousPage startCursor endCursor } }`.
   Nested examples use shorthand `field(first:N){ … }` for the `edges{ node{ … } }` selection; §M
   shows the full form + cursor walk.
6. **Money is our model:** `grandTotal` (Decimal) + `currencyUomId`; line `unitPrice` (Decimal).
   (No `MoneyBag`/`...Set` — that's Shopify naming.)
7. **Dates:** `DateTime` scalar (ISO-8601). **Statuses:** our raw `statusId`; computed
   `fulfillmentStatus` (decision 12) where applicable.
8. **`extensions.cost`** (Shopify-shaped envelope) rides every successful response (shown in A1/A2; assume always present).
9. **Errors are agent-actionable** — `message` names the offending key/limit; `extensions.code` is stable (`COST_EXCEEDED`, `FIELD_NOT_FILTERABLE`, `OPERATOR_NOT_ALLOWED`, `FIRST_REQUIRED`, `DEPTH_EXCEEDED`, `THROTTLED`).

---

## Declared search keys & sort keys (Q3 — declare-and-control over the `query:` grammar)

Shopify-style string, but only **declared keys + comparators** (our field names) are honored.
Example for the **Order** root (each type declares its own):

| Search key | Comparators | Maps to | Index |
|---|---|---|---|
| `orderId` | `:` eq, `:a,b` in | OrderHeader.orderId (PK) | PK |
| `externalId` | `:` eq, `:a,b` in | OrderHeader.externalId | idx |
| `orderName` | `:` eq | OrderHeader.orderName | idx |
| `statusId` | `:` eq, `:a,b` in | OrderHeader.statusId | idx |
| `orderDate` | `:>` `:>=` `:<` `:<=` | OrderHeader.orderDate | idx |
| `customerPartyId` | `:` eq, `:a,b` in | OrderRole(BILL_TO).partyId | idx |
| `productStoreId` | `:` eq, `:a,b` in | OrderHeader.productStoreId | idx |

**`OrderSortKeys`** (enum, our fields): `ORDER_DATE`, `ORDER_NAME`, `GRAND_TOTAL`, `ORDER_ID`, `RELEVANCE`.
**Rules (Q3):** unknown key → `FIELD_NOT_FILTERABLE`; comparator not in the key's set (e.g.
`statusId:>X`) → `OPERATOR_NOT_ALLOWED`. The declared key list is published in the SDL so agents
can introspect what's allowed.

### Q3a — allowed keys + comparators
**Need:** "Approved/held ONLINE orders placed in May, newest first."
```graphql
query Orders {
  orders(query: "statusId:ORDER_APPROVED,ORDER_HELD productStoreId:ONLINE orderDate:>=2026-05-01 orderDate:<=2026-05-31",
         sortKey: ORDER_DATE, reverse: true, first: 2) {
    edges { node { orderId orderName statusId orderDate } }
    pageInfo { hasNextPage hasPreviousPage startCursor endCursor } } }
```
**Output:**
```json
{ "data": { "orders": {
  "edges": [
    { "node": { "orderId": "10042", "orderName": "NN10042", "statusId": "ORDER_APPROVED", "orderDate": "2026-05-28T16:10:00Z" } },
    { "node": { "orderId": "10039", "orderName": "NN10039", "statusId": "ORDER_HELD",     "orderDate": "2026-05-27T11:02:00Z" } }
  ],
  "pageInfo": { "hasNextPage": true, "hasPreviousPage": false, "startCursor": "b3JkZXI6MTAwNDI=", "endCursor": "b3JkZXI6MTAwMzk=" } } } }
```
**maps:** OrderHeader, declared+indexed keys · **kind:** `[DB]` · **cost:** cheap · **test:** `in` (comma) + date range honored; sorted desc by orderDate; full PageInfo present.

### Q3b — disallowed comparator is rejected
```graphql
{ orders(query: "statusId:>ORDER_APPROVED", first: 5) { edges { node { orderId } } } }
```
**Output:**
```json
{ "errors": [ { "message": "comparator '>' not allowed on search key 'statusId' (allowed: :, in)",
  "extensions": { "code": "OPERATOR_NOT_ALLOWED", "key": "statusId", "allowed": [":", "in"] } } ], "data": null }
```
**test:** rejected pre-execution; lists allowed comparators; nothing hits the DB.

---

## A. Order

### A1 — Order detail (CS agent / order screen)
**Need:** open one order with everything an agent needs. *Today:* `get#SalesOrder` + extra calls; *after:* one query.
```graphql
query OrderDetail($orderId: ID!) {
  order(orderId: $orderId) {
    orderName statusId orderDate grandTotal currencyUomId
    customerPartyId customerName
    billingAddress { address1 city stateProvinceGeoId postalCode countryGeoId }
    orderItems(first: 50) { orderItemSeqId productId quantity unitPrice statusId fulfillmentStatus promisedDate }
    shipGroups(first: 10) { shipGroupSeqId shipmentMethodTypeId carrierPartyId trackingCode facility { facilityName } }
    paymentPreferences(first: 10) { paymentMethodTypeId maxAmount statusId } } }
```
Variables: `{ "orderId": "10001" }`
**Output:**
```json
{ "data": { "order": {
  "orderName": "NN10001", "statusId": "ORDER_APPROVED", "orderDate": "2026-05-14T09:32:00Z",
  "grandTotal": "129.00", "currencyUomId": "USD",
  "customerPartyId": "CUST_88", "customerName": "Jordan Lee",
  "billingAddress": { "address1": "123 Main St", "city": "Austin", "stateProvinceGeoId": "USA_TX", "postalCode": "78701", "countryGeoId": "USA" },
  "orderItems": [
    { "orderItemSeqId": "00001", "productId": "NN-HOODIE-BLK-M", "quantity": 1, "unitPrice": "89.00", "statusId": "ITEM_APPROVED", "fulfillmentStatus": "PROCESSING", "promisedDate": "2026-05-20T00:00:00Z" },
    { "orderItemSeqId": "00002", "productId": "NN-SOCK-3PK", "quantity": 2, "unitPrice": "20.00", "statusId": "ITEM_COMPLETED", "fulfillmentStatus": "COMPLETED", "promisedDate": "2026-05-18T00:00:00Z" }
  ],
  "shipGroups": [ { "shipGroupSeqId": "00001", "shipmentMethodTypeId": "STANDARD", "carrierPartyId": "USPS", "trackingCode": "9400111899560000000000", "facility": { "facilityName": "Dallas DC" } } ],
  "paymentPreferences": [ { "paymentMethodTypeId": "CREDIT_CARD", "maxAmount": "129.00", "statusId": "PMNT_SETTLED" } ] } },
  "extensions": { "cost": { "requestedQueryCost": 64, "actualQueryCost": 61,
    "throttleStatus": { "maximumAvailable": 1000, "currentlyAvailable": 939, "restoreRate": 50 } } } }
```
**maps:** OrderHeader + `orderItems`/`shipGroups`/`paymentPreferences`; `customerName`+`fulfillmentStatus` service-backed; billingAddress via OrderContactMech→PostalAddress (purpose view) · **kind:** `[DB][VIEW][SERVICE]` · **cost:** moderate · **test:** order resolves; 2 orderItems; `customerName` non-null; `fulfillmentStatus` ∈ enum; `extensions.cost` present.

### A2 — Open-orders queue (ops), first page
```graphql
{ orders(query: "statusId:ORDER_APPROVED", sortKey: ORDER_DATE, first: 2) {
    edges { cursor node { orderId orderName orderDate grandTotal customerName } }
    pageInfo { hasNextPage hasPreviousPage startCursor endCursor } } }
```
**Output:**
```json
{ "data": { "orders": {
  "edges": [
    { "cursor": "b3JkZXI6MTAwMDE=", "node": { "orderId": "10001", "orderName": "NN10001", "orderDate": "2026-05-14T09:32:00Z", "grandTotal": "129.00", "customerName": "Jordan Lee" } },
    { "cursor": "b3JkZXI6MTAwMDU=", "node": { "orderId": "10005", "orderName": "NN10005", "orderDate": "2026-05-14T10:05:00Z", "grandTotal": "54.00", "customerName": "Priya Shah" } }
  ],
  "pageInfo": { "hasNextPage": true, "hasPreviousPage": false, "startCursor": "b3JkZXI6MTAwMDE=", "endCursor": "b3JkZXI6MTAwMDU=" } } },
  "extensions": { "cost": { "requestedQueryCost": 12, "actualQueryCost": 12, "throttleStatus": { "maximumAvailable": 1000, "currentlyAvailable": 988, "restoreRate": 50 } } } }
```
**maps:** OrderHeader · **kind:** `[DB]` · **cost:** cheap · **test:** ≤2 edges; all approved; ascending orderDate; `endCursor`==last cursor; `hasNextPage==true`, `hasPreviousPage==false`.

---

## B. Fulfillment & shipping

### B1 — Shipment with tracking
```graphql
{ shipment(shipmentId: "SH900") { statusId originFacilityId
    shipmentPackages(first: 20) { shipmentPackageSeqId trackingCode labelImageUrl
      shipmentPackageContents(first: 50) { productId quantity } }
    shipmentRouteSegments(first: 5) { carrierPartyId trackingIdNumber trackingUrl } } }
```
**Output:**
```json
{ "data": { "shipment": {
  "statusId": "SHIPMENT_SHIPPED", "originFacilityId": "WH1",
  "shipmentPackages": [ { "shipmentPackageSeqId": "00001", "trackingCode": "9400111899560000000000", "labelImageUrl": "https://cdn.example/labels/SH900-1.png",
    "shipmentPackageContents": [ { "productId": "NN-HOODIE-BLK-M", "quantity": 1 } ] } ],
  "shipmentRouteSegments": [ { "carrierPartyId": "USPS", "trackingIdNumber": "9400111899560000000000", "trackingUrl": "https://tools.usps.com/go/TrackConfirmAction?tLabels=9400111899560000000000" } ] } } }
```
**maps:** Shipment → ShipmentPackage → ShipmentPackageContent; ShipmentRouteSegment (tracking) · **kind:** `[DB][VIEW]` · **cost:** moderate · **test:** packages nest contents; tracking present when shipped. *(No AfterShip object — tracking is on the route segment.)*

### B2 — BOPIS pickup queue
```graphql
{ pickupOrders(query: "facilityId:STORE_07", first: 25) {
    edges { node { orderId orderName customerName
      shipGroups(first: 5) { picklistId picker { firstName lastName } orderItems(first: 50) { productId quantity } } } }
    pageInfo { hasNextPage endCursor } } }
```
**Output:**
```json
{ "data": { "pickupOrders": { "edges": [ { "node": {
  "orderId": "10077", "orderName": "NN10077", "customerName": "Sam Rivera",
  "shipGroups": [ { "picklistId": "PICK55", "picker": { "firstName": "Dana", "lastName": "Cole" },
    "orderItems": [ { "productId": "NN-TEE-WHT-L", "quantity": 1 } ] } ] } } ],
  "pageInfo": { "hasNextPage": false, "endCursor": "b3JkZXI6MTAwNzc=" } } } }
```
**maps:** `get#PickupOrders` / `OrderHeaderShipGroupShipment` + `PicklistShipmentAndRole` views · **kind:** `[VIEW][SERVICE]` · **cost:** moderate · **test:** only STORE_07; picker present when assigned.

### B3 — Ready-to-pick warehouse queue (Gorjana) — deployment-specific declared keys
```graphql
{ readyToPickOrders(query: "facilityId:WH1 brandName:Fine isGift:true", sortKey: ORDER_DATE, reverse: true, first: 2) {
    edges { node { orderId orderName netsuiteOrderName shipToStateProvinceGeoId } }
    pageInfo { hasNextPage endCursor } } }
```
**Output:**
```json
{ "data": { "readyToPickOrders": { "edges": [
  { "node": { "orderId": "10088", "orderName": "NN10088", "netsuiteOrderName": "SO20088", "shipToStateProvinceGeoId": "USA_CA" } },
  { "node": { "orderId": "10081", "orderName": "NN10081", "netsuiteOrderName": "SO20081", "shipToStateProvinceGeoId": "USA_NY" } } ],
  "pageInfo": { "hasNextPage": false, "endCursor": "b3JkZXI6MTAwODE=" } } } }
```
**maps:** `ReadyToPickWarehouseOrder` view; custom `brandName`/`isGift` keys; `netsuiteOrderName` via OrderIdentification · **kind:** `[VIEW]` · **cost:** moderate · **test:** `brandName`/`isGift` honored only in Gorjana schema; undeclared elsewhere → `FIELD_NOT_FILTERABLE`.

### B4 — Picklist with items
```graphql
{ picklist(picklistId: "PICK55") { statusId picklistDate
    picklistItems(first: 200) { orderName productId quantity itemStatusId } } }
```
**Output:**
```json
{ "data": { "picklist": { "statusId": "PICKLIST_PICKING", "picklistDate": "2026-05-15T08:00:00Z",
  "picklistItems": [ { "orderName": "NN10077", "productId": "NN-TEE-WHT-L", "quantity": 1, "itemStatusId": "ITEM_APPROVED" } ] } } }
```
**maps:** `PicklistItemView` · **kind:** `[VIEW]` · **cost:** moderate · **test:** items belong to PICK55.

---

## C. Returns
```graphql
{ returns(query: "statusId:RETURN_REQUESTED", first: 1) {
    edges { node { returnId statusId identifications(first: 5) { returnIdentificationTypeId idValue }
      returnItems(first: 100) { orderId productId returnQuantity refundAmount } } }
    pageInfo { hasNextPage endCursor } } }
```
**Output:**
```json
{ "data": { "returns": { "edges": [ { "node": {
  "returnId": "RT5001", "statusId": "RETURN_REQUESTED",
  "identifications": [ { "returnIdentificationTypeId": "AFTERSHIP_RTN_ID", "idValue": "AS-99812" } ],
  "returnItems": [ { "orderId": "10001", "productId": "NN-SOCK-3PK", "returnQuantity": 1, "refundAmount": "20.00" } ] } } ],
  "pageInfo": { "hasNextPage": true, "endCursor": "cmV0dXJuOlJUNTAwMQ==" } } } }
```
**maps:** ReturnHeader → returnItems, identifications · **kind:** `[DB]` · **cost:** moderate · **test:** only RETURN_REQUESTED; external ids exposed; refundAmount Decimal.

---

## D. Transfers & purchasing
```graphql
# D1 — transfers awaiting approval
{ transferOrders(query: "statusId:ORDER_CREATED facilityId:WH1", first: 1) {
    edges { node { orderId orderItems(first: 100) { productId quantity } shipments(first: 10) { shipmentId statusId } } }
    pageInfo { hasNextPage endCursor } } }
# D2 — PO receipts
{ purchaseOrder(orderId: "PO77") { orderId receipts(first: 100) { shipmentReceiptId productId quantityAccepted } } }
```
**Output (D1):**
```json
{ "data": { "transferOrders": { "edges": [ { "node": { "orderId": "TO3001",
  "orderItems": [ { "productId": "NN-HOODIE-BLK-M", "quantity": 12 } ],
  "shipments": [ { "shipmentId": "TS7001", "statusId": "SHIPMENT_INPUT" } ] } } ],
  "pageInfo": { "hasNextPage": false, "endCursor": "dG86VE8zMDAx" } } } }
```
**maps:** TransferOrder views; PurchaseOrder→ShipmentReceipt · **kind:** `[VIEW][DB]` · **cost:** moderate/cheap · **test:** status+facility query filters; receipts belong to PO77.

---

## E. Inventory & ATP (service-backed)
```graphql
{ checkBopisInventory(facilityId: "STORE_07", productId: "NN-TEE-WHT-L", quantity: 2) { available atp }
  productOnlineAtp(productId: "NN-TEE-WHT-L", productStoreId: "ONLINE") { atp }
  facilityInventory(facilityId: "WH1", productId: "NN-TEE-WHT-L") { quantityOnHandTotal availableToPromiseTotal minimumStock } }
```
**Output:**
```json
{ "data": { "checkBopisInventory": { "available": true, "atp": 7 },
  "productOnlineAtp": { "atp": 142 },
  "facilityInventory": { "quantityOnHandTotal": 160, "availableToPromiseTotal": 142, "minimumStock": 18 } } }
```
**maps:** `get#BopisInventory`, `get#ProductOnlineAtp` (service); `ProductFacilityView` (view) · **kind:** `[SERVICE][VIEW]` · **cost:** high (service-backed → fixed high cost) · **test:** `available` boolean; `atp` numeric; service fields carry high fixed cost.

---

## F. Catalog (DB-backed query — Q1: DB only; full-text stays on Solr)
```graphql
{ products(query: "primaryProductCategoryId:TOPS productTypeId:FINISHED_GOOD", sortKey: PRODUCT_NAME, first: 2) {
    edges { node { productId productName identifications(first: 5) { goodIdentificationTypeId idValue } } }
    pageInfo { hasNextPage endCursor } } }
```
**Output:**
```json
{ "data": { "products": { "edges": [
  { "node": { "productId": "NN-HOODIE-BLK-M", "productName": "Black Hoodie / M",
      "identifications": [ { "goodIdentificationTypeId": "SKU", "idValue": "NN-HOODIE-BLK-M" }, { "goodIdentificationTypeId": "UPCA", "idValue": "0810000000019" } ] } },
  { "node": { "productId": "NN-TEE-WHT-L", "productName": "White Tee / L",
      "identifications": [ { "goodIdentificationTypeId": "SKU", "idValue": "NN-TEE-WHT-L" } ] } } ],
  "pageInfo": { "hasNextPage": true, "endCursor": "cHJvZHVjdDpOTi1URUUtV0hULUw=" } } } }
```
**maps:** Product + GoodIdentification, declared keys · **kind:** `[DB]` · **cost:** cheap · **test:** category/type keys filter; SKU resolvable via identifications. *Keyword/faceted full-text search is NOT exposed via GraphQL — it stays on existing Solr endpoints.*

---

## G. Facility & store config
```graphql
{ facility(facilityId: "WH1") { facilityName facilityTypeId
    contactMechs(first: 5) { contactMechPurposeTypeId postalAddress { city stateProvinceGeoId } }
    locations(first: 50) { locationSeqId } carriers(first: 20) { partyId } productStores(first: 10) { productStoreId } }
  productStore(productStoreId: "ONLINE") { shipmentMethods(first: 20) { shipmentMethodTypeId carrierPartyId } } }
```
**Output:**
```json
{ "data": {
  "facility": { "facilityName": "Dallas DC", "facilityTypeId": "WAREHOUSE",
    "contactMechs": [ { "contactMechPurposeTypeId": "PRIMARY_LOCATION", "postalAddress": { "city": "Dallas", "stateProvinceGeoId": "USA_TX" } } ],
    "locations": [ { "locationSeqId": "A-01-01" } ], "carriers": [ { "partyId": "USPS" } ], "productStores": [ { "productStoreId": "ONLINE" } ] },
  "productStore": { "shipmentMethods": [ { "shipmentMethodTypeId": "STANDARD", "carrierPartyId": "USPS" } ] } } }
```
**maps:** Facility (+ FacilityContactDetailByPurpose, FacilityCarrier views); ProductStore + ProductStoreShipmentMethod · **kind:** `[DB][VIEW]` · **cost:** moderate · **test:** contactMechs via purpose view; carriers via FacilityParty(role=CARRIER).

---

## H. Cycle count
```graphql
{ cycleCount(inventoryCountImportId: "CC1") { statusId facilityId
    items(first: 500) { locationSeqId productId quantity varianceQuantityOnHand } } }
```
**Output:** `{ "data": { "cycleCount": { "statusId": "INV_COUNT_COMPLETED", "facilityId": "WH1", "items": [ { "locationSeqId": "A-01-01", "productId": "NN-HOODIE-BLK-M", "quantity": 98, "varianceQuantityOnHand": -2 } ] } } }`
**maps:** InventoryCountImport → InventoryCountImportItem · **kind:** `[DB]` · **cost:** moderate (watch row cap) · **test:** items ≤ row cap; variance computed.

---

## I. Order routing
```graphql
{ orderRoutingGroup(routingGroupId: "RG1") { statusId
    routings(first: 20) { orderRoutingId rules(first: 50) { fieldName operatorEnumId fieldValue } }
    runs(first: 10) { routingRunId runStartTime orderCount } } }
```
**Output:** `{ "data": { "orderRoutingGroup": { "statusId": "ROUTING_ACTIVE", "routings": [ { "orderRoutingId": "OR1", "rules": [ { "fieldName": "facilityId", "operatorEnumId": "EQUALS", "fieldValue": "WH1" } ] } ], "runs": [ { "routingRunId": "RUN9001", "runStartTime": "2026-05-15T02:00:00Z", "orderCount": 312 } ] } } }`
**maps:** OrderRoutingGroup → OrderRouting → OrderRoutingRule + runs · **kind:** `[DB][VIEW]` · **cost:** moderate · **test:** rules nest; runs ordered by time.

---

## J. External-ID lookup (Q5 — must-have)
```graphql
# J1 — by host external id (direct arg)
{ order(externalId: "shopify:4567890") { orderId orderName statusId } }
# J2 — by typed identification
{ orderByIdentification(identificationTypeId: "NETSUITE_ORDER", idValue: "SO12345") {
    orderId orderName identifications(first: 5) { orderIdentificationTypeId idValue } } }
# J3 — batch resolve many host ids via query string (sync reconciliation)
{ orders(query: "externalId:shopify:4567890,shopify:4567999", first: 100) {
    edges { node { orderId externalId statusId } } pageInfo { hasNextPage endCursor } } }
# J4 — facility by external id
{ facility(externalId: "wms:DC-DALLAS") { facilityId facilityName facilityTypeId } }
```
**Output (J2):**
```json
{ "data": { "orderByIdentification": { "orderId": "10001", "orderName": "NN10001",
  "identifications": [ { "orderIdentificationTypeId": "NETSUITE_ORDER", "idValue": "SO12345" }, { "orderIdentificationTypeId": "SHOPIFY_ORDER", "idValue": "4567890" } ] } } }
```
**maps:** OrderIdentification + `identifications` edge; `externalId` direct lookups · **kind:** `[DB]` · **cost:** cheap · **test:** same order via NetSuite id and Shopify id; unknown id → `null` node (not an error); batch returns all matches in any order.

---

## K. Analytics / BI — DEFERRED (Q2: pick up later)
> Not in initial scope. `oms-bi` has facts but no query API today; clean build-new later.
```graphql
# illustrative only — NOT in initial scope
{ fulfillmentMetrics(facilityId: "WH1", dateFrom: "2026-05-01", dateTo: "2026-05-31", groupBy: DAY) {
    date unitsShipped avgFulfillmentHours cancelRate } }
```

---

## L. AI-agent composites
```graphql
# L1 — "which of this customer's orders are stuck in processing?"
{ orders(query: "customerPartyId:CUST_88 statusId:ORDER_APPROVED", first: 20) {
    edges { node { orderId orderDate orderItems(first: 50) { productId promisedDate fulfillmentStatus } } } } }
# L2 — one-shot multi-root context
{ order(orderId: "10042") { orderName statusId } party(partyId: "CUST_88") { partyId firstName lastName orderCount } }
```
**Output (L2):**
```json
{ "data": { "order": { "orderName": "NN10042", "statusId": "ORDER_APPROVED" },
  "party": { "partyId": "CUST_88", "firstName": "Jordan", "lastName": "Lee", "orderCount": 7 } } }
```
**test:** agent query validates and runs; `fulfillmentStatus` present so the agent flags items past `promisedDate`; `orderCount` service-backed.
**Note (query-string tradeoff):** agents must emit the `query:` string correctly — the declared search-key list is published in the SDL so an agent can introspect allowed keys/comparators before composing.

---

## M. Connection pagination (Q4) — full cursor walk
```graphql
# M1 — page 1
{ orders(query: "statusId:ORDER_APPROVED", sortKey: ORDER_DATE, first: 2) {
    edges { cursor node { orderId externalId } } pageInfo { hasNextPage hasPreviousPage startCursor endCursor } } }
# M2 — page 2 (pass previous endCursor as after)
{ orders(query: "statusId:ORDER_APPROVED", sortKey: ORDER_DATE, first: 2, after: "b3JkZXI6MTAwMDU=") {
    edges { cursor node { orderId externalId } } pageInfo { hasNextPage hasPreviousPage startCursor endCursor } } }
```
**Output (M1):**
```json
{ "data": { "orders": {
  "edges": [ { "cursor": "b3JkZXI6MTAwMDE=", "node": { "orderId": "10001", "externalId": "shopify:4567890" } },
             { "cursor": "b3JkZXI6MTAwMDU=", "node": { "orderId": "10005", "externalId": "shopify:4567901" } } ],
  "pageInfo": { "hasNextPage": true, "hasPreviousPage": false, "startCursor": "b3JkZXI6MTAwMDE=", "endCursor": "b3JkZXI6MTAwMDU=" } } } }
```
**Output (M2):**
```json
{ "data": { "orders": {
  "edges": [ { "cursor": "b3JkZXI6MTAwMDk=", "node": { "orderId": "10009", "externalId": "shopify:4567912" } } ],
  "pageInfo": { "hasNextPage": false, "hasPreviousPage": true, "startCursor": "b3JkZXI6MTAwMDk=", "endCursor": "b3JkZXI6MTAwMDk=" } } } }
```
**maps:** OrderHeader; cursor encodes the stable sort key (`orderDate`, then `orderId` tiebreaker) · **kind:** `[DB]` · **cost:** moderate ·
**test:** page 2 starts strictly after page 1's last edge — **no overlap, no skip**; `union(pages) == same query unpaged`; `hasNextPage==false` on the final page; inserting a row ahead of the cursor mid-walk does NOT cause a re-seen id. Backward paging (`last`/`before`) mirrors this with `hasPreviousPage`.

---

## N. Guardrail boundary — MUST be rejected (exact error)
```graphql
{ orders(first: 1000) { edges { node { orderItems(first: 1000) { edges { node { adjustments(first: 1000) { edges { node { orderAdjustmentId } } } } } } } } } }   # N1
{ orders(query: "orderName2:Gift", first: 50) { edges { node { orderId } } } }                                                                                     # N2 (undeclared key)
{ orders(query: "statusId:ORDER_APPROVED") { edges { node { orderId } } } }                                                                                          # N3 (missing first)
```
```json
// N1
{ "errors": [ { "message": "query cost 1,000,000,000 exceeds max 1000", "extensions": { "code": "COST_EXCEEDED", "estimatedCost": 1000000000, "maxCost": 1000 } } ], "data": null }
// N2
{ "errors": [ { "message": "search key 'orderName2' is not filterable (allowed: orderId, externalId, orderName, statusId, orderDate, customerPartyId, productStoreId)", "extensions": { "code": "FIELD_NOT_FILTERABLE", "key": "orderName2" } } ], "data": null }
// N3
{ "errors": [ { "message": "list field 'orders' requires 'first:' or 'last:' (1..100)", "extensions": { "code": "FIRST_REQUIRED", "field": "orders", "maxFirst": 100 } } ], "data": null }
// N4 (depth)
{ "errors": [ { "message": "query depth 8 exceeds max 6", "extensions": { "code": "DEPTH_EXCEEDED", "depth": 8, "maxDepth": 6 } } ], "data": null }
```
**test (all N):** structured error, `data:null`, stable `extensions.code`, message names the offending key/limit, nothing executes against the DB. (Q3b covers `OPERATOR_NOT_ALLOWED`.)

---

## Coverage matrix (domain × capability)

| Domain | by-id | query+sort | nested | computed | view | ext-id | connection |
|---|---|---|---|---|---|---|---|
| Order | A1 | A2,Q3a | A1 | A1,L1 | A1 | J1,J2,J3 | A2,M1-2 |
| Shipment | B1 | — | B1 | — | B1 | — | — |
| Picklist/Pick | B4 | B3 | B4 | — | B3,B4 | — | B3 |
| Returns | — | C1 | C1 | — | — | C1 | C1 |
| Transfer/PO | D2 | D1 | D1,D2 | — | D1 | — | D1 |
| Inventory/ATP | — | — | — | E1,E2 | E3 | — | — |
| Catalog | F1 | F1 | F1 | — | F1 | F1 | F1 |
| Facility/Store | G1 | — | G1 | — | G1 | J4 | — |
| CycleCount | H1 | — | H1 | — | — | — | — |
| Routing | I1 | — | I1 | — | I1 | — | — |

Out of initial scope (no column): analytics/aggregation (Q2 deferred), full-text/faceted Solr search (Q1 DB-only).

---

## Decisions baked in (resolved 2026-06-03)
- **Shopify query language only (shopify-alignment.md):** `query:` search-string filtering, `sortKey`+`reverse`, full Relay connections + cursors, `extensions.cost`/error envelope. **Field names are our OMS data model** (not Shopify's). **Raw entity ids** (D-B — no `gid://`/`Node`).
- **Q1 DB-backed only** · **Q2 analytics deferred** · **Q3 declare-and-control** (over the query grammar) · **Q4 Relay connections** · **Q5 external-id must-have**.

## Out of scope (not represented as queries)
Writes/mutations, analytics/aggregation (deferred), full-text/faceted Solr search, `unigate` RPC,
print/export (PDF/CSV), inbound webhooks, live external assembly, global IDs / `node()` (D-B).
