# Maarg GraphQL Query Layer with Cost Governance — Design

**Date:** 2026-06-03
**Status:** Design approved; pending implementation plan
**Author:** Anil K Patel (with Claude)

## Problem

We want to give consumers the flexibility to fetch exactly the data they need
via complex, nested, custom queries — the way Shopify's GraphQL API works for
our integrations — **without** exposing ourselves to queries that can bring the
system down. A consumer can (intentionally or not) build a query that spans many
entities, joins that grow the result set exponentially, filters on unindexed
fields, and traverses expensive relationships. We need a layer that gives this
flexibility *and* a query analyzer that evaluates the cost of execution before
running it, and controls what is allowed to run.

## Consumers (in priority order)

1. **Internal HotWax apps/teams** — immediate need. Want query flexibility
   instead of one-off REST endpoints. Semi-trusted: mistakes happen, not malice.
2. **AI agents** — soon. An agent builds a novel query on every goal,
   autonomously, with no intuition for "this join will explode." This is in some
   ways *more* dangerous than an external partner: a human ships a fixed,
   tested query shape; an agent generates a new one each time. **The cost
   governor must therefore be the spine of the design, not a later bolt-on.**
3. **External partners** — later (anticipated). Adversarial: assume someone
   *will* write the pathological query. Drives persisted/allow-listed queries.

One caller-aware policy engine, tightened per audience.

## Key foundation findings (existing Moqui)

- **DataDocument** already does most of the hard work: takes a primary entity +
  relationship paths, builds a *dynamic view-entity* (one big SQL join), runs it,
  then merges flat rows into nested JSON in memory. Supports field projection,
  pre-query (WHERE) and post-query conditions, expression fields, and aliasing.
  - Entities: `framework/entity/EntityEntities.xml` (DataDocument,
    DataDocumentField, DataDocumentCondition, DataDocumentRelAlias).
  - Materialization: `framework/src/main/groovy/org/moqui/impl/entity/EntityDataDocument.groovy`.
- **`EntityDynamicViewImpl`** lets us build joins programmatically at runtime
  (`framework/src/main/groovy/org/moqui/impl/entity/EntityDynamicViewImpl.groovy`).
- **No runtime cost guardrails exist today.** `EntityFindBase` has
  `limit`/`offset`/`maxRows`/`fetchSize`, but `maxRows` isn't wired to JDBC, and
  **`setQueryTimeout` appears zero times in the codebase**. Query timeout must be
  added.
- **No "Report Builder"** beyond DataDocument and its tools-app edit screens.

## Decisions

| # | Decision | Choice |
|---|----------|--------|
| 1 | Trust model | Build for untrusted from day one (AI agents drive this), even though phase 1 is internal. |
| 2 | Schema model | **Curated graph (Shopify-style).** Only declared types/edges are reachable. The schema is the security boundary. |
| 3 | Query language | **Real GraphQL** (e.g. `graphql-java`), `/graphql` endpoint + published SDL + introspection. Agents already know GraphQL; partners get standard tooling. Aids market acceptance. |
| 4 | Governance | **Defense-in-depth, layers 1–4 in phase 1, design for 5.** |
| 5 | Schema definition | **A (dedicated XML schema layer) + B (DataDocument-backed types as an option).** Cost/index metadata auto-derived from entity metadata, hand-tunable. |
| 6 | Rejections | **Agent-actionable structured errors** so an agent can self-correct and retry. |
| 7 | Mutations | **Out of scope.** Read-only. Writes go through normal Moqui services. |
| 8 | Build vs reuse | **Reuse `graphql-java` built-ins.** Depth/complexity limiting via `MaxQueryDepthInstrumentation` / `MaxQueryComplexityInstrumentation` (+ a custom `FieldComplexityCalculator` carrying our cost model); batching via `DataLoader`; phase-2 allow-lists via `PersistedQuerySupport`. We own only the schema-generation layer, the entity-metadata-derived cost/index model, runtime guards, and Moqui execution. |
| 9 | Load isolation | **Dedicated, capped connection pool on a read replica** for all GraphQL reads (phase 1, first-class). Expensive/runaway queries can only starve their own bounded pool; the transactional OMS workload keeps its connections. |
| 10 | Execution model | **One request = one thread = one read-only transaction.** `DataLoader` dispatches batches synchronously on the calling thread. Preserves Moqui's thread-bound `ExecutionContext` + transaction binding and gives a consistent read snapshot across the N batched levels. |
| 11 | Authorization | **Cross-client isolation is a deployment property — one database per client, no shared multi-tenant DB — so no query-level cross-client scoping is needed.** Within a client's DB: (a) per-caller schema visibility driven by the policy profile (phase 2); (b) optional **party/role row-scoping** for external/partner callers who must see only their own slice (e.g. a supplier's own POs) — phase 2, tied to the partner audience. Phase 1 (internal, trusted) needs neither. The executor still exposes a **scope-filter seam** so party-scoping can be added in phase 2 without an invasive retrofit. |
| 12 | Computed/assembled data | **Three field kinds: entity-backed, view-entity-backed, and service-backed resolvers.** Validated against real notnaked Order services (below): most needed data is raw fields + relationships, but key fields (`itemFulfillmentStatus`, `customerName`, `orderItemCompletedDatetime`) are *computed* and must reuse existing Moqui services. A field/edge may declare `resolver-service`; types may map to **view-entities** (free joins). Live external assembly (e.g. Shopify GraphQL calls) stays **out** — that is federation, not a DB-backed graph. |

### Load isolation (decision 9) — the primary blast-radius control

Per-query runtime guards stop *one* query from running too long. They do **not**
stop *aggregate* load (many expensive queries, or an agent retry loop) from
exhausting the connection pool. If GraphQL shares the OMS pool, the query API
stays up but **order capture / allocation / fulfillment lose their connections**.
That is the real catastrophic failure mode behind "bring down the system."

Mitigation: route all GraphQL reads through a **separate, capped connection pool
pointed at a read replica** (a dedicated Moqui datasource). Connection exhaustion
and heavy read CPU/IO are then contained to that pool and the replica; the primary
and the transactional workload are insulated. This is a phase-1 requirement, not
an optimization.

### Execution model (decision 10)

Each GraphQL request executes on the **calling thread, inside a single read-only
transaction**, with `DataLoader` configured for **synchronous same-thread
dispatch**. Rationale:

- Moqui's `ExecutionContext` (user, auth, tenant) and the JDBC connection are
  **thread-bound**. `graphql-java`/`DataLoader` async dispatch would resolve
  batches on other threads, silently breaking EC + transaction binding under load.
- The executor issues **N queries** (one per edge level). A single read-only
  transaction gives a **consistent snapshot** across all N, avoiding
  half-consistent documents (e.g. an order whose line items were deleted between
  levels).

Trade: we give up fetcher-level parallelism within a request. For a read API on a
bounded pool that is the correct trade.

### Authorization model (decision 11)

**Cross-client isolation is handled by deployment, not by this layer.** Each
client runs its own Maarg instance with its own database — there is no shared
multi-tenant database, so one client's query physically cannot reach another
client's data. No cross-client query scoping is required or built.

The curated graph controls **reachability** (an undeclared edge does not exist).
Within a single client's DB, two authorization concerns remain, both **phase 2**:

- **Field/type visibility** — the policy profile determines which types/fields/
  edges a given caller sees. Phase 1: one internal profile (full schema). Phase 2:
  partner/agent profiles with reduced visibility (Shopify scopes by token scope).
- **Party/role row-scoping** — when an *external/partner* caller must see only
  their own slice within the client's DB (e.g. a supplier's own POs, a seller's
  own orders), the executor injects a party-scope predicate. Phase 1 (internal,
  trusted) does not need this. To avoid an invasive retrofit, the executor exposes
  a **scope-filter seam** in phase 1 that phase 2 populates.

### Computed & service-assembled data (decision 12)

Validated against the real notnaked OMS (`notnaked/runtime/component`). Order data
consumers need falls into three categories:

- **(A) Entity-backed** — raw `OrderHeader`/`OrderItem`/extended fields + relationship
  traversal. `oms/entity/OrderExtendedEntities.xml` declares the exact edges we expose
  (`shipGroups`, `paymentPreferences`, `adjustments`, `statuses`, `items`, `returns`,
  `contactMechs`, …); its `<master>` blocks are already a curated nested shape. Fully
  covered by the entity-backed field/edge model.
- **(B) Computed** — fields derived by Groovy logic over multiple entities. Real
  examples: `itemFulfillmentStatus` (a state machine over approved/completed/cancelled
  counts + returns, `ofbiz-oms-usl/.../OrderServices.xml:2861-2993`), `customerName`
  (party→Person concat), `orderItemCompletedDatetime` (filtered status lookup). Also
  heavy **view-entity** joins (`OrderItemDetail`, `ReturnItemView`).
- **(C) Externally assembled** — live calls to Shopify/NetSuite (`ShopifyOrderServices.get#OrderDetails`).

Design response — **three field kinds in the schema layer:**

1. **Entity-backed field/edge** — the default (decisions 2/5). Statically cost-analyzable.
2. **View-entity-backed type** — a `<gql-type>` maps to a Moqui view-entity; `EntityFind`
   runs on view-entities transparently, so we reuse existing joins (`OrderItemDetail`
   etc.) instead of rebuilding them. Cost: a view type carries a higher base cost and its
   filterable fields' index status is derived from the underlying member entities.
3. **Service-backed resolver** — a field/edge declares `resolver-service="..."`; the
   resolver calls an existing Moqui service (reusing `itemFulfillmentStatus` logic rather
   than reimplementing it), mapping parent keys to inputs.

**Governance carve-out — service-backed fields are an analyzer blind spot.** A service
resolver can do arbitrary expensive work the static cost model cannot see. Therefore:

- They carry a **high fixed cost** in the estimate (they cannot be cheap).
- They rely on the **runtime guards** (query timeout, wall-clock budget) as the real bound.
- Inside a list, they must be **`DataLoader`-batched** (resolver-service accepts a key list
  and runs once per level) or **barred** when no batch form exists — never N individual
  service calls across a large list.

**(C) is explicitly out of scope** — live external enrichment belongs to a federation
layer, not this DB-backed graph. A consumer needing live Shopify data calls the existing
Shopify services directly.

### Governance layers (decision 4)

1. **Static structural limits** (pre-plan): max depth, max field count, max
   list-edges; every list edge *must* carry `first:` ≤ cap. — phase 1
2. **Static cost estimation** (pre-execution gate): walk the parsed query against
   the schema's per-edge cost metadata, multiply out fan-out, sum a cost score,
   reject if over budget. The core "query analyzer." — phase 1
3. **Index-awareness in the estimate**: every filterable/sortable field is
   classified indexed/unindexed at schema-build time; unindexed filters/sorts
   multiply cost or are rejected. — phase 1
4. **Runtime enforcement**: JDBC query timeout (new framework hook), hard row cap
   that aborts mid-fetch, per-request wall-clock budget. The net for queries the
   estimator under-guessed. — phase 1
5. **Per-caller rate budget** (leaky bucket, Shopify-style): each query's
   *measured* cost debits a replenishing per-caller bucket. — phase 2 (cost score
   designed as a real number from day one so the bucket can meter it later)

## Architecture

### Where it lives

- A **new component `moqui-gql`** (this repo): endpoint, schema registry, analyzer,
  executor, policy, observability.
- A **small, upstream-able framework addition**: `EntityFind.queryTimeout(seconds)`
  wired to `PreparedStatement.setQueryTimeout()`. Runtime enforcement is
  impossible without it. (Possibly also wire a hard row-cap abort.)
- A **dedicated read-only datasource** (separate connection pool, read replica) for
  GraphQL execution — see Load isolation (decision 9).

### The seven units (each independently testable)

1. **Schema Registry** — reads `graphql/*.gql.xml` artifacts declaring
   types↔entities, fields↔entity-fields, edges↔relationships. **Three field kinds
   (decision 12):** entity-backed (default), view-entity-backed (a type maps to a
   Moqui view-entity for free joins), and service-backed (`resolver-service="..."`
   delegates to an existing Moqui service). At startup builds
   in lockstep: (a) the `graphql-java` schema (SDL + introspection), and (b) a
   parallel **Cost Model** (service-backed fields seeded with a high fixed cost —
   they are opaque to static analysis). **Index coverage auto-derived** from Moqui entity
   index/PK definitions; **edge cardinality auto-derived** from relationship type;
   both **hand-overridable** in XML. Type B: a DataDocument can back a heavy
   hand-tuned type. **Caching (decision 6 / perf):** the `GraphQLSchema`, cost
   model, and per-type dynamic-view *definitions* are built **once at startup and
   cached process-wide** — never rebuilt per request. Parsing MNode →
   `EntityDefinition` on every request would be a hot-path cost under agent load.
   The view *shapes* are immutable; only the per-request bind values vary.
2. **`/graphql` endpoint** — accepts query + variables, authenticates the Moqui
   user, resolves caller → policy profile.
3. **Query Analyzer (the gate)** — built on `graphql-java` instrumentation
   (decision 8), not a hand-rolled AST walker. Structural limits (depth, field
   count, list-edge count, mandatory `first:` caps) use
   `MaxQueryDepthInstrumentation` + `MaxQueryComplexityInstrumentation`; our
   domain cost/index knowledge plugs in via a custom `FieldComplexityCalculator`.
   The combined gate behaves as a function
   `(parsed query, cost model, caller policy) → ALLOW | REJECT(reasons[])`.
   Rejections are structured and agent-actionable, e.g. `depth 8 exceeds max 6`,
   `edge 'returnItems' requires first: (≤100)`, `filter field 'shipBefore' is not indexed`.
4. **Planner / Executor** — translates the validated selection into execution via
   `DataLoader` (decision 8). **Critical divergence from DataDocument:** for **list
   edges** use **batched per-level resolution** (resolve parent IDs, then one
   `DataLoader` batch per child edge with `WHERE parentKey IN (…)`), **not** one
   mega-join. The single mega-join is exactly the cartesian-explosion vector we
   must avoid; batching keeps each level's row count bounded and predictable.
   To-one edges still fold into the join. Reuses `EntityDynamicViewImpl` for
   per-level dynamic-view construction; **result assembly is `DataLoader`'s job,
   not `EntityDataDocument.mergeValueToDocMap` — we deliberately do not reuse
   DataDocument's single-join flat-row merge**, since that is the model we are
   moving away from. Runs on one thread in one read-only transaction (decision 10).
   Exposes a **scope-filter seam** (decision 11) — unused in phase 1, populated for
   party-scoped partner callers in phase 2. (No cross-client scoping: one DB per
   client.)
5. **Runtime Guard** — wraps every execution step: JDBC query timeout, hard row
   cap that aborts mid-fetch, per-request wall-clock budget. Records actual cost.
6. **Policy & Metering** — caller → profile (limits + budget). Phase 1: static
   profiles in config. Phase 2: leaky-bucket rate budget debited by measured cost.
7. **Observability** — every query logs estimated cost, actual cost, rows, time,
   verdict. Drives threshold tuning and reveals which agent queries get rejected
   and why.

### Data flow

```
request → auth → parse (graphql-java)
   → ANALYZER GATE [structural → cost → budget]
        ├─ reject → structured, agent-actionable errors
        └─ allow → PLANNER → batched EXECUTOR (dynamic views / DataDocument)
                       wrapped in RUNTIME GUARD [timeout · row-cap · wall-clock]
   → assemble nested JSON → record actual cost
   → response (+ extensions.cost, Shopify-shaped — see below)
```

### Response cost format — speak Shopify's dialect (validated against the connector)

The existing `mantle-shopify-connector` (a GraphQL *client* on `graphql-java:25.0`) consumes
Shopify's cost block: `extensions.cost.{requestedQueryCost, actualQueryCost,
throttleStatus.{maximumAvailable, currentlyAvailable, restoreRate}}`. Our *server* emits the
**same shape**, so any consumer already integrated with Shopify GraphQL understands our
throttling for free, and the phase-2 leaky bucket maps 1:1:

```json
"extensions": { "cost": {
  "requestedQueryCost": 412,        // our static estimate (phase 1)
  "actualQueryCost": 388,           // measured after execution (phase 1)
  "throttleStatus": {               // budget view (static in phase 1; live bucket in phase 2)
    "maximumAvailable": 1000, "currentlyAvailable": 612, "restoreRate": 50 } } }
```

Cautionary note from the connector: it *reads* `currentlyAvailable` and does nothing with it
(`//TODO` for backoff). Our governor must **act** on the number, not just log it.

### Two defenses worth calling out

- **Estimate *and* enforce.** Gate on the static estimate *and* cap at runtime.
  Neither alone is trustworthy; together they cover predictable bombs and surprises.
- **Persisted/allow-listed queries (phase 2, for partners).** Partners *register*
  queries we analyze and approve once, then call by ID — making the adversarial
  audience safe without trusting their query-writing. The analyzer being a pure,
  reusable function gives us this nearly for free.

## Testing strategy

- **Analyzer:** pure unit tests — a table of `(query → verdict)`. TDD from day one.
- **Adversarial catalog:** explicit suite of pathological queries (deep nesting,
  many-to-many bomb, unindexed filter, oversized `first:`) — each asserted
  *rejected*. Doubles as the regression guard.
- **Executor:** integration tests against seed data — nesting correctness, cursor
  pagination, filter push-down.
- **Cost calibration:** representative queries asserting estimated cost tracks
  actual rows/time within a tolerance band.
- **Authorization (from review Issue 3) — phase 2:** cross-client isolation is a
  deployment property (one DB per client), so there is no phase-1 row-scope test.
  Phase 2: party-scope predicate is applied for partner callers; caller profile
  hides non-visible types/fields.
- **Execution / EC binding (from review Issue 2):**
  - **CRITICAL:** the full request runs in one read-only transaction; the
    `DataLoader` batch resolves on the same thread with the EC intact.
  - Consistent snapshot across the N batched levels.
- **Load isolation (from review Issue 1):**
  - **CRITICAL [→E2E]:** an expensive/runaway query exhausts only the GraphQL pool;
    the OMS pool is unaffected under concurrent load.
- **Runtime guards:** `queryTimeout` aborts a slow query (also tests the framework
  patch); row cap aborts mid-fetch; wall-clock budget aborts a deep request.
- **Computed/service-backed fields (decision 12):** a service-backed field returns its
  service's computed value (e.g. `itemFulfillmentStatus`); it carries a high fixed cost
  in the estimate; inside a list it batches (one service call per level) rather than N
  calls; a view-entity-backed type returns joined fields and is filter-cost-classified
  from its member entities.

## Resolved scope decisions (Q1–Q5, 2026-06-03)

Settled after reverse-engineering the existing Maarg surface (see `requirements.md`):

- **Q1 — DB-backed only.** All reads hit the database with the index-aware cost model. **No
  search-index (Solr/ElasticSearch) entry point.** Full-text/faceted product search stays on the
  existing Solr endpoints; GraphQL does structured DB filtering. (Removes the search-vs-DB fork.)
- **Q2 — Analytics deferred.** No aggregation (SUM/COUNT/group-by/time-series) in initial scope;
  revisited after user-group usage examples. `oms-bi` facts are the future source.
- **Q3 — Declare-and-control filtering.** Filtering is a Shopify-style `query:` string (D-A);
  the declaration controls the **grammar**: only declared **search keys** are accepted, each with a
  declared comparator set, value constraints, and required index backing; `first:` caps apply.
  Unknown key / disallowed comparator is rejected. Extends the schema layer (decision 5) and is the
  analyzer's primary control surface.
- **Q4 — Relay connections in initial scope.** List fields are full cursor connections
  (`edges { cursor node }`, `pageInfo { hasNextPage hasPreviousPage startCursor endCursor }`,
  `first`/`after`/`last`/`before`) — not deferred.
- **Q5 — External-id lookup is a must-have.** `order(externalId:)` + `orderByIdentifier(identifier:)`
  + an `identifications` edge on core types.

## Shopify alignment — query LANGUAGE only (D-A…D-D — see shopify-alignment.md)

We adopt Shopify's query *language/ergonomics* so the API feels familiar, but **field/type names
are our OMS data model** — consumers see Maarg's model, not Shopify's.

**Adopt from Shopify (ergonomics):**
- **Filtering — D-A: `query:` search string** (not a structured filter input), our field names as
  keys, parsed server-side to DB conditions, governed by declared keys/comparators (Q3).
- **Sorting:** `sortKey: <Type>SortKeys` enum + `reverse: Boolean` (enum values are our fields, e.g. `ORDER_DATE`).
- **Relay connections** + cursors: `edges { cursor node }`, full `pageInfo`, `first/after/last/before`.
- **Scalars** `DateTime`, `Decimal` (neutral). **`extensions.cost`/error envelope** (already identical).

**Keep ours (data model):**
- **D-D — field/type names are our model:** `orderId`, `orderDate`, `statusId`, `grandTotal`+`currencyUomId`,
  `orderItems`, `fulfillmentStatus`, `shipGroups`, `paymentPreferences`, `facilityName`, …
  **No** Shopify field names, **no** `MoneyBag`/`...Set`, **no** display-status enums.
- **D-B — raw entity ids**, single or composite. No `gid://`, `Node`, or `node()`/`nodes()`.
  Lookups via `order(id:)`/`order(externalId:)`/`orderByIdentification`.

## Phasing

- **Phase 1 (internal):** schema layer (entity-backed + view-entity-backed +
  service-backed resolvers; declared+operator-controlled filter/sort — Q3; type-B DataDocument
  optional), SDL/introspection, analyzer (structural + cost + index), batched executor,
  **Relay connections (Q4)**, **external-id lookup (Q5)**, runtime guards (timeout, row cap,
  wall-clock), static caller profiles, observability. **DB-backed only (Q1).**
- **Phase 2 (agents/partners):** leaky-bucket rate budget, persisted-query
  allow-lists, partner-facing policy profiles.
- **Later (post user-group examples):** analytics/aggregation (Q2).

## Out of scope

- Mutations (writes go through normal Moqui services).
- Open traversal over the raw entity model (rejected in favor of curated graph).
- Auto-generating the whole graph from the entity model (leaks raw data model).
- **Analytics / aggregation (Q2)** — deferred until user-group usage examples.
- **Full-text / faceted Solr search (Q1)** — stays on existing Solr endpoints; GraphQL is
  DB-backed structured filtering only.
- **Live external assembly (category C)** — Shopify/NetSuite live calls
  (`ShopifyOrderServices.get#OrderDetails`) belong to a federation layer, not this
  DB-backed graph. Consumers needing live external data call those services directly.

## Open items for the implementation plan

- Concrete default thresholds (max depth, max field count, `first:` cap, cost
  budget per profile, query timeout seconds, row cap) — calibrate with the cost
  tests.
- Exact `graphql/*.gql.xml` schema-artifact syntax (incl. `resolver-service` +
  view-entity type attributes — decision 12).
- Cursor encoding scheme for Relay connection pagination (now phase 1 — Q4).
- Per-field allowed-operator declaration syntax + how it generates the filter input types (Q3).
- External-id / `byIdentification` resolver + `identifications` edge details (Q5).
- How DataDocument-backed (type B) fields participate in cost estimation.
- Parent-key → service-input mapping convention for service-backed resolvers, and the
  batch (key-list) form a resolver-service must expose to be used inside a list.

## What already exists (reuse vs rebuild)

| Sub-problem | Existing | Plan |
|---|---|---|
| Nested projection / relationship traversal | `EntityDataDocument`, `EntityDynamicViewImpl` | Reuse `EntityDynamicViewImpl` for per-level view construction; **do not** reuse the single-join merge. |
| Query parse / validate / introspection | `graphql-java` | Reuse fully. |
| Depth / complexity limiting | `graphql-java` `Max*Instrumentation` | Reuse; inject our cost model via `FieldComplexityCalculator`. |
| Per-level batching | `graphql-java` `DataLoader` | Reuse (synchronous dispatch). |
| Phase-2 allow-listed queries | `graphql-java` `PersistedQuerySupport` | Reuse. |
| Query timeout | none in Moqui today | Add `EntityFind.queryTimeout()` (framework patch). |
| Connection-pool isolation | Moqui multi-datasource config | Add dedicated read-replica datasource. |
| Order relationship edges | `oms/entity/OrderExtendedEntities.xml` (roles, shipGroups, payments, adjustments, statuses, items, returns…) | Reuse as the declared edge set. |
| Joined/flattened order data | view-entities (`OrderItemDetail`, `ReturnItemView`, …) | Back GraphQL view-entity types with these (decision 12). |
| Computed order fields | services (`get#Orders` `itemFulfillmentStatus`, `customerName`) | Wrap via service-backed resolvers — reuse, don't reimplement (decision 12). |
| `graphql-java` on platform | `mantle-shopify-connector` already ships `graphql-java:25.0` (client-side query-builder DSL) | **Align our component to 25.0** — same runtime loads both; two versions would clash. The connector's `GraphqlFacade`/`GraphqlQueryBuilder` are client-query reference, not server-reusable. |
| Shopify cost-response shape | connector reads `extensions.cost.*` (but acts on none of it) | Emit the same shape from our server; act on it where they didn't. |

## NOT in scope

- **Mutations / writes** — go through normal Moqui services.
- **Open traversal over the raw entity model** — rejected for the curated graph.
- **Auto-generating the whole graph from the entity model** — leaks the raw model.
- **Per-caller field/type visibility** — seam designed in phase 1, populated in phase 2.
- **Leaky-bucket rate budget (governance layer 5)** — phase 2; cost score is a real
  number from day one so it can meter later.
- **Async/parallel fetcher execution** — deferred indefinitely; single-thread model chosen.

## GSTACK REVIEW REPORT

| Review | Trigger | Why | Runs | Status | Findings |
|--------|---------|-----|------|--------|----------|
| CEO Review | `/plan-ceo-review` | Scope & strategy | 0 | — | not run |
| Codex Review | `/codex review` | Independent 2nd opinion | 0 | — | declined by user |
| Eng Review | `/plan-eng-review` | Architecture & tests (required) | 1 | ISSUES_RESOLVED | 5 issues raised, 5 resolved into spec; 4 critical test gaps flagged |
| Design Review | `/plan-design-review` | UI/UX gaps | 0 | — | n/a (no UI) |
| DX Review | `/plan-devex-review` | Developer experience gaps | 0 | — | not run (API-as-product; consider later) |

**Findings resolved this review (folded into the spec above):**
1. Scope: reuse `graphql-java` built-ins (instrumentation + DataLoader + PersistedQuerySupport) instead of a bespoke analyzer/batcher. (decision 8)
2. Architecture / blast radius: dedicated capped pool on a read replica. (decision 9)
3. Architecture / execution: one thread, one read-only txn, synchronous DataLoader. (decision 10)
4. Architecture / authorization: clarified post-review — cross-client isolation is a deployment property (one DB per client), so no query-level scoping in phase 1; phase-2 scope-filter seam for party-scoped partner callers + per-caller field visibility. (decision 11)
5. Code quality: removed the executor↔`EntityDataDocument` merge contradiction; added startup schema/view caching. (unit 1, unit 4)

**Post-review addition (validated against real notnaked Order code):** decision 12 —
three field kinds (entity-backed, view-entity-backed, service-backed resolvers) so the
graph can return computed data (`itemFulfillmentStatus`, `customerName`) consumers
actually need, not just raw columns. Service-backed fields carry a high fixed cost and
lean on runtime guards (analyzer blind spot). Live external assembly (Shopify) ruled
out of scope (federation).

**Critical test gaps flagged (now in Testing strategy):** single-txn EC binding, DataLoader same-thread resolution, pool-isolation under load (E2E). (Tenant-scope test withdrawn — one DB per client makes cross-client leakage impossible.)

**UNRESOLVED:** none — all 5 findings resolved into the spec.

**VERDICT:** ENG REVIEW CLEARED — architecture and test surface locked. Ready to write the implementation plan (`writing-plans`).
