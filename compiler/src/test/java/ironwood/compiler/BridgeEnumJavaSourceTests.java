// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.bridge.*;
import ironwood.compiler.source.SourceFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class BridgeEnumJavaSourceTests {
    static final String NAME = "Java Bridge generated enums preserve Java metadata and exact native dispatch declarations";
    static final String SOURCE = """
            package enumjava;
            public enum Mode {
                SELL { @Override public int code() { return 29; } @Override public String toString() { return "sold"; } }, BUY;
                public int code() { return 11; }
                public Mode self() { return this; }
                public static Mode choose(Mode value) { return value; }
                public static boolean absent(Empty value) { return value == null; }
                public enum Empty { ; public static Mode unseen() { return null; } }
            }
            """;
    private BridgeEnumJavaSourceTests() {}

    static void declarations() throws Exception {
        var artifact = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(List.of(SourceFile.of("Mode.iron", SOURCE)));
        check(artifact.valid(), artifact.diagnostics().toString());
        var proof = BridgeObjectAdmission.prove(artifact, List.of("enumjava"));
        check(proof.contract().isPresent(), proof.reason());
        var admission = proof.contract().orElseThrow();
        var generation = BridgeGeneration.createObjects("enums.jar", artifact, admission, "test", "1".repeat(64), "2".repeat(64));
        var projected = BridgePermanentJavaSources.generate(artifact, admission, generation);
        check(projected.facades().isEmpty() && projected.enums().size() == 2, "enum acquired facade storage");
        check(projected.declarations().sources().keySet().stream().noneMatch(path -> path.endsWith("/PermanentCache.java")), "enum acquired weak cache");
        try {
            BridgePermanentNativeSources.generate(artifact, admission, generation,
                    new BridgePermanentJavaSources.Sources(projected.declarations(), projected.facades()));
            throw new AssertionError("missing enum token metadata admitted");
        } catch (IllegalArgumentException expected) { check(expected.getMessage().contains("exact final admission"), expected.getMessage()); }
        Path base = Path.of("workspace/java-bridge/evidence/p3b/enum-java").toAbsolutePath(); Files.createDirectories(base);
        Path directory = Files.createTempDirectory(base, "run-");
        Files.writeString(directory.resolve("Mode.iron"), SOURCE);
        var sources = new java.util.TreeMap<>(projected.declarations().sources());
        sources.put(generation.supportPackage().replace('.', '/') + "/Support.java", "package " + generation.supportPackage()
                + "; public final class Support { public static int entered; public static void " + projected.declarations().ensureMethod() + "() { entered++; } }");
        String consumer = CONSUMER.replace("SUPPORT", generation.supportPackage() + ".Support")
                .replace("TOKEN", projected.enums().stream().filter(type -> type.binaryName().equals("enumjava.Mode")).findFirst().orElseThrow().tokenField());
        sources.put("EnumJavaConsumer.java", consumer);
        Path javaHome = Path.of(System.getProperty("java.home")), classes = directory.resolve("classes");
        var javac = new ArrayList<>(List.of(javaHome.resolve("bin/javac").toString(), "--release", "21", "-Xlint:all", "-Werror", "-d", classes.toString()));
        for (var source : sources.entrySet()) {
            Path path = directory.resolve("sources").resolve(source.getKey()); Files.createDirectories(path.getParent());
            Files.writeString(path, source.getValue()); javac.add(path.toString());
        }
        BridgeEntryTests.run(directory, javac, "javac");
        String output = BridgeEntryTests.run(directory, List.of(javaHome.resolve("bin/java").toString(), "-cp", classes.toString(), "EnumJavaConsumer"), "consumer");
        check(output.endsWith("enum-java-ok\n"), output);
        try (var files = Files.walk(classes)) {
            var actual = files.filter(path -> path.toString().endsWith(".class")).map(path -> classes.relativize(path).toString().replace('/', '.').replace(".class", ""))
                    .filter(name -> !name.equals("EnumJavaConsumer")).sorted().toList();
            check(actual.equals(projected.declarations().generatedTypes().stream().sorted().toList()), "uninventoried generated enum helper: " + actual);
        }
        Files.writeString(directory.resolve("generation.txt"), generation.identity() + "\n");
        System.out.println("generated Java enum evidence: " + directory);
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static final String CONSUMER = """
            import enumjava.Mode;
            import java.lang.reflect.Modifier;
            public final class EnumJavaConsumer {
                public static void main(String[] args) throws Exception {
                    check(Mode.class.isEnum() && Mode.Empty.class.isEnum() && Mode.Empty.values().length == 0);
                    check(Mode.values()[0] == Mode.SELL && Mode.values()[1] == Mode.BUY);
                    check(Mode.SELL.name().equals("SELL") && Mode.SELL.ordinal() == 0 && Mode.BUY.ordinal() == 1);
                    check(Mode.BUY.toString().equals("BUY") && Mode.valueOf("SELL") == Mode.SELL);
                    check(Mode.SELL.compareTo(Mode.BUY) < 0 && Mode.SELL.equals(Mode.SELL) && !Mode.SELL.equals(null));
                    var values = Mode.values(); values[0] = Mode.BUY; check(Mode.values()[0] == Mode.SELL);
                    var set = java.util.EnumSet.allOf(Mode.class); check(set.size() == 2 && set.remove(Mode.SELL));
                    var token = Mode.class.getDeclaredField("TOKEN"); token.setAccessible(true);
                    check(Modifier.isPrivate(token.getModifiers()) && Modifier.isFinal(token.getModifiers()));
                    check(token.getInt(Mode.SELL) == 1 && token.getInt(Mode.BUY) == 0);
                    check(SUPPORT.entered == 0);
                    try { Mode.SELL.code(); throw new AssertionError(); } catch (UnsatisfiedLinkError expected) { check(SUPPORT.entered == 1); }
                    try { Mode.BUY.code(); throw new AssertionError(); } catch (UnsatisfiedLinkError expected) { check(SUPPORT.entered == 2); }
                    try { Mode.SELL.toString(); throw new AssertionError(); } catch (UnsatisfiedLinkError expected) { check(SUPPORT.entered == 3); }
                    check(Mode.BUY.toString().equals("BUY") && SUPPORT.entered == 3);
                    try { Mode.choose(null); throw new AssertionError(); } catch (UnsatisfiedLinkError expected) { check(SUPPORT.entered == 4); }
                    System.out.println("enum-java-ok");
                }
                private static void check(boolean value) { if (!value) throw new AssertionError(); }
            }
            """;
}
