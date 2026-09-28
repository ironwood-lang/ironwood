// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.BridgeFinalRootRetention;
import ironwood.compiler.CompilationArtifact;
import ironwood.compiler.bridge.*;
import ironwood.compiler.ir.IrType;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/** Callback-free owner operations with complete listener writes and no independent storage effects. */
public final class BridgeOwnedListenerSlots {
    private final BridgeEntryModule storage;
    private final BridgeFinalRootRetention lifetime;
    private final BridgeListenerSlotEntries entries;
    private final Set<String> closure;

    private BridgeOwnedListenerSlots(BridgeEntryModule storage, BridgeFinalRootRetention lifetime,
            BridgeListenerSlotEntries entries, Set<String> closure) {
        this.storage = storage;
        this.lifetime = lifetime;
        this.entries = entries;
        this.closure = Set.copyOf(closure);
    }

    public BridgeListenerSlotEntries entries() { return entries; }
    public Set<String> closure() { return closure; }
    public boolean matches(CompilationArtifact artifact, BridgeRootSet roots,
            BridgeEntryModule requestedStorage, BridgeFinalRootRetention requestedLifetime) {
        return storage == requestedStorage && lifetime == requestedLifetime && storage.matchesOriginal(artifact)
                && lifetime.matches(storage, lifetime.program()) && entries.matches(artifact, roots);
    }

    public static BridgeOwnedListenerSlots prove(CompilationArtifact artifact, BridgeListenerProxies proxies,
            BridgeRootSet requested, BridgeEntryModule storage, BridgeFinalRootRetention lifetime) {
        var owners = BridgeOwnedCallbackProof.validateStorage(artifact, proxies, storage, lifetime);
        var program = artifact.program().orElseThrow();
        var roots = requested.revalidate(program);
        var entries = BridgeListenerSlotEntries.create(artifact, proxies, roots);
        var facts = artifact.bridgeConstructionFacts().orElseThrow();
        Set<IrType> listeners = proxies.proxies().stream().map(proxy -> IrType.reference(proxy.listener().binaryName()))
                .collect(Collectors.toUnmodifiableSet());
        for (var entry : entries.entries()) {
            var id = entry.callable();
            for (int index = 0; index < id.parameters().size(); index++) {
                var type = id.parameters().get(index);
                if (!(type.isPrimitive() || listeners.contains(type) || owners.contains(type))) {
                    throw new IllegalArgumentException("owner slot operation has an unsupported input");
                }
                if (owners.contains(type) && !facts.borrowsInput(id, index)) {
                    throw new IllegalArgumentException("owner slot operation can publish an owner input");
                }
            }
            for (var slot : entry.retention().slots()) {
                if (!owners.contains(id.parameters().get(slot.holderInput()))) {
                    throw new IllegalArgumentException("listener slot holder lacks final root storage proof");
                }
            }
        }
        var exposed = new LinkedHashSet<>(owners);
        proxies.proxies().forEach(proxy -> exposed.add(IrType.reference(proxy.binaryName())));
        for (var type : exposed) {
            var proof = BridgeNonReclamationAnalyzer.analyze(program, roots, type, facts);
            if (proof.status() != BridgeProof.Status.PROVED) throw new IllegalArgumentException(proof.reason());
        }
        var closure = BridgeSynchronousCallbackProof.verifyClosure(artifact, proxies, roots, owners, true);
        return new BridgeOwnedListenerSlots(storage, lifetime, entries, closure);
    }
}
