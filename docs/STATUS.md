# moqui-gql — Status

A curated, **read-only GraphQL layer** over the HotWax/Maarg OMS data model, modeled on Shopify's
Admin API (query language + `extensions.cost`), fronted by a **cost governor** so internal apps, AI
agents, and partners can fetch nested OMS data without harming the system.

**Status:** Phases 1, 1.5, and 2 complete and merged to `main`. **70 tests pass** across 25 classes,
all run against the live MySQL `hcsd_notnaked` database (real OMS data, not fixtures). The only
outstanding item is a framework dependency (see below).

Last updated: 2026-06-06.

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

---

## Capabilities

| Area | Detail |
|---|---|
| Query language | Shopify-style `query:` search (declared keys + comparators), `sortKey`+`reverse`, Relay connections — over our OMS field names |
| Pagination | Keyset cursors, forward + backward, single- and composite-PK; no OFFSET (deep pages stay flat-cost) |
| Resolvers (decision 12) | Entity-backed, view-entity-backed (`parties`), service-backed fields (`Order.customerName`) and roots (`inventoryLevels`) |
| Batching | DataLoader — one `WHERE fk IN(:keys)` per level (no N+1) for connections, plain lists, and service calls |
| External-id | `order(externalId:)`, `order.identifications`, `orderByIdentification`, `facility(externalId:)` |
| Governor | Pre-execution gate (nothing hits the DB on reject) with stable `extensions.code`; runtime `queryTimeout` + per-level row caps + wall-clock deadline |
| Throttle | Live per-caller token bucket → `THROTTLED`; live `throttleStatus` |
| Caller policies | `GqlCallerProfile` overrides limits per caller; activates the `ScopeFilter` row-scope seam |
| Endpoint + observability | `POST /rest/graphql`, `GET /rest/graphql/sdl`; one `GqlQueryLog` row per request (verdict/cost/rows/duration) |

### Stable error codes (governor, pre-execution)
`DEPTH_EXCEEDED` · `COST_EXCEEDED` · `FIRST_REQUIRED` · `FIRST_TOO_LARGE` · `FIELD_NOT_FILTERABLE` ·
`OPERATOR_NOT_ALLOWED` · `BATCH_LIMIT_EXCEEDED` · `THROTTLED` · `DEADLINE_EXCEEDED` (runtime backstop)

### Schema surface
Order, OrderItem, OrderStatus, OrderAdjustment, OrderPaymentPreference, ShipGroup, OrderIdentification,
Party (view), Shipment, Return, Product, Facility, InventoryLevel.

---

## Tests — 70 across 25 classes (vs `hcsd_notnaked`)

| Group | Classes (count) |
|---|---|
| Engine / execution | `GqlEngineTests` (8) — by-pk, fwd/back pagination matched to DB keyset order, `query:` filtering, nested batch grouping |
| Governor / adversarial | `GovernorTests` (9) — N1–N6, Q3b, inventory cap, wall-clock — each rejected pre-execution with the exact code + `data:null` |
| Connection walks | `ConnectionWalkTests` (4) — page sizes 1/3/7 same ordered set, no overlap/skip; composite-PK never repeats |
| Resolver kinds | `PartyConnectionTests` (4), `ServiceBackedTests` (2), `InventoryLevelsTests` (1), `OrderDetailEdgesTests` (1), `ProductFacilityTests` (2) |
| External-id | `ExternalIdTests` (4) |
| Endpoint + observability | `EndpointTests` (4) — service, cost shape, ALLOWED + REJECTED logging |
| Phase 2 | `ThrottleGateTests` (1), `ThrottleE2ETests` (1), `CallerProfileTests` (2) |
| Scope seam | `ScopeSeamTests` (3) — invoked per find, restricts rows, batch cardinality (no N+1) |
| Catalog contract | `CatalogContractTests` (6) — A2/E/J/L + shipments-with-data + returns |
| Unit / scaffold | Scalars, SchemaArtifactParser, SearchQueryParser (4), CostModel, IndexClassifier, GqlSchemaBuilder, GqlToolFactory, QueryTimeout (2), ScaffoldSmoke (3), ShipmentRoot (3) |

Run: `./gradlew :runtime:component:moqui-gql:test`

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
