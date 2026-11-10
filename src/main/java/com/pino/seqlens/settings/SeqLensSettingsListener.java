package com.pino.seqlens.settings;

import com.intellij.util.messages.Topic;

/** 設定套用後通知（重新排版已開啟的圖、移動工具視窗）。 */
@FunctionalInterface
public interface SeqLensSettingsListener {

    Topic<SeqLensSettingsListener> TOPIC = Topic.create("SeqLens settings changed", SeqLensSettingsListener.class);

    void settingsChanged();
}
