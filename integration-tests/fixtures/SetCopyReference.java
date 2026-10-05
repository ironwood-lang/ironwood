// SPDX-License-Identifier: MIT OR Apache-2.0

import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/** Independent Java oracle for the selected logical shallow set contracts. */
public final class SetCopyReference {
    private SetCopyReference() {}

    private static <T> Set<T> identityCopy(Set<T> source) {
        Set<T> result = Collections.newSetFromMap(new IdentityHashMap<>());
        result.addAll(source);
        return result;
    }

    private static void check(Set<String> source, Function<Set<String>, Set<String>> copy, boolean ordered) {
        source.add("Aa"); source.add("BB");
        var cursor = source.iterator();
        String first = cursor.next();
        Set<String> copied = copy.apply(source);
        require(!first.equals(cursor.next()) && !cursor.hasNext());
        require(copied.contains("Aa") && copied.contains("BB"));
        if (ordered) require(List.copyOf(copied).equals(List.of("Aa", "BB")));
        copied.remove("Aa");
        require(source.contains("Aa") && copied.size() == 1);
        source.clear();
        require(copied.contains("BB") && source.isEmpty());
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

    private static void failure(Set<Key> source, Function<Set<Key>, Set<Key>> copy) {
        Key.throwAt = 0;
        source.add(new Key()); source.add(new Key()); source.add(new Key());
        var cursor = source.iterator();
        Key first = cursor.next();
        Key.calls = 0; Key.throwAt = 2;
        try {
            copy.apply(source);
            throw new AssertionError("copy did not propagate callback failure");
        } catch (RuntimeException failure) {
            require(failure == Key.FAILURE && Key.calls == 2);
        }
        Key.throwAt = 0;
        Key second = cursor.next(); Key third = cursor.next();
        require(first != second && first != third && second != third && !cursor.hasNext());
        require(source.size() == 3);
    }

    private static void require(boolean condition) {
        if (!condition) throw new AssertionError("selected set copy contract");
    }

    public static void main(String[] arguments) {
        check(new HashSet<>(), HashSet::new, false);
        check(Collections.newSetFromMap(new IdentityHashMap<>()), SetCopyReference::identityCopy, false);
        check(new LinkedHashSet<>(), LinkedHashSet::new, true);
        String key = new String("equal"); String other = new String("equal");
        Set<String> source = Collections.newSetFromMap(new IdentityHashMap<>());
        source.add(key);
        Set<String> copied = identityCopy(source);
        require(key != other && key.equals(other) && copied.contains(key) && !copied.contains(other));
        failure(new HashSet<>(), HashSet::new);
        failure(new LinkedHashSet<>(), LinkedHashSet::new);
        Set<Key> identity = Collections.newSetFromMap(new IdentityHashMap<>());
        identity.add(new Key()); identity.add(new Key());
        Key.calls = 0; Key.throwAt = 1;
        require(identityCopy(identity).size() == 2 && Key.calls == 0);
        System.out.println("selected set copy contracts pass");
    }
}
