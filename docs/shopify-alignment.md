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
| **Global ID** | `id: ID!` = `gid://shopify/Order/123`; `Node` interface; `node(id)`, `nodes(ids)`; raw id via `legacyResourceId` | Relay Global Object ID | raw `id: "10001"` | **Decision D-B** — adopt GID + `Node` or keep raw |
| **Filtering** | `query: String` **search-syntax DSL** (`financial_status:paid created_at:>2026-05-01 name:#1001`), documented searchable fields + comparators | (not specified) | structured `filter: { field: { op: val } }` (Q3) | **Decision D-A** — the big one |
| **Sorting** | `sortKey: <Type>SortKeys` **enum** + `reverse: Boolean` (single key) | (not specified) | `sort: [{ field, dir }]` (multi) | **Decision D-C** — adopt `sortKey`+`reverse` |
| **Money** | `MoneyV2 { amount: Decimal!, currencyCode: CurrencyCode! }`; `MoneyBag { shopMoney, presentmentMoney }`; fields named `...Set` (e.g. `totalPriceSet`) | custom scalars allowed | `grandTotal: 129.00` + `currency` | **Adopt `MoneyV2`/`MoneyBag`** + `...Set` naming |
| **Scalars** | `DateTime` (ISO-8601), `Decimal`, `URL`, `HTML`, `UnsignedInt64`, `ID` | custom scalars allowed | ISO string dates, JSON numbers | **Adopt `DateTime`, `Decimal`** custom scalars |
| **Status fields** | `displayFinancialStatus`, `displayFulfillmentStatus` — curated **enums** | enums | raw `statusId` + computed `fulfillmentStatus` | **Add curated display enums**; keep raw statusId optional |
| **Field naming** | `createdAt`, `updatedAt`, `name`, `lineItems`, `customer`, `shippingAddress`, `billingAddress`, `tags`, `note`, `email` | camelCase fields | `placedDate`, `items`, `customerName`, `billingAddress`… | **Decision D-D** — adopt Shopify names where concept maps |
| **External-id query** | `orderByIdentifier(identifier: …)` | — | `order(externalId:)`, `orderByIdentification(type,value)` | **Rename to `orderByIdentifier`** for parity |
| **Errors / throttle** | `errors[]` + `extensions.code`; throttle code `THROTTLED`; single-query-too-costly → max-cost error | — | `errors[]` + codes (`COST_EXCEEDED`…) | **Match codes/messages** (`THROTTLED`, max-cost wording) |
| **Cost** | `extensions.cost` (above) | — | identical | ✓ keep |

---

## Part 3 — The decisions "Shopify-as-close-as-possible" forces

### D-A — Filtering: Shopify's `query:` string DSL vs our structured `filter` (THE big one)

Shopify filters with a **single search string**: `orders(query: "financial_status:paid created_at:>2026-05-01 name:#1001")`. It's a documented mini-grammar (terms, comparators `>=`/`<=`, connectives AND/OR, modifiers NOT) with a per-type list of searchable fields. It's backed by their **search index**.

We chose (Q1) **DB-backed** and (Q3) **structured, declared, operator-controlled** filters:
`orders(filter: { statusId: { in: [...] }, placedDate: { between: [...] } })`.

**This is a genuine fork.** Three options:
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
so the very thing that makes us Shopify-like makes us slightly worse for our #1 user. This is the
decision to make consciously. **OPEN.**

### D-B — Global IDs (`gid://`) + `Node` interface

Shopify's `id` is an opaque global id `gid://shopify/Order/123`; everything implements `Node`;
`node(id)`/`nodes(ids)` refetch any object; the raw key is exposed as `legacyResourceId`.

- **Adopt it:** Shopify-identical; enables generic refetch + client cache normalization (Apollo);
  `node(id)` is a clean single entry point. Cost: ids become opaque; resolvers map `gid ↔ entity
  PK`; **our composite PKs** (e.g. `OrderItem` = orderId+orderItemSeqId) must encode into one GID
  (Shopify's are single-id — this is a real wrinkle for us).
- **Keep raw ids:** simpler, our ids are human-meaningful; but un-Shopify and no generic `node()`.
- **Recommended: adopt GID + `Node`**, expose raw key as `legacyResourceId`, and define a GID
  scheme that encodes composite PKs. **OPEN.**

### D-C — Sorting: `sortKey` enum + `reverse` (adopt)

Shopify: `orders(sortKey: CREATED_AT, reverse: true)` — a curated enum, single key, plus
`RELEVANCE` when a `query` is present. Ours: `sort: [{field, dir}]` (multi-field).
**Recommended: adopt `sortKey` enum + `reverse`** (Shopify-identical). Internal cursor tiebreaker
(PK) stays an implementation detail. Multi-sort is dropped to match (rarely needed). **Low controversy.**

### D-D — Field-naming parity (adopt where concept maps)

Adopt Shopify names where the concept is the same: `createdAt` (←placedDate/entryDate), `lineItems`
(←items), `customer` object (←customerName scalar; expose `customer { displayName }`),
`shippingAddress`/`billingAddress`, `tags`, `note`, `email`, `displayFulfillmentStatus`
(←fulfillmentStatus), `totalPriceSet`/`subtotalPriceSet` (MoneyBag). Keep **OMS-specific** fields
with our names where Shopify has no equivalent: `shipGroups`, `facility`, `picklist`, brokering,
transfer orders, cycle counts. **Low controversy** — maximizes familiarity without misrepresenting
our model.

---

## Part 4 — Concrete change list to become Shopify-shaped

Apply regardless of D-A/D-B (low controversy):
1. **Full Relay PageInfo** — add `hasPreviousPage`, `startCursor`; edges keep `cursor`.
2. **`last`/`before`** connection args (backward pagination).
3. **`sortKey: <Type>SortKeys` enum + `reverse: Boolean`** (drop `sort` array) — D-C.
4. **Money as `MoneyV2`/`MoneyBag`**, fields named `...Set` (`totalPriceSet { shopMoney { amount currencyCode } }`).
5. **Custom scalars** `DateTime`, `Decimal`.
6. **Curated display enums** `displayFulfillmentStatus`, `displayFinancialStatus` (+ keep raw `statusId` if useful).
7. **Shopify field names** where concept maps — D-D.
8. **`orderByIdentifier`** naming for external-id lookup; `node`/`nodes` if D-B adopted.
9. **Error codes/messages** aligned: `THROTTLED` (rate), max-single-query-cost wording.

Gated on decisions:
- **D-A** — `query:` string DSL vs structured `filter` (or hybrid).
- **D-B** — GID + `Node` + `legacyResourceId`, with a composite-PK GID scheme.

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

We already match Shopify on the **cost envelope and error/connection bones**. To get the rest of
the way: **adopt full Relay PageInfo, `sortKey`+`reverse`, `MoneyV2`/`MoneyBag`, `DateTime`/`Decimal`,
display-status enums, and Shopify field names** (all low-controversy — Part 4). Two real decisions
remain and they shape the API the most:

- **D-A filtering** — Shopify's `query:` search string (max familiarity) vs structured `filter`
  (better for AI agents + cost analysis). This trades our #1 consumer's ergonomics against
  Shopify-sameness.
- **D-B global IDs** — adopt `gid://`+`Node` (Shopify-identical, needs a composite-PK GID scheme)
  vs keep raw ids.

Resolve D-A and D-B, and we apply Part 4 across `examples.md` and `design.md`.
