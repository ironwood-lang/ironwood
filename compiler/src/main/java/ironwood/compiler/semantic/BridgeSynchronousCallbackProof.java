// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.CompilationArtifact;
import ironwood.compiler.bridge.BridgeCallableId;
import ironwood.compiler.bridge.BridgeExportSurface;
import ironwood.compiler.bridge.BridgeListenerProxies;
import ironwood.compiler.bridge.BridgeProof;
import ironwood.compiler.bridge.BridgeRootSet;
import ironwood.compiler.ir.*;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Stream;

/** Bounded native-state proof, not a purity claim about Java or general object admission. */
public final class BridgeSynchronousCallbackProof {
    private final IrProgram program;
    private final BridgeRootSet roots;
    private final Set<String> closure;

    private BridgeSynchronousCallbackProof(IrProgram program, BridgeRootSet roots, Set<String> closure) {
        this.program = program;
        this.roots = roots;
        this.closure = Set.copyOf(closure);
    }

    public boolean matches(CompilationArtifact artifact, BridgeRootSet requested) {
        return artifact.valid() && artifact.program().filter(program::equals).isPresent()
                && roots.equals(requested.revalidate(program));
    }

    public Set<String> closure() { return closure; }

    public static BridgeSynchronousCallbackProof prove(CompilationArtifact artifact,
            BridgeListenerProxies proxies, BridgeRootSet requested) {
        return proveOwned(artifact, proxies, requested, Set.of());
    }

    // Only BridgeOwnedCallbackProof supplies owner types, after final storage
    // admission. This overload is not a signature-based producer permission.
    static BridgeSynchronousCallbackProof proveOwned(CompilationArtifact artifact,
            BridgeListenerProxies proxies, BridgeRootSet requested, Set<IrType> owners) {
        proxies.validateArtifact(artifact);
        var program = artifact.program().orElseThrow();
        var roots = requested.revalidate(program);
        if (!roots.resolved()) throw new IllegalArgumentException("synchronous callback requires resolved roots");
        var facts = artifact.bridgeConstructionFacts().orElseThrow();
        if (!facts.matches(program)) throw new IllegalArgumentException("synchronous callback requires final borrowing facts");
        var listeners = proxies.proxies().stream().map(proxy -> IrType.reference(proxy.listener().binaryName())).toList();
        for (var proxy : proxies.proxies()) {
            for (var method : proxy.methods()) {
                if (!(method.result().isPrimitive() || method.result().equals(IrType.VOID))
                        || method.parameters().stream().anyMatch(type -> !type.isPrimitive())) {
                    throw new IllegalArgumentException("synchronous callback requires primitive listener methods");
                }
            }
        }
        var reachability = BridgeCallbackReachability.analyze(program);
        var entries = reachability.entries(roots);
        for (var root : roots.roots()) {
            var id = root.callable();
            if ((!facts.isStatic(id) && !owners.contains(IrType.reference(id.owner()))) || id.kind() != IrCallableKind.METHOD
                    || !(id.result().isPrimitive() || id.result().equals(IrType.VOID))) {
                throw new IllegalArgumentException("synchronous callback requires static primitive/void result roots");
            }
            boolean listener = false;
            for (int index = 0; index < id.parameters().size(); index++) {
                var type = id.parameters().get(index);
                if (type.isPrimitive()) continue;
                if ((!listeners.contains(type) && !owners.contains(type)
                        && !type.equals(IrType.reference("ironwood.lang.String"))) || !facts.borrowsInput(id, index)) {
                    throw new IllegalArgumentException("synchronous callback requires borrowed listener inputs: " + id.linkage());
                }
                listener |= listeners.contains(type) || owners.contains(type);
            }
            if (!listener || !entries.get(id).foreign() || !entries.get(id).complete()) {
                throw new IllegalArgumentException("synchronous callback requires a complete callback-bearing closure");
            }
        }
        return new BridgeSynchronousCallbackProof(program, roots, verifyClosure(artifact, proxies, roots, owners, false));
    }

    /** Additional slot permission requires the caller's separate complete P0 attribution. */
    static Set<String> verifyClosure(CompilationArtifact artifact, BridgeListenerProxies proxies,
            BridgeRootSet roots, Set<IrType> owners, boolean listenerWrites) {
        proxies.validateArtifact(artifact);
        var program = artifact.program().orElseThrow();
        var facts = artifact.bridgeConstructionFacts().orElseThrow();
        var listeners = proxies.proxies().stream().map(proxy -> IrType.reference(proxy.listener().binaryName()))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        var calls = new BridgeCallTargets(program);
        var rollback = new BridgeRollbackAnalysis(program, facts);
        var approvedRollback = new LinkedHashSet<BridgeCallableId>();
        var pending = new ArrayList<IrFunction>();
        for (var root : roots.roots()) {
            var id = root.callable();
            pending.add(calls.function(id.linkage()));
            var initialization = calls.initializers(id.owner());
            if (!initialization.complete()) throw new IllegalArgumentException("unresolved synchronous callback initialization");
            pending.addAll(initialization.targets());
        }
        var boundBodies = new LinkedHashSet<String>();
        for (var function : program.functions()) {
            if (function.kind() == IrCallableKind.METHOD && proxies.proxies().stream()
                    .anyMatch(proxy -> proxy.binaryName().equals(function.ownerClass()))) {
                // validateArtifact has matched the complete canonical body and
                // source inventory. No name-based foreign borrowing exemption.
                boundBodies.add(function.linkageName());
            }
        }
        var visited = new LinkedHashSet<String>();
        for (int index = 0; index < pending.size(); index++) {
            var function = pending.get(index);
            if (!visited.add(function.linkageName())) continue;
            if (boundBodies.contains(function.linkageName())) continue;
            boolean faultConstructor = function.constructor() && BridgeExportSurface.builtinThrowableNames().contains(function.ownerClass());
            if (faultConstructor && facts.constructors().getOrDefault(BridgeCallableId.of(function),
                    BridgeProof.unknown("missing constructor facts")).status() != BridgeProof.Status.PROVED) {
                throw new IllegalArgumentException("synchronous callback native fault construction is not confined");
            }
            for (var block : function.blocks()) {
                var operations = Stream.concat(block.instructions().stream(), block.terminator() instanceof IrInvokeTerminator invoke
                        ? Stream.of(invoke.call()) : Stream.empty()).toList();
                for (var operation : operations) {
                    // The sole allowed foreign edge is inside a fully bound
                    // proxy, visited above. Java effects stay unknown everywhere.
                    if (operation instanceof IrForeignCallInstruction) throw rejected(function, operation);
                    var edge = calls.resolve(operation);
                    if (edge != null) {
                        if (!edge.complete()) throw rejected(function, operation);
                        pending.addAll(edge.targets());
                    } else if (operation instanceof IrAllocateInstruction allocation
                            && BridgeExportSurface.builtinThrowableNames().contains(allocation.className())) {
                        // Native null/arithmetic/cast fault paths construct local
                        // throwables. They are never facade roots; source free and
                        // publication remain forbidden, with ordinary exception lifetime.
                    } else if (faultConstructor && operation instanceof IrFieldStoreInstruction store
                            && store.receiver().equals(function.parameters().getFirst().value())) {
                        // Only the proved constructor initializes its own fresh
                        // exception. This cannot mutate a caught callback carrier.
                    } else if (operation instanceof IrRollbackInstruction failed
                            && provedFaultRollback(artifact, rollback, function, block, failed, approvedRollback)) {
                        // Match P0's unpublished constructor unwind and prove its
                        // complete owned-message/trace cleanup. No caught-object free.
                    } else if (!ownerValueOperation(operation, owners, listeners, listenerWrites)
                            && !localValueOperation(operation)) throw rejected(function, operation);
                }
            }
        }
        return Set.copyOf(visited);
    }

    private static boolean provedFaultRollback(CompilationArtifact artifact, BridgeRollbackAnalysis analysis,
            IrFunction function, IrBasicBlock block, IrRollbackInstruction rollback, Set<BridgeCallableId> approved) {
        if (!BridgeExportSurface.builtinThrowableNames().contains(rollback.allocation().type().referenceName())) return false;
        var cleanup = analysis.unwind(function, block, rollback);
        if (cleanup.isEmpty()) return false;
        var constructor = cleanup.orElseThrow().construction().constructor();
        if (approved.contains(constructor)) return true;
        var roots = BridgeRootSet.resolve(artifact.program().orElseThrow(), java.util.List.of(constructor));
        if (BridgeCleanupAnalyzer.analyze(artifact, roots, rollback.allocation().type(), java.util.Optional.of(constructor))
                .status() != BridgeProof.Status.PROVED) return false;
        approved.add(constructor);
        return true;
    }

    private static boolean ownerValueOperation(IrInstruction operation, Set<IrType> owners, Set<IrType> listeners, boolean listenerWrites) {
        return switch (operation) {
            case IrFieldLoadInstruction load -> owners.contains(load.receiver().type())
                    && load.receiver().type().equals(IrType.reference(load.field().ownerClass()))
                    && (load.field().type().isPrimitive() || listeners.contains(load.field().type()));
            case IrFieldStoreInstruction store -> owners.contains(store.receiver().type())
                    && store.receiver().type().equals(IrType.reference(store.field().ownerClass()))
                    && (store.field().type().isPrimitive() || listenerWrites && listeners.contains(store.field().type()));
            default -> false;
        };
    }

    private static IllegalArgumentException rejected(IrFunction function, IrInstruction operation) {
        return new IllegalArgumentException("synchronous callback native-state proof rejects "
                + operation.getClass().getSimpleName() + " in " + function.linkageName());
    }

    private static boolean localValueOperation(IrInstruction operation) {
        // No general heap/static access, allocations, cleanup, native-object arguments,
        // or exception graph edits can hide behind this primitive invocation.
        return switch (operation) {
            case IrFieldLoadInstruction load -> load.field().ownerClass().equals("ironwood.lang.String")
                    && load.receiver().type().equals(IrType.reference("ironwood.lang.String"))
                    && load.field().type().equals(IrType.I32)
                    && Set.of("utf16Length", "utf8Length").contains(load.field().name());
            case IrStringCharAtInstruction ignored -> true;
            case IrBinaryInstruction binary -> !binary.left().type().isReference() && !binary.right().type().isReference()
                    || binary.left() instanceof IrNull || binary.right() instanceof IrNull
                    || binary.left().type().equals(binary.right().type())
                    && !binary.left().type().equals(IrType.reference("ironwood.lang.Object"));
            case IrUnaryInstruction ignored -> true;
            case IrNumericConversionInstruction ignored -> true;
            case IrReferenceConversionInstruction ignored -> true;
            case IrPhiInstruction ignored -> true;
            case IrNullCheckInstruction ignored -> true;
            // One nominal proxy does not represent Java's dynamic multi-interface
            // membership. Never answer that query using its narrower native class.
            case IrInstanceOfInstruction test -> BridgeExportSurface.builtinThrowableNames().contains(test.targetTypeName())
                    || test.targetTypeName().equals("ironwood.lang.Object")
                    || test.value().type().equals(IrType.reference(test.targetTypeName()));
            case IrTypeInitializedInstruction ignored -> true;
            case IrFloatingBitsInstruction ignored -> true;
            case IrExceptionLandingPadInstruction ignored -> true;
            case IrExceptionCaughtInstruction ignored -> true;
            case IrThrowableTraceInstruction trace -> trace.operation() == IrThrowableTraceInstruction.Operation.CAPTURE;
            default -> false;
        };
    }
}
