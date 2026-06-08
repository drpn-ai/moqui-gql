package org.moqui.gql.exec

import groovy.transform.CompileStatic
import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityFind
import org.moqui.gql.GqlField
import org.moqui.impl.entity.EntityDynamicViewImpl

/**
 * Builds an EntityFind over a dynamic view: the base entity (alias-all, alias PRIME) plus one
 * sub-select member per requested aggregate field. Each aggregate member is `sub-select="true"`,
 * join-optional, correlated on the fk; on a lateral-capable DB (mysql8: from-lateral-style="lateral")
 * Moqui renders it as a page-bounded LATERAL subquery, so e.g. orderItemCount becomes
 * `LEFT JOIN LATERAL (SELECT COUNT(DISTINCT external_id) AS order_item_count FROM order_item
 *  WHERE order_id = PRIME.order_id) ON 1=1` — one scalar per row, 0 over empty.
 *
 * Conditions/ordering/keyset still operate on the base fields, which alias-all exposes under their own
 * names; the aggregate value comes back as the `<field name>` column on each row's map.
 */
@CompileStatic
class AggregateViewBuilder {
    static EntityFind aggregateFind(ExecutionContext ec, String baseEntity, List<GqlField> aggFields) {
        EntityFind ef = ec.entity.find(baseEntity)
        EntityDynamicViewImpl dv = (EntityDynamicViewImpl) ef.makeEntityDynamicView()
        dv.addMemberEntity("PRIME", baseEntity, (String) null, (Boolean) null, (Map<String, String>) null)
        dv.addAliasAll("PRIME", (String) null)
        int i = 0
        for (GqlField af in aggFields) {
            String alias = "AGG" + (i++)
            Map<String, String> keyMap = new LinkedHashMap<String, String>()
            keyMap.put(af.aggregateFk, af.aggregateFk)   // PRIME.fk = child.fk  (e.g. orderId = orderId)
            dv.addMemberEntity(alias, af.aggregateEntity, "PRIME", Boolean.TRUE, keyMap,
                    (List<Map<String, String>>) null, "true")             // sub-select -> LATERAL on mysql8
            dv.addAlias(alias, af.name, af.aggregateField, af.aggregateFunction)  // orderItemCount = count-distinct(externalId)
        }
        dv.setEntityName(baseEntity.replace((char) '.', (char) '_') + "_Agg")
        return ef
    }
}
