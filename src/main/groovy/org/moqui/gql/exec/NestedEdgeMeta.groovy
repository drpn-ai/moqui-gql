package org.moqui.gql.exec

import groovy.transform.CompileStatic

/** Resolved batching metadata for one nested has-many edge, derived once from the Moqui relationship
 *  definition. loaderName is the per-request DataLoader key (unique per parent type + edge). */
@CompileStatic
class NestedEdgeMeta {
    String typeName        // parent gql type (e.g. Order)
    String edgeName        // edge field (e.g. orderItems)
    String loaderName      // DataLoader registry key, "<typeName>.<edgeName>"
    String parentKeyField  // first parent-side join field (back-compat / has-one); see parentKeyFields for the full list
    String childEntityName // full child entity name
    String fkField         // first child-side fk field (back-compat / has-one); see fkFields for the full list
    List<String> intraGroupFields // child PK minus the fk fields: natural order of children within a parent
    boolean plain          // true -> plain [Type!]! list; false -> Relay connection
    boolean single         // true -> has-one (single object), batched first-row-per-parent
    // composite-key support (#38): for has-many list edges these carry the FULL join key (parallel lists).
    // size 1 = single-key (identical to the old single-field path); size > 1 = composite key.
    List<String> parentKeyFields   // parent-side join fields (in relationship key-map order)
    List<String> fkFields          // child-side fk fields, parallel to parentKeyFields
    String excludeEmptyRelationship // optional: drop parents with no rows in this child relationship (e.g. "items")
}
