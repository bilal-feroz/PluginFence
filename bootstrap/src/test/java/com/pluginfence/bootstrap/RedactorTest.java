package com.pluginfence.bootstrap;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedactorTest {

    @Test
    void stripsUserInfoFromUrls() {
        assertEquals("https://***@example.com/path", Redactor.redactUrl("https://user:hunter2@example.com/path"));
    }

    @Test
    void masksCredentialQueryParameters() {
        String redacted = Redactor.redactUrl("https://api.example.com/v1?access_token=abc123&page=2&api_key=zzz");
        assertEquals("https://api.example.com/v1?access_token=***&page=2&api_key=***", redacted);
    }

    @Test
    void dropsFragments() {
        assertEquals("https://example.com/a", Redactor.redactUrl("https://example.com/a#token=secret"));
    }

    @Test
    void masksFlagAssignmentsAndFollowingValues() {
        List<String> out = Redactor.sanitizeArguments(Arrays.asList(
                "curl", "--token=abcdef", "-u", "admin:pw", "--password", "hunter2", "https://h/x?key=1"));
        assertEquals(Arrays.asList("curl", "--token=***", "-u", "***", "--password", "***", "https://h/x?key=***"), out);
    }

    @Test
    void masksAuthorizationHeaders() {
        List<String> out = Redactor.sanitizeArguments(Arrays.asList("-H", "Authorization: Bearer eyJabc.def.ghi"));
        assertEquals("Authorization: Bearer ***", out.get(1));
    }

    @Test
    void masksHighEntropyTokensAndSecretEnvAssignments() {
        List<String> out = Redactor.sanitizeArguments(Arrays.asList(
                "sk-ABCDEFGHIJKLMNOPQRSTUVWXYZ123456", "OPENAI_API_KEY=abc", "HOME=/tmp", "ghp_abcdefghijklmnop123456"));
        assertEquals(Arrays.asList("***", "OPENAI_API_KEY=***", "HOME=/tmp", "***"), out);
    }

    @Test
    void doesNotMaskLongOrdinaryIdentifiers() {
        assertFalse(Redactor.looksLikeSecret("definitely-not-a-real-binary-pluginfence"));
        assertFalse(Redactor.looksLikeSecret("com.pluginfence.demo.helper.DemoAction"));
        assertFalse(Redactor.looksLikeSecret("--some-very-long-command-line-flag-name"));
        assertTrue(Redactor.looksLikeSecret("ghp_16C7e42F292c6912E7710c838347Ae178B4a"));
        assertTrue(Redactor.looksLikeSecret("AKIAIOSFODNN7EXAMPLE"));
        assertTrue(Redactor.looksLikeSecret("xK9pQ2mZ7vT4wR8nL3sB6yH1jF5gD0aC"));
        assertTrue(Redactor.looksLikeSecret("2fd4e1c67a2d28fced849ee1bb76e7391b93eb12"));
    }

    @Test
    void leavesOrdinaryArgumentsAlone() {
        List<String> out = Redactor.sanitizeArguments(Arrays.asList("git", "status", "--porcelain"));
        assertEquals(Arrays.asList("git", "status", "--porcelain"), out);
        assertFalse(Redactor.isSecretName("PATH"));
        assertTrue(Redactor.isSecretName("AWS_SECRET_ACCESS_KEY"));
    }
}
