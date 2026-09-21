// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.backend;

import ironwood.compiler.CompilerPipeline;
import ironwood.compiler.IronJarMain;
import ironwood.compiler.Main;
import ironwood.compiler.UnfreedMode;
import ironwood.compiler.source.SourceFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class NativeTargetTests {
    private NativeTargetTests() {}

    public static void layoutMatchesClang() throws Exception {
        LlvmToolchain tools = LlvmToolchain.discover(null).toolchain().orElseThrow();
        Path root = Files.createTempDirectory("ironwood-target-layout-");
        Path source = root.resolve("layout.c");
        Files.writeString(source, """
                #include <stddef.h>
                #include <stdint.h>
                #include <stdio.h>
                struct mixed { void *type; int32_t tag; int64_t count;
                    float fraction; uint8_t flag; double amount; void *reference; };
                struct array { void *type; size_t length; size_t element_size;
                    uint32_t kind; uint32_t reserved; unsigned char data[]; };
                struct part { uint32_t kind; uint64_t payload; };
                int main(void) {
                    printf("%zu %zu %zu %zu %zu %zu %zu %zu\\n", sizeof(struct mixed),
                        offsetof(struct mixed, count), offsetof(struct mixed, amount),
                        offsetof(struct mixed, reference), sizeof(struct array),
                        offsetof(struct array, data), sizeof(struct part), offsetof(struct part, payload));
                    return 0;
                }
                """);
        try {
            for (TargetMachine machine : TargetMachine.values()) {
                Path cBinary = root.resolve("c-layout");
                var command = new ArrayList<>(List.of(tools.clang().toString()));
                command.addAll(machine.clangArguments());
                command.addAll(List.of(source.toString(), "-o", cBinary.toString()));
                run(command, 0);
                String[] expected = run(List.of(cBinary.toString()), 0).strip().split(" ");
                String[] offsets = {
                    "getelementptr (%mixed, ptr null, i32 1)",
                    "getelementptr (%mixed, ptr null, i32 0, i32 2)",
                    "getelementptr (%mixed, ptr null, i32 0, i32 5)",
                    "getelementptr (%mixed, ptr null, i32 0, i32 6)",
                    "getelementptr (%array, ptr null, i32 1)",
                    "getelementptr (%array, ptr null, i32 0, i32 5)",
                    "getelementptr (%part, ptr null, i32 1)",
                    "getelementptr (%part, ptr null, i32 0, i32 2)"
                };
                StringBuilder llvm = new StringBuilder("""
                        %mixed = type { ptr, i32, i64, float, i8, double, ptr }
                        %array = type { ptr, i64, i64, i32, i32, [0 x i8] }
                        %part = type { i32, i32, i64 }
                        define i32 @main() {
                        entry:
                        """);
                for (int index = 0; index < offsets.length; index++) {
                    llvm.append("  %ok").append(index).append(" = icmp eq i64 ptrtoint (ptr ")
                            .append(offsets[index]).append(" to i64), ").append(expected[index]).append('\n');
                    llvm.append("  %all").append(index).append(" = and i1 %ok").append(index)
                            .append(", ").append(index == 0 ? "true" : "%all" + (index - 1)).append('\n');
                }
                llvm.append("  %exit = select i1 %all7, i32 42, i32 1\n  ret i32 %exit\n}\n");
                Path input = root.resolve("layout.ll");
                Files.writeString(input, llvm);
                for (OptimizationLevel level : List.of(OptimizationLevel.O0, OptimizationLevel.O3)) {
                    Path binary = root.resolve("llvm-layout");
                    LinkResult link = new NativeBackend().link(tools, input, binary, level,
                            NativeLinkRequirements.NONE, machine);
                    require(link.success(), machine + " " + level + ": " + link.output());
                    run(List.of(binary.toString()), 42);
                    require(Files.readString(input).equals(llvm.toString()), "native link changed portable input");
                }
            }
            Path probe = root.resolve("target.ll");
            var command = new ArrayList<>(List.of(tools.clang().toString(), "-S", "-emit-llvm",
                    source.toString(), "-o", probe.toString()));
            if (System.getProperty("os.name").startsWith("Mac")) {
                command.add("-mmacosx-version-min=11.0");
            }
            run(command, 0);
            NativeTarget target = NativeTarget.fromLlvm(Files.readString(probe));
            if (System.getProperty("os.name").startsWith("Mac")) {
                require(target.triple().endsWith("macosx11.0.0"), "deployment target lost");
            }
            String attached = target.applyTo("; portable module\n");
            require(target.applyTo(attached).equals(attached), "matching target was duplicated");
            reject(() -> target.applyTo("target triple = \"different\"\n"));
            reject(() -> target.applyTo("target datalayout = \"different\"\n"));
            reject(() -> NativeTarget.fromLlvm("target triple = \"missing-layout\"\n"));
            reject(() -> NativeTarget.fromLlvm(attached + attached));
        } finally {
            deleteTree(root);
        }
    }

    public static void mixedObjectsAcrossArtifacts() throws Exception {
        Path fixture = Path.of("integration-tests/cases/native_target_layout.iron");
        String source = Files.readString(fixture);
        String unsafe = source.replace("defer free child;", "defer free child; free child;");
        require(!source.equals(unsafe), "unsafe cleanup mutation missed");
        for (UnfreedMode mode : UnfreedMode.values()) {
            var safeResult = new CompilerPipeline(mode).compile(SourceFile.of(fixture.toString(), source));
            require(safeResult.successful(), mode + " safe cleanup: " + safeResult.diagnostics());
            var unsafeResult = new CompilerPipeline(mode).compile(SourceFile.of(fixture.toString(), unsafe));
            require(!unsafeResult.successful(), mode + " accepted double free");
        }
        Path root = Files.createTempDirectory("ironwood-target-objects-");
        try {
            Path classes = root.resolve("classes");
            cli(fixture.toString(), "-d", classes.toString(), "--unfreed=error");
            Path archive = root.resolve("layout.ironjar");
            var errors = new ByteArrayOutputStream();
            require(IronJarMain.run(new String[]{"--create", "--file", archive.toString(), classes.toString()},
                    new PrintStream(new ByteArrayOutputStream()), new PrintStream(errors)) == 0,
                    "archive: " + errors);
            for (Path artifact : List.of(classes, archive)) {
                for (String level : List.of("-O0", "-O3")) {
                    for (TargetMachine machine : TargetMachine.values()) {
                        Path binary = root.resolve("program");
                        Path llvm = root.resolve("program.ll");
                        var arguments = new ArrayList<>(List.of("--link", "-cp", artifact.toString(),
                                "--main-class", "Main", "-o", binary.toString(), "--unfreed=error",
                                "--emit-llvm", llvm.toString(), level));
                        if (machine == TargetMachine.NATIVE) arguments.add("-march=native");
                        cli(arguments.toArray(String[]::new));
                        require(!Files.readString(llvm).contains("target triple ="), "raw LLVM became host-specific");
                        require(!Files.readString(llvm).contains("target datalayout ="), "raw LLVM layout became host-specific");
                        run(List.of(binary.toString(), "dynamic-seed"), 42);
                    }
                }
            }
        } finally {
            deleteTree(root);
        }
    }

    private static void cli(String... arguments) {
        var errors = new ByteArrayOutputStream();
        require(Main.run(arguments, new PrintStream(new ByteArrayOutputStream()), new PrintStream(errors)) == 0,
                String.join(" ", arguments) + ": " + errors);
    }

    private static String run(List<String> command, int expected) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        require(process.waitFor() == expected, command + ": " + output);
        return output;
    }

    private static void reject(IoAction action) throws IOException {
        try {
            action.run();
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError("inconsistent target information was accepted");
    }

    private static void deleteTree(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    @FunctionalInterface
    private interface IoAction {
        void run() throws IOException;
    }
}
