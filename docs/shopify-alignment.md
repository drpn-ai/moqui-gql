# GraphQL Spec & Shopify Admin Alignment

**Goal:** make moqui-gql's schema and query language **as close to Shopify Admin GraphQL as
possible**, and conformant with the GraphQL spec.
**Method:** validated against live `shopify.dev` Admin API docs (2026-06). Full schema
introspection needs the Shopify connector authenticated — noted where exhaustive field/enum
parity would need it. The conventions below are confirmed from the docs.

---

## Part 1 — Where we already match Shopify ✓

- **`extensions.cost` shape — exact match.** Shopify returns
  `{ requestedQueryCost, actualQueryCost, throttleStatus: { maximumAvailable, currentlyAvailable, restoreRate } }`.
  Our design emits the identical shape. ✓
- **Relay cursor connections** — `edges { cursor node }` + `pageInfo` + `first/after`. ✓ (gaps below)
- **HTTP 200 + `errors[]` with `extensions.code`** even on failure — Shopify's model; ours matches. ✓
- **Read-only curated schema + introspection** — same posture. ✓
- **External-id lookup exists in Shopify too** — Shopify has `orderByIdentifier`; our Q5 lookup is
  the same idea. ✓ (align naming below)

---

## Part 2 — Alignment scorecard

| Convention | Shopify Admin (confirmed) | GraphQL spec | Ours today | Gap → action |
|---|---|---|---|---|
| **PageInfo** | `hasNextPage!`, `hasPreviousPage!`, `startCursor`, `endCursor`; edge `cursor!`, `node!` | Relay Cursor Connections | `hasNextPage`, `endCursor` only | **Add `hasPreviousPage` + `startCursor`** (full Relay PageInfo) |
| **Connection args** | `first, after, last, before` | Relay | `first, after` | **Add `last`/`before`** (backward paging) to match |
| **Global ID** | `id: ID!` = `gid://shopify/Order/123`; `Node`; `node(id)` | Relay Global Object ID | raw `id` | **D-B RESOLVED — keep raw entity ids** (no GID/Node) |
| **Filtering** | `query: String` **search-syntax DSL** + comparators | (not specified) | — | **D-A RESOLVED — adopt `query:` string**, our field names as keys, declared keys/comparators (Q3) |
| **Sorting** | `sortKey: <Type>SortKeys` **enum** + `reverse` (single key) | (not specified) | — | **D-C RESOLVED — adopt `sortKey`+`reverse`** (enum values = our fields) |
| **Money** | `MoneyBag`/`MoneyV2`, `...Set` naming | custom scalars allowed | `grandTotal` + `currencyUomId` | **KEEP OURS** — our money model, not Shopify's type/naming |
| **Scalars** | `DateTime`, `Decimal`, `URL`, `HTML`, `ID` | custom scalars allowed | ISO strings, JSON numbers | **Adopt `DateTime`, `Decimal`** (neutral scalar types, not Shopify branding) |
| **Status fields** | `displayFinancialStatus`, `displayFulfillmentStatus` enums | enums | raw `statusId` only (no display-status enum field shipped) | **KEEP OURS** — raw `statusId` only (no display-status enum field shipped) |
| **Field naming** | `createdAt`, `name`, `lineItems`, `customer`, `billingAddress`… | camelCase | `orderDate`, `orderName`, `orderItems`, `billToCustomer`… | **KEEP OURS** (decision: our data model names — D-D = keep) |
| **External-id query** | `orderByIdentifier(identifier:)` | — | `order(externalId:)`, `orderByIdentification(...)` | **KEEP OURS** — `order(externalId:)` + `orderByIdentification` (our naming) |
| **Errors / throttle** | `errors[]` + `extensions.code`; throttle code `THROTTLED`; single-query-too-costly → max-cost error | — | `errors[]` + codes (`COST_EXCEEDED`…) | **Match codes/messages** (`THROTTLED`, max-cost wording) |
| **Cost** | `extensions.cost` (above) | — | identical | ✓ keep |

---

## Part 3 — The decisions "Shopify-as-close-as-possible" forces

### D-A — Filtering: Shopify's `query:` string DSL vs our structured `filter` (THE big one)

Shopify filters with a **single search string**: `orders(query: "financial_status:paid created_at:>2026-05-01 name:#1001")`. It's a documented mini-grammar (terms, comparators `>=`/`<=`, connectives AND/OR, modifiers NOT) with a per-type list of searchable fields. It's backed by their **search index**.

We had initially specced (Q1) **DB-backed** with a (Q3) **structured, declared filter input**
(the rejected alternative): `orders(filter: { statusId: { in: [...] }, orderDate: { between: [...] } })`.

**This was a genuine fork.** Three options:
1. **Adopt Shopify's `query:` string** — parse it server-side into DB conditions, **restricted to
   declared fields + operators** (Q3 becomes "which search terms are allowed"). Shopify-identical
   ergonomics, still DB-backed and governed. Cost: build a search-syntax parser; harder to
   statically cost-analyze (operators are inside a string, not the AST); **harder for AI agents to
   emit correctly** than a typed input.
2. **Keep structured `filter`/`where`** — diverges from Shopify ergonomics, but is cleaner GraphQL,
   trivially cost-analyzable (operators are explicit in the AST), and **far easier for AI agents**
   (typed, introspectable, no string grammar to learn). This is the modern GraphQL norm
   (Hasura/Postgraphile). Conflicts with "as close to Shopify as possible."
3. **Hybrid** — support `query:` string AND a structured `filter`. Most surface area; most to build
   and govern.

**Trade at the heart of it:** Shopify-familiarity (option 1) vs AI-agent-friendliness +
cost-analyzability (option 2). Our top consumer is AI agents, who do *better* with typed inputs —
so the very thing that makes us Shopify-like makes us slightly worse for our #1 user.

**RESOLVED (2026-06-03): Option 1 — adopt Shopify's `query:` string DSL.** Filtering uses the
Shopify search-syntax string. Q3 declare-and-control now governs **the query grammar**: only
declared **search keys** are accepted, each with a declared comparator set (`:` eq, `:a,b` in,
`:>`/`:>=`/`:<`/`:<=` and ranges for dates/numbers). Unknown key → `FIELD_NOT_FILTERABLE`;
disallowed comparator → `OPERATOR_NOT_ALLOWED`. Backed by the DB (Q1), parsed server-side. We
accept the agent-ergonomics cost and mitigate it by publishing the per-type searchable-field list
in the schema/SDL so agents can introspect what's allowed.

### D-B — Global IDs (`gid://`) + `Node` interface

Shopify's `id` is an opaque global id `gid://shopify/Order/123`; everything implements `Node`;
`node(id)`/`nodes(ids)` refetch any object; the raw key is exposed as `legacyResourceId`.

- **Adopt it:** Shopify-identical; enables generic refetch + client cache normalization (Apollo);
  `node(id)` is a clean single entry point. Cost: ids become opaque; resolvers map `gid ↔ entity
  PK`; **our composite PKs** (e.g. `OrderItem` = orderId+orderItemSeqId) must encode into one GID
  (Shopify's are single-id — this is a real wrinkle for us).
- **Keep raw ids:** simpler, our ids are human-meaningful; but un-Shopify and no generic `node()`.
- **RESOLVED (2026-06-03): keep raw entity ids.** No `gid://`, no `Node` interface, no
  `node`/`nodes`. `id` stays the human-meaningful entity key (single or composite). Simpler, no
  GID encode/decode, ids are readable in logs/feeds. We forgo Shopify-identical refetch/cache
  normalization — an accepted divergence. Lookups: `order(id:)`, `order(externalId:)`,
  `orderByIdentifier(...)`.

### D-C — Sorting: `sortKey` enum + `reverse` (adopt)

Shopify: `orders(sortKey: ORDER_DATE, reverse: true)` — a curated enum, single key, plus
`RELEVANCE` when a `query` is present. The rejected alternative was a `sort: [{field, dir}]` array.
**RESOLVED: adopt `sortKey` enum + `reverse`** (enum values are our fields, e.g. `ORDER_DATE`).
Internal cursor tiebreaker (PK) stays an implementation detail; multi-sort dropped to match.

### D-D — Field naming: **KEEP OUR DATA MODEL** (RESOLVED 2026-06-03)

**We do NOT rename fields to Shopify's.** Consumers should see Maarg's data model — `orderId`,
`orderDate`, `statusId`, `grandTotal`/`currencyUomId`, `orderItems`, `orderItemSeqId`, `unitPrice`,
`shipGroups`, `paymentPreferences`, `facilityName`, etc. We adopt **only the
Shopify query *language*** (the `query:` search string, `sortKey`+`reverse`, Relay
connections/cursors, the cost/error envelope), not field/type naming.

Consequently we **do not** adopt: Shopify field names (`createdAt`/`lineItems`/`customer.displayName`),
`MoneyV2`/`MoneyBag`/`...Set` money structure (we use `grandTotal`+`currencyUomId`), display-status
enums (`displayFulfillmentStatus` — we keep raw `statusId` with no display-status enum), or the
`orderByIdentifier` name (we use `order(externalId:)` + `orderByIdentification`).

---

## Part 4 — What we adopt from Shopify (query language only) vs keep ours

**Adopt (Shopify query language / protocol):**
1. **`query:` search-string filtering** (D-A) — our field names as keys, declared keys+comparators (Q3).
2. **`sortKey: <Type>SortKeys` enum + `reverse: Boolean`** (D-C) — enum values are our fields (e.g. `ORDER_DATE`).
3. **Full Relay connections** — `edges { cursor node }`, `pageInfo { hasNextPage hasPreviousPage startCursor endCursor }`, `first/after/last/before`.
4. **Custom scalars** `DateTime`, `Decimal` (neutral types).
5. **`extensions.cost` envelope** (already identical) + **error codes/messages** (`THROTTLED`, max-cost wording).

**Keep ours (data model):**
6. **Field/type names** — our OMS model (`orderId`, `orderDate`, `statusId`, `orderItems`, …) — D-D.
7. **Money** — `grandTotal` + `currencyUomId` (not `MoneyBag`/`...Set`).
8. **Status** — raw `statusId`, no display-status enum field (no `displayFulfillmentStatus`).
9. **IDs** — raw entity keys, no `gid://`/`Node`/`node()` (D-B).
10. **External-id lookup** — `order(externalId:)` + `orderByIdentification(...)` (our naming).

---

## Part 5 — GraphQL spec conformance checklist

`graphql-java` gives us the execution engine, so most of this is free; the checklist is to *use* it
idiomatically in the schema + examples:

- [ ] **Named operations + variables** in examples (`query Orders($status: String!) { … }`) — our
      examples currently use anonymous `{ … }`; Shopify docs always name + parameterize. Update.
- [ ] **Fragments** for repeated selections (e.g. an `OrderSummary` fragment).
- [ ] **Standard scalars** `Int/Float/String/Boolean/ID` + declared custom scalars (`DateTime`, `Decimal`, `Money*`).
- [ ] **Enums** SCREAMING_SNAKE; types PascalCase; fields/args camelCase (we comply).
- [ ] **Nullability** deliberate — non-null (`!`) on always-present fields (ids, counts, pageInfo bools).
- [ ] **Introspection** on (SDL published) — already planned.
- [ ] **Directives** — support `@skip`/`@include` (spec built-ins; graphql-java provides).
- [ ] **200 OK + `errors[]`** on failure (not HTTP 4xx for query errors) — match Shopify.
- [ ] **Connection/edge type naming** — `OrderConnection`, `OrderEdge` (graphql-java + our builder).

---

## Headline

**We adopt Shopify's query *language*, not its field names.** Consumers see Maarg's data model
(`orderId`, `orderDate`, `statusId`, `grandTotal`, `orderItems`); the
*ergonomics* are Shopify's: `query:` search-string filtering (D-A), `sortKey`+`reverse`, full Relay
connections + cursors, `DateTime`/`Decimal` scalars, and the `extensions.cost`/error envelope
(already identical). **IDs stay raw entity keys** (D-B — no `gid://`/`Node`).

Resolved 2026-06-03: D-A `query:` string · D-B raw ids · D-C `sortKey`+`reverse` · **D-D keep our
field names**. Applied across `examples.md`, `design.md`, and `requirements.md`.
