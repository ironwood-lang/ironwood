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
import java.util.List;
import java.util.Set;

final class BridgeImageTraceTests {
    private BridgeImageTraceTests() {}

    static void disjointImages() throws Exception {
        disjointImages(false);
    }

    static void productionImages() throws Exception {
        disjointImages(true);
    }

    private static void disjointImages(boolean production) throws Exception {
        var discovery = LlvmToolchain.discover(null);
        check(discovery.successful(), discovery.error());
        var toolchain = discovery.toolchain().orElseThrow();
        Path base = Path.of("workspace/java-bridge/evidence/" + (production ? "p1" : "p0b") + "/shared-traces").toAbsolutePath();
        Files.createDirectories(base);
        Path directory = Files.createTempDirectory(base, "run-");
        Path javaHome = Path.of(System.getProperty("java.home"));
        boolean mac = System.getProperty("os.name").startsWith("Mac");
        List<String> javaSources = new ArrayList<>();
        for (String name : List.of("left", "right")) {
            Path sourceDirectory = directory.resolve(name);
            Files.createDirectories(sourceDirectory);
            String nativeSource = """
                    package %sfixture;
                    final class Engine {
                        static int add(int first, int second) { return first + second; }
                        static int fail() { throw null; }
                    }
                    """.formatted(name);
            Files.writeString(sourceDirectory.resolve("Engine.iron"), nativeSource);
            var artifact = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(List.of(
                    SourceFile.of("test/" + name + "Engine.iron", nativeSource)));
            check(artifact.valid(), artifact.diagnostics().toString());
            var program = artifact.program().orElseThrow();
            var roots = BridgeRootSet.resolve(program, program.functions().stream()
                    .filter(function -> function.ownerClass().equals(name + "fixture.Engine")
                            && Set.of("add", "fail").contains(function.sourceName()))
                    .map(BridgeCallableId::of).toList());
            var module = BridgeEntryModule.scalars(artifact, roots);
            var compiled = production ? new CompilerPipeline(UnfreedMode.OFF).compileBridge(artifact, roots) : artifact;
            check(compiled.valid(), compiled.diagnostics().toString());
            Files.writeString(sourceDirectory.resolve("program.ll"), production ? compiled.llvmIr().orElseThrow() : new LlvmEmitter().emit(module));
            Files.writeString(sourceDirectory.resolve("adapter.c"), BridgeEntryTests.adapter(module, "p0" + name + "/Consumer"));
            Path consumer = sourceDirectory.resolve("Consumer.java");
            Files.writeString(consumer, """
                    // SPDX-License-Identifier: MIT OR Apache-2.0
                    package p0%s;
                    public final class Consumer {
                        private static native int add(int first, int second);
                        private static native int fail();
                        private static native long allocations();
                        private static native int handwrittenAdd(int first, int second);
                        public static void load(String path) { System.load(path); System.load(path); }
                        public static void check() {
                            try { fail(); throw new AssertionError("missing native failure"); }
                            catch (RuntimeException failure) {
                                if (!failure.getMessage().contains("ironwood.lang.NullPointerException")
                                        || !failure.getMessage().contains("%sfixture.Engine.fail(%sEngine.iron:4)")) {
                                    throw new AssertionError(failure.getMessage());
                                }
                            }
                            if (add(20, 22) != 42) throw new AssertionError("post-catch image call");
                        }
                    }
                    """.formatted(name, name, name));
            javaSources.add(consumer.toString());
        }
        Path driver = directory.resolve("BridgeTracePair.java");
        Files.writeString(driver, """
                // SPDX-License-Identifier: MIT OR Apache-2.0
                public final class BridgeTracePair {
                    public static void main(String[] arguments) {
                        p0left.Consumer.load(arguments[0]);
                        p0left.Consumer.check();
                        p0right.Consumer.load(arguments[1]);
                        for (int index = 0; index < 16; index++) {
                            p0right.Consumer.check();
                            p0left.Consumer.check();
                        }
                        System.out.println("disjoint-traces-ok");
                    }
                }
                """);
        javaSources.add(driver.toString());
        List<String> compile = new ArrayList<>(List.of(javaHome.resolve("bin/javac").toString(), "--release", "21", "-d", directory.toString()));
        compile.addAll(javaSources);
        BridgeEntryTests.run(directory, compile, "javac");
        for (var level : List.of(OptimizationLevel.O0, OptimizationLevel.O3)) {
            List<String> images = new ArrayList<>();
            for (String name : List.of("left", "right")) {
                Path sourceDirectory = directory.resolve(name);
                Path object = sourceDirectory.resolve("adapter-" + level + ".o");
                BridgeEntryTests.run(sourceDirectory, List.of(toolchain.clang().toString(), "-std=c11", "-fPIC", "-fvisibility=hidden",
                        level.clangArgument(), "-I" + javaHome.resolve("include"),
                        "-I" + javaHome.resolve(mac ? "include/darwin" : "include/linux"),
                        "-I" + Path.of("runtime/include").toAbsolutePath(), "-c", sourceDirectory.resolve("adapter.c").toString(),
                        "-o", object.toString()), "adapter-" + level);
                Path image = sourceDirectory.resolve("trace-" + level + (mac ? ".dylib" : ".so"));
                var linked = new NativeBackend().linkShared(toolchain, sourceDirectory.resolve("program.ll"), image, level, List.of(object));
                Files.writeString(sourceDirectory.resolve("link-" + level + ".log"), linked.output());
                check(linked.success(), linked.output());
                String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(image)));
                Files.writeString(sourceDirectory.resolve("sha256-" + level + ".txt"), hash + "  " + image.getFileName() + "\n");
                images.add(image.toString());
            }
            List<String> command = new ArrayList<>(List.of(javaHome.resolve("bin/java").toString(), "-Xcheck:jni", "-cp",
                    directory.toString(), "BridgeTracePair"));
            command.addAll(images);
            String result = BridgeEntryTests.run(directory, command, "consumer-" + level);
            check(result.equals("disjoint-traces-ok\n"), "unexpected image output: " + result);
        }
        System.out.println("bridge disjoint trace evidence: " + directory);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
