// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.backend.*;
import ironwood.compiler.bridge.*;
import ironwood.compiler.source.SourceFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class BridgePermanentFacadeNativeTests {
    static final String NAME = "Java Bridge generated permanent facades preserve native identity and protected conversion";
    static final String SOURCE = """
            package permanentnative;
            public final class Box {
                private static Box saved;
                private static Child child;
                private final int value;
                private final String label;
                public Box(int value, String text) { this.value = value; label = new String(text); }
                destructor { free label; }
                public Box publish() { saved = this; return this; }
                public static Box recall() { return saved; }
                public Box self() { return this; }
                public Box pick(Box value) { return value; }
                public int number() { return value; }
                public String text() { return label; }
                public String copy(String input) { return new String(input); }
                public void fail() { throw new IllegalStateException("native permanent failure"); }
                public static int recover() { return 23; }
                public static long allocations() { return System.allocationCount(); }
                public static long live() { return System.liveAllocationCount(); }
                public static Child cold() { if (child == null) child = new Child(31); return child; }
                public static final class Child {
                    private final int value;
                    public Child(int value) { this.value = value; }
                    public int number() { return value; }
                }
            }
            """;

    private BridgePermanentFacadeNativeTests() {}

    static void facades() throws Exception {
        BridgeGeneratedJarTests.target();
        var artifact = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(List.of(SourceFile.of("Box.iron", SOURCE)));
        check(artifact.valid(), artifact.diagnostics().toString());
        var proof = BridgeObjectAdmission.prove(artifact, List.of("permanentnative"));
        check(proof.contract().isPresent(), proof.reason());
        var admission = proof.contract().orElseThrow();
        check(admission.roots().isEmpty(), "permanent fixture acquired root bookkeeping");
        var producer = BridgeProducerInputs.discover();
        var generation = BridgeGeneration.createObjects("permanent.jar", artifact, admission,
                producer.compilerVersion(), producer.compilerIdentity(), producer.runtimeIdentity());
        var projected = BridgePermanentJavaSources.generate(artifact, admission, generation);
        var declarations = projected.declarations();
        var adapters = BridgePermanentNativeSources.generate(artifact, admission, generation, projected);
        check(adapters.matches(declarations, generation), "native generation binding lost");
        var tampered = new BridgeJavaSources(declarations.sources(), declarations.bindings(), declarations.generatedTypes(),
                declarations.ensureMethod(), List.of());
        try {
            BridgePermanentNativeSources.generate(artifact, admission, generation,
                    new BridgePermanentJavaSources.Sources(tampered, projected.facades()));
            throw new AssertionError("missing constructor cache registration admitted");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains("exact final admission"), expected.getMessage());
        }
        String llvmText = new LlvmEmitter().emit(admission.program());
        Path base = Path.of("workspace/java-bridge/evidence/p3b/permanent-facades").toAbsolutePath();
        Files.createDirectories(base);
        Path directory = Files.createTempDirectory(base, "run-");
        Path llvm = directory.resolve("program.ll"); Files.writeString(llvm, llvmText);
        Files.writeString(directory.resolve("Box.iron"), SOURCE);
        Files.writeString(directory.resolve("proof.txt"), "generation=" + generation.identity() + "\nllvm=" + digest(llvmText)
                + "\nadapters=" + digest(adapters.source()) + "\ncompiler=" + producer.compilerIdentity()
                + "\nruntime=" + producer.runtimeIdentity() + "\npermanent=" + admission.lifetime().references().keySet()
                + "\nfinal-exports=" + admission.program().exportRoots().stream().sorted().toList() + "\n");
        var discovery = LlvmToolchain.discover(null); check(discovery.successful(), discovery.error());
        var toolchain = discovery.toolchain().orElseThrow();
        var javaHome = Path.of(System.getProperty("java.home"));
        for (var level : List.of(OptimizationLevel.O0, OptimizationLevel.O3)) {
            Path folder = directory.resolve(level.toString()); Files.createDirectories(folder);
            var build = generation.nativeBuild(BridgeGeneratedJarTests.target(), Map.of("fixture", "generated-permanent-facades", "llvm", digest(llvmText),
                    "adapters", digest(adapters.source()), "optimization", level.toString()));
            String source = adapters.source() + BridgeBootstrapSources.generate(generation, build, declarations, adapters);
            Path jar = BridgeGeneratedJarTests.build(folder, llvm, toolchain, level, generation, build, declarations, source, Map.of());
            Path consumer = folder.resolve("PermanentConsumer.java"); Files.writeString(consumer, CONSUMER);
            BridgeEntryTests.run(folder, List.of(javaHome.resolve("bin/javac").toString(), "--release", "21", "-Xlint:all", "-Werror", "-cp", jar.toString(), consumer.toString()), "consumer-javac");
            for (int budget : List.of(-1, 0, 1, 2)) {
                var run = new ArrayList<String>();
                if (budget >= 0) run.addAll(List.of("/usr/bin/env", "IRONWOOD_ALLOCATION_LIMIT=" + budget));
                run.addAll(List.of(javaHome.resolve("bin/java").toString(), "-Xcheck:jni", "-Xmx64m", "-cp", jar + java.io.File.pathSeparator + folder,
                        "PermanentConsumer", budget >= 0 ? "budget" : "normal"));
                String result = BridgeEntryTests.run(folder, run, "consumer-" + budget);
                check(!result.contains("WARNING") && !result.contains("FATAL") && result.contains(budget >= 0
                        ? "permanent-budget-ok\n" : "permanent-facades-ok\n"), result);
            }
            Path objdump = toolchain.clang().resolveSibling("llvm-objdump");
            BridgeEntryTests.run(folder, List.of(objdump.toString(), "--disassemble", folder.resolve(BridgeGeneratedJarTests.imageName()).toString()), "disassembly");
        }
        System.out.println("generated permanent facade evidence: " + directory);
    }

    private static String digest(String source) { return BridgeGeneration.bytesDigest(source.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }

    private static final String CONSUMER = """
            import permanentnative.Box;
            import java.lang.ref.WeakReference;
            import java.lang.management.ManagementFactory;
            public final class PermanentConsumer {
                private static volatile Object sink;
                public static void main(String[] args) throws Exception {
                    if (args[0].equals("budget")) {
                        check(Box.recover() == 23);
                        try { new Box(17, "label"); throw new AssertionError("missing native allocation failure"); }
                        catch (OutOfMemoryError expected) { check(Box.live() == 0 && Box.recover() == 23); }
                        System.out.println("permanent-budget-ok"); return;
                    }
                    Box box = new Box(17, "A\\u0000\\uD800\\uDFFF");
                    check(box.self() == box && box.publish() == box && Box.recall() == box);
                    check(box.pick(box) == box && box.pick(null) == null && box.number() == 17);
                    long live = Box.live();
                    check(box.text().equals("A\\u0000\\uD800\\uDFFF") && box.copy(box.text()).equals(box.text()) && Box.live() == live);
                    Box.Child child = Box.cold(); check(child.number() == 31 && Box.cold() == child);
                    try { box.fail(); throw new AssertionError("missing native exception"); }
                    catch (IllegalStateException expected) { check(expected.getMessage().equals("native permanent failure")); }
                    check(box.number() == 17 && Box.recover() == 23);
                    var counter = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
                    check(counter.isThreadAllocatedMemorySupported()); counter.setThreadAllocatedMemoryEnabled(true);
                    long thread = Thread.currentThread().threadId();
                    for (int index = 0; index < 200000; index++) { check(box.number() == 17); sink = box.self(); }
                    for (int index = 0; index < 10000; index++) counter.getThreadAllocatedBytes(thread);
                    long nativeBefore = Box.allocations(), before = counter.getThreadAllocatedBytes(thread), start = System.nanoTime(), sum = 0;
                    for (int index = 0; index < 100000; index++) sum += box.number();
                    long elapsed = System.nanoTime() - start, bytes = counter.getThreadAllocatedBytes(thread) - before, allocated = Box.allocations() - nativeBefore;
                    System.out.println("permanent-scalar:100000:" + bytes + ":" + allocated + ":" + elapsed + ":" + sum);
                    check(bytes == 0 && allocated == 0 && sum == 1700000);
                    nativeBefore = Box.allocations(); before = counter.getThreadAllocatedBytes(thread); start = System.nanoTime();
                    for (int index = 0; index < 100000; index++) check(box.self() == box);
                    elapsed = System.nanoTime() - start; bytes = counter.getThreadAllocatedBytes(thread) - before; allocated = Box.allocations() - nativeBefore;
                    System.out.println("permanent-object:100000:" + bytes + ":" + allocated + ":" + elapsed);
                    check(bytes == 0 && allocated == 0);
                    sink = null; box = null;
                    WeakReference<Box> weak = collectible();
                    for (int attempt = 0; attempt < 200 && weak.get() != null; attempt++) { System.gc(); Thread.sleep(10); }
                    check(weak.get() == null);
                    nativeBefore = Box.allocations(); Box replacement = Box.recall();
                    check(replacement.number() == 17 && replacement.self() == replacement && Box.allocations() == nativeBefore);
                    System.out.println("permanent-facades-ok");
                }
                private static WeakReference<Box> collectible() { return new WeakReference<>(Box.recall()); }
                private static void check(boolean value) { if (!value) throw new AssertionError(); }
            }
            """;
}
