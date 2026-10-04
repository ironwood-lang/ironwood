// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.source.SourceFile;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Current-store order must survive snapshots before a join chooses one witness. */
final class ArraySnapshotOrderTests {
    private ArraySnapshotOrderTests() {}

    static void joins() throws Exception {
        snapshotStorage();
        for (int size : new int[]{8, 32, 128}) {
            for (boolean reverse : new boolean[]{false, true}) {
                String stores = stores(size, reverse, "data");
                String clears = stores(size, reverse, "null");
                String text = "class ArraySnapshotOrder {\n"
                        + "    static void check(boolean flag) {\n"
                        + "        byte[] data = new byte[1];\n"
                        + "        Object[] holder = new Object[" + size + "];\n"
                        + "        if (flag) {\n" + stores
                        + "        } else {\n" + clears + "        }\n"
                        + "        free data;\n        free holder;\n    }\n}\n";
                int firstStore = text.indexOf("= data;") + 2;
                SourceFile source = SourceFile.of("ArraySnapshotOrder.iron", text);
                String safe = text.replace("        } else {\n" + clears,
                        "        } else {\n" + stores).replace("        free data;", clears + "        free data;");
                for (UnfreedMode mode : UnfreedMode.values()) {
                    CompilationArtifact plain = new CompilerPipeline(mode, false, null).analyze(List.of(source));
                    CompilationArtifact explained = new CompilerPipeline(mode, true, null).analyze(List.of(source));
                    var prior = plain.diagnostics().stream().filter(d -> d.isError()
                            && d.message().startsWith("cannot free 'data':")).findFirst().orElseThrow();
                    var selected = explained.diagnostics().stream().filter(d -> d.isError()
                            && d.message().startsWith("cannot free 'data':")).findFirst().orElseThrow();
                    require(!plain.valid() && !explained.valid() && prior.notes().isEmpty()
                                    && prior.message().equals(selected.message())
                                    && prior.span().equals(selected.span())
                                    && selected.message().contains("incoming control-flow path")
                                    && selected.notes().size() == 1
                                    && selected.notes().getFirst().source() == source
                                    && selected.notes().getFirst().span().start().offset() == firstStore,
                            "join lost first incoming current-store witness: " + size + "/" + reverse + "/" + mode
                                    + ": " + selected);
                    for (boolean explain : new boolean[]{false, true}) {
                        CompilationArtifact accepted = new CompilerPipeline(mode, explain, null).analyze(
                                List.of(SourceFile.of("ArraySnapshotOrder.iron", safe)));
                        require(accepted.valid(), "detached common slots rejected: " + size + "/" + reverse + "/" + mode
                                + ": " + accepted.diagnostics());
                    }
                }
            }
        }
    }

    static void overwrittenStore() {
        String text = """
                class OverwrittenArrayStore {
                    static void check(boolean flag) {
                        byte[] data = new byte[1];
                        Object[] holder = new Object[2];
                        if (flag) {
                            holder[0] = data;
                            holder[1] = data;
                            holder[0] = data;
                        }
                        free data;
                        free holder;
                    }
                }
                """;
        SourceFile source = SourceFile.of("OverwrittenArrayStore.iron", text);
        CompilationArtifact result = new CompilerPipeline(UnfreedMode.OFF, true, null).analyze(List.of(source));
        var error = result.diagnostics().stream().filter(d -> d.isError()
                && d.message().startsWith("cannot free 'data':")).findFirst().orElseThrow();
        int currentFirst = text.indexOf("holder[1] = data;") + "holder[1] = ".length();
        require(!result.valid() && error.message().contains("incoming control-flow path")
                        && error.notes().size() == 1
                        && error.notes().getFirst().span().start().offset() == currentFirst,
                "overwritten slot kept its earliest-ever position or chose minimum index: " + error);
    }

    private static String stores(int size, boolean reverse, String value) {
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < size; index++) {
            text.append("            holder[").append(reverse ? size - index - 1 : index)
                    .append("] = ").append(value).append(";\n");
        }
        return text.toString();
    }

    private static void snapshotStorage() throws Exception {
        Class<?> snapshot = Class.forName("ironwood.compiler.semantic.FunctionAnalyzer$OwnershipSnapshot");
        var constructor = snapshot.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        var accessor = snapshot.getDeclaredMethod("knownArraySlots");
        accessor.setAccessible(true);
        Class<?> slotClass = Class.forName("ironwood.compiler.semantic.FunctionAnalyzer$ArraySlot");
        var slotConstructor = slotClass.getDeclaredConstructors()[0];
        slotConstructor.setAccessible(true);
        Class<?> allocationClass = Class.forName("ironwood.compiler.semantic.FunctionAnalyzer$AllocationInfo");
        var allocationConstructor = allocationClass.getDeclaredConstructor(int.class);
        allocationConstructor.setAccessible(true);
        Object container = allocationConstructor.newInstance(0);
        Object allocation = allocationConstructor.newInstance(0);
        Map<Object, Object> slots = new LinkedHashMap<>();
        Object first = slotConstructor.newInstance(container, 31);
        Object second = slotConstructor.newInstance(container, 0);
        Object third = slotConstructor.newInstance(container, 63);
        slots.put(first, allocation); slots.put(second, allocation); slots.put(third, allocation);
        Object saved = constructor.newInstance(Map.of(), slots, Map.of(), Map.of(), Map.of(), Set.of(), Set.of());
        Map<?, ?> copy = (Map<?, ?>) accessor.invoke(saved);
        slots.clear();
        require(new ArrayList<>(copy.keySet()).equals(List.of(first, second, third))
                && copy.get(first) == allocation, "independent ordered shallow snapshot");
        try {
            copy.clear();
            throw new AssertionError("snapshot membership mutable");
        } catch (UnsupportedOperationException expected) {
            require(copy.size() == 3, "failed mutation changed snapshot");
        }
        for (boolean nullKey : new boolean[]{false, true}) {
            Map<Object, Object> invalid = new LinkedHashMap<>();
            invalid.put(nullKey ? null : first, nullKey ? allocation : null);
            try {
                constructor.newInstance(Map.of(), invalid, Map.of(), Map.of(), Map.of(), Set.of(), Set.of());
                throw new AssertionError("null snapshot entry accepted");
            } catch (InvocationTargetException failure) {
                require(failure.getCause() instanceof NullPointerException, "snapshot null rejection changed");
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
