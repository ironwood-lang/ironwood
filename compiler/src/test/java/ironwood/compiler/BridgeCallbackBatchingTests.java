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
                        for (int count : new int[]{0, 1, 127, 128, 1023, 1024, 1025, 2048, 4099}) listener.run(count, -734L);
                    }
                    listener.mode = 0;
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
