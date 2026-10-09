// SPDX-License-Identifier: MIT OR Apache-2.0
package ironwood.compiler.semantic;

import ironwood.compiler.UnfreedMode;
import ironwood.compiler.ast.AccessModifier;
import ironwood.compiler.ast.DeclaredType;
import ironwood.compiler.ir.IrCallableKind;
import ironwood.compiler.ir.IrType;
import ironwood.compiler.lexer.Lexer;
import ironwood.compiler.parser.Parser;
import ironwood.compiler.source.SourceFile;
import ironwood.compiler.source.SourcePosition;
import ironwood.compiler.source.SourceSpan;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Test-only calls to real private proof snapshots; setup and projection are separate. */
final class OwnershipWork implements KernelCapture.Work {
    private static final int ITERATIONS = 64;
    private final FunctionAnalyzer analyzer;
    private final Object[] nodes;
    private final Object[] slots;
    private final Constructor<?> slotConstructor;
    private final Method snapshot, restore, merge;
    private final Field state, detached;
    private final Object active, escaped, uncertain;
    private final Map<Object, Object> knownSlots, fields, pools, borrows;
    private final Set<Object> exposed;
    private final UnfreedAllocationTracker<Object> unfreed;
    private final RejectedFreeEvidence evidence;
    private final RejectedFreeEvidence.Budget budget;
    private final SourceFile source;
    private final boolean reverse, tight;
    private final List<Object> versions = new ArrayList<>();
    private Object mergedVersion;
    private int completed;
    private long copiedEntries;

    private static void require(boolean condition) {
        if (!condition) throw new AssertionError("ownership kernel contract");
    }
    private static Field field(Class<?> type, String name) throws ReflectiveOperationException {
        Field result = type.getDeclaredField(name); result.setAccessible(true); return result;
    }
    private static Method method(Class<?> type, String name, Class<?>... parameters) throws ReflectiveOperationException {
        Method result = type.getDeclaredMethod(name, parameters); result.setAccessible(true); return result;
    }
    @SuppressWarnings("unchecked")
    private static <T> T read(Object owner, String name) throws ReflectiveOperationException {
        return (T)field(owner.getClass(), name).get(owner);
    }
    private static Object value(Object record, String name) throws ReflectiveOperationException {
        return method(record.getClass(), name).invoke(record);
    }
    @SuppressWarnings("unchecked")
    private static Map<Object, Object> map(Object record, String name) throws ReflectiveOperationException {
        return (Map<Object, Object>)value(record, name);
    }
    OwnershipWork(int size, int shape, boolean explain) throws Exception {
        require(size >= 8 && size <= 128 && shape >= 1 && shape <= 4);
        reverse = shape % 2 == 0;
        tight = shape >= 3;
        source = SourceFile.of("SnapshotHost.iron", "class SnapshotHost {}\n");
        var unit = new Parser(source, new Lexer(source).lex().tokens()).parse().unit().orElseThrow();
        TypeSymbol type = new TypeSymbol(new DeclaredType(unit, unit.declaration(), List.of(), "SnapshotHost", "SnapshotHost"), false);
        var types = Map.of("SnapshotHost", type);
        var resolver = new TypeResolver(types);
        var hierarchy = new ClassHierarchy(types, resolver, new GenericTypeSystem(types, resolver));
        SourceSpan span = SourceSpan.at(new SourcePosition(0, 1, 1));
        var callable = new CallableSymbol("SnapshotHost", "work", AccessModifier.PUBLIC, true,
                IrCallableKind.METHOD, IrType.VOID, List.of(), List.of(), Optional.empty(), Optional.empty(),
                Optional.empty(), false, false, false, span, span, "SnapshotHost_work");
        analyzer = new FunctionAnalyzer(source, callable, hierarchy, null, null, new StringPool(),
                new ArrayList<>(), new LinkedHashMap<>());
        Class<?> nodeType = Class.forName("ironwood.compiler.semantic.FunctionAnalyzer$AllocationInfo");
        Class<?> slotType = Class.forName("ironwood.compiler.semantic.FunctionAnalyzer$ArraySlot");
        Class<?> snapshotType = Class.forName("ironwood.compiler.semantic.FunctionAnalyzer$OwnershipSnapshot");
        Constructor<?> nodeConstructor = nodeType.getDeclaredConstructor(int.class);
        nodeConstructor.setAccessible(true);
        slotConstructor = slotType.getDeclaredConstructor(nodeType, int.class); slotConstructor.setAccessible(true);
        snapshot = method(FunctionAnalyzer.class, "snapshotOwnership");
        restore = method(FunctionAnalyzer.class, "restoreOwnership", snapshotType);
        merge = method(FunctionAnalyzer.class, "mergeOwnership", snapshotType, List.class, String.class);
        state = field(nodeType, "state"); detached = field(nodeType, "detached");
        Object[] states = state.getType().getEnumConstants();
        Object foundActive = null, foundEscaped = null, foundUncertain = null;
        for (Object candidate : states) {
            String name = ((Enum<?>)candidate).name();
            if (name.equals("ACTIVE")) foundActive = candidate;
            if (name.equals("ESCAPED")) foundEscaped = candidate;
            if (name.equals("UNCERTAIN")) foundUncertain = candidate;
        }
        active = foundActive; escaped = foundEscaped; uncertain = foundUncertain;
        nodes = new Object[size]; slots = new Object[size];
        List<Object> allocations = read(analyzer, "allocations");
        for (int i = 0; i < size; i++) { nodes[i] = nodeConstructor.newInstance(i % 3); allocations.add(nodes[i]); }
        knownSlots = read(analyzer, "knownArraySlots"); fields = read(analyzer, "borrowedOwnedFields");
        pools = read(analyzer, "poolOwners"); borrows = read(analyzer, "retainedBorrows");
        exposed = read(analyzer, "exposedContainerContents");
        unfreed = new UnfreedAllocationTracker<>(source, UnfreedMode.WARN);
        field(FunctionAnalyzer.class, "unfreed").set(analyzer, unfreed);
        budget = explain ? new RejectedFreeEvidence.Budget(tight ? 20 : 1_048_576) : null;
        evidence = explain ? new RejectedFreeEvidence(budget, 1_048_576, 1_048_576) : null;
        field(FunctionAnalyzer.class, "rejectedFreeEvidence").set(analyzer, evidence);
        for (int step = 0; step < size; step++) {
            int i = reverse ? size - step - 1 : step;
            slots[i] = slotConstructor.newInstance(nodes[7], i * 65_537);
            knownSlots.put(slots[i], nodes[(i + 3) % size]);
            fields.put(new String("field" + i), nodes[i]);
            unfreed.register(nodes[i], span, "allocation", true);
            if (evidence != null) {
                evidence.origin(nodes[i], source, span);
                evidence.arrayStore(slots[i], source, span);
            }
        }
        pools.put(nodes[4], nodes[5]);
        borrows.put(nodes[7], Set.of(nodes[1], nodes[2]));
        exposed.add(nodes[1]);
    }
    @Override public void run() {
        try {
            for (int iteration = 0; iteration < ITERATIONS; iteration++) {
                Object before = snapshot.invoke(analyzer); versions.add(before);
                require(map(before, "states").size() == nodes.length);
                require(map(before, "knownArraySlots").size() == nodes.length);
                Object equalSlot = slotConstructor.newInstance(nodes[7], 0);
                require(equalSlot.equals(slots[0]) && knownSlots.get(equalSlot) == nodes[3]);
                require(!slotConstructor.newInstance(nodes[6], 0).equals(equalSlot));
                state.set(nodes[0], escaped); detached.set(nodes[1], true);
                knownSlots.remove(equalSlot); fields.remove(new String("field0"));
                pools.put(nodes[4], nodes[6]); borrows.put(nodes[7], Set.of(nodes[2], nodes[3]));
                exposed.clear(); exposed.add(nodes[2]); unfreed.consumed(nodes[2]);
                Object changed = snapshot.invoke(analyzer); versions.add(changed);
                // The saved state value must not read the subsequently mutated node.
                require(value(map(before, "states").get(nodes[0]), "state") == active);
                require(map(before, "knownArraySlots").get(equalSlot) == nodes[3]);
                require(map(before, "borrowedOwnedFields").containsKey("field0"));
                require(map(before, "poolOwners").get(nodes[4]) == nodes[5]);
                require(map(before, "retainedBorrows").get(nodes[7]).equals(Set.of(nodes[1], nodes[2])));
                require(value(before, "exposedContainerContents").equals(Set.of(nodes[1])));
                require(((Set<?>)value(before, "unfreedLive")).contains(nodes[2]));
                restore.invoke(analyzer, before);
                require(state.get(nodes[0]) == active && !(boolean)detached.get(nodes[1]));
                List<Object> incoming = reverse ? List.of(changed, before) : List.of(before, changed);
                merge.invoke(analyzer, before, incoming, "kernel conflict");
                require(state.get(nodes[0]) == uncertain && state.get(nodes[1]) == uncertain);
                require(state.get(nodes[3]) == uncertain && state.get(nodes[5]) == uncertain && state.get(nodes[6]) == uncertain);
                require(!knownSlots.containsKey(equalSlot) && knownSlots.size() == nodes.length - 1);
                require(!fields.containsKey("field0") && fields.size() == nodes.length - 1);
                require(pools.get(nodes[4]) == (reverse ? nodes[6] : nodes[5]));
                require(borrows.get(nodes[7]).equals(Set.of(nodes[1], nodes[2], nodes[3])));
                require(exposed.equals(Set.of(nodes[1], nodes[2])) && unfreed.snapshot().size() == nodes.length - 1);
                copiedEntries += (long)nodes.length * 8 + 3;
                if (iteration == ITERATIONS - 1) mergedVersion = snapshot.invoke(analyzer);
                restore.invoke(analyzer, before);
                completed++;
            }
            if (evidence != null) evidence.close();
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("private seed operation failed", failure); }
    }
    @Override public String verify() {
        try {
            require(completed == ITERATIONS && versions.size() == ITERATIONS * 2);
            require(state.get(nodes[0]) == active && knownSlots.size() == nodes.length && fields.size() == nodes.length);
            require(pools.get(nodes[4]) == nodes[5] && exposed.equals(Set.of(nodes[1])));
            require(unfreed.snapshot().size() == nodes.length);
            if (evidence != null) require(budget.live() == 0 && budget.stopped() == tight);
            Object saved = versions.getFirst();
            for (String name : List.of("states", "knownArraySlots", "borrowedOwnedFields", "retainedBorrows", "poolOwners")) {
                try { map(saved, name).clear(); throw new AssertionError("mutable ownership snapshot"); }
                catch (UnsupportedOperationException expected) { require(!map(saved, name).isEmpty()); }
            }
            for (String name : List.of("exposedContainerContents", "unfreedLive")) {
                try { ((Set<?>)value(saved, name)).clear(); throw new AssertionError("mutable ownership set"); }
                catch (UnsupportedOperationException expected) { require(!((Set<?>)value(saved, name)).isEmpty()); }
            }
            return "iterations=" + completed + "\nversions=" + versions.size() + "\nsize=" + nodes.length
                    + "\nreverse=" + reverse + "\ntight=" + tight + "\nexplain=" + (evidence != null)
                    + "\nretainedVersionEntries=" + copiedEntries + "\nfinalBudgetLive=" + (budget == null ? 0 : budget.live())
                    + "\nbudgetHighWater=" + (budget == null ? 0 : budget.highWater()) + "\n"
                    + projection("before", versions.get(0)) + projection("changed", versions.get(1))
                    + projection("merged", mergedVersion);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("private result inspection failed", failure); }
    }
    private String projection(String label, Object version) throws ReflectiveOperationException {
        StringBuilder result = new StringBuilder();
        Map<Object, Object> states = map(version, "states");
        Map<Object, Object> storedSlots = map(version, "knownArraySlots");
        Map<Object, Object> storedFields = map(version, "borrowedOwnedFields");
        Map<Object, Object> storedPools = map(version, "poolOwners");
        Map<Object, Object> storedBorrows = map(version, "retainedBorrows");
        Set<?> storedExposed = (Set<?>)value(version, "exposedContainerContents");
        Set<?> storedLive = (Set<?>)value(version, "unfreedLive");
        // Keyed input identity order projects extensional facts, never diagnostics or IR.
        for (int i = 0; i < nodes.length; i++) {
            Object fact = states.get(nodes[i]);
            String prefix = label + "." + i + ".";
            result.append(prefix).append("state=").append(value(fact, "state")).append('\n');
            result.append(prefix).append("reason=").append(value(fact, "blockingReason")).append('\n');
            result.append(prefix).append("detached=").append(value(fact, "detached")).append('\n');
            result.append(prefix).append("slot=").append(identity(storedSlots.get(slots[i]))).append('\n');
            result.append(prefix).append("field=").append(identity(storedFields.get("field" + i))).append('\n');
            result.append(prefix).append("pool=").append(identity(storedPools.get(nodes[i]))).append('\n');
            Object children = storedBorrows.get(nodes[i]);
            result.append(prefix).append("children=");
            if (children instanceof Set<?> members) for (int j = 0; j < nodes.length; j++) {
                if (members.contains(nodes[j])) result.append(j).append(',');
            }
            result.append('\n').append(prefix).append("exposed=").append(storedExposed.contains(nodes[i])).append('\n');
            result.append(prefix).append("live=").append(storedLive.contains(nodes[i])).append('\n');
        }
        return result.toString();
    }
    private int identity(Object node) {
        if (node == null) return -1;
        for (int i = 0; i < nodes.length; i++) if (nodes[i] == node) return i;
        throw new AssertionError("unknown result node");
    }
}
