package com.pino.seqlens.analysis;

import com.intellij.codeInsight.AnnotationUtil;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

/**
 * 盡量把字串運算式還原成文字，供 SQL 與 HTTP URL 解析使用：
 * 字面值、常數、區域變數 / final 欄位初始值、{@code @Value("${...}")} 欄位、字串相加（無法解析的部分以 ? 代替）、
 * {@code formatted()} / {@code String.format()}。
 */
final class StringResolver {

    private static final String SPRING_VALUE = "org.springframework.beans.factory.annotation.Value";

    private final Project project;

    StringResolver(@NotNull Project project) {
        this.project = project;
    }

    @Nullable String resolve(@Nullable PsiAnnotationMemberValue value) {
        if (value instanceof PsiArrayInitializerMemberValue array) {
            PsiAnnotationMemberValue[] items = array.getInitializers();
            return items.length > 0 ? resolve(items[0]) : null;
        }
        return value instanceof PsiExpression expr ? resolve(expr, 0) : null;
    }

    @Nullable String resolve(@NotNull PsiExpression expression, int depth) {
        if (depth > 6) {
            return null;
        }
        PsiExpression expr = PsiUtil.skipParenthesizedExprDown(expression);
        if (expr == null) {
            return null;
        }
        if (expr instanceof PsiLiteralExpression literal) {
            if (literal.getValue() instanceof String s) {
                return s;
            }
            String text = literal.getText();
            if (text.startsWith("\"\"\"")) {
                return text.substring(3, Math.max(3, text.length() - 3)).stripIndent().strip();
            }
            if (text.length() >= 2 && text.startsWith("\"")) {
                return text.substring(1, text.length() - 1).replace("\\\"", "\"");
            }
            return null;
        }
        Object constant = JavaPsiFacade.getInstance(project).getConstantEvaluationHelper().computeConstantExpression(expr);
        if (constant instanceof String s) {
            return s;
        }
        if (expr instanceof PsiReferenceExpression ref && ref.resolve() instanceof PsiVariable variable
                && !(variable instanceof PsiParameter)) {
            PsiAnnotation value = variable.getAnnotation(SPRING_VALUE);
            if (value != null) {
                String placeholder = AnnotationUtil.getStringAttributeValue(value, "value");
                if (placeholder != null) {
                    // ${payment.url:http://localhost} → ${payment.url}
                    return placeholder.replaceAll("\\$\\{([^}:]+):[^}]*}", "\\${$1}");
                }
            }
            PsiExpression init = variable.getInitializer();
            if (init != null && (variable instanceof PsiLocalVariable || variable.hasModifierProperty(PsiModifier.FINAL))) {
                return resolve(init, depth + 1);
            }
            return null;
        }
        if (expr instanceof PsiPolyadicExpression poly && poly.getOperationTokenType() == JavaTokenType.PLUS) {
            StringBuilder sb = new StringBuilder();
            boolean any = false;
            for (PsiExpression operand : poly.getOperands()) {
                String part = resolve(operand, depth + 1);
                if (part != null) {
                    any = true;
                    sb.append(part);
                } else {
                    sb.append('?');
                }
            }
            return any ? sb.toString() : null;
        }
        if (expr instanceof PsiMethodCallExpression mc) {
            String methodName = mc.getMethodExpression().getReferenceName();
            PsiExpression q = mc.getMethodExpression().getQualifierExpression();
            if (q != null && Set.of("formatted", "stripIndent", "trim", "strip").contains(methodName)) {
                return resolve(q, depth + 1);
            }
            PsiExpression[] args = mc.getArgumentList().getExpressions();
            if ("format".equals(methodName) && args.length > 0) {
                return resolve(args[0], depth + 1);
            }
        }
        return null;
    }
}
