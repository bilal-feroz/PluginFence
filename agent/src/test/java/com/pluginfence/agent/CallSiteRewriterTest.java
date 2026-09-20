package com.pluginfence.agent;

import com.pluginfence.bootstrap.GuardBridge;
import com.pluginfence.bootstrap.OperationType;
import com.pluginfence.bootstrap.PluginIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.CheckClassAdapter;

import java.io.File;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * In-process test of the bytecode rewriting: the subject classes are loaded through a class loader
 * that runs them through {@link FenceTransformer#rewrite}, verified with ASM's CheckClassAdapter,
 * and then executed against a scripted policy.
 */
class CallSiteRewriterTest {

    private static final String SUBJECT_PACKAGE = "com.pluginfence.agent.subject.";

    private RecordingProvider provider;
    private RewritingClassLoader loader;
    private int token;

    @BeforeEach
    void setUp() {
        provider = new RecordingProvider().install();
        loader = new RewritingClassLoader(getClass().getClassLoader());
        token = GuardBridge.registerIdentity(new PluginIdentity("com.test.rewritten", "Rewritten Plugin", "1.2.3", "t", false, "unit", loader));
        loader.token = token;
    }

    @AfterEach
    void tearDown() {
        provider.uninstall();
    }

    @Test
    void rewrittenClassPassesBytecodeVerification() throws Exception {
        for (String name : List.of("FileSubject", "SystemSubject")) {
            byte[] rewritten = loader.rewrittenBytes(SUBJECT_PACKAGE + name);
            assertNotNull(rewritten, name + " should contain rewritten call sites");
            StringWriter out = new StringWriter();
            CheckClassAdapter.verify(new ClassReader(rewritten), loader, false, new PrintWriter(out));
            assertEquals("", out.toString(), "verification errors in " + name + ":\n" + out);
        }
        assertTrue(loader.transformer.callSitesRewritten() >= 15, "expected many call sites, got " + loader.transformer.callSitesRewritten());
    }

    @Test
    void staticReplacementInterceptsAndAttributes(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("project.txt");
        Files.writeString(file, "content");

        Object result = call("FileSubject", "read", new Class<?>[]{Path.class}, file);

        assertEquals("content", result);
        assertEquals(1, provider.requests.size());
        assertEquals(OperationType.FILE_READ, provider.last().operation());
        assertEquals("com.test.rewritten", provider.last().identity().pluginId());
        assertEquals("1.2.3", provider.last().identity().pluginVersion());
        assertEquals(SUBJECT_PACKAGE + "FileSubject", provider.last().sourceClass());
        assertEquals(file.toAbsolutePath().normalize().toString(), provider.last().target());
    }

    @Test
    void blockedReadNeverReturnsContent(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("id_rsa");
        Files.writeString(file, "FAKE_KEY");
        provider.blockEverything();

        Throwable cause = assertThrows(InvocationTargetException.class,
                () -> call("FileSubject", "read", new Class<?>[]{Path.class}, file)).getCause();
        assertTrue(cause instanceof SecurityException, "got " + cause);
        assertFalse(cause.getMessage().contains("FAKE_KEY"));
    }

    @Test
    void blockedWriteLeavesFilesystemUntouched(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("out.txt");
        provider.block(OperationType.FILE_WRITE);
        assertThrows(InvocationTargetException.class,
                () -> call("FileSubject", "write", new Class<?>[]{Path.class, String.class}, file, "x"));
        assertFalse(Files.exists(file));
    }

    @Test
    void constructorPreCheckTop1(@TempDir Path dir) throws Exception {
        File file = dir.resolve("s.txt").toFile();
        Files.writeString(file.toPath(), "stream");
        assertEquals("stream", call("FileSubject", "readViaStream", new Class<?>[]{File.class}, file));
        assertEquals("new java.io.FileInputStream", provider.last().api());

        provider.blockEverything();
        Throwable cause = assertThrows(InvocationTargetException.class,
                () -> call("FileSubject", "readViaStream", new Class<?>[]{File.class}, file)).getCause();
        assertTrue(cause instanceof SecurityException);
    }

    @Test
    void constructorPreCheckSecondFromTop(@TempDir Path dir) throws Exception {
        File file = dir.resolve("r.txt").toFile();
        Files.writeString(file.toPath(), "reader");
        assertEquals("reader", call("FileSubject", "readViaReaderWithCharset", new Class<?>[]{File.class}, file));
        assertEquals(file.getAbsolutePath(), provider.last().target());

        File out = dir.resolve("w.txt").toFile();
        call("FileSubject", "writeViaStream", new Class<?>[]{File.class, boolean.class, String.class}, out, true, "appended");
        assertEquals(OperationType.FILE_WRITE, provider.last().operation());
        assertEquals("appended", Files.readString(out.toPath()));
    }

    @Test
    void kotlinStdlibDelegationKeepsSemantics(@TempDir Path dir) throws Exception {
        File file = dir.resolve("k.txt").toFile();
        Files.writeString(file.toPath(), "a\nb\r\nc");

        assertEquals("a\nb\r\nc", call("FileSubject", "kotlinReadText", new Class<?>[]{File.class}, file));
        assertEquals("kotlin.io.readText", provider.last().api());
        assertEquals(Arrays.asList("a", "b", "c"), call("FileSubject", "kotlinForEachLine", new Class<?>[]{File.class}, file));

        File out = dir.resolve("kw.txt").toFile();
        call("FileSubject", "kotlinWriteText", new Class<?>[]{File.class, String.class}, out, "written");
        assertEquals("written", Files.readString(out.toPath(), StandardCharsets.UTF_8));

        provider.block(OperationType.FILE_READ);
        Throwable cause = assertThrows(InvocationTargetException.class,
                () -> call("FileSubject", "kotlinReadText", new Class<?>[]{File.class}, file)).getCause();
        assertTrue(cause instanceof SecurityException);
    }

    @Test
    void receiverPreCheckOnFileDelete(@TempDir Path dir) throws Exception {
        File file = dir.resolve("d.txt").toFile();
        Files.writeString(file.toPath(), "x");
        provider.block(OperationType.FILE_WRITE);
        assertThrows(InvocationTargetException.class, () -> call("FileSubject", "deleteFile", new Class<?>[]{File.class}, file));
        assertTrue(file.exists(), "blocked delete must not remove the file");
        assertEquals("java.io.File.delete", provider.last().api());
    }

    @Test
    void environmentReadIsInterceptedAndBlockedReadsLookUnset() throws Exception {
        String path = (String) call("SystemSubject", "env", new Class<?>[]{String.class}, "PATH");
        assertEquals(System.getenv("PATH"), path);
        assertEquals(OperationType.ENV_READ, provider.last().operation());
        assertEquals("PATH", provider.last().target());

        provider.blockEverything();
        assertEquals(null, call("SystemSubject", "env", new Class<?>[]{String.class}, "PATH"));
    }

    @Test
    void processStartIsInterceptedAndBlockedBeforeSpawn() throws Exception {
        provider.block(OperationType.PROCESS_EXEC);
        List<String> cmd = List.of("definitely-not-a-real-binary-pluginfence", "--token=abc");
        Throwable cause = assertThrows(InvocationTargetException.class,
                () -> call("SystemSubject", "start", new Class<?>[]{List.class}, cmd)).getCause();
        assertTrue(cause instanceof SecurityException, "blocked before IOException from a missing binary: " + cause);
        assertEquals("definitely-not-a-real-binary-pluginfence", provider.last().target());
        assertEquals("--token=***", provider.last().metadata("args"));
    }

    @Test
    void socketConnectAndConstructorAreIntercepted() throws Exception {
        provider.block(OperationType.NETWORK_CONNECT);
        Throwable cause = assertThrows(InvocationTargetException.class,
                () -> call("SystemSubject", "connect", new Class<?>[]{String.class, int.class, int.class}, "198.51.100.42", 8080, 200)).getCause();
        assertTrue(cause instanceof SecurityException);
        assertEquals("198.51.100.42:8080", provider.last().target());

        cause = assertThrows(InvocationTargetException.class,
                () -> call("SystemSubject", "connectViaConstructor", new Class<?>[]{String.class, int.class}, "198.51.100.42", 8080)).getCause();
        assertTrue(cause instanceof SecurityException);
        assertEquals("198.51.100.42:8080", provider.last().target());
        assertEquals("new java.net.Socket", provider.last().api());
    }

    @Test
    void urlOpenConnectionIsInterceptedWithRedactedUrl() throws Exception {
        provider.block(OperationType.NETWORK_CONNECT);
        assertThrows(InvocationTargetException.class,
                () -> call("SystemSubject", "open", new Class<?>[]{String.class}, "https://user:pw@evil.example:8443/x?token=abc"));
        assertEquals("evil.example:8443", provider.last().target());
        assertEquals("https://***@evil.example:8443/x?token=***", provider.last().metadata("url"));
    }

    // --- helpers ------------------------------------------------------------------------------

    private Object call(String subject, String method, Class<?>[] types, Object... args) throws Exception {
        Class<?> c = loader.loadClass(SUBJECT_PACKAGE + subject);
        return c.getMethod(method, types).invoke(null, args);
    }

    /** Child-first loader for the subject package that rewrites bytecode on the way in. */
    static final class RewritingClassLoader extends ClassLoader {
        final FenceTransformer transformer = new FenceTransformer(AgentConfig.fromSystemProperties(),
                new PluginLoaderRegistry(AgentConfig.fromSystemProperties()));
        int token;

        RewritingClassLoader(ClassLoader parent) {
            super(parent);
        }

        byte[] rewrittenBytes(String name) throws Exception {
            String resource = name.replace('.', '/') + ".class";
            try (InputStream in = getParent().getResourceAsStream(resource)) {
                assertNotNull(in, "missing " + resource);
                return transformer.rewrite(name.replace('.', '/'), in.readAllBytes(), token);
            }
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.startsWith(SUBJECT_PACKAGE)) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> existing = findLoadedClass(name);
                if (existing != null) return existing;
                try {
                    byte[] bytes = rewrittenBytes(name);
                    if (bytes == null) {
                        try (InputStream in = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                            bytes = in.readAllBytes();
                        }
                    }
                    return defineClass(name, bytes, 0, bytes.length);
                } catch (Exception e) {
                    throw new ClassNotFoundException(name, e);
                }
            }
        }
    }
}
