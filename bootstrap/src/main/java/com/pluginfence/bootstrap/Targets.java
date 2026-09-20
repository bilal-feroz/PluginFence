package com.pluginfence.bootstrap;

import java.io.File;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URL;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Converts the raw argument of an intercepted call into a normalized, redacted target
 * description. Handles JDK types directly and IntelliJ types (VirtualFile, GeneralCommandLine)
 * reflectively, because this class lives on the boot class path and cannot reference them.
 * <p>
 * Deliberately avoids filesystem I/O (no {@code toRealPath}, no DNS lookups).
 */
final class Targets {

    private static final Map<Class<?>, Method> PATH_ACCESSORS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Method> COMMAND_ACCESSORS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Method> URI_ACCESSORS = new ConcurrentHashMap<>();
    private static final Method MISSING;

    static {
        Method m;
        try {
            m = Targets.class.getDeclaredMethod("missing");
        } catch (NoSuchMethodException e) {
            m = null;
        }
        MISSING = m;
    }

    private Targets() {
    }

    @SuppressWarnings("unused")
    private static void missing() {
    }

    // --- files --------------------------------------------------------------------------------

    /** Returns an absolute, normalized path string for Path/File/String/VirtualFile-like objects. */
    static String describeFile(Object target) {
        if (target == null) return "";
        try {
            if (target instanceof Path) {
                return normalize((Path) target);
            }
            if (target instanceof File) {
                return normalize(((File) target).toPath());
            }
            if (target instanceof String) {
                String s = (String) target;
                try {
                    return normalize(Paths.get(s));
                } catch (InvalidPathException e) {
                    return s;
                }
            }
            if (target instanceof URI) {
                URI uri = (URI) target;
                if ("file".equalsIgnoreCase(uri.getScheme())) {
                    return normalize(Paths.get(uri));
                }
                return uri.toString();
            }
            String reflected = reflectString(target, PATH_ACCESSORS, "getPath");
            if (reflected != null) {
                try {
                    return normalize(Paths.get(reflected));
                } catch (InvalidPathException e) {
                    return reflected;
                }
            }
            return target.getClass().getName();
        } catch (Throwable t) {
            return String.valueOf(target);
        }
    }

    private static String normalize(Path path) {
        try {
            return path.toAbsolutePath().normalize().toString();
        } catch (Throwable t) {
            return path.toString();
        }
    }

    // --- processes ----------------------------------------------------------------------------

    /** Returns the sanitized command line: executable first, then arguments. */
    static List<String> describeCommand(Object target) {
        if (target == null) return Collections.emptyList();
        List<String> raw;
        try {
            if (target instanceof ProcessBuilder) {
                raw = ((ProcessBuilder) target).command();
            } else if (target instanceof List) {
                raw = new ArrayList<>();
                for (Object o : (List<?>) target) raw.add(String.valueOf(o));
            } else if (target instanceof String[]) {
                raw = Arrays.asList((String[]) target);
            } else if (target instanceof String) {
                // Runtime.exec(String) tokenizes on whitespace with StringTokenizer; mirror it.
                raw = new ArrayList<>();
                StringTokenizer st = new StringTokenizer((String) target);
                while (st.hasMoreTokens()) raw.add(st.nextToken());
            } else {
                raw = reflectCommandLine(target);
            }
        } catch (Throwable t) {
            raw = Collections.singletonList(String.valueOf(target));
        }
        return Redactor.sanitizeArguments(raw);
    }

    /** Short executable name, e.g. {@code powershell.exe} for {@code C:\...\powershell.exe}. */
    static String executableName(List<String> command) {
        if (command.isEmpty()) return "";
        String exe = command.get(0);
        int cut = Math.max(exe.lastIndexOf('/'), exe.lastIndexOf('\\'));
        return cut >= 0 ? exe.substring(cut + 1) : exe;
    }

    @SuppressWarnings("unchecked")
    private static List<String> reflectCommandLine(Object target) {
        // IntelliJ's GeneralCommandLine exposes getCommandLineList(@Nullable String exeName).
        Method m = COMMAND_ACCESSORS.computeIfAbsent(target.getClass(),
                c -> findAccessibleMethod(c, "getCommandLineList", String.class));
        if (m != null && m != MISSING) {
            try {
                Object result = m.invoke(target, (Object) null);
                if (result instanceof List) {
                    List<String> out = new ArrayList<>();
                    for (Object o : (List<Object>) result) out.add(String.valueOf(o));
                    return out;
                }
            } catch (Throwable ignored) {
                // fall through to toString
            }
        }
        return Collections.singletonList(String.valueOf(target));
    }

    // --- network ------------------------------------------------------------------------------

    /** Host, port and scheme for URL/URI/String/InetAddress/SocketAddress targets. */
    static NetworkTarget describeNetwork(Object target) {
        if (target == null) return NetworkTarget.EMPTY;
        try {
            if (target instanceof URL) {
                URL url = (URL) target;
                return NetworkTarget.of(url.getHost(), url.getPort(), url.getProtocol(), Redactor.redactUrl(url.toString()));
            }
            if (target instanceof URI) {
                URI uri = (URI) target;
                return NetworkTarget.of(uri.getHost(), uri.getPort(), uri.getScheme(), Redactor.redactUrl(uri.toString()));
            }
            if (target instanceof InetSocketAddress) {
                InetSocketAddress isa = (InetSocketAddress) target;
                // getHostString never triggers a reverse lookup.
                return NetworkTarget.of(isa.getHostString(), isa.getPort(), null, null);
            }
            if (target instanceof SocketAddress) {
                return NetworkTarget.of(target.toString(), -1, null, null);
            }
            if (target instanceof InetAddress) {
                InetAddress addr = (InetAddress) target;
                String literal = addr.getHostAddress();
                // InetAddress.toString() is "host/ip" and does not resolve; prefer a known host name.
                String s = addr.toString();
                int slash = s.indexOf('/');
                String host = slash > 0 ? s.substring(0, slash) : literal;
                return NetworkTarget.of(host, -1, null, null);
            }
            if (target instanceof String) {
                String s = ((String) target).trim();
                if (s.contains("://")) {
                    try {
                        URI uri = new URI(s);
                        return NetworkTarget.of(uri.getHost(), uri.getPort(), uri.getScheme(), Redactor.redactUrl(s));
                    } catch (Exception e) {
                        return NetworkTarget.of(Redactor.redactUrl(s), -1, null, Redactor.redactUrl(s));
                    }
                }
                int colon = s.lastIndexOf(':');
                if (colon > 0 && s.indexOf(':') == colon) {
                    try {
                        return NetworkTarget.of(s.substring(0, colon), Integer.parseInt(s.substring(colon + 1)), null, null);
                    } catch (NumberFormatException ignored) {
                        // not host:port
                    }
                }
                return NetworkTarget.of(s, -1, null, null);
            }
            // java.net.http.HttpRequest (not visible from the boot class path) exposes uri().
            String uri = reflectString(target, URI_ACCESSORS, "uri");
            if (uri != null) {
                return describeNetwork(new URI(uri));
            }
            return NetworkTarget.of(String.valueOf(target), -1, null, null);
        } catch (Throwable t) {
            return NetworkTarget.of(String.valueOf(target), -1, null, null);
        }
    }

    static final class NetworkTarget {
        static final NetworkTarget EMPTY = new NetworkTarget("", -1, "", "");

        final String host;
        final int port;
        final String scheme;
        final String display;

        private NetworkTarget(String host, int port, String scheme, String display) {
            this.host = host;
            this.port = port;
            this.scheme = scheme;
            this.display = display;
        }

        static NetworkTarget of(String host, int port, String scheme, String redactedUrl) {
            String h = host == null ? "" : host;
            String s = scheme == null ? "" : scheme.toLowerCase();
            int p = port;
            if (p < 0 && !s.isEmpty()) {
                if (s.equals("https") || s.equals("wss")) p = 443;
                else if (s.equals("http") || s.equals("ws")) p = 80;
            }
            String display = p > 0 ? h + ":" + p : h;
            if (redactedUrl != null && !redactedUrl.isEmpty() && h.isEmpty()) {
                display = redactedUrl;
            }
            return new NetworkTarget(h, p, s, display);
        }

        Map<String, String> metadata(String kind, String redactedUrl) {
            Map<String, String> meta = new LinkedHashMap<>();
            meta.put(SecurityRequest.META_KIND, kind);
            if (!host.isEmpty()) meta.put(SecurityRequest.META_HOST, host);
            if (port > 0) meta.put(SecurityRequest.META_PORT, Integer.toString(port));
            if (!scheme.isEmpty()) meta.put(SecurityRequest.META_SCHEME, scheme);
            if (redactedUrl != null && !redactedUrl.isEmpty()) meta.put("url", redactedUrl);
            return meta;
        }
    }

    // --- reflection helper ----------------------------------------------------------------------

    /**
     * Finds a public method through a public, exported declaring type so that it can be invoked
     * without {@code setAccessible} (which JPMS forbids for internal JDK implementation classes
     * such as {@code jdk.internal.net.http.HttpRequestImpl}).
     */
    static Method findAccessibleMethod(Class<?> type, String name, Class<?>... params) {
        java.util.ArrayDeque<Class<?>> queue = new java.util.ArrayDeque<>();
        queue.add(type);
        while (!queue.isEmpty()) {
            Class<?> c = queue.poll();
            if (java.lang.reflect.Modifier.isPublic(c.getModifiers()) && c.getModule().isExported(packageOf(c))) {
                try {
                    Method found = c.getMethod(name, params);
                    if (java.lang.reflect.Modifier.isPublic(found.getDeclaringClass().getModifiers())
                            && found.getDeclaringClass().getModule().isExported(packageOf(found.getDeclaringClass()))) {
                        return found;
                    }
                } catch (NoSuchMethodException ignored) {
                    // keep searching supertypes
                }
            }
            if (c.getSuperclass() != null) queue.add(c.getSuperclass());
            queue.addAll(Arrays.asList(c.getInterfaces()));
        }
        // Last resort for classpath (unnamed module) types that are not public.
        try {
            Method found = type.getMethod(name, params);
            found.setAccessible(true);
            return found;
        } catch (Throwable t) {
            return MISSING;
        }
    }

    private static String packageOf(Class<?> c) {
        String n = c.getName();
        int dot = n.lastIndexOf('.');
        return dot < 0 ? "" : n.substring(0, dot);
    }

    private static String reflectString(Object target, Map<Class<?>, Method> cache, String methodName) {
        Method m = cache.computeIfAbsent(target.getClass(), c -> findAccessibleMethod(c, methodName));
        if (m == null || m == MISSING) return null;
        try {
            Object v = m.invoke(target);
            return v == null ? null : v.toString();
        } catch (Throwable t) {
            return null;
        }
    }
}
