package com.pino.seqlens.export;

import com.pino.seqlens.model.DiagramEvent;
import com.pino.seqlens.model.MessageKind;
import com.pino.seqlens.model.Participant;
import com.pino.seqlens.model.ParticipantKind;
import com.pino.seqlens.model.SequenceDiagram;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class ExporterTest {

    private static SequenceDiagram sample() {
        SequenceDiagram d = new SequenceDiagram("UserService.register");
        d.addParticipant(new Participant("a", "Caller", ParticipantKind.ACTOR, null, null));
        d.addParticipant(new Participant("s", "UserService", ParticipantKind.CLASS, null, null));
        d.addParticipant(new Participant("r", "UserRepository", ParticipantKind.REPOSITORY, null, null));
        d.addParticipant(new Participant("t", "users", ParticipantKind.TABLE, null, null));
        var ev = d.mutableEvents();
        ev.add(new DiagramEvent.Call("a", "s", "register(dto)", MessageKind.CALL, null, null));
        ev.add(new DiagramEvent.FragmentStart(DiagramEvent.FragmentType.ALT, "exists"));
        ev.add(new DiagramEvent.Call("s", "r", "findByEmail(email)", MessageKind.CALL, null, null));
        ev.add(new DiagramEvent.Note("r", "SELECT * FROM users\nWHERE email = ?", null, null));
        ev.add(new DiagramEvent.Call("r", "t", "SELECT", MessageKind.SQL, null, null));
        ev.add(new DiagramEvent.Return("t", "r", "rows", true));
        ev.add(new DiagramEvent.Return("r", "s", "Optional<User>", true));
        ev.add(new DiagramEvent.FragmentElse("else"));
        ev.add(new DiagramEvent.FragmentEnd());
        ev.add(new DiagramEvent.Return("s", "a", "", false));
        return d;
    }

    @Test
    public void plantUml() {
        String text = PlantUmlExporter.export(sample());
        assertTrue(text, text.startsWith("@startuml"));
        assertTrue(text, text.contains("database \"users\" as p3"));
        assertTrue(text, text.contains("p2 -[#D9822B]> p3 : SELECT"));
        assertTrue(text, text.contains("alt exists"));
        assertTrue(text, text.contains("deactivate p1"));
        assertTrue(text, text.trim().endsWith("@enduml"));
    }

    @Test
    public void mermaid() {
        String text = MermaidExporter.export(sample());
        assertTrue(text, text.startsWith("sequenceDiagram"));
        assertTrue(text, text.contains("p1->>+p2: findByEmail(email)"));
        assertTrue(text, text.contains("p2-->>-p1: Optional<User>"));
        assertTrue(text, text.contains("deactivate p1"));
        assertTrue(text, text.contains("Note right of p2: SELECT * FROM users<br/>WHERE email = ?"));
    }
}
