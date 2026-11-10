package com.pino.seqlens.model;

import com.intellij.psi.PsiElement;
import com.intellij.psi.SmartPsiElementPointer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一張循序圖中用到的外部 API 與資料表彙整：
 * 外部服務 → 端點（GET /x）→ 呼叫處；資料表 → 操作（SELECT…）→ 經由的方法。
 */
public final class ResourceSummary {

    /**
     * 一次使用。
     *
     * @param via        經由的方法，例如 {@code UserRepository.findByEmail(email)}
     * @param caller     發出呼叫的參與者
     * @param location   檔名:行號（可能為空字串）
     * @param navigation 點擊跳轉的位置
     */
    public record Usage(@NotNull String via,
                        @NotNull String caller,
                        @NotNull String location,
                        @Nullable SmartPsiElementPointer<? extends PsiElement> navigation,
                        @NotNull DiagramEvent.Expansion expansion) {

        public Usage(@NotNull String via, @NotNull String caller, @NotNull String location,
                     @Nullable SmartPsiElementPointer<? extends PsiElement> navigation) {
            this(via, caller, location, navigation, DiagramEvent.Expansion.EXPANDED);
        }
    }

    /** 名稱（主機 / 表名）→ 操作（端點 / SQL 操作）→ 使用清單，皆保持出現順序。 */
    private final Map<String, Map<String, List<Usage>>> apis = new LinkedHashMap<>();
    private final Map<String, Map<String, List<Usage>>> tables = new LinkedHashMap<>();
    /** 類別 → 方法 → 呼叫處。 */
    private final Map<String, Map<String, List<Usage>>> classes = new LinkedHashMap<>();
    /** 類別名稱 → 參與者（供跳到類別定義）。 */
    private final Map<String, Participant> classParticipants = new LinkedHashMap<>();

    private ResourceSummary() {
    }

    public static @NotNull ResourceSummary of(@NotNull SequenceDiagram diagram) {
        ResourceSummary summary = new ResourceSummary();
        Map<String, DiagramEvent.Call> lastCallInto = new HashMap<>();
        for (DiagramEvent event : diagram.getEvents()) {
            if (!(event instanceof DiagramEvent.Call call)) {
                continue;
            }
            switch (call.kind()) {
                case HTTP -> {
                    String host = name(diagram, call.to());
                    String caller = name(diagram, call.from());
                    add(summary.apis, host, call.label(),
                            new Usage(caller, caller, location(call.tooltip()), call.navigation()));
                }
                case SQL -> {
                    String table = name(diagram, call.to());
                    String component = name(diagram, call.from());
                    DiagramEvent.Call into = lastCallInto.get(call.from());
                    Usage usage = into != null
                            ? new Usage(component + "." + into.label(), name(diagram, into.from()),
                            location(into.tooltip()), into.navigation() != null ? into.navigation() : call.navigation())
                            : new Usage(component, component, location(call.tooltip()), call.navigation());
                    add(summary.tables, table, call.label(), usage);
                }
                case CALL, CREATE -> {
                    Participant target = diagram.getParticipant(call.to());
                    if (target != null && (target.kind() == ParticipantKind.CLASS
                            || target.kind() == ParticipantKind.BOUNDARY || target.kind() == ParticipantKind.REPOSITORY
                            || target.kind() == ParticipantKind.MAPPER)) {
                        String method = call.kind() == MessageKind.CREATE ? "«create»" : methodName(call.label());
                        String caller = name(diagram, call.from());
                        summary.classParticipants.putIfAbsent(target.displayName(), target);
                        add(summary.classes, target.displayName(), method,
                                new Usage(caller, caller, location(call.tooltip()), call.navigation(), call.expansion()));
                    }
                }
                default -> {
                }
            }
            lastCallInto.put(call.to(), call);
        }
        return summary;
    }

    private static void add(Map<String, Map<String, List<Usage>>> target, String name, String operation, Usage usage) {
        List<Usage> usages = target.computeIfAbsent(name, k -> new LinkedHashMap<>())
                .computeIfAbsent(operation, k -> new ArrayList<>());
        boolean duplicate = usages.stream().anyMatch(u -> u.via().equals(usage.via()) && u.location().equals(usage.location()));
        if (!duplicate) {
            usages.add(usage);
        }
    }

    private static String methodName(String label) {
        int paren = label.indexOf('(');
        return (paren > 0 ? label.substring(0, paren) : label) + "()";
    }

    private static String name(SequenceDiagram diagram, String id) {
        Participant p = diagram.getParticipant(id);
        return p != null ? p.displayName() : id;
    }

    /** 提示文字最後一行是「檔名:行號」。 */
    private static String location(@Nullable String tooltip) {
        if (tooltip == null || tooltip.isBlank()) {
            return "";
        }
        String last = tooltip.substring(tooltip.lastIndexOf('\n') + 1).strip();
        return last.matches(".+:\\d+") ? last : "";
    }

    public @NotNull Map<String, Map<String, List<Usage>>> apis() {
        return apis;
    }

    public @NotNull Map<String, Map<String, List<Usage>>> tables() {
        return tables;
    }

    public @NotNull Map<String, Map<String, List<Usage>>> classes() {
        return classes;
    }

    public @Nullable Participant classParticipant(@NotNull String name) {
        return classParticipants.get(name);
    }

    public boolean isEmpty() {
        return apis.isEmpty() && tables.isEmpty() && classes.isEmpty();
    }

    /** 匯出成 Markdown 表格（包含全部區塊）。 */
    public @NotNull String toMarkdown(@NotNull String title) {
        return toMarkdown(title, true, true, true);
    }

    /** 匯出成 Markdown 表格，只包含指定的區塊。 */
    public @NotNull String toMarkdown(@NotNull String title, boolean withApis, boolean withTables, boolean withClasses) {
        StringBuilder sb = new StringBuilder("# ").append(title).append("\n\n");
        if (withApis) {
            appendApis(sb);
        }
        if (withTables) {
            appendTables(sb);
        }
        if (withClasses) {
            sb.append("## Classes / Methods\n\n");
            if (classes.isEmpty()) {
                sb.append("(none)\n\n");
            } else {
                sb.append("| Class | Method | Called from |\n|---|---|---|\n");
                appendRows(sb, classes, false);
                sb.append('\n');
            }
        }
        return sb.toString().stripTrailing() + "\n";
    }

    private void appendApis(StringBuilder sb) {
        sb.append("## External APIs\n\n");
        if (apis.isEmpty()) {
            sb.append("(none)\n\n");
        } else {
            sb.append("| Service | Endpoint | Called from |\n|---|---|---|\n");
            appendRows(sb, apis, false);
            sb.append('\n');
        }
    }

    private void appendTables(StringBuilder sb) {
        sb.append("## Tables\n\n");
        if (tables.isEmpty()) {
            sb.append("(none)\n");
        } else {
            sb.append("| Table | Operation | Via |\n|---|---|---|\n");
            appendRows(sb, tables, true);
        }
        sb.append('\n');
    }

    private static void appendRows(StringBuilder sb, Map<String, Map<String, List<Usage>>> data, boolean showVia) {
        data.forEach((name, operations) -> operations.forEach((operation, usages) -> {
            List<String> places = new ArrayList<>();
            for (Usage u : usages) {
                String text = showVia ? u.via() : u.caller();
                places.add(cell(text + (u.location().isEmpty() ? "" : " (" + u.location() + ")")));
            }
            String mark = usages.stream().anyMatch(u -> u.expansion() == DiagramEvent.Expansion.DEPTH_LIMIT) ? " ⋯"
                    : usages.stream().anyMatch(u -> u.expansion() == DiagramEvent.Expansion.STOPPED) ? " ⊘" : "";
            sb.append("| ").append(cell(name)).append(" | `").append(operation.replace("`", "'")).append("`").append(mark).append(" | ")
                    .append(String.join("<br>", places)).append(" |\n");
        }));
    }

    private static String cell(String text) {
        return text.replace("|", "\\|").replace("\n", " ");
    }
}
