// SPDX-License-Identifier: MIT OR Apache-2.0

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Java 21 reference for integration-tests/cases/compiler_value_snapshots.iron:
 * natural String order, stable List.sort, Stream.min/max ties, List.of with
 * equals/hashCode, Map.copyOf equals/hashCode and Set.copyOf membership.
 */
public final class ValueSnapshotsReference {
    private record Ranked(int key, int id) { }

    public static void main(String[] args) {
        String[] texts = {"", "a", "b", "ab", "abc", "ba", "Z", "a\u0000", "éx", "x",
                "😀", "\ud800x", "x\udc00", "￿x", new String("ab")};
        StringBuilder signs = new StringBuilder();
        for (String left : texts) {
            for (String right : texts) {
                int sign = Integer.signum(left.compareTo(right));
                signs.append(sign < 0 ? '-' : sign == 0 ? '0' : '+');
            }
            signs.append('/');
        }
        System.out.println("signs " + signs);
        List<String> sorted = new ArrayList<>();
        for (int index = texts.length - 1; index >= 0; index--) sorted.add(texts[index]);
        sorted.sort(Comparator.naturalOrder());
        StringBuilder positions = new StringBuilder();
        for (String text : sorted) {
            int original = -1;
            for (int candidate = 0; candidate < texts.length && original < 0; candidate++) {
                if (texts[candidate] == text) original = candidate;
            }
            positions.append(original).append(' ');
        }
        System.out.println("sorted " + positions);

        Comparator<Ranked> byRank = Comparator.comparingInt(Ranked::key);
        int[] keys = {5, 3, 9, 3, 9, 1, 1, 7};
        List<Ranked> candidates = new ArrayList<>();
        System.out.println("empty " + candidates.stream().min(byRank).isEmpty());
        for (int index = 0; index < keys.length; index++) {
            candidates.add(new Ranked(keys[index], index));
            Optional<Ranked> minimum = candidates.stream().min(byRank);
            Optional<Ranked> maximum = candidates.stream().max(byRank);
            List<Ranked> frozen = List.copyOf(candidates);
            System.out.println("prefix " + (index + 1) + " min " + minimum.orElseThrow().id()
                    + " max " + maximum.orElseThrow().id() + " snapshot "
                    + frozen.stream().min(byRank).orElseThrow().id() + " "
                    + frozen.stream().max(byRank).orElseThrow().id());
        }

        List<String> one = List.of("x");
        List<String> two = List.of("x", "y");
        List<String> three = List.of("x", "y", "z");
        List<String> four = List.of("x", "y", "z", "w");
        List<String> again = List.of("x", "y");
        List<String> swapped = List.of("y", "x");
        List<String> empty = List.of();
        System.out.println("hash " + empty.hashCode() + " " + one.hashCode() + " " + two.hashCode()
                + " " + three.hashCode() + " " + four.hashCode());
        System.out.println("equal " + two.equals(again) + " " + two.equals(swapped) + " "
                + two.equals(three) + " " + empty.equals(empty) + " " + one.equals(two));
        System.out.println("order " + four.get(0) + four.get(1) + four.get(2) + four.get(3));

        Map<String, String> first = Map.copyOf(new HashMap<>(Map.of("k1", "v1", "k2", "v2", "k3", "v3")));
        Map<String, String> same = Map.copyOf(new HashMap<>(Map.of("k3", "v3", "k1", "v1", "k2", "v2")));
        Map<String, String> other = Map.copyOf(new HashMap<>(Map.of("k1", "v1", "k2", "changed", "k3", "v3")));
        Map<String, String> shorter = Map.copyOf(new HashMap<>(Map.of("k1", "v1")));
        System.out.println("map equal " + first.equals(same) + " " + same.equals(first) + " "
                + first.equals(other) + " " + shorter.equals(first));
        System.out.println("map hash " + first.hashCode() + " " + same.hashCode() + " " + shorter.hashCode());

        Set<String> builder = new HashSet<>();
        for (int index = 1; index < 7; index++) builder.add(texts[index]);
        Set<String> snapshot = Set.copyOf(builder);
        builder.add("later");
        StringBuilder found = new StringBuilder();
        for (String text : texts) found.append(snapshot.contains(text) ? '1' : '0');
        System.out.println("set " + snapshot.size() + " " + found + " " + snapshot.contains("later"));
    }
}
