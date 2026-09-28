// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.bridge.BridgeCallableId;
import ironwood.compiler.bridge.BridgeRootSet;
import ironwood.compiler.ir.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

/**
 * Explicit context specialization after source safety. This transformation is
 * not lifetime/transport admission: foreign edges remain unknown to those proofs.
 * Original functions and dispatch slots retain their ordinary native ABI.
 */
public final class BridgeCallbackContextLowering {
    public record Result(IrProgram program, Map<BridgeCallableId, IrFunction> entries,
                         Map<String, String> specializations) {
        public Result {
            entries = Map.copyOf(entries);
            specializations = Map.copyOf(specializations);
        }
    }

    private final IrProgram original;
    private final BridgeCallbackReachability effects;
    private final BridgeCallTargets targets;
    private final Map<String, String> selected = new LinkedHashMap<>();
    private final Map<Integer, IrDispatchSlot> slots = new LinkedHashMap<>();
    private final List<IrFunction> pending = new ArrayList<>();

    private BridgeCallbackContextLowering(IrProgram program, BridgeCallbackReachability effects) {
        if (!effects.matches(program)) throw new IllegalArgumentException("stale callback reachability facts");
        original = program;
        this.effects = effects;
        targets = new BridgeCallTargets(program);
    }

    public static Result lower(IrProgram program, BridgeRootSet roots, BridgeCallbackReachability effects) {
        return new BridgeCallbackContextLowering(program, effects).lower(roots);
    }

    private Result lower(BridgeRootSet roots) {
        var checked = roots.revalidate(original);
        if (!checked.resolved()) throw new IllegalArgumentException("callback context requires resolved roots");
        for (var entry : effects.entries(checked).entrySet()) {
            requireComplete(entry.getValue());
            // Context-bearing initialization/rollback needs its own protected ABI.
            // Until implemented it must not enter the callback-free runtime helper.
            requireNative(targets.initializers(entry.getKey().owner()).targets(), "entry initialization");
            var function = targets.function(entry.getKey().linkage());
            if (function.kind() == IrCallableKind.CONSTRUCTOR && !function.parameters().isEmpty()) {
                var rollback = targets.cleanup(function.parameters().getFirst().value(), true);
                if (!rollback.complete()) throw new IllegalArgumentException("unresolved entry rollback");
                requireNative(rollback.targets(), "entry rollback");
            }
            if (effect(function).foreign()) select(function);
        }
        for (int index = 0; index < pending.size(); index++) {
            var function = pending.get(index);
            requireComplete(effect(function));
            operations(function).forEach(this::discover);
        }
        List<IrFunction> functions = new ArrayList<>(original.functions());
        Map<String, IrFunction> clones = new LinkedHashMap<>();
        for (var function : pending) {
            var clone = specialize(function);
            functions.add(clone);
            clones.put(function.linkageName(), clone);
        }
        var dispatchSlots = new ArrayList<>(original.dispatchSlots());
        dispatchSlots.addAll(slots.values());
        var classes = original.classes().stream().map(type -> new IrClass(type.name(), type.kind(), type.superclass(),
                type.interfaces(), type.fields(), type.typeId(), type.typeMembership(), dispatch(type.dispatchEntries()),
                type.destructorChain(), type.constructorRollback(), type.toStringReturnsOwnedFresh(),
                type.localizedMessageReturnsOwnedFresh(), type.sourceSpan())).toList();
        var arrays = original.arrayTypes().stream().map(type -> new IrArrayType(type.type(), type.typeId(),
                type.typeMembership(), dispatch(type.dispatchEntries()), type.toStringReturnsOwnedFresh())).toList();
        var program = new IrProgram(original.moduleName(), classes, original.staticFields(), original.typeInitializations(),
                arrays, original.stringConstants(), dispatchSlots, functions, original.entryPoint(),
                original.allocationFailure(), original.exportRoots());
        Map<BridgeCallableId, IrFunction> entries = new LinkedHashMap<>();
        for (var root : checked.roots()) {
            entries.put(root.callable(), clones.getOrDefault(root.callable().linkage(), targets.function(root.callable().linkage())));
        }
        return new Result(program, entries, selected);
    }

    private void select(IrFunction function) {
        if (selected.containsKey(function.linkageName())) return;
        String name = function.linkageName() + "$bridge_context";
        if (targets.function(name) != null) throw new IllegalArgumentException("callback specialization symbol collision: " + name);
        selected.put(function.linkageName(), name);
        pending.add(function);
    }

    private void discover(IrInstruction instruction) {
        if (instruction instanceof IrForeignCallInstruction foreign) {
            if (foreign.invocationContext().isPresent()) throw new IllegalArgumentException("callback context already bound");
            return;
        }
        var call = targets.resolve(instruction);
        if (instruction instanceof IrFreeInstruction free) call = targets.cleanup(free.allocation(), false);
        else if (instruction instanceof IrRollbackInstruction rollback) call = targets.cleanup(rollback.allocation(), true);
        else if (instruction instanceof IrDestroyArrayElementsInstruction elements && elements.array().type().elementType().isReference()) {
            call = targets.cleanup(new IrNull(elements.array().type().elementType(), elements.sourceSpan()), false);
        }
        if (call == null) return; // Complete reachability has already audited fixed native operations.
        if (!call.complete()) throw new IllegalArgumentException("unresolved callback context edge");
        boolean needsContext = call.targets().stream().anyMatch(target -> effect(target).requiresContext());
        if (!needsContext) return;
        if (instruction instanceof IrCallInstruction) {
            call.targets().forEach(this::select);
        } else if (instruction instanceof IrVirtualCallInstruction virtual) {
            selectSlot(virtual.slot(), call.targets());
        } else if (instruction instanceof IrInterfaceCallInstruction virtual) {
            selectSlot(virtual.slot(), call.targets());
        } else {
            throw new IllegalArgumentException("callback context unsupported through initialization or cleanup: "
                    + instruction.getClass().getSimpleName());
        }
    }

    private void selectSlot(IrDispatchSlot slot, List<IrFunction> alternatives) {
        if (!slots.containsKey(slot.index())) {
            String key = slot.key() + "$bridge_context";
            if (original.dispatchSlots().stream().anyMatch(candidate -> candidate.key().equals(key))) {
                throw new IllegalArgumentException("callback dispatch slot collision");
            }
            int index = original.dispatchSlots().stream().mapToInt(IrDispatchSlot::index).max().orElse(-1) + 1 + slots.size();
            slots.put(slot.index(), new IrDispatchSlot(index, key, slot.methodName(), slot.returnType(),
                    appended(slot.parameterTypes(), IrType.I64), slot.sourceSpan()));
        }
        // Even a pure native alternative must match the context-bearing slot ABI.
        alternatives.forEach(this::select);
    }

    private List<IrDispatchEntry> dispatch(List<IrDispatchEntry> entries) {
        var result = new ArrayList<>(entries);
        for (var entry : entries) {
            var slot = slots.get(entry.slot().index());
            if (slot != null) result.add(new IrDispatchEntry(slot, selected.get(entry.targetLinkageName())));
        }
        return result;
    }

    private IrFunction specialize(IrFunction function) {
        int[] maximum = {-1};
        function.parameters().forEach(parameter -> maximum[0] = Math.max(maximum[0], parameter.value().id()));
        var visitor = new IrCfgRenamer(value -> {
            maximum[0] = Math.max(maximum[0], value.id());
            return value;
        }, UnaryOperator.identity());
        function.blocks().forEach(visitor::block);
        var context = new IrValueReference(maximum[0] + 1, IrType.I64, function.sourceSpan());
        var parameters = appended(function.parameters(), new IrParameter("$bridge_context", context, function.sourceSpan()));
        var blocks = function.blocks().stream().map(block -> new IrBasicBlock(block.label(),
                block.instructions().stream().map(instruction -> bind(instruction, context)).toList(),
                block.terminator() instanceof IrInvokeTerminator invoke
                        ? new IrInvokeTerminator(bind(invoke.call(), context), invoke.normalTarget(), invoke.unwindTarget(), invoke.sourceSpan())
                        : block.terminator(), block.sourceSpan())).toList();
        return new IrFunction(function.ownerClass(), function.sourceName(), selected.get(function.linkageName()),
                function.returnType(), parameters, blocks, function.sourceSpan(), function.sourceFileName(), function.kind());
    }

    private IrInstruction bind(IrInstruction instruction, IrOperand context) {
        if (instruction instanceof IrForeignCallInstruction call) {
            return new IrForeignCallInstruction(call.result(), call.targetLinkageName(), call.returnType(), call.arguments(),
                    Optional.of(context), call.sourceSpan());
        }
        if (instruction instanceof IrCallInstruction call && selected.containsKey(call.targetLinkageName())) {
            return new IrCallInstruction(call.result(), selected.get(call.targetLinkageName()), call.returnType(),
                    appended(call.arguments(), context), call.callKind(), call.devirtualizedFrom(), call.specializationArguments(), call.sourceSpan());
        }
        if (instruction instanceof IrVirtualCallInstruction call && slots.containsKey(call.slot().index())) {
            return new IrVirtualCallInstruction(call.result(), slots.get(call.slot().index()), call.returnType(),
                    appended(call.arguments(), context), call.specializationArguments(), call.sourceSpan());
        }
        if (instruction instanceof IrInterfaceCallInstruction call && slots.containsKey(call.slot().index())) {
            return new IrInterfaceCallInstruction(call.result(), call.interfaceName(), slots.get(call.slot().index()), call.returnType(),
                    appended(call.arguments(), context), call.specializationArguments(), call.sourceSpan());
        }
        return instruction;
    }

    private void requireNative(List<IrFunction> functions, String operation) {
        if (functions.stream().anyMatch(function -> effect(function).requiresContext())) {
            throw new IllegalArgumentException("callback context unsupported through " + operation);
        }
    }

    private BridgeCallbackReachability.Effects effect(IrFunction function) {
        return effects.functions().get(BridgeCallableId.of(function));
    }

    private static void requireComplete(BridgeCallbackReachability.Effects effect) {
        if (!effect.complete()) throw new IllegalArgumentException("incomplete callback context graph: " + effect.unknown());
    }

    private static Stream<IrInstruction> operations(IrFunction function) {
        return function.blocks().stream().flatMap(block -> Stream.concat(block.instructions().stream(),
                block.terminator() instanceof IrInvokeTerminator invoke ? Stream.of(invoke.call()) : Stream.empty()));
    }

    private static <T> List<T> appended(List<T> values, T value) {
        var result = new ArrayList<>(values);
        result.add(value);
        return List.copyOf(result);
    }
}
