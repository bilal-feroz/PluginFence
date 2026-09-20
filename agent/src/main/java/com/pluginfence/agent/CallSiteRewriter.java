package com.pluginfence.agent;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * ASM visitor that rewrites matching call sites inside one plugin class.
 * <p>
 * Design notes (why this is safe to run inside a class-load hook):
 * <ul>
 *   <li>Only invocation instructions are touched; no new branches, labels or locals are introduced,
 *       so the existing StackMapTable frames stay valid. Max stack is recomputed by
 *       {@code ClassWriter.COMPUTE_MAXS}, which never needs to load classes
 *       (unlike {@code COMPUTE_FRAMES}).</li>
 *   <li>Replacement hooks have exactly the original operand layout (receiver + arguments) plus two
 *       pushed constants, so the surrounding bytecode is unaffected.</li>
 *   <li>Pre-check hooks copy the argument they need with {@code dup}/{@code dup2}/{@code swap} on
 *       category-1 values only and leave the stack exactly as they found it.</li>
 * </ul>
 */
final class CallSiteRewriter extends ClassVisitor {

    private final String sourceClass;
    private final int token;
    int rewrittenCallSites;

    CallSiteRewriter(ClassVisitor next, String internalClassName, int token) {
        super(Opcodes.ASM9, next);
        this.sourceClass = internalClassName.replace('/', '.');
        this.token = token;
    }

    boolean isModified() {
        return rewrittenCallSites > 0;
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
        MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
        if (mv == null || (access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
            return mv;
        }
        return new MethodRewriter(mv);
    }

    private final class MethodRewriter extends MethodVisitor {

        MethodRewriter(MethodVisitor mv) {
            super(Opcodes.ASM9, mv);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            HookRule rule = HookRules.mayMatchOwner(owner) ? HookRules.find(owner, name, descriptor) : null;
            if (rule == null) {
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                return;
            }
            rewrittenCallSites++;
            switch (rule.shape) {
                case REPLACE:
                    pushIdentity();
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, HookRule.HOOKS_CLASS, rule.hookName, rule.hookDescriptor, false);
                    return;
                case PRECHECK_TOP1:
                    super.visitInsn(Opcodes.DUP);
                    pushIdentity();
                    super.visitLdcInsn(rule.api);
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, HookRule.HOOKS_CLASS, rule.hookName, rule.hookDescriptor, false);
                    break;
                case PRECHECK_TOP2_SECOND:
                    super.visitInsn(Opcodes.SWAP);
                    super.visitInsn(Opcodes.DUP);
                    pushIdentity();
                    super.visitLdcInsn(rule.api);
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, HookRule.HOOKS_CLASS, rule.hookName, rule.hookDescriptor, false);
                    super.visitInsn(Opcodes.SWAP);
                    break;
                case PRECHECK_TOP2_BOTH:
                    super.visitInsn(Opcodes.DUP2);
                    pushIdentity();
                    super.visitLdcInsn(rule.api);
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, HookRule.HOOKS_CLASS, rule.hookName, rule.hookDescriptor, false);
                    break;
                case NOTIFY:
                    pushIdentity();
                    super.visitLdcInsn(rule.api);
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, HookRule.HOOKS_CLASS, rule.hookName, rule.hookDescriptor, false);
                    break;
                default:
                    throw new IllegalStateException("unknown shape " + rule.shape);
            }
            // Pre-check shapes keep the original instruction.
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        }

        /** Pushes {@code (int token, String sourceClass)}. */
        private void pushIdentity() {
            if (token >= Byte.MIN_VALUE && token <= Byte.MAX_VALUE) {
                super.visitIntInsn(Opcodes.BIPUSH, token);
            } else if (token >= Short.MIN_VALUE && token <= Short.MAX_VALUE) {
                super.visitIntInsn(Opcodes.SIPUSH, token);
            } else {
                super.visitLdcInsn(token);
            }
            super.visitLdcInsn(sourceClass);
        }
    }
}
