// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.ir.IrAllocateInstruction;
import ironwood.compiler.ir.IrArrayLoadInstruction;
import ironwood.compiler.ir.IrArrayStoreInstruction;
import ironwood.compiler.ir.IrBasicBlock;
import ironwood.compiler.ir.IrCallableKind;
import ironwood.compiler.ir.IrExceptionLandingPadInstruction;
import ironwood.compiler.ir.IrField;
import ironwood.compiler.ir.IrFieldLoadInstruction;
import ironwood.compiler.ir.IrFieldStoreInstruction;
import ironwood.compiler.ir.IrForeignCallInstruction;
import ironwood.compiler.ir.IrFunction;
import ironwood.compiler.ir.IrInstruction;
import ironwood.compiler.ir.IrInvokeTerminator;
import ironwood.compiler.ir.IrOperand;
import ironwood.compiler.ir.IrPhiInstruction;
import ironwood.compiler.ir.IrReferenceConversionInstruction;
import ironwood.compiler.ir.IrReturnTerminator;
import ironwood.compiler.ir.IrStaticFieldStoreInstruction;
import ironwood.compiler.ir.IrThrowTerminator;
import ironwood.compiler.ir.IrType;
import ironwood.compiler.ir.IrValueReference;

import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Publication of a constructor's in-progress receiver or a destroyed object,
 * following what the heap may hand back. A constructor store into exactly its
 * own receiver is retention rather than publication, so a later load of that
 * field must recover the stored identity: an object may hold itself, and a
 * helper may hold its constructor arguments. Each value has three facts:
 * parameters it may be, parameter identities it may reach through fields, and
 * parameters whose contents it may be or reach. An element store into an
 * exclusively owned array field of exactly that receiver is retention too.
 * Unknown effects stay conservative. The existing effect kernel's reclamation and returned-origin
 * facts are deliberately not derived from these broader facts.
 */
final class ReceiverPublicationAnalysis {
    private final ClosedWorldEffectAnalyzer effects;
    private final Map<String, IrFunction> functions;
    private final Set<String> ownedFields = new LinkedHashSet<>();
    private final Map<String, Facts> summaries = new LinkedHashMap<>();
    // Closed-world field marks only grow. A field written by a constructor with
    // its receiver or a retainer may return its holder; a field written with
    // parameter-derived data may return the holder's retained contents.
    private final Set<String> holderFields = new LinkedHashSet<>();
    private final Set<String> contentFields = new LinkedHashSet<>();

    ReceiverPublicationAnalysis(ClosedWorldEffectAnalyzer effects, List<IrFunction> functions,
                                Set<IrField> ownedFields) {
        this.effects = effects;
        ownedFields.forEach(field -> this.ownedFields.add(key(field)));
        this.functions = new LinkedHashMap<>();
        functions.forEach(function -> this.functions.put(function.linkageName(), function));
        functions.forEach(function -> summaries.put(function.linkageName(), Facts.empty()));
    }

    void analyze() {
        boolean changed;
        do {
            int marks = holderFields.size() + contentFields.size();
            changed = false;
            for (IrFunction function : functions.values()) {
                Facts next = new FunctionFacts(function).summarize();
                Facts previous = summaries.put(function.linkageName(), next);
                changed |= !next.equals(previous);
            }
            changed |= marks != holderFields.size() + contentFields.size();
        } while (changed);
    }

    boolean publishesReceiver(String linkage) {
        Facts facts = summaries.get(linkage);
        return facts != null && facts.published().get(0);
    }

    private static String key(IrField field) {
        return field.ownerClass() + "#" + field.name();
    }

    private final class FunctionFacts {
        private final IrFunction function;
        private final boolean constructor;
        private final Map<Integer, IrOperand> conversions;
        private final Set<Integer> allocations = new LinkedHashSet<>();
        // Arrays loaded from an owned field of exactly the constructing receiver.
        private final Map<Integer, String> ownedArrays = new LinkedHashMap<>();
        private final Map<Integer, Value> values = new LinkedHashMap<>();
        private final BitSet retained = new BitSet();
        private final BitSet retainedContent = new BitSet();
        private final BitSet published = new BitSet();
        private final BitSet publishedContent = new BitSet();
        private final BitSet returned = new BitSet();
        private final BitSet returnedRetained = new BitSet();
        private final BitSet returnedContent = new BitSet();
        // Values thrown to a handler in this function reach its landing pads.
        private final Value thrownLocally = new Value();
        private boolean changed;

        FunctionFacts(IrFunction function) {
            this.function = function;
            this.constructor = function.kind() == IrCallableKind.CONSTRUCTOR;
            this.conversions = ClosedWorldEffectAnalyzer.referenceConversions(function);
        }

        Facts summarize() {
            Set<String> reachable = effects.reachableBlocks(function);
            for (int index = 0; index < function.parameters().size(); index++) {
                Value parameter = value(function.parameters().get(index).value());
                parameter.identity.set(index);
            }
            do {
                changed = false;
                for (IrBasicBlock block : function.blocks()) {
                    if (!reachable.contains(block.label())) continue;
                    block.instructions().forEach(this::transfer);
                    if (block.terminator() instanceof IrInvokeTerminator invoke) {
                        transfer(invoke.call());
                    } else if (block.terminator() instanceof IrReturnTerminator result) {
                        result.value().ifPresent(value -> {
                            Value returnedValue = read(value);
                            changed |= or(returned, returnedValue.identity)
                                    | or(returnedRetained, returnedValue.retained)
                                    | or(returnedContent, returnedValue.content);
                        });
                    } else if (block.terminator() instanceof IrThrowTerminator thrown) {
                        if (thrown.unwindTarget().isPresent()) {
                            changed |= thrownLocally.join(read(thrown.exception()));
                        } else if (!ClosedWorldEffectAnalyzer.isCatchAllFallback(function, block)) {
                            publishIdentity(read(thrown.exception()), -1);
                        }
                    }
                }
            } while (changed);
            return new Facts(published, publishedContent, retained, retainedContent,
                    returned, returnedRetained, returnedContent);
        }

        private void transfer(IrInstruction instruction) {
            switch (instruction) {
                case IrAllocateInstruction allocate -> allocations.add(allocate.result().id());
                case IrExceptionLandingPadInstruction pad -> join(pad.exceptionObject(), thrownLocally);
                case IrReferenceConversionInstruction conversion ->
                        join(conversion.result(), read(conversion.value()));
                case IrPhiInstruction phi -> phi.incoming().forEach(incoming ->
                        join(phi.result(), read(incoming.value())));
                case IrFieldLoadInstruction load -> {
                    if (!load.result().type().isReference()) return;
                    Value receiver = read(load.receiver());
                    String field = key(load.field());
                    if (constructor && exactParameter(load.receiver()) == 0
                            && load.result().type().isArray() && ownedFields.contains(field)) {
                        ownedArrays.put(load.result().id(), field);
                    }
                    Value loaded = new Value();
                    if (holderFields.contains(field)) {
                        loaded.identity.or(receiver.identity);
                        loaded.retained.or(receiver.identity);
                        loaded.retained.or(receiver.retained);
                        loaded.content.or(receiver.content);
                    }
                    if (contentFields.contains(field)) {
                        BitSet held = contentIdentity(receiver);
                        loaded.identity.or(held);
                        loaded.retained.or(held);
                        loaded.content.or(receiver.identity);
                        loaded.content.or(receiver.content);
                    }
                    join(load.result(), loaded);
                }
                case IrArrayLoadInstruction load -> {
                    // An element is content of its array, which may hold retained data.
                    Value array = read(load.array());
                    Value element = new Value();
                    BitSet held = contentIdentity(array);
                    element.identity.or(held);
                    element.retained.or(held);
                    element.content.or(array.identity);
                    element.content.or(array.content);
                    join(load.result(), element);
                }
                case IrFieldStoreInstruction store -> {
                    int target = exactParameter(store.receiver());
                    if (constructor && target == 0) {
                        retain(key(store.field()), store.value());
                    } else {
                        publishIdentity(read(store.value()), target);
                    }
                }
                case IrStaticFieldStoreInstruction store -> publishIdentity(read(store.value()), -1);
                case IrArrayStoreInstruction store -> {
                    String field = store.array() instanceof IrValueReference array
                            ? ownedArrays.get(array.id()) : null;
                    if (field != null) {
                        retain(field, store.value());
                    } else {
                        publishIdentity(read(store.value()), -1);
                    }
                }
                case IrForeignCallInstruction call -> {
                    Value inputs = new Value();
                    call.arguments().stream().filter(argument -> argument.type().isReference())
                            .forEach(argument -> inputs.join(read(argument)));
                    publishIdentity(inputs, -1);
                    call.result().filter(result -> result.type().isReference())
                            .ifPresent(result -> join(result, inputs));
                }
                default -> transferCall(instruction);
            }
        }

        private void transferCall(IrInstruction instruction) {
            List<IrFunction> targets = effects.targets(instruction);
            if (targets.isEmpty()) return;
            List<IrOperand> arguments = ClosedWorldEffectAnalyzer.callArguments(instruction);
            List<Value> inputs = arguments.stream().map(this::read).toList();
            IrValueReference result = ClosedWorldEffectAnalyzer.callResult(instruction);
            Value output = new Value();
            for (IrFunction target : targets) {
                Facts facts = summaries.getOrDefault(target.linkageName(), Facts.empty());
                for (int index = 0; index < inputs.size(); index++) {
                    Value input = inputs.get(index);
                    if (facts.published().get(index)) publishIdentity(input, -1);
                    if (facts.publishedContent().get(index)) publishContent(input);
                    if (facts.returned().get(index)) output.join(input);
                    if (facts.returnedRetained().get(index)) {
                        output.retained.or(reachIdentity(input));
                        output.content.or(input.content);
                    }
                    if (facts.returnedContent().get(index)) {
                        BitSet held = contentIdentity(input);
                        output.identity.or(held);
                        output.retained.or(held);
                        output.content.or(input.identity);
                        output.content.or(input.content);
                    }
                }
                if (target.kind() == IrCallableKind.CONSTRUCTOR && !arguments.isEmpty()) {
                    retainIn(arguments.getFirst(), facts, inputs);
                }
            }
            if (result != null && result.type().isReference()) join(result, output);
        }

        /** Records a value that the constructing receiver now holds through a field. */
        private void retain(String field, IrOperand operand) {
            Value stored = read(operand);
            BitSet reached = reachIdentity(stored);
            changed |= or(retained, reached) | or(retainedContent, stored.content);
            if (!operand.type().isReference()) return;
            if (reached.get(0)) holderFields.add(field);
            BitSet parameters = (BitSet) reached.clone();
            parameters.clear(0);
            parameters.or(stored.content);
            if (!parameters.isEmpty()) contentFields.add(field);
        }

        /** Applies a callee constructor's receiver retention to the object it builds. */
        private void retainIn(IrOperand receiver, Facts facts, List<Value> inputs) {
            Value added = new Value();
            for (int index = 0; index < inputs.size(); index++) {
                Value input = inputs.get(index);
                if (facts.retained().get(index)) {
                    added.retained.or(reachIdentity(input));
                    added.content.or(input.content);
                }
                if (facts.retainedContent().get(index)) {
                    added.retained.or(contentIdentity(input));
                    added.content.or(input.identity);
                    added.content.or(input.content);
                }
            }
            if (constructor && exactParameter(receiver) == 0) {
                changed |= or(retained, added.retained) | or(retainedContent, added.content);
            } else if (receiver instanceof IrValueReference value && allocations.contains(value.id())) {
                join(value, added);
            } else {
                Value unknown = new Value();
                unknown.retained.or(added.retained);
                unknown.content.or(added.content);
                publishIdentity(unknown, -1);
            }
        }

        /** Publishes everything reachable from a value. A store into exactly
         * parameter {@code target} does not newly expose that parameter's own
         * contents; its identity and all other reached data remain published. */
        private void publishIdentity(Value value, int target) {
            BitSet contents = (BitSet) value.content.clone();
            if (target >= 0) contents.clear(target);
            changed |= or(published, reachIdentity(value))
                    | or(publishedContent, value.identity)
                    | or(publishedContent, value.retained)
                    | or(publishedContent, contents);
        }

        /** Publishes what a value reaches through its fields, but not the value. */
        private void publishContent(Value value) {
            changed |= or(published, contentIdentity(value))
                    | or(publishedContent, value.identity)
                    | or(publishedContent, value.retained)
                    | or(publishedContent, value.content);
        }

        /** Identities a value may be or reach, including what reached parameters retain. */
        private BitSet reachIdentity(Value value) {
            BitSet result = (BitSet) value.identity.clone();
            result.or(value.retained);
            result.or(retainedBy(value.content));
            return result;
        }

        /** Identities reachable through a value's fields, excluding the value itself. */
        private BitSet contentIdentity(Value value) {
            BitSet result = (BitSet) value.retained.clone();
            result.or(retainedBy(value.identity));
            result.or(retainedBy(value.content));
            return result;
        }

        /** A callee cannot see retention its caller established; only the
         * constructor knows what its own receiver holds. */
        private BitSet retainedBy(BitSet parameters) {
            return constructor && parameters.get(0) ? (BitSet) retained.clone() : new BitSet();
        }

        private int exactParameter(IrOperand operand) {
            return ClosedWorldEffectAnalyzer.exactParameter(function, conversions, operand).orElse(-1);
        }

        private Value read(IrOperand operand) {
            if (!(operand instanceof IrValueReference reference) || !tracked(reference.type())) {
                return new Value();
            }
            Value current = values.get(reference.id());
            Value result = current == null ? new Value() : current.copy();
            if (constructor && exactParameter(operand) == 0) {
                result.retained.or(retained);
                result.content.or(retainedContent);
            }
            return result;
        }

        private Value value(IrValueReference reference) {
            return values.computeIfAbsent(reference.id(), ignored -> new Value());
        }

        private void join(IrValueReference result, Value addition) {
            if (!tracked(result.type())) return;
            changed |= value(result).join(addition);
        }
    }

    /** Landing pads carry a caught object in the opaque exception type. */
    private static boolean tracked(IrType type) {
        return type.isReference() || type.equals(IrType.EXCEPTION);
    }

    private static boolean or(BitSet target, BitSet addition) {
        int cardinality = target.cardinality();
        target.or(addition);
        return cardinality != target.cardinality();
    }

    private static final class Value {
        private final BitSet identity = new BitSet();
        private final BitSet retained = new BitSet();
        private final BitSet content = new BitSet();

        boolean join(Value other) {
            return or(identity, other.identity) | or(retained, other.retained) | or(content, other.content);
        }

        Value copy() {
            Value copy = new Value();
            copy.join(this);
            return copy;
        }
    }

    private record Facts(BitSet published, BitSet publishedContent, BitSet retained,
                         BitSet retainedContent, BitSet returned, BitSet returnedRetained,
                         BitSet returnedContent) {
        private Facts {
            published = (BitSet) published.clone();
            publishedContent = (BitSet) publishedContent.clone();
            retained = (BitSet) retained.clone();
            retainedContent = (BitSet) retainedContent.clone();
            returned = (BitSet) returned.clone();
            returnedRetained = (BitSet) returnedRetained.clone();
            returnedContent = (BitSet) returnedContent.clone();
        }

        private static Facts empty() {
            BitSet none = new BitSet();
            return new Facts(none, none, none, none, none, none, none);
        }
    }
}
