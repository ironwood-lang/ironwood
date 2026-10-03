// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import ironwood.compiler.CompilationArtifact;
import ironwood.compiler.ir.IrType;
import ironwood.compiler.semantic.BridgeApiFacts;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Copied data layout only; it grants no native getter, effect or lifetime permission. */
public final class BridgeCustomSnapshotLayout {
    private static final Set<String> GRAPH = Set.of("getMessage", "getCause", "getSecondaryExceptionCount", "getSecondaryException");
    public record Key(String name, IrType type) {}
    public record Slot(int index, Key key) {}
    public record Value(Slot slot, BridgeExceptionProjection.Property property) {}

    private final BridgeExceptionProjection projection;
    private final List<Slot> slots;
    private final Map<String, List<Value>> values;
    private final Map<String, String> builtinBases;

    private BridgeCustomSnapshotLayout(BridgeExceptionProjection projection, List<Slot> slots,
            Map<String, List<Value>> values, Map<String, String> builtinBases) {
        this.projection = projection; this.slots = List.copyOf(slots);
        this.values = Map.copyOf(values); this.builtinBases = Map.copyOf(builtinBases);
    }

    public List<Slot> slots() { return slots; }
    public Map<String, List<Value>> values() { return values; }
    public Map<String, String> builtinBases() { return builtinBases; }
    public boolean matches(CompilationArtifact artifact, BridgeExceptionProjection candidate) {
        return projection == candidate && artifact.valid() && projection.matches(artifact.program().orElseThrow());
    }
    public Slot slot(String name, IrType type) {
        return slots.stream().filter(slot -> slot.key().equals(new Key(name, type))).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("getter has no copied snapshot slot: " + name + ":" + type.displayName()));
    }

    public static BridgeCustomSnapshotLayout create(CompilationArtifact artifact, BridgeExceptionProjection projection) {
        if (!artifact.valid() || !projection.matches(artifact.program().orElseThrow()) || projection.customTypes().isEmpty()) {
            throw new IllegalArgumentException("custom snapshot layout requires a matching complete custom projection");
        }
        var keys = new java.util.TreeSet<Key>(Comparator.comparing(Key::name).thenComparing(key -> BridgeJavaTypes.descriptor(key.type())));
        // Abstract catch declarations still need getter layouts, even if no
        // concrete projected type currently inherits that particular getter.
        for (var type : projection.customTypes().values()) for (var method : type.callables()) {
            if (BridgeCustomExceptionTypes.customMethod(method) && !GRAPH.contains(method.name())) {
                add(keys, method.name(), method.result());
            }
        }
        for (var type : projection.types()) {
            if (!projection.customTypes().containsKey(type.nativeName())) continue;
            for (var property : type.properties()) if (!GRAPH.contains(property.name())) add(keys, property.name(), property.type());
        }
        var slots = new ArrayList<Slot>();
        Map<Key, Slot> indexed = new LinkedHashMap<>();
        for (var key : keys) { var slot = new Slot(slots.size(), key); slots.add(slot); indexed.put(key, slot); }
        Map<String, List<Value>> values = new LinkedHashMap<>();
        for (var type : projection.types()) {
            if (!projection.customTypes().containsKey(type.nativeName())) continue;
            values.put(type.nativeName(), type.properties().stream().filter(property -> !GRAPH.contains(property.name()))
                    .map(property -> new Value(indexed.get(new Key(property.name(), property.type())), property))
                    .sorted(Comparator.comparingInt(value -> value.slot().index())).toList());
        }
        Map<String, String> bases = new LinkedHashMap<>();
        for (var type : projection.customTypes().values()) {
            var seen = new java.util.HashSet<String>();
            BridgeApiFacts.Type current = type;
            while (true) {
                if (!seen.add(current.binaryName()) || current.supertypes().size() != 1) {
                    throw new IllegalArgumentException("custom snapshot has no single complete catch hierarchy");
                }
                var parent = current.supertypes().getFirst();
                if (BridgeExportSurface.isBuiltinThrowable(parent)) { bases.put(type.binaryName(), parent.referenceName()); break; }
                current = projection.customTypes().get(parent.referenceName());
                if (current == null) throw new IllegalArgumentException("custom snapshot superclass is absent from projection");
            }
        }
        return new BridgeCustomSnapshotLayout(projection, slots, values, bases);
    }

    private static void add(Set<Key> keys, String name, IrType type) {
        if (!BridgeCustomExceptionTypes.copyable(type)) throw new IllegalArgumentException("noncopyable custom snapshot property: " + name);
        keys.add(new Key(name, type));
    }
}
