// SPDX-License-Identifier: MIT OR Apache-2.0

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Java reference for integration-tests/cases/compiler_library_discovery.iron.
 * Run it with a compiler jar or class directory as the class path and the
 * scenario's working directory and environment: it prints the archives,
 * class roots and source roots StandardLibrary.discover finds (read by
 * reflection) and its owned types, sorted, with their count.
 */
public final class LibraryReference {
    private LibraryReference() { }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        StringBuilder out = new StringBuilder();
        Class<?> library = Class.forName("ironwood.compiler.StandardLibrary");
        Method discover = library.getDeclaredMethod("discover");
        discover.setAccessible(true);
        Object standard = discover.invoke(null);
        for (String name : List.of("archives", "classRoots", "sourceRoots")) {
            Field field = library.getDeclaredField(name);
            field.setAccessible(true);
            List<Path> roots = (List<Path>) field.get(standard);
            out.append(name).append(' ').append(roots.size()).append('\n');
            for (Path root : roots) out.append("  ").append(root).append('\n');
        }
        Field owned = library.getDeclaredField("ownedTypes");
        owned.setAccessible(true);
        Set<String> types = new TreeSet<>((Set<String>) owned.get(standard));
        out.append("types ").append(types.size()).append('\n');
        for (String type : types) out.append("  ").append(type).append('\n');
        System.out.print(out);
    }
}
