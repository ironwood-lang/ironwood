// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.bridge.*;
import ironwood.compiler.source.SourceFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;

final class BridgeCallbackBatchingTests {
    static final String PROOF = "Java Bridge batches only effect-free counted callback loops";
    static final String NATIVE = "Java Bridge automatic batches preserve order reentry failure and artifact parity";
    static final String ALLOCATION = "Java Bridge automatic batches allocate no native objects on warmed calls";
    private BridgeCallbackBatchingTests() {}

    private static final String LISTENER = """
            package batches;
            public interface Listener {
                void event(long sequence, long value);
                void one(long value);
                void three(long sequence, long value, long mixed);
                void four(long sequence, long value, long mixed, long shifted);
                long query(long value);
            }
            """;
    private static final String STEP = "value = (value ^ (value >>> 13)) * 2862933555777941757L + 3037000493L;";
    private static final String BODY = """
            if (count < 0) throw new IllegalArgumentException("negative count");
            Listener current = this.listener;
            long value = seed;
            for (int sequence = 0; sequence < count; sequence++) {
                %s
                current.event((long)sequence, value);
            }
            return value;
            """.formatted(STEP);
    private static String processor(String body) {
        return """
                package batches;
                public final class Processor {
                    private Listener listener;
                    private long observed;
                    public Processor() {}
                    public void setListener(Listener input) { listener = input; }
                    public long observed() { return observed; }
                    public long run(int count, long seed) { %s }
                }
                """.formatted(body);
    }

    static void proofs() {
        var accepted = admission(processor(BODY));
        var batch = BridgeCallbackBatching.prove(accepted);
        check(batch.entries().size() == 1, "pure recurrence was not proved");
        var entry = batch.entries().values().iterator().next();
        check(entry.countInput() == 1 && entry.arity() == 2, "incorrect counted callback binding");
        check(batch.program().functions().containsAll(accepted.program().functions()), "ordinary native functions changed");
        check(batch.equals(BridgeCallbackBatching.prove(accepted)), "batch proof is not deterministic");
        var appends = batch.program().functions().stream().flatMap(function -> function.blocks().stream())
                .flatMap(block -> block.instructions().stream())
                .filter(ironwood.compiler.ir.IrBridgeBatchAppendInstruction.class::isInstance)
                .map(ironwood.compiler.ir.IrBridgeBatchAppendInstruction.class::cast).toList();
        check(appends.size() == 1, "batch append must remain explicit in typed IR");
        var append = appends.getFirst();
        var renamer = new ironwood.compiler.ir.IrCfgRenamer(value -> new ironwood.compiler.ir.IrValueReference(
                value.id() + 1000, value.type(), value.sourceSpan()), java.util.function.UnaryOperator.identity());
        var renamed = (ironwood.compiler.ir.IrBridgeBatchAppendInstruction) renamer.instruction(append);
        check(renamed.result().id() == append.result().id() + 1000 && !renamed.context().equals(append.context())
                && !renamed.index().equals(append.index()) && !renamed.count().equals(append.count())
                && !renamed.arguments().equals(append.arguments()), "batch SSA renaming lost operands");
        check(ironwood.compiler.semantic.BridgeCallbackReachability.analyze(batch.program()).functions().values().stream()
                .flatMap(effect -> effect.unknown().stream()).anyMatch(reason -> reason.endsWith(":IrBridgeBatchAppendInstruction")),
                "post-admission scratch acquired source effect permission");
        var unsupported = List.of(
                BODY.replace(STEP, STEP + " observed = value;"),
                BODY.replace("current.event", "this.listener.event"),
                BODY.replace("current.event((long)sequence, value);", "current.event((long)sequence, value); observed = value;"),
                BODY.replace(STEP, "value = value / (long) (count - sequence - 1);"),
                BODY.replace("current.event((long)sequence, value);", "if (sequence != 3) current.event((long)sequence, value);"),
                BODY.replace("sequence = 0", "sequence = 1"),
                BODY.replace("sequence++", "sequence += 2"),
                BODY.replace(STEP, STEP + " count--;"),
                BODY.replace(STEP, STEP + " value += observed();"),
                BODY.replace("current.event((long)sequence, value);", "value = current.query(value);"),
                BODY.replace("current.event((long)sequence, value);", "current.event((long)sequence, value); if (sequence == 3) return value;"),
                BODY.replace("current.event((long)sequence, value);", "try { current.event((long)sequence, value); } catch (RuntimeException failure) { return -1L; }"),
                BODY.replace("return value;", "current.event(99L, value); return value;"),
                BODY.replace("return value;", "observed = value; return value;"));
        for (int index = 0; index < unsupported.size(); index++) {
            var candidate = admission(processor(unsupported.get(index)));
            check(BridgeCallbackBatching.prove(candidate).entries().isEmpty(), "unsafe schedule accepted: " + index);
        }
    }

    private static BridgeOwnedCallbackAdmission admission(String processor) {
        var pipeline = new CompilerPipeline(UnfreedMode.ERROR);
        var sources = new ArrayList<>(List.of(SourceFile.of("Listener.iron", LISTENER), SourceFile.of("Processor.iron", processor)));
        var carrier = BridgeCallbackCarrierSources.discover(pipeline.analyzeForBridge(sources));
        sources.add(carrier.source());
        var proxies = BridgeListenerProxies.discover(pipeline.analyzeForBridge(sources), List.of("batches"));
        var artifact = pipeline.analyzeForBridge(sources, proxies);
        var result = BridgeOwnedCallbackAdmission.prove(artifact, proxies, carrier, List.of("batches"));
        return result.contract().orElseThrow(() -> new AssertionError(result.reason()));
    }

    static void nativeAllocations() throws Exception {
        var admitted = admission(processor(BODY));
        var inputs = BridgeProducerInputs.discover();
        var generation = BridgeGeneration.createOwnedCallbacks("batch-allocation.jar", admitted,
                inputs.compilerVersion(), inputs.compilerIdentity(), inputs.runtimeIdentity());
        var projected = BridgeOwnedCallbackJavaSources.generate(admitted, generation);
        var adapters = BridgeOwnedCallbackNativeSources.generate(admitted, generation, projected);
        var discovery = ironwood.compiler.backend.LlvmToolchain.discover(null);
        check(discovery.successful(), discovery.error());
        Path base = Path.of("workspace/java-bridge/evidence/p5/batch-allocation").toAbsolutePath(); Files.createDirectories(base);
        Path directory = Files.createTempDirectory(base, "run-"), llvm = directory.resolve("batch.ll");
        Files.writeString(llvm, new ironwood.compiler.backend.LlvmEmitter().emit(projected.batching().program()));
        Path jdk = Path.of(System.getProperty("java.home"));
        for (var level : List.of(ironwood.compiler.backend.OptimizationLevel.O0, ironwood.compiler.backend.OptimizationLevel.O3)) {
            Path folder = directory.resolve(level.name()); Files.createDirectories(folder);
            var build = generation.nativeBuild(BridgeGeneratedJarTests.target(), java.util.Map.of("fixture", "batch-allocation", "optimization", level.name()));
            // Same generated entry/transport, with a test-only native counter.
            // Do not expand the admitted public Ironwood API for instrumentation.
            String nativeSource = adapters.source() + BridgeBootstrapSources.generate(generation, build, projected.declarations(), adapters) + """
                    JNIEXPORT jlong JNICALL Java_AllocationConsumer_allocations(JNIEnv *env, jclass type) {
                        (void)env; (void)type; return ironwood_allocation_count();
                    }
                    """;
            Path jar = BridgeGeneratedJarTests.build(folder, llvm, discovery.toolchain().orElseThrow(), level,
                    generation, build, projected.declarations(), nativeSource, java.util.Map.of());
            Path consumer = folder.resolve("AllocationConsumer.java"); Files.writeString(consumer, ALLOCATION_CONSUMER);
            BridgeEntryTests.run(folder, List.of(jdk.resolve("bin/javac").toString(), "--release", "21", "-Xlint:all", "-Werror", "-cp", jar.toString(), consumer.toString()), "javac");
            check(BridgeEntryTests.run(folder, List.of(jdk.resolve("bin/java").toString(), "-Xcheck:jni", "-cp",
                    jar + java.io.File.pathSeparator + folder, "AllocationConsumer"), "consumer").equals("batch-native-allocation-ok\n"), "native batch allocation");
        }
        System.out.println("native batch allocation evidence: " + directory);
    }

    static void nativeBatches() throws Exception {
        Path base = Path.of("workspace/java-bridge/evidence/p5/batching").toAbsolutePath(); Files.createDirectories(base);
        Path directory = Files.createTempDirectory(base, "run-");
        Path listener = directory.resolve("Listener.iron"), processor = directory.resolve("Processor.iron");
        String owner = processor(BODY);
        var extra = new StringBuilder();
        for (var method : List.of("one", "three", "four")) {
            String arguments = switch (method) {
                case "one" -> "value";
                case "three" -> "(long)sequence, value, value ^ 123L";
                default -> "(long)sequence, value, value ^ 123L, (long)sequence + 7L";
            };
            extra.append("public long ").append(method).append("(int count, long seed) { ")
                    .append(BODY.replace("current.event((long)sequence, value);", "current." + method + "(" + arguments + ");")).append(" }\n");
        }
        owner = owner.substring(0, owner.lastIndexOf('}')) + extra + "}\n";
        Files.writeString(listener, LISTENER); Files.writeString(processor, owner);
        Path consumer = directory.resolve("Consumer.java"); Files.writeString(consumer, CONSUMER);
        Path classes = directory.resolve("iron-classes"), archive = directory.resolve("batches.ironjar");
        BridgeProducerTests.command(directory, "compile", 0, new String[]{"-d", classes.toString(), "--unfreed=error", listener.toString(), processor.toString()});
        IronJar.create(archive, List.of(classes), List.of());
        Path jdk = Path.of(System.getProperty("java.home"));
        String referenceGeneration = null;
        for (String variant : List.of("source", "classes", "archive")) {
            Path output = directory.resolve(variant); Files.createDirectories(output);
            Path jar = output.resolve("batches.jar");
            var command = new ArrayList<>(List.of("--java-bridge", "--export", "batches", "--unfreed=error",
                    variant.equals("source") ? "-O0" : "-O3", "-o", jar.toString()));
            if (variant.equals("source")) command.addAll(List.of(listener.toString(), processor.toString()));
            else command.addAll(List.of("--source-path", directory.resolve("absent").toString(), "-cp", (variant.equals("classes") ? classes : archive).toString()));
            BridgeProducerTests.command(output, "producer", 0, command.toArray(String[]::new));
            String support = null;
            try (var zip = new ZipFile(jar.toFile())) {
                var relay = zip.stream().filter(item -> item.getName().endsWith("/CallbackDispatch.java")).findFirst().orElseThrow();
                String text = new String(zip.getInputStream(relay).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                check(text.contains("static void batch0("), "paired artifact omitted automatic batching");
                support = text.substring(text.indexOf("package ") + 8, text.indexOf(';', text.indexOf("package ")));
                String generation = text.substring(text.indexOf("@Identity(\"") + 11, text.indexOf("\")", text.indexOf("@Identity(\"")));
                if (referenceGeneration == null) referenceGeneration = generation;
                else check(referenceGeneration.equals(generation), "batch source/class/archive identity differs");
            }
            BridgeEntryTests.run(output, List.of(jdk.resolve("bin/javac").toString(), "--release", "21", "-Xlint:all", "-Werror",
                    "-cp", jar.toString(), "-d", output.toString(), consumer.toString()), "javac");
            var run = List.of(jdk.resolve("bin/java").toString(), "-Xcheck:jni", "-cp", jar + java.io.File.pathSeparator + output,
                    "Consumer", support + ".BridgeLifetimeException");
            check(BridgeEntryTests.run(output, run, "consumer").equals("callback-batches-ok\n"), "batch consumer failed");
            var limited = new ArrayList<>(run); limited.add(1, "-XX:MaxDirectMemorySize=1m"); limited.add("scratch-failure");
            check(BridgeEntryTests.run(output, limited, "scratch-allocation-fallback").equals("callback-batches-ok\n"), "scratch failure changed behavior");
        }
        System.out.println("callback batching evidence: " + directory);
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private static final String ALLOCATION_CONSUMER = """
            import batches.Listener;
            import batches.Processor;
            public final class AllocationConsumer implements Listener {
                private long delivered;
                private long expected;
                private static native long allocations();
                public long query(long value) { return value; }
                public void one(long value) { event(delivered, value); }
                public void three(long sequence, long value, long mixed) { event(sequence, value); }
                public void four(long sequence, long value, long mixed, long shifted) { event(sequence, value); }
                public void event(long sequence, long value) {
                    expected = (expected ^ (expected >>> 13)) * 2862933555777941757L + 3037000493L;
                    if (sequence != delivered++ || value != expected) throw new AssertionError("event");
                }
                private void run(Processor owner, int count) {
                    delivered = 0; expected = 17L;
                    long value = owner.run(count, expected);
                    if (value != expected || delivered != count) throw new AssertionError("result");
                }
                public static void main(String[] args) {
                    Processor owner = new Processor(); AllocationConsumer listener = new AllocationConsumer();
                    owner.setListener(listener);
                    try {
                        for (int count : new int[]{1, 2, 1024, 1025, 2053}) {
                            for (int i = 0; i < 100; i++) listener.run(owner, count);
                            long before = allocations();
                            for (int i = 0; i < 1000; i++) listener.run(owner, count);
                            if (allocations() != before) throw new AssertionError("native allocation");
                        }
                    } finally { owner.free(); }
                    System.out.println("batch-native-allocation-ok");
                }
            }
            """;

    private static final String CONSUMER = """
            import batches.Listener;
            import batches.Processor;
            public final class Consumer implements Listener {
                private final Processor owner;
                private final Class<?> refusal;
                private final RuntimeException failure = new RuntimeException("callback identity");
                private int delivered;
                private long value;
                private int failAt = -1;
                private int mode;
                private boolean reenter;
                Consumer(Processor owner, Class<?> refusal) { this.owner = owner; this.refusal = refusal; }
                static long next(long value) { return (value ^ (value >>> 13)) * 2862933555777941757L + 3037000493L; }
                static void check(boolean value) { if (!value) throw new AssertionError(); }
                public long query(long value) { return value; }
                public void one(long value) { event(delivered, value); }
                public void three(long sequence, long value, long mixed) { check(mixed == (value ^ 123L)); event(sequence, value); }
                public void four(long sequence, long value, long mixed, long shifted) {
                    check(shifted == sequence + 7L); three(sequence, value, mixed);
                }
                public void event(long sequence, long actual) {
                    check(sequence == delivered);
                    value = next(value); check(actual == value);
                    delivered++;
                    if (sequence == failAt) throw failure;
                    if (reenter && sequence == 17) {
                        try { owner.free(); throw new AssertionError("active free"); }
                        catch (IllegalStateException expected) { check(expected.getClass() == refusal); }
                        Consumer nested = new Consumer(owner, refusal);
                        owner.setListener(nested);
                        nested.run(2051, 99L);
                        nested.failAt = 1024;
                        nested.failing(3073, 37L);
                        nested.failAt = -1;
                        nested.run(128, -3L);
                        owner.setListener(null);
                        // This invocation must finish delivering to its captured
                        // listener and its buffer must survive the nested calls.
                    }
                }
                void run(int count, long seed) {
                    delivered = 0; value = seed;
                    long result = switch (mode) {
                        case 1 -> owner.one(count, seed);
                        case 2 -> owner.three(count, seed);
                        case 3 -> owner.four(count, seed);
                        default -> owner.run(count, seed);
                    };
                    check(result == value);
                    check(delivered == count);
                }
                void failing(int count, long seed) {
                    delivered = 0; value = seed;
                    try { owner.run(count, seed); throw new AssertionError("lost callback failure"); }
                    catch (RuntimeException actual) { check(actual == failure); }
                    check(delivered == failAt + 1);
                }
                public static void main(String[] args) throws Exception {
                    Class<?> refusal = Class.forName(args[0]);
                    Processor owner = new Processor();
                    Consumer listener = new Consumer(owner, refusal); owner.setListener(listener);
                    if (args.length > 1) {
                        // Fill direct memory after bootstrap, so this failure
                        // targets optional scratch rather than image extraction.
                        var pressure = new java.util.ArrayList<java.nio.ByteBuffer>();
                        try {
                            for (int i = 0; i < 2048; i++) pressure.add(java.nio.ByteBuffer.allocateDirect(1024));
                            throw new AssertionError("direct-memory limit was not enforced");
                        } catch (OutOfMemoryError expected) { }
                        listener.run(128, 17L);
                        listener.failAt = 17; listener.failing(128, 9L);
                        java.lang.ref.Reference.reachabilityFence(pressure);
                        owner.free(); System.out.println("callback-batches-ok"); return;
                    }
                    for (int mode = 0; mode < 4; mode++) {
                        listener.mode = mode;
                        for (int count : new int[]{0, 1, 2, 3, 4, 127, 128, 1023, 1024, 1025, 2048, 4099}) listener.run(count, -734L);
                    }
                    listener.mode = 0;
                    var allocationBean = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
                    if (!allocationBean.isThreadAllocatedMemoryEnabled()) allocationBean.setThreadAllocatedMemoryEnabled(true);
                    for (int i = 0; i < 100; i++) listener.run(2053, 37L);
                    long thread = Thread.currentThread().threadId();
                    long javaBefore = allocationBean.getThreadAllocatedBytes(thread);
                    for (int i = 0; i < 100; i++) listener.run(2053, 37L);
                    check(allocationBean.getThreadAllocatedBytes(thread) == javaBefore);
                    for (int point : new int[]{0, 16, 127, 1023, 1024, 2048, 4999}) {
                        listener.failAt = point; listener.failing(5000, 17L);
                        listener.failAt = -1; listener.run(1031, 13L);
                    }
                    listener.reenter = true; listener.run(4099, 71L);
                    listener.reenter = false; owner.setListener(listener); listener.run(2053, 41L);
                    try { owner.run(-1, 0L); throw new AssertionError("negative count"); }
                    catch (IllegalArgumentException expected) { check(expected.getMessage().equals("negative count")); }
                    owner.setListener(null);
                    check(owner.run(0, 123L) == 123L);
                    try { owner.run(128, 0L); throw new AssertionError("null listener"); }
                    catch (NullPointerException expected) { }
                    owner.setListener(listener); listener.run(128, 0L); owner.free();
                    try { owner.run(128, 0L); throw new AssertionError("dead owner"); }
                    catch (IllegalStateException expected) { check(expected.getClass() == refusal); }
                    System.out.println("callback-batches-ok");
                }
            }
            """;
}
