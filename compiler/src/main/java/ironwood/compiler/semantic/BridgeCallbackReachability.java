// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.bridge.BridgeCallableId;
import ironwood.compiler.bridge.BridgeRootSet;
import ironwood.compiler.ir.*;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Callback context planning, not an export or lifetime proof. Unknown edges
 * require context and block a complete plan; they never establish callback freedom.
 */
public final class BridgeCallbackReachability {
    public record Effects(boolean foreign, Set<String> unknown) {
        public Effects { unknown = Set.copyOf(unknown); }
        public boolean complete() { return unknown.isEmpty(); }
        public boolean requiresContext() { return foreign || !complete(); }

        private Effects merge(Effects other) {
            var unresolved = new LinkedHashSet<>(unknown);
            unresolved.addAll(other.unknown);
            return new Effects(foreign || other.foreign, unresolved);
        }
    }

    private static final Effects NONE = new Effects(false, Set.of());
    private static final Effects FOREIGN = new Effects(true, Set.of());
    private final IrProgram program;
    private final Map<BridgeCallableId, Effects> functions;
    private final BridgeCallTargets targets;

    private BridgeCallbackReachability(IrProgram program, Map<BridgeCallableId, Effects> functions) {
        this.program = program;
        this.functions = Map.copyOf(functions);
        targets = new BridgeCallTargets(program);
    }

    public boolean matches(IrProgram candidate) { return program.equals(candidate); }
    public Map<BridgeCallableId, Effects> functions() { return functions; }

    /** Entry initialization can reach Java even when the target body cannot. */
    public Map<BridgeCallableId, Effects> entries(BridgeRootSet requested) {
        var checked = requested.revalidate(program);
        if (!checked.resolved()) throw new IllegalArgumentException("callback planning requires resolved roots");
        Map<BridgeCallableId, Effects> entries = new LinkedHashMap<>();
        for (var root : checked.roots()) {
            var id = root.callable();
            Effects effect = functions.get(id);
            var initialization = targets.initializers(id.owner());
            if (!initialization.complete()) effect = effect.merge(unknown("entry initialization: " + id.owner()));
            for (var initializer : initialization.targets()) {
                effect = effect.merge(functions.get(BridgeCallableId.of(initializer)));
            }
            if (id.kind() == IrCallableKind.CONSTRUCTOR && !id.parameters().isEmpty()) {
                var rollback = targets.cleanup(new IrNull(id.parameters().getFirst(), root.span()), true);
                if (!rollback.complete()) effect = effect.merge(unknown("entry rollback: " + id.linkage()));
                for (var cleanup : rollback.targets()) effect = effect.merge(functions.get(BridgeCallableId.of(cleanup)));
            }
            entries.put(id, effect);
        }
        return Map.copyOf(entries);
    }

    public static BridgeCallbackReachability analyze(IrProgram program) {
        var targets = new BridgeCallTargets(program);
        Map<String, IrFunction> functions = new LinkedHashMap<>();
        Map<String, Effects> effects = new LinkedHashMap<>();
        Map<String, Set<String>> edges = new LinkedHashMap<>();
        for (var function : program.functions()) {
            if (functions.putIfAbsent(function.linkageName(), function) != null) {
                throw new IllegalArgumentException("callback planning requires unique native symbols");
            }
            Effects local = NONE;
            var callees = new LinkedHashSet<String>();
            for (var block : function.blocks()) {
                for (var instruction : block.instructions()) {
                    local = local.merge(scan(targets, function, instruction, callees));
                }
                if (block.terminator() instanceof IrInvokeTerminator invoke) {
                    local = local.merge(scan(targets, function, invoke.call(), callees));
                }
            }
            effects.put(function.linkageName(), local);
            edges.put(function.linkageName(), Set.copyOf(callees));
        }
        boolean changed;
        do {
            changed = false;
            for (var edge : edges.entrySet()) {
                Effects previous = effects.get(edge.getKey());
                Effects next = previous;
                for (String callee : edge.getValue()) next = next.merge(effects.get(callee));
                if (!previous.equals(next)) {
                    effects.put(edge.getKey(), next);
                    changed = true;
                }
            }
        } while (changed);
        Map<BridgeCallableId, Effects> result = new LinkedHashMap<>();
        functions.forEach((name, function) -> result.put(BridgeCallableId.of(function), effects.get(name)));
        return new BridgeCallbackReachability(program, result);
    }

    private static Effects scan(BridgeCallTargets targets, IrFunction function,
                                IrInstruction instruction, Set<String> callees) {
        if (instruction instanceof IrForeignCallInstruction) return FOREIGN;
        var call = targets.resolve(instruction);
        if (instruction instanceof IrFreeInstruction free) call = targets.cleanup(free.allocation(), false);
        else if (instruction instanceof IrRollbackInstruction rollback) call = targets.cleanup(rollback.allocation(), true);
        else if (instruction instanceof IrDestroyArrayElementsInstruction elements) {
            IrType element = elements.array().type().elementType();
            if (!element.isReference()) return NONE;
            call = targets.cleanup(new IrNull(element, elements.sourceSpan()), false);
        }
        String site = function.linkageName() + ":" + instruction.sourceSpan().start().line()
                + ":" + instruction.getClass().getSimpleName();
        if (call != null) {
            call.targets().forEach(target -> callees.add(target.linkageName()));
            return call.complete() ? NONE : unknown("unresolved callback edge: " + site);
        }
        return fixedNative(instruction) ? NONE : unknown("unclassified callback effect: " + site);
    }

    private static Effects unknown(String reason) { return new Effects(false, Set.of(reason)); }

    /** Only fixed native operations, without producer dispatch or cleanup. */
    private static boolean fixedNative(IrInstruction instruction) {
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
            case IrFloatingBitsInstruction ignored -> true;
            case IrExceptionLandingPadInstruction ignored -> true;
            case IrExceptionCaughtInstruction ignored -> true;
            case IrRawDeallocateInstruction ignored -> true;
            case IrBridgeResultStoreInstruction ignored -> true;
            case IrBridgeSlotStoreInstruction ignored -> true;
            case IrBridgeStringCopyInstruction ignored -> true;
            case IrThrowableTraceInstruction trace -> trace.operation() == IrThrowableTraceInstruction.Operation.CAPTURE
                    || trace.operation() == IrThrowableTraceInstruction.Operation.RELEASE
                    || trace.operation() == IrThrowableTraceInstruction.Operation.COMMON;
            // Other runtime operations need an explicit callback/dispatch audit.
            default -> false;
        };
    }
}
