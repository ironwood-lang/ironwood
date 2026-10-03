// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.backend.*;
import ironwood.compiler.bridge.*;
import ironwood.compiler.source.SourceFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

final class BridgeRootRetentionNativeTests {
    static final String NAME = "Java Bridge generated retention commits exact root dependencies on every exit";
    static final String SOURCE = """
            package retaining;
            public final class Holder {
                private Item first;
                private Item second;
                private int[] storage;
                private static int entered;
                private static int destroyed;
                public Holder(Holder other, Item item, boolean fail) {
                    entered++; first = item;
                    if (other != null) helper(other, item);
                    storage = new int[2];
                    if (fail) throw null;
                }
                destructor { free storage; first = null; second = null; destroyed++; }
                public void set(Item item) { entered++; first = item; }
                public void two(Item item) { entered++; first = item; second = item; }
                public void keep(boolean change, Item item) { entered++; if (change) first = item; }
                public void clear() { entered++; first = null; second = null; }
                public void fail(Item item) { entered++; first = item; throw null; }
                public void both(Holder other, Item a, Item b) { entered++; first = a; other.first = b; }
                public void other(Holder other, Item item) { entered++; helper(other, item); }
                private static void helper(Holder holder, Item item) { holder.first = item; }
                public int value() { return first == null ? -1 : first.number; }
                public static int entered() { return entered; }
                public static int destroyed() { return destroyed; }
                public static long allocations() { return System.allocationCount(); }
                public static long live() { return System.liveAllocationCount(); }
                public static final class Item { private int number; public Item(int value) { number = value; } }
                public static final class Owner {
                    private final Item left = new Item(31);
                    private final Item right = new Item(37);
                    public Owner() {}
                    destructor { free left; free right; destroyed++; }
                    public Item left() { return left; }
                    public Item right() { return right; }
                }
            }
            """;
    private BridgeRootRetentionNativeTests() {}

    static void retention() throws Exception {
        verify(SOURCE, "root-retention", CONSUMER, "root-retention:100000:0:0:[0-9]+\\nroot-retention-ok\\n", false);
    }

    static void snapshots() throws Exception {
        String source = SOURCE.replace("private static int entered;", "private static int entered; private static int reads; private static int copies;")
                .replace("public static int entered()", "public static int reads() { return reads; } public static int copies() { return copies; } public static int entered()")
                .replace("public void fail(Item item) { entered++; first = item; throw null; }",
                        "public void fail(Item item, boolean badGetter) throws Problem { entered++; first = item; throw new Detail(badGetter); }")
                .replace("public static final class Item", """
                        public static class Problem extends Exception {
                            private Problem() { super("retention"); }
                            public int getCode() { reads++; return 73; }
                        }
                        public static final class Detail extends Problem {
                            private final boolean badGetter;
                            private Detail(boolean value) { badGetter = value; }
                            public String getCopy() { copies++; if (badGetter) throw null; return new String("after native cleanup"); }
                        }
                        public static final class Item
                        """);
        verify(source, "retention-snapshots", SNAPSHOT_CONSUMER, "retention-snapshot-ok:(normal|budget)\\n", true);
    }

    private static void verify(String input, String fixture, String consumerText, String expectedOutput, boolean snapshotBudget) throws Exception {
        var artifact = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(List.of(SourceFile.of("Holder.iron", input)));
        check(artifact.valid(), artifact.diagnostics().toString());
        var proof = BridgeObjectAdmission.prove(artifact, List.of("retaining")); check(proof.contract().isPresent(), proof.reason());
        var admission = proof.contract().orElseThrow(); var producer = BridgeProducerInputs.discover();
        var generation = BridgeGeneration.createObjects("retention.jar", artifact, admission,
                producer.compilerVersion(), producer.compilerIdentity(), producer.runtimeIdentity());
        var projected = BridgePermanentJavaSources.generateRoots(artifact, admission, generation);
        var declarations = projected.declarations(); var adapters = BridgePermanentNativeSources.generateRoots(artifact, admission, generation, projected);
        String llvmText = new LlvmEmitter().emit(admission.program());
        Path base = Path.of("workspace/java-bridge/evidence/p3d/" + fixture).toAbsolutePath(); Files.createDirectories(base);
        Path directory = Files.createTempDirectory(base, "run-"), llvm = directory.resolve("program.ll");
        Files.writeString(llvm, llvmText); Files.writeString(directory.resolve("Holder.iron"), input);
        Files.writeString(directory.resolve("identity.txt"), "generation=" + generation.identity() + "\nllvm=" + digest(llvmText)
                + "\nadapters=" + digest(adapters.source()) + "\ncompiler=" + producer.compilerIdentity() + "\nruntime=" + producer.runtimeIdentity() + "\n");
        var discovery = LlvmToolchain.discover(null); check(discovery.successful(), discovery.error()); var toolchain = discovery.toolchain().orElseThrow();
        var javaHome = Path.of(System.getProperty("java.home")); String helper = generation.supportPackage() + ".TestRetention";
        for (var level : List.of(OptimizationLevel.O0, OptimizationLevel.O3)) {
            Path folder = directory.resolve(level.toString()); Files.createDirectories(folder);
            var build = generation.nativeBuild(BridgeGeneratedJarTests.target(), Map.of("fixture", fixture, "llvm", digest(llvmText),
                    "adapters", digest(adapters.source()), "optimization", level.toString()));
            String source = adapters.source() + BridgeBootstrapSources.generate(generation, build, declarations, adapters);
            Path jar = BridgeGeneratedJarTests.build(folder, llvm, toolchain, level, generation, build, declarations, source,
                    Map.of(helper.replace('.', '/') + ".java", "package " + generation.supportPackage() + ";\n" + HELPER));
            Path consumer = folder.resolve("RetentionConsumer.java"); Files.writeString(consumer, "import " + helper + ";\n" + consumerText);
            BridgeEntryTests.run(folder, List.of(javaHome.resolve("bin/javac").toString(), "--release", "21", "-Xlint:all", "-Werror", "-cp", jar.toString(), consumer.toString()), "consumer-javac");
            for (String scenario : snapshotBudget ? List.of("normal", "budget") : List.of("normal")) {
                var command = new java.util.ArrayList<String>();
                if (scenario.equals("budget")) command.addAll(List.of("/usr/bin/env", "IRONWOOD_ALLOCATION_LIMIT=5"));
                command.addAll(List.of(javaHome.resolve("bin/java").toString(), "-Xcheck:jni", "-XX:-DoEscapeAnalysis", "-Xmx64m", "-cp",
                        jar + java.io.File.pathSeparator + folder, "RetentionConsumer", scenario));
                String output = BridgeEntryTests.run(folder, command, "consumer-" + scenario);
                check(output.matches(expectedOutput), output);
            }
            BridgeEntryTests.run(folder, List.of(toolchain.clang().resolveSibling("llvm-objdump").toString(), "--disassemble", folder.resolve(BridgeGeneratedJarTests.imageName()).toString()), "disassembly");
        }
        System.out.println("generated " + fixture + " evidence: " + directory);
    }

    private static final String HELPER = """
            public final class TestRetention {
                public static boolean refusal(Throwable value) { return value.getClass() == BridgeLifetimeException.class; }
                public static Object state(Object value) throws Exception {
                    for (var field : value.getClass().getDeclaredFields()) if (field.getType() == RootState.class) {
                        field.setAccessible(true); return field.get(value);
                    }
                    throw new AssertionError("no root state");
                }
                public static long count(Object value) throws Exception {
                    var field = RootState.class.getDeclaredField("incoming"); field.setAccessible(true); return field.getLong(state(value));
                }
                // Test-only bounded-counter simulation, never part of the generated public API.
                public static void setCount(Object value, long count) throws Exception {
                    var field = RootState.class.getDeclaredField("incoming"); field.setAccessible(true); field.setLong(state(value), count);
                }
            }
            """;
    private static final String CONSUMER = """
            import retaining.Holder;
            import java.lang.ref.WeakReference;
            public final class RetentionConsumer {
                private static void check(boolean value) { if (!value) throw new AssertionError(); }
                private static void count(Object value, long expected) throws Exception { check(TestRetention.count(value) == expected); }
                private static void refusal(Runnable action) {
                    int entered = Holder.entered(), destroyed = Holder.destroyed();
                    try { action.run(); throw new AssertionError("missing refusal"); }
                    catch (IllegalStateException expected) { check(TestRetention.refusal(expected)); }
                    check(Holder.entered() == entered && Holder.destroyed() == destroyed);
                }
                public static void main(String[] args) throws Exception {
                    long baseline = Holder.live();
                    Holder.Item a = new Holder.Item(11), b = new Holder.Item(29);
                    Holder h = new Holder(null, a, false); count(a, 1); refusal(a::free);
                    h.clear(); TestRetention.setCount(a, Long.MAX_VALUE - 1);
                    refusal(() -> h.two(a)); count(a, Long.MAX_VALUE - 1); check(h.value() == -1);
                    h.both(h, a, a); count(a, Long.MAX_VALUE); TestRetention.setCount(a, 1); h.clear(); count(a, 0);
                    Holder empty = new Holder(null, null, false); TestRetention.setCount(a, Long.MAX_VALUE - 1);
                    refusal(() -> h.both(empty, a, a)); count(a, Long.MAX_VALUE - 1);
                    TestRetention.setCount(a, 0); empty.free();
                    h.clear(); h.both(h, a, b); count(a, 0); count(b, 1); check(h.value() == 29); h.set(a);
                    h.two(a); count(a, 2); h.set(b); count(a, 1); count(b, 1);
                    h.both(h, a, b); count(a, 1); count(b, 1); check(h.value() == 29);
                    h.two(b); count(a, 0); count(b, 2);
                    try { h.fail(a); throw new AssertionError(); } catch (NullPointerException expected) { }
                    count(a, 1); count(b, 1); refusal(a::free);
                    Holder other = new Holder(null, a, false); count(a, 2);
                    h.other(other, b); count(a, 1); count(b, 2);
                    try { new Holder(other, a, true); throw new AssertionError(); } catch (NullPointerException expected) { }
                    count(a, 2); count(b, 1); other.free(); count(a, 1);
                    try { h.other(null, a); throw new AssertionError(); } catch (NullPointerException expected) { }
                    count(a, 1); count(b, 1); h.clear(); count(a, 0); count(b, 0);
                    Holder.Owner owner = new Holder.Owner(); Holder.Item child = owner.left();
                    h.two(child); count(owner, 2); refusal(owner::free);
                    h.keep(false, b); count(owner, 2); count(b, 0); check(h.value() == 31);
                    h.set(owner.right()); count(owner, 2); check(h.value() == 37);
                    WeakReference<Holder.Item> weak = new WeakReference<>(child); child = null;
                    for (int i = 0; i < 200 && weak.get() != null; i++) { System.gc(); Thread.sleep(10); }
                    check(weak.get() == null); count(owner, 2); refusal(owner::free);
                    h.set(b); count(owner, 1); count(b, 1); h.free(); count(owner, 0); count(b, 0);
                    owner.free(); owner.free(); a.free(); b.free();
                    // Three thrown NPE objects retain native ownership; roots and rollback storage are reclaimed.
                    check(Holder.live() == baseline + 3);
                    Holder.Item benchA = new Holder.Item(3), benchB = new Holder.Item(5); Holder benchHolder = new Holder(null, benchA, false);
                    bench(benchHolder, benchA, benchB); benchHolder.free(); benchA.free(); benchB.free(); check(Holder.live() == baseline + 3);
                    System.out.println("root-retention-ok");
                }
                private static void bench(Holder holder, Holder.Item a, Holder.Item b) {
                    var bean = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
                    bean.setThreadAllocatedMemoryEnabled(true); long thread = Thread.currentThread().threadId();
                    for (int i = 0; i < 20000; i++) { holder.two(a); holder.two(b); }
                    long nativeBefore = Holder.allocations(), javaBefore = bean.getThreadAllocatedBytes(thread), start = System.nanoTime();
                    for (int i = 0; i < 50000; i++) { holder.two(a); holder.two(b); }
                    long elapsed = System.nanoTime() - start, javaBytes = bean.getThreadAllocatedBytes(thread) - javaBefore;
                    long nativeCount = Holder.allocations() - nativeBefore;
                    check(javaBytes == 0 && nativeCount == 0);
                    System.out.println("root-retention:100000:" + javaBytes + ":" + nativeCount + ":" + elapsed);
                }
            }
            """;
    private static final String SNAPSHOT_CONSUMER = """
            import retaining.Holder;
            public final class RetentionConsumer {
                private static void check(boolean value) { if (!value) throw new AssertionError(); }
                private static void retained(Holder.Item value) {
                    int entered = Holder.entered(), destroyed = Holder.destroyed();
                    try { value.free(); throw new AssertionError(); }
                    catch (IllegalStateException expected) { check(TestRetention.refusal(expected)); }
                    check(Holder.entered() == entered && Holder.destroyed() == destroyed);
                }
                public static void main(String[] args) throws Exception {
                    long live = Holder.live();
                    Holder.Item a = new Holder.Item(11), b = new Holder.Item(29); Holder h = new Holder(null, a, false);
                    if (args[0].equals("budget")) {
                        try { h.fail(b, false); throw new AssertionError(); }
                        catch (OutOfMemoryError expected) { check(expected.getMessage().contains("extraction failed")); }
                        check(h.value() == 29 && TestRetention.count(a) == 0 && TestRetention.count(b) == 1);
                        retained(b); h.free(); a.free(); b.free();
                        check(Holder.live() == live + 1 && Holder.reads() == 1 && Holder.copies() == 1);
                        System.out.println("retention-snapshot-ok:budget"); return;
                    }
                    Holder.Problem saved;
                    try { h.fail(b, false); throw new AssertionError(); }
                    catch (Holder.Problem expected) { saved = expected; }
                    check(saved instanceof Holder.Detail && saved.getCode() == 73 && saved.getMessage().equals("retention"));
                    check(((Holder.Detail) saved).getCopy().equals("after native cleanup"));
                    check(h.value() == 29 && TestRetention.count(a) == 0 && TestRetention.count(b) == 1);
                    retained(b); h.free(); a.free(); b.free();
                    // Native holders/storage and the owned getter String are reclaimed; throwable ownership is independent.
                    check(Holder.live() == live + 1);
                    int reads = Holder.reads(), copies = Holder.copies(); Throwable[] failure = new Throwable[1];
                    Thread reader = new Thread(() -> {
                        try { check(saved.getCode() == 73 && ((Holder.Detail) saved).getCopy().equals("after native cleanup")); }
                        catch (Throwable error) { failure[0] = error; }
                    });
                    reader.start(); reader.join(); check(failure[0] == null && Holder.reads() == reads && Holder.copies() == copies);
                    Holder.Item c = new Holder.Item(41); Holder retry = new Holder(null, null, false);
                    try { retry.fail(c, true); throw new AssertionError(); }
                    catch (LinkageError expected) { check(expected.getMessage().contains("extraction failed")); }
                    check(retry.value() == 41 && TestRetention.count(c) == 1); retained(c); retry.free(); c.free();
                    check(Holder.live() == live + 3 && saved.getCode() == 73 && ((Holder.Detail) saved).getCopy().equals("after native cleanup"));
                    System.out.println("retention-snapshot-ok:normal");
                }
            }
            """;
    private static String digest(String value) { return BridgeGeneration.bytesDigest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
