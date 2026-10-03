// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.bridge.*;
import ironwood.compiler.source.SourceFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class BridgeCustomSnapshotJavaTests {
    static final String NAME = "Java Bridge generated custom snapshots preserve hierarchy copied data and bounded graphs";
    private BridgeCustomSnapshotJavaTests() {}

    static void snapshots() throws Exception {
        String original = BridgeCustomExceptionTests.SOURCE.replace("public static int ping()", "public static int ping() throws Base");
        String source = original.substring(0, original.lastIndexOf('}')) + """
                public static final class PathProblem extends ironwood.nio.file.InvalidPathException {
                    public PathProblem() { super("path", "reason", 2); }
                    @Override public String getInput() { return null; }
                    @Override public int getIndex() { return -99; }
                }
                public static final class ParseProblem extends ironwood.time.format.DateTimeParseException {
                    public ParseProblem() { super("message", "parsed", 3); }
                    @Override public String getParsedString() { return null; }
                    @Override public int getErrorIndex() { return -99; }
                }
                public static final class IoProblem extends ironwood.io.UncheckedIOException {
                    public IoProblem() { super("io", new ironwood.io.IOException("cause")); }
                    @Override public ironwood.io.IOException getCause() { return null; }
                }
                }
                """;
        var artifact = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(List.of(SourceFile.of("Cases.iron", source)));
        check(artifact.valid(), artifact.diagnostics().toString());
        var proof = BridgeObjectAdmission.prove(artifact, List.of("customsnap")); check(proof.contract().isPresent(), proof.reason());
        var admission = proof.contract().orElseThrow();
        var generation = BridgeGeneration.createObjects("snapshots.jar", artifact, admission, "test", "1".repeat(64), "2".repeat(64));
        var projected = BridgePermanentJavaSources.generate(artifact, admission, generation);
        check(projected.facades().isEmpty() && projected.enums().isEmpty() && projected.declarations().facadeRegistrations().isEmpty(), "snapshot acquired native facade state");
        var nativeSources = BridgePermanentNativeSources.generate(artifact, admission, generation, projected);
        check(!nativeSources.source().contains("iw_permanent_cache"), "snapshot-only world acquired native identity state");
        var projection = admission.lifetime().exceptions().projection();
        var layout = BridgeCustomSnapshotLayout.create(artifact, projection);
        String consumer = CONSUMER.replace("@SUPPORT@", generation.supportPackage()).replace("@SLOTS@", Integer.toString(layout.slots().size()));
        for (var type : projection.types()) consumer = consumer.replace("@" + type.nativeName() + "@", Integer.toString(type.typeId()));
        for (var slot : layout.slots()) consumer = consumer.replace("@" + slot.key().name() + "@", Integer.toString(slot.index()));
        check(!consumer.contains("@"), "unresolved snapshot consumer metadata");
        Path base = Path.of("workspace/java-bridge/evidence/p3b/custom-java").toAbsolutePath(); Files.createDirectories(base);
        Path directory = Files.createTempDirectory(base, "run-"); Files.writeString(directory.resolve("Cases.iron"), source);
        var sources = new java.util.TreeMap<>(projected.declarations().sources());
        sources.put(generation.supportPackage().replace('.', '/') + "/Support.java", "package " + generation.supportPackage()
                + "; public final class Support { public static void " + projected.declarations().ensureMethod() + "() { throw new AssertionError(\"snapshot entered bootstrap\"); } }");
        sources.put("bridgeconsumer/SnapshotConsumer.java", consumer);
        Path javaHome = Path.of(System.getProperty("java.home")), classes = directory.resolve("classes");
        var javac = new ArrayList<>(List.of(javaHome.resolve("bin/javac").toString(), "--release", "21", "-Xlint:all", "-Werror", "-d", classes.toString()));
        for (var item : sources.entrySet()) {
            Path path = directory.resolve("sources").resolve(item.getKey()); Files.createDirectories(path.getParent()); Files.writeString(path, item.getValue()); javac.add(path.toString());
        }
        BridgeEntryTests.run(directory, javac, "javac");
        String output = BridgeEntryTests.run(directory, List.of(javaHome.resolve("bin/java").toString(), "-Xmx64m", "-cp", classes.toString(), "bridgeconsumer.SnapshotConsumer"), "consumer");
        check(output.endsWith("custom-snapshot-java-ok\n"), output);
        BridgeEntryTests.run(directory, List.of(javaHome.resolve("bin/java").toString(), "-Xmx32m", "-cp", classes.toString(),
                "bridgeconsumer.SnapshotConsumer", "pressure"), "consumer-oom");
        Path manifest = directory.resolve("MANIFEST.MF"), jar = directory.resolve("snapshots.jar");
        Files.writeString(manifest, "Manifest-Version: 1.0\nAutomatic-Module-Name: bridge.snapshots\n\n");
        BridgeEntryTests.run(directory, List.of(javaHome.resolve("bin/jar").toString(), "--create", "--file", jar.toString(),
                "--manifest", manifest.toString(), "-C", classes.toString(), "."), "jar");
        BridgeEntryTests.run(directory, List.of(javaHome.resolve("bin/java").toString(), "--module-path", jar.toString(),
                "-m", "bridge.snapshots/bridgeconsumer.SnapshotConsumer"), "consumer-module");
        try (var files = Files.walk(classes)) {
            var actual = files.filter(path -> path.toString().endsWith(".class")).map(path -> classes.relativize(path).toString().replace('/', '.').replace(".class", ""))
                    .filter(name -> !name.startsWith("bridgeconsumer.")).sorted().toList();
            check(actual.equals(projected.declarations().generatedTypes().stream().sorted().toList()), "snapshot generated class inventory mismatch");
        }
        Files.writeString(directory.resolve("identity.txt"), "generation=" + generation.identity() + "\nlayout=" + layout.slots() + "\n");
        System.out.println("custom Java snapshot evidence: " + directory);
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static final String CONSUMER = """
            package bridgeconsumer;
            import customsnap.Cases;
            import java.lang.reflect.*;
            import java.util.*;
            public final class SnapshotConsumer {
                private static Method graph;
                private static final int DETAIL = @customsnap.Cases$Detail@, UNCHECKED = @customsnap.Cases$Unchecked@;
                private static final class Input {
                    final int[] types, numbers, causes;
                    final String[] messages, first, second, third;
                    final int[][] secondary;
                    final StackTraceElement[][] frames;
                    final long[][] bits;
                    final String[][] texts;
                    Input(int... selected) {
                        types = selected; int count = selected.length;
                        numbers = new int[count]; causes = new int[count]; Arrays.fill(causes, -1);
                        messages = new String[count]; Arrays.fill(messages, "copied message");
                        first = new String[count]; second = new String[count]; third = new String[count];
                        secondary = new int[count][]; frames = new StackTraceElement[count][];
                        bits = new long[count][@SLOTS@]; texts = new String[count][@SLOTS@];
                        for (int i = 0; i < count; i++) {
                            secondary[i] = new int[0]; frames[i] = new StackTraceElement[]{new StackTraceElement("native.Source", "fail", "Source.iron", 31)};
                        }
                    }
                    Throwable[] assemble() throws Exception { return (Throwable[]) graph.invoke(null, types, messages, first, second, third, numbers, causes, secondary, frames, bits, texts); }
                }
                private static void invalid(Input input) throws Exception {
                    try { input.assemble(); throw new AssertionError("unrepresentable graph admitted"); }
                    catch (InvocationTargetException expected) { check(expected.getCause() instanceof LinkageError); }
                }
                private static void pressure(Input input) throws Throwable {
                    var handle = java.lang.invoke.MethodHandles.lookup().unreflect(graph);
                    Throwable[][] retained = new Throwable[100000][];
                    boolean failed = false;
                    for (int i = 0; i < retained.length; i++) {
                        try {
                            retained[i] = (Throwable[]) handle.invokeExact(input.types, input.messages, input.first, input.second,
                                    input.third, input.numbers, input.causes, input.secondary, input.frames, input.bits, input.texts);
                        } catch (OutOfMemoryError expected) { failed = true; break; }
                    }
                    Arrays.fill(retained, null);
                    System.gc();
                    check(failed);
                    check(input.assemble()[0].getMessage().equals(input.messages[0]));
                }
                public static void main(String[] args) throws Throwable {
                    graph = Class.forName("@SUPPORT@.ExceptionFactory").getDeclaredMethod("graph", int[].class, String[].class, String[].class,
                            String[].class, String[].class, int[].class, int[].class, int[][].class, StackTraceElement[][].class, long[][].class, String[][].class);
                    graph.setAccessible(true);
                    if (args.length != 0) {
                        int[] types = new int[32]; Arrays.fill(types, UNCHECKED);
                        pressure(new Input(types));
                        System.out.println("custom-snapshot-oom-ok");
                        return;
                    }
                    check(Modifier.isAbstract(Cases.Base.class.getModifiers()) && Cases.Detail.class.getSuperclass() == Cases.Base.class);
                    check(Arrays.equals(Cases.class.getDeclaredMethod("ping").getExceptionTypes(), new Class<?>[]{Cases.Base.class}));
                    for (var type : List.of(Cases.Base.class, Cases.Detail.class, Cases.Unchecked.class, Cases.PathProblem.class, Cases.ParseProblem.class, Cases.IoProblem.class)) {
                        check(type.getConstructors().length == 0);
                        check(Arrays.stream(type.getDeclaredMethods()).noneMatch(method -> Modifier.isNative(method.getModifiers()) || method.getName().equals("free")));
                    }
                    Input input = new Input(DETAIL, UNCHECKED); input.causes[0] = 1; input.secondary[0] = new int[]{1, 1};
                    input.bits[0][@getCode@] = 29; input.bits[0][@getMagnitude@] = Long.MIN_VALUE;
                    input.texts[0][@getBorrowed@] = "borrowed"; input.texts[0][@getCopy@] = "A\\u0000\\uD800B";
                    input.bits[1][@isReady@] = 1; input.bits[1][@getByte@] = -2; input.bits[1][@getShort@] = -3;
                    input.bits[1][@getUnit@] = 0xd800; input.bits[1][@getLong@] = Long.MIN_VALUE + 29; input.bits[1][@getFloat@] = 0x7fc12345;
                    Throwable[] values = input.assemble(); Cases.Detail detail = (Cases.Detail) values[0]; Cases.Unchecked unchecked = (Cases.Unchecked) values[1];
                    try { throw detail; } catch (Cases.Base expected) { check(expected == detail && expected.getCode() == 29); }
                    try { throw unchecked; } catch (RuntimeException expected) { check(expected == unchecked); }
                    check(detail.getMessage().equals("copied message") && detail.getCause() == unchecked && detail.getSuppressed().length == 2);
                    check(detail.getSuppressed()[0] == unchecked && detail.getSuppressed()[1] == unchecked);
                    check(detail.getBorrowed().equals("borrowed") && detail.getCopy().equals("A\\u0000\\uD800B"));
                    check(Double.doubleToRawLongBits(detail.getMagnitude()) == Long.MIN_VALUE);
                    check(unchecked.isReady() && unchecked.getByte() == -2 && unchecked.getShort() == -3 && unchecked.getUnit() == '\\uD800');
                    check(unchecked.getLong() == Long.MIN_VALUE + 29 && Float.floatToRawIntBits(unchecked.getFloat()) == 0x7fc12345);
                    check(detail.getStackTrace()[0].getClassName().equals("native.Source"));
                    check(Arrays.stream(detail.getStackTrace()).anyMatch(frame -> frame.getClassName().equals("bridgeconsumer.SnapshotConsumer")));
                    Input path = new Input(@customsnap.Cases$PathProblem@); path.bits[0][@getIndex@] = -99; path.texts[0][@getReason@] = "why";
                    var pathValue = (Cases.PathProblem) path.assemble()[0];
                    check(pathValue.getInput() == null && pathValue.getIndex() == -99 && pathValue.getReason().equals("why") && pathValue.getMessage().equals("copied message"));
                    Input parsed = new Input(@customsnap.Cases$ParseProblem@); parsed.bits[0][@getErrorIndex@] = -99;
                    var parsedValue = (Cases.ParseProblem) parsed.assemble()[0];
                    check(parsedValue.getParsedString() == null && parsedValue.getErrorIndex() == -99 && parsedValue.getMessage().equals("copied message"));
                    Input io = new Input(@customsnap.Cases$IoProblem@); check(((Cases.IoProblem) io.assemble()[0]).getCause() == null);
                    Input self = new Input(UNCHECKED); self.causes[0] = 0; check(self.assemble()[0].getCause() instanceof java.io.IOException);
                    Input narrow = new Input(DETAIL); narrow.causes[0] = -2; invalid(narrow);
                    check(input.assemble()[0] instanceof Cases.Detail);
                    System.out.println("custom-snapshot-java-ok");
                }
                private static void check(boolean value) { if (!value) throw new AssertionError(); }
            }
            """;
}
