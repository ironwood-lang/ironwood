// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.backend.LlvmEmitter;
import ironwood.compiler.backend.LlvmToolchain;
import ironwood.compiler.backend.NativeBackend;
import ironwood.compiler.backend.OptimizationLevel;
import ironwood.compiler.bridge.BridgeCallableId;
import ironwood.compiler.bridge.BridgeEntryModule;
import ironwood.compiler.bridge.BridgeRootSet;
import ironwood.compiler.source.SourceFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class BridgeLoaderTests {
    private record Artifact(String id, int value, List<String> facades) {
        String generation() { return "p0-" + id + "-generation-" + value; }
    }
    private static final List<Artifact> ARTIFACTS = List.of(
            new Artifact("a", 111, List.of("api.shared.Common", "api.shared.OnlyA")),
            new Artifact("b", 222, List.of("api.shared.Common", "api.other.OnlyB")),
            new Artifact("c", 333, List.of("api.shared.OnlyC")),
            new Artifact("d", 444, List.of("api.disjoint.OnlyD")));

    private BridgeLoaderTests() {}

    static void lifecycle() throws Exception {
        var discovery = LlvmToolchain.discover(null);
        check(discovery.successful(), discovery.error());
        var toolchain = discovery.toolchain().orElseThrow();
        Path base = Path.of("workspace/java-bridge/evidence/p0b/loaders").toAbsolutePath();
        Files.createDirectories(base);
        Path directory = Files.createTempDirectory(base, "run-");
        Path javaHome = Path.of(System.getProperty("java.home"));
        boolean mac = System.getProperty("os.name").startsWith("Mac");
        String extension = mac ? ".dylib" : ".so";
        Path driver = directory.resolve("BridgeLoaderDriver.java");
        Files.writeString(driver, DRIVER);
        BridgeEntryTests.run(directory, List.of(javaHome.resolve("bin/javac").toString(), "--release", "21", driver.toString()), "driver-javac");
        Path inspector = directory.resolve("inspector.c");
        Files.writeString(inspector, BridgeLoaderFixtureSources.INSPECTOR);
        Path inspectorImage = directory.resolve("inspector" + extension);
        List<String> inspectorCommand = new ArrayList<>(List.of(toolchain.clang().toString(), "-std=c11", "-fPIC", "-O3",
                "-I" + javaHome.resolve("include"), "-I" + javaHome.resolve(mac ? "include/darwin" : "include/linux"),
                inspector.toString(), mac ? "-dynamiclib" : "-shared", "-o", inspectorImage.toString()));
        if (!mac) inspectorCommand.add("-ldl");
        BridgeEntryTests.run(directory, inspectorCommand, "inspector-clang");
        for (Artifact artifact : ARTIFACTS) {
            prepareArtifact(directory, artifact, javaHome);
            Path folder = directory.resolve(artifact.id());
            String source = "package native" + artifact.id() + "; final class Engine { static int initialized; "
                    + "static int offset = initialize(); static int initialize() { initialized++; return " + artifact.value() + "; } "
                    + "static int value(int a, int b) { return a + b + offset + initialized - 1; } }";
            Files.writeString(folder.resolve("Engine.iron"), source);
            var compiled = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(List.of(SourceFile.of("test/Engine" + artifact.id() + ".iron", source)));
            check(compiled.valid(), compiled.diagnostics().toString());
            var program = compiled.program().orElseThrow();
            var roots = BridgeRootSet.resolve(program, program.functions().stream().filter(function -> function.ownerClass().equals(
                    "native" + artifact.id() + ".Engine") && function.sourceName().equals("value")).map(BridgeCallableId::of).toList());
            var module = BridgeEntryModule.scalars(compiled, roots);
            Path llvm = folder.resolve("program.ll");
            Files.writeString(llvm, new LlvmEmitter().emit(module));
            Path adapter = folder.resolve("adapter.c");
            Files.writeString(adapter, BridgeLoaderFixtureSources.ADAPTER.replace("@ID@", artifact.id())
                    .replace("@GEN@", artifact.generation()).replace("@COUNT@", Integer.toString(artifact.facades().size()))
                    .replace("@ENTRY@", module.entries().getFirst().function().linkageName()));
            for (var level : List.of(OptimizationLevel.O0, OptimizationLevel.O3)) {
                Path object = folder.resolve("adapter-" + level + ".o");
                BridgeEntryTests.run(folder, List.of(toolchain.clang().toString(), "-std=c11", "-fPIC", "-fvisibility=hidden",
                        level.clangArgument(), "-I" + javaHome.resolve("include"), "-I" + javaHome.resolve(mac ? "include/darwin" : "include/linux"),
                        "-I" + Path.of("runtime/include").toAbsolutePath(), "-c", adapter.toString(), "-o", object.toString()), "adapter-" + level);
                Path image = folder.resolve("world-" + level + extension);
                var linked = new NativeBackend().linkShared(toolchain, llvm, image, level, List.of(object));
                Files.writeString(folder.resolve("link-" + level + ".log"), linked.output());
                check(linked.success(), linked.output());
                Files.writeString(folder.resolve("sha256-" + level + ".txt"), HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(image))) + "  " + image.getFileName() + "\n");
            }
        }
        prepareMixed(directory, javaHome, false);
        prepareMixed(directory, javaHome, true);
        for (var level : List.of(OptimizationLevel.O0, OptimizationLevel.O3)) {
            for (String scenario : List.of("ab-a", "ab-b", "ba-a", "ba-b", "ac-a", "ac-c", "ca-a", "ca-c",
                    "disjoint", "mixed", "signature", "anchor", "late")) {
                List<String> command = new ArrayList<>();
                if (scenario.equals("late")) command.addAll(List.of("/usr/bin/env", "IRONWOOD_BRIDGE_FAIL_REGISTRATION=1"));
                command.addAll(List.of(javaHome.resolve("bin/java").toString(), "-Xcheck:jni", "-cp",
                        directory.toString(), "BridgeLoaderDriver", directory.toString(), level.toString(), extension, scenario));
                String output = BridgeEntryTests.run(directory, command, "consumer-" + level + "-" + scenario);
                check(output.equals("driver-start\nloader-ok:" + scenario + "\n"), "unexpected loader output: " + output);
            }
            moduleRefusal(directory, javaHome, level, extension);
        }
        System.out.println("bridge loader evidence: " + directory);
    }

    private static void prepareArtifact(Path directory, Artifact artifact, Path javaHome) throws Exception {
        Path folder = directory.resolve(artifact.id());
        Path sources = folder.resolve("sources");
        Path classes = folder.resolve("classes");
        Files.createDirectories(classes);
        var names = new LinkedHashSet<String>(artifact.facades());
        artifact.facades().stream().map(name -> name.substring(0, name.lastIndexOf('.')) + ".__IronwoodPackage").forEach(names::add);
        List<String> files = new ArrayList<>();
        for (String name : names) {
            String packageName = name.substring(0, name.lastIndexOf('.'));
            String simpleName = name.substring(name.lastIndexOf('.') + 1);
            boolean facade = artifact.facades().contains(name);
            Path source = sources.resolve(name.replace('.', '/') + ".java");
            Files.createDirectories(source.getParent());
            Files.writeString(source, "// SPDX-License-Identifier: MIT OR Apache-2.0\npackage " + packageName + ";\n"
                    + "@loaderfixture." + artifact.id() + ".Support.Identity(\"" + artifact.generation() + "\")\npublic final class " + simpleName + " {\n"
                    + (facade ? " static { loaderfixture." + artifact.id() + ".Support.ensure(); }\n public static native int value(int a, int b);\n" : "") + "}\n");
            files.add(source.toString());
        }
        names.add("loaderfixture." + artifact.id() + ".Support");
        names.add("loaderfixture." + artifact.id() + ".Support$Identity");
        Path support = sources.resolve("loaderfixture/" + artifact.id() + "/Support.java");
        Files.createDirectories(support.getParent());
        Files.writeString(support, BridgeLoaderFixtureSources.SUPPORT.replace("@ID@", artifact.id()).replace("@GEN@", artifact.generation())
                .replace("@ALL_TYPES@", quoted(List.copyOf(names))).replace("@FACADES@", quoted(artifact.facades())));
        files.add(support.toString());
        List<String> command = new ArrayList<>(List.of(javaHome.resolve("bin/javac").toString(), "--release", "21", "-d", classes.toString()));
        command.addAll(files);
        BridgeEntryTests.run(folder, command, "javac");
        jar(folder, classes, folder.resolve("artifact.jar"), javaHome, artifact.id());
    }

    private static String quoted(List<String> names) {
        return names.stream().map(name -> "\"" + name + "\"").reduce((left, right) -> left + ", " + right).orElse("");
    }

    private static void jar(Path folder, Path classes, Path jar, Path javaHome, String module) throws Exception {
        Path manifest = folder.resolve("MANIFEST.MF");
        Files.writeString(manifest, "Manifest-Version: 1.0\nAutomatic-Module-Name: loaderfixture." + module + "\n\n");
        BridgeEntryTests.run(folder, List.of(javaHome.resolve("bin/jar").toString(), "--create", "--file", jar.toString(),
                "--manifest", manifest.toString(), "-C", classes.toString(), "."), "jar");
    }

    private static void prepareMixed(Path directory, Path javaHome, boolean signature) throws Exception {
        Path folder = directory.resolve(signature ? "signature" : "mixed");
        Path classes = folder.resolve("classes");
        Path original = directory.resolve("a/classes");
        try (var paths = Files.walk(original)) {
            for (Path path : paths.toList()) {
                Path destination = classes.resolve(original.relativize(path));
                if (Files.isDirectory(path)) Files.createDirectories(destination); else Files.copy(path, destination);
            }
        }
        Path source = folder.resolve("Common.java");
        String content = Files.readString(directory.resolve("a/sources/api/shared/Common.java"));
        content = signature ? content.replace("value(int a, int b)", "value(long a, int b)")
                : content.replace(ARTIFACTS.getFirst().generation(), "different-complete-generation");
        Files.writeString(source, content);
        BridgeEntryTests.run(folder, List.of(javaHome.resolve("bin/javac").toString(), "--release", "21", "-cp", classes.toString(),
                "-d", classes.toString(), source.toString()), "javac");
        jar(folder, classes, folder.resolve("artifact.jar"), javaHome, "a");
    }

    private static void moduleRefusal(Path directory, Path javaHome, OptimizationLevel level, String extension) throws Exception {
        List<String> command = List.of(javaHome.resolve("bin/java").toString(), "-Xcheck:jni", "--module-path",
                directory.resolve("a/artifact.jar") + java.io.File.pathSeparator + directory.resolve("c/artifact.jar"),
                "--add-modules", "ALL-MODULE-PATH", "-cp", directory.toString(), "BridgeLoaderDriver", directory.toString(),
                level.toString(), extension, "disjoint");
        Path log = directory.resolve("modules-" + level + ".log");
        Files.writeString(directory.resolve("modules-" + level + ".command.txt"), String.join("\n", command) + "\n");
        var process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new AssertionError("module refusal timeout"); }
        String output = Files.readString(log);
        check(process.exitValue() != 0 && output.contains("api.shared") && !output.contains("driver-start"),
                "split package reached driver/native loading: " + output);
    }

    private static final String DRIVER = """
            // SPDX-License-Identifier: MIT OR Apache-2.0
            import java.lang.ref.WeakReference;
            import java.lang.reflect.InvocationTargetException;
            import java.net.URLClassLoader;
            import java.nio.file.Path;
            public final class BridgeLoaderDriver {
                private static native int count(String image, int slot);
                private static native int reloadHook(String image);
                private static Path directory;
                private static String level, extension;
                private static String image(String id) { return directory.resolve(id + "/world-" + level + extension).toString(); }
                private static URLClassLoader loader(String... ids) throws Exception {
                    java.net.URL[] urls = new java.net.URL[ids.length];
                    for (int index = 0; index < ids.length; index++) urls[index] = directory.resolve(ids[index] + "/artifact.jar").toUri().toURL();
                    return new URLClassLoader(urls, ClassLoader.getPlatformClassLoader());
                }
                private static Throwable failure(Throwable problem) {
                    while (problem instanceof InvocationTargetException || problem instanceof ExceptionInInitializerError) {
                        if (problem.getCause() == null) break;
                        problem = problem.getCause();
                    }
                    return problem;
                }
                private static void use(ClassLoader loader, String type, int expected) throws Exception {
                    int result = (int) Class.forName(type, true, loader).getMethod("value", int.class, int.class).invoke(null, 3, 4);
                    if (result != expected + 7) throw new AssertionError("binding changed: " + type + "=" + result);
                }
                private static void refuse(ClassLoader loader, String type) throws Exception {
                    try { use(loader, type, 0); throw new AssertionError("collision admitted: " + type); }
                    catch (Throwable problem) {
                        if (!(failure(problem) instanceof LinkageError)) throw new AssertionError("wrong failure", problem);
                    }
                }
                private static String only(String id) {
                    return id.equals("b") ? "api.other.OnlyB" : id.equals("d") ? "api.disjoint.OnlyD" : "api.shared.Only" + id.toUpperCase();
                }
                private static int expected(String id) { return switch(id) { case "a" -> 111; case "b" -> 222; case "c" -> 333; default -> 444; }; }
                private static void collision(String scenario) throws Exception {
                    String winner = scenario.substring(0, 1), loser = scenario.substring(1, 2), first = scenario.substring(3, 4);
                    try (var loader = loader(winner, loser)) {
                        if (first.equals(loser)) refuse(loader, only(loser));
                        use(loader, only(winner), expected(winner));
                        int registered = count(image(winner), 1);
                        if (!first.equals(loser)) refuse(loader, only(loser));
                        if (count(image(loser), 0) != 0 || count(image(loser), 1) != 0 || count(image(loser), 2) != 0) {
                            throw new AssertionError("collision loaded/registered/entered native image");
                        }
                        use(loader, only(winner), expected(winner));
                        if (count(image(winner), 1) != registered) throw new AssertionError("first artifact rebound");
                    }
                }
                private static WeakReference<ClassLoader> bindAndDrop() throws Exception {
                    var loader = loader("a");
                    use(loader, only("a"), 111);
                    Class<?> support = Class.forName("loaderfixture.a.Support", false, loader);
                    var repeat = support.getMethod("repeat", ClassLoader.class);
                    int registrations = count(image("a"), 1);
                    repeat.invoke(null, loader);
                    if (count(image("a"), 1) != registrations) throw new AssertionError("repeat bootstrap rebound");
                    try (var different = loader("a")) {
                        try { repeat.invoke(null, different); throw new AssertionError("explicit bootstrap rebound"); }
                        catch (InvocationTargetException problem) { if (!(failure(problem) instanceof LinkageError)) throw problem; }
                    }
                    loader.close();
                    return new WeakReference<ClassLoader>(loader);
                }
                public static void main(String[] args) throws Exception {
                    System.out.println("driver-start");
                    directory = Path.of(args[0]); level = args[1]; extension = args[2];
                    for (String id : new String[]{"a", "b", "c", "d"}) System.setProperty("bridge.fixture." + id, image(id));
                    System.load(directory.resolve("inspector" + extension).toString());
                    String scenario = args[3];
                    if (scenario.contains("-")) collision(scenario);
                    else if (scenario.equals("disjoint")) {
                        try (var loader = loader("a", "d")) { use(loader, only("a"), 111); use(loader, only("d"), 444); use(loader, only("a"), 111); }
                    } else if (scenario.equals("late")) {
                        try (var loader = loader("a", "d")) {
                            use(loader, only("d"), 444);
                            refuse(loader, only("a"));
                            refuse(loader, "api.shared.Common");
                            if (count(image("a"), 1) != 2 || count(image("a"), 4) != 1 || count(image("a"), 2) != 0) {
                                throw new AssertionError("partial registration was exposed or not cleaned");
                            }
                            use(loader, only("d"), 444);
                            if (count(image("d"), 1) != 1 || count(image("d"), 4) != 0) throw new AssertionError("foreign registration changed");
                        }
                    } else if (scenario.equals("mixed") || scenario.equals("signature")) {
                        try (var loader = loader(scenario)) { refuse(loader, only("a")); }
                        if (count(image("a"), 0) != 0 || count(image("a"), 1) != 0 || count(image("a"), 2) != 0) throw new AssertionError("failed preflight entered native image");
                    } else {
                        WeakReference<ClassLoader> retained = bindAndDrop();
                        for (int i = 0; i < 8; i++) { System.gc(); Thread.sleep(25); }
                        if (retained.get() == null || count(image("a"), 3) != 1) throw new AssertionError("loader/global anchor lost");
                        int calls = count(image("a"), 2), registrations = count(image("a"), 1);
                        try (var second = loader("a")) { refuse(second, only("a")); }
                        if (count(image("a"), 2) != calls || count(image("a"), 1) != registrations) throw new AssertionError("second loader entered/rebound");
                        if (reloadHook(image("a")) != -1) throw new AssertionError("bound OS-retained image allowed OnLoad");
                        use(retained.get(), only("a"), 111);
                        if (count(image("a"), 1) != registrations) throw new AssertionError("OnLoad refusal changed registration");
                    }
                    System.out.println("loader-ok:" + scenario);
                }
            }
            """;

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
