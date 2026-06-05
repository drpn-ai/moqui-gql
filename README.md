# moqui-gql

A curated, read-only **GraphQL query layer for Moqui / HotWax Maarg OMS**, with a built-in
**cost governor** so consumers (internal apps, AI agents, and eventually partners) can fetch
exactly the nested data they need without putting the system at risk.

Modeled on Shopify's Admin GraphQL: a *designed* schema (not arbitrary table access),
connection-style pagination, and a query-cost budget — adapted to Moqui's entity/service model.

## Status

**Requirements & design phase.** No framework code yet. The focus right now is building
confidence in requirements and a concrete example set that will drive the test suite.

## Documents

| Doc | What it is |
|---|---|
| [`docs/requirements.md`](docs/requirements.md) | Requirements reverse-engineered from the existing Maarg published surface (REST APIs, services, DataDocuments, search, BI, client components). The domain-object catalog, capability requirements, scope boundaries, and the open decisions (Q1–Q5). |
| [`docs/examples.md`](docs/examples.md) | **The capability catalog and test-case basis.** Every capability as a `Need → Query → Output` triple (with the exact JSON returned) across all domains — Order, Shipment, Picklist, Returns, Transfers, Inventory/ATP, Catalog, Facility, CycleCount, Routing, external-id, agent, connection pagination — plus a **declared-operator matrix** (per-field allowed operators, Q3) and the guardrail/rejected cases with their exact error responses. Each carries entity/view/service mapping, cost class, and concrete **test assertions**. |
| [`docs/design.md`](docs/design.md) | The architecture/design spec — 12 decisions (curated graph, real `graphql-java`, defense-in-depth cost governance, read-replica isolation, single-txn execution, service-backed resolvers + view-entity types, etc.). |
| [`docs/shopify-alignment.md`](docs/shopify-alignment.md) | Validation against the GraphQL spec and **Shopify Admin GraphQL**. We adopt Shopify's query **language** (`query:` search string, `sortKey`+`reverse`, Relay connections/cursors, `DateTime`/`Decimal`, cost/error envelope) but **keep our OMS field names** and **raw entity ids** (no `gid://`/`Node`). Scorecard + resolved decisions D-A…D-D. |
| [`docs/implementation-plan-phase1.md`](docs/implementation-plan-phase1.md) | The phase-1 TDD implementation plan (14 tasks). Held until requirements/examples are locked. |

## Reading order

New here? Start with **`docs/requirements.md`** (what consumers need) → **`docs/examples.md`**
(what the API does, as test cases) → **`docs/design.md`** (how) → the plan.

## Principles

- **Read-only.** Writes stay on existing Moqui services.
- **Curated graph is the security boundary.** Only declared types/edges are reachable.
- **Estimate *and* enforce.** Static cost analysis gates queries; runtime guards (query timeout,
  row cap, wall-clock) catch what the estimate misses.
- **Examples are the contract.** The example catalog drives the test suite.
