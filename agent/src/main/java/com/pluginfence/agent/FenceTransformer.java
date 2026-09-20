package com.pluginfence.agent;

import com.pluginfence.bootstrap.GuardLog;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Class-file transformer that rewrites sensitive call sites in third-party plugin classes.
 * <p>
 * Selection is by class loader: only classes defined by an IntelliJ {@code PluginClassLoader}
 * of a non-bundled plugin (or by a forced test package) are considered. Platform, JDK and
 * PluginFence classes are never modified. Any failure leaves the class untouched.
 */
final class FenceTransformer implements ClassFileTransformer {

    private final AgentConfig config;
    private final PluginLoaderRegistry registry;

    private final AtomicLong classesSeen = new AtomicLong();
    private final AtomicLong classesInstrumented = new AtomicLong();
    private final AtomicLong callSitesRewritten = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();

    private static final ThreadLocal<Boolean> IN_TRANSFORM = ThreadLocal.withInitial(() -> Boolean.FALSE);

    FenceTransformer(AgentConfig config, PluginLoaderRegistry registry) {
        this.config = config;
        this.registry = registry;
    }

    @Override
    public byte[] transform(Module module, ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        return transform(loader, className, classBeingRedefined, protectionDomain, classfileBuffer);
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (className == null || classfileBuffer == null || loader == null) {
            return null;
        }
        boolean forced = config.isForcedPackage(className);
        if (!forced && isNeverInstrumented(className)) {
            return null;
        }
        if (IN_TRANSFORM.get()) {
            return null; // class loading triggered by our own reflection; never recurse
        }
        IN_TRANSFORM.set(Boolean.TRUE);
        try {
            PluginLoaderRegistry.LoaderInfo info = forced
                    ? registry.forcedInfo(loader)
                    : registry.describe(loader);
            if (!info.instrument) {
                return null;
            }
            classesSeen.incrementAndGet();
            return rewrite(className, classfileBuffer, info.token);
        } catch (Throwable t) {
            failures.incrementAndGet();
            GuardLog.warn("transformation of " + className + " failed; class left unmodified", t);
            return null;
        } finally {
            IN_TRANSFORM.set(Boolean.FALSE);
        }
    }

    /** Pure bytecode rewriting; also used directly by unit tests. */
    byte[] rewrite(String className, byte[] classfileBuffer, int token) {
        ClassReader reader = new ClassReader(classfileBuffer);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        CallSiteRewriter rewriter = new CallSiteRewriter(writer, className, token);
        reader.accept(rewriter, 0);
        if (!rewriter.isModified()) {
            return null;
        }
        classesInstrumented.incrementAndGet();
        callSitesRewritten.addAndGet(rewriter.rewrittenCallSites);
        if (config.debug) {
            GuardLog.info("instrumented " + className + " (" + rewriter.rewrittenCallSites + " call sites, token " + token + ")");
        }
        return writer.toByteArray();
    }

    /**
     * Namespaces that are never rewritten regardless of loader: PluginFence itself, the JDK, ASM and
     * the Kotlin standard library (whose internals are the delegates our hooks call). Selection of
     * plugin code is otherwise purely loader-based - trust is never inferred from a package name.
     */
    static boolean isNeverInstrumented(String className) {
        return className.startsWith("com/pluginfence/")
                || className.startsWith("java/")
                || className.startsWith("javax/")
                || className.startsWith("jdk/")
                || className.startsWith("sun/")
                || className.startsWith("com/sun/")
                || className.startsWith("org/objectweb/asm/")
                || className.startsWith("kotlin/")
                || className.startsWith("kotlinx/");
    }

    long classesSeen() { return classesSeen.get(); }
    long classesInstrumented() { return classesInstrumented.get(); }
    long callSitesRewritten() { return callSitesRewritten.get(); }
    long failures() { return failures.get(); }
}
