package com.pluginfence.agent;

import com.pluginfence.agent.subject.FileSubject;
import com.pluginfence.agent.subject.SystemSubject;
import com.pluginfence.bootstrap.GuardBridge;
import com.pluginfence.bootstrap.GuardHooks;
import com.pluginfence.bootstrap.OperationType;
import com.pluginfence.bootstrap.SecurityRequest;
import com.pluginfence.bootstrap.Verdict;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs inside a JVM launched with the real {@code -javaagent:plugin-fence-agent.jar}
 * (see the {@code agentTest} Gradle task). The subject package is force-instrumented through
 * {@code pluginfence.agent.instrument.packages}; nothing here is mocked.
 */
@Tag("agent")
class AgentIntegrationTest {

    private RecordingProvider provider;

    @BeforeEach
    void setUp() {
        provider = new RecordingProvider().install();
    }

    @AfterEach
    void tearDown() {
        provider.uninstall();
    }

    @Test
    void agentIsInstalledAndBootstrapIsOnBootClassPath() {
        assertTrue(GuardBridge.isAgentInstalled(), "premain did not run");
        assertNull(GuardBridge.class.getClassLoader(), "GuardBridge must be defined by the boot loader");
        assertNull(GuardHooks.class.getClassLoader());
        assertTrue(GuardBridge.agentInfo().contains("premain"));
        Map<String, String> diag = GuardBridge.diagnostics();
        assertEquals(Integer.toString(HookRules.all().size()), diag.get("rules"));
    }

    @Test
    void fileReadIsInterceptedAttributedAndAllowed(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("README.md");
        Files.writeString(file, "hello");

        assertEquals("hello", FileSubject.read(file));

        SecurityRequest r = provider.lastOf(OperationType.FILE_READ);
        assertEquals("com.pluginfence.test.subject", r.identity().pluginId());
        assertEquals("Subject Plugin", r.identity().pluginName());
        assertEquals("9.9.9", r.identity().pluginVersion());
        assertEquals("com.pluginfence.agent.subject.FileSubject", r.sourceClass());
        assertEquals(Verdict.ALLOW, provider.decisions.get(provider.decisions.size() - 1).verdict());
        assertTrue(Long.parseLong(GuardBridge.diagnostics().get("classes.instrumented")) >= 1);
    }

    @Test
    void sensitiveFileReadIsBlockedAndContentNeverLeaks(@TempDir Path dir) throws IOException {
        Path sshDir = dir.resolve(".ssh");
        Files.createDirectories(sshDir);
        Path key = sshDir.resolve("id_rsa");
        Files.writeString(key, "FAKE_PLUGINFENCE_DEMO_PRIVATE_KEY");
        provider.policy = r -> r.target().contains(".ssh") ? Verdict.BLOCK : Verdict.ALLOW;

        SecurityException ex = assertThrows(SecurityException.class, () -> FileSubject.read(key));
        assertFalse(ex.getMessage().contains("FAKE_PLUGINFENCE"));
        assertTrue(ex.getMessage().contains("id_rsa"));
        assertThrows(SecurityException.class, () -> FileSubject.readBytes(key));
        assertThrows(SecurityException.class, () -> FileSubject.readViaStream(key.toFile()));
        assertThrows(SecurityException.class, () -> FileSubject.kotlinReadText(key.toFile()));

        // an ordinary file in the same run is still readable
        Path ok = dir.resolve("notes.txt");
        Files.writeString(ok, "fine");
        assertEquals("fine", FileSubject.read(ok));
    }

    @Test
    void blockedWriteDoesNotTouchDisk(@TempDir Path dir) {
        provider.block(OperationType.FILE_WRITE);
        Path target = dir.resolve("dropped.txt");
        assertThrows(SecurityException.class, () -> FileSubject.write(target, "payload"));
        assertThrows(SecurityException.class, () -> FileSubject.kotlinWriteText(target.toFile(), "payload"));
        assertFalse(Files.exists(target));
    }

    @Test
    void processExecutionIsInterceptedAndBlockedBeforeStart() {
        provider.block(OperationType.PROCESS_EXEC);
        List<String> cmd = List.of(javaBinary(), "-version");
        assertThrows(SecurityException.class, () -> SystemSubject.start(cmd));
        assertThrows(SecurityException.class, () -> SystemSubject.exec(cmd.toArray(new String[0])));
        SecurityRequest r = provider.lastOf(OperationType.PROCESS_EXEC);
        assertTrue(r.target().startsWith("java"), r.target());
        assertEquals("-version", r.metadata(SecurityRequest.META_ARGS));
    }

    @Test
    void allowedProcessActuallyRuns() throws Exception {
        Process p = SystemSubject.start(List.of(javaBinary(), "-version"));
        assertEquals(0, p.waitFor());
        assertEquals(OperationType.PROCESS_EXEC, provider.last().operation());
    }

    @Test
    void networkConnectIsInterceptedAndBlockedBeforeTheSocketOpens() throws IOException {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            int port = server.getLocalPort();
            provider.block(OperationType.NETWORK_CONNECT);
            assertThrows(SecurityException.class, () -> SystemSubject.connect("127.0.0.1", port, 500));
            assertThrows(SecurityException.class, () -> SystemSubject.connectViaConstructor("127.0.0.1", port));
            assertThrows(SecurityException.class, () -> SystemSubject.open("http://127.0.0.1:" + port + "/x"));
            assertThrows(SecurityException.class, () -> SystemSubject.httpClientStatus("http://127.0.0.1:" + port + "/x"));
            SecurityRequest r = provider.lastOf(OperationType.NETWORK_CONNECT);
            assertEquals("127.0.0.1:" + port, r.target());

            provider.allowEverything();
            SystemSubject.connect("127.0.0.1", port, 500); // allowed: really connects
            assertNotNull(server.accept());
        }
    }

    @Test
    void environmentReadsAreGovernedWithoutExposingValues() {
        provider.policy = r -> r.target().endsWith("_TOKEN") || r.target().endsWith("_KEY") ? Verdict.BLOCK : Verdict.ALLOW;
        assertNull(SystemSubject.env("GITHUB_TOKEN"));
        assertEquals(System.getenv("PATH"), SystemSubject.env("PATH"));
        Map<String, String> all = SystemSubject.allEnv();
        assertEquals(System.getenv("PATH"), all.get("PATH"));
        for (SecurityRequest r : provider.requests) {
            assertFalse(r.toString().contains(System.getenv("PATH")), "values must never be recorded");
        }
    }

    @Test
    void nonSubjectCodeIsNeverInstrumented(@TempDir Path dir) throws IOException {
        // A read performed by test code (outside the forced package) must not produce an event.
        Path file = dir.resolve("plain.txt");
        Files.writeString(file, "x");
        int before = provider.requests.size();
        assertEquals("x", Files.readString(file));
        assertEquals(before, provider.requests.size());
    }

    private static String javaBinary() {
        return new File(System.getProperty("java.home"), "bin" + File.separator + "java").getAbsolutePath();
    }
}
