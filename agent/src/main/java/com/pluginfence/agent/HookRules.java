package com.pluginfence.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.pluginfence.agent.HookRule.Shape.NOTIFY;
import static com.pluginfence.agent.HookRule.Shape.PRECHECK_TOP1;
import static com.pluginfence.agent.HookRule.Shape.PRECHECK_TOP2_BOTH;
import static com.pluginfence.agent.HookRule.Shape.PRECHECK_TOP2_SECOND;
import static com.pluginfence.agent.HookRule.precheck;
import static com.pluginfence.agent.HookRule.replaceStatic;
import static com.pluginfence.agent.HookRule.replaceVirtual;

/**
 * The interception surface: every JDK, Kotlin-stdlib and IntelliJ Platform call that PluginFence
 * rewrites inside third-party plugin bytecode.
 * <p>
 * This is intentionally a table rather than a generic "instrument the JDK" approach: only calls
 * that originate from plugin classes are touched, the platform itself is never rewritten, and the
 * list is small enough to audit. Anything not listed here is <b>not</b> intercepted - see
 * docs/THREAT_MODEL.md.
 */
final class HookRules {

    private static final String PATH = "Ljava/nio/file/Path;";
    private static final String FILE = "Ljava/io/File;";
    private static final String STR = "Ljava/lang/String;";
    private static final String CHARSET = "Ljava/nio/charset/Charset;";
    private static final String OPEN_OPTS = "[Ljava/nio/file/OpenOption;";
    private static final String COPY_OPTS = "[Ljava/nio/file/CopyOption;";
    private static final String GCL = "Lcom/intellij/execution/configurations/GeneralCommandLine;";
    private static final String VFILE = "Lcom/intellij/openapi/vfs/VirtualFile;";
    private static final String SOCKET_ADDRESS = "Ljava/net/SocketAddress;";

    private static final List<HookRule> RULES;
    private static final Map<String, HookRule> INDEX;

    static {
        List<HookRule> r = new ArrayList<>();

        // ---------------------------------------------------------------- java.nio.file.Files
        String files = "java/nio/file/Files";
        r.add(replaceStatic(files, "readString", "(" + PATH + ")" + STR, "Files_readString"));
        r.add(replaceStatic(files, "readString", "(" + PATH + CHARSET + ")" + STR, "Files_readString"));
        r.add(replaceStatic(files, "readAllBytes", "(" + PATH + ")[B", "Files_readAllBytes"));
        r.add(replaceStatic(files, "readAllLines", "(" + PATH + ")Ljava/util/List;", "Files_readAllLines"));
        r.add(replaceStatic(files, "readAllLines", "(" + PATH + CHARSET + ")Ljava/util/List;", "Files_readAllLines"));
        r.add(replaceStatic(files, "newInputStream", "(" + PATH + OPEN_OPTS + ")Ljava/io/InputStream;", "Files_newInputStream"));
        r.add(replaceStatic(files, "newBufferedReader", "(" + PATH + ")Ljava/io/BufferedReader;", "Files_newBufferedReader"));
        r.add(replaceStatic(files, "newBufferedReader", "(" + PATH + CHARSET + ")Ljava/io/BufferedReader;", "Files_newBufferedReader"));
        r.add(replaceStatic(files, "lines", "(" + PATH + ")Ljava/util/stream/Stream;", "Files_lines"));
        r.add(replaceStatic(files, "lines", "(" + PATH + CHARSET + ")Ljava/util/stream/Stream;", "Files_lines"));
        r.add(replaceStatic(files, "list", "(" + PATH + ")Ljava/util/stream/Stream;", "Files_list"));
        r.add(replaceStatic(files, "walk", "(" + PATH + "[Ljava/nio/file/FileVisitOption;)Ljava/util/stream/Stream;", "Files_walk"));
        r.add(replaceStatic(files, "newDirectoryStream", "(" + PATH + ")Ljava/nio/file/DirectoryStream;", "Files_newDirectoryStream"));
        r.add(replaceStatic(files, "copy", "(" + PATH + "Ljava/io/OutputStream;)J", "Files_copy"));
        r.add(replaceStatic(files, "copy", "(Ljava/io/InputStream;" + PATH + COPY_OPTS + ")J", "Files_copy"));
        r.add(replaceStatic(files, "copy", "(" + PATH + PATH + COPY_OPTS + ")" + PATH, "Files_copy"));
        r.add(replaceStatic(files, "move", "(" + PATH + PATH + COPY_OPTS + ")" + PATH, "Files_move"));
        r.add(replaceStatic(files, "delete", "(" + PATH + ")V", "Files_delete"));
        r.add(replaceStatic(files, "deleteIfExists", "(" + PATH + ")Z", "Files_deleteIfExists"));
        r.add(replaceStatic(files, "write", "(" + PATH + "[B" + OPEN_OPTS + ")" + PATH, "Files_write"));
        r.add(replaceStatic(files, "write", "(" + PATH + "Ljava/lang/Iterable;" + OPEN_OPTS + ")" + PATH, "Files_write"));
        r.add(replaceStatic(files, "write", "(" + PATH + "Ljava/lang/Iterable;" + CHARSET + OPEN_OPTS + ")" + PATH, "Files_write"));
        r.add(replaceStatic(files, "writeString", "(" + PATH + "Ljava/lang/CharSequence;" + OPEN_OPTS + ")" + PATH, "Files_writeString"));
        r.add(replaceStatic(files, "writeString", "(" + PATH + "Ljava/lang/CharSequence;" + CHARSET + OPEN_OPTS + ")" + PATH, "Files_writeString"));
        r.add(replaceStatic(files, "newOutputStream", "(" + PATH + OPEN_OPTS + ")Ljava/io/OutputStream;", "Files_newOutputStream"));
        r.add(replaceStatic(files, "newBufferedWriter", "(" + PATH + OPEN_OPTS + ")Ljava/io/BufferedWriter;", "Files_newBufferedWriter"));
        r.add(replaceStatic(files, "newBufferedWriter", "(" + PATH + CHARSET + OPEN_OPTS + ")Ljava/io/BufferedWriter;", "Files_newBufferedWriter"));

        // ---------------------------------------------------------------- java.io streams (constructors)
        r.add(precheck("java/io/FileInputStream", "<init>", "(" + FILE + ")V", PRECHECK_TOP1, "checkFileRead"));
        r.add(precheck("java/io/FileInputStream", "<init>", "(" + STR + ")V", PRECHECK_TOP1, "checkFileRead"));
        r.add(precheck("java/io/FileReader", "<init>", "(" + FILE + ")V", PRECHECK_TOP1, "checkFileRead"));
        r.add(precheck("java/io/FileReader", "<init>", "(" + STR + ")V", PRECHECK_TOP1, "checkFileRead"));
        r.add(precheck("java/io/FileReader", "<init>", "(" + FILE + CHARSET + ")V", PRECHECK_TOP2_SECOND, "checkFileRead"));
        r.add(precheck("java/io/RandomAccessFile", "<init>", "(" + FILE + STR + ")V", PRECHECK_TOP2_SECOND, "checkFileRead"));
        r.add(precheck("java/io/RandomAccessFile", "<init>", "(" + STR + STR + ")V", PRECHECK_TOP2_SECOND, "checkFileRead"));
        r.add(precheck("java/util/Scanner", "<init>", "(" + FILE + ")V", PRECHECK_TOP1, "checkFileRead"));
        r.add(precheck("java/util/Scanner", "<init>", "(" + PATH + ")V", PRECHECK_TOP1, "checkFileRead"));
        r.add(precheck("java/io/FileOutputStream", "<init>", "(" + FILE + ")V", PRECHECK_TOP1, "checkFileWrite"));
        r.add(precheck("java/io/FileOutputStream", "<init>", "(" + STR + ")V", PRECHECK_TOP1, "checkFileWrite"));
        r.add(precheck("java/io/FileOutputStream", "<init>", "(" + FILE + "Z)V", PRECHECK_TOP2_SECOND, "checkFileWrite"));
        r.add(precheck("java/io/FileOutputStream", "<init>", "(" + STR + "Z)V", PRECHECK_TOP2_SECOND, "checkFileWrite"));
        r.add(precheck("java/io/FileWriter", "<init>", "(" + FILE + ")V", PRECHECK_TOP1, "checkFileWrite"));
        r.add(precheck("java/io/FileWriter", "<init>", "(" + STR + ")V", PRECHECK_TOP1, "checkFileWrite"));
        r.add(precheck("java/io/FileWriter", "<init>", "(" + FILE + "Z)V", PRECHECK_TOP2_SECOND, "checkFileWrite"));
        r.add(precheck("java/io/FileWriter", "<init>", "(" + STR + "Z)V", PRECHECK_TOP2_SECOND, "checkFileWrite"));
        r.add(precheck("java/io/FileWriter", "<init>", "(" + FILE + CHARSET + ")V", PRECHECK_TOP2_SECOND, "checkFileWrite"));
        r.add(precheck("java/io/PrintWriter", "<init>", "(" + FILE + ")V", PRECHECK_TOP1, "checkFileWrite"));
        r.add(precheck("java/io/PrintWriter", "<init>", "(" + STR + ")V", PRECHECK_TOP1, "checkFileWrite"));
        r.add(precheck("java/io/PrintStream", "<init>", "(" + FILE + ")V", PRECHECK_TOP1, "checkFileWrite"));
        r.add(precheck("java/io/PrintStream", "<init>", "(" + STR + ")V", PRECHECK_TOP1, "checkFileWrite"));
        r.add(precheck("java/io/File", "delete", "()Z", PRECHECK_TOP1, "checkFileWrite"));
        r.add(precheck("java/io/File", "renameTo", "(" + FILE + ")Z", PRECHECK_TOP2_SECOND, "checkFileWrite"));
        r.add(precheck("java/io/File", "listFiles", "()[" + FILE, PRECHECK_TOP1, "checkFileRead"));
        r.add(precheck("java/io/File", "list", "()[" + STR, PRECHECK_TOP1, "checkFileRead"));

        // ---------------------------------------------------------------- kotlin.io
        String filesKt = "kotlin/io/FilesKt";
        r.add(replaceStatic(filesKt, "readText", "(" + FILE + CHARSET + ")" + STR, "FilesKt_readText"));
        r.add(replaceStatic(filesKt, "readBytes", "(" + FILE + ")[B", "FilesKt_readBytes"));
        r.add(replaceStatic(filesKt, "readLines", "(" + FILE + CHARSET + ")Ljava/util/List;", "FilesKt_readLines"));
        r.add(replaceStatic(filesKt, "forEachLine", "(" + FILE + CHARSET + "Lkotlin/jvm/functions/Function1;)V",
                "FilesKt_forEachLine", "(" + FILE + CHARSET + HookRule.OBJECT + "I" + STR + ")V"));
        r.add(replaceStatic(filesKt, "writeText", "(" + FILE + STR + CHARSET + ")V", "FilesKt_writeText"));
        r.add(replaceStatic(filesKt, "writeBytes", "(" + FILE + "[B)V", "FilesKt_writeBytes"));
        r.add(replaceStatic(filesKt, "appendText", "(" + FILE + STR + CHARSET + ")V", "FilesKt_appendText"));
        r.add(replaceStatic(filesKt, "appendBytes", "(" + FILE + "[B)V", "FilesKt_appendBytes"));
        r.add(replaceStatic(filesKt, "copyTo", "(" + FILE + FILE + "ZI)" + FILE, "FilesKt_copyTo"));
        r.add(replaceStatic(filesKt, "deleteRecursively", "(" + FILE + ")Z", "FilesKt_deleteRecursively"));
        r.add(replaceStatic("kotlin/io/ByteStreamsKt", "readBytes", "(Ljava/net/URL;)[B", "ByteStreamsKt_readBytes"));
        r.add(replaceStatic("kotlin/io/TextStreamsKt", "readText", "(Ljava/net/URL;" + CHARSET + ")" + STR, "TextStreamsKt_readText"));

        // ---------------------------------------------------------------- environment
        r.add(replaceStatic("java/lang/System", "getenv", "(" + STR + ")" + STR, "System_getenv"));
        r.add(replaceStatic("java/lang/System", "getenv", "()Ljava/util/Map;", "System_getenv"));
        r.add(precheck("com/intellij/util/EnvironmentUtil", "getValue", "(" + STR + ")" + STR, PRECHECK_TOP1, "checkEnvRead"));
        r.add(precheck("com/intellij/util/EnvironmentUtil", "getEnvironmentMap", "()Ljava/util/Map;", NOTIFY, "noteEnvEnumerate"));

        // ---------------------------------------------------------------- processes
        r.add(replaceVirtual("java/lang/ProcessBuilder", "start", "()Ljava/lang/Process;", "ProcessBuilder_start"));
        String rt = "java/lang/Runtime";
        r.add(replaceVirtual(rt, "exec", "(" + STR + ")Ljava/lang/Process;", "Runtime_exec"));
        r.add(replaceVirtual(rt, "exec", "([" + STR + ")Ljava/lang/Process;", "Runtime_exec"));
        r.add(replaceVirtual(rt, "exec", "(" + STR + "[" + STR + ")Ljava/lang/Process;", "Runtime_exec"));
        r.add(replaceVirtual(rt, "exec", "([" + STR + "[" + STR + ")Ljava/lang/Process;", "Runtime_exec"));
        r.add(replaceVirtual(rt, "exec", "(" + STR + "[" + STR + FILE + ")Ljava/lang/Process;", "Runtime_exec"));
        r.add(replaceVirtual(rt, "exec", "([" + STR + "[" + STR + FILE + ")Ljava/lang/Process;", "Runtime_exec"));
        String gcl = "com/intellij/execution/configurations/GeneralCommandLine";
        r.add(precheck(gcl, "createProcess", "()Ljava/lang/Process;", PRECHECK_TOP1, "checkProcess"));
        r.add(precheck(gcl, "toProcessBuilder", "()Ljava/lang/ProcessBuilder;", PRECHECK_TOP1, "checkProcess"));
        r.add(precheck("com/intellij/execution/process/OSProcessHandler", "<init>", "(" + GCL + ")V", PRECHECK_TOP1, "checkProcess"));
        r.add(precheck("com/intellij/execution/process/CapturingProcessHandler", "<init>", "(" + GCL + ")V", PRECHECK_TOP1, "checkProcess"));
        r.add(precheck("com/intellij/execution/process/KillableProcessHandler", "<init>", "(" + GCL + ")V", PRECHECK_TOP1, "checkProcess"));
        String execUtil = "com/intellij/execution/util/ExecUtil";
        r.add(precheck(execUtil, "execAndGetOutput", "(" + GCL + ")Lcom/intellij/execution/process/ProcessOutput;", PRECHECK_TOP1, "checkProcess"));
        r.add(precheck(execUtil, "execAndGetOutput", "(" + GCL + "I)Lcom/intellij/execution/process/ProcessOutput;", PRECHECK_TOP2_SECOND, "checkProcess"));
        r.add(precheck(execUtil, "execAndReadLine", "(" + GCL + ")" + STR, PRECHECK_TOP1, "checkProcess"));

        // ---------------------------------------------------------------- network
        String[] sockets = {"java/net/Socket", "javax/net/ssl/SSLSocket"};
        r.add(replaceVirtual(sockets, "connect", "(" + SOCKET_ADDRESS + ")V", "Socket_connect"));
        r.add(replaceVirtual(sockets, "connect", "(" + SOCKET_ADDRESS + "I)V", "Socket_connect"));
        r.add(precheck("java/net/Socket", "<init>", "(" + STR + "I)V", PRECHECK_TOP2_BOTH, "checkNetworkHostPort"));
        r.add(precheck("java/net/Socket", "<init>", "(Ljava/net/InetAddress;I)V", PRECHECK_TOP2_BOTH, "checkNetworkHostPort"));
        r.add(replaceVirtual("java/nio/channels/SocketChannel", "connect", "(" + SOCKET_ADDRESS + ")Z", "SocketChannel_connect"));
        r.add(replaceStatic("java/nio/channels/SocketChannel", "open", "(" + SOCKET_ADDRESS + ")Ljava/nio/channels/SocketChannel;", "SocketChannel_open"));
        r.add(replaceVirtual("java/net/URL", "openConnection", "()Ljava/net/URLConnection;", "URL_openConnection"));
        r.add(replaceVirtual("java/net/URL", "openConnection", "(Ljava/net/Proxy;)Ljava/net/URLConnection;", "URL_openConnection"));
        r.add(replaceVirtual("java/net/URL", "openStream", "()Ljava/io/InputStream;", "URL_openStream"));
        // java.net.http is not in java.base, so the boot-class-path hooks cannot reference it:
        // the request object (second from top) is handed to checkNetwork, which reads uri() reflectively.
        String http = "java/net/http/HttpClient";
        String bodyHandler = "Ljava/net/http/HttpResponse$BodyHandler;";
        r.add(precheck(http, "send", "(Ljava/net/http/HttpRequest;" + bodyHandler + ")Ljava/net/http/HttpResponse;", PRECHECK_TOP2_SECOND, "checkNetwork"));
        r.add(precheck(http, "sendAsync", "(Ljava/net/http/HttpRequest;" + bodyHandler + ")Ljava/util/concurrent/CompletableFuture;", PRECHECK_TOP2_SECOND, "checkNetwork"));
        String[] factories = {"javax/net/SocketFactory", "javax/net/ssl/SSLSocketFactory"};
        r.add(replaceVirtual(factories, "createSocket", "(" + STR + "I)Ljava/net/Socket;", "SocketFactory_createSocket"));
        r.add(replaceVirtual(factories, "createSocket", "(Ljava/net/InetAddress;I)Ljava/net/Socket;", "SocketFactory_createSocket"));
        String httpRequests = "com/intellij/util/io/HttpRequests";
        String requestBuilder = "Lcom/intellij/util/io/RequestBuilder;";
        r.add(precheck(httpRequests, "request", "(" + STR + ")" + requestBuilder, PRECHECK_TOP1, "checkNetwork"));
        r.add(precheck(httpRequests, "head", "(" + STR + ")" + requestBuilder, PRECHECK_TOP1, "checkNetwork"));
        r.add(precheck(httpRequests, "delete", "(" + STR + ")" + requestBuilder, PRECHECK_TOP1, "checkNetwork"));
        r.add(precheck(httpRequests, "post", "(" + STR + STR + ")" + requestBuilder, PRECHECK_TOP2_SECOND, "checkNetwork"));

        // ---------------------------------------------------------------- IntelliJ VFS / FileUtil
        String vf = "com/intellij/openapi/vfs/VirtualFile";
        r.add(precheck(vf, "contentsToByteArray", "()[B", PRECHECK_TOP1, "checkFileRead"));
        r.add(precheck(vf, "contentsToByteArray", "(Z)[B", PRECHECK_TOP2_SECOND, "checkFileRead"));
        r.add(precheck(vf, "getInputStream", "()Ljava/io/InputStream;", PRECHECK_TOP1, "checkFileRead"));
        r.add(precheck(vf, "getOutputStream", "(Ljava/lang/Object;)Ljava/io/OutputStream;", PRECHECK_TOP2_SECOND, "checkFileWrite"));
        r.add(precheck(vf, "setBinaryContent", "([B)V", PRECHECK_TOP2_SECOND, "checkFileWrite"));
        r.add(precheck(vf, "delete", "(Ljava/lang/Object;)V", PRECHECK_TOP2_SECOND, "checkFileWrite"));
        r.add(precheck("com/intellij/openapi/vfs/VfsUtilCore", "loadText", "(" + VFILE + ")" + STR, PRECHECK_TOP1, "checkFileRead"));
        r.add(precheck("com/intellij/openapi/vfs/VfsUtilCore", "loadBytes", "(" + VFILE + ")[B", PRECHECK_TOP1, "checkFileRead"));
        r.add(precheck("com/intellij/openapi/vfs/VfsUtil", "loadText", "(" + VFILE + ")" + STR, PRECHECK_TOP1, "checkFileRead"));
        r.add(precheck("com/intellij/openapi/fileEditor/FileDocumentManager", "getDocument",
                "(" + VFILE + ")Lcom/intellij/openapi/editor/Document;", PRECHECK_TOP2_SECOND, "checkFileRead"));
        String fileUtil = "com/intellij/openapi/util/io/FileUtil";
        r.add(precheck(fileUtil, "loadFile", "(" + FILE + ")" + STR, PRECHECK_TOP1, "checkFileRead"));
        r.add(precheck(fileUtil, "loadFile", "(" + FILE + STR + ")" + STR, PRECHECK_TOP2_SECOND, "checkFileRead"));
        r.add(precheck(fileUtil, "loadFile", "(" + FILE + CHARSET + ")" + STR, PRECHECK_TOP2_SECOND, "checkFileRead"));
        r.add(precheck(fileUtil, "loadFileBytes", "(" + FILE + ")[B", PRECHECK_TOP1, "checkFileRead"));
        r.add(precheck(fileUtil, "loadFileText", "(" + FILE + ")[C", PRECHECK_TOP1, "checkFileRead"));
        r.add(precheck(fileUtil, "loadLines", "(" + FILE + ")Ljava/util/List;", PRECHECK_TOP1, "checkFileRead"));
        r.add(precheck(fileUtil, "loadLines", "(" + STR + ")Ljava/util/List;", PRECHECK_TOP1, "checkFileRead"));
        r.add(precheck(fileUtil, "writeToFile", "(" + FILE + STR + ")V", PRECHECK_TOP2_SECOND, "checkFileWrite"));
        r.add(precheck(fileUtil, "writeToFile", "(" + FILE + "[B)V", PRECHECK_TOP2_SECOND, "checkFileWrite"));
        r.add(precheck(fileUtil, "delete", "(" + FILE + ")Z", PRECHECK_TOP1, "checkFileWrite"));
        r.add(precheck(fileUtil, "copy", "(" + FILE + FILE + ")V", PRECHECK_TOP2_SECOND, "checkFileRead"));

        RULES = Collections.unmodifiableList(r);
        Map<String, HookRule> index = new HashMap<>();
        for (HookRule rule : r) {
            for (String owner : rule.owners) {
                HookRule previous = index.put(rule.key(owner), rule);
                if (previous != null) {
                    throw new IllegalStateException("duplicate hook rule for " + rule.key(owner));
                }
            }
        }
        INDEX = Collections.unmodifiableMap(index);
    }

    private HookRules() {
    }

    static List<HookRule> all() {
        return RULES;
    }

    static HookRule find(String owner, String name, String descriptor) {
        return INDEX.get(owner + "|" + name + descriptor);
    }

    /** Fast pre-filter so most invocations skip the map lookup entirely. */
    static boolean mayMatchOwner(String owner) {
        return owner.startsWith("java/") || owner.startsWith("javax/net/") || owner.startsWith("kotlin/io/")
                || owner.startsWith("com/intellij/");
    }
}
