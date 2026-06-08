# Windowed per-parent fetch for nested edges (eliminate the in-memory over-fetch) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax. Tasks 1–2 add the windowed mechanism behind a capability gate, verified by **new** correctness tests that must match the existing in-memory path bit-for-bit; Tasks 3–4 wire it into the loader and prove the over-fetch is gone; the existing nested-edge suite must stay **green throughout** (regression).

**Goal:** Make each nested has-many level fetch **≤ `first+1` rows per parent** by pushing the per-parent `first:N` (and the keyset `after`) **into SQL via a correlated LATERAL subquery**, instead of fetching all children of the page's parents up to `maxRowsPerLevel` (5000) and slicing each parent in memory (`NestedConnectionLoader.buildResult`). `orders(first:100){ orderItems(first:1) }` must fetch ~100 `OrderItem` rows, not up to 5000.

**Architecture:** `NestedConnectionLoader.load()` gains a **windowed fetch path** chosen by capability detection. On a lateral-capable datasource (`from-lateral-style="lateral"` — `mysql8`, the test DB), the loader builds **one** SQL statement: a derived table of the parent keys `LEFT JOIN LATERAL (SELECT … FROM child WHERE child.fk = p.fk [AND intra > :after] ORDER BY intra LIMIT :first+1) c ON 1=1`, runs it through the framework's public `ec.entity.sqlFind(sql, params, childEntityName, fieldList)` (returns fully-typed `EntityValue`s, parameters bound + transaction-enlisted by the framework), groups the rows by fk (each group already ≤ `first+1`), and reuses the **existing** `buildResult` to emit edges/pageInfo (the `+1` row → `hasNextPage`). When the DB is **not** lateral-capable, it falls back to **today's exact code** (`find(...).condition(fk IN keys).orderBy(...).maxRows(maxRowsPerLevel).list()` + in-memory trim) — byte-for-byte unchanged. Composite keys (#38) compose: single fk → one correlation predicate; multi fk → an AND of per-field predicates in the lateral `WHERE` (and an `IN`/OR-of-ANDs on the outer parent-keys derived table).

**Tech stack:** moqui-gql executor (java-dataloader `MappedBatchLoaderWithContext`, graphql-java), Moqui `EntityFacade.sqlFind` (raw SQL → typed `EntityListIterator`), `EntityDefinition` (table/column/group resolution), Spock 2.1 vs MySQL `hcsd_notnaked`.

**Tracking:** #39. **Caveats (carried in this plan, not hidden):**
- **DB dependency.** The windowed path needs `from-lateral-style="lateral"` (`mysql8`, `postgres`, `db2`). On `mssql`/`oracle` (`apply`) and any non-lateral DB it transparently uses the in-memory fallback. **The framework has NO window-function (`ROW_NUMBER() OVER`) support** — grepping `ROW_NUMBER`/`over(`/`window` across `framework/src/main/.../entity/` returns zero hits, and `EntityFindBuilder.makeSqlMemberSubSelect` emits a sub-select with **no `ORDER BY`/`LIMIT`** inside (built for scalar aggregates like #37 `orderItemCount`, not top-N-per-parent). So **Option B (window function) is not implementable through the entity engine and is dropped**; the portable fallback is the existing in-memory slice (Option C), not a window query. (A window-function path would require either raw SQL of its own or new framework support — out of scope; noted as future work.)
- **`useClone` is not honored on the windowed path.** `sqlFind` calls `getConnection(group)` = `getConnection(group, false)` — always the primary connection, never the clone. The in-memory fallback still honors `useClone`. This is an explicit, documented divergence (see Self-review notes); the windowed path runs in the request's active transaction/connection.
- **Composes with #38** (composite-key nested batching). This plan assumes `NestedConnectionLoader` already takes `List<String> fkFields` (the #38 generalization). If #38 has **not** landed, Task 1 Step 0 adapts the single-`fkField` shape — flagged inline.
- **Priority: performance optimization.** Edges already work without it; build on evidence (the in-memory trim biting under "few children per many parents"). Correctness (identical pages to the in-memory path) is the gate, not raw speed.

---

## File structure

| File | Change |
|---|---|
| `src/main/groovy/org/moqui/gql/exec/WindowedFetch.groovy` (NEW) | capability detection + LATERAL SQL builder + `sqlFind` runner returning grouped `Map<Object,List<EntityValue>>`; null when not capable |
| `src/main/groovy/org/moqui/gql/exec/NestedConnectionLoader.groovy` | in `load()`: try `WindowedFetch.groupedWindowed(...)`; if non-null use it (each group ≤ `first+1`), else the existing fetch-all + in-memory group; `buildResult` unchanged |
| `MoquiConf.xml` | `gql.windowedNestedFetch` default (`true`) — kill-switch to force the in-memory path |
| `src/main/groovy/org/moqui/gql/GqlEngine.groovy` | read `gql.windowedNestedFetch`; pass the flag into `NestedConnectionLoader` (constructor) in `buildRegistry` |
| `src/test/groovy/WindowedFetchTests.groovy` (NEW) | windowed path == in-memory path (same nodes, same order, same `hasNextPage`); per-parent `after`; single + composite key; over-fetch row-count assertion |
| `src/test/groovy/MoquiSuite.groovy` | register `WindowedFetchTests` |
| `docs/{STATUS.md,examples.md,query-cost-model.md}` | document windowed nested fetch, the lateral dependency, the `useClone` divergence, and the cost-model implication |

---

## Task 1: `WindowedFetch` — capability detection + LATERAL SQL + grouped result

**Files:** `src/main/groovy/org/moqui/gql/exec/WindowedFetch.groovy` (NEW)

> **Step 0 — confirm the #38 shape (adapt-on-execute).** Open `NestedConnectionLoader.groovy`. If its constructor already takes `List<String> fkFields` (the #38 generalization), proceed as written. If it still takes a single `String fkField` (#38 not landed), this whole plan degrades to the single-key case: treat `fkFields` as `[fkField]` everywhere below and skip the composite (OR-of-ANDs / multi-predicate) branches. The mechanism — derived-table + LATERAL + `sqlFind` — is identical; only the key arity changes. **Verify which shape is present before writing code.**

This class is the entire mechanism. It (a) decides whether the child entity's datasource is lateral-capable, (b) builds the parameterized LATERAL SQL from the entity/column metadata, (c) runs it via `ec.entity.sqlFind(...)` and groups the typed rows by fk. It returns `null` when not capable (caller falls back). **No `EntityFind`/`EntityDynamicView` is used** — the framework's dynamic-view sub-select cannot emit a per-group `ORDER BY … LIMIT` (verified: `EntityFindBuilder.makeSqlMemberSubSelect` has no LIMIT/ORDER BY branch), so the windowed query is hand-built SQL run through the framework's typed raw-SQL entry point.

- [ ] **Step 1 — create the file:**
```groovy
package org.moqui.gql.exec

import groovy.transform.CompileStatic
import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityListIterator
import org.moqui.entity.EntityValue
import org.moqui.impl.entity.EntityDefinition
import org.moqui.impl.entity.EntityFacadeImpl
import org.moqui.util.MNode

/**
 * Windowed per-parent fetch for nested has-many edges (#39): pushes each parent's first:N (+1 for
 * hasNextPage) and the keyset `after` into SQL with a correlated LATERAL subquery, so the DB returns
 * ≤ first+1 rows PER PARENT instead of all children up to maxRowsPerLevel.
 *
 *   SELECT c.* FROM (SELECT ? f0 [, ? f1 ...] UNION ALL ...) p          -- the parent keys
 *   LEFT JOIN LATERAL (
 *       SELECT <cols> FROM <child_table>
 *       WHERE <child.fk0 = p.f0> [AND <fk1 = p.f1> ...] [AND <intra-tuple> > ?]
 *       ORDER BY <intra ASC> LIMIT <first+1>
 *   ) c ON 1=1
 *   ORDER BY <fk cols>, <intra cols>
 *
 * Capability: only on datasources with from-lateral-style="lateral" (mysql8/postgres/db2). Returns
 * null otherwise so the caller uses the in-memory fallback. The framework has NO ROW_NUMBER()/window
 * support and its sub-select builder emits no LIMIT, so this is hand-built SQL run through the public,
 * transaction-enlisted, fully-typed ec.entity.sqlFind(sql, params, entityName, fieldList).
 *
 * NOTE: sqlFind uses the primary connection (getConnection(group, false)) — useClone is NOT honored on
 * this path (documented divergence; the in-memory fallback still honors it).
 */
@CompileStatic
class WindowedFetch {

    /** True iff the child entity's datasource emits LATERAL (from-lateral-style="lateral"). */
    static boolean lateralCapable(ExecutionContext ec, String childEntityName) {
        EntityFacadeImpl efi = (EntityFacadeImpl) ec.entity
        String group = efi.getEntityGroupName(childEntityName)
        MNode dbNode = efi.getDatabaseNode(group)
        return dbNode != null && "lateral".equals(dbNode.attribute("from-lateral-style"))
    }

    /**
     * Windowed grouped fetch. Returns rows grouped by the DataLoader key shape (single raw value for one
     * fk field, else a List tuple), each group already ≤ first+1 and ordered by the intra key. Returns
     * null if the datasource is not lateral-capable (caller falls back to the in-memory slice).
     *
     * @param afterKey decoded per-parent keyset boundary (the SAME intra-key string the in-memory path
     *                 compares with String.compareTo), or null for the first page. Applied as
     *                 intra-tuple > ? so only rows strictly after it are returned per parent.
     */
    static Map<Object, List<EntityValue>> groupedWindowed(
            ExecutionContext ec, String childEntityName, List<String> fkFields,
            List<String> intraGroupFields, Set<Object> keys, int first, String afterKey,
            int queryTimeoutSeconds) {

        if (keys == null || keys.isEmpty()) return new LinkedHashMap<Object, List<EntityValue>>()
        if (!lateralCapable(ec, childEntityName)) return null

        EntityFacadeImpl efi = (EntityFacadeImpl) ec.entity
        EntityDefinition ed = efi.getEntityDefinition(childEntityName)

        // ---- column resolution (entity field -> physical column) ----
        List<String> fkCols = new ArrayList<String>(fkFields.size())
        for (String f in fkFields) fkCols.add(ed.getColumnName(f))
        List<String> intraCols = new ArrayList<String>(intraGroupFields.size())
        for (String f in intraGroupFields) intraCols.add(ed.getColumnName(f))

        // ---- fields to materialize: every field of the child entity (so getMap() is complete, like .list()) ----
        List<String> fieldList = new ArrayList<String>(ed.getAllFieldNames())
        List<String> selCols = new ArrayList<String>(fieldList.size())
        for (String f in fieldList) selCols.add("c." + ed.getColumnName(f))

        String childTable = ed.getFullTableName()
        int limitN = first + 1   // +1 row drives hasNextPage

        // ---- build SQL + ordered parameter list ----
        // Parameter order MUST be: all parent-key values (outer derived table), in key order, then the
        // single afterKey (if present, once — same boundary for every parent). sqlFind binds params by
        // POSITION using the Nth selected field's type, but mismatches fall back to the value's real
        // type and CharSequence values bind via setString (drivers coerce). We bind fk values as their
        // natural types and afterKey as String; LIMIT is an integer LITERAL (never a ?), sidestepping the
        // positional-typing quirk for the limit entirely.
        List<Object> params = new ArrayList<Object>()
        StringBuilder sql = new StringBuilder()
        sql.append("SELECT ").append(String.join(", ", selCols))
        sql.append(" FROM (")
        appendParentKeysDerivedTable(sql, params, fkCols, keys)
        sql.append(") p LEFT JOIN LATERAL (SELECT ").append(String.join(", ", selCols0(ed, fieldList)))
        sql.append(" FROM ").append(childTable).append(" WHERE ")
        for (int i = 0; i < fkCols.size(); i++) {
            if (i > 0) sql.append(" AND ")
            sql.append(fkCols.get(i)).append(" = p.").append(pAlias(i))
        }
        if (afterKey != null) {
            // keyset: intra-tuple strictly greater than the boundary. Single intra column -> col > ?.
            // Composite intra -> row-value compare (col1,col2,...) > (?,?,...); the boundary is the
            // separator-joined string the in-memory path uses, split back into components below.
            appendAfterPredicate(sql, params, intraCols, afterKey)
        }
        sql.append(" ORDER BY ")
        for (int i = 0; i < intraCols.size(); i++) { if (i > 0) sql.append(", "); sql.append(intraCols.get(i)) }
        sql.append(" LIMIT ").append(limitN)
        sql.append(") c ON 1=1 ORDER BY ")
        for (int i = 0; i < fkCols.size(); i++) sql.append("p.").append(pAlias(i)).append(", ")
        for (int i = 0; i < intraCols.size(); i++) { if (i > 0) sql.append(", "); sql.append("c.").append(intraCols.get(i)) }

        // ---- run via the framework's typed raw-SQL path; group by fk ----
        Map<Object, List<EntityValue>> grouped = new LinkedHashMap<Object, List<EntityValue>>()
        for (Object k in keys) grouped.put(k, new ArrayList<EntityValue>())
        EntityListIterator eli = null
        try {
            eli = efi.sqlFind(sql.toString(), params, childEntityName, fieldList)
            EntityValue ev
            while ((ev = eli.next()) != null) {
                Object gk = groupKey(ev, fkFields)
                List<EntityValue> g = grouped.get(gk)
                if (g != null) g.add(ev)
            }
        } finally {
            if (eli != null) eli.close()
        }
        return grouped
    }

    // ---- the derived table of parent keys: SELECT ? f0[, ? f1] UNION ALL SELECT ? f0 ... ----
    private static void appendParentKeysDerivedTable(StringBuilder sql, List<Object> params,
                                                     List<String> fkCols, Set<Object> keys) {
        boolean firstRow = true
        for (Object k in keys) {
            if (firstRow) { firstRow = false } else { sql.append(" UNION ALL ") }
            sql.append("SELECT ")
            List<Object> tuple = (k instanceof List) ? (List<Object>) k : Collections.singletonList(k)
            for (int i = 0; i < fkCols.size(); i++) {
                if (i > 0) sql.append(", ")
                sql.append("? ").append(pAlias(i))   // alias each key column p0, p1, ...
                params.add(tuple.get(i))
            }
        }
    }

    // ---- after predicate: single col -> col > ?; composite -> (c1,c2,...) > (?,?,...) ----
    private static void appendAfterPredicate(StringBuilder sql, List<Object> params,
                                             List<String> intraCols, String afterKey) {
        if (intraCols.size() == 1) {
            sql.append(" AND ").append(intraCols.get(0)).append(" > ?")
            params.add(afterKey)
        } else {
            // afterKey is the control-char()-joined intra tuple (see NestedConnectionLoader.intraKey)
            String[] comps = afterKey.split("", -1)
            sql.append(" AND (")
            for (int i = 0; i < intraCols.size(); i++) { if (i > 0) sql.append(", "); sql.append(intraCols.get(i)) }
            sql.append(") > (")
            for (int i = 0; i < intraCols.size(); i++) {
                if (i > 0) sql.append(", ")
                sql.append("?")
                params.add(i < comps.length ? comps[i] : "")
            }
            sql.append(")")
        }
    }

    private static String pAlias(int i) { return "p" + i }

    /** Inner-subquery select list, aliased to the entity field's underscored name so sqlFind/ELI maps
     *  each column back to the right field (the ELI reads columns positionally against fieldList order,
     *  but aliasing keeps the SQL self-describing and order-stable). */
    private static List<String> selCols0(EntityDefinition ed, List<String> fieldList) {
        List<String> out = new ArrayList<String>(fieldList.size())
        for (String f in fieldList) out.add(ed.getColumnName(f) + " AS " + org.moqui.impl.entity.EntityJavaUtil.camelCaseToUnderscored(f))
        return out
    }

    /** Group key matching the DataLoader key shape: single raw value for one fk, else a List tuple. */
    private static Object groupKey(EntityValue ev, List<String> fkFields) {
        if (fkFields.size() == 1) return ev.get(fkFields.get(0))
        List<Object> t = new ArrayList<Object>(fkFields.size())
        for (String f in fkFields) t.add(ev.get(f))
        return t
    }
}
```
> **Adapt-on-execute (mechanism, explicitly flagged — not a placeholder):** `sqlFind` returns the ELI by reading result columns **positionally** against `fieldList` order (`EntityListIteratorImpl(con, rs, ed, fiArray, …)`). The outer `SELECT c.col1, c.col2, …` MUST list columns in **exactly the same order** as `fieldList` (= `ed.getAllFieldNames()`). The code above builds `selCols` from `fieldList` in the same loop, so they are aligned by construction — **do not reorder them independently**. Before trusting the output, run the Step-2 self-check test (it compares every node against the in-memory path); if a column appears mis-mapped, the cause is select-list/fieldList order drift — re-derive both from the one `fieldList` list. The `AS underscored` aliasing in the inner subquery is cosmetic for the outer `c.*` references; the binding that matters is outer-select-order ↔ `fieldList`-order.

- [ ] **Step 2 — compile:** `./gradlew :runtime:component:moqui-gql:compileGroovy` → BUILD SUCCESSFUL.
- [ ] **Step 3 — commit:** `git add src/main/groovy/org/moqui/gql/exec/WindowedFetch.groovy && git commit -m "feat(gql): WindowedFetch — LATERAL per-parent top-N via sqlFind (#39)"`

---

## Task 2: correctness test — windowed path == in-memory path

**Files:** `src/test/groovy/WindowedFetchTests.groovy` (NEW), `src/test/groovy/MoquiSuite.groovy`

This test is the gate: the windowed result must equal the in-memory result for the same `(keys, first, after)`. It calls `WindowedFetch.groupedWindowed(...)` directly and compares against a hand-rolled in-memory slice over the same children, on `hcsd_notnaked` (mysql8 → lateral-capable, so `groupedWindowed` returns non-null here).

- [ ] **Step 1 — write the test:**
```groovy
import spock.lang.Specification
import spock.lang.Shared
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.entity.EntityValue
import org.moqui.gql.exec.WindowedFetch

/** #39: the LATERAL windowed fetch must return, per parent, exactly the SAME ordered first+1 rows the
 *  in-memory slice would. Self-checking vs hcsd_notnaked (mysql8 -> lateral-capable). Child: OrderItem,
 *  fk=orderId, intra=orderItemSeqId (the single-key edge that powers orders{ orderItems }). */
class WindowedFetchTests extends Specification {
    static final String CHILD = "org.apache.ofbiz.order.order.OrderItem"
    static final List<String> FK = ["orderId"]
    static final List<String> INTRA = ["orderItemSeqId"]

    @Shared ExecutionContext ec
    @Shared List<Object> someOrderIds

    def setupSpec() {
        ec = Moqui.getExecutionContext(); ec.artifactExecution.disableAuthz()
        // pick orders that actually have items so groups are non-trivial
        def rows = ec.entity.find(CHILD).selectField("orderId").distinct(true)
                .orderBy("orderId").maxRows(8).fetchSize(8).list()
        someOrderIds = new ArrayList<Object>(); for (def r in rows) someOrderIds.add(r.orderId)
    }
    def cleanupSpec() { if (ec != null) { ec.artifactExecution.enableAuthz(); ec.destroy() } }

    /** the in-memory oracle: all children of these keys, grouped, each group sliced to first(+1) after the boundary. */
    private Map<Object, List<String>> oracleSeqIds(Set<Object> keys, int first, String afterKey) {
        def all = ec.entity.find(CHILD).condition("orderId", org.moqui.entity.EntityCondition.IN, new ArrayList(keys))
                .orderBy(["orderId", "orderItemSeqId"]).maxRows(5000).list()
        Map<Object, List<String>> g = new LinkedHashMap<>(); for (Object k in keys) g.put(k, new ArrayList<String>())
        for (EntityValue ev in all) {
            String seq = (String) ev.get("orderItemSeqId")
            if (afterKey != null && seq.compareTo(afterKey) <= 0) continue
            List<String> lst = g.get(ev.get("orderId"))
            if (lst != null && lst.size() < first + 1) lst.add(seq)
        }
        return g
    }

    private Map<Object, List<String>> windowedSeqIds(Set<Object> keys, int first, String afterKey) {
        def grouped = WindowedFetch.groupedWindowed(ec, CHILD, FK, INTRA, keys, first, afterKey, 20)
        assert grouped != null   // mysql8 is lateral-capable; null here would mean the gate misfired
        Map<Object, List<String>> g = new LinkedHashMap<>()
        for (def e in grouped.entrySet()) {
            List<String> seqs = new ArrayList<>(); for (EntityValue ev in e.value) seqs.add((String) ev.get("orderItemSeqId"))
            g.put(e.key, seqs)
        }
        return g
    }

    def "windowed first:1 returns same ordered rows as in-memory slice (per parent)"() {
        given: Set<Object> keys = new LinkedHashSet<>(someOrderIds)
        expect: windowedSeqIds(keys, 1, null) == oracleSeqIds(keys, 1, null)
    }

    def "windowed first:2 returns same ordered first+1 rows as in-memory slice"() {
        given: Set<Object> keys = new LinkedHashSet<>(someOrderIds)
        expect: windowedSeqIds(keys, 2, null) == oracleSeqIds(keys, 2, null)
    }

    def "windowed honors per-parent after boundary identically to in-memory"() {
        given:
        Set<Object> keys = new LinkedHashSet<>(someOrderIds)
        // boundary = the smallest seq across the sample, so most parents have rows strictly after it
        String boundary = oracleSeqIds(keys, 1, null).values().findAll { !it.isEmpty() }*.get(0).min()
        expect: windowedSeqIds(keys, 5, boundary) == oracleSeqIds(keys, 5, boundary)
    }

    def "windowed fetches at most first+1 rows per parent (no over-fetch)"() {
        given: Set<Object> keys = new LinkedHashSet<>(someOrderIds)
        when: def g = windowedSeqIds(keys, 1, null)
        then: g.values().every { it.size() <= 2 }                 // first(1)+1
        and:  g.values().sum { it.size() } as int <= keys.size() * 2   // total bounded by parents*(first+1)
    }
}
```
- [ ] **Step 2 — register** in `MoquiSuite.groovy` (`@SelectClasses`): add `WindowedFetchTests.class`.
- [ ] **Step 3 — run, expect PASS:** `./gradlew :runtime:component:moqui-gql:test --tests WindowedFetchTests` → PASS (4 features). Expected console: `BUILD SUCCESSFUL`; the over-fetch feature confirms each group ≤ `first+1` and the total ≤ `parents × (first+1)`.
  - **If the equality features FAIL:** the most likely causes, in order — (a) outer select-list order ≠ `fieldList` order (see Task 1 Step 1 adapt note — re-derive both from one list); (b) `getColumnName` returning an unexpected physical name (log `sql.toString()` and eyeball the columns); (c) `after` boundary type — `orderItemSeqId` is a String, compared with `String.compareTo`, and bound via `setString`, so it must match exactly; print the generated SQL + params and compare the first divergent parent. Fix the SQL builder, not the test — the in-memory oracle is the source of truth.
- [ ] **Step 4 — commit:** `git add src/test/groovy/WindowedFetchTests.groovy src/test/groovy/MoquiSuite.groovy && git commit -m "test(gql): windowed fetch matches in-memory slice + bounds per-parent rows (#39)"`

---

## Task 3: wire the windowed path into `NestedConnectionLoader` (behind the capability + flag gate)

**Files:** `src/main/groovy/org/moqui/gql/exec/NestedConnectionLoader.groovy`, `MoquiConf.xml`, `src/main/groovy/org/moqui/gql/GqlEngine.groovy`

The loader keeps `buildResult`, `intraKey`, `orderByList`, `pickArgs`, `clampN` **unchanged**. Only `load()` changes: decode the `after` boundary once, try the windowed grouped fetch, and fall back to the existing query when it returns null or the flag is off.

- [ ] **Step 1 — add the config kill-switch.** In `MoquiConf.xml`, after `gql.maxRowsPerLevel`:
```xml
    <default-property name="gql.windowedNestedFetch" value="true"/>  <!-- #39: LATERAL per-parent top-N for nested edges; false forces the in-memory slice -->
```
- [ ] **Step 2 — thread the flag into the loader.** In `GqlEngine.groovy`:
  - where the other `gql.*` settings are read (next to `maxRowsPerLevel`), add:
    `boolean windowedNestedFetch = (sysOr("gql.windowedNestedFetch", "true")) == "true"`
    (use the same `sysOr`/property-read helper the surrounding code already uses for `gql.useClone` etc.; match its exact form).
  - in `buildRegistry`, the `new NestedConnectionLoader(...)` call: append `windowedNestedFetch` as the final constructor argument:
```groovy
                    new NestedConnectionLoader(ec, meta.childEntityName, meta.fkFields, meta.intraGroupFields,
                            useClone, queryTimeoutSeconds, maxFirst, maxRowsPerLevel, meta.plain, windowedNestedFetch)
```
  > (If #38 has **not** landed and the constructor still takes a single `String fkField`, pass `meta.fkField` there as today and add `windowedNestedFetch` as the new final arg; Task 1 Step 0 already set `fkFields=[fkField]` semantics.)
- [ ] **Step 3 — loader field + constructor.** In `NestedConnectionLoader.groovy` add the field and constructor param (final arg, after `plainList`):
```groovy
    private final boolean windowedNestedFetch
```
```groovy
    NestedConnectionLoader(ExecutionContext ec, String childEntityName, List<String> fkFields,
                           List<String> intraGroupFields, boolean useClone, int queryTimeoutSeconds,
                           int maxFirst, int maxRowsPerLevel, boolean plainList, boolean windowedNestedFetch) {
        this.ec = ec; this.childEntityName = childEntityName; this.fkFields = fkFields
        this.intraGroupFields = intraGroupFields; this.useClone = useClone
        this.queryTimeoutSeconds = queryTimeoutSeconds; this.maxFirst = maxFirst
        this.maxRowsPerLevel = maxRowsPerLevel; this.plainList = plainList
        this.windowedNestedFetch = windowedNestedFetch
    }
```
  > This assumes the #38 `List<String> fkFields` field/constructor is already present (replace its constructor signature by adding the trailing `boolean windowedNestedFetch`). If only the single-`String fkField` shape exists, keep that param and just add the trailing `boolean windowedNestedFetch` — and in Step 4 build a 1-element list `List<String> fkFields = [this.fkField]` at the top of `load()` for `WindowedFetch`.
- [ ] **Step 4 — `load()` chooses windowed-or-fallback.** Replace the body of `load()` from the `EntityFind cf = ...` build through the `grouped` population (the part that fetches + groups; leave the trailing `out`/`buildResult` loop as-is) with:
```groovy
        Map argsMap = pickArgs(env.getKeyContexts())
        int first = clampN(argsMap != null ? argsMap.get("first") : null)
        String afterStr = argsMap != null ? (String) argsMap.get("after") : null

        // decode the per-parent keyset boundary once (the same intra-key string buildResult compares).
        String afterKey = null
        if (afterStr != null && !afterStr.isEmpty()) {
            try { afterKey = Cursor.decode(afterStr).sortValue } catch (Exception ignored) { afterKey = null }
        }

        Map<Object, List<EntityValue>> grouped = null
        if (windowedNestedFetch) {
            // windowed path: DB returns <= first+1 rows per parent (null if datasource not lateral-capable)
            grouped = WindowedFetch.groupedWindowed(ec, childEntityName, fkFields, intraGroupFields,
                    keys, first, afterKey, queryTimeoutSeconds)
        }
        if (grouped == null) {
            // fallback: today's behavior exactly — fetch all children up to maxRowsPerLevel, group, trim in memory
            EntityFind cf = ec.entity.find(childEntityName)
            if (fkFields.size() == 1) {
                List<Object> vals = new ArrayList<Object>()
                for (Object k in keys) vals.add(k instanceof List ? ((List) k).get(0) : k)
                cf.condition(fkFields.get(0), EntityCondition.IN, vals)
            } else {
                org.moqui.entity.EntityConditionFactory ecf = ec.entity.getConditionFactory()
                List<EntityCondition> ors = new ArrayList<EntityCondition>()
                for (Object k in keys) {
                    List tuple = (List) k
                    List<EntityCondition> ands = new ArrayList<EntityCondition>()
                    for (int i = 0; i < fkFields.size(); i++) ands.add(ecf.makeCondition(fkFields.get(i), EntityCondition.EQUALS, tuple.get(i)))
                    ors.add(ecf.makeCondition(ands, EntityCondition.AND))
                }
                cf.condition(ecf.makeCondition(ors, EntityCondition.OR))
            }
            cf.orderBy(orderByList()).useClone(useClone).queryTimeout(queryTimeoutSeconds)
                    .maxRows(maxRowsPerLevel).fetchSize(Math.min(maxRowsPerLevel, 1000))
            ScopeFilters.apply(cf, childEntityName, ec)
            EntityList rows = cf.list()
            grouped = new LinkedHashMap<Object, List<EntityValue>>()
            for (Object k in keys) grouped.put(k, new ArrayList<EntityValue>())
            for (EntityValue ev in rows) {
                Object gk = groupKey(ev)
                List<EntityValue> g = grouped.get(gk)
                if (g != null) g.add(ev)
            }
        }

        Map<Object, Object> out = new LinkedHashMap<Object, Object>()
        for (Map.Entry<Object, List<EntityValue>> e in grouped.entrySet()) {
            out.put(e.getKey(), buildResult(e.getValue(), first, afterStr))
        }
        return CompletableFuture.completedFuture(out)
```
  > The fallback block above is the **#38 composite-key version** (single → `IN`, multi → OR-of-ANDs) using a `groupKey(ev)` helper. If #38 has not landed, this reduces to the original single-`fkField` `condition(fkField, IN, keys)` + `grouped.get(ev.get(fkField))`; keep whichever matches the file's current shape — the **only new logic** is the `if (windowedNestedFetch) { grouped = WindowedFetch.groupedWindowed(...) }` attempt and the `if (grouped == null)` guard around the existing code.
  > **`buildResult` is intentionally still passed `afterStr` and re-applies the `after` filter + `first` cap in memory.** On the windowed path each group is already `≤ first+1` and already strictly after the boundary, so this second pass is a **no-op that cannot change the result** (it re-derives the same edges) — keeping it means the cursor/pageInfo construction stays in exactly one place and the two paths are provably identical. Do **not** special-case `buildResult` per path.
- [ ] **Step 5 — imports.** Ensure `NestedConnectionLoader.groovy` imports `org.moqui.entity.EntityValue` and `org.moqui.entity.EntityList` (likely already present) and references `WindowedFetch` (same package — no import needed). `EntityFind`/`EntityCondition` imports already present.
- [ ] **Step 6 — compile:** `./gradlew :runtime:component:moqui-gql:compileGroovy` → BUILD SUCCESSFUL.
- [ ] **Step 7 — commit:** `git add src/main/groovy/org/moqui/gql/exec/NestedConnectionLoader.groovy MoquiConf.xml src/main/groovy/org/moqui/gql/GqlEngine.groovy && git commit -m "feat(gql): nested loader uses windowed per-parent fetch when capable (#39)"`

---

## Task 4: end-to-end regression + over-fetch proof through the GraphQL engine

**Files:** `src/test/groovy/WindowedFetchTests.groovy` (extend)

Tasks 1–2 proved `WindowedFetch` in isolation; Task 3 wired it in. Now prove the **full engine** path (`GqlEngine.execute`) returns identical pages with the flag on vs off, and that the existing nested-edge suite is unaffected.

- [ ] **Step 1 — add engine-level features to `WindowedFetchTests`** (flag-on vs flag-off equality, via a fresh `GqlEngine` each time; the flag is read at registry build, so toggling the system property between runs selects the path):
```groovy
    def "orders{ orderItems(first:1) } returns identical edges with windowed ON vs OFF"() {
        given:
        String q = 'query { orders(first:20){ edges{ node{ orderId orderItems(first:1){ edges{ cursor node{ orderId orderItemSeqId } } pageInfo{ hasNextPage } } } } } }'
        when: "windowed ON (default)"
        System.setProperty("gql.windowedNestedFetch", "true")
        def on = new org.moqui.gql.GqlEngine(ec).execute(q, [:], null)
        and: "windowed OFF (in-memory)"
        System.setProperty("gql.windowedNestedFetch", "false")
        def off = new org.moqui.gql.GqlEngine(ec).execute(q, [:], null)
        System.clearProperty("gql.windowedNestedFetch")
        then:
        on.errors.isEmpty(); off.errors.isEmpty()
        // same orders, same per-order item edges (nodes + cursors), same hasNextPage
        on.data.orders.edges*.node*.orderItems == off.data.orders.edges*.node*.orderItems
    }

    def "orders(first:N){ orderItems(first:1) } over-fetch is bounded per parent under windowed fetch"() {
        given:
        System.setProperty("gql.windowedNestedFetch", "true")
        String q = 'query { orders(first:50){ edges{ node{ orderId orderItems(first:1){ edges{ node{ orderItemSeqId } } } } } } }'
        when:
        def r = new org.moqui.gql.GqlEngine(ec).execute(q, [:], null)
        System.clearProperty("gql.windowedNestedFetch")
        then:
        r.errors.isEmpty()
        // each order returns at most 1 item edge (first:1); the windowed fetch never materialized the
        // full child set, so even orders with many items expose exactly their first item here.
        r.data.orders.edges.every { (it.node.orderItems?.edges ?: []).size() <= 1 }
    }
```
  > **Adapt-on-execute (flag read timing — flagged, not silent):** this assumes `gql.windowedNestedFetch` is read at `GqlEngine`/registry construction time (so a new `GqlEngine(ec)` after `System.setProperty(...)` picks up the toggle). Confirm where `sysOr("gql.windowedNestedFetch", …)` is read in Task 3 Step 2: if it's read once and cached at a higher scope (e.g. a tool-factory singleton built at startup), the `System.setProperty` toggle won't take effect mid-suite — in that case drop the ON/OFF feature's runtime toggling and instead assert only the **windowed-ON** behavior (default) plus the over-fetch bound, and keep the path-equality proof at the `WindowedFetch` unit level (Task 2, which already compares windowed vs in-memory directly). The unit-level equality (Task 2) is the authoritative correctness gate; this engine-level toggle is a convenience and may be reduced to a single windowed-ON assertion if the flag isn't runtime-togglable.
- [ ] **Step 2 — run the new test file, expect PASS:** `./gradlew :runtime:component:moqui-gql:test --tests WindowedFetchTests` → PASS (6 features total). Expected: `BUILD SUCCESSFUL`.
- [ ] **Step 3 — run the existing nested-edge suite, expect PASS (regression):** the windowed path must not change any existing result.
  `./gradlew :runtime:component:moqui-gql:test --tests OrderDetailEdgesTests --tests ConnectionWalkTests --tests PartyConnectionTests` → PASS. Then the **full** suite:
  `./gradlew :runtime:component:moqui-gql:test` → BUILD SUCCESSFUL.
- [ ] **Step 4 — commit:** `git add src/test/groovy/WindowedFetchTests.groovy && git commit -m "test(gql): engine-level windowed-vs-in-memory parity + over-fetch bound (#39)"`

---

## Task 5: documentation

**Files:** `docs/STATUS.md`, `docs/examples.md`, `docs/query-cost-model.md`

- [ ] **Step 1 — `STATUS.md`:** Batching row — note nested has-many edges now use a **windowed per-parent fetch** (correlated LATERAL via `ec.entity.sqlFind`) on lateral-capable datasources (`mysql8`/`postgres`/`db2`), fetching ≤ `first+1` rows per parent; in-memory slice is the portable fallback (and the kill-switch `gql.windowedNestedFetch=false`). State plainly: **no window-function path** — the framework has no `ROW_NUMBER()` support; window functions were considered (#39 Option B) and dropped.
- [ ] **Step 2 — `examples.md`:** add a note under the nested-edge examples: `orders(100){ orderItems(first:1) }` fetches ~100 item rows (one LATERAL per parent), not up to `maxRowsPerLevel` (5000); `maxRowsPerLevel` is now rarely binding for nested edges. Note the **`useClone` divergence**: the windowed path uses the primary connection (read-replica routing via `useClone` applies only on the in-memory fallback).
- [ ] **Step 3 — `query-cost-model.md`:** update the nested-edge cost note — the cost model's tight per-parent assumption now matches reality on lateral-capable DBs (the DB fetches ≤ `first+1` per parent), so the cost estimate no longer under-counts the real fetch for nested row edges; on the in-memory fallback the old caveat (real fetch up to `maxRowsPerLevel`) still applies.
- [ ] **Step 4 — commit:** `git add docs/STATUS.md docs/examples.md docs/query-cost-model.md && git commit -m "docs(gql): windowed per-parent nested fetch (#39)"`

---

## Acceptance criteria

- `orders(first:100){ orderItems(first:1) }` fetches **~100** `OrderItem` rows (one LATERAL per parent, `LIMIT first+1`), **not up to 5000** — proven by `WindowedFetchTests` (per-parent ≤ `first+1`; total ≤ `parents × (first+1)`).
- **Keyset pagination correctness preserved:** the windowed page equals the in-memory page for the same `(keys, first, after)` — same ordered nodes, same cursors, same `hasNextPage` (the `+1` row) — proven at the unit level (Task 2) and engine level (Task 4, flag ON vs OFF).
- Per-parent `after` cursor applies in the lateral `WHERE` (`intra > :after`), single and composite intra keys.
- **Composes with #38:** single fk → one correlation predicate; composite fk → AND-of-predicates inside the lateral + OR-of-ANDs on the parent-keys derived table; grouping by the key tuple.
- **Capability-gated:** lateral on `from-lateral-style="lateral"`; otherwise the **existing in-memory fetch-all-then-trim** (byte-for-byte unchanged), also forced by `gql.windowedNestedFetch=false`.
- `maxRowsPerLevel` becomes rarely binding for nested edges on lateral-capable DBs.
- **Regression:** all existing nested-edge tests (`OrderDetailEdgesTests`, `ConnectionWalkTests`, `PartyConnectionTests`, …) and the full suite stay green vs `hcsd_notnaked`. No DB/index change (relies on the child's fk-leading PK index — `(orderId, orderItemSeqId)` for `OrderItem`).

## Self-review notes (author)

- **Mechanism is the hard part — here is exactly what was verified and chosen, with the risks named:**
  - **Window functions (#39 Option B) are NOT implementable through Moqui's entity engine.** `grep -rE 'ROW_NUMBER|over\(|window' framework/src/main/.../entity/` → **zero hits**; `EntityFindBuilder.makeSqlMemberSubSelect` emits `SELECT … FROM … WHERE … [GROUP BY …]` with **no `ORDER BY`/`LIMIT`** (it's the #37 scalar-aggregate LATERAL builder). So neither `EntityFind` nor `EntityDynamicView` can express `ROW_NUMBER() OVER (PARTITION BY fk ORDER BY intra)` **or** a per-parent `ORDER BY … LIMIT`. **Decision: drop Option B; implement Option A (LATERAL) via hand-built SQL run through the framework's public `EntityFacade.sqlFind`.** The portable fallback is the existing in-memory slice (Option C), not a window query.
  - **Chosen mechanism: `ec.entity.sqlFind(sql, params, childEntityName, fieldList)`** (confirmed at `EntityFacadeImpl:1820`). It prepares the statement on `getConnection(group)` (transaction-enlisted), binds params, and returns an `EntityListIterator` of **fully-typed `EntityValue`s** (so `ev.getMap()`/`ev.get(field)` behave exactly like `find().list()` rows). This avoids internal types (`LiteStringMap`) and manual `ResultSet` extraction. The SQL itself (derived parent-keys table + `LEFT JOIN LATERAL (… LIMIT first+1)`) is the same LATERAL technique #37 relies on and that `mysql8` supports (`from-lateral-style="lateral"`, `offset-style="limit"`).
  - **`sqlFind` parameter-typing quirk — assessed, mitigated.** `sqlFind` binds the Nth `?` using the Nth **selected field's** `FieldInfo` (`fiArray[paramIndex-1]`), which does **not** correspond to our parameters (our params are parent-key + after values, not the first N selected columns). Verified in `FieldInfo.setPreparedStatementValue`: on a type mismatch it **re-derives the type from the value's actual class**, and any `CharSequence` value binds via `ps.setString` (drivers coerce). Our bound values are fk keys (String/typed) and the `after` boundary (String) — all safe; **`LIMIT` is emitted as an integer literal, never a `?`**, so the one numerically-sensitive value sidesteps the quirk entirely. **Residual risk:** an fk whose Java type is non-String *and* doesn't coerce under the mis-assigned field type — low for OMS keys (ids are strings), but the Task-2 equality test would catch it immediately. **This is the single biggest mechanism risk; the unit test is the guard.**
  - **Column/field order coupling — flagged as an adapt-on-execute point (Task 1 Step 1, Task 2 Step 3).** `sqlFind`'s ELI reads result columns **positionally** against `fieldList`. The outer `SELECT` list and `fieldList` are both built from the single `ed.getAllFieldNames()` list in the same loop, so they're aligned by construction — but any later hand-edit that reorders one without the other silently mis-maps columns. The Task-2 equality test (every node vs the in-memory oracle) is the detector; the fix is always "re-derive both from one list."
- **`useClone` divergence — honest, documented, not hidden.** `sqlFind` → `getConnection(group)` = `getConnection(group, false)` (confirmed `EntityFacadeImpl:2017`), so the windowed path uses the **primary** connection regardless of `useClone`. The in-memory fallback still honors `useClone`. #39 says "compose with useClone"; this plan composes by *documenting* that read-replica routing applies on the fallback path only, and gating the windowed path behind a flag so an operator who needs clone-routing for nested reads can set `gql.windowedNestedFetch=false`. **If clone-routing for the windowed path is a hard requirement, the alternative is to obtain `getConnection(group, useClone)` and run the prepared statement directly (not via `sqlFind`), reusing `FieldInfo.getResultSetValue` for typing** — more code, defers the typed-row convenience; left as a flagged contingency, not built, because no current evidence requires clone-routing for nested edges.
- **`ScopeFilters` on the windowed path — known gap, flagged.** The in-memory fallback calls `ScopeFilters.apply(cf, childEntityName, ec)` (a phase-1 no-op today). The hand-built SQL does **not** invoke it. Because `ScopeFilters` is currently a no-op, behavior is identical now; **but if/when row-scoping becomes real, the windowed SQL must add the same predicate to the lateral `WHERE`.** Adapt-on-execute: if `ScopeFilters.apply` is non-trivial at execution time, either (a) keep nested edges on the in-memory path for scoped entities, or (b) translate the scope condition into the lateral `WHERE`. Surfaced explicitly so it is not silently dropped.
- **`queryTimeout` on the windowed path — partial.** `queryTimeoutSeconds` is threaded into `groupedWindowed` for parity of intent, but `sqlFind` does not expose a per-statement timeout hook; the active transaction's timeout still bounds it. The in-memory fallback sets `.queryTimeout(...)` explicitly. Noted; not a correctness issue (transaction timeout remains the backstop).
- **Spec coverage (#39):** per-parent `LIMIT first+1` via LATERAL (Task 1); keyset `after` in the lateral `WHERE` (Task 1 + Task 2 boundary test); `hasNextPage` from the `+1` row preserved (buildResult reused, Task 3); grouping preserved (Task 1 `groupKey`); plain-list vs connection preserved (buildResult untouched); composite keys composed (#38 single/multi branches, Tasks 1 & 3); capability detection LATERAL-or-fallback (Task 1 `lateralCapable` + Task 3 null-guard + flag); acceptance over-fetch + parity proven (Tasks 2 & 4). Window-function option deliberately dropped with the framework-grep evidence above.
- **Scope discipline:** has-many only — `NestedSingleLoader` (has-one, single row per parent) is untouched; it already fetches ≤ 1 per parent so the over-fetch issue doesn't apply.
- **Regression safety:** the windowed path is purely additive behind `if (windowedNestedFetch) { grouped = WindowedFetch... }` + `if (grouped == null)`; with the flag off, or on a non-lateral DB, the loader runs the **existing** code unchanged. `buildResult`/cursor/pageInfo logic is shared by both paths (single source of truth), so the only way the windowed path can differ is a SQL-construction bug — which Tasks 2 and 4 are built to catch.
