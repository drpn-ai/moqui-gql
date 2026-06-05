import spock.lang.Specification
import spock.lang.Shared
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.gql.GqlEngine

/** View-entity-backed type + composite-PK keyset: the `parties` root is backed by
 *  PartyNameAndRoleDetail (Party+Person+PartyRole join, PK partyId+roleTypeId). Verifies pagination
 *  over a composite PK and search on a view-only field (firstName) — all vs real hcsd_notnaked data. */
class PartyConnectionTests extends Specification {
    @Shared ExecutionContext ec
    @Shared String samplePartyId
    @Shared String sampleFirstName

    def setupSpec() {
        ec = Moqui.getExecutionContext(); ec.artifactExecution.disableAuthz()
        // plain for-loop: EntityList.find(Closure) dispatches to Moqui's EntityList.find(Map), not Groovy's
        def rows = ec.entity.find("co.hotwax.party.party.PartyNameAndRoleDetail")
                .selectField("partyId").selectField("firstName").maxRows(200).fetchSize(200).list()
        for (r in rows) { if (r.firstName) { samplePartyId = r.partyId; sampleFirstName = r.firstName; break } }
        if (samplePartyId == null && !rows.isEmpty()) samplePartyId = rows.get(0).partyId
    }
    def cleanupSpec() { if (ec != null) { ec.artifactExecution.enableAuthz(); ec.destroy() } }

    def "parties(first:3) returns a connection over the composite-PK view"() {
        when:
        def r = new GqlEngine(ec).execute(
                'query { parties(first:3){ edges{ cursor node{ partyId roleTypeId } } pageInfo{ hasNextPage endCursor } } }',
                [:], null)
        then:
        r.errors.isEmpty()
        def conn = r.data.parties
        conn.edges.size() == 3
        conn.edges.every { it.node.partyId != null }
        conn.edges.every { it.cursor != null }
        conn.pageInfo.endCursor != null
    }

    def "parties forward pagination over composite PK has no overlapping edges"() {
        when: "page 1"
        def p1 = new GqlEngine(ec).execute(
                'query { parties(first:4){ edges{ cursor node{ partyId roleTypeId } } pageInfo{ endCursor } } }', [:], null)
        def k1 = p1.data.parties.edges.collect { it.node.partyId + "/" + it.node.roleTypeId }
        def after = p1.data.parties.pageInfo.endCursor
        and: "page 2 via after"
        def p2 = new GqlEngine(ec).execute(
                'query Q($a:String){ parties(first:4, after:$a){ edges{ node{ partyId roleTypeId } } } }', [a: after], "Q")
        def k2 = p2.data.parties.edges.collect { it.node.partyId + "/" + it.node.roleTypeId }
        then:
        p1.errors.isEmpty() && p2.errors.isEmpty()
        k1.size() == 4
        k2.size() > 0
        k1.intersect(k2).isEmpty()       // composite-PK keyset advanced correctly, no repeats
    }

    def "parties query: filters on a view-only field (firstName)"() {
        when:
        def r = new GqlEngine(ec).execute(
                'query Q($q:String){ parties(first:25, query:$q){ edges{ node{ partyId firstName } } } }',
                [q: ("firstName:" + sampleFirstName)], "Q")
        then:
        r.errors.isEmpty()
        def nodes = r.data.parties.edges.collect { it.node }
        nodes.size() > 0
        nodes.every { it.firstName == sampleFirstName }
    }

    def "party(partyId:) by-pk returns the party from the view (first-row, no multi-row error)"() {
        when:
        def r = new GqlEngine(ec).execute(
                'query Q($id:ID!){ party(partyId:$id){ partyId } }', [id: samplePartyId], "Q")
        then:
        r.errors.isEmpty()
        r.data.party != null
        r.data.party.partyId == samplePartyId
    }
}
