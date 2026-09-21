// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.ir.*;
import ironwood.compiler.source.SourceFile;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

final class EnumArgumentTests {
    private static final Path SOURCE = Path.of("integration-tests/cases/enum_argument_specialization.iron");

    private EnumArgumentTests() {}

    static void structure() throws Exception {
        var raw = new CompilerPipeline().analyze(List.of(SourceFile.of("case.iron", Files.readString(SOURCE))))
                .program().orElseThrow();
        require(EnumArgumentSpecializer.specialize(raw) == raw, "ordinary static loads became constants");
        var initialized = InitializedTypeSpecializer.specialize(raw);
        var result = EnumArgumentSpecializer.specialize(initialized);
        var clones = result.functions().stream().filter(f -> f.linkageName().contains(".$enumarg.")).toList();
        require(clones.stream().anyMatch(f -> f.sourceName().equals("score")), "constant positive was skipped");
        require(clones.stream().noneMatch(f -> f.sourceName().equals("recursive")), "recursive target cloned");
        require(clones.size() <= 32 && cost(result) - cost(initialized) <= 2048, "global budget exceeded");
        for (var original : initialized.functions()) {
            var copies = clones.stream().filter(f -> f.linkageName().startsWith(original.linkageName() + ".$enumarg.")).toList();
            require(copies.size() <= 2, "per-target budget exceeded");
            for (var clone : copies) {
                require(clone.parameters().equals(original.parameters()) && clone.sourceSpan().equals(original.sourceSpan())
                        && clone.traceCallableName().equals(original.traceCallableName()), "ABI or trace identity changed");
                require(clone.blocks().size() == original.blocks().size(), "CFG size changed");
                require(ops(clone).filter(IrFieldLoadInstruction.class::isInstance).count()
                        == ops(original).filter(IrFieldLoadInstruction.class::isInstance).count(), "mutable load removed");
            }
        }
        require(result.functions().stream().filter(f -> f.sourceName().equals("dynamic"))
                .flatMap(EnumArgumentTests::ops).filter(IrCallInstruction.class::isInstance)
                .map(IrCallInstruction.class::cast).noneMatch(c -> c.targetLinkageName().contains(".$enumarg.")),
                "dynamic argument specialized");
        require(EnumArgumentSpecializer.specialize(result) == result, "pass not idempotent");
        require(clones.stream().filter(f -> f.sourceName().equals("score")).count() >= 2,
                "two distinct identities were not selected");

        String safe = "class Main { public static int main(String[] a) { int[] x = new int[1]; free x; return 0; } }";
        for (var mode : UnfreedMode.values()) {
            require(new CompilerPipeline(mode).compile(SourceFile.of("safe.iron", safe)).valid(), "safe cleanup rejected");
            require(!new CompilerPipeline(mode).compile(SourceFile.of("unsafe.iron",
                    safe.replace("free x;", "free x; free x;"))).valid(), "double free accepted " + mode);
        }
    }

    static void nativeArtifacts() throws Exception {
        Path root = Path.of("integration-tests/target/enum-argument").toAbsolutePath();
        Files.createDirectories(root);
        Path classes = root.resolve("classes");
        cli(SOURCE.toString(), "--unfreed=error", "-d", classes.toString());
        Path archive = root.resolve("program.ironjar");
        IronJar.create(archive, List.of(classes));
        for (int level : List.of(0, 3)) {
            Path binary = root.resolve("program-O" + level);
            Path llvm = root.resolve("program-O" + level + ".ll");
            cli("--link", "-cp", (level == 0 ? classes : archive).toString(), "--main-class", "Main", "--unfreed=error",
                    "-O" + level, "--emit-llvm", llvm.toString(), "-o", binary.toString());
            require(Files.readString(llvm).contains(".$enumarg."), "artifact link skipped specialization");
            Path output = root.resolve("O" + level + ".output");
            Process process = new ProcessBuilder(binary.toString()).redirectErrorStream(true)
                    .redirectOutput(output.toFile()).start();
            require(process.waitFor() == 42 && Files.readString(output).isEmpty(), "native enum result " + output);
            Path trace = root.resolve("O" + level + ".trace");
            Process failure = new ProcessBuilder(binary.toString(), "fail").redirectErrorStream(true)
                    .redirectOutput(trace.toFile()).start();
            require(failure.waitFor() == 1 && Files.readString(trace).equals(
                    "uncaught Ironwood exception: ironwood.lang.IllegalArgumentException: enum argument trace\n"
                            + "\tat Main.score(enum_argument_specialization.iron:17)\n"
                            + "\tat Main.drive(enum_argument_specialization.iron:36)\n"
                            + "\tat Main.main(enum_argument_specialization.iron:50)\n"),
                    "specialized trace changed: " + Files.readString(trace));
        }
    }

    private static void cli(String... args) {
        var output = new ByteArrayOutputStream();
        try (var stream = new PrintStream(output)) {
            require(Main.run(args, stream, stream) == 0, output.toString());
        }
    }

    private static int cost(IrProgram program) {
        return program.functions().stream().flatMap(f -> f.blocks().stream())
                .mapToInt(b -> b.instructions().size() + 1).sum();
    }

    private static Stream<IrInstruction> ops(IrFunction function) {
        return function.blocks().stream().flatMap(b -> Stream.concat(b.instructions().stream(),
                b.terminator() instanceof IrInvokeTerminator i ? Stream.of(i.call()) : Stream.empty()));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
