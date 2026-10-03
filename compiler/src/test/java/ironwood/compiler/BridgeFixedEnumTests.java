// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.bridge.*;
import ironwood.compiler.ir.*;
import ironwood.compiler.source.SourceFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;

final class BridgeFixedEnumTests {
    static final String STRUCTURE = "Java Bridge private enum entries preserve generic roots and exact private ABI";
    static final String NATIVE = "Java Bridge private enum entries preserve cold null failure and public API behavior";
    private BridgeFixedEnumTests() {}

    private static final String SOURCE = """
            package fixedenum;
            public final class Engine {
                private static Engine saved;
                private static int attempts;
                public Engine() {}
                public Engine publish() { saved = this; return this; }
                public enum Side { RIGHT, LEFT; }
                public enum Bad {
                    RIGHT, LEFT;
                    private final int marker = failInit();
                    public int marker() { return marker; }
                }
                private static int failInit() { attempts++; throw new IllegalStateException("fixed init"); }
                public static int attempts() { return attempts; }
                public static long allocations() { return System.allocationCount(); }
                public long value(Side side, long a, long b) {
                    if (a < 0) throw new IllegalArgumentException("fixed target");
                    return side == null ? -1 : (side == Side.LEFT ? a : b);
                }
                public Engine identity(Side side, long a, long b) { return side == null ? null : this; }
                public void accept(Side side, long a, long b) { if (a < 0) throw new IllegalArgumentException("fixed target"); }
                public int broken(Bad side, long a, long b) { return side == null ? -1 : side.marker(); }
                public boolean booleanValue(Side side, boolean flag, long ignored) { return flag; }
                public Side echo(Side side, long a, long b) { return side; }
                public static long staticValue(Side side, long a, long b) { return a; }
                public int small(Side side) { return side == null ? 0 : 1; }
                public int $ironwood$fixedEnum() { return 13; }
            }
            """;

    static void structure() {
        var artifact = artifact(SOURCE);
        var proof = BridgeObjectAdmission.prove(artifact, List.of("fixedenum"));
        check(proof.contract().isPresent(), proof.reason());
        var admission = proof.contract().orElseThrow();
        var module = admission.entries();
        var fixed = module.entries().stream().filter(entry -> !entry.fixedEnums().isEmpty()).toList();
        check(fixed.size() == 10 && admission.roots().isEmpty(), "wrong fixed entry eligibility");
        check(module.primaryEntries().stream().map(BridgeEntryModule.Entry::root).toList().equals(admission.surface().roots().roots()),
                "public root parity lost");
        for (var entry : fixed) {
            check(entry.fixedEnums().keySet().equals(Set.of(1)) && Set.of(0, 1).contains(entry.fixedEnums().get(1)), "wrong token mapping");
            check(entry.function().parameters().stream().map(parameter -> parameter.value().type()).toList()
                    .equals(List.of(IrType.reference("fixedenum.Engine"),
                            entry.root().callable().parameters().get(2).equals(IrType.I1) ? IrType.I8 : IrType.I64,
                            IrType.I64, IrType.I64)), "fixed parameter retained in ABI");
            check(module.entrySymbols().contains(entry.function().linkageName())
                    && admission.program().exportRoots().contains(entry.function().linkageName()), "fixed export omitted from final proofs");
            check(ops(entry.function()).anyMatch(IrEnsureTypeInitializedInstruction.class::isInstance), "cold active use removed");
            check(ops(entry.function()).filter(IrStaticFieldLoadInstruction.class::isInstance)
                    .map(IrStaticFieldLoadInstruction.class::cast).anyMatch(load -> load.field().name().equals(
                            entry.fixedEnums().get(1) == 0 ? "LEFT" : "RIGHT")), "fixed field disagrees with token");
            check(entry.function().blocks().stream().noneMatch(block -> block.terminator() instanceof IrSwitchTerminator),
                    "fixed conversion retains generic token switch");
        }
        var generation = BridgeGeneration.createObjects("fixed.jar", artifact, admission, "test", "1".repeat(64), "2".repeat(64));
        var java = BridgePermanentJavaSources.generate(artifact, admission, generation);
        var declarations = java.declarations();
        check(declarations.bindings().stream().filter(binding -> !binding.fixedEnums().isEmpty()).count() == 10, "missing private binding");
        check(declarations.nativeDeclarations().size() == declarations.nativeDeclarations().stream().distinct().count(), "duplicate conversion registration");
        String source = declarations.sources().get("fixedenum/Engine.java");
        check(source.contains("side != null && side == fixedenum.Engine.Side.LEFT"), "null guard must precede Java enum active use");
        var nativeCode = BridgePermanentNativeSources.generate(artifact, admission, generation, java);
        check(nativeCode.adapters().size() == declarations.nativeDeclarations().size(), "native inventory differs from declarations");
        var bindings = new ArrayList<>(declarations.bindings());
        int index = IntStream.range(0, bindings.size()).filter(i -> !bindings.get(i).fixedEnums().isEmpty()).findFirst().orElseThrow();
        var binding = bindings.get(index);
        bindings.set(index, new BridgeJavaSources.Binding(binding.binaryName(), binding.nativeName(), binding.descriptor(),
                binding.method(), binding.entrySymbol(), binding.permanentConversion(), binding.enumTokenParameters(), Map.of(1, 99)));
        var stale = new BridgeJavaSources(declarations.sources(), bindings, declarations.generatedTypes(), declarations.ensureMethod(),
                declarations.facadeRegistrations(), declarations.rootDestructions());
        try {
            BridgePermanentNativeSources.generate(artifact, admission, generation,
                    new BridgePermanentJavaSources.Sources(stale, java.facades(), java.enums()));
            throw new AssertionError("forged fixed token admitted");
        } catch (IllegalArgumentException expected) { check(expected.getMessage().contains("exact final admission"), expected.getMessage()); }
        for (var mode : UnfreedMode.values()) {
            String unsafe = SOURCE.replace("return side == null ? -1 : (side == Side.LEFT ? a : b);",
                    "if (side == Side.RIGHT) { Engine doomed = new Engine(); free doomed; } return a;");
            var analyzed = new CompilerPipeline(mode).analyzeForBridge(List.of(SourceFile.of("Engine.iron", unsafe)));
            check(BridgeObjectAdmission.prove(analyzed, List.of("fixedenum")).status() != BridgeProof.Status.PROVED,
                    "fixed variant hid generic reclaim branch in " + mode);
        }
    }

    static void nativeBehavior() throws Exception {
        Path base = Path.of("workspace/java-bridge/evidence/optimization/fixed-enum-tests").toAbsolutePath();
        Files.createDirectories(base);
        Path folder = Files.createTempDirectory(base, "run-");
        Path source = folder.resolve("Engine.iron"); Files.writeString(source, SOURCE);
        Path consumer = folder.resolve("FixedConsumer.java"); Files.writeString(consumer, CONSUMER);
        Path java = Path.of(System.getProperty("java.home"), "bin");
        for (int level : List.of(0, 3)) {
            Path jar = folder.resolve("fixed-O" + level + ".jar");
            BridgeProducerTests.command(folder, "produce-" + level, 0, new String[]{"--java-bridge", "--export", "fixedenum",
                    "--unfreed=off", "-O" + level, "-o", jar.toString(), source.toString()});
            Path classes = folder.resolve("consumer-" + level);
            BridgeEntryTests.run(folder, List.of(java.resolve("javac").toString(), "--release", "21", "-Xlint:all", "-Werror",
                    "-cp", jar.toString(), "-d", classes.toString(), consumer.toString()), "javac-" + level);
            for (boolean checked : List.of(false, true)) {
                var command = new ArrayList<>(List.of(java.resolve("java").toString()));
                if (checked) command.addAll(List.of("-Xcheck:jni", "-XX:-DoEscapeAnalysis"));
                command.addAll(List.of("-cp", jar + System.getProperty("path.separator") + classes, "FixedConsumer"));
                check(BridgeEntryTests.run(folder, command, "consumer-" + level + "-" + checked).equals("fixed-enum-ok\n"), "consumer mismatch");
            }
        }
        System.out.println("private enum entry evidence: " + folder);
    }

    private static CompilationArtifact artifact(String source) {
        var result = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(List.of(SourceFile.of("Engine.iron", source)));
        check(result.valid(), result.diagnostics().toString()); return result;
    }

    private static Stream<IrInstruction> ops(IrFunction function) {
        return function.blocks().stream().flatMap(block -> Stream.concat(block.instructions().stream(),
                block.terminator() instanceof IrInvokeTerminator invoke ? Stream.of(invoke.call()) : Stream.empty()));
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private static final String CONSUMER = """
            import fixedenum.Engine;
            import fixedenum.Engine.Side;
            import java.lang.reflect.Modifier;
            public final class FixedConsumer {
                public static void main(String[] args) {
                    Engine engine = new Engine().publish();
                    long before = Engine.allocations();
                    check(engine.value(null, 7, 11) == -1 && engine.identity(null, 7, 11) == null);
                    check(engine.broken(null, 7, 11) == -1 && Engine.attempts() == 0);
                    check(engine.echo(null, 7, 11) == null);
                    check(Engine.allocations() == before);
                    for (Side side : Side.values()) {
                        check(engine.value(side, 7, 11) == (side == Side.LEFT ? 7 : 11));
                        check(engine.identity(side, 7, 11) == engine);
                        check(engine.echo(side, 7, 11) == side);
                        check(engine.booleanValue(side, true, 0) && !engine.booleanValue(side, false, 0));
                        engine.accept(side, 7, 11);
                        try { engine.value(side, -1, 11); throw new AssertionError(); }
                        catch (IllegalArgumentException expected) { check(expected.getMessage().equals("fixed target")); }
                    }
                    for (Engine.Bad bad : Engine.Bad.values()) {
                        try { engine.broken(bad, 7, 11); throw new AssertionError(); }
                        catch (IllegalStateException expected) { check(expected.getMessage().equals("fixed init")); }
                        check(Engine.attempts() == 1);
                    }
                    check(engine.broken(null, 7, 11) == -1);
                    check(Engine.staticValue(Side.LEFT, 7, 11) == 7 && engine.small(Side.RIGHT) == 1);
                    check(engine.$ironwood$fixedEnum() == 13);
                    for (var method : Engine.class.getDeclaredMethods()) {
                        if (method.getName().startsWith("$ironwood$fixedEnum") && !method.getName().equals("$ironwood$fixedEnum")) {
                            check(Modifier.isPrivate(method.getModifiers()) && Modifier.isNative(method.getModifiers()));
                            check(method.getParameterCount() == 3);
                        }
                    }
                    System.out.println("fixed-enum-ok");
                }
                private static void check(boolean condition) { if (!condition) throw new AssertionError(); }
            }
            """;
}
