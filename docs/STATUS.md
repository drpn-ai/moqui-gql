# moqui-gql — Status

A curated, **read-only GraphQL layer** over the HotWax/Maarg OMS data model, modeled on Shopify's
Admin API (query language + `extensions.cost`), fronted by a **cost governor** so internal apps, AI
agents, and partners can fetch nested OMS data without harming the system.

**Status:** Phases 1, 1.5, 2, 3, epic #46, and query-log v2 complete. **104 tests pass (3 skipped)** across
33 classes, all run against the live MySQL `hcsd_notnaked` database (real OMS data, not fixtures). The
only outstanding item is a framework dependency (see below).

Last updated: 2026-06-10.

---

## Phases delivered

### Phase 1 — core engine (epic #1, PRs #8–#13)
| Child | Delivered |
|---|---|
| C1 (#2) | Component scaffold + `EntityFind.queryTimeout` framework coverage |
| C2 (#3) | Schema layer: scalars, artifact parser, index classifier, cost model, schema builder, tool factory |
| C3 (#4) | Executor: Relay keyset cursors (forward/backward/composite-PK), `query:` search, DataLoader batching, entity/view/service resolvers, external-id trio, new roots |
| C4 (#5) | Pre-execution query governor + runtime guards |
| C5 (#6) | `/graphql` endpoint, `extensions.cost`, `GqlQueryLog` observability |
| C6 (#7) | Shipment data generator, scope seam, catalog/walk/scope test suites |

### Phase 1.5 — schema breadth (epic #14, PRs #17–#18)
Purely declarative `*.gql.xml` additions — **zero engine changes**.
- Order detail edges: `shipGroups` (connection) + `statuses`/`adjustments`/`paymentPreferences` (plain lists)
- `Product` (`product`/`products`) and `Facility` (`facility` w/ externalId, `facilities`)

### Phase 2 — live runtime policy (epic #19, PRs #22–#23)
- **Live cost throttle**: per-caller token bucket (cost-debited, time-refilled); over-budget → `THROTTLED`; `extensions.cost.throttleStatus` reports the real bucket
- **Caller profiles + scope activation**: `GqlCallerProfile` overrides per-caller limits and activates the (ThreadLocal) `ScopeFilter` seam for row-scope

### Phase 3 — query cache + service-field audit (epic #26, PRs #29–#32)
- **Prepared-statement document cache**: `CachingPreparsedDocumentProvider` caches the parsed + validated GraphQL `Document` per query string — "PREPARE once, EXECUTE many" (the JDBC prepared-statement analogue)
- **`customerName` → `billToCustomer`**: the service-backed `customerName` became a view-backed has-one leaf edge (`{partyId, firstName, lastName}`); the **leaf-over-service rule** is codified in `design.md` decision 12
- **Runtime deploy + HTTP verification**: a `deployToLib` task builds the component jar; `POST /rest/s1/graphql` exercised end-to-end over real HTTP (billToCustomer, the prepared-statement cache, and the live throttle bucket)

### Aggregate-field kind (#37)
- **`Order.itemCount` → `Order.orderItemCount`**: the service-backed item count became a new **aggregate-field** kind — a lazy SQL aggregate (`COUNT(DISTINCT OrderItem.externalId)` = number of distinct Shopify order lines) added to the order query as a `sub-select` member (a **LATERAL** subquery on mysql8) **only when the field is selected**. No schema-builder/fetcher change: the value rides in as a column. Resolved on the `orders` list and `order(orderId:)` roots; charged `gql.aggregateFieldCost` by the governor.
- `get#OrderItemCount` retired; the service-backed-field capability is retained (engine + governor branch) and covered by `ServiceBackedLoaderTests`. v1 scope: `orderByIdentification` and nested-`Order` nodes return `orderItemCount: null`. mysql8/LATERAL-dependent.

---

## Capabilities

| Area | Detail |
|---|---|
| Query language | Shopify-style `query:` search (declared keys + comparators), `sortKey`+`reverse`, Relay connections — over our OMS field names |
| Pagination | Keyset cursors, forward + backward, single- and composite-PK; no OFFSET (deep pages stay flat-cost) |
| Resolvers (decision 12) | Entity-backed, view-entity-backed (`parties`, `inventoryLevels`), **aggregate fields** (`Order.orderItemCount` — lazy LATERAL `COUNT(DISTINCT externalId)`, added to the query only when selected), view-backed has-one leaf edges (`Order.billToCustomer`). The service-backed **field** and **root** kinds are retained (engine + governor branch) but currently have **no schema user**: the last service-backed field went with `Order.itemCount`→`orderItemCount` (#37), and the last service-backed root went with `inventoryLevels`→view-backed connection (#35). The service-backed-field capability is covered by `ServiceBackedLoaderTests` |
| Batching | DataLoader — one query per level (no N+1) for connections, plain lists, and service calls. Nested has-many edges batch over **composite (multi-field) parent keys** (#38): single fk field → `WHERE fk IN(:keys)`, composite key → `OR`-of-`AND`s grouped by key tuple. `ShipGroup.orderItems` batches per `(orderId, shipGroupSeqId)`; `order.shipGroups` excludes empty groups via one extra batched `DISTINCT` query |
| External-id | `order(externalId:)`, `order.identifications`, `orderByIdentification`, `facility(externalId:)` |
| Governor | Pre-execution gate (nothing hits the DB on reject) with stable `extensions.code`; runtime `queryTimeout` + per-level row caps + wall-clock deadline |
| Throttle | Live per-caller token bucket → `THROTTLED`; live `throttleStatus` |
| Caller policies | `GqlCallerProfile` overrides limits per caller; activates the `ScopeFilter` row-scope seam |
| Query cache | Prepared-statement document cache (`CachingPreparsedDocumentProvider`) — parsed + validated `Document` cached per query string (JDBC prepared-statement analogue) |
| Endpoint + observability | `POST /rest/s1/graphql`, `GET /rest/s1/graphql/sdl`; query-log v2 (below) |
| Query-log v2 | Raw `GqlQueryLog` rows only for: every REJECTED query; ALLOWED queries slow for their shape (`QueryStats`: avg + 2.6 sigma after warm-up, over `slowMinMillis` — the framework slow-hit math); a `sampleRate` random sample. Every ALLOWED hit aggregates into per-shape `GqlQueryStatsBin` rows (the cost-calibration dataset: avg duration vs avg estimated cost per `queryHash`). Resolved caller profile recorded. Daily `purge_GqlQueryLog` ServiceJob enforces retention. |

### Stable error codes (governor, pre-execution)
`DEPTH_EXCEEDED` · `COST_EXCEEDED` · `FIRST_REQUIRED` · `FIRST_TOO_LARGE` · `FIELD_NOT_FILTERABLE` ·
`OPERATOR_NOT_ALLOWED` · `BATCH_LIMIT_EXCEEDED` · `THROTTLED` · `DEADLINE_EXCEEDED` (runtime backstop)

### Schema surface
Order, OrderItem, OrderStatus, OrderAdjustment, OrderPaymentPreference, ShipGroup, OrderIdentification,
BillToCustomer (view), Party (view), Shipment, Return, Product, Facility, InventoryLevel (view —
`ProductFacilityInventoryItemView`; the `inventoryLevels` connection root).
Plus the #43 ShipGroup detail types: `FacilityOriginAddress` (view), `ShipmentMethodType`, `OrderFacilityChange`.
Nested edges include `ShipGroup.orderItems` (#38) — items of a ship group, grouped per `(orderId, shipGroupSeqId)` —
and the #43 `ShipGroup` detail edges:
- `ShipGroup.shipFromAddress` — origin (facility) postal address incl. `latitude`/`longitude`. **Single-key has-one** (the `billToCustomer` path) keyed by `facilityId`, backed by the gql-owned view `moqui.gql.FacilityOriginAddress` (purpose `SHIP_ORIG_LOCATION`, lat/long from the joined `GeoPoint`). Works today — no engine change.
- `ShipGroup.shippingMethod` — descriptive method (`ShipmentMethodType` `shipmentMethodTypeId` + `description`). **Single-key has-one** keyed by `shipmentMethodTypeId`; carrier identity stays on the `carrierPartyId` scalar (the composite carrier edge was closed not-planned). Works today.
- `ShipGroup.facilityChangeHistory` — facility-routing audit trail. **Composite-key has-many** batched per `(orderId, shipGroupSeqId)` via the gql-owned `OrderItemShipGroup.facilityChanges` `extend-entity` relationship; rode #38's composite `NestedConnectionLoader` (declarative-only — relationship + edge + type, no engine change). Ordered by the child PK `orderFacilityChangeId` (assigned in change-time order). Only reachable on ship groups that survive `order.shipGroups` exclude-empty.

---

## Tests — 95 across 31 classes (92 pass, 3 skipped; vs `hcsd_notnaked`)

| Group | Classes (count) |
|---|---|
| Engine / execution | `GqlEngineTests` (8) — by-pk, fwd/back pagination matched to DB keyset order, `query:` filtering, nested batch grouping |
| Governor / adversarial | `GovernorTests` (8) — N1–N5, Q3b, aggregate-field cost, wall-clock — each rejected (or charged) pre-execution with the exact code + `data:null`. (`BATCH_LIMIT_EXCEEDED` has no governor-test driver after the inventory-cap retirement (#35); see the test's note.) |
| Connection walks | `ConnectionWalkTests` (4) — page sizes 1/3/7 same ordered set, no overlap/skip; composite-PK never repeats |
| Composite-key batching (#38) | `ShipGroupItemsTests` (4) — `order → shipGroups → orderItems` batched per `(orderId, shipGroupSeqId)`, items match DB ground truth (no cross-order/cross-group leakage); `order.shipGroups` excludes empty groups (data-exercised) |
| ShipGroup detail edges (#43) | `ShipGroupDetailEdgesTests` (4; 1 skipped) — `shipFromAddress` (+lat/long, single-key has-one), `shippingMethod` (single-key has-one, data-exercised: 473 ship groups), `facilityChangeHistory` (composite-key has-many, wiring-verified — no change-bearing ship group has items in `hcsd_notnaked`, so all are dropped by exclude-empty; the ground-truth test skips until such data exists) |
| Resolver kinds | `PartyConnectionTests` (4), `ServiceBackedLoaderTests` (2), `InventoryLevelsTests` (5) — view-backed connection: 0-not-null totals, productId/facilityId filters (data-skipped if `ProductFacility` empty), composite-PK keyset, `FIRST_REQUIRED` — `OrderDetailEdgesTests` (1), `ProductFacilityTests` (2) |
| Aggregate field (#37) | `OrderItemCountTests` (3) — `Order.orderItemCount` lazy LATERAL `COUNT(DISTINCT externalId)` added to the query only when selected; resolved on `orders` + `order(orderId:)` roots, charged `gql.aggregateFieldCost` |
| External-id | `ExternalIdTests` (4) |
| Endpoint + observability | `EndpointTests` (4) — service, cost shape, ALLOWED + REJECTED logging |
| Phase 2 | `ThrottleGateTests` (1), `ThrottleE2ETests` (1), `CallerProfileTests` (2) |
| Phase 3 | `PreparsedCacheTests` (3), `BillToCustomerTests` (2) |
| Scope seam | `ScopeSeamTests` (3) — invoked per find, restricts rows, batch cardinality (no N+1) |
| Catalog contract | `CatalogContractTests` (6) — A2/E/J/L + shipments-with-data + returns |
| Schema contract (#49) | `SchemaContractTests` (4) — built schema ⊆ `docs/schema.graphql`; the SDL is enforced as a superset of the built schema |
| Unit / scaffold | Scalars, SchemaArtifactParser, SearchQueryParser (4), CostModel, IndexClassifier, GqlSchemaBuilder, GqlToolFactory, QueryTimeout (2), ScaffoldSmoke (3), ShipmentRoot (3) |

Run: `./gradlew :runtime:component:moqui-gql:test`

- **Schema contract**: `docs/schema.graphql` is enforced as a superset of the built schema by `SchemaContractTests` (built ⊆ contract). Schema changes must update the SDL in the same PR.

---

## Notes & engineering calls
- **Throttle is disabled in the test JVM** (`build.gradle` huge bucket) so the shared anonymous caller
  can't deplete a bucket across the suite and spuriously throttle unrelated tests; the throttle e2e
  test enables a small bucket locally with save/restore. Production keeps the real default.
- **`ScopeFilters` is ThreadLocal** — per-request, thread-safe row scoping (one request = one thread).
- **Unindexed declared filters** apply a **cost penalty** (`unindexedFilterPenalty`), not a hard reject:
  our schema intentionally declares common-but-unindexed keys (`statusId`, `orderDate`).
- Reads use the replica clone (`useClone`) inside a single read-only transaction (`runUseOrBegin`).

## Outstanding / future
- **Framework PR #45** (`EntityFind.queryTimeout`, hotwax/moqui-framework, assigned to @dixitdeepak) —
  not merged by this work; epic #1 stays open until it lands. The component compiles against that branch.
- Future: cross-node throttle fairness (cache is per-node), profile admin API, scope filters beyond
  productStore, remaining catalog types (Picklist, etc. — declarative when needed), analytics/aggregation,
  search-index entry point.
