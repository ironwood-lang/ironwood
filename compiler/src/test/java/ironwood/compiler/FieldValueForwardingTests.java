// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.ir.*;
import ironwood.compiler.source.SourceFile;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

final class FieldValueForwardingTests {
    private FieldValueForwardingTests() {}

    private static final String SOURCE = """
            class Box {
                int x; int y; Box ref; double floating;
                int get() { return this.x; }
                void set(int value) { this.x = value; }
            }
            class Main {
                static int direct(Box a, Box b) { a.x = 7; b.y = 9; int x = a.x; return x + a.x; }
                static int alias(Box a, Box b) { a.x = 7; b.x = 9; return a.x; }
                static int getter(Box a) { a.set(7); a.y = 4; return a.get() + a.get(); }
                static int repeated(Box a, Box b) { int x = a.get(); b.y = 3; return x + a.get(); }
                static int barrier(Box a) { a.x = 7; change(a); return a.x; }
                static void change(Box a) { a.x++; }
                static int join(Box a, boolean flag) { a.x = 7; if (flag) a.y = 9; return a.x; }
                static int loop(Box a, int n) { a.x = 7; for (int i = 0; i < n; i++) a.x++; return a.x; }
                static boolean reference(Box a, Box b) { a.ref = b; return a.ref == b; }
                static double floating(Box a) { a.floating = -0.0; return a.floating; }
                public static int main(String[] args) { return 0; }
            }
            """;

    static void structure() {
        IrProgram before = new CompilerPipeline().analyze(List.of(SourceFile.of("Main.iron", SOURCE)))
                .program().orElseThrow();
        require(before.entryPoint().isPresent(), "fixture needs a closed entry point");
        IrProgram after = FieldValueForwarder.forward(before);
        require(loads(after, "direct") == 0 && loads(before, "direct") == 2, "direct forwarding missing");
        require(loads(after, "alias") == 1, "possible alias write ignored");
        require(calls(after, "getter", "ironwood.Box.get") == 0, "setter/getter facts missing");
        require(calls(after, "getter", "ironwood.Box.set") == 1, "setter execution removed");
        require(calls(after, "repeated", "ironwood.Box.get") == 1, "repeated getter not forwarded");
        for (String name : List.of("barrier", "join", "loop", "floating")) {
            require(loads(after, name) == loads(before, name), "unsafe forwarding in " + name);
        }
        require(loads(after, "reference") == 0, "reference forwarding missing");
        for (String name : List.of("direct", "alias", "getter", "reference")) {
            List<IrInstruction> original = ops(method(before, name)).filter(FieldValueForwardingTests::retained).toList();
            require(original.equals(ops(method(after, name)).filter(FieldValueForwardingTests::retained).toList()),
                    "store or check changed in " + name);
        }
        require(before.classes().equals(after.classes()), "layout changed");
        require(FieldValueForwarder.forward(after).equals(after), "non-idempotent forwarding");
        barriers(before);
        IrProgram library = new CompilerPipeline().analyze(List.of(SourceFile.of("Box.iron", "class Box { int x; }")))
                .program().orElseThrow();
        require(FieldValueForwarder.forward(library) == library, "open library optimized");
    }

    private static boolean retained(IrInstruction i) {
        return i instanceof IrFieldStoreInstruction || i instanceof IrNullCheckInstruction
                || i instanceof IrArrayBoundsCheckInstruction;
    }

    private static void barriers(IrProgram template) {
        IrFunction original = method(template, "direct");
        var span = original.sourceSpan();
        IrOperand receiver = original.parameters().getFirst().value();
        IrField field = template.classes().stream().filter(c -> c.name().equals("Box")).findFirst().orElseThrow()
                .fields().stream().filter(f -> f.name().equals("x")).findFirst().orElseThrow();
        var result = new IrValueReference(1000, IrType.I32, span);
        var nativeResult = new IrValueReference(1001, IrType.I64, span);
        List<IrInstruction> barriers = List.of(new IrEnsureTypeInitializedInstruction("Box", span),
                new IrRawDeallocateInstruction(receiver, span), new IrFreeInstruction(receiver, span),
                new IrTcpInstruction(nativeResult, IrTcpInstruction.Operation.CREATE,
                        List.of(new IrConstant(IrType.I32, 0, span)), List.of(), span),
                new IrCallInstruction(Optional.empty(), "unknown", IrType.VOID, List.of(receiver), span),
                new IrVirtualCallInstruction(Optional.empty(),
                        new IrDispatchSlot(0, "change", "change", IrType.VOID, List.of(), span),
                        IrType.VOID, List.of(receiver), span));
        for (IrInstruction barrier : barriers) {
            IrBasicBlock block = new IrBasicBlock("entry", List.of(
                    new IrFieldStoreInstruction(receiver, field, new IrConstant(IrType.I32, 7, span), span), barrier,
                    new IrFieldLoadInstruction(result, receiver, field, span)),
                    new IrReturnTerminator(Optional.of(result), span), span);
            IrFunction function = new IrFunction(original.ownerClass(), "direct", original.linkageName(), IrType.I32,
                    original.parameters(), List.of(block), span, original.sourceFileName(), original.kind());
            IrProgram program = new IrProgram(template.moduleName(), template.classes(), template.staticFields(),
                    template.typeInitializations(), template.arrayTypes(), template.stringConstants(), template.dispatchSlots(),
                    List.of(function), Optional.of(function), template.allocationFailure());
            require(loads(FieldValueForwarder.forward(program), "direct") == 1, "barrier ignored: " + barrier);
        }
    }

    static void nativeArtifacts() throws Exception {
        Path source = Path.of("integration-tests/cases/field_value_forwarding.iron");
        Path root = Path.of("integration-tests/target/field-value-forwarding").toAbsolutePath();
        Files.createDirectories(root);
        Path classes = root.resolve("classes");
        cli(source.toString(), "--unfreed=error", "-d", classes.toString());
        Path archive = root.resolve("program.ironjar");
        IronJar.create(archive, List.of(classes));
        for (int level : List.of(0, 3)) {
            for (String kind : List.of("source", "class", "archive")) {
                Path binary = root.resolve(kind + "-O" + level);
                if (kind.equals("source")) {
                    var artifact = new CompilerPipeline(UnfreedMode.ERROR).compile(SourceFile.of(source.toString(), Files.readString(source)));
                    require(artifact.successful(), artifact.diagnostics().toString());
                    Path llvm = root.resolve(kind + "-O" + level + ".ll");
                    Files.writeString(llvm, artifact.llvmIr().orElseThrow());
                    var linked = new ironwood.compiler.backend.NativeBackend().link(
                            ironwood.compiler.backend.LlvmToolchain.discover(null).toolchain().orElseThrow(), llvm, binary,
                            ironwood.compiler.backend.OptimizationLevel.valueOf("O" + level));
                    require(linked.success(), linked.output());
                } else {
                    cli("--link", "-cp", (kind.equals("class") ? classes : archive).toString(),
                            "--main-class", "Main", "--unfreed=error", "-O" + level, "-o", binary.toString());
                }
                Process process = new ProcessBuilder(binary.toString()).redirectErrorStream(true).start();
                String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                require(process.waitFor() == 42 && output.isEmpty(), binary + ": " + output);
            }
        }
    }

    static void safety() {
        String safe = """
                class Box { int value; int read() { return this.value; } }
                class Main { public static int main(String[] args) {
                    Box box = new Box(); box.value = 7; int result = box.read() + box.read();
                    free box; return result;
                } }
                """;
        for (UnfreedMode mode : UnfreedMode.values()) {
            require(new CompilerPipeline(mode).compile(SourceFile.of("safe.iron", safe)).successful(), "safe " + mode);
            for (String invalid : List.of(safe.replace("free box;", "free box; free box;"),
                    safe.replace("free box;", "Box alias = box; free box; result += alias.read();"))) {
                require(!new CompilerPipeline(mode).compile(SourceFile.of("unsafe.iron", invalid)).valid(), "unsafe " + mode);
            }
        }
    }

    private static IrFunction method(IrProgram p, String name) {
        return p.functions().stream().filter(f -> f.ownerClass().equals("Main") && f.sourceName().equals(name))
                .findFirst().orElseThrow();
    }

    private static long loads(IrProgram p, String name) {
        return ops(method(p, name)).filter(IrFieldLoadInstruction.class::isInstance).count();
    }

    private static long calls(IrProgram p, String name, String target) {
        return ops(method(p, name)).filter(i -> i instanceof IrCallInstruction c && c.targetLinkageName().equals(target)).count();
    }

    private static Stream<IrInstruction> ops(IrFunction f) {
        return f.blocks().stream().flatMap(b -> Stream.concat(b.instructions().stream(),
                b.terminator() instanceof IrInvokeTerminator invoke ? Stream.of(invoke.call()) : Stream.empty()));
    }

    private static void cli(String... arguments) {
        var output = new ByteArrayOutputStream();
        try (var stream = new PrintStream(output)) {
            require(Main.run(arguments, stream, stream) == 0, output.toString());
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
