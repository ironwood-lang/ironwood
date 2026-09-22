// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.ir.*;
import ironwood.compiler.source.SourceFile;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

final class InitializedEnumFieldTests {
    private InitializedEnumFieldTests() {}

    private static final String SOURCE = """
            // SPDX-License-Identifier: MIT OR Apache-2.0
            enum Value {
                FIRST(7, 31L), SECOND(11, 43L);
                final int index;
                final long code;
                Value(int index, long code) { this.index = index; this.code = code; }
                int index() { return this.index; }
                long code() { return this.code; }
            }
            class Main {
                static int leaf() { return Value.FIRST.index() + Value.SECOND.index(); }
                static long direct() { return Value.FIRST.code + Value.SECOND.code(); }
                static long loop(int n) {
                    long sum = 0L;
                    for (int i = 0; i < n; i++) sum += leaf() + direct();
                    return sum;
                }
                public static int main(String[] args) {
                    if (loop(0) != 0L || loop(1) != 92L || loop(2) != 184L) return 1;
                    return 42;
                }
            }
            """;

    static void structure() {
        IrProgram raw = analyze(SOURCE);
        IrProgram folded = InitializedTypeSpecializer.specialize(raw);
        require(fast(folded, "leaf").flatMap(InitializedEnumFieldTests::ops)
                .noneMatch(InitializedEnumFieldTests::accessor), "proven accessor calls remain");
        require(fast(folded, "direct").flatMap(InitializedEnumFieldTests::ops)
                .noneMatch(i -> i instanceof IrFieldLoadInstruction || accessor(i)),
                "direct or long field read remains");
        for (long expected : List.of(7L, 11L, 31L, 43L)) {
            require(folded.functions().stream().filter(f -> f.linkageName().contains(".$initialized."))
                    .flatMap(InitializedEnumFieldTests::ops).anyMatch(i -> i instanceof IrBinaryInstruction binary
                            && binary.left() instanceof IrConstant c && c.value().longValue() == expected),
                    "missing exact payload " + expected);
        }
        for (IrFunction function : raw.functions()) {
            IrFunction replacement = folded.functions().stream().filter(f -> f.linkageName().equals(function.linkageName()))
                    .findFirst().orElseThrow();
            require(replacement.blocks().containsAll(function.blocks()), "original fallback or constructor changed");
        }
        require(InitializedTypeSpecializer.specialize(folded) == folded, "non-idempotent specialization");
        require(raw.classes().stream().filter(c -> c.name().equals("Value")).flatMap(c -> c.fields().stream())
                .filter(f -> f.name().equals("index") || f.name().equals("code")).allMatch(IrField::isFinal),
                "final metadata lost");
        IrProgram generic = analyze("""
                class Box<T> { final T value; Box(T value) { this.value = value; } T get() { return this.value; } }
                class Main { public static int main(String[] args) {
                    Box<int> box = new Box<int>(7); int result = box.get(); free box; return result;
                } }
                """);
        List<IrField> genericFields = generic.classes().stream().flatMap(c -> c.fields().stream())
                .filter(f -> f.name().equals("value") && f.type().equals(IrType.I32)).toList();
        require(!genericFields.isEmpty() && genericFields.stream().allMatch(IrField::isFinal), "generic final metadata lost");
        for (String source : List.of(
                SOURCE.replace("final int", "int").replace("final long", "long"),
                SOURCE.replace("FIRST(7, 31L)", "FIRST(Config.read(), 31L)")
                        + "class Config { static int read() { return 7; } }",
                SOURCE.replace("this.index = index;", "Config.touch(); this.index = index;")
                        + "class Config { static void touch() {} }",
                SOURCE.replace("Value(int index, long code) {", "Value(int index, long code) { this(index, code, 0); } Value(int index, long code, int ignored) {"),
                SOURCE.replace("FIRST(7, 31L)", "FIRST(7, 31L) { int extra() { return 1; } }"),
                SOURCE.replace("int index() { return this.index; }", "int index() { Config.touch(); return this.index; }")
                        + "class Config { static int count; static void touch() { count++; } }")) {
            IrProgram fallback = InitializedTypeSpecializer.specialize(analyze(source));
            require(fast(fallback, "leaf").flatMap(InitializedEnumFieldTests::ops)
                    .anyMatch(InitializedEnumFieldTests::accessor), "unsupported proof folded accessor: " + source);
        }
        IrFunction initializer = raw.functions().stream().filter(f -> f.ownerClass().equals("Value")
                && f.kind() == IrCallableKind.CLASS_INITIALIZER).findFirst().orElseThrow();
        IrBasicBlock entry = initializer.blocks().getFirst();
        IrInstruction construct = entry.instructions().stream().filter(IrCallInstruction.class::isInstance)
                .findFirst().orElseThrow();
        List<IrInstruction> repeated = new ArrayList<>(entry.instructions());
        repeated.add(construct);
        IrFunction twice = copy(initializer, List.of(new IrBasicBlock(entry.label(), repeated, entry.terminator(), entry.sourceSpan())));
        requireUnfolded(replace(raw, initializer, twice), "repeated constructor");
        IrFunction leaf = raw.functions().stream().filter(f -> f.sourceName().equals("leaf")).findFirst().orElseThrow();
        List<IrBasicBlock> foreignBlocks = new ArrayList<>(leaf.blocks());
        IrBasicBlock first = foreignBlocks.getFirst();
        List<IrInstruction> foreign = new ArrayList<>(first.instructions());
        foreign.add(0, construct);
        foreignBlocks.set(0, new IrBasicBlock(first.label(), foreign, first.terminator(), first.sourceSpan()));
        requireUnfolded(replace(raw, leaf, copy(leaf, foreignBlocks)), "foreign constructor");
        IrEnumConstant receiver = (IrEnumConstant) ((IrCallInstruction) construct).arguments().getFirst();
        IrField field = raw.classes().stream().filter(c -> c.name().equals("Value")).findFirst().orElseThrow()
                .fields().stream().filter(f -> f.name().equals("index")).findFirst().orElseThrow();
        foreign.set(0, new IrFieldStoreInstruction(receiver, field, new IrConstant(IrType.I32, 99, first.sourceSpan()), first.sourceSpan()));
        foreignBlocks.set(0, new IrBasicBlock(first.label(), foreign, first.terminator(), first.sourceSpan()));
        requireUnfolded(replace(raw, leaf, copy(leaf, foreignBlocks)), "foreign field store");
        for (UnfreedMode mode : UnfreedMode.values()) {
            String safe = SOURCE.replace("return 42;", "Object x = new Object(); free x; return 42;");
            require(new CompilerPipeline(mode).compile(SourceFile.of("safe.iron", safe)).successful(), "safe " + mode);
            require(!new CompilerPipeline(mode).compile(SourceFile.of("unsafe.iron",
                    safe.replace("free x;", "free x; free x;"))).valid(), "unsafe accepted " + mode);
        }
    }

    static void nativeArtifacts() throws Exception {
        Path root = Path.of("integration-tests/target/initialized-enum-fields").toAbsolutePath();
        Files.createDirectories(root);
        Path source = root.resolve("Main.iron");
        Files.writeString(source, SOURCE);
        Path classes = root.resolve("classes");
        cli(source.toString(), "--unfreed=error", "-d", classes.toString());
        Path archive = root.resolve("program.ironjar");
        IronJar.create(archive, List.of(classes));
        for (String input : List.of("source", "class", "archive")) {
            for (int level : List.of(0, 3)) {
                Path binary = root.resolve(input + "-O" + level);
                Path llvm = root.resolve(input + "-O" + level + ".ll");
                List<String> command = new ArrayList<>(List.of("--unfreed=error", "-O" + level,
                        "--emit-llvm", llvm.toString(), "-o", binary.toString()));
                if (input.equals("source")) {
                    var artifact = new CompilerPipeline(UnfreedMode.ERROR).compile(SourceFile.of(source.toString(), SOURCE));
                    require(artifact.successful(), artifact.diagnostics().toString());
                    Files.writeString(llvm, artifact.llvmIr().orElseThrow());
                    var linked = new ironwood.compiler.backend.NativeBackend().link(
                            ironwood.compiler.backend.LlvmToolchain.discover(null).toolchain().orElseThrow(), llvm, binary,
                            level == 0 ? ironwood.compiler.backend.OptimizationLevel.O0 : ironwood.compiler.backend.OptimizationLevel.O3);
                    require(linked.success(), linked.toString());
                } else {
                    command.addAll(List.of("--link", "-cp", (input.equals("class") ? classes : archive).toString(), "--main-class", "Main"));
                    cli(command.toArray(String[]::new));
                }
                String emitted = Files.readString(llvm);
                require(emitted.contains("add i32 7, 0") && emitted.contains("add i64 43, 0"), "artifact lost payload facts " + input);
                execute(binary, 42);
            }
        }
        Path fixture = Path.of("integration-tests/cases/initialized_enum_fields.iron");
        Path fixtureClasses = root.resolve("adversarial-classes");
        cli(fixture.toString(), "--unfreed=error", "-d", fixtureClasses.toString());
        for (int level : List.of(0, 3)) {
            Path binary = root.resolve("adversarial-O" + level);
            cli("--link", "-cp", fixtureClasses.toString(), "--main-class", "Main",
                    "--unfreed=error", "-O" + level, "-o", binary.toString());
            execute(binary, 42);
        }
    }

    private static void execute(Path binary, int exit) throws Exception {
        Process process = new ProcessBuilder(binary.toString()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        require(process.waitFor() == exit && output.isEmpty(), binary + ": " + output);
    }

    private static void cli(String... arguments) {
        var output = new ByteArrayOutputStream();
        try (var stream = new PrintStream(output)) {
            require(Main.run(arguments, stream, stream) == 0, output.toString());
        }
    }

    private static void requireUnfolded(IrProgram raw, String reason) {
        require(fast(InitializedTypeSpecializer.specialize(raw), "leaf").flatMap(InitializedEnumFieldTests::ops)
                .anyMatch(InitializedEnumFieldTests::accessor), reason + " was ignored");
    }

    private static boolean accessor(IrInstruction instruction) {
        return instruction instanceof IrCallInstruction call
                && (call.targetLinkageName().equals("ironwood.Value.index")
                    || call.targetLinkageName().equals("ironwood.Value.code"));
    }

    private static IrProgram analyze(String source) {
        var artifact = new CompilerPipeline().analyze(List.of(SourceFile.of("Main.iron", source)));
        require(artifact.valid(), artifact.diagnostics().toString());
        return artifact.program().orElseThrow();
    }

    private static Stream<IrFunction> fast(IrProgram program, String name) {
        List<IrFunction> result = program.functions().stream()
                .filter(f -> f.sourceName().equals(name) && f.linkageName().contains(".$initialized.")).toList();
        require(!result.isEmpty(), "missing initialized clone " + name);
        return result.stream();
    }

    private static Stream<IrInstruction> ops(IrFunction function) {
        return function.blocks().stream().flatMap(b -> Stream.concat(b.instructions().stream(),
                b.terminator() instanceof IrInvokeTerminator invoke ? Stream.of(invoke.call()) : Stream.empty()));
    }

    private static IrFunction copy(IrFunction f, List<IrBasicBlock> blocks) {
        return new IrFunction(f.ownerClass(), f.sourceName(), f.linkageName(), f.returnType(), f.parameters(),
                blocks, f.sourceSpan(), f.sourceFileName(), f.kind());
    }

    private static IrProgram replace(IrProgram p, IrFunction old, IrFunction replacement) {
        return new IrProgram(p.moduleName(), p.classes(), p.staticFields(), p.typeInitializations(), p.arrayTypes(),
                p.stringConstants(), p.dispatchSlots(), p.functions().stream().map(f -> f == old ? replacement : f).toList(),
                p.entryPoint().map(f -> f == old ? replacement : f), p.allocationFailure());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
