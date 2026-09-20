package com.pluginfence.bootstrap;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Invokes the original implementation of a hooked method that lives outside the boot class path
 * (e.g. {@code kotlin.io.FilesKt}). The class is resolved through the class loader of the
 * attributed plugin so that the exact library version the plugin sees is used.
 */
final class Delegates {

    private static final Map<String, MethodHandle> CACHE = new ConcurrentHashMap<>();

    private Delegates() {
    }

    /**
     * @param token          identity token of the calling plugin (used to pick the class loader)
     * @param owner          binary name of the declaring class, e.g. {@code kotlin.io.FilesKt}
     * @param name           method name
     * @param returnType     binary name of the return type, or a primitive keyword
     * @param parameterTypes binary names of the parameter types
     */
    static Object invokeStatic(int token, String owner, String name, String returnType,
                               String[] parameterTypes, Object... args) throws Throwable {
        ClassLoader loader = loaderFor(token);
        String key = System.identityHashCode(loader) + "|" + owner + "." + name + String.join(",", parameterTypes);
        MethodHandle handle = CACHE.get(key);
        if (handle == null) {
            Class<?> ownerClass = Class.forName(owner, true, loader);
            Class<?>[] params = new Class<?>[parameterTypes.length];
            for (int i = 0; i < params.length; i++) {
                params[i] = resolve(parameterTypes[i], loader);
            }
            MethodType type = MethodType.methodType(resolve(returnType, loader), params);
            handle = MethodHandles.publicLookup().findStatic(ownerClass, name, type);
            CACHE.put(key, handle);
        }
        return handle.invokeWithArguments(args);
    }

    private static ClassLoader loaderFor(int token) {
        PluginIdentity identity = GuardBridge.identity(token);
        ClassLoader loader = identity.loader();
        if (loader == null) {
            loader = Thread.currentThread().getContextClassLoader();
        }
        if (loader == null) {
            loader = ClassLoader.getSystemClassLoader();
        }
        return loader;
    }

    private static Class<?> resolve(String name, ClassLoader loader) throws ClassNotFoundException {
        switch (name) {
            case "void": return void.class;
            case "boolean": return boolean.class;
            case "int": return int.class;
            case "long": return long.class;
            case "byte[]": return byte[].class;
            default: return Class.forName(name, false, loader);
        }
    }
}
