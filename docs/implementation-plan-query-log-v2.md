# GqlQueryLog v2 — adaptive slow-query logging, per-shape stats bins, retention — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the unconditional per-query `GqlQueryLog` write (a synchronous `runRequireNew`
transaction on EVERY request) with a policy that logs what we will actually use: every REJECTED
query, ALLOWED queries that are **slow for their shape** (adaptive: avg + 2.6σ, after warm-up,
over an absolute floor — the framework's `ArtifactStatsInfo.isHitSlow` math), and a small random
sample for calibration. Aggregate everything else into per-shape **stats bins** (the
`ArtifactHitBin` analogue) so `avg(durationMs)` vs `avg(estimatedCost)` per query shape — the
cost-model calibration dataset — is available at tiny storage cost. Fix the hardcoded
`callerProfile: "default"`, add `queryHash`, and add a retention/purge job.

**Architecture:** A query **shape** is identified by `queryHash` = SHA-256 of the raw query string
(same identity the preparsed-document cache uses). Per-shape running stats (count/total/total-sq —
incremental avg + stddev, no history) live in a capped Moqui cache `gql.query.stats`, mirroring
`ThrottleGate`: a **pure `record()` step** (unit-tested with injected time) + a cache-backed
`track()` entry, state mutated under per-object synchronization. Each `ShapeStats` carries the
current `Bin`; when a hit arrives and the bin is older than `gql.queryLog.binSeconds` the finished
bin is returned to the engine and persisted (inline flush, like `advanceArtifactHitBin` — no
scheduler). Raw rows, shape rows, and bins are written in the same `runRequireNew` so a log failure
never fails the request (existing guarantee preserved).

**Known tradeoffs (documented, accepted):** cache eviction of a cold shape loses its un-flushed
bin and resets warm-up; the last bin of a shape that stops being queried is never flushed; raw
slow/sample rows are biased toward outliers — the bins (which aggregate ALL hits) are the
representative dataset, raw rows are for debugging individual executions.

**Tracking:** issue TBD. **DB change:** two new entities + two new columns on `GqlQueryLog`
(Moqui auto-DDL adds them). Tests vs MySQL `hcsd_notnaked`.

---

## Config knobs (MoquiConf.xml)

| Property | Default | Meaning |
|---|---|---|
| `gql.queryLog.slowMinMillis` | 1000 | absolute floor — never flag a hit slower than avg+2.6σ but faster than this (framework `userImpactMinMillis`) |
| `gql.queryLog.warmupHits` | 50 | per-shape hits before slow detection starts (framework `checkSlowThreshold`) |
| `gql.queryLog.sampleRate` | 0.01 | probability an ALLOWED, non-slow query still writes a raw row |
| `gql.queryLog.binSeconds` | 900 | stats-bin length per shape |
| `gql.queryLog.retainDays` | 90 | raw `GqlQueryLog` retention (purge job) |
| `gql.queryLog.binRetainDays` | 365 | `GqlQueryStatsBin` retention (purge job) |
| cache `gql.query.stats` | max-elements 2000 LRU | caps per-shape stats memory |

## File structure

| File | Change |
|---|---|
| `src/main/groovy/org/moqui/gql/policy/QueryStats.groovy` (NEW) | pure `record()` (slow math + bin roll) + cache-backed `track()` |
| `src/main/groovy/org/moqui/gql/GqlEngine.groovy` | queryHash, real callerProfile, log policy, bin/shape persistence |
| `entity/GqlEntities.xml` | `GqlQueryLog` +`queryHash`+`slowHit`+hash index; NEW `GqlQueryShape`, `GqlQueryStatsBin` |
| `service/gql/QueryServices.xml` | NEW `purge#QueryLog` |
| `data/GqlSetupData.xml` (NEW) | daily `ServiceJob` for the purge |
| `MoquiConf.xml` | knobs above + `gql.query.stats` cache cap |
| `src/test/groovy/QueryStatsTests.groovy` (NEW) | pure unit tests: warm-up, σ math, floor, bin rollover |
| `src/test/groovy/QueryLogPolicyTests.groovy` (NEW) | e2e: rejected-always, sampleRate 0/1, shape row, bin flush, purge |
| `src/test/groovy/EndpointTests.groovy` | allowed-row test pins `sampleRate=1` (save/restore, ThrottleE2ETests pattern) |
| `src/test/groovy/CallerProfileTests.groovy` | assert the resolved profileId is recorded (not `"default"`) |
| `src/test/groovy/MoquiSuite.groovy` | register the two new classes |
| `docs/{STATUS.md,design.md}` | reflect the logging policy |

---

## Task 1: `QueryStats` — pure slow detection + bin rollover

**Files:** `policy/QueryStats.groovy` (NEW), `QueryStatsTests.groovy` (NEW), `MoquiSuite.groovy`

- [ ] **Step 1 — failing tests** (`QueryStatsTests`, pure — no ec, injected `now`):
  - no slow verdict during warm-up: 49 hits at 10ms then one at 10000ms with `warmupHits=50` → `!slow`
  - slow after warm-up: 50 hits at 100ms (σ≈0) then 10000ms → `slow`
  - floor wins: 50 hits at 10ms then 500ms with `slowMinMillis=1000` → `!slow` (statistically slow, under floor)
  - bin rollover: `binMillis=1000`, hits at now=0 and now=1500 → second `record` returns `finishedBin`
    with `hitCount==1`, totals/min/max of the first hit; new bin holds the second
  - bin accumulates cost/rows: totals sum across hits in the bin
- [ ] **Step 2 — run, expect FAIL** (class missing).
- [ ] **Step 3 — implement** `QueryStats` mirroring `ThrottleGate`:
  `static class ShapeStats { long hitCount; double totalMs, totalSqMs; Bin curBin }`,
  `static class Bin { long binStartMs, hitCount, slowHitCount, minMs, maxMs, totalCost, totalRows; double totalMs, totalSqMs }`,
  `static Outcome record(ShapeStats, durationMs, cost, rows, now, warmupHits, slowMinMillis, binMillis)` —
  slow iff `hitCount >= warmupHits && durationMs >= slowMinMillis && durationMs > avg + 2.6*stdDev`
  (incremental stddev: `sqrt(abs(totalSq - total²/n) / (n-1))`, the `ArtifactStatsInfo` formula);
  then update lifetime totals, roll the bin if aged, count into the current bin.
  `static Outcome track(ec, queryHash, ...)` — cache `gql.query.stats`, double-checked create under
  `synchronized(QueryStats.class)`, mutate under `synchronized(s)`.
- [ ] **Step 4 — run, expect PASS.** **Step 5 — commit.**

## Task 2: entities + knobs

**Files:** `entity/GqlEntities.xml`, `MoquiConf.xml`

- [ ] `GqlQueryLog`: add `queryHash` (`text-short`), `slowHit` (`text-indicator`), index `GQL_QLOG_HASH`;
  add the missing `<description>` + `short-alias` while touching it (audit hygiene).
- [ ] NEW `GqlQueryShape`: `queryHash` (pk, `text-short`), `queryText` (`text-very-long`, truncated 4000),
  `firstSeenDate`. One row per shape — raw rows and bins join to it by hash.
- [ ] NEW `GqlQueryStatsBin`: `statsBinId` (pk, sequenced), `queryHash`, `binStartDate`, `binEndDate`,
  `hitCount`, `slowHitCount`, `totalDurationMs`, `totalSquaredDuration` (`number-float`),
  `minDurationMs`, `maxDurationMs`, `totalCost`, `totalRows`; index `(queryHash, binStartDate)`.
- [ ] MoquiConf: the six `gql.queryLog.*` defaults + `<cache name="gql.query.stats" max-elements="2000"
  eviction-strategy="least-recently-used"/>`.
- [ ] Compile/boot check via any single test run (auto-DDL adds columns). Commit.

## Task 3: engine integration — the logging policy

**Files:** `GqlEngine.groovy`, `EndpointTests.groovy`, `QueryLogPolicyTests.groovy` (NEW), `MoquiSuite.groovy`

- [ ] **Step 1 — failing tests** (`QueryLogPolicyTests`, e2e; save/restore the `gql.queryLog.*`
  system properties in setup/cleanup exactly like `ThrottleE2ETests` does for the bucket):
  - REJECTED is always logged: bad query → raw row with `verdict REJECTED`, non-null `queryHash`,
    and a `GqlQueryShape` row for that hash
  - `sampleRate=0`: allowed query → NO new raw row; `gql.query.stats` cache has the shape entry
  - `sampleRate=1`: allowed query → raw row with `slowHit == "N"`, `callerProfile == "default"`,
    `queryHash` matching SHA-256 of the query text
  - bin flush: `binSeconds=0` → two executions of the same query produce a `GqlQueryStatsBin` row
    (`hitCount==1`, `totalCost == estimatedCost` of one run)
  - (slow-path verdict is covered by Task 1 unit tests — not reproducible deterministically e2e)
- [ ] **Step 2 — update `EndpointTests`**: the allowed-row test pins `sampleRate=1` (save/restore);
  rejected-row test unchanged.
- [ ] **Step 3 — implement in `GqlEngine`:**
  - read the `gql.queryLog.*` knobs in the constructor (`sysOr` pattern)
  - `sha256Hex(query)` helper (`MessageDigest`)
  - `resolveCallerProfile()` already returns the profile map — thread `profileId` through;
    `"default"` only when no profile resolved (fixes the hardcoded value)
  - replace the unconditional `writeQueryLog` call with:
    REJECTED → write raw row (`slowHit "N"`); ALLOWED → `QueryStats.track(...)` →
    write raw row iff `outcome.slow` (`slowHit "Y"`) or `ThreadLocalRandom < sampleRate`;
    persist `outcome.finishedBin` when present
  - one `runRequireNew` writes (in order): `GqlQueryShape` if absent (find-then-create),
    finished bin if any, raw row if any; whole block try/caught — never fails the request
- [ ] **Step 4 — run, expect PASS** (new tests + EndpointTests + full suite green). **Step 5 — commit.**

## Task 4: caller profile recorded — extend `CallerProfileTests`

- [ ] With the existing profile fixture and `sampleRate=1`: execute as the profiled user, assert the
  raw row's `callerProfile` equals the fixture `profileId`. Run, commit.

## Task 5: purge service + job

**Files:** `service/gql/QueryServices.xml`, `data/GqlSetupData.xml` (NEW), `QueryLogPolicyTests.groovy`

- [ ] **Step 1 — failing test:** create a `GqlQueryLog` row dated 200 days back and a fresh one;
  call `gql.QueryServices.purge#QueryLog` (`retainDays: 90`); old gone, fresh remains; same for an
  old `GqlQueryStatsBin` vs `binRetainDays`.
- [ ] **Step 2 — implement** `purge#QueryLog`: in-params `retainDays`/`binRetainDays` (defaults from
  the system properties); `find(...).condition("queryDate", LESS_THAN, cutoff).deleteAll()` for raw,
  same on `binStartDate` for bins; `<description>` block.
- [ ] **Step 3 — `data/GqlSetupData.xml`** (`type="seed"`): daily `moqui.service.job.ServiceJob`
  `purge_GqlQueryLog`, `cronExpression "0 0 3 * * ?"`. (Seed loads on fresh installs; the component
  is pre-first-deployment so no upgrade step is needed — see maarg data-load conventions.)
- [ ] **Step 4 — run, expect PASS.** **Step 5 — commit.**

## Task 6: docs

- [ ] `STATUS.md`: logging-policy paragraph (what is logged when, the calibration story).
- [ ] `design.md`: new decision — "query-log v2: adaptive slow + sampled raw rows, per-shape bins,
  framework `ArtifactStatsInfo`/`ArtifactHitBin` pattern; bins are the calibration dataset."
- [ ] `docs/review-2026-06-10.md` item 3: mark addressed by this change. Commit.

---

## Verification

- Full suite green (`./gradlew :runtime:component:moqui-gql:test`), including the audit script
  showing no NEW hygiene findings on touched artifacts.
- Manual sanity: run a query twice with `binSeconds=0`, select `GqlQueryStatsBin` — totals match.
