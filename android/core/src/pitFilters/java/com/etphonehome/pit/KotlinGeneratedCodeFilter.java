package com.etphonehome.pit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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
 * Drops PIT mutants in bytecode kotlinc generates on its own, which no edit of
 * the Kotlin source can produce (PIT's FKOTLIN filter only drops line-0 code;
 * the Arcmutate Kotlin plugin that does this is commercial):
 *
 * <ul>
 *   <li>Removed calls to the null checks kotlinc inserts --
 *       {@code Intrinsics.checkNotNull*} / {@code checkParameterIsNotNull} /
 *       {@code checkExpressionValueIsNotNull} on platform-type values,
 *       parameters and smart casts. Without them a null still fails, a few
 *       instructions later with a plain NPE; the code under test never sees a
 *       difference.</li>
 *   <li>Inside a coroutine state machine (a suspend lambda's
 *       {@code invokeSuspend(Object): Object}, or a suspend function: last
 *       parameter a {@code Continuation}, returns {@code Object}):
 *       <ul>
 *         <li>removed {@code ResultKt.throwOnFailure}, the resume check at the
 *             top of each state (stdlib-internal, source can't call it);</li>
 *         <li>"replaced return value with null" on {@code return Unit.INSTANCE}:
 *             a Unit coroutine body ({@code launch { }}) completes with a value
 *             nobody reads.</li>
 *       </ul></li>
 * </ul>
 *
 * Everything else, coroutine bodies included (conditions, arithmetic, the
 * calls the source makes), is still mutated.
 */
public final class KotlinGeneratedCodeFilter implements MutationInterceptor {
    private static final Set<String> NULL_CHECKS = Set.of(
        "checkNotNull", "checkNotNullParameter", "checkNotNullExpressionValue",
        "checkParameterIsNotNull", "checkExpressionValueIsNotNull");

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
            if (!isGenerated(d)) {
                keep.add(d);
            }
        }
        return keep;
    }

    @Override
    public void end() {
        current = null;
    }

    private boolean isGenerated(MutationDetails d) {
        Optional<MethodTree> found = current.method(d.getId().getLocation());
        if (!found.isPresent()) {
            return false;
        }
        MethodTree method = found.get();
        AbstractInsnNode insn = method.instruction(d.getInstructionIndex());
        if (insn instanceof MethodInsnNode && insn.getOpcode() == Opcodes.INVOKESTATIC) {
            MethodInsnNode call = (MethodInsnNode) insn;
            if ("kotlin/jvm/internal/Intrinsics".equals(call.owner) && NULL_CHECKS.contains(call.name)) {
                return true;
            }
            return isStateMachine(method.rawNode())
                && "kotlin/ResultKt".equals(call.owner) && "throwOnFailure".equals(call.name);
        }
        if (insn.getOpcode() == Opcodes.ARETURN && d.getMutator().endsWith("NullReturnValsMutator")
            && isStateMachine(method.rawNode())) {
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
