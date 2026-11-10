package com.pino.seqlens.sql;

import org.jetbrains.annotations.NotNull;

/** 一次對某張表的存取。 */
public record TableAccess(@NotNull String table, @NotNull SqlOperation operation) {
}
