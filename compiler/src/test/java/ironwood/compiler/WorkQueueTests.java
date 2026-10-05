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
 * The compiler-private FIFO must keep queued items alive and its ring private;
 * map iterator removal must keep the evidence store's retain filter exact.
 */
final class WorkQueueTests {
    private static final String HELPER = "compiler/src/main/ironwood/ironwood/compiler/port/WorkQueue.iron";
    // Matches docs/self-hosting/m1/worklist-evidence/WorkQueueReference.java.
    private static final String TRANSCRIPT = """
            size:18
            order:n0 n1 n2 n3 n4 n5 n6 n7 n8 n9 n10 n11 n12 n13 n14 n15 n16 n17 n18 n19 n20 n21 n22 n23
            reachable:entry branch then else switch loop default case1 case2 invoke normal unwind
            """;

    // Matches docs/self-hosting/m1/worklist-evidence/IteratorRetainReference.java.
    private static final String RETAINED = """
            removed:12 kept:size:0
            removed:0 kept:AaAaAa AaAaBB AaBBAa AaBBBB BBAaAa BBAaBB BBBBAa BBBBBB x0 x1 x2 x3 size:12
            removed:8 kept:AaAaAa AaBBBB BBBBAa x1 size:4
            removed:4 kept:AaAaBB AaBBAa BBAaAa BBAaBB BBBBBB x0 x2 x3 size:8
            removed:9 kept:AaAaAa AaAaBB AaBBAa size:3
            removed:9 kept:BBAaBB BBBBAa BBBBBB size:3
            """;

    private WorkQueueTests() { }

    static void ownership() throws Exception {
        SourceFile helper = SourceFile.of("test/WorkQueue.iron", Files.readString(Path.of(HELPER)));
        String prefix = """
                import ironwood.compiler.port.WorkQueue;
                class Item { int value() { return 7; } }
                class Main { public static int main(String[] args) {
                    Item item = new Item(); WorkQueue<Item> queue = new WorkQueue<Item>();
                """;
        for (UnfreedMode mode : UnfreedMode.values()) {
            require(mode, helper, prefix + "queue.add(item); Item back = queue.removeFirst(); free queue;"
                    + " return back.value() + item.value(); }}", null);
            require(mode, helper, prefix + "queue.add(item); free item; Item back = queue.removeFirst();"
                    + " free queue; return back.value(); }}", "cannot free 'item'");
            require(mode, helper, prefix + "queue.add(item); free queue; return queue.size(); }}",
                    "after its allocation was freed");
            // Queued items escape conservatively: no consumer needs a loan discharge.
            require(mode, helper, prefix + "queue.add(item); queue.removeFirst(); free queue; free item;"
                    + " return 0; }}", "cannot free 'item'");
        }
    }

    static void artifacts() throws Exception {
        Path root = Files.createTempDirectory("ironwood-work-queue-");
        try {
            for (String fixture : List.of("compiler_work_queue", "compiler_work_queue_failure", "compiler_iterator_retain")) {
                Path classes = root.resolve(fixture);
                run(List.of(HELPER, "integration-tests/cases/" + fixture + ".iron", "--unfreed=warn",
                        "-d", classes.toString()));
                Path archive = root.resolve(fixture + ".ironjar");
                ByteArrayOutputStream ignored = new ByteArrayOutputStream();
                if (IronJarMain.run(new String[]{"--create", "--file", archive.toString(), classes.toString()},
                        new PrintStream(ignored), new PrintStream(ignored)) != 0) {
                    throw new AssertionError("work queue archive: " + ignored);
                }
                for (Path input : List.of(classes, archive)) {
                    Path executable = root.resolve(input.getFileName() + "-program");
                    run(List.of("--link", "-cp", input.toString(), "--main-class", "Main", "--unfreed=warn",
                            "-O3", "-o", executable.toString()));
                    if (fixture.equals("compiler_work_queue")) {
                        execute(executable, null, 42, TRANSCRIPT);
                    } else if (fixture.equals("compiler_iterator_retain")) {
                        execute(executable, null, 42, RETAINED);
                    } else {
                        execute(executable, null, 43, "");
                        for (int limit = 0; limit <= 8; limit++) execute(executable, limit, limit < 4 ? 42 : 43, "");
                    }
                }
            }
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    /** Compiles with no diagnostics at all; port sources must stay warning-free. */
    private static void run(List<String> arguments) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PrintStream stream = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            int exit = Main.run(new ArrayList<>(arguments).toArray(String[]::new), stream, stream);
            String text = output.toString(StandardCharsets.UTF_8);
            if (exit != 0 || text.contains("warning") || text.contains("error")) {
                throw new AssertionError("work queue compiler run " + arguments + ": exit " + exit + ": " + text);
            }
        }
    }

    private static void execute(Path executable, Integer limit, int expectedExit, String expectedOutput) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(executable.toString()).redirectErrorStream(true);
        Map<String, String> environment = builder.environment();
        if (limit == null) environment.remove("IRONWOOD_ALLOCATION_LIMIT");
        else environment.put("IRONWOOD_ALLOCATION_LIMIT", limit.toString());
        Process process = builder.start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("work queue program timed out");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.exitValue() != expectedExit || !output.equals(expectedOutput)) {
            throw new AssertionError("work queue limit " + limit + ": exit " + process.exitValue() + ", output " + output);
        }
    }

    private static void require(UnfreedMode mode, SourceFile helper, String source, String rejection) {
        CompilationArtifact artifact = new CompilerPipeline(mode)
                .compile(List.of(helper, SourceFile.of("test/Main.iron", source)));
        String messages = String.join("; ", artifact.diagnostics().stream()
                .map(diagnostic -> diagnostic.message()).toList());
        if (rejection == null ? !artifact.successful() : artifact.successful() || !messages.contains(rejection)) {
            throw new AssertionError("work queue ownership under " + mode + ": " + messages + "\n" + source);
        }
    }
}
