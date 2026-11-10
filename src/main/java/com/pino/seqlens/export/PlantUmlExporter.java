package com.pino.seqlens.export;

import com.pino.seqlens.model.DiagramEvent;
import com.pino.seqlens.model.Participant;
import com.pino.seqlens.model.SequenceDiagram;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;

/** 匯出為 PlantUML 文字。 */
public final class PlantUmlExporter {

    private PlantUmlExporter() {
    }

    public static @NotNull String export(@NotNull SequenceDiagram diagram) {
        StringBuilder sb = new StringBuilder("@startuml\n");
        sb.append("title ").append(diagram.getTitle()).append('\n');
        sb.append("autoactivate off\n");
        Map<String, String> alias = new HashMap<>();
        int n = 0;
        for (Participant p : diagram.getParticipants()) {
            String a = "p" + n++;
            alias.put(p.id(), a);
            String keyword = switch (p.kind()) {
                case ACTOR -> "actor";
                case BOUNDARY -> "boundary";
                case REPOSITORY -> "collections";
                case TABLE -> "database";
                case EXTERNAL, MAPPER -> "participant";
                case CLASS -> "participant";
            };
            sb.append(keyword).append(" \"").append(escape(p.displayName())).append("\" as ").append(a)
                    .append(p.kind() == com.pino.seqlens.model.ParticipantKind.EXTERNAL ? " <<external>> #F3E8FF"
                            : p.kind() == com.pino.seqlens.model.ParticipantKind.MAPPER ? " <<mapper>> #E6F6F7" : "")
                    .append('\n');
        }
        sb.append('\n');
        for (DiagramEvent event : diagram.getEvents()) {
            switch (event) {
                case DiagramEvent.Call c -> {
                    String arrow = switch (c.kind()) {
                        case CREATE -> " -> ";
                        case SQL -> " -[#D9822B]> ";
                        case HTTP -> " -[#7C3AED]> ";
                        case CALL, THROW -> " -> ";
                    };
                    sb.append(alias.get(c.from())).append(arrow).append(alias.get(c.to()))
                            .append(" : ").append(escape(c.label() + badge(c))).append('\n');
                    sb.append("activate ").append(alias.get(c.to())).append('\n');
                }
                case DiagramEvent.Return r -> {
                    if (r.visible()) {
                        sb.append(alias.get(r.from())).append(" --> ").append(alias.get(r.to()))
                                .append(" : ").append(escape(r.label())).append('\n');
                    }
                    sb.append("deactivate ").append(alias.get(r.from())).append('\n');
                }
                case DiagramEvent.Throw t -> sb.append(alias.get(t.from())).append(" -[#E5484D]->x ")
                        .append(alias.get(t.to())).append(" : ").append(escape(t.label())).append('\n');
                case DiagramEvent.FragmentStart f -> {
                    String keyword = f.type() == DiagramEvent.FragmentType.TRY ? "group" : f.type().keyword();
                    sb.append(keyword).append(' ').append(escape(f.condition())).append('\n');
                }
                case DiagramEvent.FragmentElse e -> sb.append(elseLine(e.condition())).append('\n');
                case DiagramEvent.FragmentEnd ignored -> sb.append("end\n");
                case DiagramEvent.Note note -> {
                    sb.append("note right of ").append(alias.get(note.over())).append('\n');
                    sb.append(note.text()).append('\n');
                    sb.append("end note\n");
                }
            }
        }
        if (diagram.isTruncated()) {
            sb.append("note across: Maximum number of calls reached; the diagram is truncated\n");
        }
        sb.append("@enduml\n");
        return sb.toString();
    }

    private static String elseLine(String condition) {
        return condition.equals("else") ? "else" : "else " + escape(condition);
    }

    private static String badge(DiagramEvent.Call c) {
        return switch (c.expansion()) {
            case STOPPED -> " ⊘";
            case DEPTH_LIMIT -> " ⋯";
            case EXPANDED -> "";
        };
    }

    private static String escape(String text) {
        return text.replace("\"", "'").replace("\n", "\\n");
    }
}
