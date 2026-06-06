# moqui-gql

A curated, read-only **GraphQL query layer for Moqui / HotWax Maarg OMS**, with a built-in
**cost governor** so consumers (internal apps, AI agents, and eventually partners) can fetch
exactly the nested data they need without putting the system at risk.

Modeled on Shopify's Admin GraphQL: a *designed* schema (not arbitrary table access),
connection-style pagination, and a query-cost budget — adapted to Moqui's entity/service model.

## Status

**Phases 1, 1.5, 2 & 3 are implemented and merged.** The engine builds the curated schema at
startup, governs query cost pre-execution (depth/cost/first/filter/batch limits), executes
Relay-paginated reads with DataLoader batching in one read-only transaction, throttles per caller
(live token bucket), caches parsed query documents, and serves `POST /rest/s1/graphql`
(+ `GET /rest/s1/graphql/sdl`). **~75 tests pass** against MySQL `hcsd_notnaked`. The one open
dependency is framework PR #45 (`EntityFind.queryTimeout`). See [`docs/STATUS.md`](docs/STATUS.md)
for authoritative status; the design docs below remain the contract — some surface is intended,
not yet built (see each doc's header).

## Documents

| Doc | What it is |
|---|---|
| [`docs/requirements.md`](docs/requirements.md) | Requirements reverse-engineered from the existing Maarg published surface (REST APIs, services, DataDocuments, search, BI, client components). The domain-object catalog, capability requirements, scope boundaries, and the open decisions (Q1–Q5). |
| [`docs/examples.md`](docs/examples.md) | **The capability catalog and test-case basis.** Every capability as a `Need → Query → Output` triple (with the exact JSON returned) across all domains — Order, Shipment, Picklist, Returns, Transfers, Inventory/ATP, Catalog, Facility, CycleCount, Routing, external-id, agent, connection pagination — plus a **declared-operator matrix** (per-field allowed operators, Q3) and the guardrail/rejected cases with their exact error responses. Each carries entity/view/service mapping, cost class, and concrete **test assertions**. |
| [`docs/schema.graphql`](docs/schema.graphql) | **The SDL contract.** Our OMS field/type names with the Shopify-shaped query language (Relay connections, `query:`/`sortKey`/`reverse`, `DateTime`/`Decimal`, declared `@search` keys, `@service`/`@cost` directives). What the engine generates and the test catalog exercises. |
| [`docs/design.md`](docs/design.md) | The architecture/design spec — 12 decisions (curated graph, real `graphql-java`, defense-in-depth cost governance, read-replica isolation, single-txn execution, service-backed resolvers + view-entity types, etc.). |
| [`docs/shopify-alignment.md`](docs/shopify-alignment.md) | Validation against the GraphQL spec and **Shopify Admin GraphQL**. We adopt Shopify's query **language** (`query:` search string, `sortKey`+`reverse`, Relay connections/cursors, `DateTime`/`Decimal`, cost/error envelope) but **keep our OMS field names** and **raw entity ids** (no `gid://`/`Node`). Scorecard + resolved decisions D-A…D-D. |
| [`docs/implementation-plan-phase1.md`](docs/implementation-plan-phase1.md) | The phase-1 TDD implementation plan (Tasks 0–14), aligned to all decisions (Q1–Q5, D-A…D-D) and **bound to the contract** — Task 7 asserts the built SDL ⊇ `schema.graphql`; Task 14 runs `examples.md` as the test suite. Ready to execute when we move to build. |
| [`docs/query-cost-model.md`](docs/query-cost-model.md) | **How query cost is computed** — the science (lists multiply, service-backed fields are flat-costed, the effective page size, saturation) and three worked case studies whose numbers come from the running engine. |
| [`docs/throttle.md`](docs/throttle.md) | **Rate limiting** — the per-caller cost bucket (token/leaky bucket), the lazy-refill math, where it sits in the gate chain (last), `throttleStatus`, defaults + per-caller `GqlCallerProfile` overrides, a worked trace, and how a client backs off. |
| [`docs/STATUS.md`](docs/STATUS.md) | **Current build status** — phases delivered (1, 1.5, 2, 3), the capability matrix, stable error codes, the test inventory, and outstanding items. |

## Reading order

For current status, start with **`docs/STATUS.md`**. For the design, read
**`docs/requirements.md`** (what consumers need) → **`docs/examples.md`** (the capability catalog)
→ **`docs/design.md`** (how) → **`docs/query-cost-model.md`** + **`docs/throttle.md`** (governance
internals) → **`docs/implementation-plan-phase1.md`** (the plan).

## Principles

- **Read-only.** Writes stay on existing Moqui services.
- **Curated graph is the security boundary.** Only declared types/edges are reachable.
- **Estimate *and* enforce.** Static cost analysis gates queries; runtime guards (query timeout,
  row cap, wall-clock) catch what the estimate misses.
- **Examples are the contract.** The example catalog drives the test suite.
