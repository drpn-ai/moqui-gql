# Query Cost Model

How `moqui-gql` estimates the cost of a GraphQL query, why the formula is shaped the way it is, and
three worked examples whose numbers are taken from the running engine.

> Authoritative source: `src/main/groovy/org/moqui/gql/GovernorInstrumentation.groovy`
> (`Walk.cost` / `Walk.fieldCost`). Defaults: `MoquiConf.xml` (`gql.*`).
> Every number in the case studies below was produced by the engine against the live
> `hcsd_notnaked` database, not hand-waved.

---

## 1. Why a cost model exists

GraphQL's power is also its danger: one small query string can ask the server to materialize an
enormous result. `orders(first:100){ orderItems(first:100){ … } }` is 19 tokens that fan out to
10,000 rows. A traditional REST endpoint has a fixed, tested shape; an agent-authored GraphQL query
has a *new* shape every time.

So before we run anything, we estimate **how much work the query could cause** and refuse to run it if
that exceeds a budget. The estimate is computed **statically** — by walking the parsed query against
the schema, *before any database access* — so a query that's too expensive **never touches the DB**.

The same number is then **debited from the caller's throttle bucket** (see §6), so cost is both a
hard gate and a rate-limiting currency.

---

## 2. The science

### 2.1 Cost ≈ weighted count of rows the query could touch

The model treats a query as a tree and estimates the number of result nodes it can produce, with two
adjustments grounded in how the executor actually runs:

1. **Lists multiply.** A connection returning `N` items, each carrying a sub-selection that itself
   costs `C`, produces on the order of `N × (1 + C)` work — the engine fetches `N` parents and does
   `C` work per parent. Nesting lists multiplies these factors, which is exactly how a small query
   becomes a "fan-out bomb". The cost model multiplies the same way, so the bomb shows up as a large
   number *before* execution.
2. **Service-backed fields are opaque, so they're never cheap.** A field whose value comes from a
   Moqui service can do arbitrary work the static analyzer can't see. It is therefore charged a flat,
   deliberately-high fixed cost rather than `1`.
3. **Aggregate fields cost more than a scalar but less than a service.** A field backed by a lazy
   LATERAL aggregate (`Order.orderItemCount` = `COUNT(DISTINCT externalId)`) adds a sub-select to the
   query only when selected. Its work is bounded and SQL-shaped — not opaque — so it carries a small
   flat cost (`aggregateFieldCost`) rather than the service fixed cost.

### 2.2 The recursive formula

`cost(selection)` is the sum of `fieldCost(field)` over the fields in that selection. `fieldCost`
depends on the **kind** of the field:

| Field kind | Cost | Notes |
|---|---|---|
| Scalar leaf (`orderId`, `statusId`, …) | `1` | the base unit |
| Single object (by-pk root, single edge, by-identification) | `1 + child` | `child` = cost of its sub-selection |
| **Connection** (Relay list: `orders`, `orderItems`, `shipGroups`) | `eff × (1 + child)` | `eff` = effective page size (see §2.3); root connections add an unindexed-filter penalty (§2.4) |
| **Plain bounded list** (`identifications`, `statuses`, …) | `eff × (1 + child)` | `eff` from `first` or the field's declared default |
| **Aggregate field** (`Order.orderItemCount`) | `aggregateFieldCost` | flat; charged only when selected. A lazy LATERAL `COUNT(DISTINCT externalId)` sub-select — cheaper than a service call, dearer than a scalar |
| **Service-backed field** (capability, no shipped field) | `serviceFixedCost` | flat; the sub-tree is not added (service scalars are leaves). The kind is retained but **no current schema field uses it** (see note below) |
| **Service-backed root** (`inventoryLevels`) | `serviceFixedCost + child` | the service result's fields are still costed |

For a connection, `child` is the cost of the **node** sub-selection — the Relay plumbing
(`edges` / `node` / `pageInfo` / `cursor`) is *not* itself charged; only the entity fields under
`node` are. The total query cost is `cost(root selection)`.

### 2.3 The effective page size `eff`

`eff = clampFirst(first ?: last ?: default)`:

- if the value is missing, ≤ 0, or `> maxFirst`, it becomes **`maxFirst`** (default **100**);
- otherwise it's the requested value.

Clamping for *cost* is intentional: a query asking `first: 5000` is **separately rejected** with
`FIRST_TOO_LARGE`, but its cost is computed with `eff = 100` so the cost number stays meaningful
rather than astronomically inflated by an already-illegal argument.

### 2.4 Constants and adjustments

| Constant | Default | Where | Role |
|---|---|---|---|
| `maxCost` | `1000` | `gql.maxCost` | the gate: `cost > maxCost` → `COST_EXCEEDED` |
| `maxFirst` | `100` | `gql.maxFirst` | clamp ceiling for `eff` |
| `serviceFixedCost` | `25` | `gql.serviceFixedCost` | flat cost of a service-backed field/root |
| `aggregateFieldCost` | `5` | `gql.aggregateFieldCost` | flat cost of a selected aggregate field (lazy LATERAL sub-select) |
| `unindexedFilterPenalty` | `50` | `gql.unindexedFilterPenalty` | added once **per** declared `query:` term that filters an **unindexed** column |
| `COST_CEILING` | `100,000,000` | code | saturation cap (see §2.5) |

**Unindexed-filter penalty.** Filtering on a non-indexed column means a table scan. For each declared
search term in a root connection's `query:` whose column is not index-backed, we add
`unindexedFilterPenalty` to that connection's cost. (Phase-1 choice: penalize, not hard-reject — the
OMS legitimately exposes common-but-unindexed keys like `statusId` and `orderDate`.)

### 2.5 Saturation (overflow safety)

Every intermediate sum and product is passed through `sat(v)`:

```
sat(v) = (v < 0) ? COST_CEILING : min(v, COST_CEILING)
```

A pathological deeply-nested query could otherwise overflow a 64-bit accumulator and **wrap negative**,
sneaking under `maxCost`. Saturation makes cost **monotonic and non-negative**: a more expensive query
can only ever score higher (or hit the ceiling), never lower. The ceiling (100M) is far above
`maxCost` (1000), so it never affects a legitimate verdict — it only defangs adversarial extremes.

### 2.6 When and where it runs

Cost is computed in graphql-java's `beginExecuteOperation` hook — after the query is parsed and
validated and its variables are coerced, but **before any data fetcher runs**. The walk uses the real
argument values (including variables). Because it precedes fetching, a rejected query does no DB work.

The computed value is surfaced to the client as
`extensions.cost.requestedQueryCost` (and `actualQueryCost`, equal to it in phase 1 — we do not
re-measure after execution).

---

## 3. Case study A — a real, allowed query (cost **240**)

A page of orders with an aggregate field and a nested item connection:

```graphql
query {
  orders(first: 5) {
    edges { node {
      orderId
      orderName
      orderItemCount                    # aggregate (lazy LATERAL COUNT DISTINCT)
      orderItems(first: 10) {
        edges { node { orderItemSeqId productId quantity } }
      }
    } }
  }
}
```

Compute **bottom-up**:

| Step | Node | Rule | Cost |
|---|---|---|---|
| 1 | `OrderItem` node `{ orderItemSeqId, productId, quantity }` | 3 scalars | `3` |
| 2 | `orderItems(first:10)` | connection: `eff=10`, `child=3` → `10 × (1+3)` | `40` |
| 3 | `Order` node fields | `orderId`(1) + `orderName`(1) + `orderItemCount`(**5**, aggregate) + `orderItems`(40) | `47` |
| 4 | `orders(first:5)` | connection: `eff=5`, `child=47` → `5 × (1+47)` | **`240`** |

**Verdict:** `240 ≤ maxCost (1000)` → **allowed**. The engine returns
`extensions.cost.requestedQueryCost = 240`, and 240 points are debited from the caller's throttle
bucket. Note the aggregate field (`orderItemCount`) contributes its flat `5` only because it was
selected — when omitted, no sub-select is added and the field costs nothing. (A *service-backed*
field would instead contribute the flat `serviceFixedCost` of `25`, but no shipped schema field
currently uses that kind — see §2.2.)

*(Engine-confirmed: `requestedQueryCost = 240`.)*

---

## 4. Case study B — the fan-out bomb (cost **20,100**, rejected)

The same structure, but both page sizes pushed to the cap and the selection trimmed to one scalar so
it *looks* tiny:

```graphql
query {
  orders(first: 100) {
    edges { node {
      orderItems(first: 100) {
        edges { node { orderId } }
      }
    } }
  }
}
```

| Step | Node | Rule | Cost |
|---|---|---|---|
| 1 | `OrderItem` node `{ orderId }` | 1 scalar | `1` |
| 2 | `orderItems(first:100)` | `eff=100`, `child=1` → `100 × (1+1)` | `200` |
| 3 | `Order` node `{ orderItems }` | just the connection | `200` |
| 4 | `orders(first:100)` | `eff=100`, `child=200` → `100 × (1+200)` | **`20,100`** |

**Verdict:** `20,100 > maxCost (1000)` → rejected, pre-execution, with

```json
{ "errors": [ { "message": "query cost 20100 exceeds max 1000",
  "extensions": { "code": "COST_EXCEEDED", "estimatedCost": 20100, "maxCost": 1000 } } ],
  "data": null }
```

Two 19-token connections nested two deep produce a 20,100-point estimate — the multiplication in §2.1
is what catches it, and nothing reaches the database. The throttle bucket is **not** debited, because
the query never executes.

*(Engine-confirmed: `requestedQueryCost = 20100`, `code = COST_EXCEEDED`.)*

---

## 5. Case study C — a by-pk lookup (cost **4**)

```graphql
query Q($id: ID!) { order(orderId: $id) { orderId orderName statusId } }
```

| Step | Node | Rule | Cost |
|---|---|---|---|
| 1 | `Order` node `{ orderId, orderName, statusId }` | 3 scalars | `3` |
| 2 | `order(orderId:)` | single object: `1 + child` → `1 + 3` | **`4`** |

A point read costs `4`. With `maxCost = 1000` and a default bucket of `1000`, a caller can run ~250
such reads back-to-back before the bucket needs to refill (at `restoreRate` points/second).

*(Engine-confirmed: `requestedQueryCost = 4`.)*

---

## 6. How cost relates to the other gates

Cost is one of several pre-execution checks in the same AST walk; they are independent and all report
stable `extensions.code`s with `data: null`:

| Gate | Trigger | Code |
|---|---|---|
| **Cost** | `cost > maxCost` | `COST_EXCEEDED` |
| Depth | entity-nesting depth `> maxDepth` (Relay plumbing skipped) | `DEPTH_EXCEEDED` |
| First required / too large | connection missing `first/last`, or `first/last > maxFirst` | `FIRST_REQUIRED` / `FIRST_TOO_LARGE` |
| Filter grammar | undeclared key / disallowed comparator in `query:` | `FIELD_NOT_FILTERABLE` / `OPERATOR_NOT_ALLOWED` |
| Batch blast radius | service-field fan-out, or `inventoryLevels` pairs, over the limit | `BATCH_LIMIT_EXCEEDED` |

**Throttle (the last gate).** If the query passes every static check, its cost is debited from a
per-caller **token bucket** (`maximumAvailable` points, refilling at `restoreRate`/second). If the
bucket can't cover the cost, the query is rejected with `THROTTLED` and **not** debited. Either way the
response reports the live bucket in `extensions.cost.throttleStatus`. Caller **profiles**
(`GqlCallerProfile`) can override `maxCost`, `maxFirst`, and the bucket size/rate per caller. The full
throttle model — refill math, gate ordering, defaults, and a worked trace — is in [`throttle.md`](throttle.md).

So the cost number does double duty: a **hard ceiling** per query (`maxCost`) and a **spend** against a
replenishing budget (throttle).

---

## 7. Tuning and limitations

- **Tuning.** Raise/lower `gql.maxCost` to widen/narrow what a single query may do; `gql.maxFirst`
  bounds every page; `gql.serviceFixedCost` and `gql.unindexedFilterPenalty` shape how harshly
  service-backed and unindexed access are weighted. All are `default-property` values overridable per
  deployment, and several are overridable per caller via `GqlCallerProfile`.
- **It's an upper-bound estimate, not a measurement.** Cost assumes every list returns its full `eff`
  rows and every branch is taken. A query that *could* be expensive is charged as if it *is*. Phase 1
  does not re-measure actual rows after execution (`actualQueryCost == requestedQueryCost`); a future
  phase can debit the bucket by measured cost instead.
- **Service cost is a flat proxy.** `serviceFixedCost` says "not cheap", not "exactly this expensive".
  The real bound on a service-backed field is the runtime guard set (`queryTimeout`, per-level row
  caps, wall-clock deadline) plus the batch-key cap — cost just keeps it out of the "free" bucket.
- **Saturation hides true magnitude past the ceiling.** Above `COST_CEILING` (100M) the reported number
  is clamped; the query is rejected anyway, so the exact astronomical value is not interesting.
