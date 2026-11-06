// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.backend.LlvmEmitter;
import ironwood.compiler.backend.LlvmToolchain;
import ironwood.compiler.backend.NativeBackend;
import ironwood.compiler.backend.OptimizationLevel;
import ironwood.compiler.ir.IrFunction;
import ironwood.compiler.ir.IrInstruction;
import ironwood.compiler.ir.IrInvokeTerminator;
import ironwood.compiler.source.SourceFile;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

final class FrontendCorrectnessTests {
    private static final Path SOURCE = Path.of("integration-tests/cases/frontend_correctness.iron");

    private FrontendCorrectnessTests() {}

    static void nativeArtifacts() throws Exception {
        Path root = Path.of("integration-tests/target/frontend-correctness").toAbsolutePath();
        Files.createDirectories(root);
        Path classes = root.resolve("classes");
        cli(SOURCE.toString(), "-d", classes.toString());
        Path archive = root.resolve("frontend.ironjar");
        IronJar.create(archive, List.of(classes));
        for (int level : List.of(0, 3)) {
            for (String kind : List.of("source", "class", "archive")) {
                Path binary = root.resolve(kind + "-O" + level);
                if (kind.equals("source")) {
                    var artifact = new CompilerPipeline().compile(
                            SourceFile.of(SOURCE.toString(), Files.readString(SOURCE)));
                    require(artifact.successful(), artifact.diagnostics().toString());
                    Path llvm = root.resolve(kind + "-O" + level + ".ll");
                    Files.writeString(llvm, new LlvmEmitter().emit(artifact.program().orElseThrow()));
                    var linked = new NativeBackend().link(
                            LlvmToolchain.discover(null).toolchain().orElseThrow(), llvm, binary,
                            OptimizationLevel.valueOf("O" + level));
                    require(linked.success(), linked.output());
                } else {
                    cli("--link", "-cp", (kind.equals("class") ? classes : archive).toString(),
                            "--main-class", "Main", "-O" + level,
                            "-o", binary.toString());
                }
                Process process = new ProcessBuilder(binary.toString()).redirectErrorStream(true).start();
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                int exit = process.waitFor();
                require(exit == 42 && output.isEmpty(), binary + " exited " + exit + ": " + output);
            }
        }
    }

    private static Stream<IrInstruction> instructions(IrFunction function) {
        return function.blocks().stream().flatMap(block -> Stream.concat(
                block.instructions().stream(),
                block.terminator() instanceof IrInvokeTerminator invoke
                        ? Stream.of(invoke.call()) : Stream.empty()));
    }

    private static CompilationArtifact compile(String source) {
        return new CompilerPipeline().compile(SourceFile.of("Main.iron", source));
    }

    private static void reject(String source, String message) {
        var artifact = compile(source);
        require(!artifact.successful() && artifact.diagnostics().stream()
                        .anyMatch(diagnostic -> diagnostic.isError()
                                && diagnostic.message().contains(message)),
                "missing '" + message + "' in " + "GC" + ": " + artifact.diagnostics());
    }

    private static void cli(String... arguments) {
        var output = new ByteArrayOutputStream();
        try (var stream = new PrintStream(output)) {
            require(Main.run(arguments, stream, stream) == 0, output.toString(StandardCharsets.UTF_8));
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
