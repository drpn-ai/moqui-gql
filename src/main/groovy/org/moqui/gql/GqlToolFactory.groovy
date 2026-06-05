package org.moqui.gql

import groovy.transform.CompileStatic
import org.moqui.context.ExecutionContextFactory
import org.moqui.context.ToolFactory
import org.moqui.resource.ResourceReference
import org.moqui.util.MNode
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/** Builds the GraphQLSchema + cost model ONCE at startup and caches it (decision 6 / perf).
 *  Scans every component's graphql/*.gql.xml. getInstance() returns the cached BuiltSchema. */
@CompileStatic
class GqlToolFactory implements ToolFactory<BuiltSchema> {
    private final static Logger logger = LoggerFactory.getLogger(GqlToolFactory.class)
    private ExecutionContextFactory ecf
    private volatile BuiltSchema built

    @Override String getName() { return "GraphQL" }

    @Override void init(ExecutionContextFactory ecf) {
        this.ecf = ecf
        List<MNode> nodes = new ArrayList<MNode>()
        int fileCount = 0
        for (String compName in ecf.getComponentBaseLocations().keySet()) {
            ResourceReference dir = ecf.getResource().getLocationReference("component://" + compName + "/graphql")
            if (dir == null || !dir.getExists() || !dir.isDirectory()) continue
            for (ResourceReference rr in dir.getDirectoryEntries()) {
                if (rr.getFileName().endsWith(".gql.xml")) { nodes.add(MNode.parse(rr)); fileCount++ }
            }
        }
        SchemaArtifact art = new SchemaArtifactParser().parse(nodes)
        this.built = new GqlSchemaBuilder().build(art)
        logger.info("GraphQL schema built: ${art.types.size()} types, ${art.rootQueries.size()} root queries from ${fileCount} artifact file(s)")
    }

    @Override BuiltSchema getInstance(Object... parameters) { return built }

    @Override void destroy() { }
}
