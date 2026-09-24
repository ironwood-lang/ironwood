// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.diagnostic.Diagnostic;
import ironwood.compiler.source.SourceFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class OwnedArrayDiagnosticTests {
    private static final String FIELD = "a creation-array object cannot also escape through a field";
    private static final String ARRAY = "a fresh creation-array object cannot also be stored in another array";
    private static final String REPEATED = "each creation-array entry must receive a distinct fresh object exactly once";
    private static final String PREFIX = "cannot prove owned elements of 'items' safe: ";

    private OwnedArrayDiagnosticTests() {}

    static void firstRecordedFailure() throws Exception {
        String aFirst = source("""
                Item a = new Item();
                Item b = new Item();
                items[0] = a;
                items[1] = b;
                cached = a;
                Item[] other = new Item[1];
                other[0] = b;
                free other;
                """);
        String bFirst = source("""
                Item a = new Item();
                Item b = new Item();
                items[0] = b;
                items[1] = a;
                cached = a;
                Item[] other = new Item[1];
                other[0] = b;
                free other;
                """);
        String bCreatedFirst = source("""
                Item b = new Item();
                Item a = new Item();
                items[0] = a;
                items[1] = b;
                Item[] other = new Item[1];
                other[0] = b;
                free other;
                cached = a;
                """);
        String bOffendsFirst = source("""
                Item a = new Item();
                Item b = new Item();
                items[0] = a;
                items[1] = b;
                Item[] other = new Item[1];
                other[0] = b;
                free other;
                cached = a;
                """);
        for (UnfreedMode mode : UnfreedMode.values()) {
            rejected(aFirst, mode, FIELD);
            rejected(bFirst, mode, ARRAY);
            rejected(bCreatedFirst, mode, FIELD);
            rejected(bOffendsFirst, mode, FIELD);
            rejected(source("""
                    Item a = new Item();
                    items[0] = a;
                    cached = a;
                    Item[] other = new Item[1];
                    other[0] = a;
                    free other;
                    """), mode, FIELD);
            rejected(source("""
                    Item a = new Item();
                    items[0] = a;
                    Item[] other = new Item[1];
                    other[0] = a;
                    free other;
                    cached = a;
                    """), mode, ARRAY);
            rejected(source("""
                    Item a = new Item();
                    items[0] = a;
                    items[1] = a;
                    cached = a;
                    """), mode, REPEATED);
            accepted(source("""
                    Item a = new Item();
                    Item b = new Item();
                    items[0] = a;
                    items[1] = b;
                    """), mode);
        }
        freshProcesses(aFirst, FIELD);
        freshProcesses(bFirst, ARRAY);
    }

    private static String source(String body) {
        return """
                class Item { }
                class Owner {
                    private Item[] items = new Item[2];
                    static Item cached = new Item();
                    Owner() {
                %s
                    }
                    destructor {
                        for (int i = 0; i < this.items.length; i++) { free this.items[i]; }
                        free items;
                    }
                }
                class Main { public static int main(String[] args) { return 0; } }
                """.formatted(body);
    }

    private static void rejected(String source, UnfreedMode mode, String reason) {
        CompilationArtifact result = new CompilerPipeline(mode).analyze(
                List.of(SourceFile.of("OwnedOrder.iron", source)));
        require(!result.valid() && result.program().isEmpty() && result.llvmIr().isEmpty(),
                mode + " rejected program produced an artifact");
        List<Diagnostic> errors = result.diagnostics().stream().filter(Diagnostic::isError).toList();
        require(errors.size() == 1, mode + " expected one error: " + errors);
        Diagnostic primary = errors.getFirst();
        require(primary.message().equals(PREFIX + reason), mode + " wrong reason: " + primary);
        require(primary.source().path().toString().equals("OwnedOrder.iron")
                        && primary.span().start().line() == 3 && primary.span().start().column() == 20
                        && primary.span().end().line() == 3 && primary.span().end().column() == 25,
                mode + " changed owned-field primary: " + primary);
    }

    private static void accepted(String source, UnfreedMode mode) {
        CompilationArtifact result = new CompilerPipeline(mode).compile(
                List.of(SourceFile.of("OwnedOrder.iron", source)));
        require(result.successful(), mode + " valid creation array rejected: " + result.diagnostics());
    }

    private static void freshProcesses(String source, String reason) throws Exception {
        Path root = Files.createTempDirectory("ironwood-owned-order-");
        try {
            Path input = root.resolve("OwnedOrder.iron");
            Path output = root.resolve("diagnostics.txt");
            Path classes = root.resolve("classes");
            Files.writeString(input, source);
            String first = null;
            for (int run = 0; run < 8; run++) {
                ProcessBuilder builder = new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp", System.getProperty("java.class.path"), Main.class.getName(),
                        "--unfreed=off", input.toString(), "-d", classes.toString())
                        .redirectErrorStream(true).redirectOutput(output.toFile());
                builder.environment().put("IRONWOOD_STDLIB_HOME", Path.of("").toAbsolutePath().toString());
                Process process = builder.start();
                try {
                    require(process.waitFor(30, TimeUnit.SECONDS), "compiler timed out");
                    require(process.exitValue() == 1, "compiler exit: " + process.exitValue());
                    String actual = Files.readString(output);
                    require(actual.lines().filter(line -> line.startsWith("error: ")).toList()
                                    .equals(List.of("error: " + PREFIX + reason)), actual);
                    if (first == null) first = actual;
                    require(actual.equals(first), "owned-element primary varied between JVMs");
                    require(!Files.exists(classes), "rejected program produced class output");
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

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
