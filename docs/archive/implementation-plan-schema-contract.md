# Schema-Contract Conformance — keep `docs/schema.graphql` honest, test-enforced — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax. Each task is one bite: a failing Spock test first, then the COMPLETE reconciliation, then the test green, then a commit.

**Goal:** Add a Spock test (`SchemaContractTests`) that **fails whenever the built GraphQL schema exposes any type / field / root query / argument / enum value / custom scalar that is NOT documented in `docs/schema.graphql`** (direction: **built ⊆ contract**), and reconcile the current contract so the baseline is green — so the SDL contract can never again silently fall behind the implementation, and every future schema change (including #35/#37/#38/#43) is forced to update the contract in the same PR.

**Architecture:** The built `GraphQLSchema` is obtained from the cached tool factory — `((BuiltSchema) ec.factory.getToolFactory("GraphQL").getInstance()).schema` (no engine/framework change: `BuiltSchema.schema` is a public field, schema built once at startup). `docs/schema.graphql` is read via `ec.resource.getLocationText("component://moqui-gql/docs/schema.graphql", false)` and parsed with graphql-java's `SchemaParser` into a `TypeDefinitionRegistry`. The test walks the built schema's object types, fields, root queries, arguments, enums, and custom scalars and asserts each is present in the contract registry — **presence only**. It deliberately ignores: nullability (`!`), list-vs-element nullability (built edges are nullable, the contract uses `!`), directives (`@search`/`@cost`/`@service` are doc-only and never appear in the built schema), descriptions, default values, scalar field *types* (the builder defaults untyped fields to `String`; the contract documents them with their semantic type), and field order. The reverse direction (contract ⊆ built) is intentionally **NOT** asserted — the contract is a curated, human-readable **superset** design surface that documents future/unbuilt API (picklists, transfers, cycle counts, routing, etc.).

**Tech stack:** graphql-java 25.0 (`graphql.schema.idl.SchemaParser`, `TypeDefinitionRegistry`; `GraphQLSchema.getAllTypesAsList()` / `getQueryType()`; `graphql.language.ObjectTypeDefinition` / `EnumTypeDefinition`), Spock vs `hcsd_notnaked`, Moqui `ResourceFacade`.

**Tracking:** (new issue — foundational; this is **step 0** of the contract-first execution, run before #35/#37/#38/#43). **No DB/index change. No engine or framework change.** This is the first consumer of `graphql.schema.idl.*` in the component.

---

## Background — why this plan exists

`docs/schema.graphql` is hand-authored and has **drifted**: the built artifact (`graphql/OmsSchema.gql.xml`) exposes a `return` root, a `facilities` connection root, and ~12 fields that the contract never documented (see Task 2). Nothing today catches this. Decision #3 of epic #46 (contract-first) only works if "the built schema matches the contract" is *executable*. This test is that executable guarantee, and Task 1–3 reconcile the existing drift so the baseline passes.

**Scope boundary:** Tasks 1–3 reconcile the contract to the **current** built surface (so the test is green now). They do **not** pre-add the planned-feature surface (#35 `inventoryLevels`-as-connection, #37 `orderItemCount`, #43 ShipGroup edges) — those features each change the built schema *and* update `docs/schema.graphql` in their own PR, and this test is what forces them to.

---

## File structure

| File | Change |
|---|---|
| `src/test/groovy/SchemaContractTests.groovy` | **NEW** — the conformance test (shared `setupSpec` + helpers + 3 assertion methods + 1 optional SDL-dump diagnostic). |
| `src/test/groovy/MoquiSuite.groovy` | add `SchemaContractTests.class` to `@SelectClasses` (the gradle `test` task only runs `*MoquiSuite`). |
| `docs/schema.graphql` | reconcile to the current built surface: add the `return` + `facilities` roots, the `FacilityConnection`/`FacilityEdge` types, the `FacilitySortKey` enum (Task 1), and the 12 undocumented fields on `OrderStatus`/`ShipGroup`/`Party`/`Shipment`/`Return`/`Product`/`Facility` (Task 2). |
| `docs/design.md` | append a "Schema-contract maintenance" section documenting the built ⊆ contract guardrail + the per-PR workflow (Task 5). |
| `docs/STATUS.md` | note the conformance test exists and the contract is enforced (Task 5). |

---

## Task 1: Conformance test skeleton + root-query conformance (RED: `return`, `facilities`)

**Files:** `src/test/groovy/SchemaContractTests.groovy` (NEW), `src/test/groovy/MoquiSuite.groovy`, `docs/schema.graphql`

- [ ] **Step 1 — write the test class with shared setup, helpers, and the root-query assertion.** Create `src/test/groovy/SchemaContractTests.groovy` (default package, no `package` line — matches every other test):

```groovy
import spock.lang.Specification
import spock.lang.Shared
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.gql.BuiltSchema

import graphql.schema.GraphQLSchema
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLArgument
import graphql.schema.GraphQLNamedType
import graphql.schema.GraphQLScalarType
import graphql.schema.GraphQLEnumType
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.TypeDefinitionRegistry
import graphql.language.ObjectTypeDefinition
import graphql.language.EnumTypeDefinition

/** Conformance guardrail: the BUILT GraphQL schema must be fully documented in docs/schema.graphql.
 *  Direction: built ⊆ contract (every type/field/root/arg/enum-value/custom-scalar the engine exposes
 *  is declared in the contract). The contract is intentionally a SUPERSET (design surface), so the
 *  reverse is NOT asserted. Ignored: nullability (!), directives (@search/@cost/@service are doc-only),
 *  descriptions, default values, scalar field types (builder defaults untyped fields to String), order.
 *  When a method fails it prints the exact undocumented elements: add each to docs/schema.graphql. */
class SchemaContractTests extends Specification {
    @Shared ExecutionContext ec
    @Shared GraphQLSchema built
    @Shared TypeDefinitionRegistry contract

    static final Set<String> BUILTIN_SCALARS = ['Int','Float','String','Boolean','ID'] as Set

    def setupSpec() {
        ec = Moqui.getExecutionContext(); ec.artifactExecution.disableAuthz()
        built = ((BuiltSchema) ec.factory.getToolFactory("GraphQL").getInstance()).schema
        String sdl = ec.resource.getLocationText("component://moqui-gql/docs/schema.graphql", false)
        contract = new SchemaParser().parse(sdl)
    }
    def cleanupSpec() { if (ec != null) { ec.artifactExecution.enableAuthz(); ec.destroy() } }

    private ObjectTypeDefinition contractObject(String name) {
        def opt = contract.getType(name, ObjectTypeDefinition.class)
        return opt.isPresent() ? opt.get() : null
    }

    def "every built root query is declared in the contract Query type (names + arguments)"() {
        when:
        List<String> missing = []
        ObjectTypeDefinition cQuery = contractObject("Query")
        assert cQuery != null : "docs/schema.graphql has no `type Query`"
        Set<String> cRoots = cQuery.fieldDefinitions.collect { it.name } as Set
        Map<String, Set<String>> cArgs = [:]
        cQuery.fieldDefinitions.each { fd -> cArgs[fd.name] = (fd.inputValueDefinitions.collect { it.name } as Set) }
        built.getQueryType().fieldDefinitions.each { GraphQLFieldDefinition f ->
            if (!cRoots.contains(f.name)) { missing << "Query.${f.name} (root query)"; return }
            f.arguments.each { GraphQLArgument a ->
                if (!(cArgs[f.name]?.contains(a.name))) missing << "Query.${f.name}(${a.name}:) argument"
            }
        }
        if (!missing.isEmpty()) System.err.println("[schema-contract] undocumented built roots/args:\n  " + missing.join("\n  "))
        then:
        missing == []
    }
}
```

- [ ] **Step 2 — register it** in `src/test/groovy/MoquiSuite.groovy` `@SelectClasses` (append to the list, before the closing `])`):

```groovy
        BillToCustomerTests.class, SchemaContractTests.class ])
```

- [ ] **Step 3 — run, expect FAIL:** `./gradlew :runtime:component:moqui-gql:test --tests MoquiSuite` → the method fails; stderr prints exactly:
```
[schema-contract] undocumented built roots/args:
  Query.return (root query)
  Query.facilities (root query)
```
(`return` is a by-pk root `return(returnId, externalId): Return`; `facilities` is a list/connection root — both absent from the contract `Query`.)

- [ ] **Step 4 — reconcile the contract roots + the `facilities` wrapper/enum types** in `docs/schema.graphql`.

  (a) In `type Query`, add the singular `return` root immediately above the `returns(...)` field (in the `# ---- Returns ----` group):
```graphql
  return(returnId: ID, externalId: String): Return
```
  (b) In `type Query`, immediately after the `facility(facilityId: ID, externalId: String): Facility` line (in `# ---- Facility & store ----`), add the `facilities` connection root:
```graphql
  "List facilities. `query:` keys: facilityId(eq,in) facilityTypeId(eq,in) externalId(eq,in) parentFacilityId(eq,in)."
  facilities(query: String, sortKey: FacilitySortKey = FACILITY_NAME, reverse: Boolean = false,
    first: Int, after: String, last: Int, before: String): FacilityConnection!
    @search(keys: ["facilityId","facilityTypeId","externalId","parentFacilityId"])
```
  (c) In the sort-key enums block, add (next to `ProductSortKey`):
```graphql
enum FacilitySortKey { FACILITY_NAME FACILITY_ID }
```
  (d) In the `# Facility & store` types section, add the connection + edge types (next to the other `*Connection`/`*Edge` pairs):
```graphql
type FacilityConnection { edges: [FacilityEdge!]!  pageInfo: PageInfo! }
type FacilityEdge { cursor: String!  node: Facility! }
```

- [ ] **Step 5 — run, expect PASS:** `./gradlew :runtime:component:moqui-gql:test --tests MoquiSuite` → the root-query method is green (no more undocumented roots/args).

- [ ] **Step 6 — commit:**
```bash
git add src/test/groovy/SchemaContractTests.groovy src/test/groovy/MoquiSuite.groovy docs/schema.graphql
git commit -m "test(gql): schema-contract conformance — built roots ⊆ contract; document return + facilities (#49)"
```

---

## Task 2: Object-type & field conformance (RED: 12 undocumented fields)

**Files:** `src/test/groovy/SchemaContractTests.groovy`, `docs/schema.graphql`

- [ ] **Step 1 — add the types+fields assertion method** to `SchemaContractTests`:

```groovy
    def "every built object type and its fields are declared in the contract (built ⊆ contract)"() {
        when:
        List<String> missing = []
        built.getAllTypesAsList().each { GraphQLNamedType t ->
            if (!(t instanceof GraphQLObjectType) || t.name.startsWith("__")) return
            GraphQLObjectType ot = (GraphQLObjectType) t
            ObjectTypeDefinition cd = contractObject(ot.name)
            if (cd == null) { missing << "type ${ot.name}"; return }
            Set<String> cFields = cd.fieldDefinitions.collect { it.name } as Set
            ot.fieldDefinitions.each { GraphQLFieldDefinition f ->
                if (!cFields.contains(f.name)) missing << "${ot.name}.${f.name}"
            }
        }
        if (!missing.isEmpty()) System.err.println("[schema-contract] undocumented built types/fields:\n  " + missing.join("\n  "))
        then:
        missing == []
    }
```

- [ ] **Step 2 — run, expect FAIL:** `./gradlew :runtime:component:moqui-gql:test --tests MoquiSuite` → stderr prints exactly these 12 undocumented fields (built in `graphql/OmsSchema.gql.xml`, missing from the contract):
```
[schema-contract] undocumented built types/fields:
  OrderStatus.orderItemSeqId
  ShipGroup.contactMechId
  Party.partyTypeId
  Party.roleTypeId
  Party.statusId
  Shipment.shipmentTypeId
  Shipment.primaryOrderId
  Shipment.shipmentMethodTypeId
  Shipment.estimatedShipDate
  Shipment.externalId
  Return.returnHeaderTypeId
  Return.fromPartyId
  Return.entryDate
  Return.externalId
  Return.currencyUomId
  Product.brandName
  Facility.parentFacilityId
```
(17 fields total — the prose said "12+"; document every line the test prints.)

- [ ] **Step 3 — reconcile the field gaps** in `docs/schema.graphql`. Apply each exactly (semantic types chosen to match the OMS data model; the test ignores types, but use these for a correct contract):

  - `type OrderStatus` — add `orderItemSeqId: ID`:
```graphql
type OrderStatus { statusId: ID!  statusDatetime: DateTime  orderItemSeqId: ID }
```
  - `type ShipGroup` — add `contactMechId: ID` (to the existing field list):
```graphql
  shipGroupSeqId: ID!  shipmentMethodTypeId: ID  carrierPartyId: ID  trackingCode: String
  facilityId: ID  contactMechId: ID  facility: Facility  picklistId: ID  picker: Person
```
  - `type Party` — add `partyTypeId`, `roleTypeId`, `statusId`:
```graphql
type Party {
  partyId: ID!  partyTypeEnumId: ID  partyTypeId: ID  roleTypeId: ID  statusId: ID
  externalId: String  firstName: String  lastName: String  emailAddress: String
  "Service-backed convenience: number of orders placed by this party."
  orderCount: Int @service(name: "get#PartyOrderCount") @cost(weight: 25)
}
```
  - `type Shipment` — add `shipmentTypeId`, `primaryOrderId`, `shipmentMethodTypeId`, `estimatedShipDate`, `externalId`:
```graphql
type Shipment {
  shipmentId: ID!  shipmentTypeId: ID  statusId: ID!  primaryOrderId: ID
  originFacilityId: ID  destinationFacilityId: ID  shipmentMethodTypeId: ID
  shippedDate: DateTime  estimatedShipDate: DateTime  externalId: String
  shipmentPackages(first: Int = 50): [ShipmentPackage!]!
  shipmentRouteSegments(first: Int = 10): [ShipmentRouteSegment!]!
}
```
  - `type Return` — add `returnHeaderTypeId`, `fromPartyId`, `entryDate`, `externalId`, `currencyUomId`:
```graphql
type Return {
  returnId: ID!  statusId: ID!  returnHeaderTypeId: ID  orderId: ID  fromPartyId: ID
  returnDate: DateTime  entryDate: DateTime  externalId: String  currencyUomId: ID
  identifications(first: Int = 5): [ReturnIdentification!]!
  returnItems(first: Int, after: String, last: Int, before: String): ReturnItemConnection!
}
```
  - `type Product` — add `brandName: String`:
```graphql
  primaryProductCategoryId: ID  brandName: String  isVirtual: Boolean  isVariant: Boolean
```
  - `type Facility` — add `parentFacilityId: ID`:
```graphql
  facilityId: ID!  facilityName: String  facilityTypeId: ID  parentFacilityId: ID  externalId: String
```

- [ ] **Step 4 — run, expect PASS:** `./gradlew :runtime:component:moqui-gql:test --tests MoquiSuite` → the types+fields method is green.

- [ ] **Step 5 — commit:**
```bash
git add src/test/groovy/SchemaContractTests.groovy docs/schema.graphql
git commit -m "test(gql): schema-contract — built fields ⊆ contract; document 17 drifted fields (#49)"
```

---

## Task 3: Custom-scalar & enum conformance

**Files:** `src/test/groovy/SchemaContractTests.groovy`

- [ ] **Step 1 — add the scalar+enum assertion method** to `SchemaContractTests`:

```groovy
    def "every built custom scalar and enum (with its values) is declared in the contract"() {
        when:
        List<String> missing = []
        built.getAllTypesAsList().each { GraphQLNamedType t ->
            if (t.name.startsWith("__")) return
            if (t instanceof GraphQLScalarType && !BUILTIN_SCALARS.contains(t.name)) {
                if (!contract.scalars().containsKey(t.name)) missing << "scalar ${t.name}"
            } else if (t instanceof GraphQLEnumType) {
                def opt = contract.getType(t.name, EnumTypeDefinition.class)
                if (!opt.isPresent()) { missing << "enum ${t.name}"; return }
                Set<String> cVals = opt.get().enumValueDefinitions.collect { it.name } as Set
                ((GraphQLEnumType) t).values.each { v -> if (!cVals.contains(v.name)) missing << "enum ${t.name}.${v.name}" }
            }
        }
        if (!missing.isEmpty()) System.err.println("[schema-contract] undocumented built scalars/enums:\n  " + missing.join("\n  "))
        then:
        missing == []
    }
```

- [ ] **Step 2 — run, expect PASS:** `./gradlew :runtime:component:moqui-gql:test --tests MoquiSuite` → green. (Custom scalars `DateTime`/`Decimal` and the sort-key enums `OrderSortKey`/`ShipmentSortKey`/`ReturnSortKey`/`ProductSortKey`/`PartySortKey` are already in the contract; `FacilitySortKey` was added in Task 1. If stderr prints any `enum FacilitySortKey.<VALUE>` mismatch, the built enum name/values differ from the assumed `FACILITY_NAME`/`FACILITY_ID` — correct the Task 1 enum to match what is printed, then re-run.)

- [ ] **Step 3 — commit:**
```bash
git add src/test/groovy/SchemaContractTests.groovy
git commit -m "test(gql): schema-contract — built custom scalars + enums ⊆ contract (#49)"
```

---

## Task 4: SDL-dump diagnostic (maintenance convenience)

**Files:** `src/test/groovy/SchemaContractTests.groovy`

A printed view of the full built surface makes it obvious what to document when a future change adds many fields at once. graphql-java's `SchemaPrinter` (directives off, to match the built reality) prints the built schema as SDL into the build dir.

- [ ] **Step 1 — add imports** to `SchemaContractTests`:
```groovy
import graphql.schema.idl.SchemaPrinter
```
- [ ] **Step 2 — add the diagnostic method** (always runs, never fails the build; writes a reference artifact):
```groovy
    def "emit the built schema as SDL to build/ for contract review (diagnostic, never fails)"() {
        when:
        String sdl = new SchemaPrinter(
            SchemaPrinter.Options.defaultOptions()
                .includeDirectives(false)
                .includeIntrospectionTypes(false)
                .includeScalarTypes(true)
                .includeSchemaDefinition(false)).print(built)
        File out = new File(System.getProperty("user.dir"), "build/reports/built-schema.graphql")
        out.parentFile.mkdirs(); out.text = sdl
        then:
        sdl.contains("type Order") && sdl.contains("type Query")
    }
```
- [ ] **Step 3 — run, expect PASS:** `./gradlew :runtime:component:moqui-gql:test --tests MoquiSuite` → green; inspect `runtime/component/moqui-gql/build/reports/built-schema.graphql` (this is the exact built surface; the contract must be a superset of it). It is a build artifact — do not commit it.

- [ ] **Step 4 — commit:**
```bash
git add src/test/groovy/SchemaContractTests.groovy
git commit -m "test(gql): schema-contract — built-SDL dump diagnostic (#49)"
```

---

## Task 5: Document the maintenance workflow

**Files:** `docs/design.md`, `docs/STATUS.md`

- [ ] **Step 1 — append a section to `docs/design.md`** (the guardrail + the per-PR rule):

```markdown
## Schema-contract maintenance (built ⊆ contract, test-enforced)

`docs/schema.graphql` is the SDL **contract** and is allowed to be a **superset** — it documents
designed-but-unbuilt surface (decision surface). `graphql/OmsSchema.gql.xml` is what is actually
**built**. `SchemaContractTests` (Spock, in the suite) enforces **built ⊆ contract**: every type,
field, root query, argument, enum value, and custom scalar the engine exposes MUST be declared in
`docs/schema.graphql`. It ignores nullability, directives (`@search`/`@cost`/`@service` are doc-only),
descriptions, default values, scalar field types, and order.

**Rule:** any PR that adds or changes a type/field/root/arg/enum in `graphql/OmsSchema.gql.xml` MUST
update `docs/schema.graphql` in the same PR — otherwise `SchemaContractTests` fails. To see exactly
what is undocumented, run the suite and read the `[schema-contract] undocumented ...` lines; for the
full built surface, see `build/reports/built-schema.graphql` (the Task-4 diagnostic). This is the
executable form of the contract-first decision (epic #46 decision 3): features #35/#37/#38/#43 each
update both sides together, and this test guarantees they do.
```

- [ ] **Step 2 — note it in `docs/STATUS.md`** (one line under the testing/coverage section):
```markdown
- **Schema contract**: `docs/schema.graphql` is enforced as a superset of the built schema by `SchemaContractTests` (built ⊆ contract). Schema changes must update the SDL in the same PR.
```

- [ ] **Step 3 — commit:**
```bash
git add docs/design.md docs/STATUS.md
git commit -m "docs(gql): document the schema-contract maintenance guardrail (#49)"
```

---

## Acceptance criteria

- `SchemaContractTests` is in `MoquiSuite` and green vs `hcsd_notnaked`.
- Adding a field/type/root/enum to `graphql/OmsSchema.gql.xml` **without** documenting it in `docs/schema.graphql` makes the suite fail, naming the exact undocumented element.
- `docs/schema.graphql` documents the entire current built surface: the `return` + `facilities` roots, `FacilityConnection`/`FacilityEdge`/`FacilitySortKey`, and the 17 previously-undocumented fields.
- The contract may still document unbuilt design surface (picklists, transfers, cycle counts, routing, `PostalAddress`, `fulfillmentStatus`, etc.) — that is intentional and does NOT fail the test.
- `build/reports/built-schema.graphql` is produced for review and is not committed.
- The maintenance rule is written in `design.md`.

## Self-review notes (author)

- **Direction is built ⊆ contract, not equality.** Confirmed necessary: the contract is explicitly a superset design surface (header lines 18–26), and a strict equality/SchemaPrinter golden test would fail on every `!` (built edges are nullable: `cursor`/`node`/`edges`), every directive (doc-only), and every documented-but-unbuilt type. Presence-only built ⊆ contract is the correct, low-brittleness guardrail for "the docs never fall behind the code." The other direction (did we build everything we promised?) is covered per-feature by each plan's behavior tests.
- **No engine/framework change.** The schema is reachable via the public `BuiltSchema.schema` field from the cached `GraphQLToolFactory` instance — verified. `GqlEngine.built` is private; do NOT route through it.
- **`SchemaParser.parse()` tolerates the contract as-is** — block-string descriptions, custom directive *declarations* (`@search`/`@cost`/`@service`) and *applications*, and forward references all parse into the `TypeDefinitionRegistry` without full schema validation (that is `SchemaGenerator`, which we do not call). So the contract's applied `@service`/`@cost` and its many unbuilt types do not break parsing.
- **Types ignored, names checked.** The builder defaults untyped `<field>`s to `GraphQLString` (`scalarType()` default branch), so asserting field *types* would force the contract to document semantic-`ID` fields as `String`. Presence-only avoids that and keeps the contract human-correct. A future tightening could compare unwrapped core type names with a String-default exception, but that is out of scope (YAGNI).
- **Relay wrappers & sort-key enums are generated** (`<T>Connection`/`<T>Edge`, `<T>SortKey`) and are real types in the built schema, so they are checked like any other type — which is why `FacilityConnection`/`FacilityEdge`/`FacilitySortKey` must be added (the `facilities` root generates them). All other wrapper/enum types are already documented.
- **Reconciliation is to CURRENT reality only.** Tasks 1–3 do not pre-document #35/#37/#38/#43 surface; those PRs update the contract themselves and this test enforces it. This keeps the baseline green and the test meaningful (no permanently-red "target" assertions).
- **`component://` resource read** is layout-independent (resolves to the component root regardless of which project checkout runs it), unlike a hard-coded filesystem path.
- **Test-data independence:** the test reads only schema shape (no entity rows), so it is deterministic regardless of `hcsd_notnaked` contents — unlike the resolver tests.
- **Enum value assumption flagged:** Task 1 adds `FacilitySortKey { FACILITY_NAME FACILITY_ID }` from the `facilities` root's `sort-keys="FACILITY_NAME:facilityName FACILITY_ID:facilityId"`; Task 3 Step 2 verifies the built enum matches and says how to correct it if not.

## Open questions (none blocking)

1. **Where the maintenance note lives** — `design.md` is assumed (Task 5). If the team prefers `CONTRIBUTING.md`/`README.md`, move the section there.
2. **Future tightening** — whether to later add an opt-in *type-name* comparison (built field core type ⊆ contract, with the String-default exception) or a `contract ⊆ built` "no-unimplemented-promises" check gated to epic-completion. Both are out of scope here; the per-feature behavior tests already cover "we built what we promised."
```
