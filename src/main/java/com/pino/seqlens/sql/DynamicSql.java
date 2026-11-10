package com.pino.seqlens.sql;

import org.jetbrains.annotations.NotNull;

/**
 * {@code @DynamicQuery} 的 SQL 前處理：{@code [ ... ]} 是選填片段標記（有值才保留），
 * 分析用到哪些表時要把標記拿掉、保留片段內容，片段內的表（例如 EXISTS 子查詢）也算用到。
 */
public final class DynamicSql {

    private DynamicSql() {
    }

    /** 移除 [ ] 片段標記（字串字面值內的不動）。 */
    public static @NotNull String stripOptionalMarkers(@NotNull String sql) {
        StringBuilder sb = new StringBuilder(sql.length());
        boolean inString = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\'') {
                inString = !inString;
            }
            if (!inString && (c == '[' || c == ']')) {
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 選填片段的數量。 */
    public static int optionalFragmentCount(@NotNull String sql) {
        int count = 0;
        boolean inString = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\'') {
                inString = !inString;
            } else if (!inString && c == '[') {
                count++;
            }
        }
        return count;
    }
}
