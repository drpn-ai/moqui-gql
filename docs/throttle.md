# Rate Limiting — the Throttle

How `moqui-gql` rate-limits callers with a per-caller cost **bucket**, why it is the same "leaky
bucket" model Shopify uses, exactly what the code does, and a worked trace whose numbers follow the
engine's own formula.

> Authoritative source: `src/main/groovy/org/moqui/gql/policy/ThrottleGate.groovy` (the bucket),
> `GovernorInstrumentation.beginExecuteOperation` (where it runs, last in the gate chain), and
> `GqlEngine.execute` (defaults, caller-profile overrides, `throttleStatus` emission).
> Defaults are code constants read by `GqlEngine` (`gql.throttle.bucketSize`, `gql.throttleRestoreRate`).
> Cost — the currency the throttle spends — is documented in [`query-cost-model.md`](query-cost-model.md).

---

## 1. Why a throttle (cost alone is not enough)

The cost gate (`maxCost`) bounds **one** query: it refuses any single query whose static estimate is
too large. But nothing stops a caller from sending a thousand *individually-legal* queries in a tight
loop. A point read costs `4` and passes every static check — fire 10,000 of them per second and you
have still buried the database.

The throttle bounds **sustained spend over time**. It is a rate limit, not a per-query gate, and it
reuses the cost number as its currency: every query that runs spends its `requestedQueryCost` against
a replenishing per-caller budget. So cost does double duty —

- a **hard ceiling** on any one query (`maxCost`, the cost gate), and
- a **spend** against a refilling budget (`bucketSize` / `restoreRate`, the throttle).

---

## 2. The model — "token bucket" and "leaky bucket" are the same thing here

Shopify describes its GraphQL rate limit as a **leaky bucket**. Mechanically what we implement is a
**token bucket**. For our purposes these are two names for one behaviour:

- Each caller owns a **bucket** that holds at most `maximumAvailable` credits.
- The bucket **refills continuously** at `restoreRate` credits per second, never exceeding the cap.
- Each query that runs **debits** its cost from the bucket.
- If the bucket cannot cover a query's cost, the query is **throttled** (rejected, not queued).

```
            refill: restoreRate credits/sec (capped at maximumAvailable)
                       │
                       ▼
   ┌───────────────────────────────────┐
   │ ███████████████░░░░░░░░  available │  ← currentlyAvailable
   └───────────────────────────────────┘
                       │
                       ▼
            debit: requestedQueryCost per query
            (only if available ≥ cost, else THROTTLED)
```

"Token bucket" frames it as credits you spend and earn back; "leaky bucket" frames it as a bucket that
drains at a steady rate while requests pour in. The arithmetic — `available = min(cap, available +
elapsed × rate) − cost` — is identical. We say **token bucket** in the code because we debit
discrete, variable-sized costs (not a fixed drip), but we deliberately match Shopify's **shape** and
defaults so any client already integrated with Shopify GraphQL understands our throttle for free
(see §5).

---

## 3. The mechanics — exactly what the code does

The whole model is `ThrottleGate` (≈40 lines). Two methods: `decide` (the pure refill-and-debit step,
unit-tested with injected time) and `check` (resolves the caller's bucket from the cache, then calls
`decide`).

### 3.1 The bucket

```groovy
static class Bucket { double available; long lastMillis }
```

One per caller. Created **full** (`available = bucketSize`) the first time a caller is seen, so a new
caller starts with a complete budget rather than an empty one. `lastMillis` records when the bucket
was last refilled.

### 3.2 Lazy refill — no background timer

The bucket is **not** topped up by a scheduler. Instead, every request computes how much time has
passed since the last touch and credits that much, capped at the bucket size:

```
refill   = ((now − lastMillis) / 1000) × restoreRate
available = min(bucketSize, available + refill)      // only when refill > 0
lastMillis = now
```

This "lazy" refill is exact (it is a function of elapsed wall-clock, not of how often anyone polled),
needs no threads, and keeps the bucket a plain value in a cache. A bucket idle for 20 seconds at
`restoreRate = 50` has earned back 1000 credits — i.e. it is full again.

### 3.3 Debit (and "peek")

```
allowed = available ≥ cost
if (allowed && apply) available -= cost
```

Two things to note:

- A query is **only debited when it actually runs** (`apply = true`). When the throttle is consulted
  for a query that another gate already rejected, `apply = false` — the call still refills and
  *reports* the bucket, but spends nothing. This is the **peek**: you never pay for a query that does
  not execute.
- A **throttled** query (`available < cost`) is **not debited** either — `allowed` is false, so the
  `-= cost` never happens. A bounced query costs you nothing; you simply have to wait for refill.

### 3.4 Per-caller keying

```groovy
String callerKey = ec.user?.userId ?: "anonymous"
ThrottleGate.check(ec, callerKey, cost, bucketSize, restoreRate, willExecute)
```

The bucket is keyed by Moqui `userId` (all unauthenticated traffic shares the `"anonymous"` bucket).
Buckets live **by reference** in the Moqui cache `gql.throttle.bucket` so they persist across
requests; get-or-create and `decide` are `synchronized` so concurrent requests from the same caller
debit the same bucket safely.

---

## 4. Where it sits — the **last** gate

The throttle runs inside the governor's `beginExecuteOperation` hook — after parse/validate, before
any data fetcher — in the **same pre-execution walk** as the static gates, and it runs **last**:

```
parse → validate → coerce variables
   └─ governor walk (one pass):
        depth        → DEPTH_EXCEEDED
        cost         → COST_EXCEEDED
        first / filter / batch  → FIRST_REQUIRED · FIELD_NOT_FILTERABLE · BATCH_LIMIT_EXCEEDED · …
        ────────────────────────────────────────────────
        THROTTLE (last)  → THROTTLED         ← debits only if every check above passed
```

The ordering is deliberate:

```groovy
boolean willExecute = w.errors.isEmpty()                       // did every static gate pass?
ThrottleGate.Decision td = ThrottleGate.check(ec, callerKey, cost, bucketSize, restoreRate, willExecute)
if (willExecute && !td.allowed) w.errors.add(err(... "THROTTLED" ...))
```

- A query rejected by an **earlier** gate **peeks** (`willExecute = false → apply = false`): its
  bucket is refilled and reported, but **not** debited. You are not charged for a malformed or
  over-budget query.
- A query that passes every static gate **debits** its cost — unless the bucket can't cover it, in
  which case it is rejected with `THROTTLED` and (per §3.3) **still not debited**.

The `THROTTLED` error carries everything a caller needs to back off, in `extensions`:

| Field | Meaning |
|---|---|
| `code` | `"THROTTLED"` |
| `cost` | the `requestedQueryCost` that could not be afforded |
| `currentlyAvailable` | credits in the bucket right now |
| `maximumAvailable` | the bucket cap |
| `restoreRate` | credits refilled per second |

From these a client computes the wait exactly: `ceil((cost − currentlyAvailable) / restoreRate)`
seconds until the query would fit (see §8).

---

## 5. What the caller sees — `throttleStatus`

Every response — allowed or throttled — reports the **live** bucket in the Shopify-shaped cost block:

```json
"extensions": { "cost": {
  "requestedQueryCost": 340,
  "actualQueryCost": 340,
  "throttleStatus": { "maximumAvailable": 1000, "currentlyAvailable": 660, "restoreRate": 50 }
} }
```

This is the exact shape the existing `mantle-shopify-connector` (a GraphQL *client* on the same
`graphql-java`) already consumes from Shopify, so any consumer integrated with Shopify GraphQL reads
our throttle with no new code.

Two honest notes about the numbers:

- **`actualQueryCost == requestedQueryCost`.** We debit the **estimate**; we do not re-measure actual
  rows after execution and refund the difference (Shopify does). Our debit is therefore *conservative*
  — a caller may be charged for rows a query *could* have returned but didn't. A future phase can
  re-measure and debit actual cost.
- **`currentlyAvailable` is post-debit and live.** It reflects this query's debit plus all
  time-refill since the caller's previous request, so successive responses show the bucket genuinely
  moving (down on a burst, back up when the caller slows — see §7).

---

## 6. Defaults and configuration

| Knob | Default | Source | Role |
|---|---|---|---|
| `gql.throttle.bucketSize` | `gql.maxCost` (= **1000**) | code default in `GqlEngine` | bucket capacity = `maximumAvailable` |
| `gql.throttleRestoreRate` | **50** / second | code default in `GqlEngine` | `restoreRate` |

These two are **code defaults** read via system property by `GqlEngine` (not currently declared in
`MoquiConf.xml`). The capacity defaults to `maxCost`, so out of the box a caller can spend one
maximum-cost query and must then wait for refill. **1000 capacity / 50 per second** mirrors Shopify's
**Standard** plan, which is why the example envelopes match Shopify's so closely.

**Per-caller overrides.** A `GqlCallerProfile` (resolved from the caller's `userId` via
`GqlCallerProfileMember`) can override the bucket per caller — `bucketSize` and `restoreRate`, along
with `maxCost` and `maxFirst`. A trusted internal app can be given a large, fast
bucket; an external partner a small, slow one. Profile lookup is defensive: any failure falls back to
the global defaults and never fails the request.

> **Retired — `maxInventoryKeys` (as of #35).** This was once an overridable per-caller knob capping
> the number of product×facility keys a bulk-ATP `inventoryLevels` request could pass. It is **no
> longer enforced**: #35 removed the `gql.maxInventoryKeys` governor cap and stopped reading the
> per-caller override. `inventoryLevels` is now a normal view-backed connection (over
> `ProductFacilityInventoryItemView`), governed by the standard connection cost / fan-out rules — the
> same `first`/`last` bounds and cost weights as every other connection — not by a key cap. The
> `GqlCallerProfile.maxInventoryKeys` column still exists but is vestigial (dropping it is a separate
> schema/migration follow-up).

**Tests disable it.** The test JVM sets `gql.throttle.bucketSize` to a huge value (`build.gradle`) so
the shared `"anonymous"` caller can't deplete a 1000-point bucket across dozens of suite queries and
spuriously throttle unrelated tests. `ThrottleE2ETests` enables a small bucket locally (save/restore)
to exercise the real behaviour; `ThrottleGateTests` unit-tests `decide` with injected time. Production
keeps the real default.

---

## 7. A worked trace

Numbers below follow the formulas in §3 exactly, with the default bucket (`maximumAvailable = 1000`,
`restoreRate = 50/s`) and a caller repeating **case study A** from the cost model — a query whose
`requestedQueryCost = 340` (engine-confirmed there).

| # | t (s) | refill since last | available before debit | cost | verdict | `currentlyAvailable` after |
|--:|------:|------------------:|-----------------------:|-----:|---------|---------------------------:|
| 1 | 0.0 | 0 (fresh, full) | 1000 | 340 | allowed | **660** |
| 2 | 2.0 | 2.0 × 50 = 100 | min(1000, 660+100)=760 | 340 | allowed | **420** |
| 3 | 4.0 | 100 | 520 | 340 | allowed | **180** |
| 4 | 4.1 | 0.1 × 50 = 5 | 185 | 340 | **THROTTLED** | 185 *(not debited)* |
| 5 | 7.2 | 3.1 × 50 = 155 | min(1000, 185+155)=340 | 340 | allowed | **0** |

Read row 4: only `185` credits are available, the query costs `340`, so it is rejected with
`THROTTLED` and the bucket is left untouched. The error reports `currentlyAvailable: 185`,
`restoreRate: 50` → the client waits `ceil((340 − 185) / 50) = 4s` and (row 5) the query fits again.

**Observed live (dogfood).** Running the real default bucket through `POST /rest/s1/graphql` with a
repeated `billToCustomer` query, `currentlyAvailable` was seen moving **both directions** across
requests — e.g. `1000 → 968 → 939 → 995 → 996` — dropping when debits outran refill and *recovering*
when the gap between requests let refill outrun the debit. A bucket that only ever went down would be
a counter; one that climbs back up on its own is the live refill at work.

---

## 8. How a client should back off

The response hands the client everything; it does not need to guess.

```
status = response.extensions.cost.throttleStatus
need   = requestedQueryCost − status.currentlyAvailable
if (need > 0) waitSeconds = ceil(need / status.restoreRate)   // sleep, then retry
```

On a `THROTTLED` error the same numbers arrive in `extensions` (§4), so the client can compute the
wait from the failure directly. (The `mantle-shopify-connector` today *reads* `currentlyAvailable` but
does nothing with it — a `//TODO` for backoff; our server emits the data so a consumer can act on it.)
There is no `Retry-After` header yet — the throttle speaks through the cost block.

---

## 9. Limitations & differences from Shopify

- **Per-node, not cluster-fair.** Buckets live in the local JVM Moqui cache (`gql.throttle.bucket`),
  so each node enforces its own bucket. Behind a load balancer with *N* nodes a caller's effective
  capacity is up to *N×* the configured size. Single-node deployments are exact; a future phase can
  move buckets to a shared store for cluster-wide fairness.
- **We debit the estimate, never refund.** Unlike Shopify we do not re-measure `actualQueryCost` after
  execution, so the debit is the (conservative) upper-bound estimate. Callers are never *under*-charged.
- **Anonymous traffic shares one bucket.** All unauthenticated requests key to `"anonymous"`; give
  real callers identities (and profiles) for independent budgets.
- **Buckets are in-memory.** A server restart resets every caller to a full bucket — lenient by
  design (it can never wrongly lock someone out), and acceptable for a per-node rate limit.

---

## 10. Related

- [`query-cost-model.md`](query-cost-model.md) — how `requestedQueryCost` (the currency the throttle
  spends) is computed; §6 there summarizes the throttle as "the last gate".
- [`design.md`](design.md) — governance defense-in-depth; the throttle is layer 5 (per-caller rate
  budget), and the Shopify `throttleStatus` envelope it emits.
- [`STATUS.md`](STATUS.md) — phase status; the live throttle landed in Phase 2 (PR #22).
