package com.pino.seqlens.model;

/** 訊息（箭頭）的種類。 */
public enum MessageKind {
    /** 一般同步方法呼叫。 */
    CALL,
    /** 建構子（new）。 */
    CREATE,
    /** 對資料表的 SQL 操作。 */
    SQL,
    /** 對外部服務的 HTTP 呼叫。 */
    HTTP,
    /** 拋出例外。 */
    THROW
}
