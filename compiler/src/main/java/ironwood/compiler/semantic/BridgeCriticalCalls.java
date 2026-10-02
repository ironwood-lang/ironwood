// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.ir.*;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Selects native functions that may run without a JVM thread-state transition.
 * A critical downcall cannot enter Java, and every JVM safepoint waits until it
 * returns. Its complete closure must therefore contain only resolved native calls
 * and memory-only operations. Foreign, unresolved and unclassified effects keep
 * the registered JNI transport. This optional selection never admits an export
 * and never relaxes a lifetime, ownership or exception proof.
 */
public final class BridgeCriticalCalls {
    private final IrProgram program;
    private final Map<String, String> refusals;

    private BridgeCriticalCalls(IrProgram program, Map<String, String> refusals) {
        this.program = program;
        this.refusals = Map.copyOf(refusals);
    }

    public boolean matches(IrProgram candidate) { return program.equals(candidate); }

    /** Empty when the named function's complete closure is admitted. */
    public Optional<String> refusal(String linkageName) {
        if (program.functions().stream().noneMatch(function -> function.linkageName().equals(linkageName))) {
            return Optional.of("unknown native function: " + linkageName);
        }
        return Optional.ofNullable(refusals.get(linkageName));
    }

    public static BridgeCriticalCalls analyze(IrProgram program) {
        var targets = new BridgeCallTargets(program);
        Map<String, String> refusals = new LinkedHashMap<>();
        Map<String, Set<String>> edges = new LinkedHashMap<>();
        for (var function : program.functions()) {
            if (edges.containsKey(function.linkageName())) {
                throw new IllegalArgumentException("critical call selection requires unique native symbols");
            }
            var callees = new LinkedHashSet<String>();
            String local = null;
            for (var block : function.blocks()) {
                for (var instruction : block.instructions()) {
                    if (local == null) local = scan(targets, function, instruction, callees);
                }
                if (local == null && block.terminator() instanceof IrInvokeTerminator invoke) {
                    local = scan(targets, function, invoke.call(), callees);
                }
            }
            if (local != null) refusals.put(function.linkageName(), local);
            edges.put(function.linkageName(), Set.copyOf(callees));
        }
        boolean changed;
        do {
            changed = false;
            for (var edge : edges.entrySet()) {
                if (refusals.containsKey(edge.getKey())) continue;
                for (String callee : edge.getValue()) {
                    String reason = refusals.get(callee);
                    if (reason == null) continue;
                    refusals.put(edge.getKey(), reason);
                    changed = true;
                    break;
                }
            }
        } while (changed);
        return new BridgeCriticalCalls(program, refusals);
    }

    private static String scan(BridgeCallTargets targets, IrFunction function,
                               IrInstruction instruction, Set<String> callees) {
        String site = instruction.getClass().getSimpleName() + " in " + function.linkageName()
                + ":" + instruction.sourceSpan().start().line();
        if (instruction instanceof IrForeignCallInstruction) return "Java callback: " + site;
        var call = targets.resolve(instruction);
        if (instruction instanceof IrFreeInstruction free) call = targets.cleanup(free.allocation(), false);
        else if (instruction instanceof IrRollbackInstruction rollback) call = targets.cleanup(rollback.allocation(), true);
        else if (instruction instanceof IrDestroyArrayElementsInstruction elements) {
            IrType element = elements.array().type().elementType();
            if (!element.isReference()) return null;
            call = targets.cleanup(new IrNull(element, elements.sourceSpan()), false);
        }
        if (call != null) {
            if (!call.complete()) return "unresolved call: " + site;
            call.targets().forEach(target -> callees.add(target.linkageName()));
            return null;
        }
        return memoryOnly(instruction) ? null : "operation outside the critical call subset: " + site;
    }

    /**
     * Operations that only compute, read or write process memory, allocate, or
     * unwind. Console, file, stream, socket, TLS, clock, environment, process and
     * every other runtime service stay outside this list, as do String operations
     * whose lowering has not been audited for dispatch.
     */
    private static boolean memoryOnly(IrInstruction instruction) {
        return switch (instruction) {
            case IrAllocateInstruction ignored -> true;
            case IrArrayAllocateInstruction ignored -> true;
            case IrAllocationCountInstruction ignored -> true;
            case IrLiveAllocationCountInstruction ignored -> true;
            case IrArrayBoundsCheckInstruction ignored -> true;
            case IrArrayLengthCheckInstruction ignored -> true;
            case IrArrayLengthInstruction ignored -> true;
            case IrArrayLoadInstruction ignored -> true;
            case IrArrayStoreInstruction ignored -> true;
            case IrArrayTypeTestInstruction ignored -> true;
            case IrFieldLoadInstruction ignored -> true;
            case IrFieldStoreInstruction ignored -> true;
            case IrStaticFieldLoadInstruction ignored -> true;
            case IrStaticFieldStoreInstruction ignored -> true;
            case IrBinaryInstruction ignored -> true;
            case IrUnaryInstruction ignored -> true;
            case IrNumericConversionInstruction ignored -> true;
            case IrReferenceConversionInstruction ignored -> true;
            case IrPhiInstruction ignored -> true;
            case IrNullCheckInstruction ignored -> true;
            case IrInstanceOfInstruction ignored -> true;
            case IrTypeInitializedInstruction ignored -> true;
            case IrIdentityHashCodeInstruction ignored -> true;
            case IrMathUnaryInstruction ignored -> true;
            case IrMathBinaryInstruction ignored -> true;
            case IrCharacterInstruction ignored -> true;
            case IrStringCharAtInstruction ignored -> true;
            case IrFloatingBitsInstruction ignored -> true;
            case IrExceptionLandingPadInstruction ignored -> true;
            case IrExceptionCaughtInstruction ignored -> true;
            case IrAddSecondaryExceptionInstruction ignored -> true;
            case IrRawDeallocateInstruction ignored -> true;
            case IrBridgeResultStoreInstruction ignored -> true;
            case IrBridgeFailureSnapshotInstruction ignored -> true;
            case IrThrowableTraceInstruction trace -> trace.operation() == IrThrowableTraceInstruction.Operation.CAPTURE
                    || trace.operation() == IrThrowableTraceInstruction.Operation.RELEASE
                    || trace.operation() == IrThrowableTraceInstruction.Operation.COMMON;
            default -> false;
        };
    }
}
