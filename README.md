# moqui-gql (Darpan fork)

A curated, read-only GraphQL query layer for Moqui with a built-in cost governor. This repo
(`drpn-ai/moqui-gql`) is Darpan's fork of the upstream HotWax engine: the OMS schema was removed and
replaced by a small Darpan schema, and every read is tenant-scoped.

GraphQL is not a supported public Darpan API yet. The component ships in the backend images, but no
Darpan product surface or connector consumes it, and it has no release tag (the prod Dockerfile builds
`main`). Treat everything below as engineering reference.

## What it serves

| Path | Auth | Service |
|---|---|---|
| `POST /rest/s1/graphql` | Moqui session (login key / cookie) | `gql.QueryServices.execute#Query` |
| `POST /rest/s1/graphql/public` | API key only (`Authorization: Bearer <key>` or `X-Darpan-Gql-Key`) | `gql.QueryServices.execute#PublicQuery` |
| `GET /rest/s1/graphql/sdl` | none | `gql.QueryServices.get#Sdl` (prints the built schema) |

Request body: `{ query, variables, operationName }`. Response: `{ data, errors, extensions.cost }`,
where each error carries a stable `extensions.code`.

Session access is granted by the `DARPAN_GQL_API` artifact group in darpan's `SecuritySeedData.xml`.

## Schema

The schema is built once at startup from every component's `graphql/*.gql.xml`. Today there is one
file, `graphql/DarpanSchema.gql.xml`, with one type:

- `ReconciliationResult` over `darpan.reconciliation.ReconciliationRunResult`
- roots `reconciliationResult(reconciliationRunResultId)` and `reconciliationResults` (Relay
  connection; search keys `savedRunId`, `reconciliationRunId`, `ruleSetId`, `statusEnumId`; sort keys
  `CREATED_DATE`, `RESULT_ID`)

New types are added by declaring more `gql-type` blocks. Only declared types and edges are reachable.

## Tenant scope

Every entity read passes through a scope filter. In this fork the default is
`DarpanTenantScopeFilter`, which ANDs `companyUserGroupId = <tenant>` into each find:

- Session path: the tenant is the caller's active tenant (`TenantAccessSupport`). No active tenant
  means zero rows.
- Public path: the tenant is fixed by the API key. The session is never consulted. A key with a
  blank tenant returns zero rows.
- A type whose entity has no `companyUserGroupId` column fails the read instead of returning
  unscoped rows. Such entities must be reached through a scoped parent edge, never as a root.

`ProductStoreScopeFilter` (OMS heritage) is still in the code. The public path ignores it. On the
session path, a `GqlCallerProfile` with `scopeProductStoreId` set replaces the tenant filter, so do
not set that field on profiles for Darpan users.

## Public API-key realm

- `gql.GqlAuthServices.create#ApiKey` mints a key for the caller's active tenant, labelled by
  `clientName`, with an optional `expireDate`. Tenant admin of that tenant, or super-admin. The tenant
  is not an input: an admin of several tenants gets a key for the one they are signed into. Only a
  super-admin may set `profileId`. The raw key (prefix `dgql_`) is returned once; only its SHA-256
  digest is stored in `GqlApiKey`.
- `resolve#ApiKey` looks the key up by digest. Missing, unknown, disabled or expired keys all get the
  same `UNAUTHENTICATED` error and the query does not run. A session without a key is rejected.
- A key may carry a `GqlCallerProfile` (cost and throttle limits). The throttle bucket and the query
  log are keyed by `apiKeyId`, so each key has its own budget.

Managing keys, with the same gate and only the active tenant's keys (another tenant's key reads as
not found):

- `list#ApiKeys` lists keys newest first (label, tenant, profile, enabled, created, expires, last
  used). Never the key or its digest.
- `disable#ApiKey` turns a key off from the next request on. Use it for a leaked key.
- `rotate#ApiKey` mints a new key for the same tenant, profile and label and returns it once. The old
  key keeps working for `graceMinutes` (default 60, 0 ends it now) so the agent can switch over.

## Governance

| Layer | Behaviour | Knob (default) |
|---|---|---|
| Static gate (before any SQL) | Rejects on depth, estimated cost, missing or oversized `first`/`last`, undeclared filter keys or operators | `gql.maxDepth` (6), `gql.maxCost` (1000), `gql.maxFirst` (100), `gql.unindexedFilterPenalty` (50) |
| Throttle | Per-caller token bucket debited by the estimated cost; returns `THROTTLED` when empty | `gql.throttle.bucketSize` (= `maxCost`), `gql.throttleRestoreRate` (50/s) |
| Row cap | Per nested level | `gql.maxRowsPerLevel` (5000) |
| Wall-clock budget | Checked before each field fetch; returns `DEADLINE_EXCEEDED` | `gql.wallClockBudgetMs` (30000) |
| Transaction | One read-only transaction per request | `gql.queryTimeoutSeconds` (20) |

There is no per-statement JDBC timeout (the framework change was dropped in #62). The 20-second
value only applies when the engine starts the transaction itself; through the REST services it runs
inside the service's own transaction, so the service transaction timeout applies instead. Buckets are
in memory per node. Details: [`docs/query-cost-model.md`](docs/query-cost-model.md),
[`docs/throttle.md`](docs/throttle.md).

## Observability

- `GqlQueryLog` raw rows: every rejected query, slow queries for their shape, and a 1% sample.
  `GqlQueryStatsBin` holds per-shape aggregates.
- `purge#QueryLog` runs nightly at 03:00 (`purge_GqlQueryLog` job): raw rows older than 90 days,
  bins older than 365 days.
- `gql.GqlMonitorServices.scan#QueryAnomalies` runs every 15 minutes (`GqlAnomalyScanJob`). Per caller
  it checks volume (> 2000 queries), total cost (> 500000) and reject rate (> 50% with at least 20
  queries) over the window, writes a `GqlAnomalyAlert` row (status `NEW`, one per caller, metric and
  window) and logs a WARN. It alerts only; it does not throttle or disable keys. Thresholds are
  `gql.anomaly.*` system properties.

## Build and tests

- `./gradlew :runtime:component:moqui-gql:jar` builds `lib/moqui-gql.jar` and vendors graphql-java into
  `lib/`. Both Docker images build it before `addRuntime`.
- The inherited test suite (`MoquiSuite`) targets the upstream OMS schema and a MySQL OMS database
  (`hcsd_notnaked`, forced in `build.gradle`). It has not been ported to the Darpan schema and does not
  run green in Darpan. `ApiKeyAuthTests` (key hashing, fixed-tenant filter) is the Darpan-specific
  class.

## Documents

| Doc | Status |
|---|---|
| [`docs/STATUS.md`](docs/STATUS.md) | Current state of this fork |
| [`docs/design.md`](docs/design.md) | Upstream architecture record, with a Darpan fork section at the top |
| [`docs/query-cost-model.md`](docs/query-cost-model.md) | Cost formula (engine behaviour is current; worked examples use the old OMS schema) |
| [`docs/throttle.md`](docs/throttle.md) | Throttle mechanics |
| [`docs/requirements.md`](docs/requirements.md), [`docs/examples.md`](docs/examples.md), [`docs/schema.graphql`](docs/schema.graphql), [`docs/shopify-alignment.md`](docs/shopify-alignment.md) | Upstream OMS-era design; the OMS schema they describe is not in this fork |
| [`docs/archive/`](docs/archive/) | Upstream implementation plans and reviews (historical) |
