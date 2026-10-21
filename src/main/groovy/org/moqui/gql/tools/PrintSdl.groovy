package org.moqui.gql.tools

import graphql.schema.idl.SchemaPrinter
import org.moqui.gql.GqlSchemaBuilder
import org.moqui.gql.SchemaArtifactParser
import org.moqui.util.MNode

/**
 * DAR-DOCS-003. Prints the SDL that /rest/s1/graphql/sdl serves, without a running server: parses the given
 * graphql/*.gql.xml files and builds the schema exactly as GqlToolFactory does. The docs site generates its GraphQL
 * reference from this output; ./gradlew :runtime:component:moqui-gql:printSdl -PsdlOut=<file>
 */
class PrintSdl {
    static void main(String[] args) {
        if (args.length < 2) throw new IllegalArgumentException("usage: PrintSdl <out.graphql> <schema.gql.xml>...")
        List<MNode> nodes = args.drop(1).collect { String path -> MNode.parse(new File(path)) }
        String sdl = new SchemaPrinter().print(new GqlSchemaBuilder().build(new SchemaArtifactParser().parse(nodes)).schema)
        new File(args[0]).text = sdl
        println "wrote ${args[0]} (${sdl.readLines().size()} lines) from ${nodes.size()} artifact file(s)"
    }
}
