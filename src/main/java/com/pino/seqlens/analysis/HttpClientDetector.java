package com.pino.seqlens.analysis;

import com.intellij.codeInsight.AnnotationUtil;
import com.intellij.psi.*;
import com.intellij.psi.util.InheritanceUtil;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 偵測 Spring 的對外 HTTP 呼叫：
 * <ul>
 *     <li>{@code RestTemplate} / {@code RestOperations}</li>
 *     <li>{@code RestClient} 與 {@code WebClient} 的 fluent 呼叫鏈</li>
 *     <li>宣告式 HTTP interface（{@code @HttpExchange}、{@code @GetExchange}…）</li>
 *     <li>Spring Cloud OpenFeign（{@code @FeignClient} + {@code @GetMapping}… / {@code @RequestLine}）</li>
 * </ul>
 */
final class HttpClientDetector {

    private static final String REST_OPERATIONS = "org.springframework.web.client.RestOperations";
    private static final String REST_TEMPLATE = "org.springframework.web.client.RestTemplate";
    private static final List<String> FLUENT_PREFIXES = List.of(
            "org.springframework.web.client.RestClient",
            "org.springframework.web.reactive.function.client.WebClient");
    private static final String EXCHANGE_PKG = "org.springframework.web.service.annotation.";
    private static final String MAPPING_PKG = "org.springframework.web.bind.annotation.";
    private static final String FEIGN_CLIENT = "org.springframework.cloud.openfeign.FeignClient";
    private static final String FEIGN_REQUEST_LINE = "feign.RequestLine";

    private static final Map<String, String> REST_TEMPLATE_VERBS = Map.ofEntries(
            Map.entry("getForObject", "GET"), Map.entry("getForEntity", "GET"),
            Map.entry("postForObject", "POST"), Map.entry("postForEntity", "POST"), Map.entry("postForLocation", "POST"),
            Map.entry("put", "PUT"), Map.entry("patchForObject", "PATCH"), Map.entry("delete", "DELETE"),
            Map.entry("headForHeaders", "HEAD"), Map.entry("optionsForAllow", "OPTIONS"),
            Map.entry("exchange", "?"), Map.entry("execute", "?"));
    private static final Set<String> FLUENT_VERBS = Set.of("get", "post", "put", "patch", "delete", "head", "options");
    private static final Set<String> FLUENT_BODY = Set.of(
            "body", "toEntity", "toEntityList", "bodyToMono", "bodyToFlux", "toEntityFlux");
    private static final Map<String, String> EXCHANGE_VERBS = Map.of(
            "GetExchange", "GET", "PostExchange", "POST", "PutExchange", "PUT",
            "PatchExchange", "PATCH", "DeleteExchange", "DELETE", "HttpExchange", "?");
    private static final Map<String, String> MAPPING_VERBS = Map.of(
            "GetMapping", "GET", "PostMapping", "POST", "PutMapping", "PUT",
            "PatchMapping", "PATCH", "DeleteMapping", "DELETE", "RequestMapping", "?");
    private static final Pattern HTTP_METHOD_REF = Pattern.compile("\\b(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS|TRACE)\\b");

    private final StringResolver strings;

    HttpClientDetector(@NotNull StringResolver strings) {
        this.strings = strings;
    }

    /**
     * 呼叫端應略過的 HTTP client 內部呼叫：RestClient / WebClient 呼叫鏈中間的呼叫（只在最外層處理一次）、
     * 以及 uri(b -> b.path(..).build()) 這類傳給 client 的 lambda 中對 UriBuilder 的呼叫。
     */
    boolean isInnerFluentCall(@NotNull PsiMethodCallExpression call, @NotNull PsiMethod method) {
        if (isUriBuilder(method.getContainingClass()) && insideFluentArgument(call)) {
            return true;
        }
        if (!isFluent(method.getContainingClass())) {
            return false;
        }
        PsiMethodCallExpression outer = outerCall(call);
        return outer != null && outer.resolveMethod() instanceof PsiMethod m && isFluent(m.getContainingClass());
    }

    @Nullable HttpCall detect(@NotNull PsiMethodCallExpression call, @NotNull PsiMethod method) {
        PsiClass declaring = method.getContainingClass();
        if (declaring == null) {
            return null;
        }
        if (isFluent(declaring)) {
            return detectFluent(call);
        }
        PsiExpression qualifier = call.getMethodExpression().getQualifierExpression();
        PsiClass receiver = qualifier != null ? PsiUtil.resolveClassInClassTypeOnly(qualifier.getType()) : null;
        if (InheritanceUtil.isInheritor(declaring, REST_OPERATIONS) || InheritanceUtil.isInheritor(declaring, REST_TEMPLATE)
                || receiver != null && InheritanceUtil.isInheritor(receiver, REST_OPERATIONS)) {
            return detectRestTemplate(call, method);
        }
        return detectDeclarative(call, method, declaring);
    }

    // ---------------------------------------------------------------- RestTemplate

    private @Nullable HttpCall detectRestTemplate(PsiMethodCallExpression call, PsiMethod method) {
        String verb = REST_TEMPLATE_VERBS.get(method.getName());
        if (verb == null) {
            return null;
        }
        PsiExpression[] args = call.getArgumentList().getExpressions();
        if (verb.equals("?")) {
            verb = "HTTP";
            for (PsiExpression arg : args) {
                Matcher m = HTTP_METHOD_REF.matcher(arg.getText());
                if (arg.getText().contains("HttpMethod") && m.find()) {
                    verb = m.group(1);
                    break;
                }
            }
        }
        String url = args.length > 0 ? strings.resolve(args[0], 0) : null;
        String response = classLiteral(args);
        if (response == null) {
            response = presentable(call.getType());
        }
        return build(url, null, verb, response, "RestTemplate", clientName(call), clientTarget(call));
    }

    // ---------------------------------------------------------------- RestClient / WebClient

    private @Nullable HttpCall detectFluent(PsiMethodCallExpression outermost) {
        List<PsiMethodCallExpression> chain = new ArrayList<>();
        PsiExpression current = outermost;
        while (PsiUtil.skipParenthesizedExprDown(current) instanceof PsiMethodCallExpression mc) {
            chain.add(mc);
            current = mc.getMethodExpression().getQualifierExpression();
        }
        String verb = null;
        String uri = null;
        String response = null;
        for (PsiMethodCallExpression mc : chain) {
            String name = mc.getMethodExpression().getReferenceName();
            PsiExpression[] args = mc.getArgumentList().getExpressions();
            if (name == null) {
                continue;
            }
            if (FLUENT_VERBS.contains(name) && args.length == 0) {
                verb = name.toUpperCase(Locale.ROOT);
            } else if (name.equals("method") && args.length == 1) {
                Matcher m = HTTP_METHOD_REF.matcher(args[0].getText());
                verb = m.find() ? m.group(1) : "HTTP";
            } else if (name.equals("uri") && args.length > 0) {
                uri = fluentUri(args[0]);
            } else if (FLUENT_BODY.contains(name) && response == null) {
                response = classLiteral(args);
            }
        }
        if (verb == null) {
            return null; // 例如 RestClient.builder()...build()
        }
        String client = chain.isEmpty() ? "RestClient" : clientKind(chain.getLast());
        PsiExpression root = current;
        String base = root != null ? baseUrl(root) : null;
        String rootName = root != null ? root.getText() : client;
        PsiElement target = root instanceof PsiReferenceExpression ref ? ref.resolve() : null;
        return build(uri, base, verb, response, client, rootName, target);
    }

    private String clientKind(PsiMethodCallExpression innermost) {
        PsiMethod m = innermost.resolveMethod();
        String fqn = m != null && m.getContainingClass() != null ? m.getContainingClass().getQualifiedName() : null;
        return fqn != null && fqn.contains("WebClient") ? "WebClient" : "RestClient";
    }

    /** uri("/x/{id}", id)、uri(URI.create("..."))、uri(b -> b.path("/x").build())。 */
    private @Nullable String fluentUri(PsiExpression arg) {
        if (arg instanceof PsiLambdaExpression lambda) {
            StringBuilder sb = new StringBuilder();
            for (PsiMethodCallExpression mc : PsiTreeUtil.findChildrenOfType(lambda, PsiMethodCallExpression.class)) {
                String name = mc.getMethodExpression().getReferenceName();
                PsiExpression[] args = mc.getArgumentList().getExpressions();
                if (("path".equals(name) || "pathSegment".equals(name)) && args.length > 0) {
                    String part = strings.resolve(args[0], 0);
                    if (part != null) {
                        // findChildrenOfType 由外而內，呼叫鏈越內層越早執行
                        sb.insert(0, part.startsWith("/") || "path".equals(name) ? part : "/" + part);
                    }
                }
            }
            return sb.isEmpty() ? null : sb.toString();
        }
        if (arg instanceof PsiMethodCallExpression mc && "create".equals(mc.getMethodExpression().getReferenceName())
                && mc.getArgumentList().getExpressionCount() == 1) {
            return strings.resolve(mc.getArgumentList().getExpressions()[0], 0);
        }
        return strings.resolve(arg, 0);
    }

    /** client 變數 / 欄位的 baseUrl：初始值或類別中對它的指派裡的 baseUrl("...")。 */
    private @Nullable String baseUrl(PsiExpression root) {
        if (!(PsiUtil.skipParenthesizedExprDown(root) instanceof PsiReferenceExpression ref)
                || !(ref.resolve() instanceof PsiVariable variable)) {
            return null;
        }
        List<PsiExpression> sources = new ArrayList<>();
        if (variable.getInitializer() != null) {
            sources.add(variable.getInitializer());
        }
        PsiClass owner = PsiTreeUtil.getParentOfType(variable, PsiClass.class);
        if (variable instanceof PsiField && owner != null) {
            for (PsiAssignmentExpression assignment : PsiTreeUtil.findChildrenOfType(owner, PsiAssignmentExpression.class)) {
                if (assignment.getLExpression() instanceof PsiReferenceExpression lhs && lhs.isReferenceTo(variable)
                        && assignment.getRExpression() != null) {
                    sources.add(assignment.getRExpression());
                }
            }
        }
        for (PsiExpression source : sources) {
            List<PsiMethodCallExpression> calls = new ArrayList<>(PsiTreeUtil.findChildrenOfType(source, PsiMethodCallExpression.class));
            if (source instanceof PsiMethodCallExpression self) {
                calls.add(self);
            }
            for (PsiMethodCallExpression mc : calls) {
                if ("baseUrl".equals(mc.getMethodExpression().getReferenceName()) && mc.getArgumentList().getExpressionCount() > 0) {
                    String base = strings.resolve(mc.getArgumentList().getExpressions()[0], 0);
                    if (base != null) {
                        return base;
                    }
                }
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- HTTP interface / Feign

    private @Nullable HttpCall detectDeclarative(PsiMethodCallExpression call, PsiMethod method, PsiClass declaring) {
        if (!declaring.isInterface()) {
            return null;
        }
        String verb = null;
        String path = null;
        String classUrl = null;
        String classPath = null;
        String host = null;
        String client;

        PsiAnnotation feign = declaring.getAnnotation(FEIGN_CLIENT);
        PsiAnnotation classExchange = declaring.getAnnotation(EXCHANGE_PKG + "HttpExchange");
        if (feign != null) {
            client = "Feign";
            host = firstNonBlank(attr(feign, "name"), attr(feign, "value"), attr(feign, "contextId"));
            classUrl = attr(feign, "url");
            classPath = attr(feign, "path");
            PsiAnnotation classMapping = declaring.getAnnotation(MAPPING_PKG + "RequestMapping");
            if (classMapping != null) {
                classPath = join(classPath, firstNonBlank(attr(classMapping, "value"), attr(classMapping, "path")));
            }
            for (Map.Entry<String, String> e : MAPPING_VERBS.entrySet()) {
                PsiAnnotation a = method.getAnnotation(MAPPING_PKG + e.getKey());
                if (a != null) {
                    verb = e.getValue().equals("?") ? requestMethod(a, "method") : e.getValue();
                    path = firstNonBlank(attr(a, "value"), attr(a, "path"));
                    break;
                }
            }
            PsiAnnotation requestLine = method.getAnnotation(FEIGN_REQUEST_LINE);
            if (verb == null && requestLine != null) {
                String line = attr(requestLine, "value");
                if (line != null) {
                    String[] parts = line.trim().split("\\s+", 2);
                    verb = parts[0];
                    path = parts.length > 1 ? parts[1] : "/";
                }
            }
        } else {
            client = "HttpExchange";
            if (classExchange != null) {
                classUrl = attr(classExchange, "url");
                classPath = attr(classExchange, "value");
            }
            for (Map.Entry<String, String> e : EXCHANGE_VERBS.entrySet()) {
                PsiAnnotation a = method.getAnnotation(EXCHANGE_PKG + e.getKey());
                if (a != null) {
                    verb = e.getValue().equals("?") ? firstNonBlank(attr(a, "method"), "HTTP") : e.getValue();
                    path = firstNonBlank(attr(a, "value"), attr(a, "url"));
                    break;
                }
            }
        }
        if (verb == null) {
            return null;
        }
        String base = classUrl;
        String fullPath = join(classPath, path);
        if (host == null && (base == null || base.isBlank())) {
            host = declaring.getName();
        }
        HttpCall result = build(fullPath, base, verb, presentable(call.getType()), client,
                host != null ? host : declaring.getName(), declaring);
        if (host != null && (base == null || base.isBlank())) {
            // Feign 服務名 / 介面名作為外部服務名稱
            result = new HttpCall(host, result.method(), result.path(), result.url(), result.responseType(), client, declaring);
        }
        return result;
    }

    private @Nullable String requestMethod(PsiAnnotation a, String attribute) {
        PsiAnnotationMemberValue value = a.findAttributeValue(attribute);
        if (value == null) {
            return "HTTP";
        }
        Matcher m = HTTP_METHOD_REF.matcher(value.getText());
        return m.find() ? m.group(1) : "HTTP";
    }

    private @Nullable String attr(PsiAnnotation annotation, String name) {
        PsiAnnotationMemberValue value = annotation.findDeclaredAttributeValue(name);
        if (value == null) {
            return null;
        }
        String s = strings.resolve(value);
        if (s == null) {
            s = AnnotationUtil.getStringAttributeValue(annotation, name);
        }
        return s == null || s.isBlank() ? null : s;
    }

    // ---------------------------------------------------------------- 共用

    private static @Nullable PsiMethodCallExpression outerCall(PsiMethodCallExpression call) {
        PsiElement parent = PsiUtil.skipParenthesizedExprUp(call.getParent());
        if (parent instanceof PsiReferenceExpression ref && ref.getParent() instanceof PsiMethodCallExpression outer
                && outer.getMethodExpression() == ref) {
            return outer;
        }
        return null;
    }

    private static boolean isUriBuilder(@Nullable PsiClass cls) {
        String fqn = cls != null ? cls.getQualifiedName() : null;
        return fqn != null && (fqn.endsWith(".UriBuilder") || fqn.endsWith(".UriComponentsBuilder"));
    }

    private static boolean insideFluentArgument(PsiElement element) {
        PsiMethodCallExpression enclosing = PsiTreeUtil.getParentOfType(element, PsiMethodCallExpression.class, true);
        while (enclosing != null) {
            if (enclosing.resolveMethod() instanceof PsiMethod m && isFluent(m.getContainingClass())) {
                return true;
            }
            enclosing = PsiTreeUtil.getParentOfType(enclosing, PsiMethodCallExpression.class, true);
        }
        return false;
    }

    private static boolean isFluent(@Nullable PsiClass cls) {
        String fqn = cls != null ? cls.getQualifiedName() : null;
        if (fqn == null) {
            return false;
        }
        for (String prefix : FLUENT_PREFIXES) {
            if (fqn.equals(prefix) || fqn.startsWith(prefix + ".")) {
                return true;
            }
        }
        return false;
    }

    private static @Nullable String classLiteral(PsiExpression[] args) {
        for (PsiExpression arg : args) {
            if (PsiUtil.skipParenthesizedExprDown(arg) instanceof PsiClassObjectAccessExpression access) {
                return access.getOperand().getType().getPresentableText();
            }
        }
        return null;
    }

    private static @Nullable String presentable(@Nullable PsiType type) {
        return type == null || PsiTypes.voidType().equals(type) ? null : type.getPresentableText();
    }

    private static String clientName(PsiMethodCallExpression call) {
        PsiExpression q = call.getMethodExpression().getQualifierExpression();
        return q != null ? q.getText() : "RestTemplate";
    }

    private static @Nullable PsiElement clientTarget(PsiMethodCallExpression call) {
        PsiExpression q = call.getMethodExpression().getQualifierExpression();
        return q instanceof PsiReferenceExpression ref ? ref.resolve() : null;
    }

    private static @Nullable String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private static String join(@Nullable String a, @Nullable String b) {
        String left = a == null ? "" : a.strip();
        String right = b == null ? "" : b.strip();
        if (left.isEmpty()) {
            return right;
        }
        if (right.isEmpty()) {
            return left;
        }
        return left.replaceAll("/+$", "") + "/" + right.replaceAll("^/+", "");
    }

    /** 組出 HttpCall：從 URL 拆出主機與路徑；主機未知時以 client 名稱代替。 */
    static HttpCall build(@Nullable String url, @Nullable String base, String verb, @Nullable String response,
                          String client, String fallbackHost, @Nullable PsiElement target) {
        String full = url == null ? "?" : url;
        if (base != null && !base.isBlank() && !full.matches("(?i)^https?://.*") && !full.startsWith("${")) {
            full = join(base, full);
        }
        String host = null;
        String path = full;
        Matcher abs = Pattern.compile("(?i)^(https?://[^/?#]+)(.*)$").matcher(full);
        if (abs.matches()) {
            host = abs.group(1).replaceFirst("(?i)^https?://", "");
            path = abs.group(2);
        } else if (full.startsWith("${")) {
            int end = full.indexOf('}');
            if (end > 0) {
                host = full.substring(0, end + 1);
                path = full.substring(end + 1);
            }
        } else if (full.startsWith("?")) {
            // 前段（通常是 base url 變數）無法解析
            path = full.substring(1);
        }
        if (path.isEmpty()) {
            path = "/";
        }
        if (host == null) {
            host = fallbackHost;
        }
        return new HttpCall(host, verb, path, full, response, client, target);
    }
}
