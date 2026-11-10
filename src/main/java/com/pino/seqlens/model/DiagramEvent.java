package com.pino.seqlens.model;

import com.intellij.psi.PsiElement;
import com.intellij.psi.SmartPsiElementPointer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 循序圖中依時間順序排列的事件。
 * 每個 {@link Call} 一定會有一個對應的 {@link Return}（return 可以設為不顯示箭頭，只用來結束 activation）。
 */
public sealed interface DiagramEvent {

    /**
     * 方法呼叫 / 建構 / SQL 操作。
     *
     * @param expansion 被呼叫方法是否有往下展開
     * @param targetKey 被呼叫的方法，格式 {@code com.foo.Bar#method}（供「加入不展開清單」與資源清單使用），可為 null
     */
    record Call(@NotNull String from,
                @NotNull String to,
                @NotNull String label,
                @NotNull MessageKind kind,
                @Nullable String tooltip,
                @Nullable SmartPsiElementPointer<? extends PsiElement> navigation,
                @NotNull Expansion expansion,
                @Nullable String targetKey) implements DiagramEvent {

        public Call(@NotNull String from, @NotNull String to, @NotNull String label, @NotNull MessageKind kind,
                    @Nullable String tooltip, @Nullable SmartPsiElementPointer<? extends PsiElement> navigation) {
            this(from, to, label, kind, tooltip, navigation, Expansion.EXPANDED, null);
        }
    }

    /** 呼叫是否往下展開。 */
    enum Expansion {
        /** 已展開（或本來就沒有可展開的內容）。 */
        EXPANDED,
        /** 命中「不展開清單」，刻意不往下追。 */
        STOPPED,
        /** 已達最大深度，可能有未呈現的呼叫。 */
        DEPTH_LIMIT
    }

    /** 回傳；{@code visible == false} 時只結束 activation，不畫箭頭。 */
    record Return(@NotNull String from,
                  @NotNull String to,
                  @NotNull String label,
                  boolean visible) implements DiagramEvent {
    }

    /** 拋出例外：從 {@code from} 丟回呼叫者 {@code to}，不結束 activation（由對應的 Return 結束）。 */
    record Throw(@NotNull String from,
                 @NotNull String to,
                 @NotNull String label,
                 @Nullable String tooltip,
                 @Nullable SmartPsiElementPointer<? extends PsiElement> navigation) implements DiagramEvent {
    }

    /** 組合片段開始（alt / opt / loop / try）。 */
    record FragmentStart(@NotNull FragmentType type, @NotNull String condition) implements DiagramEvent {
    }

    /** 組合片段中的分隔（else / catch / finally / case）。 */
    record FragmentElse(@NotNull String condition) implements DiagramEvent {
    }

    /** 組合片段結束。 */
    record FragmentEnd() implements DiagramEvent {
    }

    /** 附註（例如 SQL 語句）。 */
    record Note(@NotNull String over,
                @NotNull String text,
                @Nullable String tooltip,
                @Nullable SmartPsiElementPointer<? extends PsiElement> navigation) implements DiagramEvent {
    }

    enum FragmentType {
        ALT("alt"), OPT("opt"), LOOP("loop"), TRY("try");

        private final String keyword;

        FragmentType(String keyword) {
            this.keyword = keyword;
        }

        public String keyword() {
            return keyword;
        }
    }
}
