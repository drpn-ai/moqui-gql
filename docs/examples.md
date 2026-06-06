# moqui-gql — Capability Examples & Test-Case Catalog

**Status:** Requirements / design phase. Source of truth for what the API does, and the basis for
the test suite. Every example is a `Need → Query → Output` triple.

**Field names are OUR OMS data model** (`orderId`, `orderDate`, `statusId`, `grandTotal`,
`orderItems`, `fulfillmentStatus`, …). From **Shopify we adopt only the query language**: `query:`
search-string filtering, `sortKey`+`reverse`, Relay cursor connections, the cost/error envelope.
**IDs are raw entity keys** (D-B — no `gid://`/`Node`). DB-backed only (Q1); analytics deferred (Q2).
Validates against `schema.graphql` (plan Task 7 + Task 14 bind code to this catalog).

---

## How to read an example
- **Need / Query / Output** triple; **Footer:** `maps:` · `kind:` `[DB|VIEW|SERVICE]` · `cost:` class · `test:` assertions.

### Conventions

1. **Envelope.** Success → `{ "data": {…}, "extensions": {…} }`; error → `{ "errors": [...], "data": null }`. HTTP 200 either way.
2. **IDs are raw entity keys** (D-B): `orderId: "10001"`, composite like `"10001:00001"`. No global IDs / `node()`.
3. **Connections vs lists (G1, hybrid by size).** *Connections* (select via `edges { node }`; accept
   `first/after/last/before`): all root lists, and nested **orderItems, shipGroups, returns,
   returnItems, picklistItems, cycle-count items, transferOrder orderItems/shipments**. *Plain
   bounded lists* (select fields directly; `first:N` cap, no cursors): **statuses, adjustments,
   identifications, paymentPreferences, shipment packages/route-segments, facility sub-lists,
   product variants, routing rules/runs, PO receipts**. (There is no "shorthand" — connections
   always require `edges/node`.)
4. **Filtering = `query:` search string** (Shopify syntax, our field names as keys): only declared
   keys + comparators (the SDL `@search` + field description list them). DB-backed (Q1), governed by Q3.
5. **Sorting = `sortKey: <Type>SortKey` enum + `reverse: Boolean`** (singular enum names; values are our fields).
6. **`first` is capped at `maxFirst` (=100)** on every connection AND nested edge; exceeding it is rejected.
7. **Money & quantities are `Decimal`, serialized as STRINGS** (`"129.00"`, `"7"`). Dates are `DateTime`.
8. **`extensions.cost` is illustrative**, not an asserted value — the engine computes it. `throttleStatus`
   reflects the **live per-caller bucket** (debited by cost, refilled at `restoreRate`/s) — see [`throttle.md`](throttle.md).
9. **Errors** carry stable `extensions.code`: `COST_EXCEEDED`, `FIELD_NOT_FILTERABLE`, `OPERATOR_NOT_ALLOWED`, `FIRST_REQUIRED`, `FIRST_TOO_LARGE`, `DEPTH_EXCEEDED`, `BATCH_LIMIT_EXCEEDED`.

---

## Declared search keys & sort keys (Q3) — Order root

| Search key | Comparators | Maps to | Index |
|---|---|---|---|
| `orderId` | eq, in | OrderHeader.orderId (PK) | PK |
| `externalId` | eq, in | OrderHeader.externalId | idx |
| `orderName` | eq | OrderHeader.orderName | idx |
| `statusId` | eq, in | OrderHeader.statusId | idx |
| `orderDate` | >, >=, <, <= | OrderHeader.orderDate | idx |
| `customerPartyId` | eq, in | OrderRole(BILL_TO).partyId | idx |
| `productStoreId` | eq, in | OrderHeader.productStoreId | idx |

`OrderSortKey`: `ORDER_DATE`, `ORDER_NAME`, `GRAND_TOTAL`, `ORDER_ID`. Unknown key →
`FIELD_NOT_FILTERABLE`; bad comparator (e.g. `statusId:>X`) → `OPERATOR_NOT_ALLOWED`. **Every
declared key must be index-backed** (a declared-but-unindexed key is rejected at schema build — see §N7 for the query-time guard).

### Q3a — allowed keys + comparators
**Need:** "Approved/held ONLINE orders placed in May, newest first."
```graphql
query Orders {
  orders(query: "statusId:ORDER_APPROVED,ORDER_HELD productStoreId:ONLINE orderDate:>=2026-05-01 orderDate:<=2026-05-31",
         sortKey: ORDER_DATE, reverse: true, first: 2) {
    edges { node { orderId orderName statusId orderDate } }
    pageInfo { hasNextPage hasPreviousPage startCursor endCursor } } }
```
```json
{ "data": { "orders": { "edges": [
  { "node": { "orderId": "10042", "orderName": "NN10042", "statusId": "ORDER_APPROVED", "orderDate": "2026-05-28T16:10:00Z" } },
  { "node": { "orderId": "10039", "orderName": "NN10039", "statusId": "ORDER_HELD", "orderDate": "2026-05-27T11:02:00Z" } } ],
  "pageInfo": { "hasNextPage": true, "hasPreviousPage": false, "startCursor": "b3JkZXI6MTAwNDI=", "endCursor": "b3JkZXI6MTAwMzk=" } } } }
```
**maps:** OrderHeader, declared+indexed keys · **kind:** `[DB]` · **cost:** cheap · **test:** `in`+range honored; desc by orderDate; full PageInfo.

### Q3b — disallowed comparator → rejected
```graphql
{ orders(query: "statusId:>ORDER_APPROVED", first: 5) { edges { node { orderId } } } }
```
```json
{ "errors": [ { "message": "comparator '>' not allowed on search key 'statusId' (allowed: eq, in)",
  "extensions": { "code": "OPERATOR_NOT_ALLOWED", "key": "statusId", "allowed": ["eq","in"] } } ], "data": null }
```

---

## A. Order

### A1 — Order detail (CS agent / order screen)
**Need:** open one order with everything an agent needs.
```graphql
query OrderDetail($orderId: ID!) {
  order(orderId: $orderId) {
    orderName statusId orderDate grandTotal currencyUomId customerPartyId customerName
    billingAddress { address1 city stateProvinceGeoId postalCode countryGeoId }
    orderItems(first: 50) { edges { node { orderItemSeqId productId quantity unitPrice statusId fulfillmentStatus promisedDate } } }
    shipGroups(first: 10) { edges { node { shipGroupSeqId shipmentMethodTypeId carrierPartyId trackingCode facility { facilityName } } } }
    statuses(first: 50) { statusId statusDatetime }
    paymentPreferences(first: 20) { paymentMethodTypeId maxAmount statusId } } }
```
Variables: `{ "orderId": "10001" }`
```json
{ "data": { "order": {
  "orderName": "NN10001", "statusId": "ORDER_APPROVED", "orderDate": "2026-05-14T09:32:00Z",
  "grandTotal": "129.00", "currencyUomId": "USD", "customerPartyId": "CUST_88", "customerName": "Jordan Lee",
  "billingAddress": { "address1": "123 Main St", "city": "Austin", "stateProvinceGeoId": "USA_TX", "postalCode": "78701", "countryGeoId": "USA" },
  "orderItems": { "edges": [
    { "node": { "orderItemSeqId": "00001", "productId": "NN-HOODIE-BLK-M", "quantity": 1, "unitPrice": "89.00", "statusId": "ITEM_APPROVED", "fulfillmentStatus": "PROCESSING", "promisedDate": "2026-05-20T00:00:00Z" } },
    { "node": { "orderItemSeqId": "00002", "productId": "NN-SOCK-3PK", "quantity": 2, "unitPrice": "20.00", "statusId": "ITEM_COMPLETED", "fulfillmentStatus": "COMPLETED", "promisedDate": "2026-05-18T00:00:00Z" } } ] },
  "shipGroups": { "edges": [ { "node": { "shipGroupSeqId": "00001", "shipmentMethodTypeId": "STANDARD", "carrierPartyId": "USPS", "trackingCode": "9400111899560000000000", "facility": { "facilityName": "Dallas DC" } } } ] },
  "statuses": [ { "statusId": "ORDER_CREATED", "statusDatetime": "2026-05-14T09:32:00Z" }, { "statusId": "ORDER_APPROVED", "statusDatetime": "2026-05-14T09:40:00Z" } ],
  "paymentPreferences": [ { "paymentMethodTypeId": "CREDIT_CARD", "maxAmount": "129.00", "statusId": "PMNT_SETTLED" } ] } },
  "extensions": { "cost": { "requestedQueryCost": 173, "actualQueryCost": 173,
    "throttleStatus": { "maximumAvailable": 1000, "currentlyAvailable": 827, "restoreRate": 50 } } } }
```
**maps:** OrderHeader + connections + plain lists; `customerName`/`fulfillmentStatus` service-backed; billingAddress via OrderContactMech→PostalAddress · **kind:** `[DB][VIEW][SERVICE]` · **cost:** moderate (illustrative) · **test:** orderItems/shipGroups returned as connections (`edges[].node`); statuses/payments as plain lists; money as string `"129.00"`; `fulfillmentStatus` ∈ enum.

### A2 — Open-orders queue, first page
```graphql
{ orders(query: "statusId:ORDER_APPROVED", sortKey: ORDER_DATE, first: 2) {
    edges { cursor node { orderId orderName orderDate grandTotal customerName } }
    pageInfo { hasNextPage hasPreviousPage startCursor endCursor } } }
```
```json
{ "data": { "orders": { "edges": [
  { "cursor": "b3JkZXI6MTAwMDE=", "node": { "orderId": "10001", "orderName": "NN10001", "orderDate": "2026-05-14T09:32:00Z", "grandTotal": "129.00", "customerName": "Jordan Lee" } },
  { "cursor": "b3JkZXI6MTAwMDU=", "node": { "orderId": "10005", "orderName": "NN10005", "orderDate": "2026-05-14T10:05:00Z", "grandTotal": "54.00", "customerName": "Priya Shah" } } ],
  "pageInfo": { "hasNextPage": true, "hasPreviousPage": false, "startCursor": "b3JkZXI6MTAwMDE=", "endCursor": "b3JkZXI6MTAwMDU=" } } } }
```
**kind:** `[DB]` · **cost:** cheap · **test:** ≤2 edges; all approved; ascending orderDate; cursors correct.

---

## B. Fulfillment & shipping

### B0 — Shipments queue (NEW, R1)
**Need:** "shipments shipped today from WH1, by status." *Today:* no such REST shape (by-id only).
```graphql
{ shipments(query: "originFacilityId:WH1 statusId:SHIPMENT_SHIPPED shippedDate:>=2026-05-15", sortKey: SHIPPED_DATE, reverse: true, first: 2) {
    edges { node { shipmentId statusId originFacilityId shippedDate } } pageInfo { hasNextPage endCursor } } }
```
```json
{ "data": { "shipments": { "edges": [
  { "node": { "shipmentId": "SH900", "statusId": "SHIPMENT_SHIPPED", "originFacilityId": "WH1", "shippedDate": "2026-05-15T18:20:00Z" } } ],
  "pageInfo": { "hasNextPage": false, "endCursor": "c2hpcDpTSDkwMA==" } } } }
```
**maps:** Shipment, declared keys · **kind:** `[DB]` · **cost:** cheap · **test:** facility+status+date filter; sorted desc.

### B1 — Shipment detail with tracking
```graphql
{ shipment(shipmentId: "SH900") { statusId originFacilityId
    shipmentPackages(first: 20) { shipmentPackageSeqId trackingCode labelImageUrl shipmentPackageContents(first: 50) { productId quantity } }
    shipmentRouteSegments(first: 5) { carrierPartyId trackingIdNumber trackingUrl } } }
```
```json
{ "data": { "shipment": { "statusId": "SHIPMENT_SHIPPED", "originFacilityId": "WH1",
  "shipmentPackages": [ { "shipmentPackageSeqId": "00001", "trackingCode": "9400111899560000000000", "labelImageUrl": "https://cdn.example/labels/SH900-1.png",
    "shipmentPackageContents": [ { "productId": "NN-HOODIE-BLK-M", "quantity": 1 } ] } ],
  "shipmentRouteSegments": [ { "carrierPartyId": "USPS", "trackingIdNumber": "9400111899560000000000", "trackingUrl": "https://tools.usps.com/go/TrackConfirmAction?tLabels=9400111899560000000000" } ] } } }
```
**kind:** `[DB][VIEW]` · **cost:** moderate · **test:** packages/route-segments are plain lists (direct selection); tracking present when shipped.

### B2 — BOPIS pickup queue
```graphql
{ pickupOrders(query: "facilityId:STORE_07", first: 25) {
    edges { node { orderId orderName customerName
      shipGroups(first: 5) { edges { node { picklistId picker { firstName lastName } orderItems(first: 50) { edges { node { productId quantity } } } } } } } }
    pageInfo { hasNextPage endCursor } } }
```
```json
{ "data": { "pickupOrders": { "edges": [ { "node": {
  "orderId": "10077", "orderName": "NN10077", "customerName": "Sam Rivera",
  "shipGroups": { "edges": [ { "node": { "picklistId": "PICK55", "picker": { "firstName": "Dana", "lastName": "Cole" },
    "orderItems": { "edges": [ { "node": { "productId": "NN-TEE-WHT-L", "quantity": 1 } } ] } } } ] } } } ],
  "pageInfo": { "hasNextPage": false, "endCursor": "b3JkZXI6MTAwNzc=" } } } }
```
**kind:** `[VIEW][SERVICE]` · **cost:** moderate · **test:** nested connections (`shipGroups`, `orderItems`) selected via `edges/node`.

### B3 — Ready-to-pick warehouse queue (Gorjana) — deployment-specific keys
```graphql
{ readyToPickOrders(query: "facilityId:WH1 brandName:Fine isGift:true", sortKey: ORDER_DATE, reverse: true, first: 2) {
    edges { node { orderId orderName netsuiteOrderName shipToStateProvinceGeoId } } pageInfo { hasNextPage endCursor } } }
```
```json
{ "data": { "readyToPickOrders": { "edges": [
  { "node": { "orderId": "10088", "orderName": "NN10088", "netsuiteOrderName": "SO20088", "shipToStateProvinceGeoId": "USA_CA" } } ],
  "pageInfo": { "hasNextPage": false, "endCursor": "b3JkZXI6MTAwODg=" } } } }
```
**kind:** `[VIEW]` · **test:** `brandName`/`isGift` keys honored only in the Gorjana schema; undeclared elsewhere → `FIELD_NOT_FILTERABLE`.

### B4 — Picklist with items
```graphql
{ picklist(picklistId: "PICK55") { statusId picklistDate
    picklistItems(first: 100) { edges { node { orderName productId quantity itemStatusId } } pageInfo { hasNextPage endCursor } } } }
```
```json
{ "data": { "picklist": { "statusId": "PICKLIST_PICKING", "picklistDate": "2026-05-15T08:00:00Z",
  "picklistItems": { "edges": [ { "node": { "orderName": "NN10077", "productId": "NN-TEE-WHT-L", "quantity": 1, "itemStatusId": "ITEM_APPROVED" } } ],
  "pageInfo": { "hasNextPage": true, "endCursor": "cGk6UElDSzU1OjAwMDAx" } } } } }
```
**kind:** `[VIEW]` · **cost:** moderate · **test:** `picklistItems` is a connection (`first ≤ 100`).

---

## C. Returns
```graphql
{ returns(query: "statusId:RETURN_REQUESTED", sortKey: RETURN_DATE, reverse: true, first: 1) {
    edges { node { returnId statusId
      identifications(first: 5) { returnIdentificationTypeId idValue }
      returnItems(first: 100) { edges { node { orderId productId returnQuantity refundAmount } } } } }
    pageInfo { hasNextPage endCursor } } }
```
```json
{ "data": { "returns": { "edges": [ { "node": {
  "returnId": "RT5001", "statusId": "RETURN_REQUESTED",
  "identifications": [ { "returnIdentificationTypeId": "AFTERSHIP_RTN_ID", "idValue": "AS-99812" } ],
  "returnItems": { "edges": [ { "node": { "orderId": "10001", "productId": "NN-SOCK-3PK", "returnQuantity": 1, "refundAmount": "20.00" } } ] } } } ],
  "pageInfo": { "hasNextPage": true, "endCursor": "cmV0dXJuOlJUNTAwMQ==" } } } }
```
**kind:** `[DB]` · **test:** `returnItems` is a connection; `identifications` a plain list; refundAmount string.

---

## D. Transfers & purchasing
```graphql
# D1 — transfers awaiting approval
{ transferOrders(query: "statusId:ORDER_CREATED facilityId:WH1", first: 1) {
    edges { node { orderId
      orderItems(first: 100) { edges { node { productId quantity } } }
      shipments(first: 10) { edges { node { shipmentId statusId } } } } }
    pageInfo { hasNextPage endCursor } } }
# D2 — PO receipts (plain list)
{ purchaseOrder(orderId: "PO77") { orderId receipts(first: 100) { shipmentReceiptId productId quantityAccepted } } }
```
```json
{ "data": { "transferOrders": { "edges": [ { "node": { "orderId": "TO3001",
  "orderItems": { "edges": [ { "node": { "productId": "NN-HOODIE-BLK-M", "quantity": 12 } } ] },
  "shipments": { "edges": [ { "node": { "shipmentId": "TS7001", "statusId": "SHIPMENT_INPUT" } } ] } } } ],
  "pageInfo": { "hasNextPage": false, "endCursor": "dG86VE8zMDAx" } } } }
```
**kind:** `[VIEW][DB]` · **test:** transferOrder `orderItems`/`shipments` are connections; PO `receipts` a plain list; `quantityAccepted` string.

---

## E. Inventory & ATP (service-backed)
```graphql
{ checkBopisInventory(facilityId: "STORE_07", productId: "NN-TEE-WHT-L", quantity: 2) { available atp }
  productOnlineAtp(productId: "NN-TEE-WHT-L", productStoreId: "ONLINE") { atp }
  facilityInventory(facilityId: "WH1", productId: "NN-TEE-WHT-L") { quantityOnHandTotal availableToPromiseTotal minimumStock }
  # bulk ATP (R3): many SKUs across facilities in one call
  inventoryLevels(productIds: ["NN-TEE-WHT-L","NN-HOODIE-BLK-M"], facilityIds: ["WH1","STORE_07"]) {
    productId facilityId availableToPromiseTotal } }
```
```json
{ "data": {
  "checkBopisInventory": { "available": true, "atp": "7" },
  "productOnlineAtp": { "atp": "142" },
  "facilityInventory": { "quantityOnHandTotal": "160", "availableToPromiseTotal": "142", "minimumStock": "18" },
  "inventoryLevels": [
    { "productId": "NN-TEE-WHT-L", "facilityId": "WH1", "availableToPromiseTotal": "142" },
    { "productId": "NN-TEE-WHT-L", "facilityId": "STORE_07", "availableToPromiseTotal": "7" },
    { "productId": "NN-HOODIE-BLK-M", "facilityId": "WH1", "availableToPromiseTotal": "33" },
    { "productId": "NN-HOODIE-BLK-M", "facilityId": "STORE_07", "availableToPromiseTotal": "0" } ] } }
```
**maps:** `get#BopisInventory`/`get#ProductOnlineAtp`/`get#InventoryLevels` (service); `ProductFacilityView` (view) · **kind:** `[SERVICE][VIEW]` · **cost:** high (service-backed fixed cost) · **test:** Decimal as strings; `inventoryLevels` capped at `maxInventoryKeys` product×facility pairs (→ `BATCH_LIMIT_EXCEEDED` beyond it).

---

## F. Catalog (DB-backed; full-text stays on Solr — Q1)
```graphql
{ products(query: "primaryProductCategoryId:TOPS productTypeId:FINISHED_GOOD", sortKey: PRODUCT_NAME, first: 2) {
    edges { node { productId productName identifications(first: 5) { goodIdentificationTypeId idValue } } }
    pageInfo { hasNextPage endCursor } } }
```
```json
{ "data": { "products": { "edges": [
  { "node": { "productId": "NN-HOODIE-BLK-M", "productName": "Black Hoodie / M",
      "identifications": [ { "goodIdentificationTypeId": "SKU", "idValue": "NN-HOODIE-BLK-M" }, { "goodIdentificationTypeId": "UPCA", "idValue": "0810000000019" } ] } } ],
  "pageInfo": { "hasNextPage": true, "endCursor": "cHJvZHVjdDpOTi1IT09ESUUtQkxLLU0=" } } } }
```
**kind:** `[DB]` · **cost:** cheap · **test:** category/type keys; SKU via identifications (plain list). Keyword full-text NOT here (Solr).

---

## G. Facility & store config
```graphql
{ facility(facilityId: "WH1") { facilityName facilityTypeId
    contactMechs(first: 5) { contactMechPurposeTypeId postalAddress { city stateProvinceGeoId } }
    carriers(first: 20) { partyId } productStores(first: 10) { productStoreId } }
  productStore(productStoreId: "ONLINE") { shipmentMethods(first: 20) { shipmentMethodTypeId carrierPartyId } } }
```
```json
{ "data": {
  "facility": { "facilityName": "Dallas DC", "facilityTypeId": "WAREHOUSE",
    "contactMechs": [ { "contactMechPurposeTypeId": "PRIMARY_LOCATION", "postalAddress": { "city": "Dallas", "stateProvinceGeoId": "USA_TX" } } ],
    "carriers": [ { "partyId": "USPS" } ], "productStores": [ { "productStoreId": "ONLINE" } ] },
  "productStore": { "shipmentMethods": [ { "shipmentMethodTypeId": "STANDARD", "carrierPartyId": "USPS" } ] } } }
```
**kind:** `[DB][VIEW]` · **test:** contactMechs via purpose view; all plain lists.

---

## H. Cycle count
```graphql
{ cycleCount(inventoryCountImportId: "CC1") { statusId facilityId
    items(first: 100) { edges { node { locationSeqId productId quantity varianceQuantityOnHand } } pageInfo { hasNextPage endCursor } } } }
```
```json
{ "data": { "cycleCount": { "statusId": "INV_COUNT_COMPLETED", "facilityId": "WH1",
  "items": { "edges": [ { "node": { "locationSeqId": "A-01-01", "productId": "NN-HOODIE-BLK-M", "quantity": "98", "varianceQuantityOnHand": "-2" } } ],
  "pageInfo": { "hasNextPage": true, "endCursor": "Y2M6Q0MxOkEtMDEtMDE=" } } } } }
```
**kind:** `[DB]` · **cost:** moderate · **test:** `items` is a connection (`first ≤ 100`); Decimal as strings.

---

## I. Order routing
```graphql
{ orderRoutingGroup(routingGroupId: "RG1") { statusId
    routings(first: 20) { orderRoutingId rules(first: 50) { fieldName operatorEnumId fieldValue } }
    runs(first: 10) { routingRunId runStartTime orderCount } } }
```
```json
{ "data": { "orderRoutingGroup": { "statusId": "ROUTING_ACTIVE",
  "routings": [ { "orderRoutingId": "OR1", "rules": [ { "fieldName": "facilityId", "operatorEnumId": "EQUALS", "fieldValue": "WH1" } ] } ],
  "runs": [ { "routingRunId": "RUN9001", "runStartTime": "2026-05-15T02:00:00Z", "orderCount": 312 } ] } } }
```
**kind:** `[DB][VIEW]` · **test:** routings/rules/runs are plain lists.

---

## J. External-ID & party lookup (Q5 + R2)
```graphql
# J1 — order by host external id
{ order(externalId: "shopify:4567890") { orderId orderName statusId } }
# J2 — order by typed identification
{ orderByIdentification(identificationTypeId: "NETSUITE_ORDER", idValue: "SO12345") {
    orderId orderName identifications(first: 5) { orderIdentificationTypeId idValue } } }
# J3 — batch resolve many host ids (sync reconciliation)
{ orders(query: "externalId:shopify:4567890,shopify:4567999", first: 100) {
    edges { node { orderId externalId statusId } } pageInfo { hasNextPage endCursor } } }
# J4 — facility by external id
{ facility(externalId: "wms:DC-DALLAS") { facilityId facilityName facilityTypeId } }
# J5 — customer/party lookup (NEW, R2): resolve a customer, then their orders
{ parties(query: "lastName:Lee emailAddress:jordan@example.com", first: 5) {
    edges { node { partyId firstName lastName emailAddress orderCount } } pageInfo { hasNextPage endCursor } } }
```
```json
{ "data": { "parties": { "edges": [
  { "node": { "partyId": "CUST_88", "firstName": "Jordan", "lastName": "Lee", "emailAddress": "jordan@example.com", "orderCount": 7 } } ],
  "pageInfo": { "hasNextPage": false, "endCursor": "cGFydHk6Q1VTVF84OA==" } } } }
```
**maps:** OrderIdentification / `externalId` / Party search · **kind:** `[DB][SERVICE]` · **cost:** cheap (J5 `orderCount` service-backed) · **test:** order reachable by NetSuite id and Shopify id; `parties` lookup yields `partyId` the agent then uses in `orders(query:"customerPartyId:...")`.

---

## K. Analytics / BI — DEFERRED (Q2)
> Not in initial scope; `oms-bi` facts back it later. Illustrative only:
```graphql
{ fulfillmentMetrics(facilityId: "WH1", dateFrom: "2026-05-01", dateTo: "2026-05-31", groupBy: DAY) { date unitsShipped cancelRate } }
```

---

## L. AI-agent composites
```graphql
# L1 — "which of this customer's orders are stuck in processing?"
{ orders(query: "customerPartyId:CUST_88 statusId:ORDER_APPROVED", first: 20) {
    edges { node { orderId orderDate orderItems(first: 50) { edges { node { productId promisedDate fulfillmentStatus } } } } } } }
# L2 — multi-root context
{ order(orderId: "10042") { orderName statusId } party(partyId: "CUST_88") { firstName lastName orderCount } }
```
**Agent loop (introspect → compose → correct):** an agent reads the connection field's description
(or `@search` keys) to learn allowed search keys/comparators, composes `query:`, and on
`OPERATOR_NOT_ALLOWED`/`FIELD_NOT_FILTERABLE` self-corrects. The search grammar is in the SDL field
descriptions precisely so a standard introspection makes it visible.
**test:** L1 `orderItems` is a connection; `fulfillmentStatus` present; a bad-key query returns the documented error code an agent can act on.

---

## M. Connection pagination (Q4) — cursor walk
```graphql
# M1 — page 1
{ orders(query: "statusId:ORDER_APPROVED", sortKey: ORDER_DATE, first: 2) {
    edges { cursor node { orderId externalId } } pageInfo { hasNextPage hasPreviousPage startCursor endCursor } } }
# M2 — page 2 (pass previous endCursor as after)
{ orders(query: "statusId:ORDER_APPROVED", sortKey: ORDER_DATE, first: 2, after: "b3JkZXI6MTAwMDU=") {
    edges { cursor node { orderId externalId } } pageInfo { hasNextPage hasPreviousPage startCursor endCursor } } }
```
```json
// M1
{ "data": { "orders": { "edges": [
  { "cursor": "b3JkZXI6MTAwMDE=", "node": { "orderId": "10001", "externalId": "shopify:4567890" } },
  { "cursor": "b3JkZXI6MTAwMDU=", "node": { "orderId": "10005", "externalId": "shopify:4567901" } } ],
  "pageInfo": { "hasNextPage": true, "hasPreviousPage": false, "startCursor": "b3JkZXI6MTAwMDE=", "endCursor": "b3JkZXI6MTAwMDU=" } } } }
// M2
{ "data": { "orders": { "edges": [ { "cursor": "b3JkZXI6MTAwMDk=", "node": { "orderId": "10009", "externalId": "shopify:4567912" } } ],
  "pageInfo": { "hasNextPage": false, "hasPreviousPage": true, "startCursor": "b3JkZXI6MTAwMDk=", "endCursor": "b3JkZXI6MTAwMDk=" } } } }
```
**maps:** OrderHeader; cursor encodes the stable sort key `(orderDate, orderId)` — keyset predicate `orderDate > X OR (orderDate = X AND orderId > Y)` · **kind:** `[DB]` · **cost:** moderate ·
**test:** page 2 starts strictly after page 1's last edge — **no overlap, no skip**; `union(pages) == unpaged set`; `hasNextPage` false on final page; insert-ahead mid-walk does NOT cause a re-seen id; non-unique/null sort values and backward paging (`last`/`before`) tested.

---

## N. Guardrail boundary — MUST be rejected (exact error)
```graphql
# N1 — fan-out bomb (nested connections, each within first cap but multiplied)
{ orders(first: 100) { edges { node { orderItems(first: 100) { edges { node { adjustments(first: 50) { orderAdjustmentId } } } } } } } }
# N2 — undeclared search key
{ orders(query: "orderName2:Gift", first: 50) { edges { node { orderId } } } }
# N3 — missing first/last on a connection
{ orders(query: "statusId:ORDER_APPROVED") { edges { node { orderId } } } }
# N5 — first over the cap on a nested edge
{ order(orderId: "10001") { orderItems(first: 5000) { edges { node { productId } } } } }
# N6 — service-backed field under a large nested list (batch-key blast radius)
{ orders(first: 100) { edges { node { orderItems(first: 100) { edges { node { fulfillmentStatus } } } } } } }
# N7 — declared-but-unindexed search key (deployment misconfig caught at query time)
{ orders(query: "customerName:Lee", first: 50) { edges { node { orderId } } } }
```
```json
// N1
{ "errors": [ { "message": "query cost 500000 exceeds max 1000", "extensions": { "code": "COST_EXCEEDED", "estimatedCost": 500000, "maxCost": 1000 } } ], "data": null }
// N2
{ "errors": [ { "message": "search key 'orderName2' is not filterable (allowed: orderId, externalId, orderName, statusId, orderDate, customerPartyId, productStoreId)", "extensions": { "code": "FIELD_NOT_FILTERABLE", "key": "orderName2" } } ], "data": null }
// N3
{ "errors": [ { "message": "connection field 'orders' requires 'first:' or 'last:' (1..100)", "extensions": { "code": "FIRST_REQUIRED", "field": "orders", "maxFirst": 100 } } ], "data": null }
// N5
{ "errors": [ { "message": "'first: 5000' on 'orderItems' exceeds maxFirst 100", "extensions": { "code": "FIRST_TOO_LARGE", "field": "orderItems", "maxFirst": 100 } } ], "data": null }
// N6
{ "errors": [ { "message": "service-backed field 'fulfillmentStatus' would resolve 10000 keys; exceeds batch limit 1000", "extensions": { "code": "BATCH_LIMIT_EXCEEDED", "field": "fulfillmentStatus", "keys": 10000, "limit": 1000 } } ], "data": null }
// N7
{ "errors": [ { "message": "search key 'customerName' is not filterable", "extensions": { "code": "FIELD_NOT_FILTERABLE", "key": "customerName" } } ], "data": null }
// N4 (depth) — { "errors": [ { "message": "query depth 8 exceeds max 6", "extensions": { "code": "DEPTH_EXCEEDED", "depth": 8, "maxDepth": 6 } } ], "data": null }
```
**test (all N):** structured error, `data:null`, stable `extensions.code`, message names the offending key/limit, **nothing executes against the DB**. N6 proves the service-backed batch-key cap (the analyzer's blind-spot mitigation); N5 the per-edge `first` cap; N7 the unindexed-key guard.

---

## Coverage matrix (domain × capability)

| Domain | by-id | query+sort | nested | computed | view | ext-id | connection |
|---|---|---|---|---|---|---|---|
| Order | A1 | A2,Q3a | A1 | A1,L1 | A1 | J1,J2,J3 | A2,M |
| Shipment | B1 | B0 | B1 | — | B1 | — | B0 |
| Picklist/Pick | B4 | B3 | B4 | — | B3,B4 | — | B3,B4 |
| Returns | — | C | C | — | — | C | C |
| Transfer/PO | D2 | D1 | D1,D2 | — | D1 | — | D1 |
| Inventory/ATP | — | — | — | E | E | — | — |
| Catalog | F | F | F | — | F | F | F |
| Facility/Store | G | — | G | — | G | J4 | — |
| CycleCount | H | — | H | — | — | — | H |
| Routing | I | — | I | — | I | — | — |
| Party | L2 | J5 | — | J5 | — | J1,J4 | J5 |

Out of scope (no column): analytics/aggregation (Q2 deferred), full-text/faceted Solr search (Q1).

---

## Decisions baked in (2026-06-03)
- **Shopify query language only:** `query:` string, `sortKey`+`reverse`, full Relay connections, cost/error envelope. **Field names are our OMS model.** **Raw entity ids** (D-B).
- **G1 hybrid connections** (large=connection, small metadata=plain list). **R1–R3 coverage:** shipments queue (B0), party lookup (J5), bulk ATP (E `inventoryLevels`).
- Q1 DB-only · Q2 analytics deferred · Q3 declare-and-control · Q4 Relay connections · Q5 external-id.
- **Governance:** `maxFirst=100` per edge (N5); service-backed batch-key cap (N6); unindexed-key guard (N7); cost saturated in long (no overflow); `extensions.cost` illustrative, `throttleStatus` is the live per-caller bucket.

## Out of scope
Writes/mutations, analytics (deferred), full-text Solr, `unigate` RPC, print/export, inbound webhooks, live external assembly, global IDs / `node()` (D-B).
