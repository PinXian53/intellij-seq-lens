package com.pino.seqlens.model;

/** 循序圖中參與者的種類，決定繪製外觀。 */
public enum ParticipantKind {
    /** 呼叫的起點（使用者 / 外部呼叫者）。 */
    ACTOR,
    /** 一般 Java 類別。 */
    CLASS,
    /** Controller / RestController 等邊界類別。 */
    BOUNDARY,
    /** Spring Data Repository、EntityManager、JdbcTemplate 等資料存取元件。 */
    REPOSITORY,
    /** 資料庫的表。 */
    TABLE,
    /** MapStruct mapper（不展開實作）。 */
    MAPPER,
    /** 外部 HTTP 服務。 */
    EXTERNAL
}
