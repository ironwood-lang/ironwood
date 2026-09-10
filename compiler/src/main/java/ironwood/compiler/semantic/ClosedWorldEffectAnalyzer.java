// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.diagnostic.Diagnostic;
import ironwood.compiler.ir.IrStreamInstruction;
import ironwood.compiler.ir.*;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Closed-world fixed-point callable effects used by destructor and construction safety. */
final class ClosedWorldEffectAnalyzer {
    private final Map<String, IrFunction> functions = new LinkedHashMap<>();
    private final List<IrClass> classes;
    private final Map<String, Summary> summaries = new LinkedHashMap<>();
    // Targets depend only on this analysis's immutable IR and class snapshot.
    // Cache the graph, never the evolving allocation/publication summaries.
    private final Map<IrInstruction, List<IrFunction>> targetCache = new IdentityHashMap<>();

    ClosedWorldEffectAnalyzer(List<IrFunction> functions, List<IrClass> classes) {
        functions.forEach(function -> this.functions.put(function.linkageName(), function));
        this.classes = List.copyOf(classes);
        functions.forEach(function -> summaries.put(function.linkageName(), Summary.empty()));
    }

    void validate(Map<String, TypeSymbol> types, List<Diagnostic> diagnostics) {
        analyze();
        for (IrFunction function : functions.values()) {
            Summary summary = summaries.get(function.linkageName());
            TypeSymbol owner = types.get(function.ownerClass());
            if (owner == null) {
                continue;
            }
            if (function.kind() == IrCallableKind.DESTRUCTOR) {
                if (summary.allocates()) {
                    diagnostics.add(Diagnostic.error(owner.source(), function.sourceSpan(),
                            "destructor may allocate; destructor cleanup must be allocation-free"));
                }
                if (summary.throwsOutward()) {
                    diagnostics.add(Diagnostic.error(owner.source(), function.sourceSpan(),
                            "an exception may escape this destructor"));
                }
                if (summary.publishedParameters().get(0)) {
                    diagnostics.add(Diagnostic.error(owner.source(), function.sourceSpan(),
                            "destructor may publish or resurrect 'this'"));
                }
            } else if (function.kind() == IrCallableKind.CONSTRUCTOR
                    && summary.publishedParameters().get(0)) {
                diagnostics.add(Diagnostic.error(owner.source(), function.sourceSpan(),
                        "constructor may publish in-progress 'this' before construction completes"));
            }
        }
    }

    void analyze() {
        boolean changed;
        do {
            changed = false;
            for (IrFunction function : functions.values()) {
                Summary next = summarize(function);
                Summary previous = summaries.put(function.linkageName(), next);
                changed |= !next.equals(previous);
            }
        } while (changed);
    }

    private Summary summarize(IrFunction function) {
        Map<Integer, BitSet> origins = new LinkedHashMap<>();
        for (int index = 0; index < function.parameters().size(); index++) {
            BitSet origin = new BitSet();
            origin.set(index);
            origins.put(function.parameters().get(index).value().id(), origin);
        }
        BitSet published = new BitSet();
        BitSet returned = new BitSet();
        BitSet reclaimed = new BitSet();
        boolean allocates = false;
        boolean throwsOutward = false;
        boolean originChanged;
        do {
            originChanged = false;
            for (IrBasicBlock block : function.blocks()) {
                for (IrInstruction instruction : block.instructions()) {
                    originChanged |= propagateResultOrigins(instruction, origins);
                }
                if (block.terminator() instanceof IrInvokeTerminator invoke) {
                    originChanged |= propagateResultOrigins(invoke.call(), origins);
                }
            }
        } while (originChanged);

        for (IrBasicBlock block : function.blocks()) {
            for (IrInstruction instruction : block.instructions()) {
                allocates |= locallyAllocates(instruction);
                Effect callEffect = callEffect(instruction, origins);
                allocates |= callEffect.allocates();
                throwsOutward |= callEffect.throwsOutward();
                published.or(callEffect.published());
                reclaimed.or(callEffect.reclaimed());
                IrOperand released = switch (instruction) {
                    case IrFreeInstruction free -> free.allocation();
                    case IrRollbackInstruction rollback -> rollback.allocation();
                    case IrReleaseOwnedToStringResultInstruction text -> text.result();
                    case IrReleaseOwnedThrowableMessageInstruction text -> text.message();
                    default -> null;
                };
                reclaimed.or(origin(released, origins));
                if (instruction instanceof IrFieldStoreInstruction store) {
                    BitSet receiver = origin(store.receiver(), origins);
                    if (function.kind() != IrCallableKind.CONSTRUCTOR || !receiver.get(0)) {
                        published.or(origin(store.value(), origins));
                    }
                } else if (instruction instanceof IrStaticFieldStoreInstruction store) {
                    published.or(origin(store.value(), origins));
                } else if (instruction instanceof IrArrayStoreInstruction store) {
                    BitSet array = origin(store.array(), origins);
                    if (function.kind() != IrCallableKind.CONSTRUCTOR || !array.get(0)) {
                        published.or(origin(store.value(), origins));
                    }
                }
            }
            IrTerminator terminator = block.terminator();
            if (terminator instanceof IrReturnTerminator result) {
                result.value().ifPresent(value -> returned.or(origin(value, origins)));
            } else if (terminator instanceof IrThrowTerminator thrown) {
                boolean outward = thrown.unwindTarget().isEmpty()
                        && !isCatchAllFallback(function, block);
                if (outward) {
                    published.or(origin(thrown.exception(), origins));
                    throwsOutward = true;
                }
            } else if (terminator instanceof IrInvokeTerminator invoke) {
                allocates |= locallyAllocates(invoke.call());
                Effect effect = callEffect(invoke.call(), origins);
                allocates |= effect.allocates();
                published.or(effect.published());
                reclaimed.or(effect.reclaimed());
                // The exceptional edge is handled inside this function. Any eventual escape
                // appears as an IrThrowTerminator without another local unwind target.
            }
        }
        return new Summary(allocates, throwsOutward, published, returned, reclaimed);
    }

    private static boolean isCatchAllFallback(IrFunction function, IrBasicBlock fallback) {
        if (!fallback.label().startsWith("catch.next")) {
            return false;
        }
        return function.blocks().stream().anyMatch(predecessor ->
                predecessor.terminator() instanceof IrBranch branch
                        && branch.falseTarget().equals(fallback.label())
                        && predecessor.instructions().stream()
                        .filter(IrInstanceOfInstruction.class::isInstance)
                        .map(IrInstanceOfInstruction.class::cast)
                        .anyMatch(test -> test.targetTypeName()
                                .equals("ironwood.lang.Throwable")));
    }

    private boolean propagateResultOrigins(IrInstruction instruction,
                                           Map<Integer, BitSet> origins) {
        if (instruction instanceof IrReferenceConversionInstruction conversion) {
            return mergeOrigin(conversion.result(), origin(conversion.value(), origins), origins);
        }
        if (instruction instanceof IrPhiInstruction phi) {
            BitSet value = new BitSet();
            phi.incoming().forEach(incoming -> value.or(origin(incoming.value(), origins)));
            return mergeOrigin(phi.result(), value, origins);
        }
        List<IrFunction> targets = targets(instruction);
        IrValueReference result = callResult(instruction);
        if (result == null || targets.isEmpty()) {
            return false;
        }
        List<IrOperand> arguments = callArguments(instruction);
        BitSet value = new BitSet();
        for (IrFunction target : targets) {
            BitSet targetReturns = summaries.getOrDefault(target.linkageName(), Summary.empty())
                    .returnedParameters();
            for (int parameter = targetReturns.nextSetBit(0); parameter >= 0;
                 parameter = targetReturns.nextSetBit(parameter + 1)) {
                if (parameter < arguments.size()) {
                    value.or(origin(arguments.get(parameter), origins));
                }
            }
        }
        return mergeOrigin(result, value, origins);
    }

    private Effect callEffect(IrInstruction instruction, Map<Integer, BitSet> origins) {
        List<IrFunction> targets = targets(instruction);
        if (targets.isEmpty()) {
            return Effect.NONE;
        }
        List<IrOperand> arguments = callArguments(instruction);
        boolean allocates = false;
        boolean throwsOutward = false;
        BitSet published = new BitSet();
        BitSet reclaimed = new BitSet();
        for (IrFunction target : targets) {
            Summary summary = summaries.getOrDefault(target.linkageName(), Summary.empty());
            allocates |= summary.allocates();
            throwsOutward |= summary.throwsOutward();
            BitSet targetReclaimed = summary.reclaimedParameters();
            for (int parameter = targetReclaimed.nextSetBit(0); parameter >= 0;
                 parameter = targetReclaimed.nextSetBit(parameter + 1)) {
                if (parameter < arguments.size()) reclaimed.or(origin(arguments.get(parameter), origins));
            }
            BitSet targetPublished = summary.publishedParameters();
            for (int parameter = targetPublished.nextSetBit(0); parameter >= 0;
                 parameter = targetPublished.nextSetBit(parameter + 1)) {
                if (parameter < arguments.size()) {
                    published.or(origin(arguments.get(parameter), origins));
                }
            }
        }
        return new Effect(allocates, throwsOutward, published, reclaimed);
    }

    // A may-reclaim result only suppresses a definite-abandonment diagnostic;
    // it never authorizes free or asserts that a caller's allocation is dead.
    BitSet possiblyReclaimedArguments(IrInstruction instruction) {
        BitSet result = new BitSet();
        targets(instruction).forEach(target -> result.or(
                summaries.getOrDefault(target.linkageName(), Summary.empty()).reclaimedParameters()));
        return result;
    }

    private List<IrFunction> targets(IrInstruction instruction) {
        if (instruction instanceof IrCallInstruction call) {
            IrFunction target = functions.get(call.targetLinkageName());
            return target == null ? List.of() : List.of(target);
        }
        if (instruction instanceof IrEnsureTypeInitializedInstruction
                || instruction instanceof IrVirtualCallInstruction
                || instruction instanceof IrInterfaceCallInstruction
                || instruction instanceof IrDestroyArrayElementsInstruction
                || instruction instanceof IrFreeInstruction) {
            return targetCache.computeIfAbsent(instruction, this::resolveTargets);
        }
        return List.of();
    }

    private List<IrFunction> resolveTargets(IrInstruction instruction) {
        if (instruction instanceof IrEnsureTypeInitializedInstruction ensure) {
            LinkedHashSet<IrFunction> initializers = new LinkedHashSet<>();
            collectInitializationTargets(ensure.typeName(), new LinkedHashSet<>(), initializers);
            return List.copyOf(initializers);
        }
        int slot;
        if (instruction instanceof IrVirtualCallInstruction call) {
            slot = call.slot().index();
        } else if (instruction instanceof IrInterfaceCallInstruction call) {
            slot = call.slot().index();
        } else if (instruction instanceof IrDestroyArrayElementsInstruction destroy) {
            IrType elementType = destroy.array().type().elementType().erasure();
            return classes.stream().filter(type -> elementType.isNominalReference()
                            && isSubtype(type, elementType.referenceName()))
                    .flatMap(type -> type.destructorChain().stream())
                    .map(functions::get).filter(java.util.Objects::nonNull).distinct().toList();
        } else if (instruction instanceof IrFreeInstruction free) {
            IrType staticType = free.allocation().type().erasure();
            if (staticType.isArray() || !staticType.isNominalReference()) {
                return List.of();
            }
            LinkedHashSet<IrFunction> destructors = new LinkedHashSet<>();
            classes.stream().filter(type -> isSubtype(type, staticType.referenceName()))
                    .flatMap(type -> type.destructorChain().stream())
                    .map(functions::get).filter(java.util.Objects::nonNull)
                    .forEach(destructors::add);
            return List.copyOf(destructors);
        } else {
            return List.of();
        }
        Set<IrFunction> result = new LinkedHashSet<>();
        classes.stream().flatMap(type -> type.dispatchEntries().stream())
                .filter(entry -> entry.slot().index() == slot)
                .map(entry -> functions.get(entry.targetLinkageName()))
                .filter(java.util.Objects::nonNull).forEach(result::add);
        return List.copyOf(result);
    }

    private void collectInitializationTargets(String typeName, Set<String> visited,
                                              Set<IrFunction> result) {
        if (!visited.add(typeName)) {
            return;
        }
        functions.values().stream()
                .filter(function -> function.kind() == IrCallableKind.CLASS_INITIALIZER)
                .filter(function -> function.ownerClass().equals(typeName))
                .forEach(result::add);
        IrClass type = classes.stream().filter(candidate -> candidate.name().equals(typeName))
                .findFirst().orElse(null);
        if (type == null) {
            return;
        }
        type.superclass().ifPresent(superclass ->
                collectInitializationTargets(superclass, visited, result));
        type.interfaces().forEach(interfaceName ->
                collectInitializationTargets(interfaceName, visited, result));
    }

    private boolean isSubtype(IrClass candidate, String targetName) {
        return isSubtype(candidate, targetName, new LinkedHashSet<>());
    }

    private boolean isSubtype(IrClass candidate, String targetName, Set<String> visited) {
        if (candidate.name().equals(targetName)
                || candidate.interfaces().contains(targetName)) {
            return true;
        }
        if (!visited.add(candidate.name())) {
            return false;
        }
        for (String interfaceName : candidate.interfaces()) {
            IrClass implemented = classes.stream()
                    .filter(type -> type.name().equals(interfaceName)).findFirst().orElse(null);
            if (implemented != null && isSubtype(implemented, targetName, visited)) {
                return true;
            }
        }
        String superclass = candidate.superclass().orElse(null);
        if (superclass == null) {
            return false;
        }
        IrClass parent = classes.stream().filter(type -> type.name().equals(superclass))
                .findFirst().orElse(null);
        return parent != null && isSubtype(parent, targetName, visited);
    }

    private static List<IrOperand> callArguments(IrInstruction instruction) {
        if (instruction instanceof IrCallInstruction call) {
            return call.arguments();
        }
        if (instruction instanceof IrVirtualCallInstruction call) {
            return call.arguments();
        }
        if (instruction instanceof IrInterfaceCallInstruction call) {
            return call.arguments();
        }
        if (instruction instanceof IrFreeInstruction free) {
            return List.of(free.allocation());
        }
        return List.of();
    }

    private static IrValueReference callResult(IrInstruction instruction) {
        if (instruction instanceof IrCallInstruction call) {
            return call.result().orElse(null);
        }
        if (instruction instanceof IrVirtualCallInstruction call) {
            return call.result().orElse(null);
        }
        if (instruction instanceof IrInterfaceCallInstruction call) {
            return call.result().orElse(null);
        }
        return null;
    }

    private static boolean locallyAllocates(IrInstruction instruction) {
        return instruction instanceof IrThrowableTraceInstruction trace
                && (trace.operation() == IrThrowableTraceInstruction.Operation.CAPTURE
                || trace.operation() == IrThrowableTraceInstruction.Operation.ARRAY)
                || instruction instanceof IrAllocateInstruction
                || instruction instanceof IrSystemPropertyInstruction
                || instruction instanceof IrArrayAllocateInstruction
                || instruction instanceof IrObjectToStringInstruction
                || instruction instanceof IrThrowableDescriptionInstruction
                || instruction instanceof IrStringFromCharsInstruction
                || instruction instanceof IrStringCaseInstruction
                || instruction instanceof IrStringRepeatInstruction
                || instruction instanceof IrStringReplaceCharInstruction
                || instruction instanceof IrStringReplaceTextInstruction
                || instruction instanceof IrStringJoinInstruction
                || instruction instanceof IrStringFromUtf8Instruction
                || instruction instanceof IrStringFromCharRangeInstruction
                || instruction instanceof IrStringFromRangeInstruction
                || instruction instanceof IrStringFromIntegerInstruction
                || instruction instanceof IrStringFromCharacterInstruction
                || instruction instanceof IrStreamInstruction stream
                        && stream.operation() == IrStreamInstruction.Operation.OPEN
                || instruction instanceof IrFileInstruction file
                        && file.operation() != IrFileInstruction.Operation.LAST_ERROR
                || instruction instanceof IrStringConcatInstruction;
    }

    private static BitSet origin(IrOperand operand, Map<Integer, BitSet> origins) {
        if (!(operand instanceof IrValueReference value)) {
            return new BitSet();
        }
        return (BitSet) origins.getOrDefault(value.id(), new BitSet()).clone();
    }

    private static boolean mergeOrigin(IrValueReference result, BitSet value,
                                       Map<Integer, BitSet> origins) {
        BitSet previous = origins.computeIfAbsent(result.id(), ignored -> new BitSet());
        int cardinality = previous.cardinality();
        previous.or(value);
        return cardinality != previous.cardinality();
    }

    private record Summary(boolean allocates, boolean throwsOutward,
                           BitSet publishedParameters, BitSet returnedParameters, BitSet reclaimedParameters) {
        private Summary {
            publishedParameters = (BitSet) publishedParameters.clone();
            returnedParameters = (BitSet) returnedParameters.clone();
            reclaimedParameters = (BitSet) reclaimedParameters.clone();
        }

        private static Summary empty() {
            return new Summary(false, false, new BitSet(), new BitSet(), new BitSet());
        }
    }

    private record Effect(boolean allocates, boolean throwsOutward, BitSet published, BitSet reclaimed) {
        private static final Effect NONE = new Effect(false, false, new BitSet(), new BitSet());
    }
}
