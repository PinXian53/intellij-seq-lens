package com.pino.seqlens.analysis;

import com.intellij.codeInsight.AnnotationUtil;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.util.InheritanceUtil;
import com.intellij.psi.util.PsiUtil;
import com.intellij.psi.util.TypeConversionUtil;
import com.pino.seqlens.sql.DerivedQueryParser;
import com.pino.seqlens.sql.DynamicSql;
import com.pino.seqlens.sql.SqlInfo;
import com.pino.seqlens.sql.SqlOperation;
import com.pino.seqlens.sql.SqlTableExtractor;
import com.pino.seqlens.sql.TableAccess;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 偵測資料存取呼叫：
 * <ul>
 *     <li>Spring Data Repository（衍生查詢、內建 CRUD、{@code @Query}）</li>
 *     <li>JPA {@code EntityManager} / Hibernate {@code Session}</li>
 *     <li>Spring {@code JdbcTemplate} / {@code NamedParameterJdbcTemplate} / {@code JdbcClient}</li>
 * </ul>
 */
final class PersistenceDetector {

    static final String SPRING_DATA_REPOSITORY = "org.springframework.data.repository.Repository";
    private static final Set<String> ENTITY_MANAGERS = Set.of(
            "jakarta.persistence.EntityManager", "javax.persistence.EntityManager",
            "org.hibernate.Session", "org.hibernate.SharedSessionContract", "org.hibernate.StatelessSession");
    private static final Set<String> JDBC_TYPES = Set.of(
            "org.springframework.jdbc.core.JdbcTemplate", "org.springframework.jdbc.core.JdbcOperations",
            "org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate",
            "org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations",
            "org.springframework.jdbc.core.simple.JdbcClient");
    private static final List<String> QUERY_ANNOTATIONS = List.of(
            "org.springframework.data.jpa.repository.Query",
            "org.springframework.data.jdbc.repository.query.Query");

    private final Project project;
    private final EntityResolver entities;
    private final StringResolver strings;

    PersistenceDetector(@NotNull Project project, @NotNull StringResolver strings) {
        this.project = project;
        this.entities = new EntityResolver(project);
        this.strings = strings;
    }

    @Nullable DataAccess detect(@NotNull PsiMethodCallExpression call, @NotNull PsiMethod method) {
        PsiClass declaring = method.getContainingClass();
        if (declaring == null) {
            return null;
        }
        PsiExpression qualifier = call.getMethodExpression().getQualifierExpression();
        PsiClass receiver = qualifier != null ? PsiUtil.resolveClassInClassTypeOnly(qualifier.getType()) : null;
        if (receiver == null || receiver instanceof PsiTypeParameter) {
            receiver = declaring;
        }

        PsiAnnotation dynamicQuery = findAnnotationBySimpleName(method, "DynamicQuery");
        if (dynamicQuery != null) {
            return detectDynamicQuery(method, receiver, dynamicQuery);
        }

        if (InheritanceUtil.isInheritor(receiver, SPRING_DATA_REPOSITORY)) {
            return detectRepository(call, method, receiver);
        }
        if (isAnyOf(receiver, ENTITY_MANAGERS) || isAnyOf(declaring, ENTITY_MANAGERS)) {
            return detectEntityManager(call, method, receiver);
        }
        if (isAnyOf(receiver, JDBC_TYPES) || isAnyOf(declaring, JDBC_TYPES)) {
            return detectJdbc(call, method, receiver);
        }
        return null;
    }

    private static boolean isAnyOf(PsiClass cls, Set<String> fqns) {
        if (cls == null) {
            return false;
        }
        for (String fqn : fqns) {
            if (InheritanceUtil.isInheritor(cls, fqn)) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- @DynamicRepository / @DynamicQuery

    /**
     * 專案自訂的動態 SQL 機制：{@code @DynamicRepository} 介面上的 {@code @DynamicQuery} 方法（僅查詢）。
     * 預設 JPQL，{@code nativeQuery = true} 為原生 SQL；{@code [ ... ]} 為選填片段。
     */
    private @Nullable DataAccess detectDynamicQuery(PsiMethod method, PsiClass repo, PsiAnnotation annotation) {
        String text = strings.resolve(annotation.findAttributeValue("value"));
        if (text == null || text.isBlank()) {
            return null;
        }
        boolean nativeQuery = Boolean.TRUE.equals(AnnotationUtil.getBooleanAttributeValue(annotation, "nativeQuery"));
        String parsable = DynamicSql.stripOptionalMarkers(text);
        Map<String, PsiClass> entityByTable = new HashMap<>();
        SqlInfo parsed = nativeQuery ? SqlTableExtractor.parseSql(parsable) : parseJpql(parsable, entityByTable);
        if (parsed.isEmpty()) {
            return null;
        }
        if (nativeQuery) {
            for (TableAccess access : parsed.accesses()) {
                PsiClass entity = entities.findEntityByTable(access.table());
                if (entity != null) {
                    entityByTable.put(access.table().toLowerCase(Locale.ROOT), entity);
                }
            }
        }
        SqlInfo info = new SqlInfo(parsed.operation(), parsed.accesses(), text.strip());
        return new DataAccess(repo, repo.getName() != null ? repo.getName() : "DynamicRepository",
                info, annotation, entityByTable, false);
    }

    /** 以簡名找註解（專案自訂註解的套件因服務而異）。 */
    static @Nullable PsiAnnotation findAnnotationBySimpleName(@NotNull PsiModifierListOwner owner, @NotNull String simpleName) {
        PsiModifierList modifiers = owner.getModifierList();
        if (modifiers == null) {
            return null;
        }
        for (PsiAnnotation annotation : modifiers.getAnnotations()) {
            String fqn = annotation.getQualifiedName();
            String name = fqn != null ? fqn.substring(fqn.lastIndexOf('.') + 1)
                    : annotation.getNameReferenceElement() != null ? annotation.getNameReferenceElement().getReferenceName() : null;
            if (simpleName.equals(name)) {
                return annotation;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- Spring Data

    private @Nullable DataAccess detectRepository(PsiMethodCallExpression call, PsiMethod method, PsiClass repo) {
        PsiClass entity = repositoryEntity(repo);
        String table = entity != null ? entities.tableName(entity) : guessTableFromRepository(repo);
        Map<String, PsiClass> entityByTable = new HashMap<>();
        if (entity != null) {
            entityByTable.put(table.toLowerCase(Locale.ROOT), entity);
        }

        // 1. @Query
        for (String fqn : QUERY_ANNOTATIONS) {
            PsiAnnotation query = method.getAnnotation(fqn);
            if (query == null) {
                continue;
            }
            String text = constantString(query.findAttributeValue("value"));
            if (text == null || text.isBlank()) {
                break;
            }
            boolean nativeQuery = Boolean.TRUE.equals(AnnotationUtil.getBooleanAttributeValue(query, "nativeQuery"))
                    || fqn.contains(".jdbc.");
            SqlInfo info = nativeQuery ? SqlTableExtractor.parseSql(text) : parseJpql(text, entityByTable);
            if (info.isEmpty()) {
                info = new SqlInfo(info.operation(), List.of(new TableAccess(table, info.operation())), text);
            }
            return new DataAccess(repo, repo.getName(), info, query, entityByTable, false);
        }

        // 2. NamedQuery：Entity.methodName
        if (entity != null) {
            String named = entities.findNamedQuery(entity.getName() + "." + method.getName());
            if (named != null) {
                SqlInfo info = parseJpql(named, entityByTable);
                return new DataAccess(repo, repo.getName(), info, entity, entityByTable, false);
            }
        }

        // 3. 衍生查詢 / 內建 CRUD
        PsiClass finalEntity = entity;
        SqlInfo info = DerivedQueryParser.parse(method.getName(), table,
                prop -> finalEntity != null ? entities.columnName(finalEntity, prop) : null);
        if (info == null) {
            return null; // 自訂實作方法，交給一般流程
        }
        if (info.operation() == SqlOperation.UPSERT && isNewEntityHint(call)) {
            info = new SqlInfo(SqlOperation.INSERT, List.of(new TableAccess(table, SqlOperation.INSERT)), info.sql());
        }
        return new DataAccess(repo, repo.getName(), info, null, entityByTable, true);
    }

    /** save(new Xxx(...)) 時可以確定是 INSERT。 */
    private static boolean isNewEntityHint(PsiMethodCallExpression call) {
        PsiExpression[] args = call.getArgumentList().getExpressions();
        return args.length == 1 && PsiUtil.skipParenthesizedExprDown(args[0]) instanceof PsiNewExpression;
    }

    private @Nullable PsiClass repositoryEntity(PsiClass repo) {
        PsiClass base = JavaPsiFacade.getInstance(project).findClass(SPRING_DATA_REPOSITORY, repo.getResolveScope());
        if (base != null && base.getTypeParameters().length > 0 && InheritanceUtil.isInheritorOrSelf(repo, base, true)) {
            PsiSubstitutor substitutor = TypeConversionUtil.getClassSubstitutor(base, repo, PsiSubstitutor.EMPTY);
            if (substitutor != null) {
                PsiType type = substitutor.substitute(base.getTypeParameters()[0]);
                PsiClass entity = PsiUtil.resolveClassInClassTypeOnly(type);
                if (entity != null && !(entity instanceof PsiTypeParameter)) {
                    return entity;
                }
            }
        }
        // 後備：從直接父介面的第一個泛型參數取得
        for (PsiClassType superType : repo.getSuperTypes()) {
            PsiType[] params = superType.getParameters();
            if (params.length > 0) {
                PsiClass entity = PsiUtil.resolveClassInClassTypeOnly(params[0]);
                if (entity != null && !(entity instanceof PsiTypeParameter)) {
                    return entity;
                }
            }
        }
        return null;
    }

    private static String guessTableFromRepository(PsiClass repo) {
        String name = repo.getName() == null ? "unknown" : repo.getName();
        name = name.replaceAll("(Jpa|Crud)?(Repository|Repo|Dao)$", "");
        return DerivedQueryParser.toSnakeCase(name.isEmpty() ? "unknown" : name);
    }

    private SqlInfo parseJpql(String jpql, Map<String, PsiClass> entityByTable) {
        return SqlTableExtractor.parseJpql(jpql, entityName -> {
            PsiClass cls = entities.findEntity(entityName);
            if (cls == null) {
                return DerivedQueryParser.toSnakeCase(entityName);
            }
            String table = entities.tableName(cls);
            entityByTable.put(table.toLowerCase(Locale.ROOT), cls);
            return table;
        }, entities::associationTarget);
    }

    // ---------------------------------------------------------------- EntityManager / Session

    private @Nullable DataAccess detectEntityManager(PsiMethodCallExpression call, PsiMethod method, PsiClass em) {
        PsiExpression[] args = call.getArgumentList().getExpressions();
        Map<String, PsiClass> entityByTable = new HashMap<>();
        String name = method.getName();
        SqlOperation op;
        switch (name) {
            case "persist", "save" -> op = SqlOperation.INSERT;
            case "merge", "update", "saveOrUpdate" -> op = SqlOperation.UPSERT;
            case "remove", "delete" -> op = SqlOperation.DELETE;
            case "find", "getReference", "get", "load", "refresh", "lock", "byId" -> op = SqlOperation.SELECT;
            case "createQuery", "createNativeQuery", "createNamedQuery", "createSelectionQuery",
                 "createMutationQuery", "createNativeMutationQuery", "createNamedSelectionQuery" -> {
                if (args.length == 0) {
                    return null;
                }
                String text = resolveString(args[0], 0);
                if (text == null) {
                    return null;
                }
                boolean isNative = name.contains("Native");
                PsiElement source = args[0];
                if (name.contains("Named")) {
                    String q = entities.findNamedQuery(text);
                    if (q == null) {
                        return null;
                    }
                    text = q;
                }
                SqlInfo info = isNative ? SqlTableExtractor.parseSql(text) : parseJpql(text, entityByTable);
                if (info.isEmpty()) {
                    return null;
                }
                return new DataAccess(em, componentName(em), info, source, entityByTable, false);
            }
            default -> {
                return null;
            }
        }
        PsiClass entity = entityFromArguments(args);
        if (entity == null) {
            return null;
        }
        String table = entities.tableName(entity);
        entityByTable.put(table.toLowerCase(Locale.ROOT), entity);
        String sql = switch (op) {
            case INSERT -> "INSERT INTO " + table + " (...) VALUES (...)";
            case UPSERT -> "UPDATE " + table + " SET ... WHERE id = ?";
            case DELETE -> "DELETE FROM " + table + " WHERE id = ?";
            default -> "SELECT * FROM " + table + " WHERE id = ?";
        };
        SqlInfo info = new SqlInfo(op, List.of(new TableAccess(table, op)), sql);
        return new DataAccess(em, componentName(em), info, null, entityByTable, true);
    }

    private static String componentName(PsiClass cls) {
        return cls.getName() != null ? cls.getName() : "EntityManager";
    }

    private static @Nullable PsiClass entityFromArguments(PsiExpression[] args) {
        for (PsiExpression arg : args) {
            PsiExpression e = PsiUtil.skipParenthesizedExprDown(arg);
            PsiType type = null;
            if (e instanceof PsiClassObjectAccessExpression classAccess) {
                type = classAccess.getOperand().getType();
            } else if (e != null) {
                type = e.getType();
            }
            PsiClass cls = PsiUtil.resolveClassInClassTypeOnly(type);
            if (EntityResolver.isEntity(cls)) {
                return cls;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- JDBC

    private @Nullable DataAccess detectJdbc(PsiMethodCallExpression call, PsiMethod method, PsiClass jdbc) {
        String name = method.getName();
        if (!(name.startsWith("query") || name.equals("update") || name.equals("batchUpdate")
                || name.equals("execute") || name.equals("sql"))) {
            return null;
        }
        for (PsiExpression arg : call.getArgumentList().getExpressions()) {
            String sql = resolveString(arg, 0);
            if (sql == null) {
                continue;
            }
            SqlInfo info = SqlTableExtractor.parseSql(sql);
            if (info.isEmpty()) {
                continue;
            }
            Map<String, PsiClass> entityByTable = new HashMap<>();
            for (TableAccess access : info.accesses()) {
                PsiClass entity = entities.findEntityByTable(access.table());
                if (entity == null) {
                    entity = entities.findEntity(toPascal(access.table()));
                }
                if (entity != null) {
                    entityByTable.put(access.table().toLowerCase(Locale.ROOT), entity);
                }
            }
            return new DataAccess(jdbc, componentName(jdbc), info, arg, entityByTable, false);
        }
        return null;
    }

    private static String toPascal(String table) {
        StringBuilder sb = new StringBuilder();
        String t = table.contains(".") ? table.substring(table.lastIndexOf('.') + 1) : table;
        for (String part : t.split("_")) {
            if (!part.isEmpty()) {
                sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- 字串解析

    private @Nullable String constantString(@Nullable PsiAnnotationMemberValue value) {
        return strings.resolve(value);
    }

    @Nullable String resolveString(@NotNull PsiExpression expression, int depth) {
        return strings.resolve(expression, depth);
    }
}
