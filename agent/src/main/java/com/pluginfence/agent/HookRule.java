package com.pluginfence.agent;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * One call-site rewriting rule: which method invocation to match in plugin bytecode and how to
 * route it through {@code com.pluginfence.bootstrap.GuardHooks}.
 */
final class HookRule {

    static final String HOOKS_CLASS = "com/pluginfence/bootstrap/GuardHooks";
    static final String OBJECT = "Ljava/lang/Object;";
    static final String STRING = "Ljava/lang/String;";

    enum Shape {
        /**
         * The invocation is replaced by an {@code invokestatic} of a hook with the same descriptor
         * (receiver first for instance calls) plus {@code (int token, String sourceClass)}.
         * The hook performs the original operation itself.
         */
        REPLACE,
        /** {@code dup} the top-of-stack argument and pass it to a {@code check*} hook; original call stays. */
        PRECHECK_TOP1,
        /** Like TOP1 but the relevant argument is second from the top (both category-1 values). */
        PRECHECK_TOP2_SECOND,
        /** {@code dup2} both top values (e.g. host + port) into a two-argument check hook. */
        PRECHECK_TOP2_BOTH,
        /** No argument copied; the hook only records that the API was used. */
        NOTIFY
    }

    final List<String> owners;
    final String name;
    final String descriptor;
    final Shape shape;
    final String hookName;
    final String hookDescriptor;
    final String api;
    final boolean isStatic;

    private HookRule(List<String> owners, String name, String descriptor, Shape shape,
                     String hookName, String hookDescriptor, boolean isStatic) {
        this.owners = owners;
        this.name = name;
        this.descriptor = descriptor;
        this.shape = shape;
        this.hookName = hookName;
        this.hookDescriptor = hookDescriptor;
        this.isStatic = isStatic;
        String ownerDots = owners.get(0).replace('/', '.');
        this.api = "<init>".equals(name) ? "new " + ownerDots : ownerDots + "." + name;
    }

    String canonicalOwner() {
        return owners.get(0);
    }

    String key(String owner) {
        return owner + "|" + name + descriptor;
    }

    // --- factories --------------------------------------------------------------------------

    /** Replacement hook for a static method; hook descriptor derived from the original. */
    static HookRule replaceStatic(String owner, String name, String descriptor, String hookName) {
        return new HookRule(Collections.singletonList(owner), name, descriptor, Shape.REPLACE,
                hookName, insertTrailer(descriptor, null), true);
    }

    /** Replacement hook for a static method with an explicit hook descriptor (non-JDK parameter types). */
    static HookRule replaceStatic(String owner, String name, String descriptor, String hookName, String hookDescriptor) {
        return new HookRule(Collections.singletonList(owner), name, descriptor, Shape.REPLACE, hookName, hookDescriptor, true);
    }

    /** Replacement hook for an instance method; receiver becomes the first hook parameter. */
    static HookRule replaceVirtual(String[] owners, String name, String descriptor, String hookName) {
        return new HookRule(Arrays.asList(owners), name, descriptor, Shape.REPLACE,
                hookName, insertTrailer(descriptor, owners[0]), false);
    }

    static HookRule replaceVirtual(String owner, String name, String descriptor, String hookName) {
        return replaceVirtual(new String[]{owner}, name, descriptor, hookName);
    }

    static HookRule precheck(String owner, String name, String descriptor, Shape shape, String hookName) {
        String hookDesc;
        switch (shape) {
            case PRECHECK_TOP1:
            case PRECHECK_TOP2_SECOND:
                hookDesc = "(" + OBJECT + "I" + STRING + STRING + ")V";
                break;
            case PRECHECK_TOP2_BOTH:
                hookDesc = "(" + OBJECT + "II" + STRING + STRING + ")V";
                break;
            case NOTIFY:
                hookDesc = "(I" + STRING + STRING + ")V";
                break;
            default:
                throw new IllegalArgumentException("not a pre-check shape: " + shape);
        }
        boolean isStatic = descriptor.startsWith("()") && !"<init>".equals(name) && shape == Shape.NOTIFY;
        return new HookRule(Collections.singletonList(owner), name, descriptor, shape, hookName, hookDesc, isStatic);
    }

    /** Builds {@code (Receiver?, originalParams..., int, String)ret} from an original descriptor. */
    static String insertTrailer(String descriptor, String receiverOwner) {
        int close = descriptor.lastIndexOf(')');
        String params = descriptor.substring(1, close);
        String ret = descriptor.substring(close + 1);
        String receiver = receiverOwner == null ? "" : "L" + receiverOwner + ";";
        return "(" + receiver + params + "I" + STRING + ")" + ret;
    }

    @Override
    public String toString() {
        return owners.get(0) + "." + name + descriptor + " -> " + shape + " " + hookName;
    }
}
