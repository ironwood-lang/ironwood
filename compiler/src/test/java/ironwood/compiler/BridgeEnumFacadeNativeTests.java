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

final class BridgeEnumFacadeNativeTests {
    static final String NAME = "Java Bridge generated enum jars preserve cold conversion and initializer containment";
    private static final String MODE = """
            package enumjava;
            public enum Mode {
                SELL(29) { @Override public int code() { return 41; } @Override public String toString() { return "sold"; } }, BUY(11);
                private final int index;
                Mode(int index) { this.index = index; }
                public int index() { return index; }
                public int code() { return index; }
                public Mode self() { return this; }
                public String copy(String text) { return new String(text); }
                public static Mode choose(Mode value) { return value; }
                public static boolean absent(Empty value) { return value == null; }
                public enum Empty { ; public static Mode unseen() { return null; } }
            }
            """;
    private static final String PROBE = """
            package enumjava;
            public final class Probe {
                private static int entries;
                private static int attempts;
                private Probe() {}
                public static int receive(Mode value, String text) { entries++; return (value == null ? -1 : value.index()) + text.length(); }
                public static int broken(Bad value, String text) { entries++; return value == null ? -1 : value.marker() + text.length(); }
                public static int failInit() { attempts++; throw new IllegalStateException("enum initialization failed"); }
                public static int entered() { return entries; }
                public static int attempts() { return attempts; }
                public static long allocations() { return System.allocationCount(); }
                public static long live() { return System.liveAllocationCount(); }
                public enum Bad {
                    BROKEN;
                    private final int marker = Probe.failInit();
                    public int marker() { return marker; }
                }
            }
            """;
    private static final String BOX = """
            package enumjava;
            public final class Box {
                private static Box saved;
                private final Mode mode;
                public Box(Mode mode) { this.mode = mode; }
                public Box publish() { saved = this; return this; }
                public static Box recall() { return saved; }
                public Box self() { return this; }
                public Mode mode() { return mode; }
                public Mode select(Mode value) { return value; }
            }
            """;
    private BridgeEnumFacadeNativeTests() {}

    static void facades() throws Exception {
        if (!System.getProperty("os.name").startsWith("Mac")) throw new AssertionError("enum preview jars require macOS ARM64");
        Path base = Path.of("workspace/java-bridge/evidence/p3b/enum-facades").toAbsolutePath(); Files.createDirectories(base);
        Path directory = Files.createTempDirectory(base, "run-");
        var producer = BridgeProducerInputs.discover();
        var discovery = LlvmToolchain.discover(null); check(discovery.successful(), discovery.error());
        var toolchain = discovery.toolchain().orElseThrow();
        Path javaHome = Path.of(System.getProperty("java.home"));
        for (boolean mixed : List.of(false, true)) {
            Path world = directory.resolve(mixed ? "mixed" : "enum-only"); Files.createDirectories(world);
            var inputs = new ArrayList<SourceFile>(); inputs.add(SourceFile.of("Mode.iron", MODE));
            if (mixed) { inputs.add(SourceFile.of("Probe.iron", PROBE)); inputs.add(SourceFile.of("Box.iron", BOX)); }
            for (var input : inputs) Files.writeString(world.resolve(input.path().getFileName()), input.content());
            var artifact = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(inputs);
            check(artifact.valid(), artifact.diagnostics().toString());
            var proof = BridgeObjectAdmission.prove(artifact, List.of("enumjava"));
            check(proof.contract().isPresent(), proof.reason());
            var admission = proof.contract().orElseThrow(); check(admission.roots().isEmpty(), "enum fixture acquired root bookkeeping");
            var generation = BridgeGeneration.createObjects("enums.jar", artifact, admission,
                    producer.compilerVersion(), producer.compilerIdentity(), producer.runtimeIdentity());
            var projected = BridgePermanentJavaSources.generate(artifact, admission, generation);
            var adapters = BridgePermanentNativeSources.generate(artifact, admission, generation, projected);
            String llvm = new LlvmEmitter().emit(admission.program());
            Path program = world.resolve("program.ll"); Files.writeString(program, llvm);
            Files.writeString(world.resolve("proof.txt"), "generation=" + generation.identity() + "\nllvm=" + digest(llvm)
                    + "\nadapters=" + digest(adapters.source()) + "\ncompiler=" + producer.compilerIdentity()
                    + "\nruntime=" + producer.runtimeIdentity() + "\n");
            for (var level : List.of(OptimizationLevel.O0, OptimizationLevel.O3)) {
                Path folder = world.resolve(level.toString()); Files.createDirectories(folder);
                var build = generation.nativeBuild("macos-arm64", Map.of("fixture", "generated-enum-facades", "llvm", digest(llvm),
                        "adapters", digest(adapters.source()), "optimization", level.toString()));
                String nativeSource = adapters.source() + BridgeBootstrapSources.generate(generation, build, projected.declarations(), adapters);
                Path jar = BridgeGeneratedJarTests.build(folder, program, toolchain, level, generation, build, projected.declarations(), nativeSource, Map.of());
                Path consumer = folder.resolve("EnumNativeConsumer.java");
                Files.writeString(consumer, CONSUMER.replace("MIXED", mixed ? MIXED : "throw new AssertionError(scenario);"));
                BridgeEntryTests.run(folder, List.of(javaHome.resolve("bin/javac").toString(), "--release", "21", "-Xlint:all", "-Werror", "-cp", jar.toString(), consumer.toString()), "consumer-javac");
                for (String scenario : mixed ? List.of("receiver", "argument", "normal", "failure", "budget") : List.of("receiver", "normal", "metadata")) {
                    var command = new ArrayList<String>();
                    if (scenario.equals("budget")) command.addAll(List.of("/usr/bin/env", "IRONWOOD_ALLOCATION_LIMIT=0"));
                    Path launcher = scenario.equals("metadata") ? Path.of("workspace/java-bridge/jdks/temurin-24-macos-arm64/jdk-24.0.2+12/Contents/Home/bin/java").toAbsolutePath()
                            : javaHome.resolve("bin/java");
                    check(Files.isExecutable(launcher), "missing pinned enum metadata launcher: " + launcher);
                    Path temporary = folder.resolve("tmp-" + scenario); Files.createDirectories(temporary);
                    command.addAll(List.of(launcher.toString(), "-Xcheck:jni", "-Xmx64m", "-Djava.io.tmpdir=" + temporary, "-cp", jar + java.io.File.pathSeparator + folder,
                            "EnumNativeConsumer", scenario));
                    String output = BridgeEntryTests.run(folder, command, "consumer-" + scenario);
                    check(output.endsWith("enum-native-ok:" + scenario + "\n") && !output.contains("WARNING") && !output.contains("FATAL"), output);
                    if (scenario.equals("metadata")) try (var files = Files.list(temporary)) {
                        check(files.findAny().isEmpty(), "Java-only enum access or version refusal extracted a native payload");
                    }
                }
                BridgeEntryTests.run(folder, List.of(toolchain.clang().resolveSibling("llvm-objdump").toString(), "--disassemble",
                        folder.resolve("libbridge.dylib").toString()), "disassembly");
            }
        }
        System.out.println("generated enum native evidence: " + directory);
    }

    private static String digest(String text) { return BridgeGeneration.bytesDigest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static final String CONSUMER = """
            import enumjava.Mode;
            public final class EnumNativeConsumer {
                public static void main(String[] args) throws Exception {
                    String scenario = args[0];
                    if (scenario.equals("receiver")) check(Mode.SELL.index() == 29);
                    else if (scenario.equals("metadata")) {
                        check(Runtime.version().feature() == 24);
                        Thread thread = new Thread(() -> { check(Mode.SELL.ordinal() == 0 && Mode.BUY.name().equals("BUY")); });
                        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
                        thread.setUncaughtExceptionHandler((ignored, error) -> failure.set(error)); thread.start(); thread.join();
                        check(failure.get() == null && Mode.Empty.values().length == 0 && Mode.values().length == 2);
                        try { Mode.SELL.index(); throw new AssertionError("version guard missing"); }
                        catch (LinkageError expected) { check(expected.getMessage().contains("21-23")); }
                    } else if (scenario.equals("normal")) {
                        check(Mode.choose(null) == null && Mode.choose(Mode.SELL) == Mode.SELL && Mode.choose(Mode.BUY) == Mode.BUY);
                        check(Mode.SELL.self() == Mode.SELL && Mode.BUY.self() == Mode.BUY);
                        check(Mode.SELL.code() == 41 && Mode.BUY.code() == 11 && Mode.SELL.index() == 29);
                        check(Mode.SELL.toString().equals("sold") && Mode.BUY.toString().equals("BUY"));
                        check(Mode.absent(null) && Mode.Empty.unseen() == null);
                        check(Mode.SELL.copy("A\\u0000\\uD800B").equals("A\\u0000\\uD800B"));
                        check(Mode.valueAt(0) == Mode.SELL && Mode.valueAt(1) == Mode.BUY && Mode.valueCount() == 2 && Mode.Empty.valueCount() == 0);
                    } else { MIXED }
                    System.out.println("enum-native-ok:" + scenario);
                }
                private static void check(boolean value) { if (!value) throw new AssertionError(); }
            }
            """;
    private static final String MIXED = """
            if (scenario.equals("argument")) {
                check(enumjava.Probe.receive(Mode.SELL, "abc") == 32 && enumjava.Probe.entered() == 1);
                var box = new enumjava.Box(Mode.SELL); check(box.publish() == box && box.self() == box && enumjava.Box.recall() == box);
                check(box.mode() == Mode.SELL && box.select(Mode.BUY) == Mode.BUY && box.select(null) == null);
                long nativeBefore = enumjava.Probe.allocations();
                var counter = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
                counter.setThreadAllocatedMemoryEnabled(true); long thread = Thread.currentThread().threadId();
                for (int i = 0; i < 200000; i++) { check(Mode.SELL.index() == 29 && box.select(Mode.BUY) == Mode.BUY); }
                for (int i = 0; i < 10000; i++) counter.getThreadAllocatedBytes(thread);
                long before = counter.getThreadAllocatedBytes(thread), start = System.nanoTime();
                for (int i = 0; i < 100000; i++) { check(Mode.SELL.index() == 29 && box.select(Mode.BUY) == Mode.BUY); }
                long elapsed = System.nanoTime() - start, bytes = counter.getThreadAllocatedBytes(thread) - before;
                check(bytes == 0 && enumjava.Probe.allocations() == nativeBefore);
                System.out.println("enum-hot:100000:" + bytes + ":0:" + elapsed);
            } else if (scenario.equals("failure")) {
                check(enumjava.Probe.broken(null, "x") == -1 && enumjava.Probe.attempts() == 0);
                for (int attempt = 0; attempt < 2; attempt++) {
                    try { enumjava.Probe.broken(enumjava.Probe.Bad.BROKEN, "copied"); throw new AssertionError("initializer failure missing"); }
                    catch (IllegalStateException expected) { check(expected.getMessage().equals("enum initialization failed")); }
                    check(enumjava.Probe.entered() == 1 && enumjava.Probe.attempts() == 1);
                }
                check(Mode.SELL.index() == 29 && enumjava.Probe.receive(null, "x") == 0);
            } else if (scenario.equals("budget")) {
                try { enumjava.Probe.receive(Mode.SELL, "copied"); throw new AssertionError("allocation failure missing"); }
                catch (OutOfMemoryError expected) { check(enumjava.Probe.entered() == 0 && enumjava.Probe.live() == 0); }
                check(Mode.SELL.index() == 29);
            } else throw new AssertionError(scenario);
            """;
}
