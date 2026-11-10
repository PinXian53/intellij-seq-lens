package com.pino.seqlens.analysis;

import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.pino.seqlens.sql.SqlInfo;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * 偵測到的資料存取。
 *
 * @param componentClass  資料存取元件（Repository 介面、EntityManager、JdbcTemplate）
 * @param componentName   顯示名稱
 * @param info            SQL 解析結果
 * @param sqlSource       SQL 的來源 PSI（@Query、字串常數），點附註時跳轉
 * @param entityByTable   表名（小寫）→ 實體類別，點資料表時跳轉
 * @param derived         SQL 是否為推導出來的示意 SQL（非原始碼中的字串）
 */
public record DataAccess(@NotNull PsiClass componentClass,
                         @NotNull String componentName,
                         @NotNull SqlInfo info,
                         @Nullable PsiElement sqlSource,
                         @NotNull Map<String, PsiClass> entityByTable,
                         boolean derived) {
}
