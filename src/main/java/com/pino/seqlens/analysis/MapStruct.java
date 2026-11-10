package com.pino.seqlens.analysis;

import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypes;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** MapStruct mapper 的辨識。 */
final class MapStruct {

    private static final String MAPPER = "org.mapstruct.Mapper";
    private static final String DECORATED_WITH = "org.mapstruct.DecoratedWith";
    private static final Set<String> GENERATED = Set.of(
            "javax.annotation.processing.Generated", "javax.annotation.Generated", "jakarta.annotation.Generated");
    /** mapper 方法中不屬於來源物件的參數（@MappingTarget 以外的 context 等）。 */
    private static final Set<String> NON_SOURCE_ANNOTATIONS = Set.of("org.mapstruct.Context", "org.mapstruct.MappingTarget");

    private MapStruct() {
    }

    /**
     * 回傳代表此 mapper 的類別：標了 {@code @Mapper} 的介面 / 抽象類別本身；
     * 若是 MapStruct 產生的 {@code XxxMapperImpl}（或 decorator）則回傳它實作的 mapper 介面。非 mapper 回傳 null。
     */
    static @Nullable PsiClass mapperOf(@Nullable PsiClass cls) {
        if (cls == null) {
            return null;
        }
        if (cls.hasAnnotation(MAPPER) || cls.hasAnnotation(DECORATED_WITH)) {
            return cls;
        }
        boolean generated = isGeneratedByMapStruct(cls);
        for (PsiClass sup : cls.getSupers()) {
            if (sup.hasAnnotation(MAPPER)) {
                // 只有 MapStruct 產生的 Impl 或 decorator 才折回 mapper 介面；手寫的子類別照常處理
                if (generated || cls.getName() != null && cls.getName().endsWith("Impl")) {
                    return sup;
                }
            }
        }
        return null;
    }

    private static boolean isGeneratedByMapStruct(PsiClass cls) {
        for (String fqn : GENERATED) {
            PsiAnnotation generated = cls.getAnnotation(fqn);
            if (generated != null) {
                PsiAnnotationMemberValue value = generated.findAttributeValue("value");
                if (value != null && value.getText().contains("mapstruct")) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 轉換方向，例如 {@code User → UserDto}、{@code UserDto, Address → User}。 */
    static @Nullable String direction(PsiMethod method) {
        PsiType returnType = method.getReturnType();
        List<String> sources = new ArrayList<>();
        String target = null;
        for (PsiParameter p : method.getParameterList().getParameters()) {
            if (p.hasAnnotation("org.mapstruct.MappingTarget")) {
                target = p.getType().getPresentableText();
            } else if (NON_SOURCE_ANNOTATIONS.stream().noneMatch(p::hasAnnotation)) {
                sources.add(p.getType().getPresentableText());
            }
        }
        if (target == null && returnType != null && !PsiTypes.voidType().equals(returnType)) {
            target = returnType.getPresentableText();
        }
        if (sources.isEmpty() || target == null) {
            return null;
        }
        return String.join(", ", sources) + " → " + target;
    }
}
