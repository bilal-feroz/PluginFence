package com.pluginfence.agent;

import com.pluginfence.bootstrap.GuardHooks;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Type;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every rule must point at a real, public static hook with exactly the descriptor the rewriter emits. */
class HookRulesTest {

    @Test
    void everyRuleHasAMatchingHookMethod() {
        Set<String> hooks = new HashSet<>();
        for (Method m : GuardHooks.class.getDeclaredMethods()) {
            if (Modifier.isPublic(m.getModifiers()) && Modifier.isStatic(m.getModifiers())) {
                hooks.add(m.getName() + Type.getMethodDescriptor(m));
            }
        }
        for (HookRule rule : HookRules.all()) {
            String wanted = rule.hookName + rule.hookDescriptor;
            assertTrue(hooks.contains(wanted), "missing hook for " + rule + ": expected GuardHooks." + wanted);
        }
    }

    @Test
    void replacementHooksKeepTheOriginalReturnType() {
        for (HookRule rule : HookRules.all()) {
            if (rule.shape == HookRule.Shape.REPLACE) {
                String originalReturn = rule.descriptor.substring(rule.descriptor.lastIndexOf(')') + 1);
                String hookReturn = rule.hookDescriptor.substring(rule.hookDescriptor.lastIndexOf(')') + 1);
                assertEquals(originalReturn, hookReturn, "return type mismatch for " + rule);
            }
        }
    }

    @Test
    void lookupIsExactOnOwnerNameAndDescriptor() {
        assertNotNull(HookRules.find("java/nio/file/Files", "readString", "(Ljava/nio/file/Path;)Ljava/lang/String;"));
        assertNull(HookRules.find("java/nio/file/Files", "readString", "(Ljava/lang/String;)Ljava/lang/String;"));
        assertNull(HookRules.find("java/nio/file/Files", "exists", "(Ljava/nio/file/Path;[Ljava/nio/file/LinkOption;)Z"));
        assertNotNull(HookRules.find("javax/net/ssl/SSLSocket", "connect", "(Ljava/net/SocketAddress;)V"));
        assertTrue(HookRules.mayMatchOwner("java/nio/file/Files"));
        assertFalse(HookRules.mayMatchOwner("com/example/plugin/Helper"));
    }

    @Test
    void trailerInsertionBuildsExpectedDescriptors() {
        assertEquals("(Ljava/nio/file/Path;ILjava/lang/String;)Ljava/lang/String;",
                HookRule.insertTrailer("(Ljava/nio/file/Path;)Ljava/lang/String;", null));
        assertEquals("(Ljava/lang/ProcessBuilder;ILjava/lang/String;)Ljava/lang/Process;",
                HookRule.insertTrailer("()Ljava/lang/Process;", "java/lang/ProcessBuilder"));
    }
}
