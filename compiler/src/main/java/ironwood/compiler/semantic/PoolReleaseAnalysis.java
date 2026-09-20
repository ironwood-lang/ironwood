// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.ir.IrCallInstruction;
import ironwood.compiler.ir.IrFieldLoadInstruction;
import ironwood.compiler.ir.IrFunction;
import ironwood.compiler.ir.IrInstruction;
import ironwood.compiler.ir.IrInterfaceCallInstruction;
import ironwood.compiler.ir.IrInvokeTerminator;
import ironwood.compiler.ir.IrOperand;
import ironwood.compiler.ir.IrPhiInstruction;
import ironwood.compiler.ir.IrReferenceConversionInstruction;
import ironwood.compiler.ir.IrStaticFieldLoadInstruction;
import ironwood.compiler.ir.IrVirtualCallInstruction;
import ironwood.compiler.source.SourceSpan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Proves a bundled release returns a checkout to its exact originating pool.
 * Uses provisional SSA operands, including captured defer operands and every
 * finally copy. A common lifetime owner is insufficient: sibling fields and
 * distinct parameters may hold different pools. This never transfers ownership.
 */
final class PoolReleaseAnalysis {
    private sealed interface Identity permits Parameter, FinalField, StaticField {}
    private record Parameter(IrOperand value) implements Identity {}
    private record FinalField(Identity receiver, String owner, String name) implements Identity {}
    private record StaticField(String owner, String name) implements Identity {}
    private record Call(List<IrOperand> arguments, String name, List<CallableSymbol> targets) {}

    private final Map<String, TypeSymbol> types;
    private final EscapeSummaryAnalyzer summaries;
    private final Map<String, IrFunction> functions = new HashMap<>();
    private final Map<String, Set<SourceSpan>> proved = new HashMap<>();

    PoolReleaseAnalysis(Map<String, TypeSymbol> types, BorrowDispatchAnalysis dispatch,
                        EscapeSummaryAnalyzer summaries) {
        this.types = types;
        this.summaries = summaries;
        dispatch.functions().forEach(function -> functions.put(function.linkageName(), function));
    }

    boolean returnsToOrigin(String function, SourceSpan span) {
        return proved.computeIfAbsent(function, name -> functions.containsKey(name)
                ? new Check(functions.get(name)).run() : Set.of()).contains(span);
    }

    private final class Check {
        private final IrFunction function;
        private final Map<IrOperand, IrInstruction> definitions = new HashMap<>();
        private final Set<IrOperand> parameters = new HashSet<>();
        private final List<IrInstruction> instructions = new ArrayList<>();

        Check(IrFunction function) {
            this.function = function;
            function.parameters().forEach(parameter -> parameters.add(parameter.value()));
            function.blocks().forEach(block -> {
                block.instructions().forEach(this::add);
                if (block.terminator() instanceof IrInvokeTerminator invoke) add(invoke.call());
            });
        }

        private void add(IrInstruction instruction) {
            instructions.add(instruction);
            IrOperand result = switch (instruction) {
                case IrCallInstruction call -> call.result().orElse(null);
                case IrVirtualCallInstruction call -> call.result().orElse(null);
                case IrInterfaceCallInstruction call -> call.result().orElse(null);
                case IrReferenceConversionInstruction conversion -> conversion.result();
                case IrPhiInstruction phi -> phi.result();
                case IrFieldLoadInstruction load -> load.result();
                case IrStaticFieldLoadInstruction load -> load.result();
                default -> null;
            };
            if (result != null) definitions.put(result, instruction);
        }

        Set<SourceSpan> run() {
            Map<SourceSpan, Boolean> copies = new HashMap<>();
            for (IrInstruction instruction : instructions) {
                Call call = call(instruction);
                if (call == null || !call.name().equals("release")) continue;
                boolean safe = false;
                if (!call.targets().isEmpty() && call.targets().stream().allMatch(PoolSemantics::isRelease)
                        && call.arguments().size() == 2) {
                    Identity receiver = identity(call.arguments().get(0), false, new HashSet<>());
                    Identity source = identity(call.arguments().get(1), true, new HashSet<>());
                    safe = receiver != null && receiver.equals(source);
                }
                // One AST call can have several normal/exceptional cleanup copies.
                copies.merge(instruction.sourceSpan(), safe, (left, right) -> left && right);
            }
            Set<SourceSpan> result = new HashSet<>();
            copies.forEach((span, safe) -> { if (safe) result.add(span); });
            return Set.copyOf(result);
        }

        private Identity identity(IrOperand value, boolean checkout, Set<IrOperand> visiting) {
            if (!visiting.add(value)) return null;
            try {
                if (!checkout && parameters.contains(value)) return new Parameter(value);
                IrInstruction definition = definitions.get(value);
                if (definition instanceof IrReferenceConversionInstruction conversion) {
                    return identity(conversion.value(), checkout, visiting);
                }
                if (definition instanceof IrPhiInstruction phi) {
                    Identity common = null;
                    for (var incoming : phi.incoming()) {
                        Identity current = identity(incoming.value(), checkout, visiting);
                        if (current == null || common != null && !common.equals(current)) return null;
                        common = current;
                    }
                    return common;
                }
                if (!checkout && definition instanceof IrFieldLoadInstruction load) {
                    TypeSymbol owner = types.get(load.field().ownerClass());
                    FieldSymbol field = owner == null ? null : owner.declaredFields().get(load.field().name());
                    if (field == null || !field.isFinal()) return null;
                    Identity receiver = identity(load.receiver(), false, visiting);
                    return receiver == null ? null
                            : new FinalField(receiver, load.field().ownerClass(), load.field().name());
                }
                if (!checkout && definition instanceof IrStaticFieldLoadInstruction load && load.field().isFinal()) {
                    return new StaticField(load.field().ownerClass(), load.field().name());
                }
                Call call = call(definition);
                if (checkout && call != null && call.arguments().size() == 1 && !call.targets().isEmpty()
                        && call.targets().stream().allMatch(PoolSemantics::isCheckout)) {
                    return identity(call.arguments().getFirst(), false, visiting);
                }
                // Unknown calls, fresh roots, mutable field reloads, and cycles
                // cannot establish a stable identity across invocations/iterations.
                return null;
            } finally {
                visiting.remove(value);
            }
        }

        private Call call(IrInstruction instruction) {
            if (instruction instanceof IrCallInstruction call) {
                CallableSymbol target = summaries.callable(call.targetLinkageName());
                return target == null ? null : new Call(call.arguments(), target.sourceName(), List.of(target));
            }
            String name;
            List<IrOperand> arguments;
            if (instruction instanceof IrVirtualCallInstruction call) {
                name = call.slot().methodName();
                arguments = call.arguments();
            } else if (instruction instanceof IrInterfaceCallInstruction call) {
                name = call.slot().methodName();
                arguments = call.arguments();
            } else {
                return null;
            }
            return new Call(arguments, name,
                    summaries.boundTargets(function.linkageName(), instruction.sourceSpan(), name));
        }
    }
}
