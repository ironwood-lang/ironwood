// SPDX-License-Identifier: MIT OR Apache-2.0
package ironwood.compiler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/** Independent Java contract facts, not imported implementation or native sorting. */
public final class B2ContractProbe {
    private static int checks;
    private record Key(String name, int ordinal) {}
    private B2ContractProbe() {}
    private static void require(boolean value) {
        checks++;
        if (!value) throw new AssertionError("B2 contract");
    }
    private static void nullFailure(Runnable action) {
        try { action.run(); }
        catch (NullPointerException expected) { require(true); return; }
        throw new AssertionError("missing null failure");
    }
    private static void immutableFailure(Runnable action) {
        try { action.run(); }
        catch (UnsupportedOperationException expected) { require(true); return; }
        throw new AssertionError("missing immutable failure");
    }
    public static void main(String[] args) {
        TreeMap<String, Integer> map = new TreeMap<>();
        require(map.firstEntry() == null);
        nullFailure(() -> map.get(null));
        nullFailure(() -> map.containsKey(null));
        nullFailure(() -> map.remove(null));
        nullFailure(() -> map.put(null, 1));
        nullFailure(() -> new TreeMap<String, Integer>((Map<String, Integer>)null));
        require(map.put("b", null) == null);
        require(map.containsKey("b") && map.get("b") == null);
        require(map.put("a", 7) == null);
        require(map.put("a", 9) == 7);
        require(map.putIfAbsent("a", 99) == 9 && map.get("a") == 9);
        require(map.putIfAbsent("b", 3) == null && map.get("b") == 3);
        require(new ArrayList<>(map.keySet()).equals(List.of("a", "b")));
        Map.Entry<String, Integer> first = map.firstEntry();
        map.put("a", 10);
        require(first.getKey().equals("a") && first.getValue() == 9);
        immutableFailure(() -> first.setValue(20));
        TreeMap<String, Integer> copy = new TreeMap<>(map);
        map.clear();
        require(copy.size() == 2 && copy.get("a") == 10);
        require(new ArrayList<>(copy.values()).equals(List.of(10, 3)));
        List<String> order = new ArrayList<>();
        copy.forEach((key, value) -> order.add(key));
        require(order.equals(List.of("a", "b")));

        Key earlier = new Key("same", 1), later = new Key("same", 2);
        Comparator<Key> byName = Comparator.comparing(Key::name);
        TreeSet<Key> keys = new TreeSet<>(byName);
        require(keys.add(earlier) && !keys.add(later));
        require(!earlier.equals(later) && keys.size() == 1 && keys.contains(later));
        require(keys.first() == earlier);
        TreeSet<Key> reversedInsertion = new TreeSet<>(byName);
        reversedInsertion.add(later); reversedInsertion.add(earlier);
        require(reversedInsertion.first() == later);
        nullFailure(() -> keys.add(null));
        TreeSet<String> natural = new TreeSet<>();
        nullFailure(() -> natural.add(null));
        require(natural.addAll(List.of("b", "a", "b")) && natural.size() == 2);
        require(new ArrayList<>(natural).equals(List.of("a", "b")));

        Key low = new Key("a", 3), high = new Key("z", 0);
        ArrayList<Key> values = new ArrayList<>(List.of(high, later, earlier, low));
        values.sort(byName);
        require(values.equals(List.of(low, later, earlier, high)));
        require(values.get(1) == later && values.get(2) == earlier);
        List<Key> sorted = Stream.of(high, later, earlier, low).sorted(byName).toList();
        require(sorted.equals(values));
        immutableFailure(() -> sorted.add(low));
        immutableFailure(() -> sorted.sort(byName));
        require(Stream.of(later, earlier).min(byName).orElseThrow() == later);
        require(Stream.of(later, earlier).max(byName).orElseThrow() == later);
        require(Stream.<Key>empty().min(byName).isEmpty());
        nullFailure(() -> Stream.<Key>empty().sorted(null));
        nullFailure(() -> Stream.<Key>empty().min(null));
        nullFailure(() -> Stream.of((Key)null).min((left, right) -> 0));
        nullFailure(() -> Comparator.comparing((java.util.function.Function<Key, String>)null));
        nullFailure(() -> byName.thenComparing((Comparator<Key>)null));
        nullFailure(() -> byName.compare(new Key(null, 0), low));
        Comparator<Key> joined = byName.thenComparingInt(Key::ordinal);
        require(joined.compare(earlier, later) < 0 && joined.compare(high, low) > 0);
        Comparator<Key> extreme = (left, right) -> left.ordinal() == right.ordinal() ? 0
                : left.ordinal() < right.ordinal() ? Integer.MIN_VALUE : 1;
        require(extreme.compare(earlier, later) == Integer.MIN_VALUE);
        require(extreme.reversed().compare(earlier, later) > 0);
        require(Comparator.<String>reverseOrder().compare("a", "z") > 0);

        List<String> words = new ArrayList<>(List.of("\ue000", "\ud83d\ude00", "Z", "A"));
        words.sort(null);
        require(words.equals(List.of("A", "Z", "\ud83d\ude00", "\ue000")));
        ArrayList<String> arrayWords = new ArrayList<>(List.of("b", "a"));
        arrayWords.sort(null);
        require(arrayWords.equals(List.of("a", "b")));
        require(Stream.of("b", "a").sorted().toList().equals(List.of("a", "b")));
        require(Long.compareUnsigned(Long.MIN_VALUE, 1) > 0);
        require(java.util.Arrays.compareUnsigned(new byte[]{(byte)0xff}, new byte[]{0}) > 0);
        // The qualified Unix provider sorts encoded path bytes, unlike String UTF-16 order.
        require(java.nio.file.Path.of("\ue000").compareTo(java.nio.file.Path.of("\ud83d\ude00")) < 0);
        require("\ue000".compareTo("\ud83d\ude00") > 0);
        java.nio.file.Path privateUse = java.nio.file.Path.of("\ue000");
        java.nio.file.Path supplementary = java.nio.file.Path.of("\ud83d\ude00");
        TreeMap<java.nio.file.Path, String> pathValues = new TreeMap<>();
        pathValues.put(supplementary, "emoji"); pathValues.put(privateUse, "private");
        require(pathValues.firstKey() == privateUse);
        require(new ArrayList<>(pathValues.keySet()).equals(List.of(privateUse, supplementary)));
        require(pathValues.get(java.nio.file.Path.of("\ue000")).equals("private"));
        nullFailure(() -> pathValues.get(null));
        TreeSet<java.nio.file.Path> pathKeys = new TreeSet<>();
        require(pathKeys.add(supplementary) && pathKeys.add(privateUse));
        require(!pathKeys.add(java.nio.file.Path.of("\ue000")) && pathKeys.size() == 2);
        require(pathKeys.first() == privateUse);
        nullFailure(() -> pathKeys.contains(null));
        System.out.println("checks=" + checks);
    }
}
