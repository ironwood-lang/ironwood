// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.ir.*;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Function;

/**
 * The classes a freed value, or an element of a destroyed array, may be at run time
 * (D284). A closed-world, context-insensitive union over typed IR: an object allocation
 * gives its exact class and an array allocation its own allocation site, fields join
 * what is stored into them, calls return what their targets return, and each array
 * site's elements join what is stored into arrays that may be that site. Everything
 * else is unknown, which keeps every subclass of the static type.
 *
 * <p>Reference fields are written only by field stores: immortal objects carry
 * primitive field values, and the runtime keeps traces and secondary exceptions in its
 * own tables. A store into an array of unknown origin may reach any array site whose
 * element type fits, so it joins all of them. Parameters join their call arguments
 * where every caller is visible: for a private callable, whose callers are in its nest,
 * and for any callable of a closed executable, whose single entry point and absence of
 * foreign calls leave no caller unseen. Other parameters are unknown, as are those of
 * the entry point, destructors and rollbacks, which the runtime calls. {@code System.arraycopy}
 * is modeled where it is called, as a copy between its arguments.</p>
 */
final class ValueClasses {
    /** Any class the static type allows. Absorbs everything joined with it. */
    private static final String UNKNOWN = "*";
    private static final Set<String> ANY = Set.of(UNKNOWN);
    /** Prefix of an array allocation site; other entries are class names. */
    private static final String SITE = "[]";

    private final Map<String, IrFunction> functions = new LinkedHashMap<>();
    private final Function<IrInstruction, List<IrFunction>> callTargets;
    private final BiPredicate<String, String> subtype;
    private final boolean closedExecutable;
    private final String entryPoint;
    private final Set<String> privateCallables;
    private final Set<String> storedFields = new HashSet<>();
    private final Map<String, Set<String>> fields = new HashMap<>();
    private final Map<String, Set<String>> returns = new HashMap<>();
    // Parameter values by function and index, when every caller is visible.
    private final Map<String, Map<Integer, Set<String>>> parameters = new HashMap<>();
    // Element type and elements of each array allocation site.
    private final Map<String, String> siteTypes = new HashMap<>();
    private final Map<String, Set<String>> elements = new HashMap<>();
    // What stores into arrays of unknown origin added, by their static element type.
    private final Map<String, Set<String>> unknownArrayElements = new HashMap<>();
    private final Map<IrInstruction, Set<String>> released = new IdentityHashMap<>();
    private boolean changed;

    /**
     * @param entryPoint the linkage name of a closed executable's entry point, or
     *        {@code null} for a library or bridge, whose callers are not all visible
     * @param privateCallables linkage names of private methods and constructors
     */
    ValueClasses(Collection<IrFunction> input, String entryPoint, Set<String> privateCallables,
                 Function<IrInstruction, List<IrFunction>> callTargets,
                 BiPredicate<String, String> subtype) {
        input.forEach(function -> functions.put(function.linkageName(), function));
        this.callTargets = callTargets;
        this.subtype = subtype;
        this.entryPoint = entryPoint;
        this.privateCallables = privateCallables;
        boolean foreignCalls = false;
        for (IrFunction function : functions.values()) {
            for (IrBasicBlock block : function.blocks()) {
                for (IrInstruction instruction : operations(block)) {
                    if (instruction instanceof IrFieldStoreInstruction store) {
                        storedFields.add(key(store.field()));
                    }
                    foreignCalls |= instruction instanceof IrForeignCallInstruction;
                }
            }
        }
        this.closedExecutable = entryPoint != null && !foreignCalls;
        do {
            changed = false;
            for (IrFunction function : functions.values()) {
                if (!isArrayCopy(function)) new Body(function).flow(false);
            }
        } while (changed);
        for (IrFunction function : functions.values()) {
            if (!isArrayCopy(function)) new Body(function).flow(true);
        }
    }

    /**
     * The classes the value a free releases, or an element a destruction releases, may
     * be; {@code null} when unknown.
     */
    Set<String> released(IrInstruction instruction) {
        Set<String> classes = released.get(instruction);
        return classes == null || classes.contains(UNKNOWN) ? null : classes;
    }

    private final class Body {
        private final IrFunction function;
        private final Set<Integer> defined = new HashSet<>();
        private final Map<Integer, Set<String>> values = new HashMap<>();
        private final Map<Integer, Integer> parameterIndexes = new HashMap<>();
        private final Map<Integer, IrOperand> conversions;
        private final boolean knownParameters;

        Body(IrFunction function) {
            this.function = function;
            this.conversions = ClosedWorldEffectAnalyzer.referenceConversions(function);
            for (int index = 0; index < function.parameters().size(); index++) {
                parameterIndexes.put(function.parameters().get(index).value().id(), index);
            }
            // The runtime also calls the entry point, destructors and rollbacks.
            this.knownParameters = (closedExecutable || privateCallables.contains(function.linkageName()))
                    && !function.linkageName().equals(entryPoint)
                    && function.kind() != IrCallableKind.DESTRUCTOR
                    && function.kind() != IrCallableKind.CONSTRUCTOR_ROLLBACK;
        }

        void flow(boolean record) {
            for (IrBasicBlock block : function.blocks()) {
                for (IrInstruction instruction : operations(block)) {
                    IrValueReference result = result(instruction);
                    if (result != null) defined.add(result.id());
                }
            }
            boolean local;
            do {
                local = false;
                for (IrBasicBlock block : function.blocks()) {
                    for (IrInstruction instruction : operations(block)) {
                        IrValueReference result = result(instruction);
                        if (result != null) local |= join(values, result.id(), produced(instruction));
                    }
                }
            } while (local);
            for (IrBasicBlock block : function.blocks()) {
                operations(block).forEach(this::consume);
                if (block.terminator() instanceof IrReturnTerminator returned) {
                    returned.value().filter(value -> value.type().isReference()).ifPresent(value ->
                            changed |= join(returns, function.linkageName(), value(value)));
                }
                if (record) {
                    for (IrInstruction instruction : operations(block)) {
                        if (instruction instanceof IrFreeInstruction free) {
                            released.put(free, value(free.allocation()));
                        } else if (instruction instanceof IrDestroyArrayElementsInstruction destroy) {
                            released.put(destroy, elementsOf(value(destroy.array())));
                        }
                    }
                }
            }
        }

        private IrValueReference result(IrInstruction instruction) {
            IrValueReference result = switch (instruction) {
                case IrAllocateInstruction allocate -> allocate.result();
                case IrArrayAllocateInstruction allocate -> allocate.result();
                case IrReferenceConversionInstruction conversion -> conversion.result();
                case IrPhiInstruction phi -> phi.result();
                case IrFieldLoadInstruction load -> load.result();
                case IrArrayLoadInstruction load -> load.result();
                default -> ClosedWorldEffectAnalyzer.callResult(instruction);
            };
            return result != null && result.type().isReference() ? result : null;
        }

        private Set<String> produced(IrInstruction instruction) {
            return switch (instruction) {
                case IrAllocateInstruction allocate -> Set.of(allocate.className());
                case IrArrayAllocateInstruction allocate -> {
                    String site = SITE + function.linkageName() + "@" + allocate.result().id();
                    siteTypes.put(site, typeName(allocate.elementType()));
                    yield Set.of(site);
                }
                case IrReferenceConversionInstruction conversion -> value(conversion.value());
                case IrPhiInstruction phi -> {
                    Set<String> joined = new LinkedHashSet<>();
                    phi.incoming().forEach(incoming -> addAll(joined, value(incoming.value())));
                    yield joined;
                }
                case IrFieldLoadInstruction load -> {
                    String field = key(load.field());
                    yield storedFields.contains(field) ? fields.getOrDefault(field, Set.of()) : ANY;
                }
                case IrArrayLoadInstruction load -> elementsOf(value(load.array()));
                default -> {
                    List<IrFunction> targets = callTargets.apply(instruction);
                    if (targets.isEmpty()) yield ANY;
                    Set<String> joined = new LinkedHashSet<>();
                    targets.forEach(target -> addAll(joined,
                            returns.getOrDefault(target.linkageName(), Set.of())));
                    yield joined;
                }
            };
        }

        private void consume(IrInstruction instruction) {
            if (instruction instanceof IrFieldStoreInstruction store) {
                if (store.value().type().isReference()) {
                    changed |= join(fields, key(store.field()), value(store.value()));
                }
            } else if (instruction instanceof IrArrayStoreInstruction store) {
                if (store.value().type().isReference()) {
                    storeElements(store.array(), value(store.value()));
                }
            } else if (instruction instanceof IrSystemArrayCopyInstruction copy) {
                copy(copy.source(), copy.destination());
            } else if (instruction instanceof IrCallInstruction
                    || instruction instanceof IrVirtualCallInstruction
                    || instruction instanceof IrInterfaceCallInstruction) {
                List<IrFunction> targets = callTargets.apply(instruction);
                List<IrOperand> arguments = ClosedWorldEffectAnalyzer.callArguments(instruction);
                if (targets.stream().anyMatch(ValueClasses::isArrayCopy) && arguments.size() == 5) {
                    copy(arguments.get(0), arguments.get(2));
                    return;
                }
                for (IrFunction target : targets) {
                    for (int index = 0; index < arguments.size(); index++) {
                        if (arguments.get(index).type().isReference()) {
                            changed |= join(parameters.computeIfAbsent(target.linkageName(),
                                    ignored -> new HashMap<>()), index, value(arguments.get(index)));
                        }
                    }
                }
            }
        }

        /** An element copy; primitive arrays carry no references. */
        private void copy(IrOperand source, IrOperand destination) {
            IrType sourceType = arrayType(source);
            IrType destinationType = arrayType(destination);
            if (sourceType != null && !sourceType.elementType().isReference()
                    || destinationType != null && !destinationType.elementType().isReference()) {
                return;
            }
            storeElements(destination, elementsOf(value(source)));
        }

        private void storeElements(IrOperand array, Set<String> stored) {
            Set<String> targets = value(array);
            if (targets.contains(UNKNOWN)) {
                // An array of unknown origin may be any array site its type allows.
                IrType type = arrayType(array);
                String elementType = type == null ? UNKNOWN : typeName(type.elementType());
                changed |= join(unknownArrayElements, elementType, stored);
                return;
            }
            for (String target : targets) {
                if (target.startsWith(SITE)) changed |= join(elements, target, stored);
            }
        }

        /** The array type of an operand, seen through reference conversions. */
        private IrType arrayType(IrOperand operand) {
            IrOperand current = operand;
            while (!current.type().isArray()) {
                if (!(current instanceof IrValueReference reference)
                        || !conversions.containsKey(reference.id())) {
                    return null;
                }
                current = conversions.get(reference.id());
            }
            return current.type();
        }

        private Set<String> value(IrOperand operand) {
            if (operand instanceof IrNull) return Set.of();
            if (operand instanceof IrImmortalObject immortal) {
                return Set.of(immortal.storageType().referenceName());
            }
            if (operand instanceof IrValueReference reference && defined.contains(reference.id())) {
                return values.getOrDefault(reference.id(), Set.of());
            }
            if (knownParameters && operand instanceof IrValueReference reference
                    && parameterIndexes.containsKey(reference.id())) {
                return parameters.getOrDefault(function.linkageName(), Map.of())
                        .getOrDefault(parameterIndexes.get(reference.id()), Set.of());
            }
            return ANY;
        }
    }

    /**
     * Elements of an array value: those stored into its possible sites and, for each
     * site, into arrays of unknown origin whose element type the site fits. Class names
     * in the value are objects, which a checked array access excludes.
     */
    private Set<String> elementsOf(Set<String> array) {
        if (array.contains(UNKNOWN)) return ANY;
        Set<String> result = new LinkedHashSet<>();
        for (String site : array) {
            if (!site.startsWith(SITE)) continue;
            addAll(result, elements.getOrDefault(site, Set.of()));
            String siteType = siteTypes.getOrDefault(site, UNKNOWN);
            unknownArrayElements.forEach((elementType, stored) -> {
                if (fits(siteType, elementType)) addAll(result, stored);
            });
        }
        return result;
    }

    /** Whether an array allocated with {@code allocated} elements fits a {@code target} array type. */
    private boolean fits(String allocated, String target) {
        return allocated.equals(target) || target.equals(UNKNOWN) || allocated.equals(UNKNOWN)
                || subtype.test(allocated, target);
    }

    private static boolean isArrayCopy(IrFunction function) {
        return function.ownerClass().equals("ironwood.lang.System")
                && function.sourceName().equals("arraycopy");
    }

    private static String typeName(IrType type) {
        IrType erased = type.erasure();
        return erased.isNominalReference() ? erased.referenceName() : UNKNOWN;
    }

    private static String key(IrField field) {
        return field.ownerClass() + "#" + field.name();
    }

    private static List<IrInstruction> operations(IrBasicBlock block) {
        if (!(block.terminator() instanceof IrInvokeTerminator invoke)) return block.instructions();
        List<IrInstruction> operations = new java.util.ArrayList<>(block.instructions());
        operations.add(invoke.call());
        return operations;
    }

    private static void addAll(Set<String> target, Set<String> addition) {
        if (target.contains(UNKNOWN)) return;
        if (addition.contains(UNKNOWN)) {
            target.clear();
            target.add(UNKNOWN);
        } else {
            target.addAll(addition);
        }
    }

    private static <K> boolean join(Map<K, Set<String>> sets, K key, Set<String> addition) {
        Set<String> current = sets.computeIfAbsent(key, ignored -> new LinkedHashSet<>());
        int before = current.size();
        boolean wasUnknown = current.contains(UNKNOWN);
        addAll(current, addition);
        return current.size() != before || current.contains(UNKNOWN) != wasUnknown;
    }
}
