// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.source.SourceFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class FreeDiagnosticTests {
    private static final String MIXED = """
            import ironwood.ds.ArrayList;

            class Holder {

                private Object value;

                void set(Object value) {

                    this.value = value;
                }
            }

            class Mixed {

                static void check() {

                    Holder holder = new Holder();
                    ArrayList<Object> list = new ArrayList<>();
                    Object value = new Object();
                    holder.set(value);
                    list.add(value);
                    free value;
                }
            }
            """;
    private static final String SLOTS = """
            class Slots {

                static void check(boolean flag) {

                    Object[] holder = new Object[4];
                    Object value = new Object();
                    holder[0] = value;
                    holder[1] = value;
                    holder[2] = value;
                    holder[3] = value;
                    if (flag) {
                        int unused = 1;
                    }
                    free value;
                }
            }
            """;
    private static final String WRAPPER =
            "cannot free 'value': allocation is still borrowed by a live wrapper";
    private static final String CONTAINER =
            "cannot free 'value': allocation is still borrowed by a live container";
    private static final String SLOT =
            "cannot free 'value': allocation is still reachable through known array element [0]";

    private FreeDiagnosticTests() {}

    static void stableBlockers() throws Exception {
        String reverseSlots = replace(SLOTS,
                "holder[0] = value;\n        holder[1] = value;\n        holder[2] = value;\n        holder[3] = value;",
                "holder[3] = value;\n        holder[2] = value;\n        holder[1] = value;\n        holder[0] = value;");
        String noJoin = replace(reverseSlots,
                "if (flag) {\n            int unused = 1;\n        }", "");
        String listFirst = replace(MIXED,
                "Holder holder = new Holder();\n        ArrayList<Object> list = new ArrayList<>();",
                "ArrayList<Object> list = new ArrayList<>();\n        Holder holder = new Holder();");
        String reverseRetention = replace(MIXED,
                "holder.set(value);\n        list.add(value);",
                "list.add(value);\n        holder.set(value);");
        String safe = replace(MIXED, "free value;", "free list; free holder; free value;")
                + replace(SLOTS, "free value;", "free holder; free value;");
        for (UnfreedMode mode : UnfreedMode.values()) {
            rejected(noJoin, mode, List.of(SLOT));
            rejected(MIXED + SLOTS, mode, List.of(WRAPPER, SLOT));
            rejected(listFirst + SLOTS, mode, List.of(CONTAINER, SLOT));
            rejected(reverseRetention + reverseSlots, mode, List.of(WRAPPER, SLOT));
            CompilationArtifact accepted = new CompilerPipeline(mode).analyze(
                    List.of(SourceFile.of("Safe.iron", safe)));
            require(accepted.valid(), mode + " safe borrower cleanup rejected: " + accepted.diagnostics());
        }
        freshProcesses(MIXED + SLOTS);
    }

    private static void rejected(String source, UnfreedMode mode, List<String> expected) {
        CompilationArtifact artifact = new CompilerPipeline(mode).analyze(
                List.of(SourceFile.of("Rejected.iron", source)));
        require(!artifact.valid(), "retained value was accepted in " + mode);
        List<String> actual = artifact.diagnostics().stream()
                .filter(diagnostic -> diagnostic.isError()
                        && diagnostic.message().startsWith("cannot free 'value':"))
                .map(diagnostic -> diagnostic.message()).toList();
        require(actual.equals(expected), mode + " expected " + expected + ", got " + actual);
    }

    private static void freshProcesses(String source) throws Exception {
        Path root = Files.createTempDirectory("ironwood-free-diagnostics-");
        try {
            Path input = root.resolve("Rejected.iron");
            Path output = root.resolve("diagnostics.txt");
            Path classes = root.resolve("classes");
            Files.writeString(input, source);
            String firstOutput = null;
            for (int run = 0; run < 8; run++) {
                Process process = new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp", System.getProperty("java.class.path"), Main.class.getName(),
                        "--unfreed=off", input.toString(), "-d", classes.toString())
                        .redirectErrorStream(true).redirectOutput(output.toFile()).start();
                try {
                    require(process.waitFor(30, TimeUnit.SECONDS), "compiler timed out");
                    require(process.exitValue() == 1, "retained value compiler exit: " + process.exitValue());
                    String actual = Files.readString(output);
                    require(actual.lines().filter(line -> line.startsWith("error: ")).toList()
                            .equals(List.of("error: " + WRAPPER, "error: " + SLOT)), actual);
                    if (firstOutput == null) firstOutput = actual;
                    require(actual.equals(firstOutput), "diagnostic text or locations varied between JVMs");
                    require(!Files.exists(classes), "invalid source produced class output");
                } finally {
                    if (process.isAlive()) {
                        process.destroyForcibly();
                        require(process.waitFor(5, TimeUnit.SECONDS), "compiler did not stop");
                    }
                }
            }
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static String replace(String source, String before, String after) {
        require(source.contains(before), "test mutation did not match: " + before);
        return source.replace(before, after);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
