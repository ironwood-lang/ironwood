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
        var calls = new BridgeCallTargets(program);
        var reachability = BridgeCallbackReachability.analyze(program);
        var entries = reachability.entries(roots);
        var pending = new ArrayList<IrFunction>();
        for (var root : roots.roots()) {
            var id = root.callable();
            if (!facts.isStatic(id) || id.kind() != IrCallableKind.METHOD
                    || !(id.result().isPrimitive() || id.result().equals(IrType.VOID))) {
                throw new IllegalArgumentException("synchronous callback requires static primitive/void result roots");
            }
            boolean listener = false;
            for (int index = 0; index < id.parameters().size(); index++) {
                var type = id.parameters().get(index);
                if (type.isPrimitive()) continue;
                if ((!listeners.contains(type) && !type.equals(IrType.reference("ironwood.lang.String"))) || !facts.borrowsInput(id, index)) {
                    throw new IllegalArgumentException("synchronous callback requires borrowed listener inputs: " + id.linkage());
                }
                listener |= listeners.contains(type);
            }
            if (!listener || !entries.get(id).foreign() || !entries.get(id).complete()) {
                throw new IllegalArgumentException("synchronous callback requires a complete callback-bearing closure");
            }
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
                    } else if (!localValueOperation(operation)) throw rejected(function, operation);
                }
            }
        }
        return new BridgeSynchronousCallbackProof(program, roots, visited);
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
