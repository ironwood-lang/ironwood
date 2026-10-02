// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.bridge.*;
import ironwood.compiler.ir.IrFunction;
import ironwood.compiler.semantic.BridgeCriticalCalls;
import ironwood.compiler.source.SourceFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

final class BridgeCriticalCallTests {
    static final String SELECTION = "Java Bridge critical calls admit only memory-only closures";
    static final String STRUCTURE = "Java Bridge critical calls keep JNI declarations and exact adapter inventories";
    static final String NATIVE = "Java Bridge critical calls preserve values failures and JNI fallback";
    private BridgeCriticalCallTests() {}

    private static final String PROBE = """
            package criticalprobe;
            public final class Probe {
                public static long plain(long value) { return helper(value) + 1; }
                private static long helper(long value) {
                    if (value < 0) throw new IllegalArgumentException("negative");
                    return value * 2;
                }
                public static long allocates(int size) {
                    long[] values = new long[size];
                    values[0] = 7;
                    return values[0] + values.length;
                }
                public static void prints(long value) { System.out.println(value); }
                public static long indirect(long value) { prints(value); return value; }
                public static long clock() { return System.nanoTime(); }
                public static int text(long value) { return ("" + value).length(); }
            }
            """;

    private static final String SOURCE = """
            package criticalcalls;
            public final class Engine {
                private static Engine saved;
                private long total;
                public Engine() {}
                public Engine publish() { saved = this; return this; }
                public enum Mode { FAST, SLOW; }
                public long add(long a, long b) {
                    if (a < 0) throw new IllegalArgumentException("wide failure");
                    this.total += a + b;
                    return a + b;
                }
                public double scale(double value) {
                    if (value < 0.0) throw new IllegalArgumentException("real failure");
                    return value * 2.0;
                }
                public int count(int value) {
                    if (value == 13) throw new IllegalStateException("narrow failure");
                    return value + 1;
                }
                public boolean positive(long value) { return value > 0; }
                public float half(float value) { return value / 2.0f; }
                public char letter(int index) { return (char) ('a' + index); }
                public short step(short value) { return (short) (value + 1); }
                public byte low(int value) { return (byte) value; }
                public void accept(long value) {
                    if (value < 0) throw new IllegalArgumentException("status failure");
                    this.total += value;
                }
                public Engine self(long value) {
                    if (value < 0) throw new IllegalArgumentException("address failure");
                    return value == 0 ? null : this;
                }
                public long mode(Mode mode, long a) { return mode == null ? -1 : (mode == Mode.FAST ? a : -a); }
                public long fixed(Mode mode, long a, long b) {
                    if (a < 0) throw new IllegalArgumentException("fixed failure");
                    return mode == null ? -1 : (mode == Mode.FAST ? a : b);
                }
                public static long twice(long value) { return value * 2; }
                public long total() { return this.total; }
                public String name() { return "engine"; }
                public Mode current(long value) { return value > 0 ? Mode.FAST : Mode.SLOW; }
            }
            """;

    /** Engine methods whose every binding is critical; the two private fixed-enum entries add to these. */
    private static final Set<String> CRITICAL = Set.of("accept", "add", "count", "fixed", "half", "letter", "low", "mode",
            "positive", "publish", "scale", "self", "step", "total", "twice");

    static void selection() {
        var analyzed = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(List.of(SourceFile.of("Probe.iron", PROBE)));
        check(analyzed.valid(), analyzed.diagnostics().toString());
        var program = analyzed.program().orElseThrow();
        var proof = BridgeCriticalCalls.analyze(program);
        check(proof.matches(program), "selection lost its program identity");
        for (String admitted : List.of("plain", "helper", "allocates")) {
            check(proof.refusal(linkage(program.functions(), admitted)).isEmpty(), "memory-only closure refused: " + admitted);
        }
        check(proof.refusal(linkage(program.functions(), "prints")).orElseThrow().contains("PrintStream"), "console output admitted");
        // A caller inherits the first disqualifying operation of its closure.
        check(proof.refusal(linkage(program.functions(), "indirect")).equals(proof.refusal(linkage(program.functions(), "prints"))),
                "transitive console output admitted");
        check(proof.refusal(linkage(program.functions(), "clock")).orElseThrow().contains("IrSystemClockInstruction"), "clock admitted");
        check(proof.refusal(linkage(program.functions(), "text")).isPresent(), "unaudited String operation admitted");
        check(proof.refusal("missing_symbol").orElseThrow().contains("unknown native function"), "unknown symbol admitted");
    }

    static void structure() {
        var artifact = artifact();
        var proof = BridgeObjectAdmission.prove(artifact, List.of("criticalcalls"));
        check(proof.contract().isPresent(), proof.reason());
        var admission = proof.contract().orElseThrow();
        check(admission.roots().isEmpty(), "fixture must use the permanent projection");
        var jni = BridgeGeneration.createObjects("critical.jar", artifact, admission, "test", "1".repeat(64), "2".repeat(64));
        var unselected = BridgePermanentJavaSources.generate(artifact, admission, jni);
        // Without the producer option nothing about the generated artifact changes.
        check(!jni.criticalCalls() && !jni.manifest().containsKey("calls")
                && jni.manifest().equals(BridgeGeneration.createObjects("critical.jar", artifact, admission, "test", "1".repeat(64), "2".repeat(64), false).manifest()),
                "default generation changed");
        check(unselected.declarations().bindings().stream().allMatch(binding -> binding.critical() == -1)
                && unselected.declarations().supportNatives().isEmpty()
                && unselected.declarations().generatedTypes().stream().noneMatch(name -> name.endsWith(".Critical"))
                && unselected.declarations().sources().values().stream().noneMatch(text -> text.contains("MethodHandle")),
                "unselected generation gained critical declarations");
        check(!BridgePermanentNativeSources.generate(artifact, admission, jni, unselected).source().contains("iw_critical"),
                "unselected native adapters gained critical entries");

        var generation = BridgeGeneration.createObjects("critical.jar", artifact, admission, "test", "1".repeat(64), "2".repeat(64), true);
        check(generation.criticalCalls() && !generation.identity().equals(jni.identity())
                && generation.matchesObjects(artifact, admission) && jni.matchesObjects(artifact, admission),
                "critical selection must select its own generation");
        var restored = BridgeGeneration.fromManifest(generation.manifest());
        check(restored.criticalCalls() && restored.identity().equals(generation.identity()), "packaged generation lost its call selection");
        var java = BridgePermanentJavaSources.generate(artifact, admission, generation);
        var declarations = java.declarations();
        var bindings = declarations.bindings();
        var selected = bindings.stream().filter(binding -> binding.critical() >= 0).toList();
        check(selected.stream().map(BridgeJavaSources.Binding::critical).toList().equals(IntStream.range(0, selected.size()).boxed().toList()),
                "critical indices must follow declaration order");
        check(selected.size() == CRITICAL.size() + 2 && bindings.stream().allMatch(binding ->
                (binding.critical() >= 0) == (binding.binaryName().equals("criticalcalls.Engine") && CRITICAL.contains(binding.method().name()))),
                "wrong critical selection: " + selected.stream().map(binding -> binding.method().name()).toList());
        check(selected.stream().filter(binding -> !binding.fixedEnums().isEmpty()).count() == 2, "private enum entries lost critical adapters");
        // Every critical binding keeps its registered JNI declaration as the fallback.
        var natives = declarations.nativeDeclarations();
        check(natives.size() == natives.stream().distinct().count() && selected.stream().allMatch(binding -> natives.contains(
                new BridgeJavaSources.NativeDeclaration(binding.binaryName(), binding.nativeName(), binding.descriptor()))),
                "critical binding lost its JNI declaration");
        String support = generation.supportPackage() + ".Critical";
        check(declarations.generatedTypes().contains(support) && declarations.supportNatives().equals(List.of(
                new BridgeJavaSources.NativeDeclaration(support, "address", "(I)J"),
                new BridgeJavaSources.NativeDeclaration(support, "deliver", "()V"))), "wrong critical support inventory");
        String source = declarations.sources().get("criticalcalls/Engine.java");
        check(count(source, ".link(java.lang.invoke.MethodHandles.lookup(), ") == selected.size()
                && count(source, ".invokeExact(") == selected.size(), "wrong generated handle count");
        var nativeCode = BridgePermanentNativeSources.generate(artifact, admission, generation, java);
        check(nativeCode.adapters().size() == natives.size(), "native inventory differs from declarations");
        for (int index = 0; index < selected.size(); index++) {
            check(nativeCode.source().contains(" iw_critical_" + index + "("), "missing critical adapter " + index);
        }
        check(!nativeCode.source().contains(" iw_critical_" + selected.size() + "("), "extra critical adapter");
        // A forged critical index for a JNI-only binding cannot reach native generation.
        var forged = new ArrayList<>(bindings);
        int target = IntStream.range(0, forged.size()).filter(index -> forged.get(index).method().name().equals("name")).findFirst().orElseThrow();
        var binding = forged.get(target);
        forged.set(target, new BridgeJavaSources.Binding(binding.binaryName(), binding.nativeName(), binding.descriptor(), binding.method(),
                binding.entrySymbol(), binding.permanentConversion(), binding.enumTokenParameters(), binding.fixedEnums(), selected.size()));
        var stale = new BridgeJavaSources(declarations.sources(), forged, declarations.generatedTypes(), declarations.ensureMethod(),
                declarations.facadeRegistrations(), declarations.rootDestructions(), declarations.supportNatives());
        try {
            BridgePermanentNativeSources.generate(artifact, admission, generation,
                    new BridgePermanentJavaSources.Sources(stale, java.facades(), java.enums()));
            throw new AssertionError("forged critical binding admitted");
        } catch (IllegalArgumentException expected) { check(expected.getMessage().contains("exact final admission"), expected.getMessage()); }
        // Declarations generated for JNI cannot be paired with a critical generation.
        try {
            BridgePermanentNativeSources.generate(artifact, admission, generation, unselected);
            throw new AssertionError("mismatched call selection admitted");
        } catch (IllegalArgumentException expected) { check(expected.getMessage().contains("exact final admission"), expected.getMessage()); }
    }

    static void nativeBehavior() throws Exception {
        Path base = Path.of("workspace/java-bridge/evidence/critical-calls/tests").toAbsolutePath();
        Files.createDirectories(base);
        Path folder = Files.createTempDirectory(base, "run-");
        Path source = folder.resolve("Engine.iron"); Files.writeString(source, SOURCE);
        Path consumer = folder.resolve("CriticalConsumer.java"); Files.writeString(consumer, CONSUMER);
        Path java = Path.of(System.getProperty("java.home"), "bin");
        String separator = System.getProperty("path.separator");
        for (int level : List.of(0, 3)) {
            Path jar = folder.resolve("critical-O" + level + ".jar");
            String produced = BridgeProducerTests.command(folder, "produce-" + level, 0, new String[]{"--java-bridge", "--export", "criticalcalls",
                    "--unfreed=off", "-O" + level, "--critical-calls=on", "-o", jar.toString(), source.toString()});
            check(produced.contains("critical calls selected for " + (CRITICAL.size() + 2) + " of "), produced);
            Path classes = folder.resolve("consumer-" + level);
            BridgeEntryTests.run(folder, List.of(java.resolve("javac").toString(), "--release", "21", "-Xlint:all", "-Werror",
                    "-cp", jar.toString(), "-d", classes.toString(), consumer.toString()), "javac-" + level);
            String path = jar + separator + classes;
            // Granted native access: linked handles, checked JNI for the fallback-only members, no warning.
            check(BridgeEntryTests.run(folder, List.of(java.resolve("java").toString(), "-Xcheck:jni", "--enable-native-access=ALL-UNNAMED",
                    "-cp", path, "CriticalConsumer", "critical"), "granted-" + level).equals("critical-calls-ok\n"), "granted consumer mismatch");
            // The consumer override and a launch that denies this module both select the registered JNI methods.
            check(BridgeEntryTests.run(folder, List.of(java.resolve("java").toString(), "-Xcheck:jni", "-Dironwood.bridge.calls=jni",
                    "-cp", path, "CriticalConsumer", "jni"), "override-" + level).equals("critical-calls-ok\n"), "override consumer mismatch");
            check(BridgeEntryTests.run(folder, List.of(java.resolve("java").toString(), "--enable-native-access=java.base",
                    "-cp", path, "CriticalConsumer", "jni"), "denied-" + level).equals("critical-calls-ok\n"), "denied consumer mismatch");
            // The default launch links the handles; the JVM reports its own restricted-method warning.
            String defaulted = BridgeEntryTests.run(folder, List.of(java.resolve("java").toString(),
                    "-cp", path, "CriticalConsumer", "critical"), "default-" + level);
            check(defaulted.endsWith("critical-calls-ok\n") && defaulted.contains("WARNING"), defaulted);
        }
        // The same source without the option keeps the JNI-only artifact.
        Path jni = folder.resolve("jni.jar");
        String unselected = BridgeProducerTests.command(folder, "produce-jni", 0, new String[]{"--java-bridge", "--export", "criticalcalls",
                "--unfreed=off", "-O3", "--critical-calls=off", "-o", jni.toString(), source.toString()});
        check(!unselected.contains("critical calls"), unselected);
        try (var archive = new java.util.zip.ZipFile(jni.toFile())) {
            check(archive.stream().noneMatch(entry -> entry.getName().endsWith("/Critical.class")), "JNI artifact packaged critical support");
        }
        System.out.println("critical call evidence: " + folder);
    }

    private static CompilationArtifact artifact() {
        var result = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(List.of(SourceFile.of("Engine.iron", SOURCE)));
        check(result.valid(), result.diagnostics().toString()); return result;
    }

    private static String linkage(List<IrFunction> functions, String name) {
        var matches = functions.stream().filter(function -> function.sourceName().equals(name)
                && function.ownerClass().equals("criticalprobe.Probe")).map(IrFunction::linkageName).collect(Collectors.toList());
        check(matches.size() == 1, "expected one function named " + name + ": " + matches);
        return matches.getFirst();
    }

    private static int count(String text, String part) {
        int total = 0;
        for (int index = text.indexOf(part); index >= 0; index = text.indexOf(part, index + part.length())) total++;
        return total;
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private static final String CONSUMER = """
            import criticalcalls.Engine;
            import criticalcalls.Engine.Mode;
            import java.lang.reflect.Field;
            import java.lang.reflect.Modifier;
            public final class CriticalConsumer {
                public static void main(String[] args) throws Exception {
                    boolean critical = args[0].equals("critical");
                    Engine engine = new Engine().publish();
                    int handles = 0;
                    for (Field field : Engine.class.getDeclaredFields()) {
                        if (!field.getName().startsWith("$ironwood$critical$")) continue;
                        check(Modifier.isPrivate(field.getModifiers()) && Modifier.isStatic(field.getModifiers())
                                && Modifier.isFinal(field.getModifiers()));
                        field.setAccessible(true);
                        check((field.get(null) != null) == critical);
                        handles++;
                    }
                    check(handles == 17);
                    long total = 0;
                    // Enough rounds to run interpreted and compiled callers, with failures between successes.
                    for (int round = 0; round < 20000; round++) {
                        check(engine.add(round, 2) == round + 2L); total += round + 2L;
                        check(engine.add(0, 0) == 0 && engine.add(1, 0) == 1 && engine.add(0, -1) == -1);
                        check(engine.add(1L << 40, 1L << 41) == 3L << 40); total += 3L << 40;
                        engine.accept(round); total += round;
                        check(engine.self(round + 1) == engine && engine.self(0) == null);
                        check(engine.count(41) == 42 && engine.count(-5) == -4 && engine.count(-1) == 0
                                && engine.count(Integer.MAX_VALUE) == Integer.MIN_VALUE);
                        check(engine.positive(1) && !engine.positive(-1) && !engine.positive(0));
                        check(engine.half(5.0f) == 2.5f && Float.floatToRawIntBits(engine.half(-0.0f)) == Float.floatToRawIntBits(-0.0f)
                                && Float.isNaN(engine.half(Float.NaN)));
                        check(engine.letter(2) == 'c' && engine.letter(65535 - 'a') == (char) 65535);
                        check(engine.step((short) -2) == -1 && engine.step(Short.MAX_VALUE) == Short.MIN_VALUE);
                        check(engine.low(0x1ff) == (byte) -1 && engine.low(0x80) == Byte.MIN_VALUE);
                        check(engine.scale(1.5) == 3.0 && Double.doubleToRawLongBits(engine.scale(-0.0)) == Double.doubleToRawLongBits(-0.0)
                                && Double.isNaN(engine.scale(Double.NaN)));
                        check(engine.mode(null, 7) == -1 && engine.mode(Mode.FAST, 7) == 7 && engine.mode(Mode.SLOW, 7) == -7);
                        check(engine.fixed(null, 7, 11) == -1 && engine.fixed(Mode.FAST, 7, 11) == 7 && engine.fixed(Mode.SLOW, 7, 11) == 11);
                        check(Engine.twice(21) == 42 && Engine.twice(-1) == -2);
                        if (round % 4000 == 0) failures(engine);
                    }
                    check(engine.total() == total);
                    // Members outside the critical subset keep their JNI conversions.
                    check(engine.name().equals("engine") && engine.current(1) == Mode.FAST && engine.current(0) == Mode.SLOW);
                    check(Mode.valueCount() == 2 && Mode.valueAt(1) == Mode.SLOW);
                    System.out.println("critical-calls-ok");
                }
                private static void failures(Engine engine) {
                    long before = engine.total();
                    try { engine.add(-1, 0); throw new AssertionError(); }
                    catch (IllegalArgumentException expected) { failure(expected, "wide failure"); }
                    try { engine.scale(-1.0); throw new AssertionError(); }
                    catch (IllegalArgumentException expected) { failure(expected, "real failure"); }
                    try { engine.count(13); throw new AssertionError(); }
                    catch (IllegalStateException expected) { failure(expected, "narrow failure"); }
                    try { engine.accept(-1); throw new AssertionError(); }
                    catch (IllegalArgumentException expected) { failure(expected, "status failure"); }
                    try { engine.self(-1); throw new AssertionError(); }
                    catch (IllegalArgumentException expected) { failure(expected, "address failure"); }
                    for (Mode mode : Mode.values()) {
                        try { engine.fixed(mode, -1, 0); throw new AssertionError(); }
                        catch (IllegalArgumentException expected) { failure(expected, "fixed failure"); }
                    }
                    // A delivered failure leaves nothing parked for the following successful calls.
                    check(engine.total() == before && engine.add(0, 0) == 0 && engine.self(1) == engine);
                }
                private static void failure(RuntimeException thrown, String message) {
                    check(thrown.getMessage().equals(message));
                    check(thrown.getStackTrace().length > 0 && "Engine.iron".equals(thrown.getStackTrace()[0].getFileName()));
                }
                private static void check(boolean condition) { if (!condition) throw new AssertionError(); }
            }
            """;
}
