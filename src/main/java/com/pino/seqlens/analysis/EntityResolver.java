package com.pino.seqlens.analysis;

import com.intellij.codeInsight.AnnotationUtil;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiArrayInitializerMemberValue;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiType;
import com.intellij.psi.util.PsiUtil;
import com.intellij.psi.PsiModifierListOwner;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.AnnotatedElementsSearch;
import com.pino.seqlens.sql.DerivedQueryParser;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 找出 JPA 實體與其對應的表名、欄位名、NamedQuery。 */
final class EntityResolver {

    static final List<String> PERSISTENCE_PACKAGES = List.of("jakarta.persistence.", "javax.persistence.");

    private final Project project;
    private Map<String, PsiClass> entitiesByName;
    private Map<String, String> namedQueries;

    EntityResolver(@NotNull Project project) {
        this.project = project;
    }

    static @Nullable PsiAnnotation persistenceAnnotation(@NotNull PsiModifierListOwner owner, @NotNull String simpleName) {
        for (String pkg : PERSISTENCE_PACKAGES) {
            PsiAnnotation a = owner.getAnnotation(pkg + simpleName);
            if (a != null) {
                return a;
            }
        }
        return null;
    }

    static boolean isEntity(@Nullable PsiClass cls) {
        return cls != null && persistenceAnnotation(cls, "Entity") != null;
    }

    /** JPQL 的實體名稱（@Entity(name) 或類別簡名）→ 實體類別。 */
    @Nullable PsiClass findEntity(@NotNull String entityName) {
        ensureIndex();
        PsiClass cls = entitiesByName.get(entityName);
        if (cls == null) {
            cls = entitiesByName.get(entityName.toLowerCase(Locale.ROOT));
        }
        return cls;
    }

    /** 依表名找實體（比對 @Table(name) 或推導出的表名，不分大小寫、可含 schema）。 */
    @Nullable PsiClass findEntityByTable(@NotNull String table) {
        ensureIndex();
        String wanted = table.toLowerCase(Locale.ROOT);
        String wantedNoSchema = wanted.contains(".") ? wanted.substring(wanted.lastIndexOf('.') + 1) : wanted;
        for (PsiClass cls : new java.util.LinkedHashSet<>(entitiesByName.values())) {
            String name = tableName(cls).toLowerCase(Locale.ROOT);
            String nameNoSchema = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : name;
            if (name.equals(wanted) || nameNoSchema.equals(wantedNoSchema)) {
                return cls;
            }
        }
        return null;
    }

    @Nullable String findNamedQuery(@NotNull String name) {
        ensureIndex();
        return namedQueries.get(name);
    }

    private void ensureIndex() {
        if (entitiesByName != null) {
            return;
        }
        entitiesByName = new HashMap<>();
        namedQueries = new HashMap<>();
        GlobalSearchScope scope = GlobalSearchScope.projectScope(project);
        GlobalSearchScope all = GlobalSearchScope.allScope(project);
        for (String pkg : PERSISTENCE_PACKAGES) {
            PsiClass entityAnnotation = JavaPsiFacade.getInstance(project).findClass(pkg + "Entity", all);
            if (entityAnnotation == null) {
                continue;
            }
            for (PsiClass cls : AnnotatedElementsSearch.searchPsiClasses(entityAnnotation, scope).findAll()) {
                PsiAnnotation entity = cls.getAnnotation(pkg + "Entity");
                String name = entity != null ? AnnotationUtil.getStringAttributeValue(entity, "name") : null;
                if (name == null || name.isEmpty()) {
                    name = cls.getName();
                }
                for (String key : new String[]{name, cls.getName()}) {
                    if (key != null) {
                        entitiesByName.putIfAbsent(key, cls);
                        entitiesByName.putIfAbsent(key.toLowerCase(Locale.ROOT), cls);
                    }
                }
                collectNamedQueries(cls, pkg);
            }
        }
    }

    private void collectNamedQueries(PsiClass cls, String pkg) {
        for (String annotationName : List.of("NamedQuery", "NamedNativeQuery")) {
            PsiAnnotation single = cls.getAnnotation(pkg + annotationName);
            if (single != null) {
                addNamedQuery(single);
            }
            PsiAnnotation container = cls.getAnnotation(pkg + annotationName + "s");
            if (container != null) {
                PsiAnnotationMemberValue value = container.findAttributeValue("value");
                if (value instanceof PsiArrayInitializerMemberValue array) {
                    for (PsiAnnotationMemberValue v : array.getInitializers()) {
                        if (v instanceof PsiAnnotation a) {
                            addNamedQuery(a);
                        }
                    }
                } else if (value instanceof PsiAnnotation a) {
                    addNamedQuery(a);
                }
            }
        }
    }

    private void addNamedQuery(PsiAnnotation a) {
        String name = AnnotationUtil.getStringAttributeValue(a, "name");
        String query = AnnotationUtil.getStringAttributeValue(a, "query");
        if (name != null && query != null) {
            namedQueries.put(name, query);
        }
    }

    /** JPQL 關聯路徑：實體的屬性（ManyToOne / OneToMany 等）指向的目標實體名稱；非實體則回傳 null。 */
    @Nullable String associationTarget(@NotNull String entityName, @NotNull String property) {
        PsiClass entity = findEntity(entityName);
        if (entity == null) {
            return null;
        }
        PsiField field = entity.findFieldByName(property, true);
        if (field == null) {
            return null;
        }
        PsiType type = field.getType();
        if (type instanceof PsiClassType classType && classType.getParameterCount() > 0) {
            // List<Order> / Set<Order> / Map<K, Order>：取最後一個泛型參數
            PsiType[] params = classType.getParameters();
            PsiClass element = PsiUtil.resolveClassInClassTypeOnly(params[params.length - 1]);
            if (isEntity(element)) {
                return element.getName();
            }
        }
        PsiClass target = PsiUtil.resolveClassInClassTypeOnly(type);
        return isEntity(target) ? target.getName() : null;
    }

    /** 實體對應的表名：@Table(name) → @Entity(name) → 類別名，未明確指定時轉為 snake_case（Spring Boot 預設）。 */
    @NotNull String tableName(@NotNull PsiClass entity) {
        PsiAnnotation table = persistenceAnnotation(entity, "Table");
        if (table != null) {
            String name = AnnotationUtil.getStringAttributeValue(table, "name");
            if (name != null && !name.isBlank()) {
                String schema = AnnotationUtil.getStringAttributeValue(table, "schema");
                return schema != null && !schema.isBlank() ? schema + "." + name : name;
            }
        }
        PsiAnnotation e = persistenceAnnotation(entity, "Entity");
        String name = e != null ? AnnotationUtil.getStringAttributeValue(e, "name") : null;
        if (name == null || name.isBlank()) {
            name = entity.getName() != null ? entity.getName() : "unknown";
        }
        return DerivedQueryParser.toSnakeCase(name);
    }

    /** 屬性對應的欄位名：@Column(name) / @JoinColumn(name)，否則 snake_case。 */
    @Nullable String columnName(@NotNull PsiClass entity, @NotNull String property) {
        PsiField field = entity.findFieldByName(property, true);
        if (field == null) {
            return null;
        }
        for (String ann : List.of("Column", "JoinColumn")) {
            PsiAnnotation a = persistenceAnnotation(field, ann);
            if (a != null) {
                String name = AnnotationUtil.getStringAttributeValue(a, "name");
                if (name != null && !name.isBlank()) {
                    return name;
                }
                if (ann.equals("JoinColumn")) {
                    return DerivedQueryParser.toSnakeCase(property) + "_id";
                }
            }
        }
        if (persistenceAnnotation(field, "ManyToOne") != null || persistenceAnnotation(field, "OneToOne") != null) {
            return DerivedQueryParser.toSnakeCase(property) + "_id";
        }
        return DerivedQueryParser.toSnakeCase(property);
    }
}
