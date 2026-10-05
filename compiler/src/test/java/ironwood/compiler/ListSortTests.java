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
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * ArrayList.sortWithComparator (B2): stable explicit-comparator sorting that
 * keeps the list's storage and frees its workspace. The call is not an audited
 * container method, so it conservatively exposes the stored items.
 */
final class ListSortTests {
    private static final String FIXTURE = "integration-tests/cases/ds_array_list_sort.iron";
    private static final String FAILURE = "integration-tests/cases/ds_array_list_sort_failure.iron";
    private static final String REFERENCE = "docs/self-hosting/m3/sort-evidence/ListSortReference.java";
    private static final String PREFIX = """
            import ironwood.ds.ArrayList;
            import ironwood.util.Comparator;
            class Item { int key; Item(int key) { this.key = key; } }
            class ByKey implements Comparator<Item> {
                @Override public int compare(Item a, Item b) { return Integer.compare(a.key, b.key); }
            }
            class Keeper implements Comparator<Item> {
                Item kept;
                @Override public int compare(Item a, Item b) { this.kept = a; return Integer.compare(a.key, b.key); }
            }
            class Publisher implements Comparator<Item> {
                static Item seen;
                @Override public int compare(Item a, Item b) { seen = b; return Integer.compare(a.key, b.key); }
            }
            class Main { public static int main(String[] args) {
                Item a = new Item(2); Item b = new Item(1);
                ArrayList<Item> list = new ArrayList<Item>(); list.add(a); list.add(b);
            """;

    private ListSortTests() { }

    static void ownership() {
        for (UnfreedMode mode : UnfreedMode.values()) {
            // The list, comparator and workspace retire; sorted items stay exposed.
            require(mode, "ByKey c = new ByKey(); list.sortWithComparator(c); int r = list.get(0).key;"
                    + " free list; free c; return r; }}", null);
            require(mode, "Comparator<Item> c = new ByKey(); list.sortWithComparator(c);"
                    + " free list; free c; return 0; }}", null);
            require(mode, "ArrayList<Item> empty = new ArrayList<Item>(); int r = 0;"
                    + " try { empty.sortWithComparator(null); } catch (NullPointerException e) { r = 1; }"
                    + " free empty; free list; free a; free b; return r; }}", null);
            // Items stay exposed after sorting, even with a non-retaining comparator.
            require(mode, "ByKey c = new ByKey(); list.sortWithComparator(c); free list; free c; free a;"
                    + " return 0; }}", "method 'sortWithComparator' can expose stored data-structure references");
            require(mode, "ByKey c = new ByKey(); list.sortWithComparator(c); free a; free list; free c;"
                    + " return 0; }}", "cannot free 'a'");
            require(mode, "Keeper c = new Keeper(); list.sortWithComparator(c); free list; free a;"
                    + " int r = c.kept.key; free c; return r; }}", "cannot free 'a'");
            require(mode, "Publisher c = new Publisher(); list.sortWithComparator(c); free list; free c;"
                    + " free b; return 0; }}", "cannot free 'b'");
            require(mode, "ByKey c = new ByKey(); free list; list.sortWithComparator(c); free c; return 0; }}",
                    "after its allocation was freed");
            require(mode, "ByKey c = new ByKey(); free c; list.sortWithComparator(c); free list; return 0; }}",
                    "after its allocation was freed");
        }
    }

    /** No Java-shaped sort(null) fallback and no list-to-array adapter. */
    static void omissions() {
        for (String call : List.of("list.sort(new ByKey());", "list.sort(null);", "Object[] all = list.toArray();")) {
            CompilationArtifact artifact = new CompilerPipeline(UnfreedMode.WARN)
                    .compile(List.of(SourceFile.of("test/Main.iron", PREFIX + call + " return 0; }}")));
            if (artifact.successful()) throw new AssertionError("omitted list member compiled: " + call);
        }
    }

    static void artifacts() throws Exception {
        String expected = reference();
        if (expected.lines().count() != 720) throw new AssertionError("list sort reference size");
        Path root = Files.createTempDirectory("ironwood-list-sort-");
        try {
            for (Path executable : links(root, FIXTURE)) execute(executable, null, 42, expected);
        } finally {
            delete(root);
        }
    }

    /** Every allocation failure unwinds; exactly one limit fails the workspace. */
    static void failures() throws Exception {
        Path root = Files.createTempDirectory("ironwood-list-sort-failure-");
        try {
            for (Path executable : links(root, FAILURE)) {
                execute(executable, null, 43, "");
                int workspace = -1;
                for (int limit = 0; limit < 64; limit++) {
                    int exit = execute(executable, limit, -1, "");
                    if (exit == 43) {
                        if (workspace < 0 || limit != workspace + 1) throw new AssertionError("sort OOM order");
                        break;
                    }
                    if (exit == 44 && workspace < 0) workspace = limit;
                    else if (exit != 42) throw new AssertionError("sort OOM limit " + limit + ": exit " + exit);
                }
                if (workspace < 0) throw new AssertionError("sort workspace failure not reached");
            }
        } finally {
            delete(root);
        }
    }

    private static List<Path> links(Path root, String fixture) throws Exception {
        String name = Path.of(fixture).getFileName().toString().replace(".iron", "");
        Path classes = root.resolve(name);
        run(List.of(fixture, "--unfreed=warn", "-d", classes.toString()));
        Path archive = root.resolve(name + ".ironjar");
        ByteArrayOutputStream ignored = new ByteArrayOutputStream();
        if (IronJarMain.run(new String[]{"--create", "--file", archive.toString(), classes.toString()},
                new PrintStream(ignored), new PrintStream(ignored)) != 0) {
            throw new AssertionError("list sort archive: " + ignored);
        }
        List<Path> executables = new ArrayList<>();
        for (Path input : List.of(classes, archive)) {
            Path executable = root.resolve(input.getFileName() + "-program");
            run(List.of("--link", "-cp", input.toString(), "--main-class", "Main", "--unfreed=warn",
                    "-O3", "-o", executable.toString()));
            executables.add(executable);
        }
        return executables;
    }

    /** Runs the Java 21 reference in a fresh JVM with inherited options cleared. */
    private static String reference() throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        ProcessBuilder builder = new ProcessBuilder(java.toString(), REFERENCE).redirectErrorStream(true);
        for (String variable : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")) {
            builder.environment().remove(variable);
        }
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(120, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new AssertionError("list sort reference failed: " + output);
        }
        return output;
    }

    /** Compiles with no diagnostics at all. */
    private static void run(List<String> arguments) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PrintStream stream = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            int exit = Main.run(arguments.toArray(String[]::new), stream, stream);
            String text = output.toString(StandardCharsets.UTF_8);
            if (exit != 0 || text.contains("warning") || text.contains("error")) {
                throw new AssertionError("list sort compiler run " + arguments + ": exit " + exit + ": " + text);
            }
        }
    }

    private static int execute(Path executable, Integer limit, int expectedExit, String expectedOutput)
            throws Exception {
        ProcessBuilder builder = new ProcessBuilder(executable.toString()).redirectErrorStream(true);
        Map<String, String> environment = builder.environment();
        if (limit == null) environment.remove("IRONWOOD_ALLOCATION_LIMIT");
        else environment.put("IRONWOOD_ALLOCATION_LIMIT", limit.toString());
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("list sort program timed out");
        }
        if (expectedExit >= 0 && (process.exitValue() != expectedExit || !output.equals(expectedOutput))) {
            throw new AssertionError("list sort limit " + limit + ": exit " + process.exitValue()
                    + ", output " + output.length() + " characters");
        }
        return process.exitValue();
    }

    private static void require(UnfreedMode mode, String body, String rejection) {
        CompilationArtifact artifact = new CompilerPipeline(mode)
                .compile(List.of(SourceFile.of("test/Main.iron", PREFIX + body)));
        String messages = String.join("; ", artifact.diagnostics().stream()
                .map(diagnostic -> diagnostic.message()).toList());
        if (rejection == null ? !artifact.successful() : artifact.successful() || !messages.contains(rejection)) {
            throw new AssertionError("list sort ownership under " + mode + ": " + messages + "\n" + body);
        }
    }

    private static void delete(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
}
