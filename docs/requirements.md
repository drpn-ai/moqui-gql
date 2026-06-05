# GraphQL API Requirements — Derived from the Existing Maarg Surface

**Date:** 2026-06-03
**Purpose:** Reverse-engineer the requirements for the new GraphQL read API from what Maarg
already exposes. What consumers already pull (published REST, remote services, data feeds,
indexed search, BI facts, client views) *is* the requirement set.
**Method:** Four inventories across the suite — published REST APIs, DataDocuments/feeds/BI,
the `unigate` standalone API + remote services + search, and client components (`gorjana-maarg`,
`notnaked`).
**Feeds:** the main design spec (`2026-06-03-moqui-graphql-query-layer-design.md`).

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
3. **Dynamic, field-driven filter + multi-sort.** Gorjana's `PickProfileCondition`
   (`fieldName` + `operator` + `value` + `fieldType`, FILTER vs SORT_BY) proves clients build
   UIs that *generate* arbitrary filter/sort. The filter input must be expressive
   (operators, multiple fields, ordered sort). **This is the single biggest pull on the cost
   analyzer** — arbitrary filter fields = unindexed-filter risk.
4. **Pagination.** `viewIndex`/`viewSize` used pervasively; cursor (`after`/`pageInfo`)
   needed for stable sync feeds (Shopify connector pattern). Strengthens the case for Relay
   connections in phase 1.
5. **Full-text / faceted search.** Solr/ElasticSearch indexes Products (keyword, category,
   feature, SKU/UPC), Orders (status, fulfillmentStatus), Inventory (facility/ATP). There is a
   `run#SolrQuery` raw pass-through and facet autocomplete. Consumers already depend on search,
   not just SQL filters. **See Decision Q1.**
6. **Aggregation / analytics.** BI facts + DataDocument aggregates (SUM/COUNT/COUNT-DISTINCT:
   productCount, orderCount, QOH totals, fulfillment metrics). **See Decision Q2.**
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
10. **External-ID lookup.** `OrderHeader.externalId`, `Facility.externalId`, and
    `OrderIdentification` (netsuiteOrderName, shopifyOrderId) are used for all multi-system sync.
    Need: query by external id, and expose `identifications` as an edge. **New requirement.**
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

## Part 4 — New decisions the existing surface forces (open, for the spec)

These are NOT yet resolved in the design spec. The existing surface reveals them.

- **Q1 — Search-backed vs DB-backed reads.** Products/Orders are browsed via Solr/ElasticSearch
  (keyword, facets), but our design is DB-entity-backed with index-aware cost. Options: (a)
  GraphQL filters always hit the DB (our cost model applies); (b) add a search-backed entry
  point for indexed types (Product, Order) that resolves against the index; (c) hybrid — search
  for discovery, DB for detail. This is a real architecture fork with cost-governance
  implications.
- **Q2 — Is analytics/aggregation in scope?** BI facts need SUM/COUNT/time-series/rollups. Our
  phase-1 design is row projection, not aggregation. Decide: aggregate fields/queries in scope,
  or analytics stays a separate BI/reporting concern (likely defer, but decide explicitly).
- **Q3 — How expressive is the filter input?** Gorjana's condition model wants
  field+operator+value+sort. More operators = more unindexed-filter surface = more load on the
  cost analyzer. Decide the operator set and whether arbitrary fields are filterable or only
  declared-filterable ones (the spec already leans to declared-filterable — confirm against this
  pressure).
- **Q4 — Relay connections in phase 1?** Feeds + view-index pagination + the Shopify cursor
  pattern all argue for cursor connections now rather than phase 2. Revisit the earlier deferral.
- **Q5 — External-id as a first-class lookup.** Add `byExternalId` entry points and an
  `identifications` edge to the core types.

---

## Part 5 — Reuse signals (don't rebuild)

- **DataDocuments** (`WebhookOrderStatus`, `WebhookShipmentStatus`, `WebhookOrderItem`,
  `OmsProduct`, `FacilityGroupAndMember`, `ProductStoreShipmentMethod`, …) are pre-curated nested
  shapes → seed the GraphQL type catalog directly (type-B backing).
- **View-entities** (`ReadyToPickWarehouseOrder`, `OrderItemReservation`, `ProductFacilityView`,
  …) → view-entity-backed types (decision 12), reuse the joins.
- **Existing read services** (`get#SalesOrder`, `get#PickupOrders`, `get#BrokeredOrders`,
  `get#BopisInventory`, `get#ProductOnlineAtp`) → service-backed resolvers for computed shapes.
- **BI facts/dimensions** → if analytics is in scope (Q2), these are the aggregate sources.

---

## Headline conclusion

The existing surface confirms the design direction and **validates decision 12 emphatically**
(computed fields, view-entity types, and service-backed resolvers are the norm across the
platform). It also surfaces **five new decisions** (Part 4) the spec must resolve — most
importantly **search-vs-DB (Q1)** and **analytics scope (Q2)** — and **two concrete new
requirements**: external-id lookup (Q5) and expressive dynamic filtering (Q3), the latter being
the dominant new pressure on the cost analyzer.
