package com.pino.seqlens.sql;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class DerivedQueryParserTest {

    private static SqlInfo parse(String method) {
        return DerivedQueryParser.parse(method, "users", Map.of("emailAddress", "mail")::get);
    }

    @Test
    public void simpleFindBy() {
        SqlInfo info = parse("findByEmailAddressAndStatus");
        assertEquals(SqlOperation.SELECT, info.operation());
        assertEquals("SELECT * FROM users WHERE mail = ? AND status = ?", info.sql());
    }

    @Test
    public void operatorsOrAndOrderBy() {
        assertEquals("SELECT * FROM users WHERE age > ? OR last_name LIKE %?% ORDER BY created_at DESC, id ASC",
                parse("findByAgeGreaterThanOrLastNameContainingOrderByCreatedAtDescIdAsc").sql());
    }

    @Test
    public void propertyContainingOrIsNotSplit() {
        assertEquals("SELECT * FROM users WHERE origin = ? AND order_no IS NULL",
                parse("findByOriginAndOrderNoIsNull").sql());
    }

    @Test
    public void topAndDistinct() {
        assertEquals("SELECT DISTINCT * FROM users WHERE name = ? LIMIT 3", parse("findDistinctTop3ByName").sql());
        assertEquals("SELECT * FROM users WHERE name = ? LIMIT 1", parse("findFirstByName").sql());
    }

    @Test
    public void countExistsDelete() {
        assertEquals("SELECT COUNT(*) FROM users WHERE active = TRUE", parse("countByActiveTrue").sql());
        assertEquals(SqlOperation.DELETE, parse("deleteByCreatedAtBefore").operation());
        assertEquals("DELETE FROM users WHERE created_at < ?", parse("deleteByCreatedAtBefore").sql());
        assertEquals("SELECT 1 FROM users WHERE email = ?", parse("existsByEmail").sql());
    }

    @Test
    public void crudMethods() {
        assertEquals(SqlOperation.UPSERT, parse("save").operation());
        assertEquals(SqlOperation.SELECT, parse("findById").operation());
        assertEquals(SqlOperation.SELECT, parse("findAll").operation());
        assertEquals(SqlOperation.DELETE, parse("deleteById").operation());
    }

    @Test
    public void unknownMethod() {
        assertNull(parse("recalculateEverything"));
    }

    @Test
    public void snakeCase() {
        assertEquals("order_line_item", DerivedQueryParser.toSnakeCase("OrderLineItem"));
        assertEquals("created_at", DerivedQueryParser.toSnakeCase("createdAt"));
    }
}
