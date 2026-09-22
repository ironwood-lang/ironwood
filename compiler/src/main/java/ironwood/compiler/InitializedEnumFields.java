// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.ir.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/** Exact enum payload facts usable only inside existing completed-initialization paths. */
final class InitializedEnumFields {
    private final Map<String, IrFunction> functions = new HashMap<>();
    private final Map<String, IrClass> classes = new HashMap<>();
    private final Map<String, Map<IrField, IrConstant>> values = new HashMap<>();

    InitializedEnumFields(IrProgram program, Set<IrStaticField> published) {
        program.functions().forEach(f -> functions.put(f.linkageName(), f));
        program.classes().forEach(c -> classes.put(c.name(), c));
        Map<String, Set<IrEnumConstant>> constants = new LinkedHashMap<>();
        for (IrStaticField field : published) {
            IrEnumConstant value = (IrEnumConstant) field.initialValue();
            constants.computeIfAbsent(field.ownerClass(), ignored -> new HashSet<>()).add(value);
        }
        constants.forEach((owner, enums) -> prove(program, owner, enums, published));
    }

    private void prove(IrProgram program, String owner, Set<IrEnumConstant> enums,
                       Set<IrStaticField> published) {
        // Constant-specific subclasses and nontrivial initializer CFGs deliberately
        // retain their loads. An ordinary ensure may return during recursion.
        if (enums.stream().anyMatch(e -> !e.type().equals(e.storageType()))) return;
        List<IrFunction> initializers = program.functions().stream()
                .filter(f -> f.ownerClass().equals(owner) && f.kind() == IrCallableKind.CLASS_INITIALIZER).toList();
        if (initializers.size() != 1) return;
        IrFunction initializer = initializers.getFirst();
        if (initializer.blocks().size() != 1
                || !(initializer.blocks().getFirst().terminator() instanceof IrReturnTerminator end)
                || end.value().isPresent()) return;
        // Final declaration validation precedes this pass. Also audit actual
        // stores and constructor sites, including native address exposure.
        for (IrFunction function : program.functions()) {
            for (IrInstruction instruction : operations(function).toList()) {
                if (instruction instanceof IrFieldStoreInstruction store && store.field().ownerClass().equals(owner)
                        && (function.kind() != IrCallableKind.CONSTRUCTOR || !function.ownerClass().equals(owner))) return;
                if (instruction instanceof IrTcpInstruction tcp
                        && tcp.outputFields().stream().anyMatch(f -> f.ownerClass().equals(owner))) return;
                if (instruction instanceof IrCallInstruction call) {
                    IrFunction target = functions.get(call.targetLinkageName());
                    if (target != null && target.kind() == IrCallableKind.CONSTRUCTOR
                            && target.ownerClass().equals(owner) && function != initializer) return;
                }
            }
        }
        Map<String, Map<IrField, IrConstant>> proposed = new HashMap<>();
        Set<IrEnumConstant> constructed = new HashSet<>();
        Set<IrEnumConstant> stored = new HashSet<>();
        for (IrInstruction instruction : initializer.blocks().getFirst().instructions()) {
            if (instruction instanceof IrCallInstruction call && call.callKind() == IrCallKind.DIRECT
                    && !call.arguments().isEmpty() && call.arguments().getFirst() instanceof IrEnumConstant receiver
                    && enums.contains(receiver) && constructed.add(receiver)) {
                IrFunction target = functions.get(call.targetLinkageName());
                if (target == null || target.kind() != IrCallableKind.CONSTRUCTOR
                        || !target.ownerClass().equals(owner)) return;
                Map<IrField, IrConstant> fields = new HashMap<>();
                if (!evaluate(target, call.arguments(), receiver, fields, true, 0).valid()) return;
                proposed.put(receiver.symbol(), Map.copyOf(fields));
            } else if (instruction instanceof IrStaticFieldStoreInstruction store
                    && published.contains(store.field()) && store.value() instanceof IrEnumConstant receiver
                    && constructed.contains(receiver) && stored.add(receiver)
                    && store.field().initialValue().equals(receiver)) {
                // Keep the runtime publication; this only certifies its ordering.
            } else {
                return;
            }
        }
        if (constructed.equals(enums) && stored.equals(enums)) values.putAll(proposed);
    }

    List<IrBasicBlock> fold(List<IrBasicBlock> blocks, Set<String> initialized) {
        if (values.isEmpty()) return blocks;
        Map<Integer, IrOperand> identities = new HashMap<>();
        boolean changed;
        do {
            changed = false;
            for (IrBasicBlock block : blocks) {
                for (IrInstruction instruction : block.instructions()) {
                    if (instruction instanceof IrReferenceConversionInstruction copy) {
                        IrOperand value = resolve(copy.value(), identities);
                        if (value instanceof IrEnumConstant
                                && identities.putIfAbsent(copy.result().id(), value) == null) changed = true;
                    }
                }
            }
        } while (changed);
        List<IrBasicBlock> result = new ArrayList<>();
        for (IrBasicBlock block : blocks) {
            List<IrInstruction> instructions = new ArrayList<>();
            block.instructions().forEach(i -> instructions.add(fold(i, identities, initialized)));
            IrTerminator end = block.terminator();
            if (end instanceof IrInvokeTerminator invoke) {
                IrInstruction replacement = fold(invoke.call(), identities, initialized);
                if (replacement != invoke.call()) {
                    instructions.add(replacement);
                    end = new IrJump(invoke.normalTarget(), invoke.sourceSpan());
                }
            }
            result.add(new IrBasicBlock(block.label(), instructions, end, block.sourceSpan()));
        }
        return result;
    }

    private IrInstruction fold(IrInstruction instruction, Map<Integer, IrOperand> identities,
                               Set<String> initialized) {
        IrValueReference result;
        IrConstant constant;
        if (instruction instanceof IrFieldLoadInstruction load
                && resolve(load.receiver(), identities) instanceof IrEnumConstant receiver
                && initialized.contains(receiver.type().referenceName())) {
            constant = values.getOrDefault(receiver.symbol(), Map.of()).get(load.field());
            result = load.result();
        } else if (instruction instanceof IrCallInstruction call
                && call.result().isPresent() && call.arguments().size() == 1
                && resolve(call.arguments().getFirst(), identities) instanceof IrEnumConstant receiver
                && initialized.contains(receiver.type().referenceName()) && values.containsKey(receiver.symbol())) {
            IrFunction target = functions.get(call.targetLinkageName());
            if (target == null || target.kind() != IrCallableKind.METHOD) return instruction;
            Evaluation evaluation = evaluate(target, List.of(receiver), receiver,
                    values.get(receiver.symbol()), false, 0);
            constant = evaluation.valid() ? evaluation.value() : null;
            result = call.result().orElseThrow();
        } else {
            return instruction;
        }
        if (constant == null || !constant.type().equals(result.type())) return instruction;
        // An integer identity operation preserves the SSA definition and source
        // span without introducing a new IR instruction or floating-point rules.
        return new IrBinaryInstruction(result, IrBinaryOperator.ADD,
                new IrConstant(constant.type(), constant.value(), instruction.sourceSpan()),
                new IrConstant(constant.type(), 0, instruction.sourceSpan()), instruction.sourceSpan());
    }

    private Evaluation evaluate(IrFunction function, List<IrOperand> arguments, IrEnumConstant receiver,
                                Map<IrField, IrConstant> fields, boolean constructor, int depth) {
        if (depth > 8 || arguments.size() != function.parameters().size() || function.blocks().isEmpty()
                || function.blocks().stream().mapToInt(b -> b.instructions().size() + 1).sum() > 256) return Evaluation.UNKNOWN;
        Map<Integer, IrOperand> known = new HashMap<>();
        for (int i = 0; i < arguments.size(); i++) {
            IrOperand argument = arguments.get(i);
            if (!(argument instanceof IrEnumConstant) && !(argument instanceof IrConstant)) return Evaluation.UNKNOWN;
            known.put(function.parameters().get(i).value().id(), argument);
        }
        Map<String, IrBasicBlock> blocks = new HashMap<>();
        function.blocks().forEach(b -> blocks.put(b.label(), b));
        Set<String> visited = new HashSet<>();
        IrBasicBlock block = function.blocks().getFirst();
        while (block != null && visited.add(block.label())) {
            for (IrInstruction instruction : block.instructions()) {
                if (instruction instanceof IrReferenceConversionInstruction copy
                        && resolve(copy.value(), known) instanceof IrEnumConstant value) {
                    known.put(copy.result().id(), value);
                } else if (instruction instanceof IrNullCheckInstruction check
                        && resolve(check.receiver(), known) instanceof IrEnumConstant) {
                    known.put(check.result().id(), new IrConstant(IrType.I1, 1, check.sourceSpan()));
                } else if (constructor && instruction instanceof IrFieldStoreInstruction store
                        && receiver.equals(resolve(store.receiver(), known)) && store.field().isFinal()
                        && store.field().ownerClass().equals(receiver.type().referenceName())
                        && resolve(store.value(), known) instanceof IrConstant value
                        && integer(value.type()) && value.type().equals(store.field().type())) {
                    if (fields.putIfAbsent(store.field(), value) != null) return Evaluation.UNKNOWN;
                } else if (!constructor && instruction instanceof IrFieldLoadInstruction load
                        && receiver.equals(resolve(load.receiver(), known)) && fields.containsKey(load.field())) {
                    known.put(load.result().id(), fields.get(load.field()));
                } else if (constructor && instruction instanceof IrCallInstruction call
                        && call.callKind() == IrCallKind.DIRECT && call.result().isEmpty()) {
                    IrFunction target = functions.get(call.targetLinkageName());
                    IrClass owner = classes.get(function.ownerClass());
                    List<IrOperand> actual = call.arguments().stream().map(a -> resolve(a, known)).toList();
                    if (target == null || target.kind() != IrCallableKind.CONSTRUCTOR || owner == null
                            || !owner.superclass().filter(target.ownerClass()::equals).isPresent()
                            || actual.isEmpty() || !receiver.equals(actual.getFirst())
                            || !evaluate(target, actual, receiver, fields, true, depth + 1).valid()) return Evaluation.UNKNOWN;
                } else {
                    return Evaluation.UNKNOWN;
                }
            }
            if (block.terminator() instanceof IrReturnTerminator end) {
                if (constructor) return end.value().isEmpty() ? new Evaluation(true, null) : Evaluation.UNKNOWN;
                IrOperand value = end.value().map(v -> resolve(v, known)).orElse(null);
                return value instanceof IrConstant c && integer(c.type()) ? new Evaluation(true, c) : Evaluation.UNKNOWN;
            }
            String next;
            if (block.terminator() instanceof IrJump jump) {
                next = jump.target();
            } else if (block.terminator() instanceof IrBranch branch
                    && resolve(branch.condition(), known) instanceof IrConstant condition
                    && condition.type().equals(IrType.I1)) {
                next = condition.value().intValue() == 0 ? branch.falseTarget() : branch.trueTarget();
            } else {
                return Evaluation.UNKNOWN;
            }
            block = blocks.get(next);
        }
        return Evaluation.UNKNOWN;
    }

    private static boolean integer(IrType type) {
        return type.equals(IrType.I32) || type.equals(IrType.I64);
    }

    private static IrOperand resolve(IrOperand operand, Map<Integer, IrOperand> known) {
        return operand instanceof IrValueReference reference ? known.get(reference.id()) : operand;
    }

    private static Stream<IrInstruction> operations(IrFunction function) {
        return function.blocks().stream().flatMap(b -> Stream.concat(b.instructions().stream(),
                b.terminator() instanceof IrInvokeTerminator invoke ? Stream.of(invoke.call()) : Stream.empty()));
    }

    private record Evaluation(boolean valid, IrConstant value) {
        private static final Evaluation UNKNOWN = new Evaluation(false, null);
    }
}
