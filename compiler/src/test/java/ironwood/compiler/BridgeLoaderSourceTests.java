// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.bridge.*;
import ironwood.compiler.source.SourceFile;

import java.lang.reflect.InvocationTargetException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

final class BridgeLoaderSourceTests {
    static final String NAME = "Java Bridge generated loader validates metadata versions and canonical extraction";

    private BridgeLoaderSourceTests() {}

    static void sourceAndExtraction() throws Exception {
        var compiler = new CompilerPipeline(UnfreedMode.OFF);
        var artifact = compiler.analyzeForBridge(List.of(SourceFile.of("Engine.iron", """
                package loaderpreview;
                public final class Engine {
                    private Engine() {}
                    public static int value(int left, int right) { return left + right; }
                }
                """)));
        check(artifact.valid(), artifact.diagnostics().toString());
        var surface = BridgeExportSurface.scalarPreview(artifact, List.of("loaderpreview")).surface().orElseThrow();
        var generation = BridgeGeneration.create("loader.jar", artifact, surface, "test", "1".repeat(64), "2".repeat(64));
        var declarations = BridgeJavaSources.generate(artifact, surface, generation, BridgeEntryModule.scalars(artifact, surface.roots()));
        byte[] content = "not a native library: extraction-only test".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var payload = new BridgeLoaderSources.Payload(generation.nativeBuild("macos-arm64", Map.of("fixture", "source-only")),
                "11.0", BridgeGeneration.bytesDigest(content));
        String support = BridgeLoaderSources.generate(generation, declarations, payload);
        Path directory = Files.createTempDirectory("bridge loader sources ");
        String temporaryDirectory = System.getProperty("java.io.tmpdir");
        try {
            var sources = new java.util.TreeMap<>(declarations.sources());
            sources.put(generation.supportPackage().replace('.', '/') + "/Support.java", support);
            Path classes = compile(directory.resolve("valid"), sources);
            Path resource = classes.resolve("META-INF/ironwood/native/macos-arm64/" + generation.identity() + "/libbridge.dylib");
            Files.createDirectories(resource.getParent());
            Files.write(resource, content);
            Path cache = directory.resolve("cache");
            Files.createDirectory(cache);
            System.setProperty("java.io.tmpdir", cache.toString());
            try (var first = loader(classes); var second = loader(classes)) {
                var firstSupport = Class.forName(generation.supportPackage() + ".Support", true, first);
                var secondSupport = Class.forName(generation.supportPackage() + ".Support", true, second);
                for (int feature : new int[]{0, 17, 20, 21, 22, 23, 24, 25, Integer.MAX_VALUE}) {
                    check(invoke(firstSupport, "supportedVersion", new Class<?>[]{int.class}, feature)
                                    .equals(feature >= 21 && feature <= 23), "version predicate admitted " + feature);
                }
                check(invoke(firstSupport, "atLeast", new Class<?>[]{String.class, String.class}, "26.6.2", "11.0").equals(true)
                        && invoke(firstSupport, "atLeast", new Class<?>[]{String.class, String.class}, "10.15", "11.0").equals(false)
                        && invoke(firstSupport, "atLeast", new Class<?>[]{String.class, String.class}, "unknown", "11.0").equals(false),
                        "minimum OS predicate changed");
                Class<?>[] checked = (Class<?>[]) invoke(firstSupport, "preflight", new Class<?>[]{ClassLoader.class}, first);
                check(checked.length == declarations.generatedTypes().size(), "incomplete class preflight");
                try (var entries = Files.list(cache)) { check(entries.findAny().isEmpty(), "preflight extracted native data"); }
                // Race independent defining loaders. Only private Java extraction is
                // invoked here; these fake bytes must never reach System.load.
                var one = java.util.concurrent.CompletableFuture.supplyAsync(() -> extract(firstSupport));
                var two = java.util.concurrent.CompletableFuture.supplyAsync(() -> extract(secondSupport));
                Path image = one.get();
                check(image.equals(two.get()), "independent loaders acquired duplicate image paths");
                check(java.util.Arrays.equals(Files.readAllBytes(image), content), "extraction changed bytes");
                check(Files.getPosixFilePermissions(image).equals(PosixFilePermissions.fromString("rw-------")), "unsafe file mode");
                check(Files.getPosixFilePermissions(image.getParent()).equals(PosixFilePermissions.fromString("rwx------")), "unsafe directory mode");
                Files.writeString(image.getParent().resolve("ignored.partial"), "stale unrelated partial");
                check(extract(secondSupport).equals(image), "stale partial affected image selection");
                Files.writeString(image, "corrupt existing image");
                expectFailure(firstSupport, "extract", new Class<?>[0], java.io.IOException.class, "digest mismatch");
                check(Files.readString(image).equals("corrupt existing image"), "failed extraction repaired/replaced existing image");
                Files.delete(image);
                Files.createSymbolicLink(image, resource);
                expectFailure(firstSupport, "extract", new Class<?>[0], java.io.IOException.class, "unsafe Ironwood extraction file");
                Files.delete(image);
                Files.setPosixFilePermissions(image.getParent(), PosixFilePermissions.fromString("rwxr-x---"));
                expectFailure(firstSupport, "extract", new Class<?>[0], java.io.IOException.class, "unsafe Ironwood extraction directory");
                Files.setPosixFilePermissions(image.getParent(), PosixFilePermissions.fromString("rwx------"));
            }
            for (String scenario : List.of("identity", "marker", "signature", "missing")) {
                var altered = new java.util.TreeMap<>(sources);
                String name = scenario.equals("marker") ? "loaderpreview/_IronwoodBridgePackage.java" : "loaderpreview/Engine.java";
                if (scenario.equals("signature")) {
                    altered.put(name, altered.get(name).replace("private static native int $ironwood$native$0(int left", "private static native int $ironwood$native$0(long left"));
                } else if (scenario.equals("missing")) {
                    altered.remove("loaderpreview/_IronwoodBridgePackage.java");
                } else {
                    altered.put(name, altered.get(name).replace("\"" + generation.identity() + "\"", "\"different-generation\""));
                }
                Path invalid = compile(directory.resolve(scenario), altered);
                try (var invalidLoader = loader(invalid)) {
                    var type = Class.forName(generation.supportPackage() + ".Support", true, invalidLoader);
                    expectFailure(type, "preflight", new Class<?>[]{ClassLoader.class},
                            scenario.equals("missing") ? ClassNotFoundException.class : LinkageError.class,
                            scenario.equals("signature") ? "native signature mismatch" : scenario.equals("missing")
                                    ? "_IronwoodBridgePackage" : "observed different-generation", invalidLoader);
                }
            }
        } finally {
            System.setProperty("java.io.tmpdir", temporaryDirectory);
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static Path compile(Path directory, Map<String, String> sources) throws Exception {
        Path classes = directory.resolve("classes");
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "javac").toString(),
                "--release", "21", "-Xlint:all", "-Werror", "-d", classes.toString()));
        for (var entry : sources.entrySet()) {
            Path file = directory.resolve("sources").resolve(entry.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue());
            command.add(file.toString());
        }
        BridgeEntryTests.run(directory, command, "javac");
        return classes;
    }

    private static URLClassLoader loader(Path classes) throws Exception {
        return new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
    }

    private static Object invoke(Class<?> type, String name, Class<?>[] parameters, Object... arguments) throws Exception {
        var method = type.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method.invoke(null, arguments);
    }

    private static Path extract(Class<?> type) {
        try { return (Path) invoke(type, "extract", new Class<?>[0]); }
        catch (Exception failure) { throw new AssertionError(failure); }
    }

    private static void expectFailure(Class<?> type, String name, Class<?>[] parameters,
            Class<? extends Throwable> expected, String message, Object... arguments) throws Exception {
        try {
            invoke(type, name, parameters, arguments);
            throw new AssertionError("expected failure from " + name);
        } catch (InvocationTargetException failure) {
            check(expected.isInstance(failure.getCause()) && failure.getCause().getMessage().contains(message),
                    "unexpected failure: " + failure.getCause());
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
