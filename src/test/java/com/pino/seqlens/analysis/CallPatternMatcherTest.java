package com.pino.seqlens.analysis;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CallPatternMatcherTest {

    private static boolean m(String patterns, String fqn, String method) {
        String simple = fqn.substring(fqn.lastIndexOf('.') + 1);
        return CallPatternMatcher.parse(patterns).matches(fqn, simple, method);
    }

    @Test
    public void fullyQualifiedClass() {
        assertTrue(m("com.foo.AuditService", "com.foo.AuditService", "log"));
        assertFalse(m("com.foo.AuditService", "com.foo.AuditServiceImpl", "log"));
    }

    @Test
    public void packageWildcard() {
        assertTrue(m("com.foo.util.*", "com.foo.util.DateUtil", "now"));
        assertFalse(m("com.foo.util.*", "com.foo.service.UserService", "now"));
    }

    @Test
    public void simpleNameAndGlob() {
        assertTrue(m("AuditService", "com.foo.AuditService", "log"));
        assertTrue(m("*Client", "com.foo.http.PaymentClient", "charge"));
        assertFalse(m("*Client", "com.foo.http.ClientFactory", "create"));
    }

    @Test
    public void methodPatterns() {
        assertTrue(m("AuditService#log*", "com.foo.AuditService", "logAccess"));
        assertFalse(m("AuditService#log*", "com.foo.AuditService", "record"));
        assertTrue(m("*#toString", "com.foo.User", "toString"));
        assertTrue(m("com.foo.User#<init>", "com.foo.User", "<init>"));
        assertTrue(m("Mapper#map()", "com.foo.Mapper", "map"));
    }

    @Test
    public void separatorsAndComments() {
        String patterns = "# 註解\ncom.a.*, com.b.X; *Util\n\n";
        assertTrue(m(patterns, "com.a.Y", "x"));
        assertTrue(m(patterns, "com.b.X", "x"));
        assertTrue(m(patterns, "com.c.StringUtil", "x"));
        assertFalse(m(patterns, "com.c.Other", "x"));
        assertTrue(CallPatternMatcher.parse("  \n# only comment").isEmpty());
    }
}
