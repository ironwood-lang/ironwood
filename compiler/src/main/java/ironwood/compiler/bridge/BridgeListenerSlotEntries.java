// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import ironwood.compiler.CompilationArtifact;
import ironwood.compiler.ir.*;
import ironwood.compiler.semantic.BridgeCallbackReachability;
import ironwood.compiler.semantic.BridgeCallTargets;
import ironwood.compiler.semantic.BridgeRetentionAnalyzer;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Complete listener-field writes and protected final snapshots, not holder lifetime admission. */
public final class BridgeListenerSlotEntries {
    public record Entry(BridgeCallableId callable, BridgeRetentionContract retention, IrFunction function) {}

    private final IrProgram original;
    private final BridgeRootSet roots;
    private final List<Entry> entries;

    private BridgeListenerSlotEntries(IrProgram original, BridgeRootSet roots, List<Entry> entries) {
        this.original = original;
        this.roots = roots;
        this.entries = List.copyOf(entries);
    }

    public List<Entry> entries() { return entries; }
    public boolean matches(CompilationArtifact artifact, BridgeRootSet requested) {
        return artifact.valid() && artifact.program().filter(original::equals).isPresent()
                && roots.equals(requested.revalidate(original));
    }

    public static BridgeListenerSlotEntries create(CompilationArtifact artifact, BridgeListenerProxies proxies,
            BridgeRootSet requested) {
        proxies.validateArtifact(artifact);
        var program = artifact.program().orElseThrow();
        var roots = requested.revalidate(program);
        if (!roots.resolved()) throw new IllegalArgumentException("listener slot entries require resolved roots");
        var reachability = BridgeCallbackReachability.analyze(program).entries(roots);
        if (reachability.values().stream().anyMatch(effect -> effect.requiresContext())) {
            throw new IllegalArgumentException("listener mutation requires complete callback-free entry effects");
        }
        var facts = artifact.bridgeConstructionFacts().orElseThrow();
        var writes = BridgeRetentionAnalyzer.analyze(program, roots, facts);
        var targets = new BridgeCallTargets(program);
        Set<IrType> listeners = proxies.proxies().stream().map(proxy -> IrType.reference(proxy.listener().binaryName()))
                .collect(Collectors.toUnmodifiableSet());
        var result = new ArrayList<Entry>();
        for (var root : roots.roots()) {
            var id = root.callable();
            if (id.kind() != IrCallableKind.METHOD || !(id.result().isPrimitive() || id.result().equals(IrType.VOID))) {
                throw new IllegalArgumentException("listener mutation requires an ordinary primitive/void method");
            }
            var proof = writes.get(id);
            if (proof.status() != BridgeProof.Status.PROVED) throw new IllegalArgumentException(proof.reason());
            var contract = proof.contract().orElseThrow();
            for (var slot : contract.slots()) {
                var holder = id.parameters().get(slot.holderInput());
                var declaration = artifact.bridgeApiFacts().orElseThrow().types().get(slot.field().ownerClass());
                if (!holder.equals(IrType.reference(slot.field().ownerClass())) || declaration == null || !declaration.finalType()) {
                    throw new IllegalArgumentException("listener slot requires an exact holder input");
                }
                if (!listeners.contains(slot.field().type()) || slot.valueInputs().stream()
                        .anyMatch(input -> !id.parameters().get(input).equals(slot.field().type()))) {
                    throw new IllegalArgumentException("listener slot requires null or an exact listener input");
                }
            }
            String symbol = "ironwood_bridge_listener_slots_" + result.size();
            if (program.functions().stream().anyMatch(function -> function.linkageName().equals(symbol))) {
                throw new IllegalArgumentException("listener slot entry symbol collision");
            }
            var initialization = targets.initializers(id.owner());
            if (!initialization.complete()) throw new IllegalArgumentException("listener slot initialization is unresolved");
            result.add(new Entry(id, contract, BridgeRootEntryLowering.lower(root, symbol, contract,
                    !initialization.targets().isEmpty())));
        }
        return new BridgeListenerSlotEntries(program, roots, result);
    }
}
