// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.bridge.BridgeEntryModule;
import ironwood.compiler.bridge.BridgeExportSurface;
import ironwood.compiler.bridge.BridgeGeneration;
import ironwood.compiler.bridge.BridgeJavaSources;
import ironwood.compiler.source.SourceFile;

import java.lang.invoke.MethodType;
import java.lang.reflect.Modifier;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class BridgeJavaSourceTests {
    static final String NAME = "Java Bridge generated Java preserves signatures constants and private JNI entries";
    private static final String SOURCE = """
            package generatedapi;
            public final class Engine {
                private Engine() {}
                public static final byte BYTE = -128;
                public static final short SHORT = -32768;
                public static final char CHAR = '\\uFFFF';
                public static final long LONG = -9223372036854775807L - 1L;
                public static final boolean TRUE = true;
                public static final float FLOAT = -0.0f;
                public static final double DOUBLE = -0.0d;
                public static final int ironwood = 1;
                public static final int java = 2;
                public static final float INFINITY = 1.0f / 0.0f;
                public static final double NAN = 0.0d / 0.0d;
                public static final String TEXT = "A\\n" + '\\u0000' + '\\uD800' + '\\uDFFF';
                public static int sum(int left, int right) throws Exception { return left + right; }
                public static long sum(long left, long right) { return left + right; }
                public static int length(String input) { return input == null ? -1 : input.length(); }
                public static void all(boolean flag, byte small, short medium, char character,
                        int number, long wide, float single, double real) {}
                public static int $ironwood$native$0() { return 7; }
                public static void $ironwood$ensure() {}
                public static final class Nested {
                    private Nested() {}
                    public static boolean value() { return true; }
                }
            }
            """;

    private BridgeJavaSourceTests() {}

    static void declarations() throws Exception {
        var compiler = new CompilerPipeline(UnfreedMode.OFF);
        var artifact = compiler.analyzeForBridge(List.of(SourceFile.of("Engine.iron", SOURCE)));
        check(artifact.valid(), artifact.diagnostics().toString());
        var selection = BridgeExportSurface.scalarValues(artifact, List.of("generatedapi"));
        check(selection.surface().isPresent(), selection.diagnostics().toString());
        var surface = selection.surface().orElseThrow();
        var generation = BridgeGeneration.create("api.jar", artifact, surface, "test", "1".repeat(64), "2".repeat(64));
        var module = BridgeEntryModule.copiedStrings(artifact, surface.roots());
        var generated = BridgeJavaSources.generate(artifact, surface, generation, module);
        check(generated.bindings().size() == 7, "missing native declaration");
        check(generated.sources().values().stream().noneMatch(source -> source.contains("public static native")),
                "raw JNI entry exposed publicly");
        Path directory = Files.createTempDirectory("bridge Java declarations ");
        try {
            var files = new ArrayList<String>();
            for (var entry : generated.sources().entrySet()) {
                Path file = directory.resolve("sources").resolve(entry.getKey());
                Files.createDirectories(file.getParent());
                Files.writeString(file, entry.getValue());
                files.add(file.toString());
            }
            Path support = directory.resolve("sources").resolve(generation.supportPackage().replace('.', '/') + "/Support.java");
            // Explicit test-only stub. Production generation deliberately does not
            // provide one and cannot package an apparently usable inert bridge.
            Files.writeString(support, "package " + generation.supportPackage() + ";\n@Identity(\"" + generation.identity()
                    + "\") public final class Support { public static int calls; public static void "
                    + generated.ensureMethod() + "() { calls++; } }");
            files.add(support.toString());
            String tricky = "\"\\u000a\n\r\t\b\f\u0000\u001f\u007f\u00e9\ud800\udfff";
            Path literal = directory.resolve("sources/Literal.java");
            Files.writeString(literal, "public final class Literal { public static final String VALUE = "
                    + BridgeJavaSources.quote(tricky) + "; }");
            files.add(literal.toString());
            Path classes = directory.resolve("classes");
            var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "javac").toString(),
                    "--release", "21", "-parameters", "-Xlint:all", "-Werror", "-d", classes.toString()));
            command.addAll(files);
            BridgeEntryTests.run(directory, command, "javac");
            try (var loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
                var engine = Class.forName("generatedapi.Engine", false, loader);
                var identity = Class.forName(generation.supportPackage() + ".Identity", false, loader);
                for (String name : generated.generatedTypes()) {
                    var type = Class.forName(name, false, loader);
                    var annotation = java.util.Arrays.stream(type.getDeclaredAnnotations())
                            .filter(value -> value.annotationType() == identity).findFirst().orElseThrow();
                    check(identity.getMethod("value").invoke(annotation).equals(generation.identity()), "class identity lost: " + name);
                }
                for (var binding : generated.bindings()) {
                    var type = Class.forName(binding.binaryName(), false, loader);
                    var nativeMethod = java.util.Arrays.stream(type.getDeclaredMethods())
                            .filter(method -> method.getName().equals(binding.nativeName())).findFirst().orElseThrow();
                    check(Modifier.isPrivate(nativeMethod.getModifiers()) && Modifier.isStatic(nativeMethod.getModifiers())
                            && Modifier.isNative(nativeMethod.getModifiers()), "native entry access changed");
                    check(MethodType.methodType(nativeMethod.getReturnType(), nativeMethod.getParameterTypes())
                            .toMethodDescriptorString().equals(binding.descriptor()), "JNI descriptor differs from javac output");
                    var api = type.getDeclaredMethod(binding.method().name(), nativeMethod.getParameterTypes());
                    check(Modifier.isPublic(api.getModifiers()) && !Modifier.isNative(api.getModifiers()), "public facade wrapper missing");
                    check(java.util.Arrays.stream(api.getParameters()).map(parameter -> parameter.getName()).toList()
                            .equals(binding.method().parameterNames()), "source parameter names changed");
                }
                check(engine.getDeclaredMethod("sum", int.class, int.class).getExceptionTypes()[0] == Exception.class,
                        "checked declaration changed");
                check(java.util.Arrays.stream(engine.getDeclaredConstructors()).allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers())),
                        "unimplemented public constructor exposed");
                var supportType = Class.forName(generation.supportPackage() + ".Support", true, loader);
                check(supportType.getField("calls").getInt(null) == 0, "metadata inspection initialized facade");
                check(engine.getField("BYTE").getByte(null) == -128 && engine.getField("SHORT").getShort(null) == -32768
                        && engine.getField("CHAR").getChar(null) == '\uffff' && engine.getField("LONG").getLong(null) == Long.MIN_VALUE
                        && engine.getField("TRUE").getBoolean(null), "primitive constants changed");
                check(Float.floatToRawIntBits(engine.getField("FLOAT").getFloat(null)) == Float.floatToRawIntBits(-0.0f)
                        && Double.doubleToRawLongBits(engine.getField("DOUBLE").getDouble(null)) == Double.doubleToRawLongBits(-0.0d),
                        "negative zero constants changed");
                check(engine.getField("TEXT").get(null).equals("A\n\u0000\ud800\udfff"), "UTF-16 constant changed");
                check(engine.getField("INFINITY").getFloat(null) == Float.POSITIVE_INFINITY
                        && Double.isNaN(engine.getField("NAN").getDouble(null)), "nonfinite constants changed");
                check(Class.forName("Literal", true, loader).getField("VALUE").get(null).equals(tricky), "Java literal escaping changed content");
                check(supportType.getField("calls").getInt(null) == 1, "facade bootstrap was not one-time");
                Class.forName("generatedapi.Engine$Nested", true, loader);
                check(supportType.getField("calls").getInt(null) == 2, "nested facade did not bootstrap independently");
            }
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
