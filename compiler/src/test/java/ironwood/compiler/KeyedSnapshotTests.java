// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.source.SourceFile;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Private copied membership must preserve both backing and payload lifetimes. */
final class KeyedSnapshotTests {
    private record Family(String map, String wrapper) { }
    private static final List<Family> FAMILIES = List.of(new Family("HashMap", "SnapshotMap"),
            new Family("IdentityHashMap", "SnapshotIdentityMap"), new Family("LinkedHashMap", "SnapshotLinkedMap"));

    private KeyedSnapshotTests() { }

    static void loans() throws Exception {
        for (Family family : FAMILIES) {
            SourceFile helper = helper(family.wrapper());
            String prefix = prefix(family);
            for (String cleanup : List.of(
                    "free source; int observed = snapshot.get(key).value(); free snapshot; free key; free value;",
                    "source.clear(); free source; boolean found = snapshot.containsKey(key); free snapshot; free key; free value;",
                    "free source; int count = snapshot.size(); boolean empty = snapshot.isEmpty(); free snapshot; free key; free value;")) {
                for (UnfreedMode mode : UnfreedMode.values()) {
                    requireAccepted(mode, List.of(helper, main(prefix + cleanup + " return 42; }}")), family.wrapper());
                }
            }
            for (String cleanup : List.of(
                    "free source; free key; snapshot.containsKey(key);",
                    "free source; free value; snapshot.get(key).value();",
                    "Item alias = snapshot.get(key); free source; free snapshot; alias.value();",
                    "free source; snapshot.get(key).publish(); free snapshot; free key; free value;")) {
                for (UnfreedMode mode : UnfreedMode.values()) {
                    requireRejected(mode, List.of(helper, main(prefix + cleanup + " return 0; }}")), family.wrapper());
                }
            }
            String fallback = prefix.replace("%s<Item, Item> snapshot".formatted(family.wrapper()),
                    "source.get(key); %s<Item, Item> snapshot".formatted(family.wrapper()));
            requireAccepted(UnfreedMode.OFF, List.of(helper, main(fallback + "return snapshot.size(); }}")), "exposed source definition");
            for (UnfreedMode mode : UnfreedMode.values()) {
                requireRejected(mode, List.of(helper, main(fallback + "free source; return snapshot.size(); }}")), "exposed source root");
            }
            String self = """
                    import ironwood.ds.%1$s; import ironwood.compiler.port.%2$s;
                    class Main { public static int main(String[] args) {
                        %1$s<Object, Object> source = new %1$s<Object, Object>(1);
                        source.put("self", source);
                        %2$s<Object, Object> snapshot = new %2$s<Object, Object>(source);
                        free source; return snapshot.size();
                    }}
                    """.formatted(family.map(), family.wrapper());
            for (UnfreedMode mode : UnfreedMode.values()) requireRejected(mode, List.of(helper, main(self)), "self source");
        }
        SourceFile set = helper("SnapshotIdentitySet");
        String prefix = """
                import ironwood.ds.IdentityHashSet; import ironwood.compiler.port.SnapshotIdentitySet;
                class Item { }
                class Main { public static int main(String[] args) {
                    Item item = new Item();
                    IdentityHashSet<Item> source = new IdentityHashSet<Item>(1); source.add(item);
                    SnapshotIdentitySet<Item> snapshot = new SnapshotIdentitySet<Item>(source);
                """;
        for (UnfreedMode mode : UnfreedMode.values()) {
            requireAccepted(mode, List.of(set, main(prefix + "free source; boolean found = snapshot.contains(item);"
                    + "int count = snapshot.size(); boolean empty = snapshot.isEmpty(); free snapshot; free item; return 42; }}")), "identity membership");
            requireRejected(mode, List.of(set, main(prefix + "free source; free item; return snapshot.size(); }}")), "identity membership item loan");
        }
        String shared = """
                import ironwood.ds.*; import ironwood.compiler.port.*;
                class Item { }
                class Main { public static int main(String[] args) {
                    Item item = new Item(); IdentityHashSet<Item> children = new IdentityHashSet<Item>(1); children.add(item);
                    SnapshotIdentitySet<Item> child = new SnapshotIdentitySet<Item>(children); free children;
                    IdentityHashMap<Item, SnapshotIdentitySet<Item>> source = new IdentityHashMap<Item, SnapshotIdentitySet<Item>>(1);
                    source.put(item, child);
                    SnapshotIdentityMap<Item, SnapshotIdentitySet<Item>> left = new SnapshotIdentityMap<Item, SnapshotIdentitySet<Item>>(source);
                    SnapshotIdentityMap<Item, SnapshotIdentitySet<Item>> right = new SnapshotIdentityMap<Item, SnapshotIdentitySet<Item>>(source);
                    free source;
                """;
        for (UnfreedMode mode : UnfreedMode.values()) {
            List<SourceFile> helpers = List.of(set, helper("SnapshotIdentityMap"));
            requireAccepted(mode, withMain(helpers, shared + "boolean found = left.get(item).contains(item);"
                    + "free left; found = right.get(item).contains(item); free right; free child; free item; return 42; }}"), "shared child versions");
            requireRejected(mode, withMain(helpers, shared + "free left; free child; return right.size(); }}"), "live sibling child loan");
            requireRejected(mode, withMain(helpers, shared + "free left; free right; free item; return child.size(); }}"), "child payload loan");
        }
    }

    static void proofControls() throws Exception {
        for (Family family : FAMILIES) {
            String raw = Files.readString(Path.of("stdlib/src/main/ironwood/ironwood/ds/" + family.map() + ".iron"));
            SourceFile wrapper = helper(family.wrapper());
            for (String action : List.of("saved = key;", "clear();", "observe(key);")) {
                String modified = raw.replace("public E get(K key) {", "public E get(K key) { " + action);
                int body = modified.indexOf('{', modified.indexOf("public class " + family.map()));
                modified = modified.substring(0, body + 1)
                        + "\n    private static Object saved; private static void observe(Object value) { saved = value; }\n"
                        + modified.substring(body + 1);
                SourceFile map = SourceFile.of("test/" + family.map() + ".iron", modified);
                String noFree = prefix(family) + "int observed = snapshot.get(key).value(); return observed; }}";
                requireAccepted(UnfreedMode.OFF, List.of(map, wrapper, main(noFree)), "changed lookup definition");
                String cleanup = prefix(family) + "free source; int observed = snapshot.get(key).value();"
                        + "free snapshot; free key; free value; return observed; }}";
                for (UnfreedMode mode : UnfreedMode.values()) {
                    requireRejected(mode, List.of(map, wrapper, main(cleanup)), "changed lookup body");
                }
            }
            String source = """
                    import ironwood.ds.%1$s; import ironwood.compiler.port.%2$s;
                    class Key {
                        @Override public int hashCode() { Sink.saved = this; return 1; }
                        @Override public boolean equals(Object other) { Sink.saved = this; return this == other; }
                    }
                    class Sink { static Object saved; }
                    class Main { public static int main(String[] args) {
                        Key key = new Key(); Object value = new Object();
                        %1$s<Key, Object> source = new %1$s<Key, Object>(1); source.put(key, value);
                        %2$s<Key, Object> snapshot = new %2$s<Key, Object>(source);
                        free source; boolean found = snapshot.containsKey(key); Object observed = snapshot.get(key);
                        free snapshot; free key; free value; return 42;
                    }}
                    """.formatted(family.map(), family.wrapper());
            for (UnfreedMode mode : UnfreedMode.values()) {
                if (family.map().equals("IdentityHashMap")) requireAccepted(mode, List.of(wrapper, main(source)), "identity callbacks omitted");
                else requireRejected(mode, List.of(wrapper, main(source)), "publishing key callbacks");
            }
        }
    }

    static void countAndStoredCallbacks() throws Exception {
        String raw = Files.readString(Path.of("stdlib/src/main/ironwood/ironwood/ds/IdentityHashMap.iron"));
        String changedCount = replaceBody(raw, "public int size() {",
                "IdentityHashMapEntry<K,E> entry = this.data[0]; if (entry != null) return entry.key.hashCode(); return 0;");
        SourceFile helper = helper("SnapshotIdentityMap");
        String prefix = """
                import ironwood.ds.IdentityHashMap; import ironwood.compiler.port.SnapshotIdentityMap;
                class Key { @Override public int hashCode() { Sink.saved = this; return 1; } }
                class Sink { static Object saved; }
                class Main { public static int main(String[] args) {
                    Key key = new Key(); Object value = new Object();
                    IdentityHashMap<Key,Object> source = new IdentityHashMap<Key,Object>(1); source.put(key,value);
                    SnapshotIdentityMap<Key,Object> snapshot = new SnapshotIdentityMap<Key,Object>(source);
                """;
        SourceFile count = SourceFile.of("test/IdentityHashMap.iron", changedCount);
        requireAccepted(UnfreedMode.OFF, List.of(count, helper, main(prefix + "snapshot.size(); return 0; }}")), "stored callback count definition");
        for (UnfreedMode mode : UnfreedMode.values()) {
            String diagnostics = requireRejected(mode, List.of(count, helper, main(prefix + "free source; snapshot.size();"
                    + "free snapshot; free key; free value; return 0; }}")), "count callback publication");
            if (!diagnostics.contains("cannot free 'key'")) throw new AssertionError("count callback key publication: " + diagnostics);
            System.out.println("stored count callback " + mode + ": " + diagnostics);
        }
        String changedGet = replaceBody(raw, "public E get(K key) {",
                "IdentityHashMapEntry<K,E> entry = this.data[0]; if (entry != null) { int hash = entry.key.hashCode(); return entry.value; } return null;");
        SourceFile get = SourceFile.of("test/IdentityHashMap.iron", changedGet);
        String nested = """
                import ironwood.ds.*; import ironwood.compiler.port.SnapshotIdentityMap;
                class Item { int value() { return 7; } @Override public int hashCode() { return 1; } }
                class Publishing extends Item { @Override public int hashCode() { Sink.saved = this; return 1; } }
                class Sink { static Object saved; }
                class Main { public static int main(String[] args) {
                    Item nested = new Publishing(); Item value = new Item();
                    HashMap<String,Item> stored = new HashMap<String,Item>(1); stored.put("member",nested);
                    HashMap<String,Item> probe = new HashMap<String,Item>(1);
                    IdentityHashMap<HashMap<String,Item>,Item> source = new IdentityHashMap<HashMap<String,Item>,Item>(1);
                    source.put(stored,value);
                    SnapshotIdentityMap<HashMap<String,Item>,Item> snapshot = new SnapshotIdentityMap<HashMap<String,Item>,Item>(source);
                """;
        requireAccepted(UnfreedMode.OFF, List.of(get, helper, main(nested + "snapshot.get(probe).value(); return 0; }}")), "stored nested callback definition");
        for (UnfreedMode mode : UnfreedMode.values()) {
            String diagnostics = requireRejected(mode, List.of(get, helper, main(nested + "free source; snapshot.get(probe).value();"
                    + "free snapshot; free stored; free probe; free nested; free value; return 0; }}")), "stored nested callback publication");
            if (!diagnostics.contains("cannot free 'nested'")) throw new AssertionError("stored nested key publication: " + diagnostics);
            System.out.println("stored nested callback " + mode + ": " + diagnostics);
        }
        // A distinct scalar query must not inherit a reference-result alias.
        // The altered body is only a proof control; it is never run natively.
        String pure = prefix.replace("Sink.saved = this;", "")
                + "Key probe = new Key(); free source; snapshot.get(probe); free snapshot; free key; free probe; free value; return 42; }}";
        for (UnfreedMode mode : UnfreedMode.values()) {
            requireAccepted(mode, List.of(get, helper, main(pure)), "pure scalar stored callback");
        }
    }

    static void artifacts() throws Exception {
        Path root = Files.createTempDirectory("ironwood-keyed-snapshots-");
        List<String> helpers = List.of("SnapshotMap", "SnapshotIdentityMap", "SnapshotLinkedMap", "SnapshotIdentitySet");
        try {
            StringBuilder expected = new StringBuilder();
            for (int count : List.of(0, 8, 32, 128, 512)) {
                for (String family : List.of("value", "identity", "linked", "set")) {
                    int allocations = family.equals("set") ? (count == 0 ? 11 : count + 10) : (count == 0 ? 9 : count + 8);
                    expected.append(family).append(':').append(count).append(':').append(allocations).append('\n');
                }
            }
            for (String fixture : List.of("compiler_keyed_snapshots", "compiler_keyed_snapshot_membership",
                    "compiler_keyed_snapshot_failure", "compiler_keyed_snapshot_callback_failure")) {
                Path classes = root.resolve(fixture);
                List<String> arguments = new ArrayList<>();
                for (String helper : helpers) arguments.add("compiler/src/main/ironwood/ironwood/compiler/port/" + helper + ".iron");
                arguments.addAll(List.of("integration-tests/cases/" + fixture + ".iron", "--unfreed=warn", "-d", classes.toString()));
                runCompiler(arguments);
                Path archive = root.resolve(fixture + ".ironjar");
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                try (PrintStream stream = new PrintStream(output, true, StandardCharsets.UTF_8)) {
                    if (IronJarMain.run(new String[]{"--create", "--file", archive.toString(), classes.toString()}, stream, stream) != 0)
                        throw new AssertionError(output.toString(StandardCharsets.UTF_8));
                }
                for (Path input : List.of(classes, archive)) {
                    Path executable = root.resolve(input.getFileName() + "-program");
                    runCompiler(List.of("--link", "-cp", input.toString(), "--main-class", "Main", "--unfreed=warn",
                            "-O3", "-o", executable.toString()));
                    runNative(executable, root, null, fixture.equals("compiler_keyed_snapshot_failure") ? 43 : 42,
                            fixture.equals("compiler_keyed_snapshots") ? expected.toString() : "");
                    if (fixture.equals("compiler_keyed_snapshot_failure")) {
                        for (int limit = 0; limit <= 72; limit++) runNative(executable, root, limit, limit < 72 ? 42 : 43, "");
                    }
                    for (Family family : FAMILIES) {
                        var loaded = new SourceSetLoader(List.of(root.resolve("missing")), List.of(input))
                                .load(List.of(), List.of("ironwood.compiler.port." + family.wrapper()));
                        if (!loaded.diagnostics().isEmpty()) throw new AssertionError(loaded.diagnostics().toString());
                        for (UnfreedMode mode : UnfreedMode.values()) {
                            requireAccepted(mode, withMain(loaded.sources(), prefix(family)
                                    + "free source; snapshot.get(key).value(); free snapshot; free key; free value; return 42; }}"), "artifact payload retirement");
                            requireRejected(mode, withMain(loaded.sources(), prefix(family)
                                    + "free source; free value; return snapshot.size(); }}"), "artifact retained payload");
                        }
                    }
                }
            }
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void runCompiler(List<String> arguments) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PrintStream stream = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            if (Main.run(arguments.toArray(String[]::new), stream, stream) != 0)
                throw new AssertionError(output.toString(StandardCharsets.UTF_8));
        }
    }

    private static void runNative(Path executable, Path root, Integer limit, int expectedExit, String expectedOutput) throws Exception {
        Path output = root.resolve("native-output.log");
        ProcessBuilder builder = new ProcessBuilder(executable.toString()).redirectErrorStream(true).redirectOutput(output.toFile());
        if (limit == null) builder.environment().remove("IRONWOOD_ALLOCATION_LIMIT");
        else builder.environment().put("IRONWOOD_ALLOCATION_LIMIT", limit.toString());
        Process process = builder.start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new AssertionError("keyed snapshot timeout"); }
        String observed = Files.readString(output);
        if (process.exitValue() != expectedExit || !observed.equals(expectedOutput))
            throw new AssertionError("keyed snapshot limit " + limit + ": exit " + process.exitValue() + ", output " + observed);
    }

    private static String replaceBody(String source, String header, String body) {
        int start = source.indexOf(header);
        if (start < 0) throw new AssertionError("body header missing: " + header);
        int opening = start + header.length() - 1;
        int depth = 1;
        int end = opening + 1;
        for (; depth > 0 && end < source.length(); end++) {
            if (source.charAt(end) == '{') depth++;
            else if (source.charAt(end) == '}') depth--;
        }
        if (depth != 0) throw new AssertionError("body close missing: " + header);
        return source.substring(0, opening + 1) + body + source.substring(end - 1);
    }

    private static String prefix(Family family) {
        return """
                import ironwood.ds.%1$s; import ironwood.compiler.port.%2$s;
                class Item {
                    private int marker = 7;
                    int value() { return this.marker; }
                    int publish() { Sink.saved = this; return this.marker; }
                }
                class Sink { static Object saved; }
                class Main { public static int main(String[] args) {
                    Item key = new Item(); Item value = new Item();
                    %1$s<Item, Item> source = new %1$s<Item, Item>(1); source.put(key, value);
                    %2$s<Item, Item> snapshot = new %2$s<Item, Item>(source);
                """.formatted(family.map(), family.wrapper());
    }

    private static SourceFile helper(String name) throws Exception {
        return SourceFile.of("test/" + name + ".iron", Files.readString(Path.of(
                "compiler/src/main/ironwood/ironwood/compiler/port/" + name + ".iron")));
    }

    private static SourceFile main(String source) { return SourceFile.of("test/Main.iron", source); }

    private static List<SourceFile> withMain(List<SourceFile> helpers, String source) {
        List<SourceFile> result = new ArrayList<>(helpers); result.add(main(source)); return List.copyOf(result);
    }

    private static void requireAccepted(UnfreedMode mode, List<SourceFile> sources, String label) {
        CompilationArtifact artifact = new CompilerPipeline(mode).compile(sources);
        if (!artifact.successful()) throw new AssertionError(label + " under " + mode + ": " + messages(artifact));
    }

    private static String requireRejected(UnfreedMode mode, List<SourceFile> sources, String label) {
        CompilationArtifact artifact = new CompilerPipeline(mode).compile(sources);
        String messages = messages(artifact);
        if (artifact.successful() || !messages.contains("cannot free") && !messages.contains("after its allocation was freed")) {
            throw new AssertionError(label + " under " + mode + ": " + messages);
        }
        return messages;
    }

    private static String messages(CompilationArtifact artifact) {
        return String.join("; ", artifact.diagnostics().stream().map(diagnostic -> diagnostic.message()).toList());
    }
}
