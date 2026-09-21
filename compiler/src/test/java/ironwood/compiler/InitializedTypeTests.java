// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.ir.*;
import ironwood.compiler.source.SourceFile;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

final class InitializedTypeTests {
    private InitializedTypeTests() {}

    private static final String SOURCE = """
            enum Flag { FIRST, SECOND; }
            class State { static Flag current = Flag.FIRST; }
            class Main {
                static int leaf(int depth) {
                    if (depth > 0) return leaf(depth - 1);
                    return Flag.FIRST == State.current && Flag.SECOND != State.current ? 1 : 0;
                }
                static int loop(int count) {
                    int sum = 0;
                    for (int i = 0; i < count; i++) sum += leaf(2) + leaf(1);
                    return sum;
                }
                public static int main(String[] args) { return loop(args.length); }
            }
            """;

    static void structure() throws Exception {
        IrProgram raw = analyze(SOURCE);
        IrProgram specialized = InitializedTypeSpecializer.specialize(raw);
        IrFunction original = named(raw, "loop");
        IrFunction guarded = named(specialized, "loop");
        require(guarded != original, "loop was not guarded");
        require(guarded.blocks().containsAll(original.blocks()), "fallback body changed");
        require(operations(guarded).filter(IrTypeInitializedInstruction.class::isInstance).count() == 2,
                "expected exactly Flag and State guards");
        List<IrFunction> clones = specialized.functions().stream()
                .filter(f -> f.linkageName().contains(".$initialized.")).toList();
        require(clones.size() == 1, "recursive leaf must have exactly one bounded clone");
        IrFunction clone = clones.getFirst();
        require(clone.traceCallableName().equals("Main.leaf"), "clone changed source trace identity");
        require(clone.sourceSpan().equals(named(raw, "leaf").sourceSpan()), "clone changed source span");
        require(operations(clone).noneMatch(IrEnsureTypeInitializedInstruction.class::isInstance), "clone retained proved ensures");
        require(operations(clone).filter(IrCallInstruction.class::isInstance).map(IrCallInstruction.class::cast)
                .allMatch(c -> c.targetLinkageName().equals(clone.linkageName())), "recursive clone repeated guard");
        require(operations(clone).anyMatch(i -> i instanceof IrReferenceConversionInstruction c
                && c.value() instanceof IrEnumConstant), "proven enum address not substituted");
        require(operations(clone).filter(IrStaticFieldLoadInstruction.class::isInstance)
                .map(IrStaticFieldLoadInstruction.class::cast).allMatch(i -> i.field().ownerClass().equals("State")),
                "immutable enum pointer loads remain");
        require(operations(clone).anyMatch(i -> i instanceof IrStaticFieldLoadInstruction load
                && load.field().name().equals("current")), "mutable enum-valued field was substituted");
        IrFunction initializer = raw.functions().stream().filter(f -> f.ownerClass().equals("Flag")
                && f.kind() == IrCallableKind.CLASS_INITIALIZER).findFirst().orElseThrow();
        IrStaticFieldStoreInstruction publication = operations(initializer)
                .filter(IrStaticFieldStoreInstruction.class::isInstance).map(IrStaticFieldStoreInstruction.class::cast)
                .filter(s -> s.field().name().equals("FIRST")).findFirst().orElseThrow();
        List<IrBasicBlock> changedBlocks = new ArrayList<>(initializer.blocks());
        IrBasicBlock publicationBlock = changedBlocks.stream().filter(b -> b.instructions().contains(publication))
                .findFirst().orElseThrow();
        List<IrInstruction> changedInstructions = new ArrayList<>(publicationBlock.instructions());
        changedInstructions.add(publication);
        changedBlocks.set(changedBlocks.indexOf(publicationBlock), new IrBasicBlock(publicationBlock.label(),
                changedInstructions, publicationBlock.terminator(), publicationBlock.sourceSpan()));
        IrFunction repeatedPublication = new IrFunction(initializer.ownerClass(), initializer.sourceName(), initializer.linkageName(),
                initializer.returnType(), initializer.parameters(), changedBlocks, initializer.sourceSpan(),
                initializer.sourceFileName(), initializer.kind());
        IrProgram uncertain = InitializedTypeSpecializer.specialize(replace(raw, initializer, repeatedPublication));
        require(uncertain.functions().stream().filter(f -> f.linkageName().contains(".$initialized."))
                .flatMap(InitializedTypeTests::operations).anyMatch(i -> i instanceof IrStaticFieldLoadInstruction load
                        && load.field().name().equals("FIRST")), "unproved publication identity was substituted");
        IrProgram pruned = ClosedWorldPruner.prune(specialized);
        require(pruned.functions().contains(clone), "direct clone was pruned");
        require(pruned.staticFields().stream().anyMatch(f -> f.initialValue() instanceof IrEnumConstant), "enum storage pruned");
        require(InitializedTypeSpecializer.specialize(specialized) == specialized, "pass is not idempotent");
        var phiInputs = guarded.blocks().stream().flatMap(b -> b.instructions().stream())
                .filter(IrPhiInstruction.class::isInstance).map(IrPhiInstruction.class::cast);
        List<String> labels = guarded.blocks().stream().map(IrBasicBlock::label).toList();
        phiInputs.forEach(p -> p.incoming().forEach(i -> require(labels.contains(i.predecessor()), "orphan phi predecessor")));

        // Oversized bodies must retain the exact program, irrespective of naming.
        List<IrInstruction> many = new ArrayList<>(original.blocks().getFirst().instructions());
        for (int i = 0; i < 1300; i++) many.add(new IrEnsureTypeInitializedInstruction("Flag", original.sourceSpan()));
        List<IrBasicBlock> blocks = new ArrayList<>(original.blocks());
        IrBasicBlock first = blocks.getFirst();
        blocks.set(0, new IrBasicBlock(first.label(), many, first.terminator(), first.sourceSpan()));
        IrFunction huge = new IrFunction(original.ownerClass(), original.sourceName(), original.linkageName(), original.returnType(),
                original.parameters(), blocks, original.sourceSpan(), original.sourceFileName(), original.kind());
        IrProgram oversized = replace(raw, original, huge);
        require(InitializedTypeSpecializer.specialize(oversized) == oversized, "oversized root was cloned");

        // An ordinary successful ensure outside a loop never authorizes state-2 facts.
        IrProgram straight = analyze(SOURCE.replace("for (int i = 0; i < count; i++) sum += leaf(2) + leaf(1);",
                "sum = leaf(2) + leaf(1);"));
        require(InitializedTypeSpecializer.specialize(straight) == straight, "normal ensure implied complete state");

        // The executable fixtures must actually exercise the new transform.
        for (String fixture : List.of("initialized_specialization", "initialized_specialization_trace")) {
            IrProgram fixtureProgram = InitializedTypeSpecializer.specialize(analyze(Files.readString(
                    Path.of("integration-tests/cases/" + fixture + ".iron"))));
            List<String> expected = fixture.endsWith("_trace") ? List.of("loop")
                    : List.of("lazy", "enumValue", "observe", "broken", "ordered", "warmed");
            for (String name : expected) {
                require(fixtureProgram.functions().stream().anyMatch(f -> f.sourceName().equals(name)
                        && operations(f).anyMatch(IrTypeInitializedInstruction.class::isInstance)),
                        fixture + " does not guard " + name);
            }
        }
    }

    static void safety() {
        String safe = SOURCE.replace("return loop(args.length);", "Object value = new Object(); free value; return loop(args.length);");
        String unsafe = safe.replace("free value;", "Object alias = value; free value; alias.toString();");
        for (UnfreedMode mode : UnfreedMode.values()) {
            var positive = new CompilerPipeline(mode).compile(SourceFile.of("test/Main.iron", safe));
            require(positive.successful(), mode + " safe acceptance: " + positive.diagnostics());
            var negative = new CompilerPipeline(mode).compile(SourceFile.of("test/Main.iron", unsafe));
            require(!negative.valid(), mode + " unsafe free accepted");
            require(negative.program().isEmpty(), "unsafe program reached specialization");
        }
    }

    static void artifacts() throws Exception {
        Path root = Path.of("integration-tests/target/initialized-specialization-artifacts").toAbsolutePath();
        Files.createDirectories(root.resolve("sources/library"));
        Path library = root.resolve("sources/library/Work.iron");
        Files.writeString(library, """
                // SPDX-License-Identifier: MIT OR Apache-2.0
                package library;
                enum Flag { FIRST, SECOND; }
                public class Work {
                    private static int leaf() { return Flag.FIRST != Flag.SECOND ? 1 : 0; }
                    public static int run(int count) {
                        int sum = 0;
                        for (int i = 0; i < count; i++) sum += leaf() + leaf();
                        return sum;
                    }
                }
                """);
        Path application = root.resolve("Main.iron");
        Files.writeString(application, """
                // SPDX-License-Identifier: MIT OR Apache-2.0
                import library.Work;
                class Main {
                    public static int main(String[] args) {
                        if (Work.run(0) != 0 || Work.run(1) != 2) return 1;
                        return Work.run(21);
                    }
                }
                """);
        cli("--unfreed=error", "--source-path", root.resolve("sources").toString(), application.toString(),
                "-d", root.resolve("source-classes").toString());
        linkAndRun(root, "source", root.resolve("source-classes").toString());
        cli("--unfreed=error", library.toString(), "-d", root.resolve("library-classes").toString());
        Path archive = root.resolve("work.ironjar");
        IronJar.create(archive, List.of(root.resolve("library-classes")));
        for (Path dependency : List.of(root.resolve("library-classes"), archive)) {
            String name = dependency.equals(archive) ? "archive" : "class";
            Path classes = root.resolve(name + "-classes");
            cli("--unfreed=error", "--source-path", root.resolve("absent").toString(),
                    "-cp", dependency.toString(), application.toString(), "-d", classes.toString());
            linkAndRun(root, name, classes + java.io.File.pathSeparator + dependency);
        }
    }

    private static void linkAndRun(Path root, String name, String classPath) throws Exception {
        Path executable = root.resolve(name + "-program");
        Path llvm = root.resolve(name + ".ll");
        cli("--link", "--unfreed=error", "-cp", classPath, "--main-class", "Main", "-O3",
                "--emit-llvm", llvm.toString(), "-o", executable.toString());
        require(Files.readString(llvm).contains(".leaf.$initialized."), "artifact lost guardless specialization");
        Process process = new ProcessBuilder(executable.toString()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        require(process.waitFor() == 42 && output.isEmpty(), name + ": " + output);
    }

    private static void cli(String... arguments) {
        var errors = new ByteArrayOutputStream();
        int result = Main.run(arguments, new PrintStream(new ByteArrayOutputStream()), new PrintStream(errors));
        require(result == 0, String.join(" ", arguments) + ": " + errors.toString(StandardCharsets.UTF_8));
    }

    private static IrProgram analyze(String source) {
        var artifact = new CompilerPipeline(UnfreedMode.OFF).analyze(List.of(SourceFile.of("test/Main.iron", source)));
        require(artifact.valid(), "analysis failed: " + artifact.diagnostics());
        return artifact.program().orElseThrow();
    }

    private static IrProgram replace(IrProgram p, IrFunction old, IrFunction replacement) {
        return new IrProgram(p.moduleName(), p.classes(), p.staticFields(), p.typeInitializations(), p.arrayTypes(), p.stringConstants(),
                p.dispatchSlots(), p.functions().stream().map(f -> f == old ? replacement : f).toList(),
                p.entryPoint().map(f -> f == old ? replacement : f), p.allocationFailure());
    }

    private static IrFunction named(IrProgram program, String name) {
        return program.functions().stream().filter(f -> f.ownerClass().equals("Main") && f.sourceName().equals(name)
                && !f.linkageName().contains(".$initialized.")).findFirst().orElseThrow();
    }

    private static Stream<IrInstruction> operations(IrFunction function) {
        return function.blocks().stream().flatMap(b -> Stream.concat(b.instructions().stream(),
                b.terminator() instanceof IrInvokeTerminator i ? Stream.of(i.call()) : Stream.empty()));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
