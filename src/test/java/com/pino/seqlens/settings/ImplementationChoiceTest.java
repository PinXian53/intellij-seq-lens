package com.pino.seqlens.settings;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ImplementationChoiceTest {

    @Test
    public void setReplaceAndClear() {
        SeqLensSettings.State s = new SeqLensSettings.State();
        s.setImplementationChoice("a.Gateway", "a.Stripe");
        s.setImplementationChoice("a.Notifier", "a.Mail");
        s.setImplementationChoice("a.Gateway", "a.Paypal");
        assertEquals(Map.of("a.Gateway", "a.Paypal", "a.Notifier", "a.Mail"), s.implementationChoiceMap());
        assertEquals("a.Gateway=a.Paypal\na.Notifier=a.Mail", s.implementationChoices);
        s.setImplementationChoice("a.Gateway", null);
        assertEquals(Map.of("a.Notifier", "a.Mail"), s.implementationChoiceMap());
    }

    @Test
    public void ignoresCommentsBlankAndMalformedLines() {
        SeqLensSettings.State s = new SeqLensSettings.State();
        s.implementationChoices = "# comment\n\n  a.Gateway = a.Stripe  \nnot-a-pair\n=a.Missing";
        assertEquals(Map.of("a.Gateway", "a.Stripe"), s.implementationChoiceMap());
        s.implementationChoices = null;
        assertTrue(s.implementationChoiceMap().isEmpty());
    }
}
