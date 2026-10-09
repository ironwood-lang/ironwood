// SPDX-License-Identifier: MIT OR Apache-2.0

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Independent Java 21 oracle for selected shallow membership copy contracts. */
public final class MapCopyReference {
    private MapCopyReference() {}

    private static void check(Map<String, String> source,
                              Function<Map<String, String>, Map<String, String>> copy,
                              boolean ordered) {
        source.put("Aa", "first");
        source.put("BB", "second");
        var cursor = source.values().iterator();
        String first = cursor.next();
        Map<String, String> snapshot = copy.apply(source);
        require(!first.equals(cursor.next()) && !cursor.hasNext());
        require(snapshot.size() == 2 && snapshot.get("Aa").equals("first")
                && snapshot.get("BB").equals("second"));
        if (ordered) require(List.copyOf(snapshot.values()).equals(List.of("first", "second")));
        source.clear();
        require(snapshot.size() == 2);
        snapshot.remove("Aa");
        require(source.isEmpty() && snapshot.size() == 1);
    }

    private static final class Key {
        private static final RuntimeException FAILURE = new RuntimeException();
        private static int calls;
        private static int throwAt;

        @Override public int hashCode() {
            if (++calls == throwAt) throw FAILURE;
            return 1;
        }
    }

    private static void failure(Map<Key, String> source,
                                Function<Map<Key, String>, Map<Key, String>> copy) {
        Key.throwAt = 0;
        source.put(new Key(), "first");
        source.put(new Key(), "second");
        source.put(new Key(), "third");
        var cursor = source.values().iterator();
        String first = cursor.next();
        Key.calls = 0;
        Key.throwAt = 2;
        try {
            copy.apply(source);
            throw new AssertionError("callback failure not propagated");
        } catch (RuntimeException failure) {
            require(failure == Key.FAILURE && Key.calls == 2);
        }
        Key.throwAt = 0;
        String second = cursor.next();
        String third = cursor.next();
        require(source.size() == 3 && !first.equals(second) && !second.equals(third)
                && !first.equals(third) && !cursor.hasNext());
    }

    private static void require(boolean condition) {
        if (!condition) throw new AssertionError("selected map copy contract");
    }

    public static void main(String[] arguments) {
        check(new HashMap<>(), HashMap::new, false);
        check(new IdentityHashMap<>(), IdentityHashMap::new, false);
        check(new LinkedHashMap<>(), LinkedHashMap::new, true);
        String key = new String("equal");
        String other = new String("equal");
        IdentityHashMap<String, String> source = new IdentityHashMap<>();
        source.put(key, "value");
        IdentityHashMap<String, String> copied = new IdentityHashMap<>(source);
        require(key != other && key.equals(other) && copied.containsKey(key) && !copied.containsKey(other));
        failure(new HashMap<>(), HashMap::new);
        failure(new LinkedHashMap<>(), LinkedHashMap::new);
        System.out.println("selected map copy contracts pass");
    }
}
