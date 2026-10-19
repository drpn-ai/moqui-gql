# moqui-gql — Status (Darpan fork)

Last updated: 2026-10-19, at `main` 0c49b4b.

**Summary:** the engine runs in Darpan with a one-type, tenant-scoped schema, a session endpoint, a
separate public API-key endpoint, query logging, a nightly purge and a 15-minute anomaly scan.
GraphQL is not a supported public Darpan API yet: no Darpan UI or connector calls it, and the
component has no release tag (both Dockerfiles build `MOQUI_GQL_REF=main`).

---

## History in one paragraph

Upstream (HotWax, May–June 2026) built the engine against the Maarg OMS: a curated OMS schema
(`OmsSchema.gql.xml`: Order, ShipGroup, Product, Facility, InventoryLevel and more), the cost
governor, the per-caller throttle, caller profiles, the parsed-document cache and query-log v2.
The framework change for a per-statement query timeout (hotwax/moqui-framework PR #45) was tabled and
the dependency removed in #62. The Darpan fork (from 63734b6, 2026-06-29) deleted the OMS schema and
example services, added `DarpanSchema.gql.xml` and the fail-closed `DarpanTenantScopeFilter`, then
added the public API-key realm, per-key throttle buckets, anomaly detection and a scheduler fix for
the purge job.

---

## What is built

| Area | State |
|---|---|
| Schema | `graphql/DarpanSchema.gql.xml`: `ReconciliationResult` over `darpan.reconciliation.ReconciliationRunResult`; roots `reconciliationResult` (by id) and `reconciliationResults` (connection, search on `savedRunId`, `reconciliationRunId`, `ruleSetId`, `statusEnumId`; sort `CREATED_DATE`, `RESULT_ID`) |
| Engine features | Relay keyset cursors, Shopify-style `query:` search, `sortKey`/`reverse`, DataLoader batching, view-backed types, aggregate fields, has-one leaf edges. Most of these have no user in the Darpan schema yet |
| Session endpoint | `POST /rest/s1/graphql` (`execute#Query`, authenticated; `DARPAN_GQL_API` artifact group in darpan seed data) |
| Public endpoint | `POST /rest/s1/graphql/public` (`execute#PublicQuery`), API key only |
| SDL | `GET /rest/s1/graphql/sdl`, no auth, prints the built schema |
| Tenant scope | `DarpanTenantScopeFilter` on every find; session = active tenant, public = the key's tenant; no tenant = zero rows |
| API keys | `create#ApiKey` (super-admin only, raw key returned once, SHA-256 stored), `resolve#ApiKey` (by digest, generic `UNAUTHENTICATED` on any failure) |
| Governor | Static gate before SQL; codes `DEPTH_EXCEEDED`, `COST_EXCEEDED`, `FIRST_REQUIRED`, `FIRST_TOO_LARGE`, `FIELD_NOT_FILTERABLE`, `OPERATOR_NOT_ALLOWED`, `BATCH_LIMIT_EXCEEDED` |
| Throttle | Per-caller token bucket, `THROTTLED`; keyed by `apiKeyId` on the public path, `userId` on the session path |
| Runtime guards | Row cap per level, wall-clock budget (`DEADLINE_EXCEEDED`), one read-only transaction |
| Query log | `GqlQueryLog` (rejected, slow-for-shape, 1% sample), `GqlQueryShape`, `GqlQueryStatsBin` |
| Purge | `purge#QueryLog`, nightly 03:00, raw 90 days, bins 365 days; runs under the scheduler (`anonymous-all`, not remote) |
| Anomaly scan | `scan#QueryAnomalies`, every 15 minutes; volume, cost and reject-rate thresholds; `GqlAnomalyAlert` rows + WARN log; alert only |

## Timeout model

- No per-statement JDBC timeout (removed in #62 with the tabled framework PR).
- `gql.queryTimeoutSeconds` (20) is passed to `runUseOrBegin`. It only takes effect when no
  transaction is active. Through `execute#Query` and `execute#PublicQuery` the service transaction is
  already open, so its timeout governs.
- `gql.wallClockBudgetMs` (30000) is checked before each field fetch. It stops further fetches; it
  does not interrupt a running SQL statement.
- `gql.maxRowsPerLevel` (5000) caps rows per nested level.

## Tests

- The suite (`MoquiSuite`, 33 classes) is inherited from upstream. It targets the deleted OMS schema
  and a MySQL OMS database (`hcsd_notnaked`, forced by `build.gradle`). It has not been ported and is
  not expected to pass in Darpan.
- `ApiKeyAuthTests` is the Darpan addition: key hashing, key generation, and the fixed-tenant filter
  (blank tenant denies). It needs no booted Moqui, but it is run through the same suite.
- The tenant isolation and per-key throttle behaviour were verified live at commit time
  (see the c33aa43, ba9a7ed and f1bca83 messages), not by an automated Darpan test.

## Known gaps

- No release tag; pin one before any public exposure.
- The test suite needs porting to the Darpan schema and an H2 or Darpan test database.
- No key management services beyond create (list, disable, rotate are data edits).
- `GqlAnomalyAlert` has no reader service or UI.
- Throttle buckets are per node and in memory (reset on restart).
- On the session path, a caller profile with `scopeProductStoreId` replaces the tenant filter
  (OMS heritage). Do not set it for Darpan users.
- `entity/GqlEntities.xml` still declares the OMS view-entities (`OrderBillToCustomer`,
  `FacilityOriginAddress`) and an `OrderItemShipGroup` extension; no schema type uses them.
- `docs/schema.graphql` and `SchemaContractTests` still describe the OMS contract, not the Darpan
  schema.
