package org.moqui.gql.exec

import groovy.transform.CompileStatic

/** Resolved batching metadata for one nested has-many edge, derived once from the Moqui relationship
 *  definition. loaderName is the per-request DataLoader key (unique per parent type + edge). */
@CompileStatic
class NestedEdgeMeta {
    String typeName        // parent gql type (e.g. Order)
    String edgeName        // edge field (e.g. orderItems)
    String loaderName      // DataLoader registry key, "<typeName>.<edgeName>"
    String parentKeyField  // field on the parent entity that joins to the child fk
    String childEntityName // full child entity name
    String fkField         // child field holding the parent key
    List<String> intraGroupFields // child PK minus the fk: natural order of children within a parent
    boolean plain          // true -> plain [Type!]! list; false -> Relay connection
    boolean single         // true -> has-one (single object), batched first-row-per-parent
}
