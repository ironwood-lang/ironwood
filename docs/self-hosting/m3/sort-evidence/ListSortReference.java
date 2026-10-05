// SPDX-License-Identifier: MIT OR Apache-2.0

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Java 21 reference for integration-tests/cases/ds_array_list_sort.iron: the
 * same seeded records sorted with java.util.ArrayList.sort, which is stable.
 * Prints the transcript the native fixture must reproduce byte for byte.
 */
public final class ListSortReference {
    private record Record(int key, int id) { }

    private static long seed = 20261005L;

    private static int next(int bound) {
        seed = seed * 6364136223846793005L + 1442695040888963407L;
        int value = (int) (seed >>> 33);
        return value % bound;
    }

    private static List<Record> build(int size, int range, int shape) {
        List<Record> list = new ArrayList<>();
        for (int index = 0; index < size; index++) {
            int key = shape == 0 ? next(range)
                    : shape == 1 ? index % range
                    : shape == 2 ? (size - index) % range
                    : (index < size / 2 ? index : size - index) % range;
            list.add(new Record(key, index));
        }
        return list;
    }

    private static void print(String label, int size, int range, int shape, List<Record> list) {
        StringBuilder line = new StringBuilder();
        int hash = 1;
        for (Record record : list) hash = 31 * hash + record.id();
        line.append(label).append(" n=").append(size).append(" k=").append(range)
                .append(" s=").append(shape).append(" h=").append(hash).append(" head=");
        for (int index = 0; index < list.size() && index < 12; index++) {
            if (index > 0) line.append(',');
            line.append(list.get(index).id());
        }
        System.out.println(line);
    }

    public static void main(String[] args) {
        int[] sizes = {0, 1, 2, 3, 7, 16, 17, 31, 32, 33, 64, 100, 257, 1000, 4096};
        int[] ranges = {1, 4, 50, 100000};
        Comparator<Record> byKey = (first, second) -> Integer.compare(first.key(), second.key());
        Comparator<Record> descending = (first, second) -> Integer.compare(second.key(), first.key());
        Comparator<Record> residue = (first, second) -> {
            int order = Integer.compare(first.key() % 3, second.key() % 3);
            return order != 0 ? order : Integer.compare(first.key(), second.key());
        };
        for (int size : sizes) {
            for (int range : ranges) {
                for (int shape = 0; shape < 4; shape++) {
                    List<Record> first = build(size, range, shape);
                    first.sort(byKey);
                    print("key", size, range, shape, first);
                    first.sort(descending);
                    print("desc", size, range, shape, first);
                    List<Record> second = build(size, range, shape);
                    second.sort(residue);
                    print("mod3", size, range, shape, second);
                }
            }
        }
    }
}
