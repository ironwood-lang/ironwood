// SPDX-License-Identifier: MIT OR Apache-2.0

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Java 21 reference for compiler_ownership_operations.iron. It applies the
 * seven-field rules of FunctionAnalyzer.snapshotOwnership, restoreOwnership
 * and mergeOwnership (LOCAL_NEW nodes, no explanation store) to one fixed
 * save/mutate/save/restore/join scenario and prints a sorted projection.
 * Logical behavior only; it never decides native ownership or free safety.
 */
public final class OwnershipOperationsReference {

    static final int ACTIVE = 0, ESCAPED = 1, FREED = 2, MAYBE_FREED = 3, UNCERTAIN = 4;

    static final class Node {
        final int id;
        boolean present = true;
        int state = ACTIVE;
        String reason;
        boolean detached;
        Node(int id) { this.id = id; }
    }

    record StateValue(int state, String reason, boolean detached) { }

    record Slot(Node container, int index) {
        @Override public boolean equals(Object value) {
            return value instanceof Slot other && container == other.container && index == other.index;
        }
        @Override public int hashCode() { return System.identityHashCode(container) * 31 + index; }
    }

    record Snapshot(Map<Node, StateValue> states, Map<Slot, Node> slots, Map<String, Node> fields,
                    Map<Node, Set<Node>> retained, Map<Node, Node> pools, Set<Node> exposed, Set<Node> live) {
        Snapshot {
            states = Map.copyOf(states);
            Map<Slot, Node> ordered = new LinkedHashMap<>();
            slots.forEach((slot, node) -> ordered.put(Objects.requireNonNull(slot), Objects.requireNonNull(node)));
            slots = Collections.unmodifiableMap(ordered);
            fields = Map.copyOf(fields);
            retained = Map.copyOf(retained);
            pools = Map.copyOf(pools);
            exposed = Set.copyOf(exposed);
            live = Set.copyOf(live);
        }
    }

    final List<Node> allocations = new ArrayList<>();
    final Map<Slot, Node> slots = new LinkedHashMap<>();
    final Map<String, Node> fields = new LinkedHashMap<>();
    final Map<Node, Set<Node>> retained = new IdentityHashMap<>();
    final Map<Node, Node> pools = new IdentityHashMap<>();
    final Set<Node> exposed = new LinkedHashSet<>();
    final Set<Node> live = new LinkedHashSet<>();
    final Set<Integer> blocked = new TreeSet<>();

    Snapshot save() {
        Map<Node, StateValue> states = new IdentityHashMap<>();
        for (Node node : allocations) {
            if (node.present) states.put(node, new StateValue(node.state, node.reason, node.detached));
        }
        return new Snapshot(states, slots, new LinkedHashMap<>(fields), new IdentityHashMap<>(retained),
                new IdentityHashMap<>(pools), Set.copyOf(exposed), Set.copyOf(live));
    }

    void restore(Snapshot snapshot) {
        live.clear();
        live.addAll(snapshot.live());
        allocations.forEach(node -> node.present = false);
        snapshot.states().forEach((node, state) -> {
            node.present = true;
            node.state = state.state();
            node.reason = state.reason();
            node.detached = state.detached();
        });
        slots.clear();
        slots.putAll(snapshot.slots());
        fields.clear();
        fields.putAll(snapshot.fields());
        retained.clear();
        retained.putAll(snapshot.retained());
        pools.clear();
        pools.putAll(snapshot.pools());
        exposed.clear();
        exposed.addAll(snapshot.exposed());
    }

    void block(Node node, String reason) {
        node.state = UNCERTAIN;
        node.reason = reason;
        blocked.add(node.id);
    }

    void merge(Snapshot before, List<Snapshot> incoming) {
        restore(before);
        Set<Node> joined = Collections.newSetFromMap(new IdentityHashMap<>());
        incoming.forEach(snapshot -> joined.addAll(snapshot.states().keySet()));
        for (Node node : joined) {
            List<StateValue> states = incoming.stream().map(snapshot -> snapshot.states().get(node))
                    .filter(Objects::nonNull).toList();
            StateValue first = states.getFirst();
            node.present = true;
            if (states.stream().allMatch(first::equals)) {
                node.state = first.state();
                node.reason = first.reason();
                node.detached = first.detached();
            } else {
                node.state = states.stream().anyMatch(state -> state.state() == FREED || state.state() == MAYBE_FREED)
                        ? MAYBE_FREED : UNCERTAIN;
                node.reason = node.state == MAYBE_FREED ? "maybe freed" : "conflict";
                node.detached = states.stream().allMatch(StateValue::detached);
            }
        }
        slots.entrySet().removeIf(entry -> incoming.stream().anyMatch(snapshot ->
                snapshot.slots().get(entry.getKey()) != entry.getValue()));
        fields.entrySet().removeIf(entry -> incoming.stream().anyMatch(snapshot ->
                snapshot.fields().get(entry.getKey()) != entry.getValue()));
        for (Map.Entry<Slot, Node> entry : incoming.getFirst().slots().entrySet()) {
            if (incoming.stream().allMatch(snapshot -> snapshot.slots().get(entry.getKey()) == entry.getValue())) {
                slots.put(entry.getKey(), entry.getValue());
            }
        }
        for (Map.Entry<String, Node> entry : incoming.getFirst().fields().entrySet()) {
            if (incoming.stream().allMatch(snapshot -> snapshot.fields().get(entry.getKey()) == entry.getValue())) {
                fields.put(entry.getKey(), entry.getValue());
            }
        }
        pools.clear();
        for (Snapshot path : incoming) {
            path.pools().forEach((value, owner) -> {
                Node previous = pools.putIfAbsent(value, owner);
                if (previous != null && previous != owner) {
                    block(previous, "pool conflict");
                    block(owner, "pool conflict");
                }
            });
        }
        retained.clear();
        exposed.clear();
        for (Snapshot path : incoming) {
            exposed.addAll(path.exposed());
            path.retained().forEach((owner, children) -> {
                Set<Node> merged = new LinkedHashSet<>(retained.getOrDefault(owner, Set.of()));
                merged.addAll(children);
                retained.put(owner, Set.copyOf(merged));
            });
        }
        for (Snapshot path : incoming) {
            path.slots().forEach((slot, node) -> {
                if (slots.get(slot) != node) block(node, "lost slot");
            });
        }
        live.clear();
        live.addAll(incoming.getFirst().live());
        incoming.forEach(snapshot -> live.retainAll(snapshot.live()));
    }

    String project(String label) {
        StringBuilder out = new StringBuilder(label).append('\n');
        for (Node node : allocations) {
            if (node.present) {
                out.append("state ").append(node.id).append(':').append(node.state).append(':')
                        .append(node.reason).append(':').append(node.detached).append('\n');
            }
        }
        slots.forEach((slot, node) -> out.append("slot ").append(slot.container().id).append('/')
                .append(slot.index()).append("->").append(node.id).append('\n'));
        // Restored field order is later-only (OWNERSHIP_PILOT); project it sorted.
        new java.util.TreeMap<>(fields).forEach((name, node) ->
                out.append("field ").append(name).append("->").append(node.id).append('\n'));
        sortedIds(retained.keySet()).forEach(owner -> {
            Node key = allocations.get(owner);
            out.append("retained ").append(owner).append("->").append(sortedIds(retained.get(key))).append('\n');
        });
        sortedIds(pools.keySet()).forEach(value ->
                out.append("pool ").append(value).append("->").append(pools.get(allocations.get(value)).id).append('\n'));
        out.append("exposed ").append(sortedIds(exposed)).append('\n');
        out.append("live ").append(sortedIds(live)).append('\n');
        out.append("blocked ").append(blocked).append('\n');
        return out.toString();
    }

    static TreeSet<Integer> sortedIds(Set<Node> nodes) {
        TreeSet<Integer> ids = new TreeSet<>();
        nodes.forEach(node -> ids.add(node.id));
        return ids;
    }

    public static void main(String[] args) {
        OwnershipOperationsReference analyzer = new OwnershipOperationsReference();
        for (int id = 0; id < 6; id++) analyzer.allocations.add(new Node(id));
        List<Node> n = analyzer.allocations;
        n.get(4).present = false;
        n.get(5).present = false;
        n.get(1).reason = "borrowed";
        analyzer.slots.put(new Slot(n.get(4), 0), n.get(0));
        analyzer.slots.put(new Slot(n.get(4), 1), n.get(1));
        analyzer.slots.put(new Slot(n.get(5), 7), n.get(2));
        analyzer.fields.put("a", n.get(0));
        analyzer.fields.put("b", n.get(1));
        analyzer.retained.put(n.get(4), Set.of(n.get(0), n.get(1)));
        analyzer.pools.put(n.get(0), n.get(4));
        analyzer.exposed.add(n.get(2));
        analyzer.live.add(n.get(0));
        analyzer.live.add(n.get(1));
        analyzer.live.add(n.get(3));
        Snapshot before = analyzer.save();

        n.get(0).state = ESCAPED;
        n.get(0).reason = "escaped";
        n.get(1).detached = true;
        analyzer.slots.remove(new Slot(n.get(4), 0));
        analyzer.slots.put(new Slot(n.get(5), 9), n.get(3));
        analyzer.fields.remove("a");
        analyzer.fields.put("c", n.get(3));
        analyzer.pools.put(n.get(0), n.get(5));
        analyzer.retained.put(n.get(4), Set.of(n.get(0), n.get(1), n.get(3)));
        analyzer.exposed.add(n.get(3));
        analyzer.live.remove(n.get(0));
        Snapshot changed = analyzer.save();
        String projectedChanged = analyzer.project("changed");

        analyzer.restore(before);
        StringBuilder out = new StringBuilder(analyzer.project("restored"));
        out.append(projectedChanged);
        analyzer.merge(before, List.of(before, changed));
        out.append(analyzer.project("merged"));
        System.out.print(out);
    }
}
