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

final class BridgeBootstrapNativeTests {
    static final String NAME = "Java Bridge generated bootstrap binds paired jars without eager initialization";
    private BridgeBootstrapNativeTests() {}

    static void bootstrap() throws Exception {
        if (!System.getProperty("os.name").startsWith("Mac")) return; // P2's loader target is macOS ARM64.
        var artifact = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(List.of(SourceFile.of("Engine.iron", """
                package bootpreview;
                public final class Engine {
                    private Engine() {}
                    public static int add(int a, int b) { return a + b; }
                    public static int length(String a, String b) { return a.length() + b.length(); }
                    public static String echo(String value) { return value; }
                    public static String fresh(String value) { return new String(value); }
                    public static String fixed() { return "fixed"; }
                    public static int fail() throws ironwood.io.IOException { throw new ironwood.io.IOException("native message"); }
                    public static final class Lazy {
                        private Lazy() {}
                        private static int value = initialize();
                        private static int initialize() { throw new IllegalStateException("lazy failure"); }
                        public static int read() { return value; }
                        public static int second() { return value + 1; }
                    }
                }
                """)));
        check(artifact.valid(), artifact.diagnostics().toString());
        var surface = BridgeExportSurface.staticValues(artifact, List.of("bootpreview")).surface().orElseThrow();
        var module = BridgeEntryModule.stringValues(artifact, surface.roots());
        var closure = BridgeExceptionClosure.builtins(artifact, module);
        check(closure.status() == BridgeProof.Status.PROVED, closure.reason());
        var snapshot = closure.contract().orElseThrow();
        var generation = BridgeGeneration.create("bootstrap.jar", artifact, surface, "test", "1".repeat(64), "2".repeat(64));
        var declarations = BridgeJavaSources.generate(artifact, surface, generation, module, snapshot.projection());
        var values = BridgeValueNativeSources.generate(artifact, module, snapshot.projection(), snapshot.entries());
        String llvmText = new LlvmEmitter().emit(NativeLinkPipeline.finish(NativeLinkPipeline.optimize(snapshot.entries().program())));
        Path base = Path.of("workspace/java-bridge/evidence/p2/bootstrap").toAbsolutePath();
        Files.createDirectories(base);
        Path directory = Files.createTempDirectory(base, "run-");
        Path javaHome = Path.of(System.getProperty("java.home")), llvm = directory.resolve("program.ll");
        Files.writeString(llvm, llvmText);
        var found = LlvmToolchain.discover(null); check(found.successful(), found.error());
        var toolchain = found.toolchain().orElseThrow();
        Path driver = directory.resolve("BootstrapConsumer.java");
        Files.writeString(driver, CONSUMER);
        BridgeEntryTests.run(directory, List.of(javaHome.resolve("bin/javac").toString(), "--release", "21", "-Xlint:all", "-Werror", driver.toString()), "driver-javac");
        for (var level : List.of(OptimizationLevel.O0, OptimizationLevel.O3)) {
            var build = generation.nativeBuild("macos-arm64", Map.of("fixture", "generated-bootstrap", "llvm", BridgeGeneration.bytesDigest(llvmText.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    "values", BridgeGeneration.bytesDigest(values.source().getBytes(java.nio.charset.StandardCharsets.UTF_8)), "optimization", level.toString()));
            String bootstrap = BridgeBootstrapSources.generate(generation, build, declarations, values);
            for (boolean fault : List.of(false, true)) {
                String cell = level + (fault ? "-fault" : "-production");
                Path folder = directory.resolve(cell); Files.createDirectories(folder);
                Path adapter = folder.resolve("adapter.c"), object = folder.resolve("adapter.o"), image = folder.resolve("libbridge.dylib");
                String source = values.source() + bootstrap;
                if (fault) source = FAULT + source.replace("(*env)->RegisterNatives(env, validated[index], binding->methods, binding->method_count)",
                        "fixture_register(env, validated[index], binding->methods, binding->method_count)");
                Files.writeString(adapter, source);
                BridgeEntryTests.run(folder, List.of(toolchain.clang().toString(), "-std=c11", "-Wall", "-Wextra", "-Werror", "-fPIC", "-fvisibility=hidden",
                        level.clangArgument(), "-I" + javaHome.resolve("include"), "-I" + javaHome.resolve("include/darwin"),
                        "-I" + Path.of("runtime/include").toAbsolutePath(), "-c", adapter.toString(), "-o", object.toString()), "compile");
                var linked = new NativeBackend().linkShared(toolchain, llvm, image, level, List.of(object));
                Files.writeString(folder.resolve("link.log"), linked.output()); check(linked.success(), linked.output());
                BridgeEntryTests.run(folder, List.of("/usr/bin/codesign", "--verify", "--strict", image.toString()), "codesign");
                String sha = BridgeGeneration.bytesDigest(Files.readAllBytes(image));
                Files.writeString(folder.resolve("payload.sha256"), sha + "\n");
                var metadata = BridgeMacPayload.inspect(Files.readAllBytes(image));
                Files.writeString(folder.resolve("payload-target.txt"), "minimum.macos=" + metadata.minimumOs() + "\nsdk=" + metadata.sdk()
                        + "\ndependencies=" + String.join(",", metadata.dependencies()) + "\n");
                var sources = new java.util.TreeMap<>(declarations.sources());
                sources.put(generation.supportPackage().replace('.', '/') + "/Support.java", BridgeLoaderSources.generate(generation, declarations,
                        new BridgeLoaderSources.Payload(build, metadata.minimumOs(), sha)));
                Path classes = folder.resolve("classes");
                var command = new ArrayList<>(List.of(javaHome.resolve("bin/javac").toString(), "--release", "21", "-Xlint:all", "-Werror", "-d", classes.toString()));
                for (var entry : sources.entrySet()) {
                    Path path = folder.resolve("sources").resolve(entry.getKey()); Files.createDirectories(path.getParent());
                    Files.writeString(path, entry.getValue()); command.add(path.toString());
                }
                BridgeEntryTests.run(folder, command, "javac");
                Path resource = classes.resolve("META-INF/ironwood/native/macos-arm64/" + generation.identity() + "/libbridge.dylib");
                Files.createDirectories(resource.getParent()); Files.copy(image, resource);
                Path jar = folder.resolve("artifact.jar");
                BridgeEntryTests.run(folder, List.of(javaHome.resolve("bin/jar").toString(), "--create", "--file", jar.toString(), "-C", classes.toString(), "."), "jar");
                for (String scenario : fault ? List.of("fault") : List.of("normal", "anchor", "budget")) {
                    var run = new ArrayList<String>();
                    if (scenario.equals("budget")) run.addAll(List.of("/usr/bin/env", "IRONWOOD_ALLOCATION_LIMIT=0"));
                    run.addAll(List.of(javaHome.resolve("bin/java").toString(), "-Xcheck:jni", "-cp", directory.toString(), "BootstrapConsumer",
                            jar.toString(), generation.supportPackage() + ".Support", scenario, sha));
                    String output = BridgeEntryTests.run(folder, run, "consumer-" + scenario);
                    check(output.contains("bootstrap-ok:" + scenario + "\n") && !output.contains("WARNING") && !output.contains("FATAL"), output);
                    if (!fault) {
                        String extracted = output.lines().filter(line -> line.startsWith("extracted:")).findFirst().orElseThrow().substring(10);
                        Path copy = Path.of(extracted);
                        check(BridgeGeneration.bytesDigest(Files.readAllBytes(copy)).equals(sha), "loaded copy digest differs");
                        BridgeEntryTests.run(folder, List.of("/usr/bin/codesign", "--verify", "--strict", copy.toString()), "extracted-codesign-" + scenario);
                    }
                }
            }
        }
        System.out.println("generated bootstrap evidence: " + directory);
    }

    private static final String FAULT = """
            // Test-only late, partially successful JNI registration failure.
            #include <jni.h>
            static int registrations;
            static jint fixture_register(JNIEnv *env, jclass type, const JNINativeMethod *methods, jint count) {
                if (++registrations != 2) return (*env)->RegisterNatives(env, type, methods, count);
                if (count < 2 || (*env)->RegisterNatives(env, type, methods, 1) != 0) return JNI_ERR;
                jclass error = (*env)->FindClass(env, "java/lang/LinkageError");
                if (error != NULL) { (*env)->ThrowNew(env, error, "injected partial registration"); (*env)->DeleteLocalRef(env, error); }
                return JNI_ERR;
            }
            """;

    private static final String CONSUMER = """
            import java.lang.ref.WeakReference;
            import java.lang.reflect.*;
            import java.net.URLClassLoader;
            import java.nio.file.*;
            public final class BootstrapConsumer {
                private static Object invoke(Class<?> type, String name, Class<?>[] signature, Object... args) throws Throwable {
                    Method method = type.getDeclaredMethod(name, signature); method.setAccessible(true);
                    try { return method.invoke(null, args); } catch (InvocationTargetException e) { throw e.getCause(); }
                }
                private static Object field(Class<?> type, String name) throws Exception {
                    Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(null);
                }
                private static void refuse(RunnableWithThrowable action, String message) throws Throwable {
                    try { action.run(); throw new AssertionError("missing refusal: " + message); }
                    catch (LinkageError expected) { if (!expected.getMessage().contains(message)) throw expected; }
                }
                @FunctionalInterface private interface RunnableWithThrowable { void run() throws Throwable; }
                public static void main(String[] args) throws Throwable {
                    WeakReference<ClassLoader> weak = run(args);
                    if (args[2].equals("anchor")) {
                        for (int i = 0; i < 8; i++) { System.gc(); Thread.sleep(20); }
                        if (weak.get() == null) throw new AssertionError("loader anchor lost");
                    }
                    System.out.println("bootstrap-ok:" + args[2]);
                }
                private static WeakReference<ClassLoader> run(String[] args) throws Throwable {
                    try (var loader = new URLClassLoader(new java.net.URL[]{Path.of(args[0]).toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
                        Class<?> support = Class.forName(args[1], true, loader);
                        Method ensure = java.util.Arrays.stream(support.getDeclaredMethods()).filter(method -> method.getName().startsWith("$ironwood$ensure")).findFirst().orElseThrow();
                        if (args[2].equals("fault")) {
                            refuse(() -> invoke(support, ensure.getName(), new Class<?>[0]), "injected partial registration");
                            refuse(() -> invoke(support, ensure.getName(), new Class<?>[0]), "injected partial registration");
                            return new WeakReference<>(loader);
                        }
                        invoke(support, ensure.getName(), new Class<?>[0]);
                        // A throwing source initializer must remain dormant during binding.
                        Class<?> engine = Class.forName("bootpreview.Engine", true, loader);
                        if (!invoke(engine, "add", new Class<?>[]{int.class, int.class}, 20, 22).equals(42)) throw new AssertionError("scalar");
                        if (!invoke(engine, "fixed", new Class<?>[0]).equals("fixed")) throw new AssertionError("immortal result");
                        if (args[2].equals("budget")) {
                            for (int i = 0; i < 3; i++) {
                                try { invoke(engine, "length", new Class<?>[]{String.class, String.class}, "a", "b"); throw new AssertionError("missing conversion OOM"); }
                                catch (OutOfMemoryError expected) {}
                            }
                        } else {
                            if (!invoke(engine, "length", new Class<?>[]{String.class, String.class}, "a\\0b", "" + (char)0xd800).equals(4)) throw new AssertionError("UTF16");
                            for (String text : new String[]{null, "", "a\\0b", "" + (char)0xd800}) {
                                if (!java.util.Objects.equals(invoke(engine, "echo", new Class<?>[]{String.class}, (Object)text), text)) throw new AssertionError("String alias result");
                                if (text != null && !invoke(engine, "fresh", new Class<?>[]{String.class}, text).equals(text)) throw new AssertionError("fresh String result");
                            }
                            for (int i = 0; i < 2; i++) {
                                try { invoke(engine, "fail", new Class<?>[0]); throw new AssertionError("missing checked exception"); }
                                catch (java.io.IOException expected) { if (!expected.getMessage().equals("native message")) throw expected; }
                                Class<?> lazy = Class.forName("bootpreview.Engine$Lazy", true, loader);
                                try { invoke(lazy, "read", new Class<?>[0]); throw new AssertionError("missing initializer exception"); }
                                catch (IllegalStateException expected) { if (!expected.getMessage().equals("lazy failure")) throw expected; }
                            }
                        }
                        Class<?>[] types = (Class<?>[])invoke(support, "preflight", new Class<?>[]{ClassLoader.class}, loader);
                        Class<?>[] signature = {ClassLoader.class, Class[].class, String.class, String.class, String.class, String.class};
                        String[][] payloads = (String[][])field(support, "PAYLOADS");
                        if (payloads.length != 1) throw new AssertionError("expected single-host bootstrap fixture");
                        Object[] original = {loader, types, field(support, "GENERATION"), field(support, "SCHEMA"), field(support, "API"), payloads[0][1]};
                        invoke(support, "bootstrap", signature, original);
                        for (int i = 2; i < 6; i++) {
                            Object[] changed = original.clone(); changed[i] = "wrong";
                            refuse(() -> invoke(support, "bootstrap", signature, changed), "mismatch");
                        }
                        try (var other = new URLClassLoader(new java.net.URL[]{Path.of(args[0]).toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
                            Object[] changed = original.clone(); changed[0] = other;
                            refuse(() -> invoke(support, "bootstrap", signature, changed), "already bound");
                            Class<?> otherSupport = Class.forName(args[1], true, other);
                            refuse(() -> invoke(otherSupport, ensure.getName(), new Class<?>[0]), "already loaded in another classloader");
                        }
                        Object[] changed = original.clone(); Class<?>[] changedTypes = types.clone(); changedTypes[0] = String.class; changed[1] = changedTypes;
                        refuse(() -> invoke(support, "bootstrap", signature, changed), "class mismatch");
                        if (!invoke(engine, "add", new Class<?>[]{int.class, int.class}, 19, 23).equals(42)) throw new AssertionError("refusal damaged binding");
                        Path extracted = (Path)invoke(support, "extract", new Class<?>[0]);
                        String actual = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(extracted)));
                        if (!actual.equals(args[3])) throw new AssertionError("extracted digest");
                        System.out.println("extracted:" + extracted);
                        return new WeakReference<>(loader);
                    }
                }
            }
            """;

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
