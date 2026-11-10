package com.pino.seqlens.sql;

/** SQL 操作種類。 */
public enum SqlOperation {
    SELECT, INSERT, UPDATE, DELETE, MERGE, UPSERT, DDL, CALL, UNKNOWN;

    /** 是否會回傳資料列。 */
    public boolean returnsRows() {
        return this == SELECT || this == CALL;
    }
}
