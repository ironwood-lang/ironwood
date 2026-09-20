// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.ir.*;
import ironwood.compiler.source.SourceSpan;
import java.util.*;

/** Keeps a confined list's identity separate from its externally owned elements. */
final class TemporaryListBorrowAnalysis {
    /** A null owner discharges a temporary retention; a non-null owner describes get(). */
    record Site(ReturnOrigin returnedOwner) {}

    static Map<String, Map<SourceSpan, Site>> prove(Map<String, TypeSymbol> types,
            Collection<IrFunction> functions, EscapeSummaryAnalyzer summaries,
            OwnedArrayFieldAnalyzer fields, ClosedWorldEffectAnalyzer effects) {
        Map<String, Map<SourceSpan, Site>> result = new LinkedHashMap<>();
        for (IrFunction function : functions) {
            if (!TemporaryBorrowAnalysis.hasCandidate(function, instruction -> {
                if (instruction instanceof IrAllocateInstruction allocation) return allocation.className().equals("ironwood.ds.ArrayList");
                IrType type = instruction instanceof IrCallInstruction call ? call.returnType()
                        : instruction instanceof IrVirtualCallInstruction call ? call.returnType()
                        : instruction instanceof IrInterfaceCallInstruction call ? call.returnType() : null;
                return type != null && type.isNominalReference() && type.referenceName().equals("ironwood.ds.ArrayList");
            })) continue;
            Map<SourceSpan, Site> sites = new Check(types, function, summaries, fields, effects).run();
            if (!sites.isEmpty()) result.put(function.linkageName(), sites);
        }
        return Map.copyOf(result);
    }

    private static final class Check {
        private final Map<String, TypeSymbol> types;
        private final IrFunction function;
        private final EscapeSummaryAnalyzer summaries;
        private final OwnedArrayFieldAnalyzer fields;
        private final TemporaryBorrowAnalysis.Check flow;
        private final Map<IrOperand, IrInstruction> definitions = new HashMap<>();
        private final Map<IrOperand, ReturnOrigin> inputs = new HashMap<>();

        Check(Map<String, TypeSymbol> types, IrFunction function, EscapeSummaryAnalyzer summaries,
              OwnedArrayFieldAnalyzer fields, ClosedWorldEffectAnalyzer effects) {
            this.types = types;
            this.function = function;
            this.summaries = summaries;
            this.fields = fields;
            flow = new TemporaryBorrowAnalysis.Check(types, function, summaries, effects);
            CallableSymbol callable = summaries.callable(function.linkageName());
            if (callable != null) {
                int offset = callable.isStatic() ? 0 : 1;
                for (int index = 0; index < function.parameters().size(); index++) {
                    inputs.put(function.parameters().get(index).value(), index < offset
                            ? ReturnOrigin.thisOrigin() : ReturnOrigin.parameter(index - offset));
                }
            }
            for (IrInstruction instruction : flow.instructions) {
                IrOperand result = instruction instanceof IrReferenceConversionInstruction conversion ? conversion.result()
                        : instruction instanceof IrPhiInstruction phi ? phi.result()
                        : instruction instanceof IrFieldLoadInstruction load ? load.result()
                        : flow.call(instruction) == null ? null : flow.call(instruction).result();
                if (result != null) definitions.put(result, instruction);
            }
        }

        Map<SourceSpan, Site> run() {
            Map<SourceSpan, Site> result = new HashMap<>();
            Set<SourceSpan> rejected = new HashSet<>();
            Set<IrInstruction> covered = Collections.newSetFromMap(new IdentityHashMap<>());
            for (IrInstruction acquisition : flow.instructions) {
                IrOperand list;
                ReturnOrigin seed = null;
                boolean factory = false;
                if (acquisition instanceof IrAllocateInstruction allocation
                        && allocation.className().equals("ironwood.ds.ArrayList")) {
                    list = allocation.result();
                } else {
                    var call = flow.call(acquisition);
                    if (call == null || call.result() == null || call.targets().size() != 1
                            || !call.result().type().isNominalReference()
                            || !call.result().type().referenceName().equals("ironwood.ds.ArrayList")) continue;
                    var proof = summaries.freshBorrowingFactory(call.targets().getFirst());
                    if (proof == null || proof.elements().isEmpty()) continue;
                    boolean valid = true;
                    for (var element : proof.elements()) {
                        ReturnOrigin origin = element.fields().stream().allMatch(fields::isOwned)
                                ? mapped(element.origin(), call, Map.of(), new HashSet<>()) : null;
                        if (origin == null || seed != null && !seed.equals(origin)) { valid = false; break; }
                        seed = origin;
                    }
                    if (!valid) continue;
                    list = call.result();
                    factory = true;
                }
                Set<IrOperand> aliases = aliases(list);
                Map<SourceSpan, Site> sites = proveList(acquisition, aliases, seed, factory);
                // Every lowering copy of the same call must have the same proof.
                Set<SourceSpan> touched = new HashSet<>();
                if (factory) touched.add(acquisition.sourceSpan());
                for (IrInstruction instruction : flow.instructions) {
                    var call = flow.call(instruction);
                    if (call != null && call.arguments().stream().anyMatch(aliases::contains)) touched.add(instruction.sourceSpan());
                }
                if (sites == null) { rejected.addAll(touched); continue; }
                if (factory) covered.add(acquisition);
                for (IrInstruction instruction : flow.instructions) {
                    var call = flow.call(instruction);
                    if (call != null && call.arguments().stream().anyMatch(aliases::contains)) covered.add(instruction);
                }
                sites.forEach((span, site) -> {
                    Site old = result.putIfAbsent(span, site);
                    if (old != null && !old.equals(site)) rejected.add(span);
                });
            }
            for (IrInstruction instruction : flow.instructions) {
                if (flow.call(instruction) != null && !covered.contains(instruction)) rejected.add(instruction.sourceSpan());
            }
            rejected.forEach(result::remove);
            return Map.copyOf(result);
        }

        private Map<SourceSpan, Site> proveList(IrInstruction acquisition, Set<IrOperand> aliases,
                                               ReturnOrigin seed, boolean factory) {
            // Conversions and loop phis must retain one exact fresh list identity.
            List<IrInstruction> operations = new ArrayList<>();
            List<IrInstruction> reads = new ArrayList<>();
            List<IrOperand> added = new ArrayList<>();
            for (IrInstruction instruction : flow.instructions) {
                if (!TemporaryBorrowAnalysis.Check.supported(instruction)) return null;
                if (instruction instanceof IrPhiInstruction phi && aliases.contains(phi.result())
                        && phi.incoming().stream().anyMatch(in -> !aliases.contains(in.value()))
                        || instruction instanceof IrFieldLoadInstruction load && aliases.contains(load.receiver())
                        || instruction instanceof IrArrayLoadInstruction load && aliases.contains(load.array())
                        || instruction instanceof IrFieldStoreInstruction store && (aliases.contains(store.value()) || aliases.contains(store.receiver()))
                        || instruction instanceof IrStaticFieldStoreInstruction store && aliases.contains(store.value())
                        || instruction instanceof IrArrayStoreInstruction store && aliases.contains(store.value())
                        || instruction instanceof IrAddSecondaryExceptionInstruction add && (aliases.contains(add.primary()) || aliases.contains(add.secondary()))) return null;
                var call = flow.call(instruction);
                if (call == null || !call.arguments().stream().anyMatch(aliases::contains)) continue;
                if (call.targets().size() != 1 || !aliases.contains(call.arguments().getFirst())) return null;
                CallableSymbol target = call.targets().getFirst();
                if (target.isStatic() || !target.ownerType().equals("ironwood.ds.ArrayList")
                        || summaries.summary(target).thisEscapesWithoutReturn()) return null;
                if (target.isConstructor()) {
                    if (target.parameterTypes().stream().anyMatch(IrType::isReference)) return null;
                } else if (validGet(target)) {
                    reads.add(instruction);
                } else if (target.sourceName().equals("add") && DataStructureSemantics.retainedArguments(target).equals(Set.of(0))) {
                    added.add(call.arguments().get(1));
                } else if (!(Set.of("clear", "size", "isEmpty").contains(target.sourceName())
                        && target.parameterTypes().isEmpty())) return null;
                operations.add(instruction);
            }
            for (IrBasicBlock block : function.blocks()) {
                if (block.terminator() instanceof IrReturnTerminator returned && returned.value().filter(aliases::contains).isPresent()
                        || block.terminator() instanceof IrThrowTerminator thrown && aliases.contains(thrown.exception())) return null;
            }
            if (!flow.cleanedOnExit(acquisition, aliases)) return null;
            Map<IrOperand, ReturnOrigin> elements = new HashMap<>();
            ReturnOrigin owner = seed;
            // A factory supplies the initial root, or an independently known add
            // does. A get/add cycle by itself cannot invent element provenance.
            for (IrOperand value : added) {
                if (value instanceof IrNull) continue;
                ReturnOrigin origin = origin(value, Map.of(), new HashSet<>());
                if (origin != null) {
                    if (owner != null && !owner.equals(origin)) return null;
                    owner = origin;
                }
            }
            if (owner == null) return null;
            for (IrInstruction read : reads) elements.put(flow.call(read).result(), owner);
            for (IrOperand value : added) {
                if (!(value instanceof IrNull) && !owner.equals(origin(value, elements, new HashSet<>()))) return null;
            }
            Map<SourceSpan, Site> sites = new HashMap<>();
            if (factory) sites.put(acquisition.sourceSpan(), new Site(null));
            for (IrInstruction operation : operations) {
                if (flow.call(operation).targets().getFirst().isConstructor()) continue;
                sites.put(operation.sourceSpan(), new Site(reads.contains(operation) ? owner : null));
            }
            return sites;
        }

        private Set<IrOperand> aliases(IrOperand list) {
            Set<IrOperand> result = new HashSet<>(Set.of(list));
            boolean changed;
            do {
                changed = false;
                for (IrInstruction instruction : flow.instructions) {
                    if (instruction instanceof IrReferenceConversionInstruction conversion && result.contains(conversion.value())) {
                        changed |= result.add(conversion.result());
                    } else if (instruction instanceof IrPhiInstruction phi && phi.incoming().stream().anyMatch(in -> result.contains(in.value()))) {
                        changed |= result.add(phi.result());
                    }
                }
            } while (changed);
            return result;
        }

        private boolean validGet(CallableSymbol target) {
            var guard = DataStructureSemantics.arrayListElementReadGuard(target);
            if (guard == null) return false;
            TypeSymbol type = types.get(target.ownerType());
            FieldSymbol storage = type == null ? null : type.declaredFields().get("array");
            var targets = summaries.boundTargets(target, guard);
            return storage != null && fields.isOwned(storage) && !targets.isEmpty()
                    && targets.stream().allMatch(method -> method.returnType().equals(IrType.VOID)
                        && !summaries.summary(method).thisEscapesWithoutReturn());
        }

        private ReturnOrigin origin(IrOperand value, Map<IrOperand, ReturnOrigin> elements, Set<IrOperand> visiting) {
            if (inputs.containsKey(value)) return inputs.get(value);
            if (elements.containsKey(value)) return elements.get(value);
            if (!visiting.add(value)) return null;
            try {
                IrInstruction definition = definitions.get(value);
                if (definition instanceof IrReferenceConversionInstruction conversion) return origin(conversion.value(), elements, visiting);
                if (definition instanceof IrPhiInstruction phi) {
                    ReturnOrigin result = null;
                    for (var incoming : phi.incoming()) {
                        if (incoming.value() instanceof IrNull) continue;
                        if (incoming.value().equals(phi.result())) continue;
                        ReturnOrigin next = origin(incoming.value(), elements, visiting);
                        if (next == null || result != null && !result.equals(next)) return null;
                        result = next;
                    }
                    return result;
                }
                if (definition instanceof IrFieldLoadInstruction load) {
                    TypeSymbol owner = types.get(load.field().ownerClass());
                    FieldSymbol field = owner == null ? null : owner.declaredFields().get(load.field().name());
                    return field != null && fields.isOwned(field) ? origin(load.receiver(), elements, visiting) : null;
                }
                var call = definition == null ? null : flow.call(definition);
                if (call == null || call.targets().isEmpty()) return null;
                ReturnOrigin result = null;
                for (CallableSymbol target : call.targets()) {
                    var summary = summaries.summary(target);
                    if (summary.mayReturnNonOrigin() || summary.mayReturnFresh()) return null;
                    Set<ReturnOrigin> roots = new HashSet<>(summary.returnedOrigins());
                    for (var borrowed : summary.borrowedReturnedOrigins()) {
                        if (borrowed.borrowedOwnerField() != null) return null;
                        roots.add(borrowed.ownerOrigin());
                    }
                    if (roots.isEmpty()) return null;
                    for (ReturnOrigin root : roots) {
                        ReturnOrigin next = mapped(root, call, elements, visiting);
                        if (next == null || result != null && !result.equals(next)) return null;
                        result = next;
                    }
                }
                return result;
            } finally { visiting.remove(value); }
        }

        private ReturnOrigin mapped(ReturnOrigin root, TemporaryBorrowAnalysis.Call call,
                                    Map<IrOperand, ReturnOrigin> elements, Set<IrOperand> visiting) {
            int offset = call.targets().getFirst().isStatic() ? 0 : 1;
            int index = root.kind() == ReturnOrigin.Kind.THIS ? 0 : root.parameterIndex() + offset;
            return root.kind() == ReturnOrigin.Kind.ELEMENT_OF_PARAMETER || index >= call.arguments().size()
                    ? null : origin(call.arguments().get(index), elements, visiting);
        }
    }
}
