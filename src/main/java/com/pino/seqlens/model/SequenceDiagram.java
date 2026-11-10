package com.pino.seqlens.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 一張循序圖：參與者（依出現順序）與事件序列。 */
public final class SequenceDiagram {

    private final String title;
    private final Map<String, Participant> participants = new LinkedHashMap<>();
    private final List<DiagramEvent> events = new ArrayList<>();
    private boolean truncated;
    private int maxDepth;

    public SequenceDiagram(@NotNull String title) {
        this.title = title;
    }

    public @NotNull String getTitle() {
        return title;
    }

    /** 加入參與者；已存在則保留原本的。 */
    public @NotNull Participant addParticipant(@NotNull Participant participant) {
        return participants.computeIfAbsent(participant.id(), k -> participant);
    }

    public boolean hasParticipant(@NotNull String id) {
        return participants.containsKey(id);
    }

    public @Nullable Participant getParticipant(@NotNull String id) {
        return participants.get(id);
    }

    public @NotNull List<Participant> getParticipants() {
        return List.copyOf(participants.values());
    }

    /** 可修改的事件列表，供分析器使用。 */
    public @NotNull List<DiagramEvent> mutableEvents() {
        return events;
    }

    public @NotNull List<DiagramEvent> getEvents() {
        return Collections.unmodifiableList(events);
    }

    /** 產生這張圖時使用的最大深度。 */
    public int getMaxDepth() {
        return maxDepth;
    }

    public void setMaxDepth(int maxDepth) {
        this.maxDepth = maxDepth;
    }

    /** 因達最大深度而未展開的呼叫數。 */
    public long depthLimitedCount() {
        return events.stream()
                .filter(e -> e instanceof DiagramEvent.Call c && c.expansion() == DiagramEvent.Expansion.DEPTH_LIMIT)
                .count();
    }

    public boolean isTruncated() {
        return truncated;
    }

    public void setTruncated(boolean truncated) {
        this.truncated = truncated;
    }

    /** 移除沒有任何事件參與的參與者（例如被丟棄的空片段造成）。 */
    public void pruneUnusedParticipants() {
        java.util.Set<String> used = new java.util.HashSet<>();
        for (DiagramEvent e : events) {
            switch (e) {
                case DiagramEvent.Call c -> {
                    used.add(c.from());
                    used.add(c.to());
                }
                case DiagramEvent.Return r -> {
                    used.add(r.from());
                    used.add(r.to());
                }
                case DiagramEvent.Throw t -> {
                    used.add(t.from());
                    used.add(t.to());
                }
                case DiagramEvent.Note n -> used.add(n.over());
                default -> {
                }
            }
        }
        participants.keySet().removeIf(id -> !used.contains(id));
    }
}
