// SPDX-License-Identifier: MIT OR Apache-2.0
package ironwood.compiler.ir;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/** On-demand, closed-world collection effects. Never an ownership/borrowing exemption. */
public final class GcEffects {
    public record Effect(boolean mayCollect, boolean unknown) {
        private Effect union(Effect other) {
            return new Effect(mayCollect || other.mayCollect, unknown || other.unknown);
        }
    }

    private static final Effect LEAF = new Effect(false, false);
    private static final Effect COLLECT = new Effect(true, false);
    private static final Effect UNKNOWN = new Effect(true, true);
    private final IrProgram program;
    private final boolean runtimeOwners;
    private final Map<String, Effect> functions = new LinkedHashMap<>();
    private final Map<String, Effect> initializations = new LinkedHashMap<>();

    private GcEffects(IrProgram program, boolean runtimeOwners) {
        this.program = program;
        this.runtimeOwners = runtimeOwners;
        for (var function : program.functions()) {
            if (functions.putIfAbsent(function.linkageName(), LEAF) != null) {
                throw new IllegalArgumentException("duplicate function in GC effect graph");
            }
        }
        for (var initialization : program.typeInitializations()) {
            if (initializations.putIfAbsent(initialization.typeName(), LEAF) != null) {
                throw new IllegalArgumentException("duplicate initialization in GC effect graph");
            }
        }
        boolean changed;
        do {
            changed = false;
            for (var initialization : program.typeInitializations()) {
                Effect effect = LEAF;
                for (String type : initialization.prerequisiteTypes()) effect = effect.union(initialization(type));
                if (initialization.initializerLinkageName().isPresent()) {
                    effect = effect.union(function(initialization.initializerLinkageName().orElseThrow()));
                }
                changed |= !initializations.put(initialization.typeName(), effect).equals(effect);
            }
            for (var function : program.functions()) {
                Effect effect = function.blocks().isEmpty() ? UNKNOWN : LEAF;
                for (var block : function.blocks()) {
                    for (var instruction : operations(block)) effect = effect.union(instruction(instruction));
                    if (!runtimeOwners && block.terminator() instanceof IrThrowTerminator) effect = effect.union(UNKNOWN);
                }
                changed |= !functions.put(function.linkageName(), effect).equals(effect);
            }
        } while (changed);
    }

    public static GcEffects analyze(IrProgram program) { return new GcEffects(program, false); }
    /** Only the GC4 admitted runtime has noncollecting exception transport. */
    public static GcEffects analyzeRuntimeOwners(IrProgram program) { return new GcEffects(program, true); }
    public Effect function(String symbol) { return functions.getOrDefault(symbol, UNKNOWN); }
    public Effect initialization(String type) { return initializations.getOrDefault(type, UNKNOWN); }
    public Map<String, Effect> functions() { return Map.copyOf(functions); }

    public Effect instruction(IrInstruction instruction) {
        if (runtimeOwners && (instruction instanceof IrGcUnwindInstruction || instruction instanceof IrExceptionLandingPadInstruction
                || instruction instanceof IrExceptionCaughtInstruction || instruction instanceof IrAddSecondaryExceptionInstruction
                || instruction instanceof IrSecondaryExceptionAtInstruction || instruction instanceof IrSecondaryExceptionCountInstruction
                || instruction instanceof IrThrowableTraceInstruction trace
                && trace.operation() == IrThrowableTraceInstruction.Operation.CAPTURE)) return LEAF;
        if (instruction instanceof IrGcCollectInstruction) return COLLECT;
        if (instruction instanceof IrCallInstruction call) return function(call.targetLinkageName());
        if (instruction instanceof IrVirtualCallInstruction call) return dispatch(call.slot());
        if (instruction instanceof IrInterfaceCallInstruction call) return dispatch(call.slot());
        if (instruction instanceof IrEnsureTypeInitializedInstruction ensure) return initialization(ensure.typeName());
        // Allocation does not schedule collection. Constructors, failure handlers,
        // cleanup and initialization are separate CFG operations and graph edges.
        if (instruction instanceof IrGcRootSlotInstruction || instruction instanceof IrGcRootLoadInstruction
                || instruction instanceof IrGcProjectionInstruction || instruction instanceof IrGcReadProjectionInstruction
                || instruction instanceof IrGcTestExceptionInstruction
                || instruction instanceof IrAllocateInstruction || instruction instanceof IrArrayAllocateInstruction
                || instruction instanceof IrAllocationCountInstruction || instruction instanceof IrLiveAllocationCountInstruction
                || instruction instanceof IrArrayBoundsCheckInstruction || instruction instanceof IrArrayLengthCheckInstruction
                || instruction instanceof IrArrayLengthInstruction || instruction instanceof IrArrayLoadInstruction
                || instruction instanceof IrArrayStoreInstruction || instruction instanceof IrArrayTypeTestInstruction
                || instruction instanceof IrBinaryInstruction || instruction instanceof IrUnaryInstruction
                || instruction instanceof IrNumericConversionInstruction || instruction instanceof IrReferenceConversionInstruction
                || instruction instanceof IrFieldLoadInstruction || instruction instanceof IrFieldStoreInstruction
                || instruction instanceof IrStaticFieldLoadInstruction || instruction instanceof IrStaticFieldStoreInstruction
                || instruction instanceof IrPhiInstruction || instruction instanceof IrNullCheckInstruction
                || instruction instanceof IrTypeInitializedInstruction || instruction instanceof IrInstanceOfInstruction
                || instruction instanceof IrFloatingBitsInstruction || instruction instanceof IrMathUnaryInstruction
                || instruction instanceof IrMathBinaryInstruction || instruction instanceof IrCharacterInstruction
                || instruction instanceof IrIdentityHashCodeInstruction) return LEAF;
        // Native calls and every new operation require an explicit compiler review.
        // In particular foreign callbacks, destruction and exception transport are
        // not silently treated as leaves because their bodies are absent here.
        return UNKNOWN;
    }

    private Effect dispatch(IrDispatchSlot slot) {
        Set<String> targets = dispatchTargets(program, slot);
        Effect effect = targets.isEmpty() ? UNKNOWN : LEAF;
        for (String target : targets) effect = effect.union(function(target));
        return effect;
    }

    public static Set<String> dispatchTargets(IrProgram program, IrDispatchSlot slot) {
        Set<String> targets = new LinkedHashSet<>();
        Stream.concat(program.classes().stream().flatMap(type -> type.dispatchEntries().stream()),
                program.arrayTypes().stream().flatMap(type -> type.dispatchEntries().stream()))
                .filter(entry -> entry.slot().equals(slot)).forEach(entry -> targets.add(entry.targetLinkageName()));
        return Set.copyOf(targets);
    }

    public static List<IrInstruction> operations(IrBasicBlock block) {
        var operations = new ArrayList<>(block.instructions());
        if (block.terminator() instanceof IrInvokeTerminator invoke) operations.add(invoke.call());
        return List.copyOf(operations);
    }
}
