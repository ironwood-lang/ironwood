// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.backend.*;
import ironwood.compiler.bridge.*;
import ironwood.compiler.source.SourceFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

final class BridgeRootFacadeNativeTests {
    static final String NAME = "Java Bridge generated root facades preserve ownership identity and protected native access";
    static final String SOURCE = BridgeRootJavaSourceTests.SOURCE
            .replace("public Root() {}", """
                    private static int entered;
                    private static int destroyed;
                    public Root() {}
                    public Root(boolean fail) { if (fail) throw null; }
                    public static int entered() { return entered; }
                    public static int destroyed() { return destroyed; }
                    public static long allocations() { return System.allocationCount(); }
                    public static long live() { return System.liveAllocationCount(); }
                    public int fastValue() { return 17; }
                    public Root absent() { return null; }
                    public Root argument(Root other) { return other; }
                    public void fail() { entered++; throw new IllegalStateException("producer failure"); }
                    """)
            .replace("destructor { free child; free view; }", "destructor { destroyed++; free child; free view; }")
            .replace("public int value() { return 17; }", "public int value() { entered++; return 17; }");
    private BridgeRootFacadeNativeTests() {}

    static void facades() throws Exception {
        var artifact = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(List.of(SourceFile.of("Root.iron", SOURCE)));
        check(artifact.valid(), artifact.diagnostics().toString());
        var proof = BridgeObjectAdmission.prove(artifact, List.of("rootjava")); check(proof.contract().isPresent(), proof.reason());
        var admission = proof.contract().orElseThrow(); var producer = BridgeProducerInputs.discover();
        var generation = BridgeGeneration.createObjects("root-facades.jar", artifact, admission,
                producer.compilerVersion(), producer.compilerIdentity(), producer.runtimeIdentity());
        var projected = BridgePermanentJavaSources.generateRoots(artifact, admission, generation);
        var declarations = projected.declarations(); var adapters = BridgePermanentNativeSources.generateRoots(artifact, admission, generation, projected);
        check(adapters.rooted() && adapters.matches(declarations, generation), "lost root native generation identity");
        var missing = new BridgeJavaSources(declarations.sources(), declarations.bindings(), declarations.generatedTypes(), declarations.ensureMethod(), declarations.facadeRegistrations());
        try { BridgePermanentNativeSources.generateRoots(artifact, admission, generation,
                new BridgePermanentJavaSources.Sources(missing, projected.facades(), projected.enums())); throw new AssertionError("missing destruction helpers accepted"); }
        catch (IllegalArgumentException expected) { check(expected.getMessage().contains("exact final admission"), expected.getMessage()); }
        String llvmText = new LlvmEmitter().emit(admission.program());
        Path base = Path.of("workspace/java-bridge/evidence/p3c/root-facades").toAbsolutePath(); Files.createDirectories(base);
        Path directory = Files.createTempDirectory(base, "run-"), llvm = directory.resolve("program.ll");
        Files.writeString(llvm, llvmText); Files.writeString(directory.resolve("Root.iron"), SOURCE);
        Files.writeString(directory.resolve("identity.txt"), "generation=" + generation.identity() + "\nllvm=" + digest(llvmText)
                + "\nadapters=" + digest(adapters.source()) + "\ncompiler=" + producer.compilerIdentity() + "\nruntime=" + producer.runtimeIdentity() + "\n");
        var discovery = LlvmToolchain.discover(null); check(discovery.successful(), discovery.error()); var toolchain = discovery.toolchain().orElseThrow();
        var javaHome = Path.of(System.getProperty("java.home"));
        String helper = generation.supportPackage() + ".TestIdentity";
        for (var level : List.of(OptimizationLevel.O0, OptimizationLevel.O3)) {
            Path folder = directory.resolve(level.toString()); Files.createDirectories(folder);
            var build = generation.nativeBuild("macos-arm64", Map.of("fixture", "generated-root-facades", "llvm", digest(llvmText),
                    "adapters", digest(adapters.source()), "optimization", level.toString()));
            String source = adapters.source() + BridgeBootstrapSources.generate(generation, build, declarations, adapters);
            Path jar = BridgeGeneratedJarTests.build(folder, llvm, toolchain, level, generation, build, declarations, source,
                    Map.of(helper.replace('.', '/') + ".java", "package " + generation.supportPackage()
                            + "; public final class TestIdentity { public static boolean refusal(Throwable value) { return value.getClass() == BridgeLifetimeException.class; } }"));
            Path consumer = folder.resolve("RootConsumer.java"); Files.writeString(consumer, "import " + helper + ";\n" + CONSUMER);
            BridgeEntryTests.run(folder, List.of(javaHome.resolve("bin/javac").toString(), "--release", "21", "-Xlint:all", "-Werror", "-cp", jar.toString(), consumer.toString()), "consumer-javac");
            String output = BridgeEntryTests.run(folder, List.of(javaHome.resolve("bin/java").toString(), "-Xcheck:jni", "-XX:-DoEscapeAnalysis", "-Xmx64m", "-cp",
                    jar + java.io.File.pathSeparator + folder, "RootConsumer"), "consumer");
            check(output.matches("root-scalar:100000:0:0:[0-9]+\\nroot-alias:100000:0:0:[0-9]+\\nroot-facades-ok\\n"), output);
            BridgeEntryTests.run(folder, List.of(toolchain.clang().resolveSibling("llvm-objdump").toString(), "--disassemble", folder.resolve("libbridge.dylib").toString()), "disassembly");
        }
        System.out.println("generated root facade evidence: " + directory);
    }
    private static String digest(String value) { return BridgeGeneration.bytesDigest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }

    private static final String CONSUMER = """
            import rootjava.Root;
            import java.lang.ref.WeakReference;
            public final class RootConsumer {
                private static volatile Object sink;
                private static void check(boolean value) { if (!value) throw new AssertionError(); }
                private static void refusal(Runnable action) {
                    int entered = Root.entered(), destroyed = Root.destroyed();
                    try { action.run(); throw new AssertionError("missing lifetime refusal"); }
                    catch (IllegalStateException expected) { check(TestIdentity.refusal(expected)); }
                    check(Root.entered() == entered && Root.destroyed() == destroyed);
                }
                public static void main(String[] args) throws Exception {
                    long baseline = Root.live();
                    check(Root.fresh(true) == null && Root.pick(null, null, true) == null && Root.live() == baseline);
                    Root first = new Root(), second = Root.fresh(false);
                    check(first.self() == first && first.argument(second) == second && first.argument(null) == null && first.absent() == null);
                    check(Root.pick(first, second, true) == first && Root.pick(first, second, false) == second && Root.pick(null, second, true) == null);
                    Root.Child child = first.child(); Root.View view = first.view();
                    check(first.child() == child && child.self() == child && child.value() == 19 && view.value() == 23);
                    refusal(child::free); check(first.value() == 17);
                    Root.Child owned = new Root.Child(); check(owned.self() == owned); owned.free(); owned.free(); refusal(owned::value);
                    Root fromEnum = Root.Mode.ONLY.create(), fromStaticEnum = Root.Mode.fresh(); fromEnum.free(); fromStaticEnum.free();
                    int entered = Root.entered();
                    try { first.fail(); throw new AssertionError("missing producer failure"); }
                    catch (IllegalStateException expected) { check(!TestIdentity.refusal(expected) && expected.getMessage().equals("producer failure")); }
                    check(Root.entered() == entered + 1); baseline++;
                    WeakReference<Root.View> weak = ephemeral(first);
                    // Drop the earlier wrapper before requiring weak collection of its identity entry.
                    view = null;
                    for (int i = 0; i < 200 && weak.get() != null; i++) { System.gc(); Thread.sleep(10); }
                    check(weak.get() == null && first.view().value() == 23);
                    bench(first);
                    int hash = first.hashCode(); String text = first.toString(); int destroyed = Root.destroyed();
                    first.free(); first.free(); check(Root.destroyed() == destroyed + 1);
                    refusal(first::value); refusal(child::value); refusal(child::free);
                    refusal(() -> Root.pick(first, second, false)); refusal(() -> Root.pick(second, first, true));
                    check(first.hashCode() == hash && first.toString().equals(text) && first.equals(first) && !first.equals(second));
                    check(second.value() == 17 && Root.pick(null, second, false) == second); second.free();
                    check(Root.live() == baseline);
                    long allocations = Root.allocations(); destroyed = Root.destroyed();
                    try { new Root(true); throw new AssertionError("missing constructor failure"); }
                    catch (NullPointerException expected) { check(Root.allocations() == allocations + 4 && Root.live() == baseline + 1); }
                    check(Root.destroyed() == destroyed); baseline++;
                    for (int i = 0; i < 1000; i++) { Root value = new Root(); check(value.self() == value); value.free(); }
                    check(Root.live() == baseline);
                    Root.Catalog permanent = new Root.Catalog(); check(permanent.publish() == permanent);
                    System.out.println("root-facades-ok");
                }
                private static WeakReference<Root.View> ephemeral(Root root) { return new WeakReference<>(root.view()); }
                private static void bench(Root root) {
                    for (int i = 0; i < 200000; i++) { check(root.fastValue() == 17); sink = root.self(); }
                    var counter = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
                    check(counter.isThreadAllocatedMemorySupported()); counter.setThreadAllocatedMemoryEnabled(true);
                    long thread = Thread.currentThread().threadId(); for (int i = 0; i < 10000; i++) counter.getThreadAllocatedBytes(thread);
                    long nativeBefore = Root.allocations(), before = counter.getThreadAllocatedBytes(thread), start = System.nanoTime();
                    int sum = 0; for (int i = 0; i < 100000; i++) sum += root.fastValue();
                    long elapsed = System.nanoTime() - start, bytes = counter.getThreadAllocatedBytes(thread) - before, nativeBytes = Root.allocations() - nativeBefore;
                    check(sum == 1700000 && bytes == 0 && nativeBytes == 0); System.out.println("root-scalar:100000:" + bytes + ":" + nativeBytes + ":" + elapsed);
                    nativeBefore = Root.allocations(); before = counter.getThreadAllocatedBytes(thread); start = System.nanoTime();
                    for (int i = 0; i < 100000; i++) { Object value = root.self(); check(value == root); sink = value; }
                    elapsed = System.nanoTime() - start; bytes = counter.getThreadAllocatedBytes(thread) - before; nativeBytes = Root.allocations() - nativeBefore;
                    check(bytes == 0 && nativeBytes == 0); System.out.println("root-alias:100000:" + bytes + ":" + nativeBytes + ":" + elapsed);
                }
            }
            """;
}
