// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.backend.*;
import ironwood.compiler.bridge.*;
import ironwood.compiler.source.SourceFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

final class BridgeRootReuseTests {
    static final String NAME = "Java Bridge generated roots isolate stale facades across forced native address reuse";
    private BridgeRootReuseTests() {}

    static void reuse() throws Exception {
        Path base = Path.of("workspace/java-bridge/evidence/p3c/root-reuse").toAbsolutePath(); Files.createDirectories(base);
        Path directory = Files.createTempDirectory(base, "run-"), javaHome = Path.of(System.getProperty("java.home"));
        if (!"1".equals(System.getenv("IRONWOOD_BRIDGE_ROOT_REUSE"))) {
            Path runtimeHome = BridgeIdentityTests.reuseRuntime(directory, "rootjava.Root");
            Files.writeString(directory.resolve("runtime.sha256"), BridgeGeneration.bytesDigest(Files.readAllBytes(runtimeHome.resolve("runtime/src/ironwood_runtime.c"))) + "\n");
            var command = List.of(javaHome.resolve("bin/java").toString(), "-ea", "-cp", System.getProperty("java.class.path"),
                    "ironwood.compiler.CompilerTests", "--test", NAME);
            Files.writeString(directory.resolve("child.command.txt"), String.join("\n", command)
                    + "\nIRONWOOD_RUNTIME_HOME=" + runtimeHome + "\nIRONWOOD_BRIDGE_ROOT_REUSE=1\n");
            var builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(directory.resolve("child.log").toFile());
            builder.environment().put("IRONWOOD_RUNTIME_HOME", runtimeHome.toString()); builder.environment().put("IRONWOOD_BRIDGE_ROOT_REUSE", "1");
            var child = builder.start();
            if (!child.waitFor(90, TimeUnit.SECONDS)) { child.destroyForcibly(); throw new AssertionError("generated root reuse child timed out"); }
            check(child.exitValue() == 0, Files.readString(directory.resolve("child.log")));
            System.out.println("root forced-reuse parent evidence: " + directory); return;
        }
        String source = BridgeRootFacadeNativeTests.SOURCE;
        var artifact = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(List.of(SourceFile.of("Root.iron", source)));
        check(artifact.valid(), artifact.diagnostics().toString()); var proof = BridgeObjectAdmission.prove(artifact, List.of("rootjava"));
        check(proof.contract().isPresent(), proof.reason()); var admission = proof.contract().orElseThrow(); var producer = BridgeProducerInputs.discover();
        var generation = BridgeGeneration.createObjects("root-reuse.jar", artifact, admission, producer.compilerVersion(), producer.compilerIdentity(), producer.runtimeIdentity());
        var projected = BridgePermanentJavaSources.generateRoots(artifact, admission, generation);
        var adapters = BridgePermanentNativeSources.generateRoots(artifact, admission, generation, projected);
        var facade = projected.facades().stream().filter(value -> value.binaryName().equals("rootjava.Root")).findFirst().orElseThrow();
        var destruction = projected.declarations().rootDestructions().stream().filter(value -> value.binaryName().equals("rootjava.Root")).findFirst().orElseThrow();
        String support = generation.supportPackage(), helper = support + ".ReuseAccess";
        String access = "package " + support + ";\n" + ACCESS.replace("@ADDRESS@", facade.addressField()).replace("@STATE@", facade.stateField())
                .replace("@DESTROY@", destruction.nativeName());
        String llvmText = new LlvmEmitter().emit(admission.program()); Path llvm = directory.resolve("program.ll");
        Files.writeString(llvm, llvmText); Files.writeString(directory.resolve("Root.iron"), source);
        Files.writeString(directory.resolve("scope.txt"), "Generated adapters with test-only deterministic runtime allocation reuse and Java reflection probes.\n"
                + "No production allocator override or facade mutation.\ncompiler=" + producer.compilerIdentity() + "\nruntime=" + producer.runtimeIdentity()
                + "\nllvm=" + digest(llvmText) + "\nadapters=" + digest(adapters.source()) + "\n");
        var discovery = LlvmToolchain.discover(null); check(discovery.successful(), discovery.error()); var toolchain = discovery.toolchain().orElseThrow();
        String counter = "extern int64_t bridge_test_reuse_count(void);\nJNIEXPORT jlong JNICALL Java_" + helper.replace('.', '_')
                + "_count(JNIEnv *env, jclass type) { (void)env; (void)type; return bridge_test_reuse_count(); }\n";
        for (var level : List.of(OptimizationLevel.O0, OptimizationLevel.O3)) {
            Path folder = directory.resolve(level.toString()); Files.createDirectories(folder);
            var build = generation.nativeBuild("macos-arm64", Map.of("fixture", "forced-root-reuse", "llvm", digest(llvmText), "adapters", digest(adapters.source()),
                    "probe", digest(counter + access), "runtime", producer.runtimeIdentity(), "optimization", level.toString()));
            String nativeSource = adapters.source() + BridgeBootstrapSources.generate(generation, build, projected.declarations(), adapters) + counter;
            Path jar = BridgeGeneratedJarTests.build(folder, llvm, toolchain, level, generation, build, projected.declarations(), nativeSource,
                    Map.of(helper.replace('.', '/') + ".java", access));
            Files.writeString(folder.resolve("adapter.sha256"), digest(nativeSource) + "\n");
            Path consumer = folder.resolve("ReuseConsumer.java"); Files.writeString(consumer, "import " + helper + ";\n" + CONSUMER);
            BridgeEntryTests.run(folder, List.of(javaHome.resolve("bin/javac").toString(), "--release", "21", "-Xlint:all", "-Werror", "-cp", jar.toString(), consumer.toString()), "consumer-javac");
            String output = BridgeEntryTests.run(folder, List.of(javaHome.resolve("bin/java").toString(), "-Xcheck:jni", "-Xmx64m", "-cp",
                    jar + java.io.File.pathSeparator + folder, "ReuseConsumer"), "consumer");
            check(output.equals("root-forced-reuse-ok:512:511\n"), output);
        }
        System.out.println("generated root forced-reuse evidence: " + directory);
    }
    private static String digest(String value) { return BridgeGeneration.bytesDigest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static final String ACCESS = """
            import rootjava.Root;
            public final class ReuseAccess {
                private static final java.lang.reflect.Field address;
                private static final java.lang.reflect.Field state;
                private static final java.lang.reflect.Method destroy;
                static {
                    try {
                        address = Root.class.getDeclaredField("@ADDRESS@"); address.setAccessible(true);
                        state = Root.class.getDeclaredField("@STATE@"); state.setAccessible(true);
                        destroy = Root.class.getDeclaredMethod("@DESTROY@", RootState.class, long.class); destroy.setAccessible(true);
                    } catch (ReflectiveOperationException failed) { throw new AssertionError(failed); }
                }
                public static native long count();
                public static long address(Root value) throws IllegalAccessException { return address.getLong(value); }
                public static Object state(Root value) throws IllegalAccessException { return state.get(value); }
                public static boolean refusal(Throwable value) { return value.getClass() == BridgeLifetimeException.class; }
                public static void staleHandle(Root value) throws ReflectiveOperationException {
                    try { destroy.invoke(null, state(value), address(value)); throw new AssertionError("stale internal handle accepted"); }
                    catch (java.lang.reflect.InvocationTargetException expected) {
                        if (expected.getCause().getClass() != IllegalArgumentException.class) throw new AssertionError(expected.getCause());
                    }
                }
            }
            """;
    private static final String CONSUMER = """
            import rootjava.Root;
            public final class ReuseConsumer {
                private static void check(boolean value) { if (!value) throw new AssertionError(); }
                private static void refusal(Runnable action) {
                    int calls = Root.entered(), destroyed = Root.destroyed();
                    try { action.run(); throw new AssertionError("missing refusal"); }
                    catch (IllegalStateException expected) { check(ReuseAccess.refusal(expected)); }
                    check(Root.entered() == calls && Root.destroyed() == destroyed);
                }
                public static void main(String[] args) throws Exception {
                    long baseline = Root.live(); Root[] stale = new Root[512]; Root.Child[] children = new Root.Child[512];
                    long address = 0; int hash = 0; String text = null;
                    for (int i = 0; i < stale.length; i++) {
                        Root current = new Root();
                        check(current.self() == current && current.fastValue() == 17);
                        if (i == 0) { address = ReuseAccess.address(current); hash = current.hashCode(); text = current.toString(); }
                        else {
                            Root previous = stale[i - 1];
                            check(ReuseAccess.address(current) == address && ReuseAccess.state(current) != ReuseAccess.state(previous)
                                    && current.hashCode() == hash && current.toString().equals(text) && !current.equals(previous) && !previous.equals(current));
                            int destroyed = Root.destroyed(); previous.free(); ReuseAccess.staleHandle(previous); check(Root.destroyed() == destroyed);
                            refusal(previous::value); refusal(children[i - 1]::value); refusal(children[i - 1]::free);
                            refusal(() -> Root.pick(current, previous, true));
                            check(current.value() == 17 && current.self() == current);
                        }
                        stale[i] = current; children[i] = current.child(); check(children[i].self() == children[i]); current.free();
                        check(Root.live() == baseline);
                    }
                    check(ReuseAccess.count() == 511 && Root.destroyed() == 512);
                    for (Root value : stale) { check(value.hashCode() == hash && value.toString().equals(text)); value.free(); }
                    check(Root.destroyed() == 512 && Root.live() == baseline);
                    System.out.println("root-forced-reuse-ok:512:" + ReuseAccess.count());
                }
            }
            """;
}
