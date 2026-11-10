package com.pino.seqlens.analysis;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 「不展開清單」的樣式比對。每個樣式為 {@code 類別[#方法]}，支援 * 萬用字元：
 * <ul>
 *     <li>{@code com.foo.AuditService}：完整類別名稱</li>
 *     <li>{@code com.foo.util.*}：套件底下全部</li>
 *     <li>{@code AuditService}、{@code *Client}：不含 . 時比對類別簡名</li>
 *     <li>{@code AuditService#log*}、{@code *#toString}：指定方法</li>
 * </ul>
 * 以逗號、分號或換行分隔；# 開頭的行為註解。
 */
public final class CallPatternMatcher {

    private record Rule(Pattern cls, boolean simpleName, @Nullable Pattern method) {
    }

    private final List<Rule> rules = new ArrayList<>();

    private CallPatternMatcher() {
    }

    public static @NotNull CallPatternMatcher parse(@Nullable String text) {
        CallPatternMatcher matcher = new CallPatternMatcher();
        if (text == null) {
            return matcher;
        }
        for (String raw : text.split("[,;\\n]")) {
            String p = raw.strip();
            if (p.isEmpty() || p.startsWith("#") || p.startsWith("//")) {
                continue;
            }
            String clsPart = p;
            String methodPart = null;
            int hash = p.indexOf('#');
            if (hash >= 0) {
                clsPart = p.substring(0, hash).strip();
                methodPart = p.substring(hash + 1).strip().replaceAll("\\(.*\\)$", "");
            }
            if (clsPart.isEmpty()) {
                clsPart = "*";
            }
            matcher.rules.add(new Rule(glob(clsPart), !clsPart.contains("."),
                    methodPart == null || methodPart.isEmpty() ? null : glob(methodPart)));
        }
        return matcher;
    }

    public boolean isEmpty() {
        return rules.isEmpty();
    }

    /**
     * @param qualifiedName 類別完整名稱（可為 null）
     * @param simpleName    類別簡名
     * @param method        方法名稱，建構子用 {@code <init>}
     */
    public boolean matches(@Nullable String qualifiedName, @Nullable String simpleName, @NotNull String method) {
        for (Rule rule : rules) {
            String target = rule.simpleName() ? simpleName : qualifiedName;
            if (target == null || !rule.cls().matcher(target).matches()) {
                continue;
            }
            if (rule.method() == null || rule.method().matcher(method).matches()) {
                return true;
            }
        }
        return false;
    }

    private static Pattern glob(String glob) {
        StringBuilder regex = new StringBuilder();
        for (char c : glob.toCharArray()) {
            if (c == '*') {
                regex.append(".*");
            } else if (c == '?') {
                regex.append('.');
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString());
    }
}
