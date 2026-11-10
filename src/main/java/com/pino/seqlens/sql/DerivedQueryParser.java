package com.pino.seqlens.sql;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析 Spring Data 衍生查詢方法名稱（例如 {@code findByEmailAndStatusOrderByCreatedAtDesc}），
 * 產生對應的 SQL 操作與示意 SQL。也處理 CrudRepository / JpaRepository 內建方法。
 */
public final class DerivedQueryParser {

    private static final Pattern DERIVED = Pattern.compile(
            "^(find|read|get|query|search|stream|count|exists|delete|remove)(.*?)By(.*)$");

    /** 由長到短排列，避免較短的後綴先匹配。 */
    private static final String[][] OPERATORS = {
            {"IsNotNull", "IS NOT NULL"}, {"NotNull", "IS NOT NULL"},
            {"IsNull", "IS NULL"}, {"Null", "IS NULL"},
            {"IsNotIn", "NOT IN (?)"}, {"NotIn", "NOT IN (?)"},
            {"IsIn", "IN (?)"}, {"In", "IN (?)"},
            {"IsNotLike", "NOT LIKE ?"}, {"NotLike", "NOT LIKE ?"},
            {"IsLike", "LIKE ?"}, {"Like", "LIKE ?"},
            {"IsStartingWith", "LIKE ?%"}, {"StartingWith", "LIKE ?%"}, {"StartsWith", "LIKE ?%"},
            {"IsEndingWith", "LIKE %?"}, {"EndingWith", "LIKE %?"}, {"EndsWith", "LIKE %?"},
            {"IsNotContaining", "NOT LIKE %?%"}, {"NotContaining", "NOT LIKE %?%"},
            {"IsContaining", "LIKE %?%"}, {"Containing", "LIKE %?%"}, {"Contains", "LIKE %?%"},
            {"IsBetween", "BETWEEN ? AND ?"}, {"Between", "BETWEEN ? AND ?"},
            {"IsGreaterThanEqual", ">= ?"}, {"GreaterThanEqual", ">= ?"},
            {"IsGreaterThan", "> ?"}, {"GreaterThan", "> ?"},
            {"IsLessThanEqual", "<= ?"}, {"LessThanEqual", "<= ?"},
            {"IsLessThan", "< ?"}, {"LessThan", "< ?"},
            {"IsAfter", "> ?"}, {"After", "> ?"},
            {"IsBefore", "< ?"}, {"Before", "< ?"},
            {"IsTrue", "= TRUE"}, {"True", "= TRUE"},
            {"IsFalse", "= FALSE"}, {"False", "= FALSE"},
            {"IsNot", "<> ?"}, {"Not", "<> ?"},
            {"IgnoreCase", "= ?"}, {"IgnoringCase", "= ?"},
            {"Equals", "= ?"}, {"Is", "= ?"},
    };

    private DerivedQueryParser() {
    }

    /**
     * @param methodName     repository 方法名稱
     * @param table          實體對應的表名
     * @param propertyToColumn 屬性名稱轉欄位名稱
     * @return 解析結果；若不是可辨識的 repository 方法則回傳 null
     */
    public static @Nullable SqlInfo parse(@NotNull String methodName,
                                          @NotNull String table,
                                          @NotNull Function<String, String> propertyToColumn) {
        SqlInfo builtIn = builtIn(methodName, table);
        if (builtIn != null) {
            return builtIn;
        }
        Matcher m = DERIVED.matcher(methodName);
        if (!m.matches()) {
            return null;
        }
        String verb = m.group(1);
        String subject = m.group(2);
        String criteria = m.group(3);

        String orderBy = null;
        int orderIdx = criteria.indexOf("OrderBy");
        if (orderIdx >= 0) {
            orderBy = criteria.substring(orderIdx + "OrderBy".length());
            criteria = criteria.substring(0, orderIdx);
        }

        StringBuilder where = new StringBuilder();
        if (!criteria.isEmpty()) {
            for (String orPart : splitKeyword(criteria, "Or")) {
                if (!where.isEmpty()) {
                    where.append(" OR ");
                }
                List<String> andParts = splitKeyword(orPart, "And");
                for (int i = 0; i < andParts.size(); i++) {
                    if (i > 0) {
                        where.append(" AND ");
                    }
                    where.append(condition(andParts.get(i), propertyToColumn));
                }
            }
        }

        SqlOperation op;
        String head;
        switch (verb) {
            case "count" -> {
                op = SqlOperation.SELECT;
                head = "SELECT COUNT(*) FROM " + table;
            }
            case "exists" -> {
                op = SqlOperation.SELECT;
                head = "SELECT 1 FROM " + table;
            }
            case "delete", "remove" -> {
                op = SqlOperation.DELETE;
                head = "DELETE FROM " + table;
            }
            default -> {
                op = SqlOperation.SELECT;
                String distinct = subject.contains("Distinct") ? "DISTINCT " : "";
                head = "SELECT " + distinct + "* FROM " + table;
            }
        }
        StringBuilder sql = new StringBuilder(head);
        if (!where.isEmpty()) {
            sql.append(" WHERE ").append(where);
        }
        if (orderBy != null && !orderBy.isEmpty()) {
            sql.append(" ORDER BY ").append(orderBy(orderBy, propertyToColumn));
        }
        Matcher limit = Pattern.compile("(?:First|Top)(\\d*)").matcher(subject);
        if (limit.find()) {
            String n = limit.group(1).isEmpty() ? "1" : limit.group(1);
            sql.append(" LIMIT ").append(n);
        }
        return new SqlInfo(op, List.of(new TableAccess(table, op)), sql.toString());
    }

    private static @Nullable SqlInfo builtIn(String name, String table) {
        SqlOperation op;
        String sql;
        switch (name) {
            case "save", "saveAndFlush", "saveAll", "saveAllAndFlush", "persist", "merge", "update" -> {
                op = SqlOperation.UPSERT;
                sql = "INSERT INTO " + table + " ... / UPDATE " + table + " SET ... WHERE id = ?";
            }
            case "insert" -> {
                op = SqlOperation.INSERT;
                sql = "INSERT INTO " + table + " (...) VALUES (...)";
            }
            case "findById", "getById", "getReferenceById", "getOne" -> {
                op = SqlOperation.SELECT;
                sql = "SELECT * FROM " + table + " WHERE id = ?";
            }
            case "findAllById" -> {
                op = SqlOperation.SELECT;
                sql = "SELECT * FROM " + table + " WHERE id IN (?)";
            }
            case "findAll", "findOne", "findBy", "findAllBy" -> {
                op = SqlOperation.SELECT;
                sql = "SELECT * FROM " + table;
            }
            case "existsById" -> {
                op = SqlOperation.SELECT;
                sql = "SELECT 1 FROM " + table + " WHERE id = ?";
            }
            case "count", "exists" -> {
                op = SqlOperation.SELECT;
                sql = "SELECT COUNT(*) FROM " + table;
            }
            case "deleteById" -> {
                op = SqlOperation.DELETE;
                sql = "DELETE FROM " + table + " WHERE id = ?";
            }
            case "delete", "deleteAll", "deleteAllById", "deleteAllInBatch", "deleteAllByIdInBatch",
                 "deleteInBatch", "deleteAllByIdInBatchImpl" -> {
                op = SqlOperation.DELETE;
                sql = "DELETE FROM " + table + (name.equals("deleteAll") || name.equals("deleteAllInBatch") ? "" : " WHERE id ...");
            }
            default -> {
                return null;
            }
        }
        return new SqlInfo(op, List.of(new TableAccess(table, op)), sql);
    }

    /** 依關鍵字（And / Or）切割，關鍵字後必須是大寫字母開頭才算邊界。 */
    static List<String> splitKeyword(String text, String keyword) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        int i = 1;
        while (i < text.length()) {
            char prev = text.charAt(i - 1);
            if (text.startsWith(keyword, i)
                    && (Character.isLowerCase(prev) || Character.isDigit(prev))
                    && i + keyword.length() < text.length()
                    && Character.isUpperCase(text.charAt(i + keyword.length()))
                    && !isOperatorBoundary(text, i, keyword)) {
                parts.add(text.substring(start, i));
                start = i + keyword.length();
                i = start + 1;
            } else {
                i++;
            }
        }
        parts.add(text.substring(start));
        return parts;
    }

    /** 避免把 "Ordered" 或 "Origin" 等屬性名中的 Or 切開：只有 Or 後面接大寫才算，這裡額外排除 OrderBy。 */
    private static boolean isOperatorBoundary(String text, int i, String keyword) {
        return keyword.equals("Or") && text.startsWith("OrderBy", i);
    }

    private static String condition(String part, Function<String, String> propertyToColumn) {
        for (String[] op : OPERATORS) {
            if (part.endsWith(op[0]) && part.length() > op[0].length()) {
                String property = part.substring(0, part.length() - op[0].length());
                if (property.endsWith("AllIgnoreCase")) {
                    property = property.substring(0, property.length() - "AllIgnoreCase".length());
                }
                return column(property, propertyToColumn) + " " + op[1];
            }
        }
        return column(part, propertyToColumn) + " = ?";
    }

    private static String orderBy(String text, Function<String, String> propertyToColumn) {
        StringBuilder sb = new StringBuilder();
        // 以 Asc/Desc 為分隔處理多欄排序，例如 OrderByLastNameAscCreatedAtDesc
        String[] chunks = text.split("(?<=Asc)|(?<=Desc)");
        for (String chunk : chunks) {
            if (chunk.isEmpty()) {
                continue;
            }
            String dir = "";
            String prop = chunk;
            if (chunk.endsWith("Desc")) {
                dir = " DESC";
                prop = chunk.substring(0, chunk.length() - 4);
            } else if (chunk.endsWith("Asc")) {
                dir = " ASC";
                prop = chunk.substring(0, chunk.length() - 3);
            }
            if (prop.isEmpty()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append(column(prop, propertyToColumn)).append(dir);
        }
        return sb.toString();
    }

    private static String column(String property, Function<String, String> propertyToColumn) {
        String prop = Character.toLowerCase(property.charAt(0)) + property.substring(1);
        String mapped = propertyToColumn.apply(prop);
        return mapped != null ? mapped : toSnakeCase(prop);
    }

    /** Spring Boot 預設命名策略：camelCase → snake_case。 */
    public static @NotNull String toSnakeCase(@NotNull String name) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0 && name.charAt(i - 1) != '_' && name.charAt(i - 1) != '.') {
                    sb.append('_');
                }
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }
}
