// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.BridgeFinalRootRetention;
import ironwood.compiler.CompilationArtifact;
import ironwood.compiler.bridge.*;
import ironwood.compiler.ir.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Invocation guard obligations for proved roots, not listener-slot or facade admission. */
public final class BridgeOwnedCallbackProof {
    private final BridgeEntryModule storage;
    private final BridgeFinalRootRetention lifetime;
    private final BridgeSynchronousCallbackProof invocation;
    private final Map<BridgeCallableId, List<Integer>> guardedInputs;

    private BridgeOwnedCallbackProof(BridgeEntryModule storage, BridgeFinalRootRetention lifetime,
            BridgeSynchronousCallbackProof invocation, Map<BridgeCallableId, List<Integer>> guardedInputs) {
        this.storage = storage;
        this.lifetime = lifetime;
        this.invocation = invocation;
        this.guardedInputs = Map.copyOf(guardedInputs);
    }

    public Map<BridgeCallableId, List<Integer>> guardedInputs() { return guardedInputs; }
    public Set<String> closure() { return invocation.closure(); }
    public boolean matches(CompilationArtifact artifact, BridgeRootSet roots,
            BridgeEntryModule requestedStorage, BridgeFinalRootRetention requestedLifetime) {
        return storage == requestedStorage && lifetime == requestedLifetime && storage.matchesOriginal(artifact)
                && lifetime.matches(storage, lifetime.program()) && invocation.matches(artifact, roots);
    }

    public static BridgeOwnedCallbackProof prove(CompilationArtifact artifact, BridgeListenerProxies proxies,
            BridgeRootSet roots, BridgeEntryModule storage, BridgeFinalRootRetention lifetime) {
        proxies.validateArtifact(artifact);
        if (!storage.matchesOriginal(artifact) || !lifetime.matches(storage, lifetime.program())) {
            throw new IllegalArgumentException("owned callbacks require matching final storage proofs");
        }
        var program = artifact.program().orElseThrow();
        var owners = lifetime.protocol().constructedRootTypes();
        if (owners.isEmpty() || storage.entries().stream().anyMatch(entry -> entry.root().callable().kind() != IrCallableKind.CONSTRUCTOR)) {
            throw new IllegalArgumentException("owned callbacks require constructor-only storage roots");
        }
        Set<IrType> listeners = proxies.proxies().stream().map(proxy -> IrType.reference(proxy.listener().binaryName()))
                .collect(Collectors.toUnmodifiableSet());
        var api = artifact.bridgeApiFacts().orElseThrow();
        if (!api.matches(program)) throw new IllegalArgumentException("owned callbacks require final declaration facts");
        for (var owner : owners) {
            var declaration = api.types().get(owner.referenceName());
            var layout = program.classes().stream().filter(type -> type.name().equals(owner.referenceName())).findFirst().orElseThrow();
            if (declaration == null || !declaration.finalType() || declaration.kind() != BridgeApiFacts.Kind.CLASS
                    || declaration.throwable() || declaration.generic() || !layout.interfaces().isEmpty()
                    || !layout.superclass().orElse("").equals("ironwood.lang.Object")
                    || layout.fields().stream().anyMatch(field -> !field.ownerClass().equals(owner.referenceName())
                    || !(field.type().isPrimitive() || listeners.contains(field.type())))) {
                throw new IllegalArgumentException("owned callbacks require exact primitive/listener root layouts");
            }
        }
        proveEmptyListeners(program, storage, listeners);
        var invocation = BridgeSynchronousCallbackProof.proveOwned(artifact, proxies, roots, owners);
        var guarded = new LinkedHashMap<BridgeCallableId, List<Integer>>();
        for (var root : roots.revalidate(program).roots()) {
            var indices = new ArrayList<Integer>();
            for (int index = 0; index < root.callable().parameters().size(); index++) {
                if (owners.contains(root.callable().parameters().get(index))) indices.add(index);
            }
            if (indices.isEmpty()) throw new IllegalArgumentException("owned callback requires a guarded owner input");
            guarded.put(root.callable(), List.copyOf(indices));
        }
        return new BridgeOwnedCallbackProof(storage, lifetime, invocation, guarded);
    }

    private static void proveEmptyListeners(IrProgram program, BridgeEntryModule storage, Set<IrType> listeners) {
        var calls = new BridgeCallTargets(program);
        var pending = new ArrayList<IrFunction>();
        for (var entry : storage.entries()) {
            var id = entry.root().callable();
            // The first constructor parameter is its fresh implicit receiver.
            if (id.parameters().stream().skip(1).anyMatch(type -> !type.isPrimitive())) {
                throw new IllegalArgumentException("owned callback constructors require primitive inputs");
            }
            pending.add(calls.function(id.linkage()));
            var initialization = calls.initializers(id.owner());
            if (!initialization.complete()) throw new IllegalArgumentException("unresolved owner initialization");
            pending.addAll(initialization.targets());
        }
        var visited = new LinkedHashSet<String>();
        for (int index = 0; index < pending.size(); index++) {
            var function = pending.get(index);
            if (!visited.add(function.linkageName())) continue;
            for (var block : function.blocks()) {
                var operations = Stream.concat(block.instructions().stream(), block.terminator() instanceof IrInvokeTerminator invoke
                        ? Stream.of(invoke.call()) : Stream.empty()).toList();
                for (var operation : operations) {
                    var edge = calls.resolve(operation);
                    if (edge != null) {
                        if (!edge.complete()) throw new IllegalArgumentException("owner construction requires a complete native closure");
                        pending.addAll(edge.targets());
                    }
                    if (operation instanceof IrFieldStoreInstruction store && listeners.contains(store.field().type())
                            && !(store.value() instanceof IrNull)) {
                        throw new IllegalArgumentException("owned callback listeners must start empty");
                    }
                }
            }
        }
    }
}
