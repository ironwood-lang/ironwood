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

/** Seven-field ownership snapshots composed from the private snapshot helpers. */
final class OwnershipSnapshotTests {
    private static final String PORT = "compiler/src/main/ironwood/ironwood/compiler/port/";
    private static final List<String> HELPERS = List.of("SnapshotList", "SnapshotMap", "SnapshotIdentityMap",
            "SnapshotLinkedMap", "SnapshotIdentitySet");
    private static final String OPERATIONS = "integration-tests/cases/compiler_ownership_operations.iron";
    // Matches docs/self-hosting/m1/composition-evidence/OwnershipOperationsReference.java.
    private static final String PROJECTION = """
            restored
            state 0:0:null:false
            state 1:0:borrowed:false
            state 2:0:null:false
            state 3:0:null:false
            slot 4/0->0
            slot 4/1->1
            slot 5/7->2
            field a->0
            field b->1
            retained 4->[0, 1]
            pool 0->4
            exposed [2]
            live [0, 1, 3]
            blocked []
            changed
            state 0:1:escaped:false
            state 1:0:borrowed:true
            state 2:0:null:false
            state 3:0:null:false
            slot 4/1->1
            slot 5/7->2
            slot 5/9->3
            field b->1
            field c->3
            retained 4->[0, 1, 3]
            pool 0->5
            exposed [2, 3]
            live [1, 3]
            blocked []
            merged
            state 0:4:lost slot:false
            state 1:4:conflict:false
            state 2:0:null:false
            state 3:4:lost slot:false
            slot 4/1->1
            slot 5/7->2
            field b->1
            retained 4->[0, 1, 3]
            pool 0->4
            exposed [2, 3]
            live [1, 3]
            blocked [0, 3, 4, 5]
            """;

    private OwnershipSnapshotTests() { }

    static void controls() throws Exception {
        List<SourceFile> sources = new ArrayList<>();
        for (String helper : HELPERS) {
            sources.add(SourceFile.of("test/" + helper + ".iron", Files.readString(Path.of(PORT + helper + ".iron"))));
        }
        String fixture = Files.readString(Path.of(OPERATIONS));
        String types = fixture.substring(fixture.indexOf("import ironwood.ds.*;"), fixture.indexOf("class Main {"));
        String save = fixture.substring(fixture.indexOf("    static OwnershipSnapshot save(Live state) {"),
                fixture.indexOf("    static void restore(Live state, OwnershipSnapshot snapshot) {"));
        String prefix = types + "class Main {\n" + save + """
                public static int main(String[] args) {
                    Live state = new Live(8); Node node = new Node(0, null); Node other = new Node(1, null);
                    state.allocations.add(node); state.allocations.add(other);
                    ArrayList<Node> childItems = new ArrayList<Node>(1); childItems.add(node);
                    SnapshotList<Node> child = new SnapshotList<Node>(childItems); free childItems;
                    state.retained.put(other, child); state.live.add(node);
                    OwnershipSnapshot saved = save(state);
                    OwnershipSnapshot sibling = save(state);
                """;
        for (UnfreedMode mode : UnfreedMode.values()) {
            // Snapshots retire independently while the live state keeps changing.
            require(mode, sources, prefix + "state.live.clear(); boolean found = saved.live(node)"
                    + " && saved.retained(other).get(0) == node; free saved;"
                    + " found = found && sibling.retained(other) == child; free sibling; return found ? 42 : 0; }}", null);
            require(mode, sources, prefix + "free node; boolean found = saved.live(other); free saved; free sibling;"
                    + " return 0; }}", "cannot free 'node'");
            require(mode, sources, prefix + "free saved; boolean found = saved.live(node); free sibling; return 0; }}",
                    "after its allocation was freed");
            require(mode, sources, prefix + "free saved; free child; int size = sibling.retained(other).size();"
                    + " free sibling; return size; }}", "cannot free 'child'");
            // Conservative boundary: payloads and shared child versions read
            // through a composite stay invocation-lived.
            require(mode, sources, prefix + "boolean found = saved.live(node); free saved; free sibling;"
                    + " free node; return 0; }}", "cannot free 'node'");
            require(mode, sources, prefix + "boolean found = saved.live(node); free saved; free sibling;"
                    + " free child; return 0; }}", "cannot free 'child'");
        }
    }

    static void artifacts() throws Exception {
        Path root = Files.createTempDirectory("ironwood-ownership-snapshot-");
        try {
            for (String fixture : List.of("compiler_ownership_snapshot", "compiler_ownership_snapshot_scaling",
                    "compiler_ownership_operations")) {
                Path classes = root.resolve(fixture);
                List<String> arguments = new ArrayList<>();
                for (String helper : HELPERS) arguments.add(PORT + helper + ".iron");
                arguments.addAll(List.of("integration-tests/cases/" + fixture + ".iron", "--unfreed=warn", "-d", classes.toString()));
                run(arguments);
                Path archive = root.resolve(fixture + ".ironjar");
                ByteArrayOutputStream ignored = new ByteArrayOutputStream();
                if (IronJarMain.run(new String[]{"--create", "--file", archive.toString(), classes.toString()},
                        new PrintStream(ignored), new PrintStream(ignored)) != 0) {
                    throw new AssertionError("ownership snapshot archive: " + ignored);
                }
                for (Path input : List.of(classes, archive)) {
                    Path executable = root.resolve(input.getFileName() + "-program");
                    run(List.of("--link", "-cp", input.toString(), "--main-class", "Main", "--unfreed=warn",
                            "-O3", "-o", executable.toString()));
                    execute(executable, fixture.endsWith("scaling") ? "8:159:123\n32:351:267\n128:1041:843\n"
                            : fixture.endsWith("operations") ? PROJECTION : "checks:43\n");
                }
            }
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    /** A failed allocation inside a save rolls back and frees every temporary. */
    static void failures() throws Exception {
        Path root = Files.createTempDirectory("ironwood-ownership-failures-");
        try {
            Path classes = root.resolve("classes");
            List<String> arguments = new ArrayList<>();
            for (String helper : HELPERS) arguments.add(PORT + helper + ".iron");
            arguments.addAll(List.of("integration-tests/cases/compiler_ownership_snapshot_failure.iron", "--unfreed=warn",
                    "-d", classes.toString()));
            run(arguments, "warning: fresh result of 'setup' leaves scope without being freed");
            Path executable = root.resolve("program");
            run(List.of("--link", "-cp", classes.toString(), "--main-class", "Main", "--unfreed=warn", "-O3",
                    "-o", executable.toString()), "warning: fresh result of 'setup' leaves scope without being freed");
            for (int limit = 0; limit <= 270; limit++) {
                ProcessBuilder builder = new ProcessBuilder(executable.toString()).redirectErrorStream(true);
                builder.environment().put("IRONWOOD_ALLOCATION_LIMIT", Integer.toString(limit));
                Process process = builder.start();
                process.getInputStream().readAllBytes();
                if (!process.waitFor(30, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    throw new AssertionError("ownership failure program timed out");
                }
                int expected = limit < 131 ? 44 : limit < 268 ? 42 : 43;
                if (process.exitValue() != expected) {
                    throw new AssertionError("ownership save limit " + limit + ": exit " + process.exitValue());
                }
            }
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void execute(Path executable, String expected) throws Exception {
        Process process = new ProcessBuilder(executable.toString()).redirectErrorStream(true).start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("ownership snapshot program timed out");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.exitValue() != 42 || !output.equals(expected)) {
            throw new AssertionError("ownership snapshot program: exit " + process.exitValue() + ", output " + output);
        }
    }

    private static void run(List<String> arguments) {
        run(arguments, "warning: allocation assigned to 'state' leaves scope without being freed");
    }

    // The single expected diagnostic is the deliberately retained live state.
    private static void run(List<String> arguments, String expectedWarning) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PrintStream stream = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            int exit = Main.run(new ArrayList<>(arguments).toArray(String[]::new), stream, stream);
            String text = output.toString(StandardCharsets.UTF_8);
            String unexpected = text.replace(expectedWarning, "");
            if (exit != 0 || unexpected.contains("warning") || unexpected.contains("error")) {
                throw new AssertionError("ownership snapshot compiler run " + arguments + ": exit " + exit + ": " + text);
            }
        }
    }

    private static void require(UnfreedMode mode, List<SourceFile> helpers, String source, String rejection) {
        List<SourceFile> sources = new ArrayList<>(helpers);
        sources.add(SourceFile.of("test/Main.iron", source));
        CompilationArtifact artifact = new CompilerPipeline(mode).compile(sources);
        String messages = String.join("; ", artifact.diagnostics().stream()
                .map(diagnostic -> diagnostic.message()).toList());
        // Strict mode may still report the deliberately invocation-lived payloads
        // as missing frees; mandatory safety errors must be absent in every mode.
        boolean accepted = mode == UnfreedMode.ERROR
                ? !messages.contains("cannot free") && !messages.contains("after its allocation was freed")
                : artifact.successful();
        if (rejection == null ? !accepted : artifact.successful() || !messages.contains(rejection)) {
            throw new AssertionError("ownership snapshot control under " + mode + ": " + messages + "\n" + source);
        }
    }
}
