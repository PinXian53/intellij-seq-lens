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
 * @param dispatch    經由介面呼叫到這個參與者時的實作選擇資訊（標頭可切換實作），可為 null
 */
public record Participant(@NotNull String id,
                          @NotNull String displayName,
                          @NotNull ParticipantKind kind,
                          @Nullable String tooltip,
                          @Nullable SmartPsiElementPointer<? extends PsiElement> navigation,
                          @Nullable Dispatch dispatch) {

    public Participant(@NotNull String id, @NotNull String displayName, @NotNull ParticipantKind kind,
                       @Nullable String tooltip, @Nullable SmartPsiElementPointer<? extends PsiElement> navigation) {
        this(id, displayName, kind, tooltip, navigation, null);
    }

    /** 是否可以在標頭切換實作（介面有兩個以上的實作）。 */
    public boolean isSwitchable() {
        return dispatch != null && dispatch.candidates().size() > 1;
    }

    /** 被呼叫的介面簡名；不是經由介面呼叫時為 null。 */
    public @Nullable String interfaceName() {
        return dispatch == null ? null : Dispatch.simpleName(dispatch.interfaceFqn());
    }
}
