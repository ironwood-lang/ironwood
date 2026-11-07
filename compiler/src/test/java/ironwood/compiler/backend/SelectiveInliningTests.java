// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.backend;

import ironwood.compiler.CompilerPipeline;
import ironwood.compiler.BridgeObjectAdmission;
import ironwood.compiler.Main;
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
        var managed = new CompilerPipeline().compile(SourceFile.of(SOURCE.toString(), source));
        require(managed.successful(), managed.diagnostics().toString());
    }

    public static void libraryStructure() throws Exception {
        // Six copies of each statement put the loop above the executable bound (cost 256)
        // and below the library bound (512); D287's null-check omission shrank each copy.
        String source = Files.readString(SOURCE).replace("sum += values[0];", "sum += values[0];\n".repeat(6));
        var original = analyze(source);
        require(!SelectiveInlining.select(original).contains("ironwood.Main.work"), "executable policy changed");
        var library = library(original);
        require(SelectiveInlining.select(library).contains("ironwood.Main.work"), "library loop remains behind a call");
        require(!SelectiveInlining.select(library).contains("ironwood.Main.wrapper"), "export root selected");
        String bridgeSource = "package inlining;\n" + source.replace("class Main {", "public final class Main {")
                .replace("private static int wrapper", "public static int wrapper")
                .replace("public static int main", "private static int exercise");
        var artifact = new CompilerPipeline().analyzeForBridge(List.of(SourceFile.of("Main.iron", bridgeSource)));
        require(artifact.valid(), artifact.diagnostics().toString());
        var proof = BridgeObjectAdmission.proveManaged(artifact, List.of("inlining"));
        require(proof.contract().isPresent(), proof.reason());
        require(new LlvmEmitter().emit(proof.contract().orElseThrow()).contains("i1 %v2) alwaysinline"),
                "admitted library inline attribute missing");
        var executable = analyze(Files.readString(SOURCE));
        require(new LlvmEmitter().emit(executable).contains("i1 %v2) alwaysinline"), "executable inline attribute missing");
        require(!new LlvmEmitter().emit(executable, false).contains("i1 %v2) alwaysinline"), "inline override ignored");
        String recursive = source.replace("return sum;", "if (count > 100) return work(values, count - 1, fail); return sum;");
        require(!SelectiveInlining.select(library(analyze(recursive))).contains("ironwood.Main.work"), "recursive library loop selected");
        String larger = source.replace("sum += values[0];", "sum += values[0];\n".repeat(4));
        require(!SelectiveInlining.select(library(analyze(larger))).contains("ironwood.Main.work"), "library policy bound lost");
    }

    private static IrProgram library(IrProgram program) {
        return new IrProgram(program.moduleName(), program.classes(), program.staticFields(),
                program.typeInitializations(), program.arrayTypes(), program.stringConstants(), program.dispatchSlots(),
                program.functions(), java.util.Optional.empty(), program.allocationFailure(), Set.of("ironwood.Main.wrapper"));
    }

    public static void nativeBehavior() throws Exception {
        nativeBehavior(true);
        nativeBehavior(false);
    }

    private static void nativeBehavior(boolean enabled) throws Exception {
        Path root = Path.of("integration-tests/target/selective-inlining/" + enabled).toAbsolutePath();
        Files.createDirectories(root);
        Path classes = root.resolve("classes");
        cli(SOURCE.toString(), "-d", classes.toString());
        for (int level : List.of(0, 3)) {
            Path binary = root.resolve("program-O" + level);
            Path llvm = root.resolve("program-O" + level + ".ll");
            var arguments = new java.util.ArrayList<>(List.of("--link", "-cp", classes.toString(),
                    "--main-class", "Main", "-O" + level,
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
