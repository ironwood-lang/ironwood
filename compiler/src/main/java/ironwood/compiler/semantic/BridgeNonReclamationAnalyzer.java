// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.bridge.BridgeCallableId;
import ironwood.compiler.bridge.BridgeNonReclamationContract;
import ironwood.compiler.bridge.BridgeProof;
import ironwood.compiler.bridge.BridgeRootSet;
import ironwood.compiler.bridge.BridgeUnpublishedCleanup;
import ironwood.compiler.ir.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Whole-export-closure non-reclamation proof. No exemption follows from a private
 * field, constructor location or missing source destructor. Unproved rollback
 * exclusions remain failures until separate unpublished-construction facts exist.
 */
public final class BridgeNonReclamationAnalyzer {
    private final IrProgram program;
    private final BridgeCallTargets targets;
    private final Set<IrType> exposedDynamicTypes;
    private final Set<String> visited = new LinkedHashSet<>();
    private final ArrayDeque<IrFunction> pending = new ArrayDeque<>();
    private final Set<String> unknown = new LinkedHashSet<>();
    private final Set<String> reclamation = new LinkedHashSet<>();
    private final BridgeRollbackAnalysis rollbackAnalysis;
    private final Set<String> checkedCleanups = new LinkedHashSet<>();
    private final List<BridgeUnpublishedCleanup> exclusions = new ArrayList<>();

    private BridgeNonReclamationAnalyzer(IrProgram program, IrType exposed, BridgeConstructionFacts construction) {
        this.program = program;
        this.targets = new BridgeCallTargets(program);
        this.exposedDynamicTypes = dynamicTypes(exposed);
        this.rollbackAnalysis = new BridgeRollbackAnalysis(program, construction);
    }

    public static BridgeProof<BridgeNonReclamationContract> analyze(
            IrProgram program, BridgeRootSet roots, IrType exposed) {
        return analyze(program, roots, exposed, null);
    }

    public static BridgeProof<BridgeNonReclamationContract> analyze(
            IrProgram program, BridgeRootSet roots, IrType exposed, BridgeConstructionFacts construction) {
        BridgeRootSet checked = roots.revalidate(program);
        if (!checked.resolved()) throw new IllegalArgumentException("non-reclamation requires resolved bridge roots");
        var analyzer = new BridgeNonReclamationAnalyzer(program, exposed, construction);
        if (analyzer.exposedDynamicTypes.isEmpty()) {
            return BridgeProof.unknown("no complete resolved dynamic-type set for " + exposed.displayName());
        }
        for (var root : checked.roots()) {
            IrFunction function = analyzer.targets.function(root.callable().linkage());
            analyzer.enqueue(function);
            var initialization = analyzer.targets.initializers(root.callable().owner());
            if (!initialization.complete()) analyzer.unknown.add("missing entry initialization: " + root.callable().owner());
            initialization.targets().forEach(analyzer::enqueue);
            // A native construction entry must include its synthesized failure cleanup.
            if (function.constructor()) {
                if (function.parameters().isEmpty()) analyzer.unknown.add("constructor has no receiver: " + function.linkageName());
                else if (analyzer.rollbackAnalysis.entry(function).isPresent()) {
                    analyzer.exclude(analyzer.rollbackAnalysis.entry(function).orElseThrow(), function,
                            function.sourceSpan(), BridgeUnpublishedCleanup.Kind.ENTRY_FAILURE);
                } else {
                    analyzer.release(function.parameters().getFirst().value().type(), "exported constructor " + function.linkageName());
                    analyzer.cleanup(function.parameters().getFirst().value(), true, "exported constructor " + function.linkageName());
                }
            }
        }
        while (!analyzer.pending.isEmpty()) analyzer.scan(analyzer.pending.removeFirst());
        if (!analyzer.reclamation.isEmpty()) {
            return BridgeProof.rejected(String.join("\n", analyzer.reclamation.stream().sorted().toList()));
        }
        if (!analyzer.unknown.isEmpty()) {
            return BridgeProof.unknown(String.join("\n", analyzer.unknown.stream().sorted().toList()));
        }
        List<BridgeCallableId> closure = java.util.stream.Stream.concat(analyzer.visited.stream(),
                        analyzer.checkedCleanups.stream()).distinct().map(analyzer.targets::function)
                .map(BridgeCallableId::of).sorted(Comparator.comparing(BridgeCallableId::linkage)).toList();
        return BridgeProof.proved(new BridgeNonReclamationContract(exposed, analyzer.exposedDynamicTypes,
                checked.roots().stream().map(BridgeRootSet.Root::callable).toList(), closure, analyzer.exclusions),
                "every resolved export/initializer/dispatch/cleanup operation excludes exposed-type reclamation");
    }

    private void enqueue(IrFunction function) {
        if (visited.add(function.linkageName())) pending.add(function);
    }

    private void scan(IrFunction function) {
        for (IrBasicBlock block : function.blocks()) {
            for (IrInstruction instruction : block.instructions()) scan(function, block, instruction);
            if (block.terminator() instanceof IrInvokeTerminator invoke) scan(function, block, invoke.call());
        }
    }

    private void scan(IrFunction function, IrBasicBlock block, IrInstruction instruction) {
        String site = function.linkageName() + " (" + function.sourceFileName() + ":"
                + instruction.sourceSpan().start().line() + ")";
        BridgeCallTargets.Call call = targets.resolve(instruction);
        if (call != null) {
            if (!call.complete()) unknown.add("unresolved call/initialization at " + site);
            call.targets().forEach(this::enqueue);
        } else if (instruction instanceof IrFreeInstruction free) {
            release(free.allocation().type(), site);
            cleanup(free.allocation(), false, site);
        } else if (instruction instanceof IrRawDeallocateInstruction raw) {
            release(raw.allocation().type(), site);
        } else if (instruction instanceof IrRollbackInstruction rollback) {
            var exclusion = rollbackAnalysis.unwind(function, block, rollback);
            if (exclusion.isPresent()) {
                exclude(exclusion.orElseThrow(), function, rollback.allocation().sourceSpan(),
                        BridgeUnpublishedCleanup.Kind.CONSTRUCTOR_UNWIND);
            } else {
                release(rollback.allocation().type(), site);
                cleanup(rollback.allocation(), true, site);
            }
        } else if (instruction instanceof IrDestroyArrayElementsInstruction elements) {
            IrType element = elements.array().type().elementType();
            release(element, site);
            cleanup(new IrNull(element, instruction.sourceSpan()), false, site);
        } else if (instruction instanceof IrReleaseOwnedToStringResultInstruction
                || instruction instanceof IrReleaseOwnedThrowableMessageInstruction) {
            // These fixed runtime helpers deallocate only String storage; they do
            // not invoke arbitrary producer destructors. String is a final runtime type.
            release(IrType.reference("ironwood.lang.String"), site);
        } else if (!nonReclaiming(instruction)) {
            unknown.add("unclassified deallocation effect " + instruction.getClass().getSimpleName() + " at " + site);
        }
    }

    private void exclude(BridgeRollbackAnalysis.Cleanup cleanup, IrFunction caller,
                         ironwood.compiler.source.SourceSpan allocation,
                         BridgeUnpublishedCleanup.Kind kind) {
        exclusions.add(new BridgeUnpublishedCleanup(BridgeCallableId.of(caller), allocation, kind,
                BridgeCallableId.of(cleanup.function()), cleanup.construction()));
        checkedCleanups.add(cleanup.function().linkageName());
        // Only the attributed storage is unpublished. Destructors may still
        // reclaim other storage and must enter the ordinary checked closure.
        String site = "unpublished cleanup " + cleanup.function().linkageName();
        cleanup.destructorObjects().forEach(object -> cleanup(object, false, site));
        cleanup.elementArrays().forEach(array -> cleanup(
                new IrNull(array.type().elementType(), allocation), false, site));
    }

    private void cleanup(IrOperand object, boolean rollback, String site) {
        var cleanup = targets.cleanup(object, rollback);
        if (!cleanup.complete()) unknown.add("unresolved " + (rollback ? "rollback" : "destructor") + " at " + site);
        cleanup.targets().forEach(this::enqueue);
    }

    private void release(IrType type, String site) {
        Set<IrType> possible = dynamicTypes(type);
        if (possible.isEmpty()) {
            unknown.add("unresolved deallocation type " + type.displayName() + " at " + site);
        } else if (possible.stream().anyMatch(exposedDynamicTypes::contains)) {
            reclamation.add("reachable deallocation may reclaim exposed " + type.displayName() + " at " + site);
        }
    }

    private Set<IrType> dynamicTypes(IrType type) {
        if (!type.typeArguments().isEmpty()) return Set.of();
        Set<IrType> result = new LinkedHashSet<>();
        if (type.isNominalReference()) {
            targets.dynamicTypes(type).forEach(candidate -> result.add(IrType.reference(candidate.name())));
            var declaration = program.classes().stream().filter(candidate -> candidate.name().equals(type.referenceName())).findFirst();
            if (declaration.isPresent()) {
                int id = declaration.orElseThrow().typeId();
                program.arrayTypes().stream().filter(array -> array.typeMembership().contains(id))
                        .forEach(array -> result.add(array.type()));
            }
        } else if (type.isArray()) {
            // Array ids are private negative descriptor identities, not entries
            // in membership tables. Preserve covariance structurally instead.
            program.arrayTypes().stream().filter(array -> assignable(array.type(), type))
                    .forEach(array -> result.add(array.type()));
        }
        return Set.copyOf(result);
    }

    private boolean assignable(IrType actual, IrType declared) {
        if (actual.equals(declared)) return true;
        if (actual.isArray() && declared.isArray()) {
            if (!actual.elementType().isReference() || !declared.elementType().isReference()) return false;
            return assignable(actual.elementType(), declared.elementType());
        }
        if (actual.isArray() && declared.isNominalReference()) {
            var declaration = program.classes().stream().filter(type -> type.name().equals(declared.referenceName())).findFirst();
            return declaration.isPresent() && program.arrayTypes().stream()
                    .anyMatch(array -> array.type().equals(actual)
                            && array.typeMembership().contains(declaration.orElseThrow().typeId()));
        }
        return actual.isNominalReference() && declared.isNominalReference()
                && targets.dynamicTypes(declared).stream().anyMatch(type -> type.name().equals(actual.referenceName()));
    }

    private static boolean nonReclaiming(IrInstruction instruction) {
        return switch (instruction) {
            case IrAllocateInstruction ignored -> true;
            case IrArrayAllocateInstruction ignored -> true;
            case IrStringCopyInstruction ignored -> true;
            case IrFieldLoadInstruction ignored -> true;
            case IrFieldStoreInstruction ignored -> true;
            case IrStaticFieldLoadInstruction ignored -> true;
            case IrStaticFieldStoreInstruction ignored -> true;
            case IrArrayLoadInstruction ignored -> true;
            case IrArrayStoreInstruction ignored -> true;
            case IrReferenceConversionInstruction ignored -> true;
            case IrPhiInstruction ignored -> true;
            case IrBinaryInstruction ignored -> true;
            case IrUnaryInstruction ignored -> true;
            case IrNumericConversionInstruction ignored -> true;
            case IrNullCheckInstruction ignored -> true;
            case IrArrayBoundsCheckInstruction ignored -> true;
            case IrArrayLengthCheckInstruction ignored -> true;
            case IrArrayLengthInstruction ignored -> true;
            case IrArrayTypeTestInstruction ignored -> true;
            case IrInstanceOfInstruction ignored -> true;
            case IrTypeInitializedInstruction ignored -> true;
            case IrIdentityHashCodeInstruction ignored -> true;
            case IrMathUnaryInstruction ignored -> true;
            case IrMathBinaryInstruction ignored -> true;
            case IrExceptionLandingPadInstruction ignored -> true;
            case IrExceptionCaughtInstruction ignored -> true;
            case IrAllocationCountInstruction ignored -> true;
            case IrLiveAllocationCountInstruction ignored -> true;
            case IrThrowableTraceInstruction trace -> trace.operation() == IrThrowableTraceInstruction.Operation.CAPTURE
                    || trace.operation() == IrThrowableTraceInstruction.Operation.RELEASE
                    || trace.operation() == IrThrowableTraceInstruction.Operation.COMMON;
            default -> false;
        };
    }
}
