package com.etphonehome.pit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.pitest.bytecode.analysis.ClassTree;
import org.pitest.bytecode.analysis.MethodTree;
import org.pitest.mutationtest.build.InterceptorType;
import org.pitest.mutationtest.build.MutationInterceptor;
import org.pitest.mutationtest.engine.Mutater;
import org.pitest.mutationtest.engine.MutationDetails;

/**
 * Drops PIT mutants in the bytecode kotlinc generates for coroutines, which no
 * line of Kotlin source corresponds to (PIT's own FKOTLIN filter only drops
 * line-0 code; the Arcmutate Kotlin plugin that does this is commercial).
 * Only inside a coroutine state machine -- a suspend lambda's
 * {@code invokeSuspend(Object): Object} or a suspend function (last parameter
 * a {@code Continuation}, returns {@code Object}) -- it drops:
 *
 * <ul>
 *   <li>"removed call to kotlin/ResultKt::throwOnFailure": the compiler's
 *       resume check at the top of each state ({@code ResultKt.throwOnFailure}
 *       is internal to the stdlib, so source code can't call or omit it).</li>
 *   <li>"replaced return value with null" on a {@code return Unit.INSTANCE}:
 *       a Unit-typed coroutine body ({@code launch { }}) completes with a
 *       value nobody reads, so null vs Unit is equivalent.</li>
 * </ul>
 *
 * Everything else in coroutine bodies (conditions, arithmetic, calls the
 * source makes) is still mutated.
 */
public final class KotlinCoroutineFilter implements MutationInterceptor {
    private ClassTree current;

    @Override
    public InterceptorType type() {
        return InterceptorType.FILTER;
    }

    @Override
    public void begin(ClassTree clazz) {
        current = clazz;
    }

    @Override
    public Collection<MutationDetails> intercept(Collection<MutationDetails> mutations, Mutater m) {
        List<MutationDetails> keep = new ArrayList<>();
        for (MutationDetails d : mutations) {
            if (!isCoroutineArtifact(d)) {
                keep.add(d);
            }
        }
        return keep;
    }

    @Override
    public void end() {
        current = null;
    }

    private boolean isCoroutineArtifact(MutationDetails d) {
        Optional<MethodTree> found = current.method(d.getId().getLocation());
        if (!found.isPresent() || !isStateMachine(found.get().rawNode())) {
            return false;
        }
        MethodTree method = found.get();
        AbstractInsnNode insn = method.instruction(d.getInstructionIndex());
        if (insn instanceof MethodInsnNode) {
            MethodInsnNode call = (MethodInsnNode) insn;
            return call.getOpcode() == Opcodes.INVOKESTATIC
                && "kotlin/ResultKt".equals(call.owner)
                && "throwOnFailure".equals(call.name);
        }
        if (insn.getOpcode() == Opcodes.ARETURN && d.getMutator().endsWith("NullReturnValsMutator")) {
            AbstractInsnNode before = method.realInstructionBefore(d.getInstructionIndex());
            return before instanceof FieldInsnNode
                && before.getOpcode() == Opcodes.GETSTATIC
                && "kotlin/Unit".equals(((FieldInsnNode) before).owner)
                && "INSTANCE".equals(((FieldInsnNode) before).name);
        }
        return false;
    }

    private static boolean isStateMachine(MethodNode m) {
        if ("invokeSuspend".equals(m.name) && "(Ljava/lang/Object;)Ljava/lang/Object;".equals(m.desc)) {
            return true;
        }
        return m.desc.endsWith("Lkotlin/coroutines/Continuation;)Ljava/lang/Object;");
    }
}
