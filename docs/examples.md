# moqui-gql — Capability Examples & Test-Case Catalog

**Status:** Requirements / design phase. Source of truth for what the API does, and the basis for
the test suite. Every example is a `Need → Query → Output` triple.
**Shaped to Shopify Admin GraphQL** (see `shopify-alignment.md`): `query:` search-string filtering,
`sortKey`+`reverse` sorting, full Relay connections, `MoneyBag`/`MoneyV2` money, `DateTime`/`Decimal`
scalars. **IDs are raw entity keys** (decision D-B — no `gid://`/`Node`). DB-backed only (Q1);
analytics deferred (Q2).

> Type/field names are the *contract*. Wire entities differ per deployment (`mantle.*` vs HotWax
> `org.apache.ofbiz.order.order.*`); schema artifacts map them.

---

## How to read an example
- **Need** — the job to be done (and what it takes *today* without this engine, where useful).
- **Query** — exact GraphQL sent.
- **Output** — exact JSON returned (or the error, for rejected cases).
- **Footer** — `maps:` backing entity/view/service · `kind:` `[DB|VIEW|SERVICE]` · `cost:` class · `test:` assertions.

### Shopify-shaped conventions (apply to every example)

1. **Envelope.** Success → `{ "data": {…}, "extensions": {…} }`; error → `{ "errors": [...], "data": null }`. HTTP 200 either way.
2. **IDs are raw entity keys** (D-B): `id: "10001"` (single) or composite like `"10001:00001"`. No global IDs, no `node()`.
3. **Filtering = `query:` search string** (D-A, Shopify syntax): `query: "status:ORDER_APPROVED created_at:>=2026-05-01"`.
   Only **declared search keys** are accepted, each with a **declared comparator set** (see the
   matrix below). Backed by the DB (Q1), parsed server-side, governed by Q3.
4. **Sorting = `sortKey: <Type>SortKeys` enum + `reverse: Boolean`** (single key; `RELEVANCE` when a `query` is present).
5. **Relay connections.** `field(first, after, last, before)` →
   `{ edges { cursor node { … } } pageInfo { hasNextPage hasPreviousPage startCursor endCursor } }`.
   Nested examples use the shorthand `field(first:N){ … }` for the `edges{ node{ … } }` selection;
   §M shows the full form + cursor walk.
6. **Money = `MoneyBag`** via `...Set` fields: `totalPriceSet { shopMoney { amount currencyCode } presentmentMoney { amount currencyCode } }`. `amount` is `Decimal`.
7. **Dates = `DateTime`** (ISO-8601). **Statuses** that map to Shopify are curated enums (`displayFulfillmentStatus`); OMS lifecycle status is exposed raw as `status`.
8. **`extensions.cost`** (Shopify-shaped) rides every successful response (shown in A1/A2; assume always present below).
9. **Errors are agent-actionable** — `message` names the offending key/limit; `extensions.code` is stable (`COST_EXCEEDED`, `FIELD_NOT_FILTERABLE`, `OPERATOR_NOT_ALLOWED`, `FIRST_REQUIRED`, `DEPTH_EXCEEDED`, `THROTTLED`).

---

## Declared search keys & sort keys (Q3 — declare-and-control over the `query:` grammar)

Filtering is a Shopify-style string, but only **declared keys + comparators** are honored. Example
for the **Order** root (each type declares its own):

| Search key | Comparators | Maps to | Index |
|---|---|---|---|
| `id` | `:` eq, `:a,b` in | OrderHeader.orderId (PK) | PK |
| `external_id` | `:` eq, `:a,b` in | OrderHeader.externalId | idx |
| `name` | `:` eq | OrderHeader.orderName | idx |
| `status` | `:` eq, `:a,b` in | OrderHeader.statusId | idx |
| `created_at` | `:>` `:>=` `:<` `:<=` (range via two terms) | placedDate | idx |
| `customer_id` | `:` eq, `:a,b` in | OrderRole(BILL_TO).partyId | idx |
| `product_store_id` | `:` eq, `:a,b` in | productStoreId | idx |

**`OrderSortKeys`** (enum): `CREATED_AT`, `UPDATED_AT`, `TOTAL_PRICE`, `NAME`, `ID`, `RELEVANCE`.
**Rules (Q3):** unknown search key → `FIELD_NOT_FILTERABLE`; comparator not in the key's set (e.g.
`status:>X`) → `OPERATOR_NOT_ALLOWED`. The declared key list is published in the SDL so agents can
introspect what's allowed.

### Q3a — allowed keys + comparators
**Need:** "Approved/held ONLINE orders created in May, newest first."
```graphql
query Orders {
  orders(query: "status:ORDER_APPROVED,ORDER_HELD product_store_id:ONLINE created_at:>=2026-05-01 created_at:<=2026-05-31",
         sortKey: CREATED_AT, reverse: true, first: 2) {
    edges { node { id name status createdAt } }
    pageInfo { hasNextPage hasPreviousPage startCursor endCursor } } }
```
**Output:**
```json
{ "data": { "orders": {
  "edges": [
    { "node": { "id": "10042", "name": "NN10042", "status": "ORDER_APPROVED", "createdAt": "2026-05-28T16:10:00Z" } },
    { "node": { "id": "10039", "name": "NN10039", "status": "ORDER_HELD",     "createdAt": "2026-05-27T11:02:00Z" } }
  ],
  "pageInfo": { "hasNextPage": true, "hasPreviousPage": false, "startCursor": "b3JkZXI6MTAwNDI=", "endCursor": "b3JkZXI6MTAwMzk=" } } } }
```
**maps:** OrderHeader, declared+indexed search keys · **kind:** `[DB]` · **cost:** cheap · **test:** `in` (comma) + date range honored; sorted desc by createdAt; full PageInfo present.

### Q3b — disallowed comparator is rejected
**Need:** guardrail — range comparator on an eq-only key.
```graphql
{ orders(query: "status:>ORDER_APPROVED", first: 5) { edges { node { id } } } }
```
**Output:**
```json
{ "errors": [ { "message": "comparator '>' not allowed on search key 'status' (allowed: :, in)",
  "extensions": { "code": "OPERATOR_NOT_ALLOWED", "key": "status", "allowed": [":", "in"] } } ], "data": null }
```
**test:** rejected pre-execution; lists allowed comparators; nothing hits the DB.

---

## A. Order

### A1 — Order detail (CS agent / order screen)
**Need:** open one order with everything an agent needs. *Today:* `get#SalesOrder` + extra calls; *after:* one query.
```graphql
query OrderDetail($id: ID!) {
  order(id: $id) {
    name status createdAt
    totalPriceSet { shopMoney { amount currencyCode } }
    customer { displayName email }
    billingAddress { address1 city provinceCode zip countryCode }
    lineItems(first: 50) { quantity productId displayFulfillmentStatus
      unitPriceSet { shopMoney { amount currencyCode } } promisedDate }
    shipGroups(first: 10) { shipmentMethod carrierPartyId trackingCode facility { name } }
    payments(first: 10) { methodType statusId amountSet { shopMoney { amount currencyCode } } } } }
```
Variables: `{ "id": "10001" }`
**Output:**
```json
{ "data": { "order": {
  "name": "NN10001", "status": "ORDER_APPROVED", "createdAt": "2026-05-14T09:32:00Z",
  "totalPriceSet": { "shopMoney": { "amount": "129.00", "currencyCode": "USD" } },
  "customer": { "displayName": "Jordan Lee", "email": "jordan@example.com" },
  "billingAddress": { "address1": "123 Main St", "city": "Austin", "provinceCode": "TX", "zip": "78701", "countryCode": "US" },
  "lineItems": [
    { "quantity": 1, "productId": "NN-HOODIE-BLK-M", "displayFulfillmentStatus": "IN_PROGRESS", "unitPriceSet": { "shopMoney": { "amount": "89.00", "currencyCode": "USD" } }, "promisedDate": "2026-05-20T00:00:00Z" },
    { "quantity": 2, "productId": "NN-SOCK-3PK", "displayFulfillmentStatus": "FULFILLED", "unitPriceSet": { "shopMoney": { "amount": "20.00", "currencyCode": "USD" } }, "promisedDate": "2026-05-18T00:00:00Z" }
  ],
  "shipGroups": [ { "shipmentMethod": "STANDARD", "carrierPartyId": "USPS", "trackingCode": "9400111899560000000000", "facility": { "name": "Dallas DC" } } ],
  "payments": [ { "methodType": "CREDIT_CARD", "statusId": "PMNT_SETTLED", "amountSet": { "shopMoney": { "amount": "129.00", "currencyCode": "USD" } } } ] } },
  "extensions": { "cost": { "requestedQueryCost": 64, "actualQueryCost": 61,
    "throttleStatus": { "maximumAvailable": 1000, "currentlyAvailable": 939, "restoreRate": 50 } } } }
```
**maps:** OrderHeader + `lineItems`/`shipGroups`/`paymentPreferences`; `customer.displayName`+`displayFulfillmentStatus` service-backed; billingAddress via OrderContactMech→PostalAddress · **kind:** `[DB][VIEW][SERVICE]` · **cost:** moderate · **test:** order resolves; 2 lineItems; money is MoneyBag with string `amount`; `displayFulfillmentStatus` ∈ enum; `extensions.cost` present.

### A2 — Open-orders queue (ops), first page
**Need:** paged worklist, oldest first.
```graphql
{ orders(query: "status:ORDER_APPROVED", sortKey: CREATED_AT, first: 2) {
    edges { cursor node { id name createdAt totalPriceSet { shopMoney { amount } } customer { displayName } } }
    pageInfo { hasNextPage hasPreviousPage startCursor endCursor } } }
```
**Output:**
```json
{ "data": { "orders": {
  "edges": [
    { "cursor": "b3JkZXI6MTAwMDE=", "node": { "id": "10001", "name": "NN10001", "createdAt": "2026-05-14T09:32:00Z", "totalPriceSet": { "shopMoney": { "amount": "129.00" } }, "customer": { "displayName": "Jordan Lee" } } },
    { "cursor": "b3JkZXI6MTAwMDU=", "node": { "id": "10005", "name": "NN10005", "createdAt": "2026-05-14T10:05:00Z", "totalPriceSet": { "shopMoney": { "amount": "54.00" } }, "customer": { "displayName": "Priya Shah" } } }
  ],
  "pageInfo": { "hasNextPage": true, "hasPreviousPage": false, "startCursor": "b3JkZXI6MTAwMDE=", "endCursor": "b3JkZXI6MTAwMDU=" } } },
  "extensions": { "cost": { "requestedQueryCost": 12, "actualQueryCost": 12, "throttleStatus": { "maximumAvailable": 1000, "currentlyAvailable": 988, "restoreRate": 50 } } } }
```
**maps:** OrderHeader · **kind:** `[DB]` · **cost:** cheap · **test:** ≤2 edges; all approved; ascending createdAt; `endCursor`==last cursor; `hasNextPage==true`, `hasPreviousPage==false`.

---

## B. Fulfillment & shipping

### B1 — Shipment with tracking
**Need:** a shipment, its packages, and carrier tracking.
```graphql
{ shipment(id: "SH900") { status originFacility { name }
    packages(first: 20) { trackingCode labelImageUrl contents(first: 50) { productId quantity } }
    routeSegments(first: 5) { carrierPartyId trackingIdNumber trackingUrl } } }
```
**Output:**
```json
{ "data": { "shipment": {
  "status": "SHIPMENT_SHIPPED", "originFacility": { "name": "Dallas DC" },
  "packages": [ { "trackingCode": "9400111899560000000000", "labelImageUrl": "https://cdn.example/labels/SH900-1.png",
    "contents": [ { "productId": "NN-HOODIE-BLK-M", "quantity": 1 } ] } ],
  "routeSegments": [ { "carrierPartyId": "USPS", "trackingIdNumber": "9400111899560000000000", "trackingUrl": "https://tools.usps.com/go/TrackConfirmAction?tLabels=9400111899560000000000" } ] } } }
```
**maps:** Shipment → ShipmentPackage → content; ShipmentRouteSegment (tracking) · **kind:** `[DB][VIEW]` · **cost:** moderate · **test:** packages nest contents; tracking present when SHIPPED. *(No AfterShip object — tracking is on the route segment.)*

### B2 — BOPIS pickup queue
**Need:** store associate's pickup list.
```graphql
{ pickupOrders(query: "facility_id:STORE_07", first: 25) {
    edges { node { id name customer { displayName }
      shipGroups(first: 5) { picklistId picker { firstName lastName } lineItems(first: 50) { productId quantity } } } }
    pageInfo { hasNextPage endCursor } } }
```
**Output:**
```json
{ "data": { "pickupOrders": { "edges": [ { "node": {
  "id": "10077", "name": "NN10077", "customer": { "displayName": "Sam Rivera" },
  "shipGroups": [ { "picklistId": "PICK55", "picker": { "firstName": "Dana", "lastName": "Cole" },
    "lineItems": [ { "productId": "NN-TEE-WHT-L", "quantity": 1 } ] } ] } } ],
  "pageInfo": { "hasNextPage": false, "endCursor": "b3JkZXI6MTAwNzc=" } } } }
```
**maps:** `get#PickupOrders` / `OrderHeaderShipGroupShipment` + `PicklistShipmentAndRole` views · **kind:** `[VIEW][SERVICE]` · **cost:** moderate · **test:** only STORE_07; picker present when assigned.

### B3 — Ready-to-pick warehouse queue (Gorjana) — declared client search keys
**Need:** Gorjana warehouse queue by brand tier + gift (deployment-specific keys).
```graphql
{ readyToPickOrders(query: "facility_id:WH1 brand_name:Fine is_gift:true", sortKey: ORDER_DATE, reverse: true, first: 2) {
    edges { node { orderId orderName netsuiteOrderName shipToProvinceCode } }
    pageInfo { hasNextPage endCursor } } }
```
**Output:**
```json
{ "data": { "readyToPickOrders": { "edges": [
  { "node": { "orderId": "10088", "orderName": "NN10088", "netsuiteOrderName": "SO20088", "shipToProvinceCode": "CA" } },
  { "node": { "orderId": "10081", "orderName": "NN10081", "netsuiteOrderName": "SO20081", "shipToProvinceCode": "NY" } } ],
  "pageInfo": { "hasNextPage": false, "endCursor": "b3JkZXI6MTAwODE=" } } } }
```
**maps:** `ReadyToPickWarehouseOrder` view; custom `brand_name`/`is_gift` keys; `netsuiteOrderName` via OrderIdentification · **kind:** `[VIEW]` · **cost:** moderate · **test:** `brand_name`/`is_gift` honored only in Gorjana schema; undeclared elsewhere → `FIELD_NOT_FILTERABLE`.

### B4 — Picklist with items
```graphql
{ picklist(id: "PICK55") { status picklistDate lineItems(first: 200) { orderName productId quantity itemStatus } } }
```
**Output:**
```json
{ "data": { "picklist": { "status": "PICKLIST_PICKING", "picklistDate": "2026-05-15T08:00:00Z",
  "lineItems": [ { "orderName": "NN10077", "productId": "NN-TEE-WHT-L", "quantity": 1, "itemStatus": "ITEM_APPROVED" } ] } } }
```
**maps:** `PicklistItemView` · **kind:** `[VIEW]` · **cost:** moderate · **test:** items belong to PICK55.

---

## C. Returns
**Need:** requested returns with external ids and lines.
```graphql
{ returns(query: "status:RETURN_REQUESTED", first: 1) {
    edges { node { returnId status identifications(first: 5) { type value }
      lineItems(first: 100) { orderId productId returnQuantity refundAmountSet { shopMoney { amount currencyCode } } } } }
    pageInfo { hasNextPage endCursor } } }
```
**Output:**
```json
{ "data": { "returns": { "edges": [ { "node": {
  "returnId": "RT5001", "status": "RETURN_REQUESTED",
  "identifications": [ { "type": "AFTERSHIP_RTN_ID", "value": "AS-99812" } ],
  "lineItems": [ { "orderId": "10001", "productId": "NN-SOCK-3PK", "returnQuantity": 1, "refundAmountSet": { "shopMoney": { "amount": "20.00", "currencyCode": "USD" } } } ] } } ],
  "pageInfo": { "hasNextPage": true, "endCursor": "cmV0dXJuOlJUNTAwMQ==" } } } }
```
**maps:** ReturnHeader → items, identifications · **kind:** `[DB]` · **cost:** moderate · **test:** only RETURN_REQUESTED; external ids exposed; refund is MoneyBag.

---

## D. Transfers & purchasing
```graphql
# D1 — transfers awaiting approval
{ transferOrders(query: "status:ORDER_CREATED facility_id:WH1", first: 1) {
    edges { node { orderId lineItems(first: 100) { productId quantity } shipments(first: 10) { shipmentId status } } }
    pageInfo { hasNextPage endCursor } } }
# D2 — PO receipts
{ purchaseOrder(id: "PO77") { orderId receipts(first: 100) { shipmentReceiptId productId quantityAccepted } } }
```
**Output (D1):**
```json
{ "data": { "transferOrders": { "edges": [ { "node": { "orderId": "TO3001",
  "lineItems": [ { "productId": "NN-HOODIE-BLK-M", "quantity": 12 } ],
  "shipments": [ { "shipmentId": "TS7001", "status": "SHIPMENT_INPUT" } ] } } ],
  "pageInfo": { "hasNextPage": false, "endCursor": "dG86VE8zMDAx" } } } }
```
**maps:** TransferOrder views; PurchaseOrder→ShipmentReceipt · **kind:** `[VIEW][DB]` · **cost:** moderate/cheap · **test:** status+facility query filters; receipts belong to PO77.

---

## E. Inventory & ATP (service-backed)
**Need:** can I promise this item here / online, and what's on hand.
```graphql
{ checkBopisInventory(facilityId: "STORE_07", productId: "NN-TEE-WHT-L", quantity: 2) { available atp }
  productOnlineAtp(productId: "NN-TEE-WHT-L", productStoreId: "ONLINE") { atp }
  facilityInventory(facilityId: "WH1", productId: "NN-TEE-WHT-L") { quantityOnHand atp safetyStock } }
```
**Output:**
```json
{ "data": { "checkBopisInventory": { "available": true, "atp": 7 },
  "productOnlineAtp": { "atp": 142 },
  "facilityInventory": { "quantityOnHand": 160, "atp": 142, "safetyStock": 18 } } }
```
**maps:** `get#BopisInventory`, `get#ProductOnlineAtp` (service); `ProductFacilityView` (view) · **kind:** `[SERVICE][VIEW]` · **cost:** high (service-backed → fixed high cost) · **test:** `available` boolean; `atp` numeric; service fields carry high fixed cost.

---

## F. Catalog (DB-backed structured query — Q1: DB only; full-text stays on Solr)
**Need:** find finished-goods in a category, or resolve a SKU.
```graphql
{ products(query: "category_id:TOPS product_type:FINISHED_GOOD", sortKey: TITLE, first: 2) {
    edges { node { id title identifications(first: 5) { type value } } }
    pageInfo { hasNextPage endCursor } } }
```
**Output:**
```json
{ "data": { "products": { "edges": [
  { "node": { "id": "NN-HOODIE-BLK-M", "title": "Black Hoodie / M",
      "identifications": [ { "type": "SKU", "value": "NN-HOODIE-BLK-M" }, { "type": "UPC", "value": "0810000000019" } ] } },
  { "node": { "id": "NN-TEE-WHT-L", "title": "White Tee / L",
      "identifications": [ { "type": "SKU", "value": "NN-TEE-WHT-L" } ] } } ],
  "pageInfo": { "hasNextPage": true, "endCursor": "cHJvZHVjdDpOTi1URUUtV0hULUw=" } } } }
```
**maps:** Product + GoodIdentification, declared keys · **kind:** `[DB]` · **cost:** cheap · **test:** category/type keys filter; SKU resolvable via identifications. *Keyword/faceted full-text search is NOT exposed via GraphQL — it stays on the existing Solr endpoints.*

---

## G. Facility & store config
```graphql
{ facility(id: "WH1") { name type address { city provinceCode }
    locations(first: 50) { locationSeqId } carriers(first: 20) { partyId } productStores(first: 10) { productStoreId } }
  productStore(id: "ONLINE") { shipmentMethods(first: 20) { shipmentMethodType carrierPartyId } } }
```
**Output:**
```json
{ "data": {
  "facility": { "name": "Dallas DC", "type": "WAREHOUSE", "address": { "city": "Dallas", "provinceCode": "TX" },
    "locations": [ { "locationSeqId": "A-01-01" } ], "carriers": [ { "partyId": "USPS" } ], "productStores": [ { "productStoreId": "ONLINE" } ] },
  "productStore": { "shipmentMethods": [ { "shipmentMethodType": "STANDARD", "carrierPartyId": "USPS" } ] } } }
```
**maps:** Facility (+ FacilityCarrier view); ProductStore + ProductStoreShipmentMethod · **kind:** `[DB][VIEW]` · **cost:** moderate · **test:** carriers via FacilityParty(role=CARRIER); methods resolve.

---

## H. Cycle count
```graphql
{ cycleCount(id: "CC1") { status facilityId items(first: 500) { locationSeqId productId countedQuantity variance } } }
```
**Output:** `{ "data": { "cycleCount": { "status": "INV_COUNT_COMPLETED", "facilityId": "WH1", "items": [ { "locationSeqId": "A-01-01", "productId": "NN-HOODIE-BLK-M", "countedQuantity": 98, "variance": -2 } ] } } }`
**maps:** WorkEffort/InventoryCountImport → items · **kind:** `[DB]` · **cost:** moderate (watch row cap) · **test:** items ≤ row cap; variance computed.

---

## I. Order routing
```graphql
{ orderRoutingGroup(id: "RG1") { status
    routings(first: 20) { orderRoutingId rules(first: 50) { field operator value } }
    runs(first: 10) { runId runDate orderCount } } }
```
**Output:** `{ "data": { "orderRoutingGroup": { "status": "ROUTING_ACTIVE", "routings": [ { "orderRoutingId": "OR1", "rules": [ { "field": "facilityId", "operator": "eq", "value": "WH1" } ] } ], "runs": [ { "runId": "RUN9001", "runDate": "2026-05-15T02:00:00Z", "orderCount": 312 } ] } } }`
**maps:** OrderRoutingGroup → routings → rules + runs · **kind:** `[DB][VIEW]` · **cost:** moderate · **test:** rules nest; runs ordered by date.

---

## J. External-ID lookup (Q5 — must-have; Shopify-parity naming `orderByIdentifier`)

```graphql
# J1 — by host external id (direct arg lookup)
{ order(externalId: "shopify:4567890") { id name status } }
# J2 — by typed identifier (Shopify-style orderByIdentifier)
{ orderByIdentifier(identifier: { type: NETSUITE_ORDER, value: "SO12345" }) {
    id name identifications(first: 5) { type value } } }
# J3 — batch resolve many host ids via query string (sync reconciliation)
{ orders(query: "external_id:shopify:4567890,shopify:4567999", first: 100) {
    edges { node { id externalId status } } pageInfo { hasNextPage endCursor } } }
# J4 — facility by external id
{ facility(externalId: "wms:DC-DALLAS") { id name type } }
```
**Output (J2):**
```json
{ "data": { "orderByIdentifier": { "id": "10001", "name": "NN10001",
  "identifications": [ { "type": "NETSUITE_ORDER", "value": "SO12345" }, { "type": "SHOPIFY_ORDER", "value": "4567890" } ] } } }
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
{ orders(query: "customer_id:CUST_88 status:ORDER_APPROVED", first: 20) {
    edges { node { id createdAt lineItems(first: 50) { productId promisedDate displayFulfillmentStatus } } } } }
# L2 — one-shot multi-root context
{ order(id: "10042") { name status } customer(id: "CUST_88") { displayName email orderCount } }
```
**Output (L2):**
```json
{ "data": { "order": { "name": "NN10042", "status": "ORDER_APPROVED" },
  "customer": { "displayName": "Jordan Lee", "email": "jordan@example.com", "orderCount": 7 } } }
```
**test:** agent query validates and runs; `displayFulfillmentStatus` present so the agent flags items past `promisedDate`; `orderCount` service-backed.
**Note (D-A tradeoff):** agents must emit the `query:` string correctly — the declared search-key list is published in the SDL so an agent can introspect allowed keys/comparators before composing.

---

## M. Connection pagination (Q4) — full cursor walk

```graphql
# M1 — page 1
{ orders(query: "status:ORDER_APPROVED", sortKey: CREATED_AT, first: 2) {
    edges { cursor node { id externalId } } pageInfo { hasNextPage hasPreviousPage startCursor endCursor } } }
# M2 — page 2 (pass previous endCursor as after)
{ orders(query: "status:ORDER_APPROVED", sortKey: CREATED_AT, first: 2, after: "b3JkZXI6MTAwMDU=") {
    edges { cursor node { id externalId } } pageInfo { hasNextPage hasPreviousPage startCursor endCursor } } }
```
**Output (M1):**
```json
{ "data": { "orders": {
  "edges": [ { "cursor": "b3JkZXI6MTAwMDE=", "node": { "id": "10001", "externalId": "shopify:4567890" } },
             { "cursor": "b3JkZXI6MTAwMDU=", "node": { "id": "10005", "externalId": "shopify:4567901" } } ],
  "pageInfo": { "hasNextPage": true, "hasPreviousPage": false, "startCursor": "b3JkZXI6MTAwMDE=", "endCursor": "b3JkZXI6MTAwMDU=" } } } }
```
**Output (M2):**
```json
{ "data": { "orders": {
  "edges": [ { "cursor": "b3JkZXI6MTAwMDk=", "node": { "id": "10009", "externalId": "shopify:4567912" } } ],
  "pageInfo": { "hasNextPage": false, "hasPreviousPage": true, "startCursor": "b3JkZXI6MTAwMDk=", "endCursor": "b3JkZXI6MTAwMDk=" } } } }
```
**maps:** OrderHeader; cursor encodes the stable sort key (`createdAt`, then `id` tiebreaker) · **kind:** `[DB]` · **cost:** moderate ·
**test:** page 2 starts strictly after page 1's last edge — **no overlap, no skip**; `union(pages) == same query unpaged` (set equality oracle); `hasNextPage==false` on the final page; inserting a row ahead of the cursor mid-walk does NOT cause a re-seen id (the property offset pagination fails). Backward paging (`last`/`before`) mirrors this with `hasPreviousPage`.

---

## N. Guardrail boundary — MUST be rejected (exact error)

```graphql
{ orders(first: 1000) { edges { node { lineItems(first: 1000) { edges { node { adjustments(first: 1000) { edges { node { id } } } } } } } } } }   # N1
{ orders(query: "order_name:Gift", first: 50) { edges { node { id } } } }                                                                       # N2 (undeclared key)
{ orders(query: "status:ORDER_APPROVED") { edges { node { id } } } }                                                                             # N3 (missing first)
```
```json
// N1
{ "errors": [ { "message": "query cost 1,000,000,000 exceeds max 1000", "extensions": { "code": "COST_EXCEEDED", "estimatedCost": 1000000000, "maxCost": 1000 } } ], "data": null }
// N2
{ "errors": [ { "message": "search key 'order_name' is not filterable (allowed: id, external_id, name, status, created_at, customer_id, product_store_id)", "extensions": { "code": "FIELD_NOT_FILTERABLE", "key": "order_name" } } ], "data": null }
// N3
{ "errors": [ { "message": "list field 'orders' requires 'first:' or 'last:' (1..100)", "extensions": { "code": "FIRST_REQUIRED", "field": "orders", "maxFirst": 100 } } ], "data": null }
// N4 (depth)
{ "errors": [ { "message": "query depth 8 exceeds max 6", "extensions": { "code": "DEPTH_EXCEEDED", "depth": 8, "maxDepth": 6 } } ], "data": null }
```
**test (all N):** structured error, `data:null`, stable `extensions.code`, message names the offending key/limit, nothing executes against the DB. (Q3b above covers `OPERATOR_NOT_ALLOWED`.)

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
- **Shopify-shaped (shopify-alignment.md):** `query:` string filtering (D-A), raw entity ids (D-B), `sortKey`+`reverse`, full Relay `PageInfo`, `MoneyBag`/`MoneyV2`, `DateTime`/`Decimal`, `displayFulfillmentStatus` enum, Shopify field names (`createdAt`, `lineItems`, `customer`), `orderByIdentifier`.
- **Q1 DB-backed only** · **Q2 analytics deferred** · **Q3 declare-and-control** (over the query grammar) · **Q4 Relay connections** · **Q5 external-id must-have**.

## Out of scope (not represented as queries)
Writes/mutations, analytics/aggregation (deferred), full-text/faceted Solr search, `unigate` RPC,
print/export (PDF/CSV), inbound webhooks (Shopify/ADP/AfterShip), live external assembly, global
IDs / `node()` (D-B: raw ids).
