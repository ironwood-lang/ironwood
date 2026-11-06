// SPDX-License-Identifier: MIT OR Apache-2.0
package ironwood.compiler.bridge;

import ironwood.compiler.CompilationArtifact;
import ironwood.compiler.ir.*;
import ironwood.compiler.semantic.*;
import java.util.*;
import java.util.stream.Collectors;

/** Exact proxy/context and JNI completion capabilities for the managed image. */
public final class BridgeGcCallbacks {
    private final BridgeListenerProxies listeners;
    private final BridgeCallbackCarrierEntries carriers;
    private final BridgeCallbackCarrierPolicy completion;
    private final BridgeCallbackContextLowering.Result context;
    private final Map<BridgeCallableId, BridgeRetentionContract> slots;
    private final Map<String, IrFunction> factories;
    private final Map<IrField, IrFunction> detachers;

    private BridgeGcCallbacks(BridgeListenerProxies listeners, BridgeCallbackCarrierEntries carriers,
            BridgeCallbackCarrierPolicy completion, BridgeCallbackContextLowering.Result context,
            Map<BridgeCallableId, BridgeRetentionContract> slots, Map<String, IrFunction> factories, Map<IrField, IrFunction> detachers) {

        this.listeners = listeners;
        this.carriers = carriers;
        this.completion = completion;
        this.context = context;
        this.slots = Map.copyOf(slots);
        this.factories = Map.copyOf(factories);
        this.detachers = Map.copyOf(detachers);
    }

    public BridgeListenerProxies listeners() { return this.listeners; }
    public BridgeCallbackCarrierEntries carriers() { return this.carriers; }
    public BridgeCallbackCarrierPolicy completion() { return this.completion; }
    public BridgeCallbackContextLowering.Result context() { return this.context; }
    public Map<BridgeCallableId, BridgeRetentionContract> slots() { return this.slots; }
    public Map<String, IrFunction> factories() { return this.factories; }
    public Map<IrField, IrFunction> detachers() { return this.detachers; }
    public List<IrField> fields() {

        return this.detachers.keySet().stream().sorted(Comparator.comparing(IrField::ownerClass).thenComparing(IrField::name)).toList();
    }
    public boolean retaining(BridgeCallableId id) {

        return this.slots.get(id).slots().stream().anyMatch(slot -> !slot.valueInputs().isEmpty());
    }
    public boolean listener(IrType type) {

        return this.factories.containsKey(type.referenceName());
    }
    public boolean context(BridgeCallableId id) {

        return this.context.specializations().containsKey(id.linkage());
    }
    public List<IrFunction> support() {

        var functions = new ArrayList<>(this.carriers.functions());
        this.factories.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> functions.add(entry.getValue()));
        for (var field : fields()) functions.add(this.detachers.get(field));
        return List.copyOf(functions);
    }

    public static BridgeGcCallbacks prove(CompilationArtifact artifact, BridgeExportSurface surface,
            BridgeListenerProxies listeners, BridgeCallbackCarrierSources carrier) {

        listeners.validateArtifact(artifact);
        var program = artifact.program().orElseThrow();
        var roots = surface.roots();
        var selected = surface.types().stream().filter(type -> type.kind() == BridgeApiFacts.Kind.INTERFACE)
                .map(BridgeApiFacts.Type::binaryName).collect(Collectors.toSet());
        if (!selected.equals(listeners.proxies().stream().map(proxy -> proxy.listener().binaryName()).collect(Collectors.toSet())))
            throw new IllegalArgumentException("managed callbacks require the complete selected proxy inventory");
        var context = BridgeCallbackContextLowering.lower(program, roots, BridgeCallbackReachability.analyze(program));
        var carriers = BridgeCallbackCarrierEntries.create(artifact, carrier);
        var completion = BridgeCallbackCarrierPolicy.proveManaged(artifact, carrier, roots, listeners);
        for (var root : roots.roots()) if (!completion.reclaims(root.callable()))
            throw new IllegalArgumentException("managed callback carrier lacks invocation completion: " + root.callable().linkage()
                    + " " + BridgeCallbackCarrierLifetime.analyzeManaged(artifact, listeners,
                            BridgeRootSet.resolve(program, List.of(root.callable()))).retentionReasons());
        var attributed = BridgeRetentionAnalyzer.managedListeners(artifact, listeners, roots);
        var slots = new LinkedHashMap<BridgeCallableId, BridgeRetentionContract>();
        for (var root : roots.roots()) {
            var proof = attributed.get(root.callable());
            if (proof.status() != BridgeProof.Status.PROVED) throw new IllegalArgumentException(proof.reason());
            var contract = proof.contract().orElseThrow();
            for (var slot : contract.slots()) {
                var holder = root.callable().parameters().get(slot.holderInput());
                var declaration = artifact.bridgeApiFacts().orElseThrow().types().get(slot.field().ownerClass());
                if (!BridgeGenericDomain.storage(holder).equals(IrType.reference(slot.field().ownerClass())) || declaration == null
                        || declaration.kind() != BridgeApiFacts.Kind.CLASS || !declaration.finalType()
                        || !selected.contains(slot.field().type().referenceName()) || slot.valueInputs().stream()
                        .anyMatch(input -> !root.callable().parameters().get(input).equals(slot.field().type())))
                    throw new IllegalArgumentException("listener slot requires exact holder and listener input attribution");
            }
            slots.put(root.callable(), contract);
        }
        var factories = new LinkedHashMap<String, IrFunction>();
        for (var proxy : listeners.proxies()) {
            var constructors = program.functions().stream().filter(function -> function.ownerClass().equals(proxy.binaryName())
                    && function.constructor()).toList();
            if (constructors.size() != 1) throw new IllegalArgumentException("listener proxy constructor inventory");
            var root = BridgeRootSet.resolve(program, List.of(BridgeCallableId.of(constructors.getFirst()))).roots().getFirst();
            var function = BridgeRootEntryLowering.lower(root, "ironwood_bridge_gc_proxy_" + factories.size(),
                    new BridgeRetentionContract(List.of()), true);
            if (program.functions().stream().anyMatch(candidate -> candidate.linkageName().equals(function.linkageName())))
                throw new IllegalArgumentException("managed listener factory symbol collision");
            factories.put(proxy.listener().binaryName(), function);
        }
        var detachers = new LinkedHashMap<IrField, IrFunction>();
        var fields = slots.values().stream().flatMap(contract -> contract.slots().stream()).map(BridgeRetentionContract.Slot::field)
                .distinct().sorted(Comparator.comparing(IrField::ownerClass).thenComparing(IrField::name)).toList();
        for (var field : fields) {
            var function = detach(field, "ironwood_bridge_gc_detach_" + detachers.size());
            if (program.functions().stream().anyMatch(candidate -> candidate.linkageName().equals(function.linkageName())))
                throw new IllegalArgumentException("managed listener detach symbol collision");
            detachers.put(field, function);
        }
        return new BridgeGcCallbacks(listeners, carriers, completion, context, slots, factories, detachers);
    }

    private static IrFunction detach(IrField field, String name) {

        var span = field.sourceSpan();
        var holder = new IrValueReference(0, IrType.reference(field.ownerClass()), span);
        var expected = new IrValueReference(1, field.type(), span);
        var current = new IrValueReference(2, field.type(), span);
        var matches = new IrValueReference(3, IrType.I1, span);
        return new IrFunction(field.ownerClass(), "<registration-detach>", name, IrType.I32,
                List.of(new IrParameter("holder", holder, span), new IrParameter("expected", expected, span)),
                List.of(new IrBasicBlock("entry", List.of(new IrFieldLoadInstruction(current, holder, field, span),
                        new IrBinaryInstruction(matches, IrBinaryOperator.EQUAL, current, expected, span)),
                        new IrBranch(matches, "clear", "unchanged", span), span),
                        new IrBasicBlock("clear", List.of(new IrFieldStoreInstruction(holder, field, new IrNull(field.type(), span), span)),
                                new IrReturnTerminator(Optional.of(new IrConstant(IrType.I32, 1, span)), span), span),
                        new IrBasicBlock("unchanged", List.of(), new IrReturnTerminator(Optional.of(new IrConstant(IrType.I32, 0, span)), span), span)),
                span, "<java-bridge>", IrCallableKind.METHOD);
    }
}
