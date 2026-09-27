// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.bridge.BridgeCallableId;
import ironwood.compiler.bridge.BridgeProof;
import ironwood.compiler.bridge.BridgeRetentionContract;
import ironwood.compiler.bridge.BridgeRootSet;
import ironwood.compiler.ir.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Additional, analysis-only reference-store proof. Never alters native free facts.
 * Summaries substitute every possible helper/dispatch effect and retain slot-load
 * provenance across recursive calls. Unimplemented effects remain unknown.
 */
public final class BridgeRetentionAnalyzer {
    private enum Kind { INPUT, LOADED, NULL, FRESH, IMMORTAL, UNKNOWN }
    private record Origin(Kind kind, int input) {
        static Origin of(Kind kind) { return new Origin(kind, -1); }
    }
    private record Store(Origin holder, IrField field, Origin value, BridgeRetentionContract.Site site) {}
    private record Failure(BridgeProof.Status status, String reason) {}
    private record Summary(Set<Origin> returns, Set<Store> stores, Set<Failure> failures) {
        Summary {
            returns = Set.copyOf(returns);
            stores = Set.copyOf(stores);
            failures = Set.copyOf(failures);
        }
    }
    private record SlotKey(int holder, IrField field) {}

    private final BridgeCallTargets callTargets;
    private final BridgeControlFlow controlFlow;
    private final Map<String, IrFunction> functions = new LinkedHashMap<>();
    private final Map<String, Summary> summaries = new LinkedHashMap<>();
    private final Set<IrStaticField> staticFields;
    private final Set<IrField> ownedFields;

    private BridgeRetentionAnalyzer(IrProgram program, BridgeConstructionFacts facts) {
        this.callTargets = new BridgeCallTargets(program);
        this.controlFlow = new BridgeControlFlow(program);
        this.staticFields = Set.copyOf(program.staticFields());
        this.ownedFields = facts == null ? Set.of() : facts.constructors().values().stream()
                .flatMap(proof -> proof.contract().stream()).flatMap(contract -> contract.ownedStorageFields().stream())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        program.functions().forEach(function -> functions.put(function.linkageName(), function));
    }

    public static Map<BridgeCallableId, BridgeProof<BridgeRetentionContract>> analyze(
            IrProgram program, BridgeRootSet roots) {
        return analyze(program, roots, null);
    }

    public static Map<BridgeCallableId, BridgeProof<BridgeRetentionContract>> analyze(
            IrProgram program, BridgeRootSet roots, BridgeConstructionFacts facts) {
        if (facts != null && !facts.matches(program)) {
            throw new IllegalArgumentException("retention owned-field facts do not match the program");
        }
        BridgeRootSet checked = roots.revalidate(program);
        if (!checked.resolved()) {
            throw new IllegalArgumentException("retention analysis requires resolved bridge roots");
        }
        var analyzer = new BridgeRetentionAnalyzer(program, facts);
        analyzer.solve();
        Map<BridgeCallableId, BridgeProof<BridgeRetentionContract>> result = new LinkedHashMap<>();
        for (var root : checked.roots()) {
            Summary body = analyzer.summary(analyzer.functions.get(root.callable().linkage()));
            Set<Failure> failures = new LinkedHashSet<>(body.failures());
            Set<Store> stores = new LinkedHashSet<>(body.stores());
            BridgeCallTargets.Initializers initializers = analyzer.callTargets.initializers(root.callable().owner());
            if (!initializers.complete()) failures.add(new Failure(BridgeProof.Status.UNKNOWN,
                    "unresolved entry initialization: " + root.callable().owner()));
            for (IrFunction initializer : initializers.targets()) {
                Summary initialization = analyzer.summary(initializer);
                failures.addAll(initialization.failures());
                stores.addAll(initialization.stores());
            }
            result.put(root.callable(), analyzer.contract(stores, failures));
        }
        return Map.copyOf(result);
    }

    /** Exact literal/null returns only; does not authorize cleanup or waive effects. */
    public static Set<BridgeCallableId> immortalStringResults(IrProgram program, BridgeRootSet roots) {
        return stringResultsFrom(program, roots, Set.of(Kind.NULL, Kind.IMMORTAL));
    }

    /** Non-fresh String origins only; consumers must separately prove the owner's lifetime. */
    public static Set<BridgeCallableId> borrowedStringResults(IrProgram program, BridgeRootSet roots) {
        return stringResultsFrom(program, roots, Set.of(Kind.NULL, Kind.IMMORTAL, Kind.INPUT, Kind.LOADED));
    }

    private static Set<BridgeCallableId> stringResultsFrom(IrProgram program, BridgeRootSet roots, Set<Kind> kinds) {
        var checked = roots.revalidate(program);
        if (!checked.resolved()) throw new IllegalArgumentException("String results require resolved roots");
        var analyzer = new BridgeRetentionAnalyzer(program, null);
        analyzer.solve();
        Set<BridgeCallableId> result = new LinkedHashSet<>();
        for (var root : checked.roots()) {
            if (!root.callable().result().equals(IrType.reference("ironwood.lang.String"))) continue;
            var origins = analyzer.summary(analyzer.functions.get(root.callable().linkage())).returns();
            if (!origins.isEmpty() && origins.stream().allMatch(origin -> kinds.contains(origin.kind()))) result.add(root.callable());
        }
        return Set.copyOf(result);
    }

    private BridgeProof<BridgeRetentionContract> contract(Set<Store> stores, Set<Failure> failures) {
        Map<SlotKey, Set<Integer>> inputs = new LinkedHashMap<>();
        Set<SlotKey> clears = new LinkedHashSet<>();
        Map<SlotKey, Set<BridgeRetentionContract.Site>> sites = new LinkedHashMap<>();
        for (Store store : stores) {
            // Existing whole-program ownership proves that these private fields
            // contain internal storage, never independently retained input roots.
            if (ownedFields.contains(store.field())
                    && (store.value().kind() == Kind.FRESH || store.value().kind() == Kind.NULL)) continue;
            // A newly allocated holder starts with no incoming-root dependency.
            // Initializing it with null/immortal data cannot retain an entry input.
            // Any store of an input or loaded reference into it remains unproved.
            if (store.holder().kind() == Kind.FRESH
                    && (store.value().kind() == Kind.NULL || store.value().kind() == Kind.IMMORTAL)) continue;
            if (store.holder().kind() != Kind.INPUT) {
                failures.add(new Failure(BridgeProof.Status.REJECTED,
                        "retention destination is not a known entry root input at " + store.site()));
                continue;
            }
            if (store.value().kind() == Kind.LOADED) {
                failures.add(new Failure(BridgeProof.Status.REJECTED,
                        "copying a loaded slot value into retaining storage is unsupported at " + store.site()));
                continue;
            }
            if (store.value().kind() != Kind.INPUT && store.value().kind() != Kind.NULL) {
                failures.add(new Failure(BridgeProof.Status.UNKNOWN,
                        "retained value is not null or a known entry input at " + store.site()));
                continue;
            }
            SlotKey key = new SlotKey(store.holder().input(), store.field());
            inputs.computeIfAbsent(key, ignored -> new LinkedHashSet<>());
            sites.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(store.site());
            if (store.value().kind() == Kind.NULL) clears.add(key);
            else inputs.get(key).add(store.value().input());
        }
        if (!failures.isEmpty()) {
            String reasons = failures.stream().map(Failure::reason).sorted().distinct()
                    .reduce((left, right) -> left + "\n" + right).orElseThrow();
            return failures.stream().anyMatch(failure -> failure.status() == BridgeProof.Status.REJECTED)
                    ? BridgeProof.rejected(reasons) : BridgeProof.unknown(reasons);
        }
        List<BridgeRetentionContract.Slot> slots = inputs.entrySet().stream()
                .sorted(java.util.Comparator.comparing((Map.Entry<SlotKey, Set<Integer>> entry) -> entry.getKey().holder())
                        .thenComparing(entry -> entry.getKey().field().ownerClass())
                        .thenComparing(entry -> entry.getKey().field().name()))
                .map(entry -> new BridgeRetentionContract.Slot(entry.getKey().holder(), entry.getKey().field(),
                        entry.getValue(), clears.contains(entry.getKey()), sites.get(entry.getKey()).stream()
                        .sorted(java.util.Comparator.comparing(BridgeRetentionContract.Site::toString)).toList()))
                .toList();
        return BridgeProof.proved(new BridgeRetentionContract(slots),
                "complete supported reference-store attribution; root ownership and acyclicity remain separate obligations");
    }

    private Summary summary(IrFunction function) {
        return summaries.get(function.linkageName());
    }

    private void solve() {
        functions.keySet().forEach(name -> summaries.put(name, new Summary(Set.of(), Set.of(), Set.of())));
        // The lattice is finite: resolved inputs/origin categories and typed
        // store sites. Resolve cyclic data flow before introducing uncertainty
        // for values that still have no producer; then propagate that uncertainty.
        for (boolean complete : List.of(false, true)) {
            boolean changed;
            do {
                changed = false;
                for (IrFunction function : functions.values()) {
                    Summary before = summary(function);
                    Summary next = summarize(function, complete);
                    Set<Origin> returned = new LinkedHashSet<>(before.returns());
                    returned.addAll(next.returns());
                    Set<Store> stores = new LinkedHashSet<>(before.stores());
                    stores.addAll(next.stores());
                    Set<Failure> failures = new LinkedHashSet<>(before.failures());
                    failures.addAll(next.failures());
                    Summary joined = new Summary(returned, stores, failures);
                    changed |= !joined.equals(before);
                    summaries.put(function.linkageName(), joined);
                }
            } while (changed);
        }
    }

    private Summary summarize(IrFunction function, boolean complete) {
        List<IrInstruction> instructions = new ArrayList<>();
        for (IrBasicBlock block : controlFlow.blocks(function)) {
            instructions.addAll(block.instructions());
            if (block.terminator() instanceof IrInvokeTerminator invoke) instructions.add(invoke.call());
        }
        Map<Integer, Set<Origin>> values = new LinkedHashMap<>();
        for (int index = 0; index < function.parameters().size(); index++) {
            values.put(function.parameters().get(index).value().id(), Set.of(new Origin(Kind.INPUT, index)));
        }
        Map<IrInstruction, BridgeCallTargets.Call> calls = new LinkedHashMap<>();
        for (IrInstruction instruction : instructions) {
            BridgeCallTargets.Call call = callTargets.resolve(instruction);
            if (instruction instanceof IrFreeInstruction free) call = callTargets.cleanup(free.allocation(), false);
            if (instruction instanceof IrRollbackInstruction rollback) call = callTargets.cleanup(rollback.allocation(), true);
            if (call != null) {
                calls.put(instruction, call);
            }
        }
        // Resolve local forward/loop references before filling missing producers.
        for (boolean fillUnknown : complete ? List.of(false, true) : List.of(false)) {
            boolean changed;
            do {
                changed = false;
                for (IrInstruction instruction : instructions) {
                    if (instruction instanceof IrReferenceConversionInstruction conversion) {
                        changed |= merge(values, conversion.result(), fillUnknown ? completeOrigins(conversion.value(), values)
                                : origins(conversion.value(), values));
                    } else if (instruction instanceof IrPhiInstruction phi) {
                        for (IrPhiIncoming incoming : phi.incoming()) {
                            changed |= merge(values, phi.result(), fillUnknown ? completeOrigins(incoming.value(), values)
                                    : origins(incoming.value(), values));
                        }
                    } else if (instruction instanceof IrFieldLoadInstruction load && load.result().type().isReference()) {
                        changed |= merge(values, load.result(), Set.of(Origin.of(Kind.LOADED)));
                    } else if (instruction instanceof IrAllocateInstruction allocation) {
                        changed |= merge(values, allocation.result(), Set.of(Origin.of(Kind.FRESH)));
                    } else if (instruction instanceof IrArrayAllocateInstruction allocation) {
                        changed |= merge(values, allocation.result(), Set.of(Origin.of(Kind.FRESH)));
                    } else if (freshStringResult(instruction) != null) {
                        changed |= merge(values, freshStringResult(instruction), Set.of(Origin.of(Kind.FRESH)));
                    } else if (instruction instanceof IrArrayLoadInstruction load && load.result().type().isReference()) {
                        changed |= merge(values, load.result(), Set.of(Origin.of(Kind.LOADED)));
                    } else if (instruction instanceof IrStaticFieldLoadInstruction load && load.result().type().isReference()) {
                        changed |= merge(values, load.result(), Set.of(Origin.of(Kind.UNKNOWN)));
                    } else if (calls.containsKey(instruction)) {
                        BridgeCallTargets.Call call = calls.get(instruction);
                        if (call.result().isPresent() && call.result().orElseThrow().type().isReference()) {
                            Set<Origin> returned = new LinkedHashSet<>();
                            if (!call.complete()) returned.add(Origin.of(Kind.UNKNOWN));
                            for (IrFunction target : call.targets()) {
                                returned.addAll(substitute(summary(target).returns(), call.arguments(), values, fillUnknown));
                            }
                            changed |= merge(values, call.result().orElseThrow(), returned);
                        }
                    }
                }
            } while (changed);
        }

        Set<Store> stores = new LinkedHashSet<>();
        Set<Failure> failures = new LinkedHashSet<>();
        for (IrInstruction instruction : instructions) {
            var site = new BridgeRetentionContract.Site(function.linkageName(), function.sourceFileName(),
                    instruction.sourceSpan());
            if (instruction instanceof IrFieldStoreInstruction store && store.field().type().isReference()) {
                for (Origin holder : complete ? completeOrigins(store.receiver(), values) : origins(store.receiver(), values)) {
                    for (Origin value : complete ? completeOrigins(store.value(), values) : origins(store.value(), values)) {
                        stores.add(new Store(holder, store.field(), value, site));
                    }
                }
            } else if (calls.containsKey(instruction)) {
                BridgeCallTargets.Call call = calls.get(instruction);
                if (!call.complete()) failures.add(new Failure(BridgeProof.Status.UNKNOWN, "unresolved call at " + site));
                for (IrFunction target : call.targets()) {
                    Summary callee = summary(target);
                    failures.addAll(callee.failures());
                    for (Store store : callee.stores()) {
                        for (Origin holder : substitute(Set.of(store.holder()), call.arguments(), values, complete)) {
                            for (Origin value : substitute(Set.of(store.value()), call.arguments(), values, complete)) {
                                stores.add(new Store(holder, store.field(), value, store.site()));
                            }
                        }
                    }
                }
            } else if (instruction instanceof IrStaticFieldStoreInstruction store && enumPublication(function, store)) {
                // This exact compiler-owned singleton publication cannot retain an entry input.
            } else if (instruction instanceof IrStaticFieldStoreInstruction store && store.field().type().isReference()
                    || instruction instanceof IrArrayStoreInstruction storeArray && storeArray.value().type().isReference()) {
                failures.add(new Failure(BridgeProof.Status.REJECTED, "untracked static/array reference store at " + site));
            } else if (!observing(instruction)) {
                failures.add(new Failure(BridgeProof.Status.UNKNOWN,
                        "unclassified reference effect " + instruction.getClass().getSimpleName() + " at " + site));
            }
        }
        Set<Origin> returned = new LinkedHashSet<>();
        for (IrBasicBlock block : controlFlow.blocks(function)) {
            if (block.terminator() instanceof IrReturnTerminator result && result.value().isPresent()
                    && result.value().orElseThrow().type().isReference()) {
                returned.addAll(complete ? completeOrigins(result.value().orElseThrow(), values)
                        : origins(result.value().orElseThrow(), values));
            }
        }
        return new Summary(returned, stores, failures);
    }

    private boolean enumPublication(IrFunction function, IrStaticFieldStoreInstruction store) {
        var field = store.field();
        return function.kind() == IrCallableKind.CLASS_INITIALIZER
                && function.ownerClass().equals(field.ownerClass()) && staticFields.contains(field)
                && field.isFinal() && field.initialValue() instanceof IrEnumConstant constant
                && constant.type().equals(IrType.reference(field.ownerClass()))
                && constant.constantName().equals(field.name()) && constant.equals(store.value());
    }

    /** Fixed runtime copies/rendering only; virtual producer overrides remain resolved calls. */
    private static IrValueReference freshStringResult(IrInstruction instruction) {
        return switch (instruction) {
            case IrStringCopyInstruction copy -> copy.result();
            case IrStringConcatInstruction concat -> concat.result();
            case IrStringFromCharsInstruction copy -> copy.result();
            case IrStringFromCharRangeInstruction copy -> copy.result();
            case IrStringFromUtf8Instruction copy -> copy.result();
            case IrObjectToStringInstruction text -> text.result();
            case IrThrowableDescriptionInstruction text -> text.result();
            default -> null;
        };
    }

    private static boolean observing(IrInstruction instruction) {
        // These results own inline UTF-16 storage and retain no source reference.
        if (freshStringResult(instruction) != null) return true;
        return switch (instruction) {
            case IrAllocateInstruction ignored -> true;
            case IrArrayAllocateInstruction ignored -> true;
            case IrFieldLoadInstruction ignored -> true;
            case IrFieldStoreInstruction store -> !store.field().type().isReference();
            case IrStaticFieldLoadInstruction ignored -> true;
            case IrStaticFieldStoreInstruction store -> !store.field().type().isReference();
            case IrArrayLoadInstruction ignored -> true;
            case IrArrayStoreInstruction store -> !store.value().type().isReference();
            case IrReferenceConversionInstruction ignored -> true;
            case IrPhiInstruction ignored -> true;
            case IrBinaryInstruction ignored -> true;
            case IrUnaryInstruction ignored -> true;
            case IrNumericConversionInstruction ignored -> true;
            case IrNullCheckInstruction ignored -> true;
            case IrArrayBoundsCheckInstruction ignored -> true;
            case IrArrayLengthCheckInstruction ignored -> true;
            case IrArrayLengthInstruction ignored -> true;
            case IrInstanceOfInstruction ignored -> true;
            case IrTypeInitializedInstruction ignored -> true;
            case IrIdentityHashCodeInstruction ignored -> true;
            case IrStringCharAtInstruction ignored -> true;
            // Fixed helpers only release proved fresh String storage, without
            // calling a producer destructor. Reclamation is checked separately.
            case IrReleaseOwnedToStringResultInstruction ignored -> true;
            case IrReleaseOwnedThrowableMessageInstruction ignored -> true;
            // Deallocation does not retain a reference. Ordinary free/rollback
            // separately substitute every possible destructor effect above.
            case IrRawDeallocateInstruction ignored -> true;
            case IrMathUnaryInstruction ignored -> true;
            case IrMathBinaryInstruction ignored -> true;
            case IrExceptionLandingPadInstruction ignored -> true;
            case IrExceptionCaughtInstruction ignored -> true;
            case IrAllocationCountInstruction ignored -> true;
            case IrLiveAllocationCountInstruction ignored -> true;
            // Capture stores only copied native PCs in private trace storage;
            // release frees that storage, not the Throwable or an input root.
            case IrThrowableTraceInstruction trace -> trace.operation() == IrThrowableTraceInstruction.Operation.CAPTURE
                    || trace.operation() == IrThrowableTraceInstruction.Operation.RELEASE
                    || trace.operation() == IrThrowableTraceInstruction.Operation.COMMON;
            default -> false;
        };
    }

    private static Set<Origin> substitute(Set<Origin> source, List<IrOperand> arguments,
                                          Map<Integer, Set<Origin>> values, boolean complete) {
        Set<Origin> result = new LinkedHashSet<>();
        for (Origin origin : source) {
            if (origin.kind() == Kind.INPUT) {
                result.addAll(origin.input() < arguments.size()
                        ? complete ? completeOrigins(arguments.get(origin.input()), values)
                        : origins(arguments.get(origin.input()), values) : Set.of(Origin.of(Kind.UNKNOWN)));
            } else result.add(origin);
        }
        return result;
    }

    private static Set<Origin> origins(IrOperand value, Map<Integer, Set<Origin>> values) {
        if (value instanceof IrNull) return Set.of(Origin.of(Kind.NULL));
        if (value instanceof IrEnumConstant || value instanceof IrImmortalObject || value instanceof IrStringConstant) {
            return Set.of(Origin.of(Kind.IMMORTAL));
        }
        if (value instanceof IrValueReference reference) return reference.type().equals(IrType.EXCEPTION)
                ? Set.of(Origin.of(Kind.UNKNOWN)) : values.getOrDefault(reference.id(), Set.of());
        return Set.of(Origin.of(Kind.UNKNOWN));
    }

    private static Set<Origin> completeOrigins(IrOperand value, Map<Integer, Set<Origin>> values) {
        Set<Origin> result = origins(value, values);
        return result.isEmpty() ? Set.of(Origin.of(Kind.UNKNOWN)) : result;
    }

    private static boolean merge(Map<Integer, Set<Origin>> values, IrValueReference value, Set<Origin> added) {
        if (!value.type().isReference() || added.isEmpty()) return false;
        Set<Origin> merged = new LinkedHashSet<>(values.getOrDefault(value.id(), Set.of()));
        if (!merged.addAll(added)) return false;
        values.put(value.id(), Set.copyOf(merged));
        return true;
    }
}
