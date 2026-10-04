// SPDX-License-Identifier: MIT OR Apache-2.0
package ironwood.audit;

import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** Discovery qualification: factories, copies, views, captures and overloads. */
final class InventorySample {
    record Snapshot(Set<String> items) {
        Snapshot { items = Set.copyOf(items); }
    }
    private final Supplier<Integer> retained;
    private final String label;
    private Map<String, String> carried;

    InventorySample(String label) {
        this.label = label;
        retained = () -> this.label.length();
    }

    private static Snapshot snapshot(Set<String> source) {
        return new Snapshot(Set.copyOf(source));
    }

    private static Map<String, String> alias(Map<String, String> value) {
        return value;
    }

    private static void fill(Map<String, String> destination, Map<String, String> input) {
        destination.putAll(input);
    }

    void distinctScopes(boolean choice) {
        if (choice) {
            Map<String, String> sibling = new HashMap<>();
            sibling.put("first", label);
        } else {
            Map<String, String> sibling = new HashMap<>();
            sibling.put("second", label);
        }
    }

    synchronized String syntax() {
        var text = """
                qualification
                """;
        return text;
    }

    List<String> exercise(int captured) {
        Set<String> original = new HashSet<>();
        original.add("Aa");
        original.add("BB");
        Set<String> alias = original;
        Snapshot frozen = snapshot(alias);
        Map<String, String> map = frozen.items().stream().collect(Collectors.toMap(s -> s, s -> s));
        Map<String, String> ordered = new LinkedHashMap<>();
        ordered.putAll(map);
        List<String> bulkCopy = new ArrayList<>(ordered.keySet());
        bulkCopy.forEach(String::length);
        Map<String, String> destination = new LinkedHashMap<>();
        Map<String, String> destinationAlias = destination;
        fill(alias(destinationAlias), map);
        carried = destination;
        List<String> viaField = new ArrayList<>(carried.keySet());
        viaField.forEach(String::length);
        List<String> result = new ArrayList<>(map.keySet());
        result.addAll(List.of(label));
        result.addAll(List.of(label, label));
        Supplier<Integer> immediate = () -> captured + result.size();
        int size = immediate.get() + retained.get();
        String[] array = result.toArray(String[]::new);
        if (array.length != size) return result;
        return new ArrayList<>(result);
    }
}
