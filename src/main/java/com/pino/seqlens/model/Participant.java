package com.pino.seqlens.model;

import com.intellij.psi.PsiElement;
import com.intellij.psi.SmartPsiElementPointer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 循序圖中的一條生命線。
 *
 * @param id          唯一識別（類別 FQN 或 {@code table:xxx}）
 * @param displayName 顯示名稱
 * @param kind        種類
 * @param tooltip     滑鼠提示文字
 * @param navigation  點擊後跳轉的程式碼位置，可為 null
 */
public record Participant(@NotNull String id,
                          @NotNull String displayName,
                          @NotNull ParticipantKind kind,
                          @Nullable String tooltip,
                          @Nullable SmartPsiElementPointer<? extends PsiElement> navigation) {
}
