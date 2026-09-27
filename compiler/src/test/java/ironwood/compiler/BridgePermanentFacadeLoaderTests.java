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
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Object projections must use the same complete preflight as the value producer. */
final class BridgePermanentFacadeLoaderTests {
    static final String NAME = "Java Bridge permanent facades reject colliding generations before extraction";
    private record Artifact(BridgeGeneration generation, BridgeJavaSources declarations, Path llvm, String adapters,
                            BridgePermanentNativeSources nativeSources) {}
    private BridgePermanentFacadeLoaderTests() {}

    static void loaders() throws Exception {
        BridgeGeneratedJarTests.target();
        Path base = Path.of("workspace/java-bridge/evidence/p3b/permanent-loaders").toAbsolutePath();
        Files.createDirectories(base);
        Path directory = Files.createTempDirectory(base, "run-");
        var inputs = BridgeProducerInputs.discover();
        var discovery = LlvmToolchain.discover(null); check(discovery.successful(), discovery.error());
        var toolchain = discovery.toolchain().orElseThrow();
        var artifacts = new java.util.LinkedHashMap<String, Artifact>();
        var fixtures = Map.of("a", List.of("objectshared.Common", "objectshared.OnlyA"),
                "b", List.of("objectshared.Common", "objectother.OnlyB"), "c", List.of("objectshared.OnlyC"),
                "d", List.of("objectdisjoint.OnlyD"));
        for (String id : List.of("a", "b", "c", "d")) {
            Path folder = directory.resolve(id); Files.createDirectories(folder);
            var sources = new ArrayList<SourceFile>();
            for (String type : fixtures.get(id)) {
                int split = type.lastIndexOf('.');
                String pkg = type.substring(0, split), name = type.substring(split + 1);
                String source = """
                        package %s;
                        public final class %s {
                            private static %s saved;
                            private final int value;
                            public %s(int value) { this.value = value; }
                            public %s publish() { saved = this; return this; }
                            public static %s recall() { return saved; }
                            public %s self() { return this; }
                            public %s pick(%s other) { return other; }
                            public int number() { return value + %d; }
                        }
                        """.formatted(pkg, name, name, name, name, name, name, name, name, (id.charAt(0) - 'a' + 1) * 111);
                Files.writeString(folder.resolve(name + ".iron"), source);
                sources.add(SourceFile.of(name + ".iron", source));
            }
            var artifact = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(sources);
            check(artifact.valid(), artifact.diagnostics().toString());
            var proof = BridgeObjectAdmission.prove(artifact, fixtures.get(id).stream().map(t -> t.substring(0, t.lastIndexOf('.'))).distinct().toList());
            check(proof.contract().isPresent(), proof.reason());
            var admission = proof.contract().orElseThrow(); check(admission.roots().isEmpty(), "fixture acquired root state");
            var generation = BridgeGeneration.createObjects(id + ".jar", artifact, admission,
                    inputs.compilerVersion(), inputs.compilerIdentity(), inputs.runtimeIdentity());
            var projected = BridgePermanentJavaSources.generate(artifact, admission, generation);
            var nativeSources = BridgePermanentNativeSources.generate(artifact, admission, generation, projected);
            String llvm = new LlvmEmitter().emit(admission.program());
            Path file = folder.resolve("program.ll"); Files.writeString(file, llvm);
            Files.writeString(folder.resolve("proof.txt"), "generation=" + generation.identity() + "\nllvm=" + digest(llvm)
                    + "\nadapters=" + digest(nativeSources.source()) + "\ncompiler=" + inputs.compilerIdentity()
                    + "\nruntime=" + inputs.runtimeIdentity() + "\n");
            artifacts.put(id, new Artifact(generation, projected.declarations(), file, nativeSources.source(), nativeSources));
        }
        Path javaHome = Path.of(System.getProperty("java.home"));
        for (var level : List.of(OptimizationLevel.O0, OptimizationLevel.O3)) {
            Path folder = directory.resolve(level.toString()); Files.createDirectories(folder);
            var generations = new StringBuilder();
            for (var item : artifacts.entrySet()) {
                String id = item.getKey(); var artifact = item.getValue();
                var generation = artifact.generation();
                var build = generation.nativeBuild(BridgeGeneratedJarTests.target(), Map.of("fixture", "permanent-loader", "optimization", level.toString(),
                        "llvm", digest(Files.readString(artifact.llvm())), "adapters", digest(artifact.adapters())));
                String nativeSource = artifact.adapters() + BridgeBootstrapSources.generate(generation, build, artifact.declarations(), artifact.nativeSources());
                Path jar = BridgeGeneratedJarTests.build(folder.resolve(id), artifact.llvm(), toolchain, level, generation, build,
                        artifact.declarations(), nativeSource, Map.of());
                Files.copy(jar, folder.resolve(id + ".jar"));
                generations.append(id).append('=').append(generation.identity()).append('\n');
            }
            Files.writeString(folder.resolve("generations.properties"), generations.toString());
            try (var b = new ZipFile(folder.resolve("b.jar").toFile())) {
                rewrite(folder.resolve("a.jar"), folder.resolve("mixed.jar"), "objectshared/Common.class",
                        b.getInputStream(b.getEntry("objectshared/Common.class")).readAllBytes());
            }
            var a = artifacts.get("a");
            var registration = a.declarations().facadeRegistrations().stream().filter(r -> r.binaryName().equals("objectshared.OnlyA")).findFirst().orElseThrow();
            String source = a.declarations().sources().get("objectshared/OnlyA.java");
            String before = "void " + registration.nativeName() + "(long ";
            check(source.indexOf(before) >= 0 && source.indexOf(before) == source.lastIndexOf(before), "constructor registration signature not unique");
            Path signatureSource = folder.resolve("signature-source/objectshared/OnlyA.java"); Files.createDirectories(signatureSource.getParent());
            // Preserve annotation and payload, but alter only the private cache-registration carrier.
            source = source.replace(before, "void " + registration.nativeName() + "(double ");
            Files.writeString(signatureSource, source);
            Path signatureClasses = folder.resolve("signature-classes");
            BridgeEntryTests.run(folder, List.of(javaHome.resolve("bin/javac").toString(), "--release", "21", "-Xlint:all", "-Werror", "-cp",
                    folder.resolve("a.jar").toString(), "-d", signatureClasses.toString(), signatureSource.toString()), "signature-javac");
            rewrite(folder.resolve("a.jar"), folder.resolve("signature.jar"), "objectshared/OnlyA.class", Files.readAllBytes(signatureClasses.resolve("objectshared/OnlyA.class")));
            Path consumer = folder.resolve("ObjectLoaderConsumer.java"); Files.writeString(consumer, CONSUMER);
            BridgeEntryTests.run(folder, List.of(javaHome.resolve("bin/javac").toString(), "--release", "21", "-Xlint:all", "-Werror", consumer.toString()), "consumer-javac");
            for (String scenario : List.of("ab-a", "ab-b", "ba-a", "ba-b", "ac-a", "ac-c", "ca-a", "ca-c", "disjoint", "mixed", "signature", "anchor")) {
                Path temporary = folder.resolve("tmp-" + scenario); Files.createDirectories(temporary);
                String output = BridgeEntryTests.run(folder, List.of(javaHome.resolve("bin/java").toString(), "-Xcheck:jni", "-Xmx64m",
                        "-Djava.io.tmpdir=" + temporary, "-cp", folder.toString(), "ObjectLoaderConsumer", folder.toString(), scenario), "consumer-" + scenario);
                check(output.endsWith("object-loader-ok:" + scenario + "\n") && !output.contains("WARNING") && !output.contains("FATAL"), output);
            }
        }
        System.out.println("permanent object loader evidence: " + directory);
    }

    private static void rewrite(Path original, Path target, String replacement, byte[] bytes) throws Exception {
        try (var input = new ZipFile(original.toFile()); var output = new ZipOutputStream(Files.newOutputStream(target))) {
            var entries = input.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement(); output.putNextEntry(new ZipEntry(entry.getName()));
                if (!entry.isDirectory()) output.write(entry.getName().equals(replacement) ? bytes : input.getInputStream(entry).readAllBytes());
                output.closeEntry();
            }
        }
        Files.writeString(target.resolveSibling(target.getFileName() + ".sha256"), BridgeGeneration.bytesDigest(Files.readAllBytes(target)) + "\n");
    }
    private static String digest(String text) { return BridgeGeneration.bytesDigest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }

    private static final String CONSUMER = """
            import java.lang.ref.WeakReference;
            import java.lang.reflect.InvocationTargetException;
            import java.net.URLClassLoader;
            import java.nio.file.*;
            import java.util.*;
            public final class ObjectLoaderConsumer {
                private static Path directory;
                private static final Properties generations = new Properties();
                private static URLClassLoader loader(String... ids) throws Exception {
                    var urls = new java.net.URL[ids.length];
                    for (int i = 0; i < ids.length; i++) urls[i] = directory.resolve(ids[i] + ".jar").toUri().toURL();
                    return new URLClassLoader(urls, ClassLoader.getPlatformClassLoader());
                }
                private static Object call(Object receiver, Class<?> type, String name, Class<?>[] signature, Object... args) throws Throwable {
                    var method = type.getDeclaredMethod(name, signature); method.setAccessible(true);
                    try { return method.invoke(receiver, args); } catch (InvocationTargetException failure) { throw failure.getCause(); }
                }
                private static String generation(String id) { return generations.getProperty(id.equals("mixed") || id.equals("signature") ? "a" : id); }
                private static Class<?> support(ClassLoader loader, String id) throws Exception {
                    return Class.forName("ironwood.bridge.generated.g" + generation(id) + ".Support", true, loader);
                }
                private static void ensure(ClassLoader loader, String id) throws Throwable {
                    Class<?> type = support(loader, id);
                    var method = Arrays.stream(type.getDeclaredMethods()).filter(m -> m.getName().startsWith("$ironwood$ensure")).findFirst().orElseThrow();
                    call(null, type, method.getName(), new Class<?>[0]);
                }
                private static void refuse(ClassLoader loader, String id, String text) throws Throwable {
                    try { ensure(loader, id); throw new AssertionError("missing refusal " + id); }
                    catch (LinkageError expected) { check(expected.getMessage().contains(text)); System.out.println("refusal:" + expected.getMessage()); }
                }
                private static void noImage(String id) throws Exception {
                    try (var files = Files.walk(Path.of(System.getProperty("java.io.tmpdir")))) {
                        check(files.noneMatch(p -> p.toString().contains(generation(id))));
                    }
                }
                private static String type(String id) {
                    return switch (id) { case "a" -> "objectshared.OnlyA"; case "b" -> "objectother.OnlyB";
                        case "c" -> "objectshared.OnlyC"; case "d" -> "objectdisjoint.OnlyD"; default -> throw new AssertionError(id); };
                }
                private static Object create(ClassLoader loader, String id) throws Throwable {
                    Class<?> type = Class.forName(type(id), true, loader);
                    Object facade = type.getConstructor(int.class).newInstance(7);
                    check(call(facade, type, "publish", new Class<?>[0]) == facade);
                    verify(facade, id); return facade;
                }
                private static void verify(Object facade, String id) throws Throwable {
                    Class<?> type = facade.getClass();
                    check(call(facade, type, "number", new Class<?>[0]).equals(7 + (id.charAt(0) - 'a' + 1) * 111));
                    check(call(facade, type, "self", new Class<?>[0]) == facade);
                    check(call(null, type, "recall", new Class<?>[0]) == facade);
                    check(call(facade, type, "pick", new Class<?>[]{type}, facade) == facade);
                    check(call(facade, type, "pick", new Class<?>[]{type}, new Object[]{null}) == null);
                }
                private static WeakReference<ClassLoader> anchored() throws Throwable {
                    var loader = loader("a"); create(loader, "a"); ensure(loader, "a"); ensure(loader, "a");
                    loader.close(); return new WeakReference<>(loader);
                }
                public static void main(String[] args) throws Throwable {
                    directory = Path.of(args[0]); String scenario = args[1];
                    try (var input = Files.newInputStream(directory.resolve("generations.properties"))) { generations.load(input); }
                    if (scenario.matches("[abc]{2}-[abc]")) {
                        String winner = scenario.substring(0, 1), loser = scenario.substring(1, 2), first = scenario.substring(3);
                        try (var loader = loader(winner, loser)) {
                            if (first.equals(loser)) refuse(loader, loser, "expected");
                            Object facade = create(loader, winner);
                            refuse(loader, loser, "expected"); refuse(loader, loser, "expected"); noImage(loser);
                            verify(facade, winner); ensure(loader, winner); verify(facade, winner);
                        }
                    } else if (scenario.equals("disjoint")) {
                        try (var loader = loader("a", "d")) { Object a = create(loader, "a"), d = create(loader, "d"); verify(a, "a"); verify(d, "d"); }
                    } else if (scenario.equals("mixed") || scenario.equals("signature")) {
                        try (var loader = loader(scenario, "d")) {
                            Object d = create(loader, "d");
                            refuse(loader, scenario, scenario.equals("mixed") ? "expected" : "signature mismatch");
                            noImage(scenario); verify(d, "d");
                        }
                    } else if (scenario.equals("anchor")) {
                        var weak = anchored();
                        for (int i = 0; i < 8; i++) { System.gc(); Thread.sleep(25); }
                        check(weak.get() != null);
                        try (var other = loader("a")) { refuse(other, "a", "already loaded in another classloader"); }
                        ClassLoader retained = weak.get();
                        Class<?> type = Class.forName(type("a"), true, retained);
                        Object facade = call(null, type, "recall", new Class<?>[0]); verify(facade, "a");
                    } else throw new AssertionError(scenario);
                    System.out.println("object-loader-ok:" + scenario);
                }
                private static void check(boolean value) { if (!value) throw new AssertionError(); }
            }
            """;
}
