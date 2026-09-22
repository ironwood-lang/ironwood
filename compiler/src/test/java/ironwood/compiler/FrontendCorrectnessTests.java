// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.backend.LlvmEmitter;
import ironwood.compiler.backend.LlvmToolchain;
import ironwood.compiler.backend.NativeBackend;
import ironwood.compiler.backend.OptimizationLevel;
import ironwood.compiler.ir.IrCallableKind;
import ironwood.compiler.ir.IrFunction;
import ironwood.compiler.ir.IrInstruction;
import ironwood.compiler.ir.IrInvokeTerminator;
import ironwood.compiler.ir.IrNullCheckInstruction;
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
        cli(SOURCE.toString(), "--unfreed=error", "-d", classes.toString());
        Path archive = root.resolve("frontend.ironjar");
        IronJar.create(archive, List.of(classes));
        for (int level : List.of(0, 3)) {
            for (String kind : List.of("source", "class", "archive")) {
                Path binary = root.resolve(kind + "-O" + level);
                if (kind.equals("source")) {
                    var artifact = new CompilerPipeline(UnfreedMode.ERROR).compile(
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
                            "--main-class", "Main", "--unfreed=error", "-O" + level,
                            "-o", binary.toString());
                }
                Process process = new ProcessBuilder(binary.toString()).redirectErrorStream(true).start();
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                int exit = process.waitFor();
                require(exit == 42 && output.isEmpty(), binary + " exited " + exit + ": " + output);
            }
        }
    }

    static void destructorSafety() {
        String safe = """
                class Box {
                    int value;
                    Box(int value) { this.value = value; }
                    destructor {
                        Box alias = this;
                        Main.seen = this.value + alias.value + value;
                    }
                }
                class Main {
                    static int seen;
                    public static int main(String[] args) {
                        Box box = new Box(14); free box; return seen;
                    }
                }
                """;
        String nullableReceiver = """
                class Box {
                    Box other; int value;
                    destructor { Main.seen = this.other.value; }
                }
                class Main { static int seen; public static int main(String[] args) { return 0; } }
                """;
        String allocation = """
                class Box { destructor { Object temporary = new Object(); free temporary; } }
                class Main { public static int main(String[] args) { return 0; } }
                """;
        String publication = """
                class Box { destructor { Main.saved = this; } }
                class Main { static Box saved; public static int main(String[] args) { return 0; } }
                """;
        String useAfterFree = """
                class Box { int value; }
                class Main { public static int main(String[] args) {
                    Box box = new Box(); Box alias = box; free box; return alias.value;
                } }
                """;
        for (UnfreedMode mode : UnfreedMode.values()) {
            var accepted = compile(safe, mode);
            require(accepted.successful(), "safe destructor rejected in " + mode + ": "
                    + accepted.diagnostics());
            IrFunction destructor = accepted.program().orElseThrow().functions().stream()
                    .filter(function -> function.ownerClass().equals("Box")
                            && function.kind() == IrCallableKind.DESTRUCTOR)
                    .findFirst().orElseThrow();
            require(instructions(destructor).noneMatch(IrNullCheckInstruction.class::isInstance),
                    "proven receiver retained a null failure path in " + mode);
            reject(nullableReceiver, mode, "destructor may allocate");
            reject(nullableReceiver, mode, "an exception may escape this destructor");
            reject(allocation, mode, "destructor may allocate");
            reject(publication, mode, "destructor may publish or resurrect 'this'");
            require(!compile(useAfterFree, mode).successful(),
                    "mandatory use-after-free safety changed in " + mode);
        }
    }

    private static Stream<IrInstruction> instructions(IrFunction function) {
        return function.blocks().stream().flatMap(block -> Stream.concat(
                block.instructions().stream(),
                block.terminator() instanceof IrInvokeTerminator invoke
                        ? Stream.of(invoke.call()) : Stream.empty()));
    }

    private static CompilationArtifact compile(String source, UnfreedMode mode) {
        return new CompilerPipeline(mode).compile(SourceFile.of("Main.iron", source));
    }

    private static void reject(String source, UnfreedMode mode, String message) {
        var artifact = compile(source, mode);
        require(!artifact.successful() && artifact.diagnostics().stream()
                        .anyMatch(diagnostic -> diagnostic.isError()
                                && diagnostic.message().contains(message)),
                "missing '" + message + "' in " + mode + ": " + artifact.diagnostics());
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
