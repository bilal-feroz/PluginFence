package com.pluginfence.bootstrap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuardHooksTest {

    private final List<SecurityRequest> recorded = new ArrayList<>();
    private volatile Verdict verdict = Verdict.ALLOW;
    private DecisionProvider provider;
    private int token;

    @BeforeEach
    void setUp() {
        provider = new DecisionProvider() {
            @Override
            public SecurityDecision decide(SecurityRequest request) {
                return new SecurityDecision(verdict, "test", "rule.test", 50);
            }

            @Override
            public void record(SecurityRequest request, SecurityDecision decision) {
                recorded.add(request);
            }
        };
        GuardBridge.setProvider(provider);
        GuardBridge.setEnforcementEnabled(true);
        token = GuardBridge.registerIdentity(new PluginIdentity("com.test.plugin", "Test Plugin", "1.0", "Tester", false, "test", null));
    }

    @AfterEach
    void tearDown() {
        GuardBridge.clearProvider(provider);
        GuardBridge.drainPending();
    }

    @Test
    void allowedFileReadExecutesAndIsRecorded(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("note.txt");
        Files.writeString(file, "hello");

        String content = GuardHooks.Files_readString(file, token, "com.test.Caller");

        assertEquals("hello", content);
        assertEquals(1, recorded.size());
        SecurityRequest r = recorded.get(0);
        assertEquals(OperationType.FILE_READ, r.operation());
        assertEquals(file.toAbsolutePath().normalize().toString(), r.target());
        assertEquals("com.test.plugin", r.identity().pluginId());
        assertEquals("com.test.Caller", r.sourceClass());
        assertEquals("java.nio.file.Files.readString", r.api());
    }

    @Test
    void blockedFileReadThrowsAndDoesNotRead(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("id_rsa");
        Files.writeString(file, "FAKE");
        verdict = Verdict.BLOCK;

        SecurityException ex = assertThrows(SecurityException.class,
                () -> GuardHooks.Files_readString(file, token, "com.test.Caller"));
        assertTrue(ex.getMessage().contains("PluginFence blocked"));
        assertFalse(ex.getMessage().contains("FAKE"), "secret contents must never appear in messages");
        assertEquals(1, recorded.size());
    }

    @Test
    void askAlsoPreventsTheOperation(@TempDir Path dir) {
        verdict = Verdict.ASK;
        assertThrows(SecurityException.class,
                () -> GuardHooks.Files_readAllBytes(dir.resolve("x"), token, "c"));
    }

    @Test
    void blockedEnvironmentReadReturnsNullInsteadOfThrowing() {
        verdict = Verdict.BLOCK;
        assertNull(GuardHooks.System_getenv("PATH", token, "c"));
        assertEquals("PATH", recorded.get(0).target());
        assertEquals(OperationType.ENV_READ, recorded.get(0).operation());
    }

    @Test
    void enumeratedEnvironmentHidesBlockedVariables() {
        verdict = Verdict.BLOCK;
        Map<String, String> env = GuardHooks.System_getenv(token, "c");
        assertTrue(env.isEmpty(), "all variables hidden when policy blocks");
        assertNull(env.get("PATH"));
        verdict = Verdict.ALLOW;
        Map<String, String> open = GuardHooks.System_getenv(token, "c");
        assertEquals(System.getenv().size(), open.size());
    }

    @Test
    void blockedNetworkConnectThrowsBeforeConnecting() {
        verdict = Verdict.BLOCK;
        InetSocketAddress addr = new InetSocketAddress("198.51.100.42", 8080);
        assertThrows(SecurityException.class, () -> GuardHooks.Socket_connect(new java.net.Socket(), addr, token, "c"));
        SecurityRequest r = recorded.get(0);
        assertEquals("198.51.100.42:8080", r.target());
        assertEquals("198.51.100.42", r.metadata(SecurityRequest.META_HOST));
        assertEquals("8080", r.metadata(SecurityRequest.META_PORT));
    }

    @Test
    void blockedProcessThrowsBeforeStart() {
        verdict = Verdict.BLOCK;
        ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-Command", "echo", "--token=abc");
        assertThrows(SecurityException.class, () -> GuardHooks.ProcessBuilder_start(pb, token, "c"));
        SecurityRequest r = recorded.get(0);
        assertEquals("powershell.exe", r.target());
        assertEquals("-Command echo --token=***", r.metadata(SecurityRequest.META_ARGS));
    }

    @Test
    void nestedHooksPassThroughWhileInsideAGuard(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("f.txt");
        Files.writeString(file, "x");
        verdict = Verdict.BLOCK;
        String value = GuardHooks.withGuard(() -> {
            try {
                return GuardHooks.Files_readString(file, token, "c");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        assertEquals("x", value);
        assertTrue(recorded.isEmpty());
    }

    @Test
    void noProviderMeansMonitorOnlyAndBuffering(@TempDir Path dir) throws IOException {
        GuardBridge.clearProvider(provider);
        Path file = dir.resolve("f.txt");
        Files.writeString(file, "x");
        assertEquals("x", GuardHooks.Files_readString(file, token, "c"));
        List<GuardBridge.BufferedEvent> pending = GuardBridge.drainPending();
        assertEquals(1, pending.size());
        assertSame(Verdict.MONITOR, pending.get(0).decision().verdict());
    }

    @Test
    void providerFailureFailsOpen(@TempDir Path dir) throws IOException {
        GuardBridge.setProvider(new DecisionProvider() {
            @Override
            public SecurityDecision decide(SecurityRequest request) {
                throw new IllegalStateException("boom");
            }

            @Override
            public void record(SecurityRequest request, SecurityDecision decision) {
                throw new IllegalStateException("boom");
            }
        });
        Path file = dir.resolve("f.txt");
        Files.writeString(file, "x");
        assertEquals("x", GuardHooks.Files_readString(file, token, "c"));
        assertTrue(GuardBridge.providerFailureCount() > 0);
    }

    @Test
    void enforcementKillSwitchDowngradesToMonitor(@TempDir Path dir) throws IOException {
        verdict = Verdict.BLOCK;
        GuardBridge.setEnforcementEnabled(false);
        Path file = dir.resolve("f.txt");
        Files.writeString(file, "x");
        assertEquals("x", GuardHooks.Files_readString(file, token, "c"));
        assertNotNull(recorded.get(0));
    }

    @Test
    void unknownTokenResolvesToUnknownIdentity() {
        assertSame(PluginIdentity.UNKNOWN, GuardBridge.identity(0));
        assertSame(PluginIdentity.UNKNOWN, GuardBridge.identity(99999));
        assertEquals("Unknown third-party plugin", PluginIdentity.UNKNOWN.displayName());
    }
}
