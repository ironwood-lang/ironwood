// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.ir.*;

import java.util.ArrayList;
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
 * The objects values may be at run time, closed-world and object-sensitive (D284, D285).
 * An object is an allocation site qualified by the object its allocating body ran on, so
 * two instances of a container keep their own fields and arrays. A body is analyzed once
 * per object in its first parameter: a call whose first argument is a known object
 * dispatches on that object's class and passes it alone, and every object may be
 * destroyed, so each object's destructor runs on that object. Fields and array elements
 * join what is stored through the objects that may hold them, static fields their
 * initial value and stores, and calls return what their targets return. Everything else is unknown, which keeps every subclass of the static
 * type.
 *
 * <p>Reference fields are written only by field stores: immortal objects carry
 * primitive field values, and the runtime keeps traces and secondary exceptions in its
 * own tables. A store through an unknown receiver or into an array of unknown origin
 * joins every object or array it could reach. In a closed executable, with one entry
 * point and no foreign calls, the entry point and class initializers are the only
 * roots and parameters are what callers pass; otherwise every body is also a root,
 * called by unseen code with unknown arguments except in private callables, whose
 * callers are all visible. {@code System.arraycopy} is modeled where it is called, as a
 * copy between its arguments.</p>
 */
final class ValueClasses {
    /** Any value the static type allows. Absorbs everything joined with it. */
    private static final String UNKNOWN = "*";
    private static final Set<String> ANY = Set.of(UNKNOWN);
    /** The context of a body whose first parameter is not a known object. */
    private static final String SHARED = "static";
    private static final String IMMORTAL = "immortal:";

    /** A body analyzed for one context: an object, {@link #SHARED} or {@link #UNKNOWN}. */
    record Pair(String function, String context) {
    }

    private final Map<String, IrFunction> functions = new LinkedHashMap<>();
    private final Function<IrInstruction, List<IrFunction>> callTargets;
    private final Function<String, IrClass> classes;
    private final BiPredicate<String, String> subtype;
    private final boolean closedExecutable;
    private final Set<String> privateCallables;
    private final Set<String> storedFields = new HashSet<>();
    // Object identity: its class, or for an array its element type.
    private final Map<String, String> objectClasses = new HashMap<>();
    private final Map<String, String> arrayTypes = new HashMap<>();
    private final Map<String, Set<String>> heap = new HashMap<>();
    private final Map<String, Set<String>> fieldsOfAll = new HashMap<>();
    private final Map<String, Set<String>> unknownReceiverStores = new HashMap<>();
    private final Map<String, Set<String>> statics = new HashMap<>();
    private final Map<String, Set<String>> elements = new HashMap<>();
    private final Map<String, Set<String>> unknownArrayElements = new HashMap<>();
    private final Map<Pair, Map<Integer, Set<String>>> parameters = new HashMap<>();
    private final Map<Pair, Set<String>> returns = new HashMap<>();
    private final Set<Pair> reached = new LinkedHashSet<>();
    private final Map<Pair, Map<IrInstruction, List<Pair>>> callees = new HashMap<>();
    private final Map<IrInstruction, Set<String>> released = new IdentityHashMap<>();
    private boolean changed;

    /**
     * @param entryPoint the linkage name of a closed executable's entry point, or
     *        {@code null} for a library or bridge, whose callers are not all visible
     * @param privateCallables linkage names of private methods and constructors
     */
    ValueClasses(Collection<IrFunction> input, String entryPoint, Set<String> privateCallables,
                 Function<IrInstruction, List<IrFunction>> callTargets,
                 Function<String, IrClass> classes, BiPredicate<String, String> subtype) {
        input.forEach(function -> functions.put(function.linkageName(), function));
        this.callTargets = callTargets;
        this.classes = classes;
        this.subtype = subtype;
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
        for (IrFunction function : functions.values()) {
            if (function.kind() == IrCallableKind.CLASS_INITIALIZER) {
                reach(new Pair(function.linkageName(), SHARED));
            } else if (closedExecutable ? function.linkageName().equals(entryPoint)
                    : !isArrayCopy(function) && !privateCallables.contains(function.linkageName())) {
                Pair root = new Pair(function.linkageName(), UNKNOWN);
                for (int index = 0; index < function.parameters().size(); index++) {
                    join(parameters.computeIfAbsent(root, ignored -> new HashMap<>()), index, ANY);
                }
                reach(root);
            }
        }
        do {
            changed = false;
            for (Pair pair : List.copyOf(reached)) new Body(pair).flow(false);
        } while (changed);
        for (Pair pair : List.copyOf(reached)) new Body(pair).flow(true);
    }

    /**
     * The classes the value a free releases, or an element a destruction releases, may
     * be in any context; {@code null} when unknown.
     */
    Set<String> released(IrInstruction instruction) {
        Set<String> classes = released.get(instruction);
        return classes == null || classes.contains(UNKNOWN) ? null : classes;
    }

    /**
     * The objects of exactly {@code className} that reached code allocates; immortal
     * objects are never destroyed.
     */
    List<String> objectsOf(String className) {
        return objectClasses.entrySet().stream()
                .filter(entry -> entry.getValue().equals(className)
                        && !entry.getKey().startsWith(IMMORTAL))
                .map(Map.Entry::getKey).sorted().toList();
    }

    /**
     * The bodies a call, free or destruction in {@code pair} runs, each with its context;
     * {@code null} when the pair was not analyzed or the operation runs no body.
     */
    List<Pair> callees(Pair pair, IrInstruction instruction) {
        Map<IrInstruction, List<Pair>> recorded = callees.get(pair);
        return recorded == null ? null : recorded.get(instruction);
    }

    boolean reached(Pair pair) {
        return reached.contains(pair);
    }

    private void reach(Pair pair) {
        if (functions.containsKey(pair.function()) && reached.add(pair)) {
            changed = true;
        }
    }

    private final class Body {
        private final Pair pair;
        private final IrFunction function;
        private final Set<Integer> defined = new HashSet<>();
        private final Map<Integer, Set<String>> values = new HashMap<>();
        private final Map<Integer, Integer> parameterIndexes = new HashMap<>();
        private final Map<Integer, IrOperand> conversions;
        private final boolean knownParameters;
        private final Map<IrInstruction, List<Pair>> recorded = new IdentityHashMap<>();

        Body(Pair pair) {
            this.pair = pair;
            this.function = functions.get(pair.function());
            this.conversions = ClosedWorldEffectAnalyzer.referenceConversions(function);
            for (int index = 0; index < function.parameters().size(); index++) {
                parameterIndexes.put(function.parameters().get(index).value().id(), index);
            }
            // Unseen code may call a non-private body with anything; the runtime calls
            // destructors and rollbacks on objects it was given.
            this.knownParameters = closedExecutable || privateCallables.contains(function.linkageName());
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
                            changed |= join(returns, pair, value(value)));
                }
            }
            if (record) {
                callees.put(pair, recorded);
                for (IrBasicBlock block : function.blocks()) {
                    for (IrInstruction instruction : operations(block)) {
                        Set<String> value = instruction instanceof IrFreeInstruction free
                                ? value(free.allocation())
                                : instruction instanceof IrDestroyArrayElementsInstruction destroy
                                ? elementsOf(value(destroy.array())) : null;
                        if (value != null) {
                            Set<String> joined = released.computeIfAbsent(instruction,
                                    ignored -> new LinkedHashSet<>());
                            addAll(joined, classesOf(value));
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
                case IrStaticFieldLoadInstruction load -> load.result();
                case IrArrayLoadInstruction load -> load.result();
                default -> ClosedWorldEffectAnalyzer.callResult(instruction);
            };
            return result != null && result.type().isReference() ? result : null;
        }

        private Set<String> produced(IrInstruction instruction) {
            return switch (instruction) {
                case IrAllocateInstruction allocate -> {
                    String object = object(allocate.result());
                    objectClasses.put(object, allocate.className());
                    // Every object may be destroyed, by the destructor of its class.
                    destructorOf(allocate.className())
                            .ifPresent(destructor -> reach(new Pair(destructor, object)));
                    yield Set.of(object);
                }
                case IrArrayAllocateInstruction allocate -> {
                    String array = object(allocate.result());
                    arrayTypes.put(array, typeName(allocate.elementType()));
                    yield Set.of(array);
                }
                case IrReferenceConversionInstruction conversion -> value(conversion.value());
                case IrPhiInstruction phi -> {
                    Set<String> joined = new LinkedHashSet<>();
                    phi.incoming().forEach(incoming -> addAll(joined, value(incoming.value())));
                    yield joined;
                }
                case IrFieldLoadInstruction load -> {
                    String field = key(load.field());
                    if (!storedFields.contains(field)) yield ANY;
                    Set<String> loaded = new LinkedHashSet<>(
                            unknownReceiverStores.getOrDefault(field, Set.of()));
                    Set<String> receivers = value(load.receiver());
                    if (receivers.contains(UNKNOWN)) {
                        addAll(loaded, fieldsOfAll.getOrDefault(field, Set.of()));
                    } else {
                        receivers.forEach(receiver ->
                                addAll(loaded, heap.getOrDefault(receiver + "#" + field, Set.of())));
                    }
                    yield loaded;
                }
                case IrStaticFieldLoadInstruction load -> {
                    Set<String> loaded = new LinkedHashSet<>(value(load.field().initialValue()));
                    addAll(loaded, statics.getOrDefault(staticKey(load.field()), Set.of()));
                    yield loaded;
                }
                case IrArrayLoadInstruction load -> elementsOf(value(load.array()));
                default -> {
                    List<Pair> pairs = calls(instruction);
                    if (pairs.isEmpty()) yield ANY;
                    Set<String> joined = new LinkedHashSet<>();
                    pairs.forEach(callee -> addAll(joined, returns.getOrDefault(callee, Set.of())));
                    yield joined;
                }
            };
        }

        private void consume(IrInstruction instruction) {
            switch (instruction) {
                case IrFieldStoreInstruction store -> {
                    if (!store.value().type().isReference()) return;
                    String field = key(store.field());
                    Set<String> stored = value(store.value());
                    Set<String> receivers = value(store.receiver());
                    changed |= join(fieldsOfAll, field, stored);
                    if (receivers.contains(UNKNOWN)) {
                        changed |= join(unknownReceiverStores, field, stored);
                    } else {
                        for (String receiver : receivers) {
                            changed |= join(heap, receiver + "#" + field, stored);
                        }
                    }
                }
                case IrStaticFieldStoreInstruction store -> {
                    if (store.value().type().isReference()) {
                        changed |= join(statics, staticKey(store.field()), value(store.value()));
                    }
                }
                case IrArrayStoreInstruction store -> {
                    if (store.value().type().isReference()) {
                        storeElements(store.array(), value(store.value()));
                    }
                }
                case IrSystemArrayCopyInstruction copy -> copy(copy.source(), copy.destination());
                case IrCallInstruction call -> invoke(call);
                case IrVirtualCallInstruction call -> invoke(call);
                case IrInterfaceCallInstruction call -> invoke(call);
                case IrEnsureTypeInitializedInstruction ensure -> record(ensure,
                        callTargets.apply(ensure).stream()
                                .map(target -> new Pair(target.linkageName(), SHARED)).toList());
                case IrFreeInstruction free -> destroy(free, value(free.allocation()));
                case IrDestroyArrayElementsInstruction destroy ->
                        destroy(destroy, elementsOf(value(destroy.array())));
                case IrRollbackInstruction rollback -> {
                    List<Pair> pairs = new ArrayList<>();
                    for (String object : value(rollback.allocation())) {
                        if (object.equals(UNKNOWN)) {
                            functions.values().stream()
                                    .filter(target -> target.kind() == IrCallableKind.CONSTRUCTOR_ROLLBACK)
                                    .forEach(target -> pairs.add(unknownReceiver(target)));
                        } else {
                            IrClass type = classes.apply(objectClasses.getOrDefault(object, ""));
                            if (type != null) type.constructorRollback()
                                    .ifPresent(target -> pairs.add(new Pair(target, object)));
                        }
                    }
                    record(rollback, pairs);
                }
                default -> {
                }
            }
        }

        private void invoke(IrInstruction instruction) {
            List<IrOperand> arguments = ClosedWorldEffectAnalyzer.callArguments(instruction);
            if (callTargets.apply(instruction).stream().anyMatch(ValueClasses::isArrayCopy)
                    && arguments.size() == 5) {
                copy(arguments.get(0), arguments.get(2));
                record(instruction, List.of());
                return;
            }
            List<Pair> pairs = calls(instruction);
            for (Pair callee : pairs) {
                Map<Integer, Set<String>> passed = parameters.computeIfAbsent(callee, ignored -> new HashMap<>());
                for (int index = 0; index < arguments.size(); index++) {
                    if (!arguments.get(index).type().isReference()) continue;
                    // An object context fixes the first parameter to that object.
                    Set<String> argument = index == 0 && isContext(callee.context())
                            ? Set.of(callee.context()) : value(arguments.get(index));
                    changed |= join(passed, index, argument);
                }
            }
            record(instruction, pairs);
        }

        /** The bodies a call runs: one per object its first argument may be. */
        private List<Pair> calls(IrInstruction instruction) {
            List<IrFunction> targets = callTargets.apply(instruction);
            List<IrOperand> arguments = ClosedWorldEffectAnalyzer.callArguments(instruction);
            if (targets.isEmpty()) return List.of();
            Set<String> first = !arguments.isEmpty() && arguments.getFirst().type().isReference()
                    ? value(arguments.getFirst()) : Set.of();
            boolean dispatched = instruction instanceof IrVirtualCallInstruction
                    || instruction instanceof IrInterfaceCallInstruction;
            List<Pair> pairs = new ArrayList<>();
            if (first.isEmpty()) {
                targets.forEach(target -> pairs.add(new Pair(target.linkageName(), SHARED)));
            }
            for (String object : first) {
                if (object.equals(UNKNOWN)) {
                    targets.forEach(target -> pairs.add(unknownReceiver(target)));
                    continue;
                }
                List<IrFunction> selected = dispatched && objectClasses.containsKey(object)
                        ? dispatch(instruction, objectClasses.get(object)) : targets;
                selected.forEach(target -> pairs.add(new Pair(target.linkageName(), object)));
            }
            List<Pair> distinct = pairs.stream().distinct().toList();
            distinct.forEach(ValueClasses.this::reach);
            return distinct;
        }

        private void destroy(IrInstruction instruction, Set<String> objects) {
            List<Pair> pairs = new ArrayList<>();
            for (String object : objects) {
                if (object.equals(UNKNOWN)) {
                    callTargets.apply(instruction).forEach(target -> pairs.add(unknownReceiver(target)));
                } else if (objectClasses.containsKey(object)) {
                    destructorOf(objectClasses.get(object))
                            .ifPresent(destructor -> pairs.add(new Pair(destructor, object)));
                }
            }
            List<Pair> distinct = pairs.stream().distinct().toList();
            distinct.forEach(ValueClasses.this::reach);
            record(instruction, distinct);
        }

        private Pair unknownReceiver(IrFunction target) {
            Pair callee = new Pair(target.linkageName(), UNKNOWN);
            if (!target.parameters().isEmpty()) {
                changed |= join(parameters.computeIfAbsent(callee, ignored -> new HashMap<>()), 0, ANY);
            }
            reach(callee);
            return callee;
        }

        private void record(IrInstruction instruction, List<Pair> pairs) {
            List<Pair> previous = recorded.get(instruction);
            if (previous == null) {
                recorded.put(instruction, pairs);
            } else {
                List<Pair> joined = new ArrayList<>(previous);
                pairs.stream().filter(callee -> !joined.contains(callee)).forEach(joined::add);
                recorded.put(instruction, joined);
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
                // An array of unknown origin may be any array its type allows.
                IrType type = arrayType(array);
                String elementType = type == null ? UNKNOWN : typeName(type.elementType());
                changed |= join(unknownArrayElements, elementType, stored);
                return;
            }
            for (String target : targets) {
                if (arrayTypes.containsKey(target)) changed |= join(elements, target, stored);
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

        /** An allocation here: its site, qualified by the object this body runs on. */
        private String object(IrValueReference result) {
            String context = pair.context();
            String qualifier = isContext(context) ? context.substring(0, context.indexOf('|')) : context;
            return function.linkageName() + "@" + result.id() + "|" + qualifier;
        }

        private Set<String> value(IrOperand operand) {
            if (operand instanceof IrNull) return Set.of();
            if (operand instanceof IrImmortalObject immortal) {
                String object = IMMORTAL + immortal.symbol() + "|" + SHARED;
                objectClasses.putIfAbsent(object, immortal.storageType().referenceName());
                return Set.of(object);
            }
            if (operand instanceof IrValueReference reference && defined.contains(reference.id())) {
                return values.getOrDefault(reference.id(), Set.of());
            }
            if (operand instanceof IrValueReference reference
                    && parameterIndexes.containsKey(reference.id())) {
                int index = parameterIndexes.get(reference.id());
                if (index == 0 && isContext(pair.context())) return Set.of(pair.context());
                if (!knownParameters) return ANY;
                return parameters.getOrDefault(pair, Map.of()).getOrDefault(index, Set.of());
            }
            return ANY;
        }
    }

    private List<IrFunction> dispatch(IrInstruction instruction, String className) {
        IrDispatchSlot slot = instruction instanceof IrVirtualCallInstruction call ? call.slot()
                : instruction instanceof IrInterfaceCallInstruction call ? call.slot() : null;
        IrClass type = classes.apply(className);
        if (slot == null || type == null) return callTargets.apply(instruction);
        return type.dispatchEntries().stream()
                .filter(entry -> entry.slot().index() == slot.index())
                .map(entry -> functions.get(entry.targetLinkageName()))
                .filter(java.util.Objects::nonNull).toList();
    }

    private java.util.Optional<String> destructorOf(String className) {
        IrClass type = classes.apply(className);
        return type == null ? java.util.Optional.empty() : type.destructorChain();
    }

    /** Whether a context names an object rather than a shared or unknown receiver. */
    private static boolean isContext(String context) {
        return context.indexOf('|') >= 0;
    }

    /** The classes of the objects in a value; arrays carry no destructor. */
    private Set<String> classesOf(Set<String> value) {
        if (value.contains(UNKNOWN)) return ANY;
        Set<String> result = new LinkedHashSet<>();
        for (String object : value) {
            String type = objectClasses.get(object);
            if (type != null) result.add(type);
        }
        return result;
    }

    /**
     * Elements of an array value: those stored into its possible arrays and, for each,
     * into arrays of unknown origin whose element type it fits. Objects in the value are
     * not arrays, which a checked array access excludes.
     */
    private Set<String> elementsOf(Set<String> array) {
        if (array.contains(UNKNOWN)) return ANY;
        Set<String> result = new LinkedHashSet<>();
        for (String object : array) {
            String arrayType = arrayTypes.get(object);
            if (arrayType == null) continue;
            addAll(result, elements.getOrDefault(object, Set.of()));
            unknownArrayElements.forEach((elementType, stored) -> {
                if (fits(arrayType, elementType)) addAll(result, stored);
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

    private static String staticKey(IrStaticField field) {
        return field.ownerClass() + "#" + field.name();
    }

    private static List<IrInstruction> operations(IrBasicBlock block) {
        if (!(block.terminator() instanceof IrInvokeTerminator invoke)) return block.instructions();
        List<IrInstruction> operations = new ArrayList<>(block.instructions());
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
