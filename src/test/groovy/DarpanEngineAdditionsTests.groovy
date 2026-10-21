import graphql.schema.GraphQLList
import graphql.schema.GraphQLNonNull
import graphql.schema.GraphQLObjectType
import graphql.schema.idl.SchemaPrinter
import org.moqui.gql.CostModel
import org.moqui.gql.GqlSchemaBuilder
import org.moqui.gql.SchemaArtifactParser
import org.moqui.util.MNode
import spock.lang.Specification

/**
 * Pure unit test (no Moqui boot) for the Darpan fork's schema additions: embedded edges and scalar-list fields
 * (DAR-BE-098), tenant-param on a service-backed root (DAR-BE-098) and descriptions (DAR-BE-099).
 */
class DarpanEngineAdditionsTests extends Specification {

    private static final String SCHEMA = '''
      <gql-schema>
        <root-query name="report" target-type="Report" service="true" service-name="x.X.get#Report" service-out="report"
                tenant-param="companyUserGroupId" description="A report.">
          <arg name="date" type="String" description="The day."/>
        </root-query>
        <gql-type name="Report" description="One report.">
          <field name="notCovered" list="true" description="Ids with no check."/>
          <edge name="health" kind="embedded" target-type="Health" description="Whether it can be trusted."/>
          <edge name="sections" kind="embedded" target-type="Section" list="true" description="Each section."/>
        </gql-type>
        <gql-type name="Health"><description>Read
            health.</description><field name="complete" type="Boolean"/></gql-type>
        <gql-type name="Section"><field name="id"/></gql-type>
      </gql-schema>'''

    private static def parse(String text) {
        return new SchemaArtifactParser().parse([MNode.parseText("t.gql.xml", text)])
    }

    def "parses tenant-param, embedded edges, scalar lists and descriptions"() {
        when:
        def art = parse(SCHEMA)
        then:
        art.rootQueries.report.tenantParam == "companyUserGroupId"
        art.rootQueries.report.description == "A report."
        art.rootQueries.report.args[0].description == "The day."
        art.types.Report.edges.health.isEmbedded() && !art.types.Report.edges.health.isConnection()
        art.types.Report.edges.sections.isEmbedded() && !art.types.Report.edges.sections.isConnection()
        art.types.Report.fields.notCovered.list
        art.types.Health.description == "Read health."   // child element, whitespace collapsed
    }

    def "a tenant-param that is also an argument is refused: a caller could name another tenant"() {
        when:
        parse('''<gql-schema><root-query name="r" target-type="T" service="true" service-name="x.X.get#R" tenant-param="companyUserGroupId">
                   <arg name="companyUserGroupId"/></root-query><gql-type name="T"><field name="id"/></gql-type></gql-schema>''')
        then:
        IllegalArgumentException e = thrown()
        e.message.contains("tenant-param")
    }

    def "builds embedded lists as [T!]! with no Connection, a scalar list, and prints descriptions"() {
        when:
        def built = new GqlSchemaBuilder().build(parse(SCHEMA))
        GraphQLObjectType report = built.schema.getObjectType("Report")
        String sdl = new SchemaPrinter().print(built.schema)
        then:
        report.getFieldDefinition("sections").type instanceof GraphQLNonNull
        ((GraphQLNonNull) report.getFieldDefinition("sections").type).wrappedType instanceof GraphQLList
        report.getFieldDefinition("sections").arguments.isEmpty()            // in memory: no first/after
        built.schema.getType("SectionConnection") == null
        ((GraphQLNonNull) report.getFieldDefinition("notCovered").type).wrappedType instanceof GraphQLList
        !built.costModel.listFields.contains("sections")                    // no per-element fan-out
        built.schema.queryType.getFieldDefinition("report").getArgument("companyUserGroupId") == null
        sdl.contains('"One report."') && sdl.contains('"Each section."') && sdl.contains('"The day."')
    }
}
