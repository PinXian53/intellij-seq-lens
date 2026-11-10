package com.pino.seqlens.export;

import com.pino.seqlens.model.DiagramEvent;
import com.pino.seqlens.model.Participant;
import com.pino.seqlens.model.SequenceDiagram;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;

/** 匯出為 Mermaid sequenceDiagram 文字。 */
public final class MermaidExporter {

    private MermaidExporter() {
    }

    public static @NotNull String export(@NotNull SequenceDiagram diagram) {
        StringBuilder sb = new StringBuilder("sequenceDiagram\n");
        sb.append("    title ").append(escape(diagram.getTitle())).append('\n');
        Map<String, String> alias = new HashMap<>();
        int n = 0;
        for (Participant p : diagram.getParticipants()) {
            String a = "p" + n++;
            alias.put(p.id(), a);
            switch (p.kind()) {
                case ACTOR -> sb.append("    actor ").append(a).append(" as ").append(escape(p.displayName())).append('\n');
                case EXTERNAL -> sb.append("    participant ").append(a).append(" as ")
                        .append(escape(p.displayName() + " «external»")).append('\n');
                case TABLE -> sb.append("    participant ").append(a).append(" as ")
                        .append(escape(p.displayName() + " «table»")).append('\n');
                default -> sb.append("    participant ").append(a).append(" as ").append(escape(p.displayName())).append('\n');
            }
        }
        String indent = "    ";
        for (DiagramEvent event : diagram.getEvents()) {
            switch (event) {
                case DiagramEvent.Call c -> sb.append(indent).append(alias.get(c.from())).append("->>+")
                        .append(alias.get(c.to())).append(": ").append(escape(c.label() + badge(c))).append('\n');
                case DiagramEvent.Return r -> {
                    if (r.visible()) {
                        sb.append(indent).append(alias.get(r.from())).append("-->>-").append(alias.get(r.to()))
                                .append(": ").append(escape(r.label())).append('\n');
                    } else {
                        sb.append(indent).append("deactivate ").append(alias.get(r.from())).append('\n');
                    }
                }
                case DiagramEvent.Throw t -> sb.append(indent).append(alias.get(t.from())).append("--x")
                        .append(alias.get(t.to())).append(": ").append(escape(t.label())).append('\n');
                case DiagramEvent.FragmentStart f -> {
                    String keyword = switch (f.type()) {
                        case ALT, TRY -> "alt";
                        case OPT -> "opt";
                        case LOOP -> "loop";
                    };
                    String label = f.type() == DiagramEvent.FragmentType.TRY ? "try" : f.condition();
                    sb.append(indent).append(keyword).append(' ').append(escape(label)).append('\n');
                    indent += "    ";
                }
                case DiagramEvent.FragmentElse e -> sb.append(indent, 0, indent.length() - 4)
                        .append(e.condition().equals("else") ? "else" : "else " + escape(e.condition())).append('\n');
                case DiagramEvent.FragmentEnd ignored -> {
                    indent = indent.substring(0, Math.max(4, indent.length() - 4));
                    sb.append(indent).append("end\n");
                }
                case DiagramEvent.Note note -> sb.append(indent).append("Note right of ").append(alias.get(note.over()))
                        .append(": ").append(escape(note.text()).replace("\n", "<br/>")).append('\n');
            }
        }
        return sb.toString();
    }

    private static String badge(DiagramEvent.Call c) {
        return switch (c.expansion()) {
            case STOPPED -> " ⊘";
            case DEPTH_LIMIT -> " ⋯";
            case EXPANDED -> "";
        };
    }

    private static String escape(String text) {
        return text.replace(";", "#59;").replace("\n", "<br/>").replace("\r", "");
    }
}
