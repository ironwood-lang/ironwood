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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * M3.1 compiler-private helpers (D264): ScopeStack, SnapshotSet, StringOrder,
 * Extremes, the Lists and Maps value helpers and the callback interfaces.
 */
final class PortHelperTests {
    private static final String PORT = "compiler/src/main/ironwood/ironwood/compiler/port/";
    private static final List<String> HELPERS = List.of("Action", "BooleanSource", "Condition", "Extremes",
            "IdentityMapper", "Lists", "Mapper", "Maps", "PairAction", "ScopeStack", "SnapshotList",
            "SnapshotMap", "SnapshotSet", "Source", "StringOrder");
    private static final String EVIDENCE = "docs/self-hosting/m3/helpers-evidence/";
    private static final Map<String, String> FIXTURES = Map.of(
            "compiler_scope_stack", "ScopeStackReference.java",
            "compiler_value_snapshots", "ValueSnapshotsReference.java",
            "compiler_callbacks", "CallbacksReference.java");
    private static final String PREFIX = """
            import ironwood.ds.HashSet;
            import ironwood.ds.ArrayList;
            import ironwood.compiler.port.*;
            class Item { int value() { return 7; } }
            class Holder {
                private final Mapper<Item, Item> mapper;
                Holder(Mapper<Item, Item> mapper) { this.mapper = mapper; }
                Item map(Item item) { return this.mapper.apply(item); }
            }
            class Main { public static int main(String[] args) {
                Item item = new Item();
            """;

    private PortHelperTests() { }

    static void ownership() throws Exception {
        List<SourceFile> helpers = new ArrayList<>();
        for (String helper : HELPERS) {
            helpers.add(SourceFile.of("test/" + helper + ".iron", Files.readString(Path.of(PORT + helper + ".iron"))));
        }
        for (UnfreedMode mode : UnfreedMode.values()) {
            // Stacks retire with membership; stacked items escape as queued items do (D255).
            require(mode, helpers, "ScopeStack<Item> stack = new ScopeStack<Item>(); stack.push(item);"
                    + " Item back = stack.pop(); free stack; return back.value(); }}", null);
            require(mode, helpers, "ScopeStack<Item> stack = new ScopeStack<Item>(); stack.push(item);"
                    + " free item; Item back = stack.peek(); free stack; return 0; }}", "cannot free 'item'");
            require(mode, helpers, "ScopeStack<Item> stack = new ScopeStack<Item>(); free stack;"
                    + " stack.push(item); return 0; }}", "after its allocation was freed");
            require(mode, helpers, "ScopeStack<Item> stack = new ScopeStack<Item>(); stack.push(item);"
                    + " SnapshotList<Item> saved = stack.snapshot(); free stack; int r = saved.size(); free saved;"
                    + " return r; }}", null);
            require(mode, helpers, "ScopeStack<Item> stack = new ScopeStack<Item>(); stack.push(item);"
                    + " SnapshotList<Item> saved = stack.snapshot(); free saved; int r = saved.size(); free stack;"
                    + " return r; }}", "after its allocation was freed");
            // Set snapshots retire their storage and keep their items borrowed.
            require(mode, helpers, "HashSet<Item> builder = new HashSet<Item>(); builder.add(item);"
                    + " SnapshotSet<Item> set = new SnapshotSet<Item>(builder); free builder;"
                    + " boolean found = set.contains(item); free set; free item; return found ? 1 : 0; }}", null);
            require(mode, helpers, "HashSet<Item> builder = new HashSet<Item>(); builder.add(item);"
                    + " SnapshotSet<Item> set = new SnapshotSet<Item>(builder); free builder; free item;"
                    + " int r = set.size(); free set; return r; }}", "cannot free 'item'");
            // Fixed-arity lists own only their storage and, like Lists.single
            // (D258), conservatively expose their items, even after the list retires.
            require(mode, helpers, "SnapshotList<Item> two = Lists.of(item, item); int r = two.size(); free two;"
                    + " return r; }}", null);
            require(mode, helpers, "SnapshotList<Item> two = Lists.of(item, item); int r = two.size(); free two;"
                    + " free item; return r; }}", "cannot free 'item'");
            require(mode, helpers, "SnapshotList<Item> two = Lists.of(item, item); free two; int r = two.size();"
                    + " return r; }}", "after its allocation was freed");
            // A holder retires before the callback it retains; the callback cannot go first.
            require(mode, helpers, "IdentityMapper<Item> identity = new IdentityMapper<Item>();"
                    + " Holder holder = new Holder(identity); int r = holder.map(item).value();"
                    + " free holder; free identity; free item; return r; }}", null);
            require(mode, helpers, "IdentityMapper<Item> identity = new IdentityMapper<Item>();"
                    + " Holder holder = new Holder(identity); free identity; Item same = holder.map(item);"
                    + " free holder; return same.value(); }}", "cannot free 'identity'");
            // Captured state stays alive: freeing it while its callback can run is rejected.
            require(mode, helpers, "Item[] box = new Item[1]; box[0] = item; Condition<Item> test = new Condition<Item>() {"
                    + " @Override public boolean test(Item candidate) { return candidate == box[0]; } };"
                    + " free box; boolean found = test.test(item); free test; return found ? 1 : 0; }}",
                    "cannot free 'box'");
        }
    }

    /** Port generics spell reference bounds; primitive arguments are rejected at the use site. */
    static void referenceBounds() throws Exception {
        Pattern typeHeader = Pattern.compile("\\b(?:class|interface)\\s+\\w+\\s*<([^{]*?)>\\s*(?:extends|implements|\\{)");
        Pattern methodHeader = Pattern.compile("\\b(?:public|private|protected|static|final)\\s+(?:static\\s+)?<([^>(]*(?:<[^>]*>[^>(]*)*)>\\s");
        List<String> unbounded = new ArrayList<>();
        int declarations = 0;
        try (Stream<Path> paths = Files.walk(Path.of("compiler/src/main/ironwood"))) {
            for (Path path : paths.filter(path -> path.toString().endsWith(".iron")).sorted().toList()) {
                String source = Files.readString(path);
                for (Pattern pattern : List.of(typeHeader, methodHeader)) {
                    Matcher matcher = pattern.matcher(source);
                    while (matcher.find()) {
                        for (String parameter : topLevel(matcher.group(1))) {
                            declarations++;
                            if (!parameter.contains(" extends ")) unbounded.add(path + ": " + parameter);
                        }
                    }
                }
            }
        }
        if (declarations < 30 || !unbounded.isEmpty()) {
            throw new AssertionError("port generic declarations " + declarations + ", unbounded " + unbounded);
        }
        for (String use : List.of("ScopeStack<int> stack = new ScopeStack<int>();",
                "SnapshotSet<long> set = null;", "Mapper<int, Item> mapper = null;",
                "SnapshotList<boolean> list = null;")) {
            CompilationArtifact artifact = new CompilerPipeline(UnfreedMode.WARN).compile(List.of(
                    SourceFile.of("test/ScopeStack.iron", Files.readString(Path.of(PORT + "ScopeStack.iron"))),
                    SourceFile.of("test/SnapshotList.iron", Files.readString(Path.of(PORT + "SnapshotList.iron"))),
                    SourceFile.of("test/SnapshotSet.iron", Files.readString(Path.of(PORT + "SnapshotSet.iron"))),
                    SourceFile.of("test/Mapper.iron", Files.readString(Path.of(PORT + "Mapper.iron"))),
                    SourceFile.of("test/Main.iron", PREFIX + use + " return 0; }}")));
            if (artifact.successful()) throw new AssertionError("primitive argument accepted: " + use);
        }
    }

    static void artifacts() throws Exception {
        Path root = Files.createTempDirectory("ironwood-port-helpers-");
        try {
            for (var fixture : FIXTURES.entrySet()) {
                String expected = reference(EVIDENCE + fixture.getValue());
                for (Path executable : links(root, fixture.getKey())) execute(executable, null, 42, expected);
            }
        } finally {
            delete(root);
        }
    }

    /** Every allocation failure unwinds to the baseline; a large enough limit succeeds. */
    static void failures() throws Exception {
        Path root = Files.createTempDirectory("ironwood-port-helpers-failure-");
        try {
            for (Path executable : links(root, "compiler_port_helpers_failure")) {
                execute(executable, null, 43, "");
                int limit = 0;
                while (execute(executable, limit, -1, "") == 42) limit++;
                if (limit < 40 || execute(executable, limit, 43, "") != 43) {
                    throw new AssertionError("port helper OOM sweep ended at limit " + limit);
                }
            }
        } finally {
            delete(root);
        }
    }

    private static List<String> topLevel(String parameters) {
        List<String> result = new ArrayList<>();
        int depth = 0;
        StringBuilder current = new StringBuilder();
        for (char character : parameters.toCharArray()) {
            if (character == '<') depth++;
            if (character == '>') depth--;
            if (character == ',' && depth == 0) {
                result.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(character);
            }
        }
        result.add(current.toString().trim());
        return result;
    }

    private static List<Path> links(Path root, String fixture) throws Exception {
        Path classes = root.resolve(fixture);
        List<String> arguments = new ArrayList<>();
        for (String helper : HELPERS) arguments.add(PORT + helper + ".iron");
        arguments.addAll(List.of("integration-tests/cases/" + fixture + ".iron", "--unfreed=warn",
                "-d", classes.toString()));
        run(arguments);
        Path archive = root.resolve(fixture + ".ironjar");
        ByteArrayOutputStream ignored = new ByteArrayOutputStream();
        if (IronJarMain.run(new String[]{"--create", "--file", archive.toString(), classes.toString()},
                new PrintStream(ignored), new PrintStream(ignored)) != 0) {
            throw new AssertionError("port helper archive: " + ignored);
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

    /** Runs a Java 21 reference in a fresh JVM with inherited options cleared. */
    static String reference(String path) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        ProcessBuilder builder = new ProcessBuilder(java.toString(), path).redirectErrorStream(true);
        for (String variable : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")) {
            builder.environment().remove(variable);
        }
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(120, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new AssertionError("reference " + path + " failed: " + output);
        }
        return output;
    }

    /** Compiles with no diagnostics at all; port sources must stay warning-free. */
    private static void run(List<String> arguments) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PrintStream stream = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            int exit = Main.run(arguments.toArray(String[]::new), stream, stream);
            String text = output.toString(StandardCharsets.UTF_8);
            if (exit != 0 || text.contains("warning") || text.contains("error")) {
                throw new AssertionError("port helper compiler run " + arguments + ": exit " + exit + ": " + text);
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
            throw new AssertionError("port helper program timed out");
        }
        if (expectedExit >= 0 && (process.exitValue() != expectedExit || !output.equals(expectedOutput))) {
            throw new AssertionError(executable.getFileName() + " limit " + limit + ": exit "
                    + process.exitValue() + ", output " + output.length() + " characters");
        }
        return process.exitValue();
    }

    private static void require(UnfreedMode mode, List<SourceFile> helpers, String body, String rejection) {
        List<SourceFile> sources = new ArrayList<>(helpers);
        sources.add(SourceFile.of("test/Main.iron", PREFIX + body));
        CompilationArtifact artifact = new CompilerPipeline(mode).compile(sources);
        String messages = String.join("; ", artifact.diagnostics().stream()
                .map(diagnostic -> diagnostic.message()).toList());
        if (rejection == null ? !artifact.successful() : artifact.successful() || !messages.contains(rejection)) {
            throw new AssertionError("port helper ownership under " + mode + ": " + messages + "\n" + body);
        }
    }

    private static void delete(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
}
