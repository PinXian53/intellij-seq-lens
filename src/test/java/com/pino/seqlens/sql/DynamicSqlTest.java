package com.pino.seqlens.sql;

import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class DynamicSqlTest {

    private static final String JPQL = """
            SELECT new demo.shop.vo.OrderRowVo(o.orderNo, c.name, o.total)
            FROM OrderEntity o
            LEFT JOIN CustomerEntity c ON o.customerId = c.id AND c.region = :region
            WHERE o.createdAt >= :since
            [ AND o.state IN :states ]
            [ AND EXISTS (SELECT 1 FROM RefundEntity r WHERE r.orderId = o.id AND r.reason = :reason) ]
            """;

    @Test
    public void stripMarkersKeepsFragmentContent() {
        String stripped = DynamicSql.stripOptionalMarkers(JPQL);
        assertFalse(stripped.contains("["));
        assertFalse(stripped.contains("]"));
        assertEquals(2, DynamicSql.optionalFragmentCount(JPQL));
        assertEquals("a = '[x]'  AND b = :b ", DynamicSql.stripOptionalMarkers("a = '[x]' [AND b = :b]"));
    }

    @Test
    public void jpqlConstructorExpressionAndEntityJoin() {
        Map<String, String> tables = Map.of("OrderEntity", "orders", "CustomerEntity", "customer", "RefundEntity", "refund");
        SqlInfo info = SqlTableExtractor.parseJpql(DynamicSql.stripOptionalMarkers(JPQL), tables::get);
        assertEquals(SqlOperation.SELECT, info.operation());
        assertEquals(List.of("orders", "customer", "refund"), info.accesses().stream().map(TableAccess::table).toList());
    }
}
