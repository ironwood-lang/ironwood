// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.ir.*;
import ironwood.compiler.source.SourceSpan;
import java.util.*;

/** Proves that a fresh local constructor borrower cannot outlive its invocation.
 * Facts come from a completed conservative summary pass and provisional typed
 * control flow. They discharge only constructor retention, never other effects.
 */
final class TemporaryBorrowAnalysis {
    private record Position(String block, int index) {}
    record Call(List<IrOperand> arguments, IrOperand result, List<CallableSymbol> targets) {}

    static Map<String, Set<SourceSpan>> prove(Map<String, TypeSymbol> types,
                                             Collection<IrFunction> functions,
                                             EscapeSummaryAnalyzer summaries,
                                             ClosedWorldEffectAnalyzer effects) {
        Map<String, Set<SourceSpan>> result = new LinkedHashMap<>();
        for (IrFunction function : functions) {
            if (!hasCandidate(function, instruction -> {
                if (!(instruction instanceof IrCallInstruction call)) return false;
                CallableSymbol target = summaries.callable(call.targetLinkageName());
                return target != null && target.isConstructor()
                        && !summaries.summary(target).receiverRetainedParameters().isEmpty();
            })) continue;
            Set<SourceSpan> sites = new Check(types, function, summaries, effects).run();
            if (!sites.isEmpty()) result.put(function.linkageName(), sites);
        }
        return Map.copyOf(result);
    }

    static boolean hasCandidate(IrFunction function, java.util.function.Predicate<IrInstruction> predicate) {
        for (IrBasicBlock block : function.blocks()) {
            for (IrInstruction instruction : block.instructions()) {
                if (predicate.test(instruction)) return true;
            }
            if (block.terminator() instanceof IrInvokeTerminator invoke && predicate.test(invoke.call())) return true;
        }
        return false;
    }

    static final class Check {
        private final Map<String, TypeSymbol> types;
        private final IrFunction function;
        private final EscapeSummaryAnalyzer summaries;
        private final ClosedWorldEffectAnalyzer effects;
        private final Map<String, IrBasicBlock> blocks = new LinkedHashMap<>();
        final List<IrInstruction> instructions = new ArrayList<>();
        private final Map<IrInstruction, Position> positions = new IdentityHashMap<>();
        private final Map<IrOperand, IrAllocateInstruction> allocations = new HashMap<>();

        Check(Map<String, TypeSymbol> types, IrFunction function,
              EscapeSummaryAnalyzer summaries, ClosedWorldEffectAnalyzer effects) {
            this.types = types;
            this.function = function;
            this.summaries = summaries;
            this.effects = effects;
            for (IrBasicBlock block : function.blocks()) {
                blocks.put(block.label(), block);
                for (int index = 0; index < block.instructions().size(); index++) {
                    add(block.instructions().get(index), new Position(block.label(), index));
                }
                if (block.terminator() instanceof IrInvokeTerminator invoke) {
                    add(invoke.call(), new Position(block.label(), block.instructions().size()));
                }
            }
        }

        private void add(IrInstruction instruction, Position position) {
            instructions.add(instruction);
            positions.put(instruction, position);
            if (instruction instanceof IrAllocateInstruction allocation) {
                allocations.put(allocation.result(), allocation);
            }
        }

        Set<SourceSpan> run() {
            Map<SourceSpan, Boolean> copies = new HashMap<>();
            for (IrInstruction instruction : instructions) {
                if (!(instruction instanceof IrCallInstruction invocation)) continue;
                CallableSymbol constructor = summaries.callable(invocation.targetLinkageName());
                if (constructor == null || !constructor.isConstructor() || invocation.arguments().isEmpty()) continue;
                IrAllocateInstruction allocation = allocations.get(invocation.arguments().getFirst());
                if (allocation == null) continue;
                boolean retains = !summaries.summary(constructor).receiverRetainedParameters().isEmpty();
                boolean safe = retains && confinedConstructor(constructor)
                        && confinedUses(allocation.result(), instruction)
                        && cleanedOnExit(allocation, allocation.result());
                // A single source allocation can appear in several cleanup copies.
                copies.merge(allocation.sourceSpan(), safe, (left, right) -> left && right);
            }
            Set<SourceSpan> result = new HashSet<>();
            copies.forEach((span, safe) -> { if (safe) result.add(span); });
            return Set.copyOf(result);
        }

        private boolean confinedConstructor(CallableSymbol constructor) {
            if (!summaries.constructorCleanupIsConfined(constructor)) return false;
            for (TypeSymbol owner = types.get(constructor.ownerType()); owner != null;
                 owner = owner.superclass().orElse(null)) {
                if (owner.constructors().stream().anyMatch(target -> summaries.summary(target).thisEscapes())) return false;
            }
            for (int index = 0; index < constructor.parameters().size(); index++) {
                if (constructor.parameterTypes().get(index).isReference()
                        && !summaries.constructorArgumentIsConfined(constructor, index)) return false;
            }
            return true;
        }

        private boolean confinedUses(IrOperand root, IrInstruction construction) {
            Set<IrOperand> related = new HashSet<>(Set.of(root));
            boolean changed;
            do {
                changed = false;
                for (IrInstruction instruction : instructions) {
                    if (instruction instanceof IrReferenceConversionInstruction conversion && related.contains(conversion.value())) {
                        changed |= related.add(conversion.result());
                    } else if (instruction instanceof IrPhiInstruction phi
                            && phi.incoming().stream().anyMatch(value -> related.contains(value.value()))) {
                        changed |= related.add(phi.result());
                    } else if (instruction instanceof IrFieldLoadInstruction load && related.contains(load.receiver())
                            && load.result().type().isReference()) {
                        changed |= related.add(load.result());
                    } else if (instruction instanceof IrArrayLoadInstruction load && related.contains(load.array())
                            && load.result().type().isReference()) {
                        changed |= related.add(load.result());
                    }
                    Call call = call(instruction);
                    if (call == null || call.arguments().stream().noneMatch(related::contains)) continue;
                    if (call.targets().isEmpty()) return false;
                    for (CallableSymbol target : call.targets()) {
                        var summary = summaries.summary(target);
                        for (int index = 0; index < call.arguments().size(); index++) {
                            if (!related.contains(call.arguments().get(index))) continue;
                            boolean receiver = !target.isStatic() && index == 0;
                            int parameter = index - (target.isStatic() ? 0 : 1);
                            if (receiver ? summary.thisEscapesWithoutReturn() : summary.parameterEscapesWithoutReturn(parameter)) return false;
                        }
                        if (call.result() != null && call.result().type().isReference() && !summary.returnsOwnedFresh()) {
                            // Treat all non-fresh results conservatively as dependent;
                            // they may be observed locally, but must not be published.
                            changed |= related.add(call.result());
                        }
                    }
                    if (instruction == construction && !call.arguments().getFirst().equals(root)) return false;
                }
            } while (changed);
            for (IrInstruction instruction : instructions) {
                if (instruction instanceof IrFieldStoreInstruction store && related.contains(store.value())
                        || instruction instanceof IrStaticFieldStoreInstruction staticStore && related.contains(staticStore.value())
                        || instruction instanceof IrArrayStoreInstruction arrayStore && related.contains(arrayStore.value())
                        || instruction instanceof IrAddSecondaryExceptionInstruction add
                        && (related.contains(add.primary()) || related.contains(add.secondary()))) return false;
                if (!supported(instruction)) return false;
            }
            for (IrBasicBlock block : blocks.values()) {
                if (block.terminator() instanceof IrReturnTerminator returned
                        && returned.value().filter(related::contains).isPresent()
                        || block.terminator() instanceof IrThrowTerminator thrown && related.contains(thrown.exception())) return false;
            }
            return true;
        }

        boolean cleanedOnExit(IrInstruction allocation, IrOperand value) {
            return cleanedOnExit(allocation, Set.of(value));
        }

        boolean cleanedOnExit(IrInstruction allocation, Set<IrOperand> values) {
            Position start = positions.get(allocation);
            IrBasicBlock initial = blocks.get(start.block());
            Deque<Position> pending = new ArrayDeque<>();
            if (start.index() == initial.instructions().size()
                    && initial.terminator() instanceof IrInvokeTerminator invoke) {
                pending.add(new Position(invoke.normalTarget(), 0));
            } else pending.add(new Position(start.block(), start.index() + 1));
            Set<Position> visited = new HashSet<>();
            while (!pending.isEmpty()) {
                Position position = pending.removeFirst();
                if (!visited.add(position)) continue;
                IrBasicBlock block = blocks.get(position.block());
                boolean cleaned = false;
                for (int index = position.index(); index < block.instructions().size(); index++) {
                    IrInstruction instruction = block.instructions().get(index);
                    if (values.stream().anyMatch(value -> frees(instruction, value))) { cleaned = true; break; }
                    if (instruction == allocation || unhandledUnwind(instruction)) return false;
                }
                if (cleaned) continue;
                IrTerminator terminator = block.terminator();
                if (terminator instanceof IrInvokeTerminator invoke) {
                    if (invoke.call() == allocation) return false;
                    pending.add(new Position(invoke.normalTarget(), 0));
                    if (effects.mayUnwind(invoke.call())) pending.add(new Position(invoke.unwindTarget(), 0));
                } else {
                    Set<String> next = successors(terminator);
                    if (next.isEmpty()) return false;
                    next.forEach(label -> pending.add(new Position(label, 0)));
                }
            }
            return true;
        }

        private boolean unhandledUnwind(IrInstruction instruction) {
            if (instruction instanceof IrCallInstruction || instruction instanceof IrVirtualCallInstruction
                    || instruction instanceof IrInterfaceCallInstruction || instruction instanceof IrEnsureTypeInitializedInstruction) {
                return effects.mayUnwind(instruction);
            }
            // Other throwing acquisition instructions only occur in a protected
            // invoke when cleanup is installed. An unprotected acquisition cannot
            // prove destruction of the already-live borrower on failure.
            return instruction instanceof IrAllocateInstruction || instruction instanceof IrArrayAllocateInstruction;
        }

        private static boolean frees(IrInstruction instruction, IrOperand allocation) {
            return instruction instanceof IrFreeInstruction free && free.allocation().equals(allocation)
                    || instruction instanceof IrRawDeallocateInstruction raw && raw.allocation().equals(allocation)
                    || instruction instanceof IrRollbackInstruction rollback && rollback.allocation().equals(allocation);
        }

        Call call(IrInstruction instruction) {
            if (instruction instanceof IrCallInstruction call) {
                CallableSymbol target = summaries.callable(call.targetLinkageName());
                return new Call(call.arguments(), call.result().orElse(null), target == null ? List.of() : List.of(target));
            }
            if (instruction instanceof IrVirtualCallInstruction call) {
                return new Call(call.arguments(), call.result().orElse(null), summaries.boundTargets(function.linkageName(), call.sourceSpan(), call.slot().methodName()));
            }
            if (instruction instanceof IrInterfaceCallInstruction call) {
                return new Call(call.arguments(), call.result().orElse(null), summaries.boundTargets(function.linkageName(), call.sourceSpan(), call.slot().methodName()));
            }
            return null;
        }

        static boolean supported(IrInstruction instruction) {
            // Unlisted runtime/intrinsic operations do not acquire a new borrowing
            // exemption. Extend this bounded proof only with an audited effect.
            return switch (instruction) {
                case IrAllocateInstruction ignored -> true;
                case IrArrayAllocateInstruction ignored -> true;
                case IrCallInstruction ignored -> true;
                case IrVirtualCallInstruction ignored -> true;
                case IrInterfaceCallInstruction ignored -> true;
                case IrReferenceConversionInstruction ignored -> true;
                case IrNumericConversionInstruction ignored -> true;
                case IrPhiInstruction ignored -> true;
                case IrFieldLoadInstruction ignored -> true;
                case IrFieldStoreInstruction ignored -> true;
                case IrStaticFieldLoadInstruction ignored -> true;
                case IrStaticFieldStoreInstruction ignored -> true;
                case IrArrayLoadInstruction ignored -> true;
                case IrArrayStoreInstruction ignored -> true;
                case IrBinaryInstruction ignored -> true;
                case IrUnaryInstruction ignored -> true;
                case IrInstanceOfInstruction ignored -> true;
                case IrNullCheckInstruction ignored -> true;
                case IrArrayBoundsCheckInstruction ignored -> true;
                case IrArrayLengthInstruction ignored -> true;
                case IrArrayTypeTestInstruction ignored -> true;
                case IrFreeInstruction ignored -> true;
                case IrRawDeallocateInstruction ignored -> true;
                case IrRollbackInstruction ignored -> true;
                case IrEnsureTypeInitializedInstruction ignored -> true;
                case IrExceptionLandingPadInstruction ignored -> true;
                case IrExceptionCaughtInstruction ignored -> true;
                case IrAddSecondaryExceptionInstruction ignored -> true;
                case IrAllocationCountInstruction ignored -> true;
                case IrLiveAllocationCountInstruction ignored -> true;
                case IrSystemClockInstruction ignored -> true;
                case IrPrintStreamWriteInstruction ignored -> true;
                case IrPrintStreamFlushInstruction ignored -> true;
                case IrPrintStreamCheckErrorInstruction ignored -> true;
                default -> false;
            };
        }

        private static Set<String> successors(IrTerminator terminator) {
            if (terminator instanceof IrJump jump) return Set.of(jump.target());
            if (terminator instanceof IrBranch branch) return new HashSet<>(List.of(branch.trueTarget(), branch.falseTarget()));
            if (terminator instanceof IrThrowTerminator thrown) return thrown.unwindTarget().map(Set::of).orElse(Set.of());
            if (terminator instanceof IrSwitchTerminator selection) {
                Set<String> result = new HashSet<>(Set.of(selection.defaultTarget()));
                selection.cases().forEach(branch -> result.add(branch.target()));
                return result;
            }
            return Set.of();
        }
    }
}
