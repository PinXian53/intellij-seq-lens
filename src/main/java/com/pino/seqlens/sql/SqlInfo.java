package com.pino.seqlens.sql;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 一段 SQL / JPQL 的解析結果。
 *
 * @param operation 主要操作
 * @param accesses  依出現順序的資料表存取（主表在前）
 * @param sql       原始（或推導出的）SQL，供附註顯示，可為 null
 */
public record SqlInfo(@NotNull SqlOperation operation,
                      @NotNull List<TableAccess> accesses,
                      @Nullable String sql) {

    public boolean isEmpty() {
        return accesses.isEmpty();
    }
}
