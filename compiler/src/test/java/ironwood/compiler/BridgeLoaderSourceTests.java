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
                Class<?> engine = Class.forName("loaderpreview.Engine", false, first);
                Class<?>[] nativeCheck = {ClassLoader.class, Class.class, String.class, String.class, String[].class};
                String[] signatures = {declarations.bindings().getFirst().nativeName() + declarations.bindings().getFirst().descriptor()};
                invoke(firstSupport, "nativePreflight", nativeCheck, first, engine, engine.getName(), generation.identity(), signatures);
                expectFailure(firstSupport, "nativePreflight", nativeCheck, LinkageError.class, "expected",
                        first, engine, "other.Engine", generation.identity(), signatures);
                expectFailure(firstSupport, "nativePreflight", nativeCheck, LinkageError.class, "expected",
                        first, engine, engine.getName(), "native-payload-mismatch", signatures);
                expectFailure(firstSupport, "nativePreflight", nativeCheck, LinkageError.class, "native signature mismatch",
                        first, engine, engine.getName(), generation.identity(), new String[]{"wrong()V"});
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
            targets(directory, generation, declarations);
        } finally {
            System.setProperty("java.io.tmpdir", temporaryDirectory);
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void targets(Path directory, BridgeGeneration generation, BridgeJavaSources declarations) throws Exception {
        byte[] image = "target image extraction fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] dependency = "private runtime extraction fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String dependencyPath = ".support-test/lib/libstdc++.so.6";
        var payloads = new ArrayList<BridgeLoaderSources.Payload>();
        for (String target : List.of("macos-arm64", "linux-arm64", "linux-x86_64")) {
            payloads.add(new BridgeLoaderSources.Payload(generation.nativeBuild(target, Map.of("fixture", "targets")),
                    target.startsWith("macos") ? "11.0" : "2.17", BridgeGeneration.bytesDigest(image),
                    target.startsWith("macos") ? Map.of() : Map.of(dependencyPath, BridgeGeneration.bytesDigest(dependency))));
        }
        for (String bad : List.of("../outside", "/absolute", "foo/../outside", "foo//bar", "foo/.", "libbridge.so", "foo\\bar")) {
            try {
                new BridgeLoaderSources.Payload(payloads.get(1).build(), "2.17", BridgeGeneration.bytesDigest(image), Map.of(bad, BridgeGeneration.bytesDigest(dependency)));
                throw new AssertionError("unsafe dependency path accepted: " + bad);
            } catch (IllegalArgumentException expected) { check(expected.getMessage().contains("dependency"), expected.getMessage()); }
        }
        try {
            BridgeLoaderSources.generate(generation, declarations, List.of(payloads.getFirst(), payloads.getFirst()));
            throw new AssertionError("duplicate target accepted");
        } catch (IllegalArgumentException expected) { check(expected.getMessage().contains("duplicate"), expected.getMessage()); }
        var sources = new java.util.TreeMap<>(declarations.sources());
        sources.put(generation.supportPackage().replace('.', '/') + "/Support.java", BridgeLoaderSources.generate(generation, declarations, payloads));
        Path classes = compile(directory.resolve("targets"), sources);
        for (var payload : payloads) {
            Path root = classes.resolve("META-INF/ironwood/native/" + payload.build().target() + "/" + generation.identity());
            Files.createDirectories(root); Files.write(root.resolve(payload.filename()), image);
            if (!payload.dependencies().isEmpty()) {
                Files.createDirectories(root.resolve(dependencyPath).getParent()); Files.write(root.resolve(dependencyPath), dependency);
            }
        }
        String os = System.getProperty("os.name"), arch = System.getProperty("os.arch"), version = System.getProperty("os.version"), bits = System.getProperty("sun.arch.data.model");
        try (var loader = loader(classes)) {
            Class<?> support = Class.forName(generation.supportPackage() + ".Support", true, loader);
            for (String[] host : List.of(new String[]{"Mac OS X", "aarch64", "macos-arm64"},
                    new String[]{"Linux", "aarch64", "linux-arm64"}, new String[]{"Linux", "amd64", "linux-x86_64"})) {
                System.setProperty("os.name", host[0]); System.setProperty("os.arch", host[1]); System.setProperty("os.version", "26.0");
                String[] selected = (String[]) invoke(support, "requireHost", new Class<?>[0]); check(selected[0].equals(host[2]), "wrong selected target");
                Path cache = directory.resolve("cache-" + host[2]); Files.createDirectory(cache); System.setProperty("java.io.tmpdir", cache.toString());
                Path extracted = extract(support);
                check(java.util.Arrays.equals(image, Files.readAllBytes(extracted)) && extracted.getParent().getFileName().toString().equals(host[2]), "wrong extracted target");
                if (host[0].equals("Linux")) {
                    Path library = extracted.getParent().resolve(dependencyPath);
                    check(java.util.Arrays.equals(dependency, Files.readAllBytes(library)), "dependency bytes changed");
                    Files.writeString(library, "corrupted dependency");
                    expectFailure(support, "extract", new Class<?>[0], java.io.IOException.class, "digest mismatch");
                    Files.delete(library); Files.createSymbolicLink(library, classes.resolve("META-INF/ironwood/native/" + host[2] + "/" + generation.identity() + "/" + dependencyPath));
                    expectFailure(support, "extract", new Class<?>[0], java.io.IOException.class, "unsafe Ironwood extraction file");
                }
            }
            for (String[] host : List.of(new String[]{"Windows", "amd64", "26.0", "64"},
                    new String[]{"Mac OS X", "aarch64", "10.15", "64"}, new String[]{"Linux", "aarch64", "6.8", "32"},
                    new String[]{"Linux", "riscv64", "6.8", "64"})) {
                System.setProperty("os.name", host[0]); System.setProperty("os.arch", host[1]);
                System.setProperty("os.version", host[2]); System.setProperty("sun.arch.data.model", host[3]);
                expectFailure(support, "requireHost", new Class<?>[0], UnsatisfiedLinkError.class, "available targets");
            }
        } finally {
            System.setProperty("os.name", os); System.setProperty("os.arch", arch); System.setProperty("os.version", version);
            System.setProperty("sun.arch.data.model", bits);
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
