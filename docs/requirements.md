# GraphQL API Requirements — Derived from the Existing Maarg Surface

**Date:** 2026-06-03
**Purpose:** Reverse-engineer the requirements for the new GraphQL read API from what Maarg
already exposes. What consumers already pull (published REST, remote services, data feeds,
indexed search, BI facts, client views) *is* the requirement set.
**Method:** Four inventories across the suite — published REST APIs, DataDocuments/feeds/BI,
the `unigate` standalone API + remote services + search, and client components (`gorjana-maarg`,
`notnaked`).
**Feeds:** the main design spec (`design.md`), the SDL contract (`schema.graphql`), and the
Shopify alignment (`shopify-alignment.md`).

---

## Part 1 — Domain object catalog (the type surface)

Grouped by tier. These are the GraphQL types the API must be able to express, with the
relationship edges already in use.

### Tier 1 — Core transactional (highest priority)
| Type | Backing | Edges already exposed in the wild |
|---|---|---|
| **Order** | OrderHeader (+ `OrderExtendedEntities`) | items, shipGroups, statuses (history), attributes, adjustments, payments, contactMechs (billing/shipping), roles, identifications, returns, reservations |
| **OrderItem** | OrderItem | adjustments, attributes, statuses, reservation, allocation |
| **ShipGroup** | OrderItemShipGroup | items, shipment, carrier, facility |
| **Shipment** | Shipment | packages → packageContents, routeSegments (tracking), statusHistory, items, origin/dest facility |
| **Picklist** | Picklist | bins → items → orderItem |
| **Return** | ReturnHeader | items, identifications |
| **Payment** | OrderPaymentPreference | (method type, status, amount) |
| **Party / Customer** | Party | contactMechs (addresses/phones/emails by purpose), roles |

### Tier 2 — Fulfillment & supply chain
TransferOrder (items, shipments, receipts), PurchaseOrder (receipts), Facility (locations,
groups, parties/carriers, contactMechs, productStores), ProductStore (facilities,
facilityGroups, shipmentMethods, emailSettings), Product (categories, features,
identifications SKU/UPC, variants), Carrier, GiftCardFulfillment,
InventoryCycleCount/WorkEffort (sessions → items).

### Tier 3 — Inventory / ATP (service-backed, not raw reads)
ATP checks (`checkBopisInventory`, `checkShippingInventory`, `getProductOnlineAtp`),
ProductFacility inventory (QOH/ATP/safety stock — aggregated views), Reservation
(OrderItemShipGrpInvRes). **These are computed/service-backed, validating decision 12.**

### Tier 4 — Analytics / BI (aggregated, time-series — see Decision Q2)
oms-bi facts: OrderItemFulfillmentFact (76 fields, 5+ date dimensions), OrderAdjustmentFact,
ReturnItemFact, InventoryItemDetailFact, OrderFacilityChangeFact, TransferOrderItemFact;
dimensions: Product, Facility, Enumeration, DateDay. Need SUM/COUNT/COUNT-DISTINCT + date-range.

### Tier 5 — Config / reference (low priority)
ProductStore config, OrderRouting (groups/rules/runs), SystemMessage, DataManagerLog,
ServiceJob, SecurityGroup/Permission, Enumeration, Status, UOM, Geo, ProductCategory,
ShipmentMethodType, PaymentMethodType, OrderAdjustmentType.

---

## Part 2 — Capability requirements (what the API must DO)

Each is evidenced by existing usage.

1. **By-id + list + nested traversal, 3-4 levels deep.** Confirmed by `get#SalesOrder`,
   `get#PickupOrders`, and the `WebhookOrderStatus` DataDocument (Order → ShipGroups → Items →
   Reservations → Facility). The graph must nest at least this deep.
2. **Rich filtering:** status, facility, productStore, type/classification, by-identifier,
   multi-value (IN), and **date-range** (entryDate/orderDate/completed/cancelled/approved are
   everywhere). Date-range and status are the most common.
3. **Shopify `query:` string filtering, declare-and-controlled (Q3 + D-A RESOLVED).** Filtering is a
   Shopify-style search string (`query: "status:ORDER_APPROVED created_at:>=2026-05-01"`); only
   declared **search keys** with declared comparators are honored (unknown key / bad comparator →
   rejected). Sorting is `sortKey` enum + `reverse`. Gorjana's `PickProfileCondition` use case maps
   to per-deployment declared search keys (`brandName`, `isGift`).
4. **Relay connections (Q4 RESOLVED — in scope).** Full cursor connections from the start:
   `edges { cursor node }`, `pageInfo { hasNextPage hasPreviousPage startCursor endCursor }`,
   `first`/`after`/`last`/`before`.
5. **Reads are DB-backed (Q1 RESOLVED).** GraphQL product/order queries are **structured DB
   filters** (category, identification/SKU, productType, status), index-aware. Full-text /
   faceted **Solr search stays on the existing endpoints** — not exposed through GraphQL. No
   search-index entry point in scope.
6. **Aggregation / analytics — DEFERRED (Q2 RESOLVED).** BI facts (SUM/COUNT/time-series) are a
   later opportunity, picked up after we have good usage examples from the user group. Not in the
   initial scope.
7. **Computed / service-backed fields.** `itemFulfillmentStatus`, `customerName`, ATP, online
   ATP. Widespread — validates decision 12.
8. **View-entity-backed types are the norm, not the exception.** `ReadyToPickWarehouseOrder`,
   `InflightOrder`, `PicklistItemView`, `ProductFacilityView`, `OrderItemReservation`,
   `OrderItemFulfillmentFact`. Clients model their working data as views. View-entity types are
   a first-class requirement.
9. **Per-deployment / client-specific schema.** Custom fields (`isGift`, `brandName`,
   `netsuiteOrderName`), client-only types (`PickProfile*`, `AdpWorkerHistory`), client-only
   entry points (`readyToPickOrders`). Schema artifacts are authored per deployment with
   per-client field/type visibility. Validates decisions 5 + 11.
10. **External-ID lookup (Q5 RESOLVED — must-have, in scope).** `OrderHeader.externalId`,
    `Facility.externalId`, and `OrderIdentification` (netsuiteOrderName, shopifyOrderId) drive all
    multi-system sync. First-class `order(externalId:)` / `orderByIdentification(identificationTypeId:, idValue:)` entry points +
    an `identifications` edge on core types.
11. **Status history, not just current status.** OrderStatus/ShipmentStatus/OrderItemChange.
    Expose status-history edges.
12. **Purpose-based contact mechs.** Billing/shipping/notification addresses, phones, emails by
    purpose (`FacilityContactDetailByPurpose`, `PartyContactMechDetails`). Per the project
    contactmech pattern — expose via the purpose-joined views, never raw association filtering.
13. **Ready-made nested shapes exist to reuse.** `WebhookOrderStatus`, `WebhookShipmentStatus`,
    `WebhookOrderItem` DataDocuments are pre-curated nested order/shipment shapes — near
    drop-in GraphQL types.

---

## Part 3 — Scope boundaries (in / out)

**IN (read API):** Tiers 1-3 entity/view/service-backed reads; rich filter + sort; pagination;
nested traversal; external-id lookup; status history; computed fields.

**OUT (with rationale):**
- **`unigate`** — RPC/proxy only (rate / label / refund / email). No queryable data, no domain
  objects. Could be *mutations* later; never a read source. **Verdict: out.**
- **Print/export endpoints** (Picklist.pdf, PackingSlip.pdf, Label.pdf, CSV) — document
  generation, not data.
- **Inbound webhooks / integration receivers** (Shopify, ADP) — event-driven, not query.
- **Write/mutation services** (cancel, pack, ship, allocate, receive) — read-only design;
  writes stay on existing services.
- **Live external assembly** (Shopify `get#OrderDetails`) — federation, not DB-backed (decision 12).
- **Raw `run#SolrQuery` pass-through** — the *capability* (search) is in scope; the raw
  arbitrary-Solr endpoint is an anti-pattern we should not reproduce.

---

## Part 4 — Decisions (RESOLVED 2026-06-03)

- **Q1 — All reads are DB-backed.** GraphQL queries/filters hit the database with the
  index-aware cost model. There is **no search-index (Solr/ElasticSearch) entry point** in scope.
  Keyword/full-text product search stays on the existing Solr endpoints; GraphQL product queries
  are **structured DB filters** (by category, identification/SKU, productType, status), not
  full-text. (Simplifies the design — removes the search-vs-DB fork entirely.)
- **Q2 — Analytics is DEFERRED.** `oms-bi` aggregation stays out for now; we pick it up **after
  we have good usage examples from the user group**. No SUM/COUNT/group-by/time-series in the
  initial scope.
- **Q3 — Declare-and-control.** Filtering uses a **Shopify `query:` search string** (decision D-A);
  the declaration controls the **grammar**: only declared **search keys** are accepted, each with a
  declared comparator set (`:` eq, `:a,b` in, `:>`/`:>=`/`:<`/`:<=` for dates/numbers), value
  constraints, required index backing, and `first:` caps. Unknown key / disallowed comparator is
  rejected. Primary control surface; feeds the cost analyzer.
- **Q4 — Relay connections are IN the initial scope.** Full cursor connections —
  `edges { cursor node }`, `pageInfo { hasNextPage hasPreviousPage startCursor endCursor }`,
  `first`/`after`/`last`/`before` — not bare lists.
- **Q5 — External-id lookup is a MUST-HAVE (initial scope).** `order(externalId:)` +
  `orderByIdentification(identificationTypeId:, idValue:)` (our naming), plus an `identifications` edge on
  the core types.

**Shopify alignment — query LANGUAGE only (see `shopify-alignment.md`):** we adopt Shopify's query
*ergonomics* — `query:` string filtering (D-A), `sortKey`+`reverse`, full Relay connections+cursors,
`DateTime`/`Decimal` scalars, `extensions.cost`/error envelope — but **field/type names stay our OMS
data model** (D-D: `orderId`, `orderDate`, `grandTotal`+`currencyUomId`, `orderItems`,
`fulfillmentStatus`, …; no Shopify naming, no `MoneyBag`, no display enums). **Raw entity ids**
(D-B, no `gid://`/`Node`).

---

## Part 5 — Reuse signals (don't rebuild)

- **DataDocuments** (`WebhookOrderStatus`, `WebhookShipmentStatus`, `WebhookOrderItem`,
  `OmsProduct`, `FacilityGroupAndMember`, `ProductStoreShipmentMethod`, …) are pre-curated nested
  shapes → seed the GraphQL type catalog directly (type-B backing).
- **View-entities** (`ReadyToPickWarehouseOrder`, `OrderItemReservation`, `ProductFacilityView`,
  …) → view-entity-backed types (decision 12), reuse the joins.
- **Existing read services** (`get#SalesOrder`, `get#PickupOrders`, `get#BrokeredOrders`,
  `get#BopisInventory`, `get#ProductOnlineAtp`) → service-backed resolvers for computed shapes.
- **BI facts/dimensions** → the aggregate sources for analytics **when we pick it up later** (Q2 deferred).

---

## Headline conclusion

The existing surface confirms the design direction and **validates decision 12 emphatically**
(computed fields, view-entity types, and service-backed resolvers are the norm across the
platform). The five surfaced decisions are now **resolved** (Part 4): **DB-backed only** (no
search index), **analytics deferred**, **declare-and-control filtering**, **Relay connections in
scope**, and **external-id as a must-have**. Net effect: a tighter, fully DB-backed initial scope
with declared/controlled filtering and cursor pagination.
