// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.backend;

import ironwood.compiler.CompilerPipeline;
import ironwood.compiler.Main;
import ironwood.compiler.UnfreedMode;
import ironwood.compiler.ir.*;
import ironwood.compiler.source.SourceFile;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

public final class SelectiveInliningTests {
    private static final Path SOURCE = Path.of("integration-tests/cases/selective_inlining.iron");

    private SelectiveInliningTests() {}

    public static void structure() throws Exception {
        String source = Files.readString(SOURCE);
        var program = analyze(source);
        Set<String> selected = SelectiveInlining.select(program);
        require(selected.contains("ironwood.Main.work"), "positive loop not selected: " + selected);
        require(selected.size() <= 8 && !selected.contains("ironwood.Main.wrapper"), "nonloop or count bound");
        String noLoop = source.replace("for (int i = 0; i < count; i++)", "if (count > 0)");
        require(!SelectiveInlining.select(analyze(noLoop)).contains("ironwood.Main.work"), "nonloop selected");
        String large = source.replace("sum += values[0];", "sum += values[0];\n".repeat(8));
        require(!SelectiveInlining.select(analyze(large)).contains("ironwood.Main.work"), "large body selected");
        String bigCaller = source.replace("return work(values, count, fail);",
                "count += values[0];\n".repeat(10) + "return work(values, count, fail);");
        require(!SelectiveInlining.select(analyze(bigCaller)).contains("ironwood.Main.work"), "large caller selected");
        String recursive = source.replace("return sum;", "if (count > 100) return work(values, count - 1, fail); return sum;");
        require(!SelectiveInlining.select(analyze(recursive)).contains("ironwood.Main.work"), "recursive target selected");
        String mutual = source.replace("return sum;", "if (count > 100) return wrapper(values, count - 1, fail); return sum;");
        require(!SelectiveInlining.select(analyze(mutual)).contains("ironwood.Main.work"), "mutually recursive target selected");
        for (UnfreedMode mode : UnfreedMode.values()) {
            var safe = new CompilerPipeline(mode).compile(SourceFile.of("case.iron", source));
            var unsafe = new CompilerPipeline(mode).compile(SourceFile.of("case.iron",
                    source.replace("free values;", "free values; free values;")));
            require(safe.valid() && !unsafe.valid() && unsafe.program().isEmpty(), "cleanup safety changed " + mode);
        }
        require(new LlvmEmitter().emit(program).contains("i1 %v2) alwaysinline"), "selected function missing attribute");
        require(!new LlvmEmitter().emit(program, false).contains("i1 %v2) alwaysinline"),
                "disabled policy retained selected attribute");
    }

    public static void nativeBehavior() throws Exception {
        nativeBehavior(true);
        nativeBehavior(false);
    }

    private static void nativeBehavior(boolean enabled) throws Exception {
        Path root = Path.of("integration-tests/target/selective-inlining/" + enabled).toAbsolutePath();
        Files.createDirectories(root);
        Path classes = root.resolve("classes");
        cli(SOURCE.toString(), "--unfreed=error", "-d", classes.toString());
        for (int level : List.of(0, 3)) {
            Path binary = root.resolve("program-O" + level);
            Path llvm = root.resolve("program-O" + level + ".ll");
            var arguments = new java.util.ArrayList<>(List.of("--link", "-cp", classes.toString(),
                    "--main-class", "Main", "--unfreed=error", "-O" + level,
                    "--emit-llvm", llvm.toString(), "-o", binary.toString()));
            if (!enabled) arguments.addAll(List.of("--selective-inlining=off", "--inline-threshold", "0"));
            cli(arguments.toArray(String[]::new));
            require(Files.readString(llvm).contains("i1 %v2) alwaysinline") == enabled,
                    "artifact link ignored selected inline policy");
            Path output = root.resolve("O" + level + ".output");
            Process normal = new ProcessBuilder(binary.toString()).redirectErrorStream(true)
                    .redirectOutput(output.toFile()).start();
            require(normal.waitFor() == 42 && Files.readString(output).isEmpty(), "native check failed");
            Path trace = root.resolve("O" + level + ".trace");
            Process failure = new ProcessBuilder(binary.toString(), "fail").redirectErrorStream(true)
                    .redirectOutput(trace.toFile()).start();
            require(failure.waitFor() == 1 && Files.readString(trace).equals(
                    "uncaught Ironwood exception: ironwood.lang.IllegalArgumentException: selective inline trace\n"
                            + "\tat Main.work(selective_inlining.iron:18)\n"
                            + "\tat Main.wrapper(selective_inlining.iron:24)\n"
                            + "\tat Main.main(selective_inlining.iron:35)\n"),
                    "inlined trace changed: " + Files.readString(trace));
        }
    }

    private static IrProgram analyze(String source) {
        var result = new CompilerPipeline().analyze(List.of(SourceFile.of("case.iron", source)));
        require(result.valid(), result.diagnostics().toString());
        return result.program().orElseThrow();
    }

    private static void cli(String... args) {
        var output = new ByteArrayOutputStream();
        try (var stream = new PrintStream(output)) {
            require(Main.run(args, stream, stream) == 0, output.toString());
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
