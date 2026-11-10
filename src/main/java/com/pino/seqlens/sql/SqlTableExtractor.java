package com.pino.seqlens.sql;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 以輕量的 token 掃描方式從 SQL / JPQL 中找出使用到的資料表。
 * 不追求完整 SQL 文法，只處理常見的 SELECT / INSERT / UPDATE / DELETE / MERGE、JOIN、子查詢與 CTE。
 */
public final class SqlTableExtractor {

    private static final Pattern LINE_COMMENT = Pattern.compile("--[^\\n]*");
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern STRING_LITERAL = Pattern.compile("'(?:[^']|'')*'");
    private static final Pattern TOKEN = Pattern.compile(
            "\"[^\"]+\"|`[^`]+`|\\[[^\\]]+]|[A-Za-z_$#][\\w$#]*(?:\\s*\\.\\s*(?:\"[^\"]+\"|`[^`]+`|\\[[^\\]]+]|[A-Za-z_$#][\\w$#]*))*|[(),;]|[^\\s\\w]");
    private static final Pattern CTE_NAME = Pattern.compile(
            "(?i)(?:\\bWITH\\s+(?:RECURSIVE\\s+)?|,\\s*)([A-Za-z_][\\w$]*)\\s*(?:\\([^)]*\\)\\s*)?AS\\s*\\(");

    /** FROM 子句中出現時代表 FROM 清單結束的關鍵字。 */
    private static final Set<String> CLAUSE_END = Set.of(
            "WHERE", "GROUP", "ORDER", "HAVING", "LIMIT", "OFFSET", "FETCH", "UNION", "INTERSECT", "EXCEPT",
            "MINUS", "SET", "VALUES", "ON", "USING", "RETURNING", "WINDOW", "FOR", "CONNECT", "START", "QUALIFY",
            "JOIN", "INNER", "LEFT", "RIGHT", "FULL", "CROSS", "NATURAL", "OUTER", "SELECT", "WHEN", "OUTPUT");

    private static final Set<String> NOT_A_TABLE = Set.of(
            "DUAL", "SELECT", "LATERAL", "UNNEST", "TABLE", "ONLY", "IN", "AS", "OBJECT", "ELEMENTS", "TREAT");

    private SqlTableExtractor() {
    }

    /** 解析原生 SQL。 */
    public static @NotNull SqlInfo parseSql(@NotNull String sql) {
        return parse(sql, false, Function.identity(), (entity, property) -> null);
    }

    /**
     * 解析 JPQL / HQL，實體名稱會透過 {@code entityToTable} 轉成表名。
     * {@code entityToTable} 回傳 null 時保留原實體名稱。
     */
    public static @NotNull SqlInfo parseJpql(@NotNull String jpql, @NotNull Function<String, String> entityToTable) {
        return parse(jpql, true, entityToTable, (entity, property) -> null);
    }

    /**
     * 解析 JPQL / HQL，並透過 {@code associationResolver}（實體名稱, 屬性）→ 目標實體名稱
     * 解析 {@code JOIN alias.property} 形式的關聯路徑。
     */
    public static @NotNull SqlInfo parseJpql(@NotNull String jpql,
                                             @NotNull Function<String, String> entityToTable,
                                             @NotNull BiFunction<String, String, String> associationResolver) {
        return parse(jpql, true, entityToTable, associationResolver);
    }

    /** 單次解析的狀態。 */
    private record Ctx(boolean jpql, Set<String> cte, Function<String, String> mapper,
                       BiFunction<String, String, String> association,
                       Map<String, String> aliasToEntity, Map<String, TableAccess> result) {
    }

    private static SqlInfo parse(String original, boolean jpql, Function<String, String> nameMapper,
                                 BiFunction<String, String, String> association) {
        String sql = normalize(original);
        List<String> tokens = tokenize(sql);
        if (tokens.isEmpty()) {
            return new SqlInfo(SqlOperation.UNKNOWN, List.of(), original);
        }
        Set<String> cteNames = findCteNames(sql);
        SqlOperation main = detectOperation(tokens);

        // table（小寫 key）-> access，保持出現順序；主操作的目標表優先
        Map<String, TableAccess> result = new LinkedHashMap<>();
        Ctx ctx = new Ctx(jpql, cteNames, nameMapper, association, new HashMap<>(), result);
        for (int i = 0; i < tokens.size(); i++) {
            String upper = tokens.get(i).toUpperCase(Locale.ROOT);
            switch (upper) {
                case "FROM" -> {
                    SqlOperation op = isDeleteFrom(tokens, i) ? SqlOperation.DELETE : SqlOperation.SELECT;
                    i = readTableList(tokens, i + 1, op, ctx);
                }
                case "JOIN" -> {
                    if (i + 1 < tokens.size()) {
                        int next = i + 1;
                        if (jpql && tokens.get(next).equalsIgnoreCase("FETCH")) {
                            next++;
                        }
                        if (next < tokens.size()) {
                            addTable(tokens, next, SqlOperation.SELECT, ctx);
                        }
                    }
                }
                case "USING" -> {
                    // MERGE INTO t USING source / DELETE FROM a USING b（JOIN ... USING (col) 會接括號，略過）
                    if (i + 1 < tokens.size() && !tokens.get(i + 1).equals("(")) {
                        addTable(tokens, i + 1, SqlOperation.SELECT, ctx);
                    }
                }
                case "UPDATE" -> {
                    // 排除 "FOR UPDATE"、"ON DUPLICATE KEY UPDATE"、"ON CONFLICT ... DO UPDATE"、MERGE 的 "THEN UPDATE"
                    String prev = i > 0 ? tokens.get(i - 1).toUpperCase(Locale.ROOT) : "";
                    if (!Set.of("FOR", "KEY", "DO", "THEN").contains(prev) && i + 1 < tokens.size()) {
                        int next = skipModifiers(tokens, i + 1);
                        if (next < tokens.size()) {
                            addTable(tokens, next, SqlOperation.UPDATE, ctx);
                        }
                    }
                }
                case "INTO" -> {
                    SqlOperation op = intoOperation(tokens, i);
                    if (op != null && i + 1 < tokens.size()) {
                        addTable(tokens, i + 1, op, ctx);
                    }
                }
                case "TRUNCATE" -> {
                    int next = i + 1;
                    if (next < tokens.size() && tokens.get(next).equalsIgnoreCase("TABLE")) {
                        next++;
                    }
                    if (next < tokens.size()) {
                        addTable(tokens, next, SqlOperation.DELETE, ctx);
                    }
                }
                case "DELETE" -> {
                    // "DELETE Entity e WHERE"（JPQL/HQL 省略 FROM 的寫法）
                    if (i + 1 < tokens.size() && !tokens.get(i + 1).equalsIgnoreCase("FROM")
                            && isIdentifier(tokens.get(i + 1))) {
                        addTable(tokens, i + 1, SqlOperation.DELETE, ctx);
                    }
                }
                default -> {
                }
            }
        }
        return new SqlInfo(main, new ArrayList<>(result.values()), original.strip());
    }

    private static String normalize(String sql) {
        String s = BLOCK_COMMENT.matcher(sql).replaceAll(" ");
        s = LINE_COMMENT.matcher(s).replaceAll(" ");
        s = STRING_LITERAL.matcher(s).replaceAll("''");
        return s.replaceAll("\\s+", " ").strip();
    }

    private static List<String> tokenize(String sql) {
        List<String> tokens = new ArrayList<>();
        Matcher m = TOKEN.matcher(sql);
        while (m.find()) {
            tokens.add(m.group().replaceAll("\\s+", ""));
        }
        return tokens;
    }

    private static Set<String> findCteNames(String sql) {
        Set<String> names = new java.util.HashSet<>();
        if (!sql.toUpperCase(Locale.ROOT).startsWith("WITH")) {
            return names;
        }
        Matcher m = CTE_NAME.matcher(sql);
        while (m.find()) {
            names.add(m.group(1).toLowerCase(Locale.ROOT));
        }
        return names;
    }

    private static SqlOperation detectOperation(List<String> tokens) {
        int depth = 0;
        boolean inWith = tokens.getFirst().equalsIgnoreCase("WITH");
        for (String t : tokens) {
            if (t.equals("(")) {
                depth++;
                continue;
            }
            if (t.equals(")")) {
                depth--;
                continue;
            }
            if (inWith && depth > 0) {
                continue;
            }
            switch (t.toUpperCase(Locale.ROOT)) {
                case "SELECT", "FROM" -> {
                    return SqlOperation.SELECT;
                }
                case "INSERT", "REPLACE" -> {
                    return SqlOperation.INSERT;
                }
                case "UPDATE" -> {
                    return SqlOperation.UPDATE;
                }
                case "DELETE", "TRUNCATE" -> {
                    return SqlOperation.DELETE;
                }
                case "MERGE" -> {
                    return SqlOperation.MERGE;
                }
                case "UPSERT" -> {
                    return SqlOperation.UPSERT;
                }
                case "CREATE", "ALTER", "DROP" -> {
                    return SqlOperation.DDL;
                }
                case "CALL", "EXEC", "EXECUTE" -> {
                    return SqlOperation.CALL;
                }
                default -> {
                    if (!inWith) {
                        return SqlOperation.UNKNOWN;
                    }
                }
            }
        }
        return SqlOperation.UNKNOWN;
    }

    private static boolean isDeleteFrom(List<String> tokens, int fromIndex) {
        return fromIndex > 0 && tokens.get(fromIndex - 1).equalsIgnoreCase("DELETE");
    }

    private static @Nullable SqlOperation intoOperation(List<String> tokens, int intoIndex) {
        for (int j = intoIndex - 1; j >= 0 && j >= intoIndex - 4; j--) {
            String t = tokens.get(j).toUpperCase(Locale.ROOT);
            switch (t) {
                case "INSERT", "REPLACE", "UPSERT" -> {
                    return SqlOperation.INSERT;
                }
                case "MERGE" -> {
                    return SqlOperation.MERGE;
                }
                default -> {
                }
            }
        }
        return null; // 例如 SELECT ... INTO var
    }

    private static int skipModifiers(List<String> tokens, int i) {
        while (i < tokens.size() && Set.of("LOW_PRIORITY", "IGNORE", "ONLY", "TOP").contains(tokens.get(i).toUpperCase(Locale.ROOT))) {
            i++;
        }
        return i;
    }

    /** 讀取 FROM 後的表清單（以逗號分隔），回傳最後處理的 token index。 */
    private static int readTableList(List<String> tokens, int start, SqlOperation op, Ctx ctx) {
        int i = start;
        boolean expectTable = true;
        int depth = 0;
        while (i < tokens.size()) {
            String t = tokens.get(i);
            String upper = t.toUpperCase(Locale.ROOT);
            if (t.equals("(")) {
                if (expectTable) {
                    // 子查詢：交給主迴圈處理其中的 FROM
                    return i - 1;
                }
                depth++;
            } else if (t.equals(")")) {
                if (depth == 0) {
                    return i;
                }
                depth--;
            } else if (depth == 0) {
                if (expectTable) {
                    if (ctx.jpql() && upper.equals("IN") && i + 1 < tokens.size() && tokens.get(i + 1).equals("(")) {
                        // JPQL: FROM Order o, IN(o.items) i
                        expectTable = false;
                    } else if (isIdentifier(t)) {
                        // 表名可能與關鍵字同名（例如實體 Order），所以先當成表名處理
                        addTable(tokens, i, op, ctx);
                        expectTable = false;
                    } else {
                        return i - 1;
                    }
                } else if (t.equals(",")) {
                    expectTable = true;
                } else if (t.equals(";") || CLAUSE_END.contains(upper)) {
                    return i - 1;
                }
            }
            i++;
        }
        return i;
    }

    private static boolean isIdentifier(String token) {
        if (token.isEmpty()) {
            return false;
        }
        char c = token.charAt(0);
        return Character.isLetter(c) || c == '_' || c == '"' || c == '`' || c == '[' || c == '$' || c == '#';
    }

    private static void addTable(List<String> tokens, int index, SqlOperation op, Ctx ctx) {
        String token = tokens.get(index);
        if (!isIdentifier(token)) {
            return;
        }
        String name = token;
        if (ctx.jpql()) {
            String entity;
            if (name.contains(".")) {
                entity = resolvePath(name, ctx);
                if (entity == null) {
                    return;
                }
            } else {
                entity = name;
            }
            String alias = aliasAfter(tokens, index);
            if (alias != null) {
                ctx.aliasToEntity().put(alias, entity);
            }
            String mapped = ctx.mapper().apply(entity);
            name = mapped != null ? mapped : entity;
        } else {
            name = unquote(name);
        }
        String upper = name.toUpperCase(Locale.ROOT);
        if (NOT_A_TABLE.contains(upper) || ctx.cte().contains(name.toLowerCase(Locale.ROOT))) {
            return;
        }
        String key = name.toLowerCase(Locale.ROOT);
        Map<String, TableAccess> result = ctx.result();
        TableAccess existing = result.get(key);
        if (existing == null) {
            result.put(key, new TableAccess(name, op));
        } else if (existing.operation() == SqlOperation.SELECT && op != SqlOperation.SELECT) {
            // 寫入操作優先顯示
            result.put(key, new TableAccess(existing.table(), op));
        }
    }

    /** JPQL 路徑：alias.prop1.prop2 → 目標實體名稱；完整類別名稱（com.x.User）→ User。 */
    private static @Nullable String resolvePath(String path, Ctx ctx) {
        String[] parts = path.split("\\.");
        String entity = ctx.aliasToEntity().get(parts[0]);
        if (entity == null) {
            String last = parts[parts.length - 1];
            return !last.isEmpty() && Character.isUpperCase(last.charAt(0)) ? last : null;
        }
        for (int k = 1; k < parts.length && entity != null; k++) {
            entity = ctx.association().apply(entity, parts[k]);
        }
        return entity;
    }

    private static @Nullable String aliasAfter(List<String> tokens, int index) {
        int j = index + 1;
        if (j < tokens.size() && tokens.get(j).equalsIgnoreCase("AS")) {
            j++;
        }
        if (j >= tokens.size()) {
            return null;
        }
        String candidate = tokens.get(j);
        if (!isIdentifier(candidate) || candidate.contains(".") || CLAUSE_END.contains(candidate.toUpperCase(Locale.ROOT))) {
            return null;
        }
        return candidate;
    }

    private static String unquote(String name) {
        StringBuilder sb = new StringBuilder();
        for (String part : name.split("\\.")) {
            if (!sb.isEmpty()) {
                sb.append('.');
            }
            if (part.length() >= 2 && (part.startsWith("\"") || part.startsWith("`") || part.startsWith("["))) {
                part = part.substring(1, part.length() - 1);
            }
            sb.append(part);
        }
        return sb.toString();
    }
}
