package com.pluginfence.bootstrap;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Proxy;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URL;
import java.net.URLConnection;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Static entry points that instrumented third-party plugin bytecode is rewritten to call.
 * <p>
 * Two shapes exist:
 * <ul>
 *   <li><b>Replacement hooks</b> mirror the original method's descriptor plus
 *       {@code (int token, String sourceClass)} and perform the original operation after the
 *       policy check (e.g. {@link #Files_readString(Path, int, String)}).</li>
 *   <li><b>Pre-check hooks</b> ({@code check*}) receive a copy of the relevant argument, evaluate
 *       policy and return; the original instruction still executes afterwards.</li>
 * </ul>
 * A blocked operation raises {@link SecurityException}, the same exception type the JDK
 * documents for security-manager denials of these APIs. Blocked environment reads return
 * {@code null} (the variable appears unset) instead of throwing.
 * <p>
 * Re-entrancy: while a policy check is running on a thread, nested hooks on that thread
 * pass straight through. The original operation always runs <em>outside</em> the guard so
 * that plugin callbacks (e.g. Kotlin lambdas) remain protected.
 */
@SuppressWarnings({"unused", "MethodName"})
public final class GuardHooks {

    private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[1]);

    private GuardHooks() {
    }

    // =========================================================================================
    // Core evaluation
    // =========================================================================================

    /** True if the current thread is already inside a PluginFence hook. */
    public static boolean isInsideHook() {
        return DEPTH.get()[0] > 0;
    }

    /**
     * Runs {@code body} with the re-entrancy guard held. Used by the control plane so that its own
     * persistence and logging never trigger hooks.
     */
    public static <T> T withGuard(java.util.function.Supplier<T> body) {
        int[] depth = DEPTH.get();
        depth[0]++;
        try {
            return body.get();
        } finally {
            depth[0]--;
        }
    }

    private static SecurityDecision evaluate(OperationType op, String target, String api,
                                             Map<String, String> metadata, int token, String source) {
        int[] depth = DEPTH.get();
        if (depth[0] > 0) {
            return null; // nested: transparent pass-through
        }
        depth[0]++;
        try {
            SecurityRequest request;
            try {
                request = new SecurityRequest(GuardBridge.nextEventId(), System.currentTimeMillis(),
                        GuardBridge.identity(token), source, op, target, api, metadata);
            } catch (Throwable t) {
                GuardLog.warn("failed to build request", t);
                return null;
            }
            SecurityDecision decision = GuardBridge.decide(request);
            GuardBridge.record(request, decision);
            if (decision.prevents()) {
                throw new SecurityException(blockMessage(request, decision));
            }
            return decision;
        } finally {
            depth[0]--;
        }
    }

    private static String blockMessage(SecurityRequest request, SecurityDecision decision) {
        String verb = decision.verdict() == Verdict.ASK ? "requires permission for" : "blocked";
        return "PluginFence " + verb + " " + request.operation().displayName().toLowerCase()
                + " of " + request.target() + " by " + request.identity().displayName()
                + (decision.reason().isEmpty() ? "" : " (" + decision.reason() + ")");
    }

    private static void checkFile(OperationType op, Object target, String api, int token, String source) {
        String path;
        try {
            path = Targets.describeFile(target);
        } catch (Throwable t) {
            path = String.valueOf(target);
        }
        evaluate(op, path, api, Collections.singletonMap(SecurityRequest.META_KIND, "path"), token, source);
    }

    private static void checkFileCopy(Object from, Object to, String api, int token, String source) {
        checkFile(OperationType.FILE_READ, from, api, token, source);
        checkFile(OperationType.FILE_WRITE, to, api, token, source);
    }

    private static void checkNet(Object target, String api, int token, String source) {
        Targets.NetworkTarget net;
        try {
            net = Targets.describeNetwork(target);
        } catch (Throwable t) {
            net = Targets.NetworkTarget.EMPTY;
        }
        String url = net.scheme.isEmpty() ? null : (target instanceof String
                ? Redactor.redactUrl((String) target) : Redactor.redactUrl(String.valueOf(target)));
        evaluate(OperationType.NETWORK_CONNECT, net.display, api, net.metadata("network", url), token, source);
    }

    private static void checkProc(Object command, String api, int token, String source) {
        List<String> sanitized;
        try {
            sanitized = Targets.describeCommand(command);
        } catch (Throwable t) {
            sanitized = Collections.singletonList(String.valueOf(command));
        }
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put(SecurityRequest.META_KIND, "process");
        if (!sanitized.isEmpty()) {
            meta.put(SecurityRequest.META_EXECUTABLE, sanitized.get(0));
            meta.put(SecurityRequest.META_ARGS, Redactor.joinForDisplay(sanitized.subList(1, sanitized.size()), 400));
        }
        evaluate(OperationType.PROCESS_EXEC, Targets.executableName(sanitized), api, meta, token, source);
    }

    /** Environment reads never throw: a blocked variable simply appears unset. */
    private static boolean envAllowed(String name, String api, int token, String source) {
        try {
            SecurityDecision d = evaluate(OperationType.ENV_READ, name == null ? "" : name, api,
                    Collections.singletonMap(SecurityRequest.META_KIND, "env"), token, source);
            return d == null || !d.prevents();
        } catch (SecurityException blocked) {
            return false;
        }
    }

    /** Quiet policy probe used to filter enumerated environments; not recorded as an event. */
    private static boolean envVisible(String name, int token, String source) {
        DecisionProvider p = GuardBridge.provider();
        if (p == null || !GuardBridge.isEnforcementEnabled()) return true;
        int[] depth = DEPTH.get();
        if (depth[0] > 0) return true;
        depth[0]++;
        try {
            SecurityRequest probe = new SecurityRequest(0, System.currentTimeMillis(), GuardBridge.identity(token),
                    source, OperationType.ENV_READ, name, "java.lang.System.getenv", Collections.emptyMap());
            SecurityDecision d = p.decide(probe);
            return d == null || !d.prevents();
        } catch (Throwable t) {
            return true;
        } finally {
            depth[0]--;
        }
    }

    // =========================================================================================
    // Pre-check hooks  (Object target, int token, String sourceClass, String api) -> void
    // =========================================================================================

    public static void checkFileRead(Object target, int token, String source, String api) {
        checkFile(OperationType.FILE_READ, target, api, token, source);
    }

    public static void checkFileWrite(Object target, int token, String source, String api) {
        checkFile(OperationType.FILE_WRITE, target, api, token, source);
    }

    public static void checkNetwork(Object target, int token, String source, String api) {
        checkNet(target, api, token, source);
    }

    public static void checkNetworkHostPort(Object host, int port, int token, String source, String api) {
        String h = host == null ? "" : (host instanceof String ? (String) host : Targets.describeNetwork(host).host);
        checkNet(port > 0 ? h + ":" + port : h, api, token, source);
    }

    public static void checkProcess(Object command, int token, String source, String api) {
        checkProc(command, api, token, source);
    }

    public static void checkEnvRead(Object name, int token, String source, String api) {
        // Cannot suppress the value here (the original call still runs), so a blocked read throws.
        String n = String.valueOf(name);
        evaluate(OperationType.ENV_READ, n, api, Collections.singletonMap(SecurityRequest.META_KIND, "env"), token, source);
    }

    public static void noteEnvEnumerate(int token, String source, String api) {
        evaluate(OperationType.ENV_ENUMERATE, "*", api, Collections.singletonMap(SecurityRequest.META_KIND, "env"), token, source);
    }

    // =========================================================================================
    // java.nio.file.Files  (replacement hooks)
    // =========================================================================================

    public static String Files_readString(Path path, int token, String source) throws IOException {
        checkFile(OperationType.FILE_READ, path, "java.nio.file.Files.readString", token, source);
        return Files.readString(path);
    }

    public static String Files_readString(Path path, Charset cs, int token, String source) throws IOException {
        checkFile(OperationType.FILE_READ, path, "java.nio.file.Files.readString", token, source);
        return Files.readString(path, cs);
    }

    public static byte[] Files_readAllBytes(Path path, int token, String source) throws IOException {
        checkFile(OperationType.FILE_READ, path, "java.nio.file.Files.readAllBytes", token, source);
        return Files.readAllBytes(path);
    }

    public static List<String> Files_readAllLines(Path path, int token, String source) throws IOException {
        checkFile(OperationType.FILE_READ, path, "java.nio.file.Files.readAllLines", token, source);
        return Files.readAllLines(path);
    }

    public static List<String> Files_readAllLines(Path path, Charset cs, int token, String source) throws IOException {
        checkFile(OperationType.FILE_READ, path, "java.nio.file.Files.readAllLines", token, source);
        return Files.readAllLines(path, cs);
    }

    public static InputStream Files_newInputStream(Path path, OpenOption[] options, int token, String source) throws IOException {
        checkFile(OperationType.FILE_READ, path, "java.nio.file.Files.newInputStream", token, source);
        return Files.newInputStream(path, options);
    }

    public static BufferedReader Files_newBufferedReader(Path path, int token, String source) throws IOException {
        checkFile(OperationType.FILE_READ, path, "java.nio.file.Files.newBufferedReader", token, source);
        return Files.newBufferedReader(path);
    }

    public static BufferedReader Files_newBufferedReader(Path path, Charset cs, int token, String source) throws IOException {
        checkFile(OperationType.FILE_READ, path, "java.nio.file.Files.newBufferedReader", token, source);
        return Files.newBufferedReader(path, cs);
    }

    public static Stream<String> Files_lines(Path path, int token, String source) throws IOException {
        checkFile(OperationType.FILE_READ, path, "java.nio.file.Files.lines", token, source);
        return Files.lines(path);
    }

    public static Stream<String> Files_lines(Path path, Charset cs, int token, String source) throws IOException {
        checkFile(OperationType.FILE_READ, path, "java.nio.file.Files.lines", token, source);
        return Files.lines(path, cs);
    }

    public static Stream<Path> Files_list(Path dir, int token, String source) throws IOException {
        checkFile(OperationType.FILE_READ, dir, "java.nio.file.Files.list", token, source);
        return Files.list(dir);
    }

    public static Stream<Path> Files_walk(Path start, FileVisitOption[] options, int token, String source) throws IOException {
        checkFile(OperationType.FILE_READ, start, "java.nio.file.Files.walk", token, source);
        return Files.walk(start, options);
    }

    public static DirectoryStream<Path> Files_newDirectoryStream(Path dir, int token, String source) throws IOException {
        checkFile(OperationType.FILE_READ, dir, "java.nio.file.Files.newDirectoryStream", token, source);
        return Files.newDirectoryStream(dir);
    }

    public static long Files_copy(Path source_, OutputStream out, int token, String source) throws IOException {
        checkFile(OperationType.FILE_READ, source_, "java.nio.file.Files.copy", token, source);
        return Files.copy(source_, out);
    }

    public static long Files_copy(InputStream in, Path target, CopyOption[] options, int token, String source) throws IOException {
        checkFile(OperationType.FILE_WRITE, target, "java.nio.file.Files.copy", token, source);
        return Files.copy(in, target, options);
    }

    public static Path Files_copy(Path from, Path to, CopyOption[] options, int token, String source) throws IOException {
        checkFileCopy(from, to, "java.nio.file.Files.copy", token, source);
        return Files.copy(from, to, options);
    }

    public static Path Files_move(Path from, Path to, CopyOption[] options, int token, String source) throws IOException {
        checkFileCopy(from, to, "java.nio.file.Files.move", token, source);
        return Files.move(from, to, options);
    }

    public static void Files_delete(Path path, int token, String source) throws IOException {
        checkFile(OperationType.FILE_WRITE, path, "java.nio.file.Files.delete", token, source);
        Files.delete(path);
    }

    public static boolean Files_deleteIfExists(Path path, int token, String source) throws IOException {
        checkFile(OperationType.FILE_WRITE, path, "java.nio.file.Files.deleteIfExists", token, source);
        return Files.deleteIfExists(path);
    }

    public static Path Files_write(Path path, byte[] bytes, OpenOption[] options, int token, String source) throws IOException {
        checkFile(OperationType.FILE_WRITE, path, "java.nio.file.Files.write", token, source);
        return Files.write(path, bytes, options);
    }

    public static Path Files_write(Path path, Iterable<? extends CharSequence> lines, OpenOption[] options, int token, String source) throws IOException {
        checkFile(OperationType.FILE_WRITE, path, "java.nio.file.Files.write", token, source);
        return Files.write(path, lines, options);
    }

    public static Path Files_write(Path path, Iterable<? extends CharSequence> lines, Charset cs, OpenOption[] options, int token, String source) throws IOException {
        checkFile(OperationType.FILE_WRITE, path, "java.nio.file.Files.write", token, source);
        return Files.write(path, lines, cs, options);
    }

    public static Path Files_writeString(Path path, CharSequence csq, OpenOption[] options, int token, String source) throws IOException {
        checkFile(OperationType.FILE_WRITE, path, "java.nio.file.Files.writeString", token, source);
        return Files.writeString(path, csq, options);
    }

    public static Path Files_writeString(Path path, CharSequence csq, Charset cs, OpenOption[] options, int token, String source) throws IOException {
        checkFile(OperationType.FILE_WRITE, path, "java.nio.file.Files.writeString", token, source);
        return Files.writeString(path, csq, cs, options);
    }

    public static OutputStream Files_newOutputStream(Path path, OpenOption[] options, int token, String source) throws IOException {
        checkFile(OperationType.FILE_WRITE, path, "java.nio.file.Files.newOutputStream", token, source);
        return Files.newOutputStream(path, options);
    }

    public static BufferedWriter Files_newBufferedWriter(Path path, OpenOption[] options, int token, String source) throws IOException {
        checkFile(OperationType.FILE_WRITE, path, "java.nio.file.Files.newBufferedWriter", token, source);
        return Files.newBufferedWriter(path, options);
    }

    public static BufferedWriter Files_newBufferedWriter(Path path, Charset cs, OpenOption[] options, int token, String source) throws IOException {
        checkFile(OperationType.FILE_WRITE, path, "java.nio.file.Files.newBufferedWriter", token, source);
        return Files.newBufferedWriter(path, cs, options);
    }

    // =========================================================================================
    // kotlin.io.FilesKt  (replacement hooks; original invoked through the plugin's class loader)
    // =========================================================================================

    private static final String FILES_KT = "kotlin.io.FilesKt";
    private static final String FILE = "java.io.File";
    private static final String CHARSET = "java.nio.charset.Charset";
    private static final String STRING = "java.lang.String";
    private static final String FUNCTION1 = "kotlin.jvm.functions.Function1";

    public static String FilesKt_readText(java.io.File file, Charset cs, int token, String source) throws Throwable {
        checkFile(OperationType.FILE_READ, file, "kotlin.io.readText", token, source);
        return (String) Delegates.invokeStatic(token, FILES_KT, "readText", STRING, new String[]{FILE, CHARSET}, file, cs);
    }

    public static byte[] FilesKt_readBytes(java.io.File file, int token, String source) throws Throwable {
        checkFile(OperationType.FILE_READ, file, "kotlin.io.readBytes", token, source);
        return (byte[]) Delegates.invokeStatic(token, FILES_KT, "readBytes", "byte[]", new String[]{FILE}, file);
    }

    @SuppressWarnings("unchecked")
    public static List<String> FilesKt_readLines(java.io.File file, Charset cs, int token, String source) throws Throwable {
        checkFile(OperationType.FILE_READ, file, "kotlin.io.readLines", token, source);
        return (List<String>) Delegates.invokeStatic(token, FILES_KT, "readLines", "java.util.List", new String[]{FILE, CHARSET}, file, cs);
    }

    public static void FilesKt_forEachLine(java.io.File file, Charset cs, Object action, int token, String source) throws Throwable {
        checkFile(OperationType.FILE_READ, file, "kotlin.io.forEachLine", token, source);
        Delegates.invokeStatic(token, FILES_KT, "forEachLine", "void", new String[]{FILE, CHARSET, FUNCTION1}, file, cs, action);
    }

    public static void FilesKt_writeText(java.io.File file, String text, Charset cs, int token, String source) throws Throwable {
        checkFile(OperationType.FILE_WRITE, file, "kotlin.io.writeText", token, source);
        Delegates.invokeStatic(token, FILES_KT, "writeText", "void", new String[]{FILE, STRING, CHARSET}, file, text, cs);
    }

    public static void FilesKt_writeBytes(java.io.File file, byte[] bytes, int token, String source) throws Throwable {
        checkFile(OperationType.FILE_WRITE, file, "kotlin.io.writeBytes", token, source);
        Delegates.invokeStatic(token, FILES_KT, "writeBytes", "void", new String[]{FILE, "byte[]"}, file, bytes);
    }

    public static void FilesKt_appendText(java.io.File file, String text, Charset cs, int token, String source) throws Throwable {
        checkFile(OperationType.FILE_WRITE, file, "kotlin.io.appendText", token, source);
        Delegates.invokeStatic(token, FILES_KT, "appendText", "void", new String[]{FILE, STRING, CHARSET}, file, text, cs);
    }

    public static void FilesKt_appendBytes(java.io.File file, byte[] bytes, int token, String source) throws Throwable {
        checkFile(OperationType.FILE_WRITE, file, "kotlin.io.appendBytes", token, source);
        Delegates.invokeStatic(token, FILES_KT, "appendBytes", "void", new String[]{FILE, "byte[]"}, file, bytes);
    }

    public static java.io.File FilesKt_copyTo(java.io.File from, java.io.File to, boolean overwrite, int bufferSize, int token, String source) throws Throwable {
        checkFileCopy(from, to, "kotlin.io.copyTo", token, source);
        return (java.io.File) Delegates.invokeStatic(token, FILES_KT, "copyTo", FILE,
                new String[]{FILE, FILE, "boolean", "int"}, from, to, overwrite, bufferSize);
    }

    public static boolean FilesKt_deleteRecursively(java.io.File file, int token, String source) throws Throwable {
        checkFile(OperationType.FILE_WRITE, file, "kotlin.io.deleteRecursively", token, source);
        return (Boolean) Delegates.invokeStatic(token, FILES_KT, "deleteRecursively", "boolean", new String[]{FILE}, file);
    }

    // kotlin.io URL extensions
    public static byte[] ByteStreamsKt_readBytes(URL url, int token, String source) throws Throwable {
        checkNet(url, "kotlin.io.readBytes(URL)", token, source);
        return (byte[]) Delegates.invokeStatic(token, "kotlin.io.ByteStreamsKt", "readBytes", "byte[]", new String[]{"java.net.URL"}, url);
    }

    public static String TextStreamsKt_readText(URL url, Charset cs, int token, String source) throws Throwable {
        checkNet(url, "kotlin.io.readText(URL)", token, source);
        return (String) Delegates.invokeStatic(token, "kotlin.io.TextStreamsKt", "readText", STRING, new String[]{"java.net.URL", CHARSET}, url, cs);
    }

    // =========================================================================================
    // Environment
    // =========================================================================================

    public static String System_getenv(String name, int token, String source) {
        if (!envAllowed(name, "java.lang.System.getenv", token, source)) {
            return null;
        }
        return System.getenv(name);
    }

    /** Enumeration never throws: blocked variables are filtered out of the returned view. */
    public static Map<String, String> System_getenv(int token, String source) {
        try {
            evaluate(OperationType.ENV_ENUMERATE, "*", "java.lang.System.getenv",
                    Collections.singletonMap(SecurityRequest.META_KIND, "env"), token, source);
        } catch (SecurityException filtered) {
            // fall through: the view below hides everything the policy does not allow
        }
        Map<String, String> env = System.getenv();
        return new GuardedEnvironment(env, name -> envVisible(name, token, source));
    }

    // =========================================================================================
    // Processes
    // =========================================================================================

    public static Process ProcessBuilder_start(ProcessBuilder pb, int token, String source) throws IOException {
        checkProc(pb, "java.lang.ProcessBuilder.start", token, source);
        return pb.start();
    }

    public static Process Runtime_exec(Runtime rt, String command, int token, String source) throws IOException {
        checkProc(command, "java.lang.Runtime.exec", token, source);
        return rt.exec(command);
    }

    public static Process Runtime_exec(Runtime rt, String[] cmdarray, int token, String source) throws IOException {
        checkProc(cmdarray, "java.lang.Runtime.exec", token, source);
        return rt.exec(cmdarray);
    }

    public static Process Runtime_exec(Runtime rt, String command, String[] envp, int token, String source) throws IOException {
        checkProc(command, "java.lang.Runtime.exec", token, source);
        return rt.exec(command, envp);
    }

    public static Process Runtime_exec(Runtime rt, String[] cmdarray, String[] envp, int token, String source) throws IOException {
        checkProc(cmdarray, "java.lang.Runtime.exec", token, source);
        return rt.exec(cmdarray, envp);
    }

    public static Process Runtime_exec(Runtime rt, String command, String[] envp, java.io.File dir, int token, String source) throws IOException {
        checkProc(command, "java.lang.Runtime.exec", token, source);
        return rt.exec(command, envp, dir);
    }

    public static Process Runtime_exec(Runtime rt, String[] cmdarray, String[] envp, java.io.File dir, int token, String source) throws IOException {
        checkProc(cmdarray, "java.lang.Runtime.exec", token, source);
        return rt.exec(cmdarray, envp, dir);
    }

    // =========================================================================================
    // Network
    // =========================================================================================

    public static void Socket_connect(Socket socket, SocketAddress endpoint, int token, String source) throws IOException {
        checkNet(endpoint, "java.net.Socket.connect", token, source);
        socket.connect(endpoint);
    }

    public static void Socket_connect(Socket socket, SocketAddress endpoint, int timeout, int token, String source) throws IOException {
        checkNet(endpoint, "java.net.Socket.connect", token, source);
        socket.connect(endpoint, timeout);
    }

    public static boolean SocketChannel_connect(SocketChannel channel, SocketAddress remote, int token, String source) throws IOException {
        checkNet(remote, "java.nio.channels.SocketChannel.connect", token, source);
        return channel.connect(remote);
    }

    public static SocketChannel SocketChannel_open(SocketAddress remote, int token, String source) throws IOException {
        checkNet(remote, "java.nio.channels.SocketChannel.open", token, source);
        return SocketChannel.open(remote);
    }

    public static URLConnection URL_openConnection(URL url, int token, String source) throws IOException {
        checkNet(url, "java.net.URL.openConnection", token, source);
        return url.openConnection();
    }

    public static URLConnection URL_openConnection(URL url, Proxy proxy, int token, String source) throws IOException {
        checkNet(url, "java.net.URL.openConnection", token, source);
        return url.openConnection(proxy);
    }

    public static InputStream URL_openStream(URL url, int token, String source) throws IOException {
        checkNet(url, "java.net.URL.openStream", token, source);
        return url.openStream();
    }

    // java.net.http.HttpClient lives in the platform class loader and is therefore not visible from
    // the boot class path; its send/sendAsync calls are covered by the pre-check hooks instead.

    public static Socket SocketFactory_createSocket(javax.net.SocketFactory factory, String host, int port, int token, String source) throws IOException {
        checkNet(host + ":" + port, "javax.net.SocketFactory.createSocket", token, source);
        return factory.createSocket(host, port);
    }

    public static Socket SocketFactory_createSocket(javax.net.SocketFactory factory, java.net.InetAddress host, int port, int token, String source) throws IOException {
        checkNet(Targets.describeNetwork(host).host + ":" + port, "javax.net.SocketFactory.createSocket", token, source);
        return factory.createSocket(host, port);
    }

    /** Referenced by tests to ensure write-option classification stays consistent. */
    static boolean isWriteOption(OpenOption option) {
        return option == StandardOpenOption.WRITE || option == StandardOpenOption.APPEND
                || option == StandardOpenOption.CREATE || option == StandardOpenOption.CREATE_NEW
                || option == StandardOpenOption.TRUNCATE_EXISTING || option == StandardOpenOption.DELETE_ON_CLOSE;
    }
}
