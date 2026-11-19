package com.pino.seqlens.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 呼叫介面 / 抽象方法時，選擇實作的結果。
 *
 * @param interfaceFqn 被呼叫的介面（手動選擇以它為 key）
 * @param candidates   專案中的實作類別（完整名稱，正式程式優先）
 * @param chosen       採用的實作；null 代表無法決定，停在介面上
 * @param reason       採用的依據
 */
public record Dispatch(@NotNull String interfaceFqn,
                       @NotNull List<String> candidates,
                       @Nullable String chosen,
                       @NotNull Reason reason) {

    public enum Reason {
        /** 使用者手動選擇。 */
        MANUAL("chosen manually"),
        /** 只有一個實作。 */
        SINGLE("only implementation"),
        /** 排除測試程式後只剩一個。 */
        PRODUCTION("only production implementation"),
        /** 注入點的 @Qualifier / @Named。 */
        QUALIFIER("matched by @Qualifier"),
        /** 注入點的變數名稱等於 bean 名稱。 */
        BEAN_NAME("matched by bean name"),
        /** 唯一標了 @Primary 的實作。 */
        PRIMARY("@Primary"),
        /** 有多個實作，無法決定。 */
        AMBIGUOUS("multiple implementations");

        private final String text;

        Reason(String text) {
            this.text = text;
        }

        public String text() {
            return text;
        }
    }

    public boolean isAmbiguous() {
        return chosen == null && candidates.size() > 1;
    }

    public static @NotNull String simpleName(@NotNull String fqn) {
        return fqn.substring(fqn.lastIndexOf('.') + 1);
    }
}
