package com.nokia.workflow.gateway;

import com.sun.net.httpserver.Headers;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SecurityHelpersTest {

    private static Headers headers(String... kv) {
        Headers h = new Headers();
        for (int i = 0; i < kv.length; i += 2) {
            h.add(kv[i], kv[i + 1]);
        }
        return h;
    }

    @Test
    public void writesAreAcceptedOnlyFromThePageOnTheSameHost() {
        assertTrue(Gateway.sameOrigin(headers("Host", "localhost:8080", "Origin", "http://localhost:8080")));
        assertTrue(Gateway.sameOrigin(headers("Host", "localhost:8080", "Referer", "http://localhost:8080/ui/main/flows")));
        assertFalse(Gateway.sameOrigin(headers("Host", "localhost:8080", "Origin", "http://evil.example")));
        assertFalse(Gateway.sameOrigin(headers("Host", "localhost:8080", "Origin", "http://localhost:8080.evil.example")));
        assertFalse(Gateway.sameOrigin(headers("Host", "localhost:8080")));
        assertFalse(Gateway.sameOrigin(headers("Host", "localhost:8080", "Origin", "null")));
    }

    @Test
    public void theSessionCookieAndKestrasCookieNeverReachKestra() {
        String forwarded = Kestra.forwardedCookies(List.of("CR_SESSION=secret; theme=dark; BASIC_AUTH=abc", "lang=en"));
        assertEquals("theme=dark; lang=en", forwarded);
        assertEquals("secret", Gateway.cookie(headers("Cookie", "a=1; CR_SESSION=secret"), "CR_SESSION"));
    }

    @Test
    public void processFlowsAreRecognisedByTheirLabel() {
        assertTrue(Kestra.hasLabel(List.of(Map.of("key", "process", "value", "true")), "process", "true"));
        assertFalse(Kestra.hasLabel(List.of(Map.of("key", "project", "value", "CR_AUTOMATION")), "process", "true"));
        assertFalse(Kestra.hasLabel(null, "process", "true"));
    }

    @Test
    public void passwordsAreSaltedAndVerified() {
        String a = Passwords.hash("Correct horse 1".toCharArray());
        String b = Passwords.hash("Correct horse 1".toCharArray());
        assertNotEquals(a, b);
        assertTrue(Passwords.verify("Correct horse 1".toCharArray(), a));
        assertFalse(Passwords.verify("correct horse 1".toCharArray(), a));
        assertFalse(Passwords.verify("x".toCharArray(), "garbage"));
    }

    @Test
    public void passwordRules() {
        assertFalse(Passwords.problems("a@b.c", "short").isEmpty());
        assertFalse(Passwords.problems("someone@nokia.com", "SOMEONE@nokia.com").isEmpty());
        assertTrue(Passwords.problems("a@b.c", "long enough pass 1").isEmpty());
    }

    @Test
    public void userNamesAreEMailAddressesInLowerCase() {
        assertEquals("ops@nokia.com", UserStore.normalizeUsername("  Ops@Nokia.COM "));
        assertEquals(null, UserStore.normalizeUsername("admin"));
        assertEquals(null, UserStore.normalizeUsername("a b@c.d"));
    }
}
