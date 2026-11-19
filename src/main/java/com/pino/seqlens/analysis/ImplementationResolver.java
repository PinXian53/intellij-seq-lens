package com.pino.seqlens.analysis;

import com.intellij.codeInsight.AnnotationUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.OverridingMethodsSearch;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;
import com.intellij.psi.util.PsiUtilCore;
import com.pino.seqlens.model.Dispatch;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.beans.Introspector;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 呼叫介面 / 抽象方法時，決定要展開哪一個實作。依序：
 * <ol>
 *     <li>使用者手動選擇（設定中的介面 → 實作對照）</li>
 *     <li>只有一個實作</li>
 *     <li>排除測試程式中的實作後只剩一個</li>
 *     <li>注入點的 {@code @Qualifier} / {@code @Named} 對應到 bean 名稱或實作上的 {@code @Qualifier}</li>
 *     <li>注入點的變數名稱等於 bean 名稱（Spring 依名稱注入的規則）</li>
 *     <li>唯一標了 {@code @Primary} 的實作</li>
 * </ol>
 * 都無法決定時回傳候選清單，由使用者在圖上手動選擇。
 */
final class ImplementationResolver {

    private static final List<String> QUALIFIERS = List.of(
            "org.springframework.beans.factory.annotation.Qualifier", "jakarta.inject.Named", "javax.inject.Named");
    private static final List<String> STEREOTYPES = List.of(
            "org.springframework.stereotype.Component", "org.springframework.stereotype.Service",
            "org.springframework.stereotype.Repository", "org.springframework.stereotype.Controller",
            "org.springframework.web.bind.annotation.RestController", "jakarta.inject.Named", "javax.inject.Named");
    private static final String PRIMARY = "org.springframework.context.annotation.Primary";

    /** 實作結果；{@code chosen} 為 null 代表停在介面上。 */
    record Resolution(@Nullable PsiMethod chosen, @NotNull Dispatch dispatch) {
    }

    private final Project project;
    private final ProjectFileIndex fileIndex;
    private final Map<String, String> manualChoices;
    private final boolean automatic;
    private final Map<PsiMethod, List<PsiMethod>> candidateCache = new HashMap<>();

    ImplementationResolver(@NotNull Project project, @NotNull Map<String, String> manualChoices, boolean automatic) {
        this.project = project;
        this.fileIndex = ProjectFileIndex.getInstance(project);
        this.manualChoices = manualChoices;
        this.automatic = automatic;
    }

    /**
     * @param method       被呼叫的介面 / 抽象方法
     * @param declaredType 呼叫處的宣告型別（例如欄位型別）
     * @param qualifier    呼叫的接收者運算式（例如 {@code paymentGateway}），用來讀取注入點資訊
     * @return 沒有任何實作時回傳 null
     */
    @Nullable Resolution resolve(@NotNull PsiMethod method, @NotNull PsiClass declaredType, @Nullable PsiExpression qualifier) {
        List<PsiMethod> all = candidates(method);
        if (all.isEmpty()) {
            return null;
        }
        List<PsiMethod> production = all.stream().filter(m -> !isTest(m)).toList();
        List<PsiMethod> pool = production.isEmpty() ? all : production;
        String interfaceFqn = interfaceKey(method, declaredType);
        List<String> names = pool.stream().map(ImplementationResolver::classFqn).toList();

        // 1. 手動選擇（介面本身或宣告型別都可以當 key）
        for (String key : new String[]{declaredType.getQualifiedName(), interfaceFqn}) {
            String chosen = key != null ? manualChoices.get(key) : null;
            if (chosen != null) {
                PsiMethod match = all.stream().filter(m -> chosen.equals(classFqn(m))).findFirst().orElse(null);
                if (match != null) {
                    return result(match, interfaceFqn, names, Dispatch.Reason.MANUAL);
                }
            }
        }
        if (!automatic) {
            return new Resolution(null, new Dispatch(interfaceFqn, names, null, Dispatch.Reason.AMBIGUOUS));
        }
        // 2 / 3. 只有一個（正式）實作
        if (all.size() == 1) {
            return result(all.getFirst(), interfaceFqn, names, Dispatch.Reason.SINGLE);
        }
        if (production.size() == 1) {
            return result(production.getFirst(), interfaceFqn, names, Dispatch.Reason.PRODUCTION);
        }
        // 4 / 5. 注入點的 @Qualifier 與變數名稱
        InjectionPoint point = injectionPoint(qualifier);
        if (point != null) {
            if (point.qualifier() != null) {
                PsiMethod match = single(pool, m -> point.qualifier().equals(beanName(m.getContainingClass()))
                        || point.qualifier().equals(classQualifier(m.getContainingClass())));
                if (match != null) {
                    return result(match, interfaceFqn, names, Dispatch.Reason.QUALIFIER);
                }
            }
            for (String name : point.names()) {
                PsiMethod match = single(pool, m -> name.equals(beanName(m.getContainingClass())));
                if (match != null) {
                    return result(match, interfaceFqn, names, Dispatch.Reason.BEAN_NAME);
                }
            }
        }
        // 6. @Primary
        PsiMethod primary = single(pool, m -> m.getContainingClass().hasAnnotation(PRIMARY));
        if (primary != null) {
            return result(primary, interfaceFqn, names, Dispatch.Reason.PRIMARY);
        }
        return new Resolution(null, new Dispatch(interfaceFqn, names, null,
                names.size() > 1 ? Dispatch.Reason.AMBIGUOUS : Dispatch.Reason.SINGLE));
    }

    private static Resolution result(PsiMethod chosen, String interfaceFqn, List<String> names, Dispatch.Reason reason) {
        return new Resolution(chosen, new Dispatch(interfaceFqn, names, classFqn(chosen), reason));
    }

    private static @Nullable PsiMethod single(List<PsiMethod> pool, java.util.function.Predicate<PsiMethod> test) {
        List<PsiMethod> matches = pool.stream().filter(test).toList();
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    private List<PsiMethod> candidates(PsiMethod method) {
        return candidateCache.computeIfAbsent(method, m -> {
            List<PsiMethod> result = new ArrayList<>();
            OverridingMethodsSearch.search(m, GlobalSearchScope.projectScope(project), true).forEach(candidate -> {
                PsiClass cls = candidate.getContainingClass();
                if (candidate.getBody() != null && !candidate.hasModifierProperty(PsiModifier.ABSTRACT)
                        && cls != null && !(cls instanceof PsiAnonymousClass) && cls.getQualifiedName() != null) {
                    result.add(candidate);
                }
                return result.size() < 50;
            });
            result.sort(java.util.Comparator.comparing(ImplementationResolver::classFqn));
            return result;
        });
    }

    private boolean isTest(PsiElement element) {
        VirtualFile file = PsiUtilCore.getVirtualFile(element);
        return file != null && fileIndex.isInTestSourceContent(file);
    }

    private static String interfaceKey(PsiMethod method, PsiClass declaredType) {
        PsiClass declaring = method.getContainingClass();
        String fqn = declaring != null ? declaring.getQualifiedName() : null;
        return fqn != null ? fqn : Objects.requireNonNullElse(declaredType.getQualifiedName(), "?");
    }

    private static String classFqn(PsiMethod method) {
        PsiClass cls = method.getContainingClass();
        return cls != null && cls.getQualifiedName() != null ? cls.getQualifiedName() : "?";
    }

    // ------------------------------------------------------------------ Spring bean 名稱與注入點

    /** bean 名稱：stereotype 註解的 value，否則為類別簡名的 decapitalize（與 Spring 相同規則）。 */
    static @Nullable String beanName(@Nullable PsiClass cls) {
        if (cls == null || cls.getName() == null) {
            return null;
        }
        for (String fqn : STEREOTYPES) {
            PsiAnnotation a = cls.getAnnotation(fqn);
            if (a != null) {
                String value = AnnotationUtil.getStringAttributeValue(a, "value");
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
        }
        return Introspector.decapitalize(cls.getName());
    }

    private static @Nullable String classQualifier(@Nullable PsiClass cls) {
        return cls == null ? null : qualifierValue(cls);
    }

    private static @Nullable String qualifierValue(PsiModifierListOwner owner) {
        for (String fqn : QUALIFIERS) {
            PsiAnnotation a = owner.getAnnotation(fqn);
            if (a != null) {
                String value = AnnotationUtil.getStringAttributeValue(a, "value");
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
        }
        return null;
    }

    /**
     * @param qualifier 注入點上的 @Qualifier / @Named 值
     * @param names     可能的 bean 名稱（欄位名、建構子參數名）
     */
    private record InjectionPoint(@Nullable String qualifier, List<String> names) {
    }

    /** 從接收者運算式找到注入點：欄位本身，以及在建構子中指派給它的參數（建構子注入）。 */
    private static @Nullable InjectionPoint injectionPoint(@Nullable PsiExpression qualifier) {
        PsiExpression expr = PsiUtil.skipParenthesizedExprDown(qualifier);
        if (!(expr instanceof PsiReferenceExpression ref) || !(ref.resolve() instanceof PsiVariable variable)) {
            return null;
        }
        String qualifierValue = qualifierValue(variable);
        List<String> names = new ArrayList<>();
        if (variable.getName() != null) {
            names.add(variable.getName());
        }
        if (variable instanceof PsiField field && field.getContainingClass() != null) {
            for (PsiMethod constructor : field.getContainingClass().getConstructors()) {
                for (PsiAssignmentExpression assignment : PsiTreeUtil.findChildrenOfType(constructor, PsiAssignmentExpression.class)) {
                    if (assignment.getLExpression() instanceof PsiReferenceExpression lhs && lhs.isReferenceTo(field)
                            && PsiUtil.skipParenthesizedExprDown(assignment.getRExpression()) instanceof PsiReferenceExpression rhs
                            && rhs.resolve() instanceof PsiParameter parameter) {
                        if (qualifierValue == null) {
                            qualifierValue = qualifierValue(parameter);
                        }
                        names.add(parameter.getName());
                    }
                }
            }
        }
        return new InjectionPoint(qualifierValue, names.stream().distinct().toList());
    }
}
