// SPDX-License-Identifier: MIT OR Apache-2.0

import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Java 21 reference for the keyed snapshot contracts that native fixtures
 * assert. Value maps use Map.copyOf, as OwnershipSnapshot does for String
 * keys. Allocation keys have identity equality, so their Map.copyOf and
 * Set.copyOf copies behave as identity copies; the probe uses explicit identity
 * containers to show that contract with equal-looking keys. Slot maps use the
 * D247 null-rejecting linked copy. This checks logical behavior only, never
 * native ownership or reclamation.
 */
public final class KeyedSnapshotReference {

    private record Item(int id) {
        @Override public int hashCode() { return id % 2; }
    }

    /** Mirrors AllocationInfo: no equality override. */
    private static final class Allocation { }

    public static void main(String[] args) {
        value();
        identity();
        linked();
        set();
        allocationKeys();
        nulls();
        System.out.println("selected keyed snapshot contracts pass");
    }

    private static void value() {
        Item key = new Item(7), equal = new Item(7), other = new Item(9), later = new Item(11);
        Object first = new Object(), second = new Object();
        Map<Item, Object> source = new HashMap<>();
        source.put(key, first);
        source.put(other, second);
        Map<Item, Object> snapshot = Map.copyOf(source);
        source.clear();
        source.put(later, second);
        require(snapshot.size() == 2 && snapshot.get(key) == first && snapshot.get(other) == second, "value members");
        require(snapshot.get(equal) == first && snapshot.containsKey(equal), "value equality lookup");
        require(snapshot.get(later) == null && !snapshot.containsKey(later), "value independence");
    }

    private static void identity() {
        Item key = new Item(7), equal = new Item(7), other = new Item(9), later = new Item(11);
        Object first = new Object(), second = new Object();
        Map<Item, Object> source = new IdentityHashMap<>();
        source.put(key, first);
        source.put(other, second);
        Map<Item, Object> snapshot = Collections.unmodifiableMap(new IdentityHashMap<>(source));
        source.clear();
        source.put(later, second);
        require(snapshot.size() == 2 && snapshot.get(key) == first && snapshot.get(other) == second, "identity members");
        require(snapshot.get(equal) == null && !snapshot.containsKey(equal), "identity lookup ignores equals");
        require(snapshot.get(later) == null && !snapshot.containsKey(later), "identity independence");
    }

    private static void linked() {
        Item key = new Item(7), equal = new Item(7), other = new Item(9), later = new Item(11);
        Object first = new Object(), second = new Object();
        Map<Item, Object> source = new LinkedHashMap<>();
        source.put(key, first);
        source.put(other, second);
        Map<Item, Object> snapshot = copyArraySlots(source);
        source.clear();
        source.put(later, second);
        require(snapshot.size() == 2 && snapshot.get(key) == first && snapshot.get(other) == second, "linked members");
        require(snapshot.get(equal) == first && snapshot.containsKey(equal), "linked equality lookup");
        require(snapshot.get(later) == null && !snapshot.containsKey(later), "linked independence");
        require(snapshot.keySet().iterator().next() == key, "linked encounter order");
    }

    private static void set() {
        Item key = new Item(7), equal = new Item(7), later = new Item(9);
        Set<Item> source = Collections.newSetFromMap(new IdentityHashMap<>());
        source.add(key);
        Set<Item> copy = Collections.newSetFromMap(new IdentityHashMap<>());
        copy.addAll(source);
        Set<Item> snapshot = Collections.unmodifiableSet(copy);
        source.clear();
        source.add(later);
        require(snapshot.size() == 1 && snapshot.contains(key), "identity set member");
        require(!snapshot.contains(equal) && !snapshot.contains(later), "identity set lookup and independence");
    }

    private static void allocationKeys() {
        Allocation key = new Allocation(), other = new Allocation();
        Map<Allocation, Object> live = new HashMap<>();
        live.put(key, "state");
        Map<Allocation, Object> byCopyOf = Map.copyOf(live);
        require(byCopyOf.containsKey(key) && !byCopyOf.containsKey(other), "Map.copyOf identity-equal keys");
        require(Set.copyOf(Set.of(key)).contains(key) && !Set.copyOf(Set.of(key)).contains(other),
                "Set.copyOf identity-equal items");
    }

    private static void nulls() {
        requireNull(() -> Map.copyOf(null), "Map.copyOf null source");
        requireNull(() -> Set.copyOf(null), "Set.copyOf null source");
        requireNull(() -> copyArraySlots(null), "slot copy null source");
        Map<Item, Object> nullValue = new HashMap<>();
        nullValue.put(new Item(1), null);
        requireNull(() -> Map.copyOf(nullValue), "Map.copyOf null value");
        requireNull(() -> copyArraySlots(nullValue), "slot copy null value");
    }

    private static <K, V> Map<K, V> copyArraySlots(Map<K, V> source) {
        Map<K, V> copy = new LinkedHashMap<>();
        source.forEach((slot, allocation) -> copy.put(Objects.requireNonNull(slot), Objects.requireNonNull(allocation)));
        return Collections.unmodifiableMap(copy);
    }

    private static void requireNull(Runnable action, String label) {
        try {
            action.run();
        } catch (NullPointerException expected) {
            return;
        }
        throw new AssertionError(label);
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
