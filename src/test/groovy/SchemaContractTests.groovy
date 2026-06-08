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
import graphql.schema.idl.SchemaPrinter
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
        assert sdl != null && !sdl.isEmpty() : "docs/schema.graphql not found or empty at component://moqui-gql/docs/schema.graphql"
        assert contract.getType("Query", ObjectTypeDefinition.class).isPresent() : "contract SDL parsed but has no `type Query`"
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
}
