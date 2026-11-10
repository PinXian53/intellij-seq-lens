package com.pino.seqlens.analysis;

import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 偵測到的對外 HTTP 呼叫。
 *
 * @param host         外部服務名稱（主機、${placeholder}、Feign 服務名或 client 名稱）
 * @param method       HTTP method（GET / POST…），無法判斷時為 "HTTP"
 * @param path         路徑（含 path variable），例如 /orders/{id}
 * @param url          盡量完整的 URL，供提示文字
 * @param responseType 回應型別（可為 null）
 * @param client       使用的 client 種類，例如 RestTemplate、Feign
 * @param target       點擊外部服務參與者時跳轉的位置（Feign 介面、client 欄位…）
 */
public record HttpCall(@NotNull String host,
                       @NotNull String method,
                       @NotNull String path,
                       @NotNull String url,
                       @Nullable String responseType,
                       @NotNull String client,
                       @Nullable PsiElement target) {
}
