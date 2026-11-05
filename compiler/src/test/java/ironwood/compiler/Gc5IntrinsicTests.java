// SPDX-License-Identifier: MIT OR Apache-2.0
package ironwood.compiler;

import ironwood.compiler.ir.*;
import ironwood.compiler.backend.GcManagedHeapVerifier;
import ironwood.compiler.source.SourceFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

final class Gc5IntrinsicTests {
    static final String RESOLUTION = "GC5 canonical collection intrinsic validates reconstructed declarations";
    static final String EFFECTS = "GC5 source collection effects survive dispatch initialization defer and specialization";
    static final String REFUSAL = "GC5 incomplete native cutover refuses collection without a stub or mixed heap";
    static final String FINAL_LINK = "GC5 final link rejects every reachable legacy managed deallocation family";
    private static final String TYPE = "ironwood.lang.Memory";

    private Gc5IntrinsicTests() {}

    static void resolution() throws Exception {
        SourceFile canonical = SourceFile.read(Path.of("stdlib/src/main/ironwood/ironwood/lang/Memory.iron"));
        for (SourceFile source : reconstructed(canonical, TYPE)) {
            var artifact = new CompilerPipeline(UnfreedMode.OFF).analyze(List.of(source));
            check(artifact.valid(), artifact.diagnostics().toString());
            var program = artifact.program().orElseThrow();
            var function = program.functions().stream().filter(f -> f.ownerClass().equals(TYPE)
                    && f.sourceName().equals("collect")).findFirst().orElseThrow();
            check(function.blocks().stream().flatMap(b -> GcEffects.operations(b).stream())
                    .anyMatch(IrGcCollectInstruction.class::isInstance), "intrinsic became an empty stub");
            var effect = GcEffects.analyze(program).function(function.linkageName());
            check(effect.mayCollect() && !effect.unknown(), "intrinsic effect is not exact");
        }
        for (String replacement : List.of("private static void collect()", "public void collect()",
                "public static int collect()", "public static void collect(int ignored)",
                "public static void unknown()", "public static <T> void collect()")) {
            String bad = canonical.content().replace("public static void collect()", replacement);
            rejected(SourceFile.of("malformed-Memory.iron", bad), "collection intrinsic");
        }
        // A valid-looking body, an extra member and an unused declaration all
        // require authority. No declaration can silently execute its source stub.
        for (String bad : List.of(canonical.content() + "\n", canonical.content().replace("collect() {", "collect() { return;"),
                canonical.content().replace("private Memory()", "public Memory()"))) {
            SourceFile source = SourceFile.of("spoof-Memory.iron", bad);
            for (SourceFile loaded : reconstructed(source, TYPE)) rejected(loaded, "collection intrinsic");
        }
        var ordinary = new CompilerPipeline(UnfreedMode.OFF).analyze(List.of(SourceFile.of("user/Memory.iron", """
                package user;
                public class Memory { public static void collect() {} }
                """)));
        check(ordinary.valid(), ordinary.diagnostics().toString());
        check(ordinary.program().orElseThrow().functions().stream().flatMap(f -> f.blocks().stream())
                .flatMap(b -> GcEffects.operations(b).stream()).noneMatch(IrGcCollectInstruction.class::isInstance),
                "a noncanonical Memory name became intrinsic");
    }

    static void effects() {
        var artifact = new CompilerPipeline(UnfreedMode.OFF).analyze(List.of(SourceFile.of("gc5-effects.iron", """
                import ironwood.lang.Memory;
                interface Step { void step(); }
                class Collecting implements Step {
                    Collecting() { Memory.collect(); }
                    @Override public void step() { Memory.collect(); }
                }
                class Init {
                    static int value = initialize();
                    static int initialize() { Memory.collect(); return 7; }
                }
                class Box<T> {
                    T roundTrip(T value) { Memory.collect(); return value; }
                }
                class Calls {
                    static Object retained;
                    static void dispatch(Step step) { step.step(); }
                    static int initialize() { return Init.value; }
                    static Collecting construct() { return new Collecting(); }
                    static Object capture(Object value, Object next) {
                        defer save(value);
                        value = next;
                        defer Memory.collect();
                        return value;
                    }
                    static void save(Object value) { Memory.collect(); retained = value; }
                    static void exceptional() {
                        try { throw new RuntimeException(); } finally { Memory.collect(); }
                    }
                    static int specialized() { Box<int> box = new Box<int>(); return box.roundTrip(3); }
                    static int pure(int value) { return value + 1; }
                }
                """)));
        check(artifact.valid(), artifact.diagnostics().toString());
        IrProgram source = artifact.program().orElseThrow();
        var rooted = new IrProgram(source.moduleName(), source.classes(), source.staticFields(), source.typeInitializations(),
                source.arrayTypes(), source.stringConstants(), source.dispatchSlots(), source.functions(), source.entryPoint(),
                source.allocationFailure(), source.functions().stream().filter(f -> f.ownerClass().equals("Calls"))
                .map(f -> f.linkageName()).collect(java.util.stream.Collectors.toSet()));
        for (IrProgram program : List.of(source, NativeLinkPipeline.optimize(source), ClosedWorldPruner.prune(rooted))) {
            GcEffects effects = GcEffects.analyzeRuntimeOwners(program);
            for (String method : List.of("dispatch", "initialize", "construct", "capture", "save", "exceptional", "specialized")) {
                var function = program.functions().stream().filter(f -> f.ownerClass().equals("Calls")
                        && f.sourceName().equals(method)).findFirst().orElseThrow();
                check(effects.function(function.linkageName()).mayCollect(), "lost source collection route: " + method);
            }
            var pure = program.functions().stream().filter(f -> f.ownerClass().equals("Calls")
                    && f.sourceName().equals("pure")).findFirst().orElseThrow();
            check(!effects.function(pure.linkageName()).mayCollect(), "noncollecting source path became collecting");
            check(effects.initialization("Init").mayCollect(), "initializer collection lost");
            check(effects.function("absent.target").unknown() && effects.function("absent.target").mayCollect(),
                    "unknown effects ceased to be conservative");
            check(program.functions().stream().filter(f -> f.ownerClass().equals("Box") && f.linkageName().contains("$specialization$"))
                    .anyMatch(f -> effects.function(f.linkageName()).mayCollect()), "generic specialization lost collection");
        }
        var oldDestructor = new CompilerPipeline(UnfreedMode.OFF).analyze(List.of(SourceFile.of("Old.iron", """
                import ironwood.lang.Memory;
                class Old { destructor { Memory.collect(); } }
                """)));
        check(!oldDestructor.valid() && oldDestructor.diagnostics().stream()
                .anyMatch(d -> d.message().contains("exception may escape this destructor")),
                "collection preparation OOM was hidden from existing exceptional-path analysis");
    }

    static void refusal() {
        var artifact = new CompilerPipeline(UnfreedMode.OFF).compile(SourceFile.of("Main.iron", """
                import ironwood.lang.Memory;
                public class Main {
                    public static void main(String[] args) { Memory.collect(); }
                }
                """));
        check(!artifact.valid() && artifact.llvmIr().isEmpty(), "incomplete source cutover emitted native code");
        check(artifact.diagnostics().stream().anyMatch(d -> d.message().contains("integrated managed native generation")),
                artifact.diagnostics().toString());
    }

    static void finalLink() throws Exception {
        IrProgram clean = GcRootTests.closure();
        GcManagedHeapVerifier.verify(clean);
        NativeLinkPipeline.finish(clean);
        var span = clean.functions().getFirst().sourceSpan();
        var object = new IrNull(IrType.reference("ironwood.lang.Throwable"), span);
        for (IrInstruction legacy : List.of(new IrFreeInstruction(object, span), new IrRawDeallocateInstruction(object, span),
                new IrRollbackInstruction(object, span),
                new IrDestroyArrayElementsInstruction(new IrNull(IrType.array(object.type()), span), span),
                new IrReleaseOwnedToStringResultInstruction(object, object, span),
                new IrReleaseOwnedThrowableMessageInstruction(object, object, span),
                new IrThrowableTraceInstruction(java.util.Optional.empty(), IrThrowableTraceInstruction.Operation.RELEASE, List.of(object), span))) {
            var function = new IrFunction("Gc5", "legacy", "gc5_legacy", IrType.VOID, List.of(),
                    List.of(new IrBasicBlock("entry", List.of(new IrGcCollectInstruction(span), legacy),
                            new IrReturnTerminator(java.util.Optional.empty(), span), span)), span);
            var functions = new java.util.ArrayList<>(clean.functions());
            functions.add(function);
            var exports = new java.util.HashSet<>(clean.exportRoots());
            exports.add(function.linkageName());
            var mixed = new IrProgram(clean.moduleName(), clean.classes(), clean.staticFields(), clean.typeInitializations(),
                    clean.arrayTypes(), clean.stringConstants(), clean.dispatchSlots(), functions, clean.entryPoint(),
                    clean.allocationFailure(), exports);
            try {
                NativeLinkPipeline.finish(mixed);
                throw new AssertionError("final native link accepted " + legacy.getClass().getSimpleName());
            } catch (IllegalArgumentException expected) {
                check(expected.getMessage().contains("legacy managed deallocation"), expected.getMessage());
            }
        }
        for (boolean rollback : List.of(false, true)) {
            var classes = clean.classes().stream().map(type -> type.name().equals("Node")
                    ? new IrClass(type.name(), type.kind(), type.superclass(), type.interfaces(), type.fields(), type.typeId(),
                    type.typeMembership(), type.dispatchEntries(), rollback ? java.util.Optional.empty() : java.util.Optional.of("legacy"),
                    rollback ? java.util.Optional.of("legacy") : java.util.Optional.empty(),
                    type.toStringReturnsOwnedFresh(), type.localizedMessageReturnsOwnedFresh(), type.sourceSpan()) : type).toList();
            var stale = new IrProgram(clean.moduleName(), classes, clean.staticFields(), clean.typeInitializations(),
                    clean.arrayTypes(), clean.stringConstants(), clean.dispatchSlots(), clean.functions(), clean.entryPoint(),
                    clean.allocationFailure(), clean.exportRoots());
            try {
                NativeLinkPipeline.finish(stale);
                throw new AssertionError("legacy managed cleanup descriptor accepted");
            } catch (IllegalArgumentException expected) {
                check(expected.getMessage().contains("legacy managed destruction metadata"), expected.getMessage());
            }
        }
    }

    private static List<SourceFile> reconstructed(SourceFile source, String type) throws Exception {
        Files.createDirectories(Path.of("workspace"));
        Path directory = Files.createTempDirectory(Path.of("workspace"), "gc5-artifacts-");
        Path loose = directory.resolve("Memory.ironclass"), archive = directory.resolve("memory.ironjar");
        var parsed = SourceParser.parse(source);
        check(parsed.diagnostics().isEmpty(), parsed.diagnostics().toString());
        IronClass.write(loose, parsed.unit().orElseThrow(), type);
        IronJar.create(archive, List.of(loose));
        return List.of(source, IronClass.read(loose).source(type).orElseThrow(), IronJar.read(archive).source(type).orElseThrow());
    }

    private static void rejected(SourceFile source, String expected) {
        var artifact = new CompilerPipeline(UnfreedMode.OFF).analyze(List.of(source));
        check(!artifact.valid(), "unrecognized collection declaration accepted: " + source.path());
        check(artifact.diagnostics().stream().anyMatch(d -> d.message().contains(expected)
                        && d.source() != null && d.source().path().equals(source.path())), artifact.diagnostics().toString());
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
