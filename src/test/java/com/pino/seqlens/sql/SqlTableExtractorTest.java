package com.pino.seqlens.sql;

import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public class SqlTableExtractorTest {

    private static List<String> tables(SqlInfo info) {
        return info.accesses().stream().map(a -> a.table() + ":" + a.operation()).toList();
    }

    @Test
    public void selectWithJoins() {
        SqlInfo info = SqlTableExtractor.parseSql(
                "SELECT u.*, o.id FROM users u LEFT JOIN orders o ON o.user_id = u.id INNER JOIN shop.items i ON i.id = o.item_id WHERE u.name = 'from x'");
        assertEquals(SqlOperation.SELECT, info.operation());
        assertEquals(List.of("users:SELECT", "orders:SELECT", "shop.items:SELECT"), tables(info));
    }

    @Test
    public void commaSeparatedFromAndSubquery() {
        SqlInfo info = SqlTableExtractor.parseSql(
                "select * from a, b bb where a.id in (select a_id from c where c.x = 1) order by a.id");
        assertEquals(List.of("a:SELECT", "b:SELECT", "c:SELECT"), tables(info));
    }

    @Test
    public void insertSelect() {
        SqlInfo info = SqlTableExtractor.parseSql("INSERT INTO archive (id) SELECT id FROM users WHERE deleted = 1");
        assertEquals(SqlOperation.INSERT, info.operation());
        assertEquals(List.of("archive:INSERT", "users:SELECT"), tables(info));
    }

    @Test
    public void updateAndDelete() {
        assertEquals(List.of("users:UPDATE"), tables(SqlTableExtractor.parseSql("UPDATE users SET name = ? WHERE id = ?")));
        assertEquals(List.of("users:DELETE"), tables(SqlTableExtractor.parseSql("DELETE FROM users WHERE id = ?")));
        assertEquals(SqlOperation.DELETE, SqlTableExtractor.parseSql("delete from \"Users\" where id = 1").operation());
        assertEquals(List.of("Users:DELETE"), tables(SqlTableExtractor.parseSql("delete from \"Users\" where id = 1")));
    }

    @Test
    public void selectForUpdateIsNotUpdate() {
        SqlInfo info = SqlTableExtractor.parseSql("SELECT * FROM accounts WHERE id = ? FOR UPDATE");
        assertEquals(SqlOperation.SELECT, info.operation());
        assertEquals(List.of("accounts:SELECT"), tables(info));
    }

    @Test
    public void cteNamesAreNotTables() {
        SqlInfo info = SqlTableExtractor.parseSql(
                "WITH recent AS (SELECT * FROM orders WHERE created > now()) SELECT * FROM recent r JOIN users u ON u.id = r.user_id");
        assertEquals(SqlOperation.SELECT, info.operation());
        assertEquals(List.of("orders:SELECT", "users:SELECT"), tables(info));
    }

    @Test
    public void commentsAreIgnored() {
        SqlInfo info = SqlTableExtractor.parseSql("-- from fake\nSELECT /* from fake2 */ id FROM real_table");
        assertEquals(List.of("real_table:SELECT"), tables(info));
    }

    @Test
    public void jpqlMapsEntitiesAndIgnoresJoinPaths() {
        Map<String, String> mapping = Map.of("User", "app_user", "Order", "orders");
        SqlInfo info = SqlTableExtractor.parseJpql(
                "select distinct u from User u join fetch u.orders o where exists (select 1 from Order x where x.user = u)",
                mapping::get);
        assertEquals(List.of("app_user:SELECT", "orders:SELECT"), tables(info));
    }

    @Test
    public void jpqlJoinPathsAreResolvedThroughAssociations() {
        Map<String, String> tables = Map.of("Order", "orders", "Customer", "customer", "Item", "order_item");
        Map<String, String> assoc = Map.of("Order.customer", "Customer", "Order.items", "Item");
        SqlInfo info = SqlTableExtractor.parseJpql(
                "select o from Order o join o.customer c left join fetch o.items i where c.vip = true",
                tables::get, (entity, prop) -> assoc.get(entity + "." + prop));
        assertEquals(List.of("orders:SELECT", "customer:SELECT", "order_item:SELECT"), tables(info));
    }

    @Test
    public void jpqlUpdateAndDeleteWithoutFrom() {
        assertEquals(List.of("app_user:UPDATE"),
                tables(SqlTableExtractor.parseJpql("update User u set u.active = false where u.id = :id", n -> "app_user")));
        assertEquals(List.of("app_user:DELETE"),
                tables(SqlTableExtractor.parseJpql("delete User u where u.id = :id", n -> "app_user")));
    }

    @Test
    public void mergeStatement() {
        SqlInfo info = SqlTableExtractor.parseSql(
                "MERGE INTO target t USING source s ON (t.id = s.id) WHEN MATCHED THEN UPDATE SET t.v = s.v");
        assertEquals(SqlOperation.MERGE, info.operation());
        assertEquals(List.of("target:MERGE", "source:SELECT"), tables(info));
    }
}
