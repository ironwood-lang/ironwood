// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.diagnostic.Diagnostic;
import ironwood.compiler.source.SourceFile;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/** Focused lifetime and diagnostic transport regressions for --unfreed. */
final class UnfreedAllocationTests {
    private UnfreedAllocationTests() {}

    static void abandonedAllocations() {
        String source = """
                import ironwood.util.Random;
                class Chatter {

                    private final Random rand = new Random();

                    String word() {

                        return this.rand.nextBoolean() ? "World" : "Ironwood";
                    }

                    destructor {

                        free this.rand;
                    }
                }
                class Cases {

                    static String word() {

                        return "World";
                    }

                    static String copy() {

                        return new String("copy");
                    }

                    static String rendered() {

                        return "Rendered " + word();
                    }

                    static void chatter() {

                        Chatter chatter = new Chatter();
                        System.out.println(chatter.word());
                    }

                    static void inline() {

                        System.out.println("Hello " + word() + "!");
                    }

                    static void named() {

                        String message = "Hello " + word();
                        System.out.println(message);
                    }

                    static void overwritten() {

                        String old = "Hello " + word();
                        old = "replacement";
                    }

                    static void compound() {

                        String previous = "Hello " + word();
                        previous += word();
                        free previous;
                    }

                    static void array() {

                        int[] values = new int[2];
                    }

                    static void factory() {

                        copy();
                    }

                    static void returnedConcatenation() {

                        rendered();
                    }

                    static void nested() {

                        int length = ("Hello " + word()).length();
                    }
                }
                """;
        SourceFile input = SourceFile.of("test/Unfreed.iron", source);
        CompilationArtifact warned = new CompilerPipeline().analyze(List.of(input));
        require(warned.valid(), warned.diagnostics().toString());
        require(warned.diagnostics().size() == 9, "expected nine findings: " + warned.diagnostics());
        require(warned.diagnostics().stream().noneMatch(Diagnostic::isError), "default severity");
        require(warned.diagnostics().stream().allMatch(d -> d.source() == input && d.span() != null),
                "findings must point to application allocation sites");
        require(warned.diagnostics().stream().anyMatch(d -> d.message().equals(
                "allocation assigned to 'chatter' leaves scope without being freed")), "Chatter diagnostic");
        require(warned.diagnostics().stream().anyMatch(d -> d.message().equals(
                "fresh result of 'rendered' is discarded without being freed")),
                "returned concatenation diagnostic");
        CompilationArtifact strict = new CompilerPipeline(UnfreedMode.ERROR).analyze(List.of(input));
        require(!strict.valid() && strict.diagnostics().size() == 9
                && strict.diagnostics().stream().allMatch(Diagnostic::isError), "strict findings");
        CompilationArtifact disabled = new CompilerPipeline(UnfreedMode.OFF).analyze(List.of(input));
        require(disabled.valid() && disabled.diagnostics().isEmpty(), "off disables only this check");
    }

    static void retainedAndReclaimedAllocations() {
        String source = """
                import ironwood.pool.*;
                class Item {

                    private int[] owned = new int[2];

                    destructor {

                        free this.owned;
                    }
                }
                class Builder implements ObjectBuilder<Item> {

                    @Override
                    public Item newInstance() {

                        return new Item();
                    }
                }
                class Thrower {

                    private static final RuntimeException FAILURE = new RuntimeException();

                    Thrower() {

                        throw FAILURE;
                    }
                }
                class FreshText {

                    @Override
                    public String toString() {

                        return new String("fresh");
                    }
                }
                class Cases {

                    static String retained;

                    static String word() {

                        return "World";
                    }

                    static String first(String a, String b) {

                        return a;
                    }

                    static String returned() {

                        return "Hello " + word();
                    }

                    static void returnedAndFreed() {

                        String message = returned();
                        free message;
                    }

                    static void published() {

                        retained = "Hello " + word();
                    }

                    static void explicit() {

                        String message = "Hello " + word();
                        System.out.println(message);
                        free message;
                        Item item = new Item();
                        free item;
                    }

                    static void aliasesAndArrays() {

                        String survivor = null;
                        {
                            String original = "Hello " + word();
                            survivor = original;
                        }
                        free survivor;
                        Item item = new Item();
                        Item[] slots = { item };
                        free slots;
                        free item;
                    }

                    static int cleanupReturn() {

                        String message = "Hello " + word();
                        try {
                            return message.length();
                        } finally {
                            free message;
                        }
                    }

                    static void failedConstruction() {

                        try {
                            Thrower impossible = new Thrower();
                            free impossible;
                        } catch (RuntimeException failure) {
                            System.out.println("caught");
                        }
                    }

                    static void nestedEvaluation() {

                        String message = first("Hello " + word(), switch (1) {
                            default -> {
                                System.out.println("side effect");
                                yield "unused";
                            }
                        });
                        free message;
                    }

                    static void rendering() {

                        FreshText item = new FreshText();
                        String text = "item=" + item;
                        free text;
                        free item;
                    }

                    static void uncertain(boolean condition) {

                        Item item = new Item();
                        if (condition) free item;
                    }

                    static Item copyOrThrow(boolean fail) {

                        if (fail) throw new RuntimeException();
                        return new Item();
                    }

                    static void failedFactory() {

                        try {
                            Item item = copyOrThrow(true);
                            free item;
                        } catch (RuntimeException failure) {
                            System.out.println("caught");
                        }
                    }

                    static void pooled() {

                        Builder builder = new Builder();
                        ObjectPool<Item> pool = new ArrayObjectPool<Item>(1, builder);
                        Item item = new Item();
                        pool.release(item);
                        free pool;
                        free builder;
                    }

                    static void loopCleanup() {

                        for (int i = 0; i < 2; i++) {
                            Item item = new Item();
                            free item;
                        }
                    }

                    static void constants() {

                        System.out.println("Hello " + "World");
                    }
                }
                """;
        CompilationArtifact result = new CompilerPipeline(UnfreedMode.ERROR)
                .analyze(List.of(SourceFile.of("test/Retained.iron", source)));
        require(result.valid() && result.diagnostics().isEmpty(), result.diagnostics().toString());
        SourceFile invalid = SourceFile.of("test/Invalid.iron", """
                class Invalid {

                    static void check() {

                        Object value = new Object();
                        free value;
                        free value;
                    }
                }
                """);
        require(!new CompilerPipeline(UnfreedMode.OFF).analyze(List.of(invalid)).valid(),
                "off must not weaken safe-free errors");
    }

    static void optionsAndNativeOutput() throws Exception {
        Path root = Files.createTempDirectory("ironwood-unfreed-");
        try {
            SourceFile source = SourceFile.of("Main.iron", """
                    class Main {

                        static String word() {

                            return "World";
                        }

                        public static int main(String[] args) {

                            long before = System.liveAllocationCount();
                            System.out.println("Hello " + word() + "!");
                            return System.liveAllocationCount() == before + 1L ? 0 : 1;
                        }
                    }
                    """);
            CompilationArtifact warned = new CompilerPipeline().compile(source);
            CompilationArtifact off = new CompilerPipeline(UnfreedMode.OFF).compile(source);
            require(warned.successful() && off.successful(), "warning must preserve successful artifacts");
            require(warned.program().equals(off.program()) && warned.llvmIr().equals(off.llvmIr()),
                    "diagnostics must not change typed IR or emitted LLVM");
            Path input = root.resolve("Main.iron");
            Files.writeString(input, source.content());
            Result warn = run(input.toString(), "-d", root.resolve("classes").toString());
            require(warn.exit() == 0 && warn.stderr().contains("warning: concatenation result"), warn.toString());
            Result disabled = run(input.toString(), "-d", root.resolve("off").toString(), "--unfreed=off");
            require(disabled.exit() == 0 && disabled.stderr().isEmpty(), disabled.toString());
            Path rejected = root.resolve("rejected");
            Result error = run(input.toString(), "-d", rejected.toString(), "--unfreed=error");
            require(error.exit() == 1 && error.stderr().contains("error: concatenation result")
                    && !Files.exists(rejected), error.toString());
            for (String mode : List.of("invalid", "")) {
                Result invalid = run(input.toString(), "--unfreed=" + mode);
                require(invalid.exit() == 2 && invalid.stderr().contains("expected off, warn, or error"),
                        invalid.toString());
            }
            Path archive = root.resolve("app.ironjar");
            ByteArrayOutputStream archiveErr = new ByteArrayOutputStream();
            require(IronJarMain.run(new String[]{"--create", "--file", archive.toString(),
                    root.resolve("classes").toString()}, new PrintStream(new ByteArrayOutputStream()),
                    new PrintStream(archiveErr)) == 0, archiveErr.toString());
            Result linkError = run("--link", "-cp", archive.toString(), "--main-class", "Main",
                    "-o", root.resolve("rejected-native").toString(), "--unfreed=error");
            require(linkError.exit() == 1 && linkError.stderr().contains("error: concatenation result")
                    && !Files.exists(root.resolve("rejected-native")), linkError.toString());
            Path executable = root.resolve("app");
            Result linked = run("--link", "-cp", archive.toString(), "--main-class", "Main",
                    "-o", executable.toString(), "-O3", "--unfreed=warn");
            require(linked.exit() == 0 && linked.stderr().contains("warning: concatenation result"), linked.toString());
            Process process = new ProcessBuilder(executable.toString()).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String errors = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            require(process.waitFor() == 0 && output.equals("Hello World!\n") && errors.isEmpty(),
                    "native allocation count and output: " + output + errors);
        } finally {
            try (var files = Files.walk(root)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static Result run(String... args) {
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        int exit = Main.run(args, new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(errors, true, StandardCharsets.UTF_8));
        return new Result(exit, errors.toString(StandardCharsets.UTF_8));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private record Result(int exit, String stderr) {}
}
