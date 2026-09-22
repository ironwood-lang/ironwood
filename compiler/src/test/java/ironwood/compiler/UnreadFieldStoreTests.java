// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.ir.*;
import ironwood.compiler.source.SourceFile;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

final class UnreadFieldStoreTests {
    private static final Path SOURCE = Path.of("integration-tests/cases/unread_primitive_stores.iron");

    private UnreadFieldStoreTests() {}

    static void structure() throws Exception {
        var artifact = new CompilerPipeline(UnfreedMode.ERROR).compile(SourceFile.of(
                SOURCE.toString(), Files.readString(SOURCE)));
        require(artifact.valid(), artifact.diagnostics().toString());
        IrProgram before = ClosedWorldPruner.prune(artifact.program().orElseThrow());
        IrProgram after = UnreadFieldStoreEliminator.eliminate(before);
        require(stores(before).anyMatch(s -> s.field().name().equals("dead")), "fixture lacks dead writes");
        require(stores(after).noneMatch(s -> s.field().name().equals("dead")), "unread writes survived");
        require(stores(after).filter(s -> s.field().name().equals("live"))
                .map(s -> s.field().ownerClass()).distinct().count() == 2, "hidden live storage lost");
        require(stores(after).anyMatch(s -> s.field().name().equals("observed")), "live object read ignored");
        require(stores(after).filter(s -> s.field().name().equals("item"))
                .noneMatch(s -> s.field().type().isPrimitive()), "specialized primitive store survived");
        require(stores(after).anyMatch(s -> s.field().name().equals("item")
                && s.field().type().isReference()), "reference store removed");
        require(before.classes().equals(after.classes()), "object layouts changed");
        require(stores(before).filter(s -> s.field().ownerClass().equals("ironwood.lang.Throwable")).toList()
                .equals(stores(after).filter(s -> s.field().ownerClass().equals("ironwood.lang.Throwable")).toList()),
                "runtime layout stores changed");
        require(before.functions().size() == after.functions().size(), "functions removed");
        for (int f = 0; f < before.functions().size(); f++) {
            IrFunction original = before.functions().get(f), result = after.functions().get(f);
            require(original.sourceSpan().equals(result.sourceSpan())
                    && original.traceCallableName().equals(result.traceCallableName()), "trace identity changed");
            for (int b = 0; b < original.blocks().size(); b++) {
                IrBasicBlock from = original.blocks().get(b), to = result.blocks().get(b);
                require(from.terminator().equals(to.terminator()), "exception or control edge changed");
                require(from.instructions().stream().filter(i -> !(i instanceof IrFieldStoreInstruction)).toList()
                        .equals(to.instructions().stream().filter(i -> !(i instanceof IrFieldStoreInstruction)).toList()),
                        "checks, effects or evaluations removed");
            }
        }
        require(UnreadFieldStoreEliminator.eliminate(after).equals(after), "pass is not idempotent");
        var library = new CompilerPipeline().analyze(List.of(SourceFile.of("Library.iron",
                "class Library { int value; void set(int x) { this.value = x; } }"))).program().orElseThrow();
        require(UnreadFieldStoreEliminator.eliminate(library) == library, "open library transformed");
        nativeFieldAddresses(before.entryPoint().orElseThrow().sourceSpan());
    }

    private static void nativeFieldAddresses(ironwood.compiler.source.SourceSpan span) {
        // A typed native boundary can observe storage even without any typed load.
        String owner = "ironwood.net.SocketDescriptor";
        var fields = new java.util.ArrayList<IrField>();
        var instructions = new java.util.ArrayList<IrInstruction>();
        var receiver = new IrValueReference(0, IrType.reference(owner), span);
        var operation = IrTcpInstruction.Operation.ENDPOINT;
        for (String name : operation.outputNames()) {
            var field = new IrField(owner, name, IrType.I32, fields.size(), span);
            fields.add(field);
            instructions.add(new IrFieldStoreInstruction(receiver, field, new IrConstant(IrType.I32, 7, span), span));
        }
        var result = new IrValueReference(1, IrType.I64, span);
        instructions.add(new IrTcpInstruction(result, operation, List.of(new IrConstant(IrType.I32, 0, span),
                new IrConstant(IrType.I1, 0, span), receiver), fields, span));
        var function = new IrFunction(owner, "query", "test.query", IrType.I64,
                List.of(new IrParameter("this", receiver, span)), List.of(new IrBasicBlock("entry", instructions,
                new IrReturnTerminator(java.util.Optional.of(result), span), span)), span);
        var type = new IrClass(owner, IrTypeKind.CLASS, java.util.Optional.empty(), List.of(), fields,
                1, List.of(1), List.of(), span);
        var program = new IrProgram("native-fields", List.of(type), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(function), java.util.Optional.of(function), java.util.Optional.empty());
        require(stores(UnreadFieldStoreEliminator.eliminate(program)).count() == fields.size(),
                "native field stores removed");
    }

    static void nativeArtifacts() throws Exception {
        Path root = Path.of("integration-tests/target/unread-primitive-stores").toAbsolutePath();
        Files.createDirectories(root);
        Path classes = root.resolve("classes");
        cli(SOURCE.toString(), "--unfreed=error", "-d", classes.toString());
        Path archive = root.resolve("stores.ironjar");
        IronJar.create(archive, List.of(classes));
        for (int level : List.of(0, 3)) {
            for (String kind : List.of("source", "classes", "archive")) {
                Path binary = root.resolve(kind + "-O" + level);
                var args = new java.util.ArrayList<>(List.of("--link", "--main-class", "Main", "--unfreed=error",
                        "-O" + level, "-o", binary.toString()));
                if (kind.equals("source")) {
                    var artifact = new CompilerPipeline(UnfreedMode.ERROR).compile(SourceFile.of(
                            SOURCE.toString(), Files.readString(SOURCE)));
                    var linked = UnreadFieldStoreEliminator.eliminate(
                            ClosedWorldPruner.prune(artifact.program().orElseThrow()));
                    Path llvm = root.resolve("source.ll");
                    Files.writeString(llvm, new ironwood.compiler.backend.LlvmEmitter().emit(linked));
                    var result = new ironwood.compiler.backend.NativeBackend().link(
                            ironwood.compiler.backend.LlvmToolchain.discover(null).toolchain().orElseThrow(),
                            llvm, binary, ironwood.compiler.backend.OptimizationLevel.valueOf("O" + level));
                    require(result.success(), result.output());
                } else {
                    args.addAll(List.of("-cp", (kind.equals("classes") ? classes : archive).toString()));
                    cli(args.toArray(String[]::new));
                }
                Process process = new ProcessBuilder(binary.toString()).redirectErrorStream(true).start();
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                int exit = process.waitFor();
                require(exit == 42 && output.isEmpty(), binary + " exited " + exit + ": " + output);
            }
        }
    }

    static void safety() {
        String safe = """
                class Box { int unread; }
                class Main { public static int main(String[] args) {
                    Box box = new Box(); box.unread = 1; free box; return 0;
                } }
                """;
        for (UnfreedMode mode : UnfreedMode.values()) {
            require(new CompilerPipeline(mode).compile(SourceFile.of("safe.iron", safe)).valid(), "safe store rejected");
            for (String invalid : List.of(safe.replace("free box;", "free box; box.unread = 2;"),
                    safe.replace("free box;", "free box; free box;"))) {
                var artifact = new CompilerPipeline(mode).compile(SourceFile.of("unsafe.iron", invalid));
                require(!artifact.valid() && artifact.program().isEmpty(), "unsafe dead write accepted " + mode);
            }
        }
    }

    private static Stream<IrFieldStoreInstruction> stores(IrProgram program) {
        return program.functions().stream().flatMap(f -> f.blocks().stream())
                .flatMap(b -> b.instructions().stream()).filter(IrFieldStoreInstruction.class::isInstance)
                .map(IrFieldStoreInstruction.class::cast);
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
