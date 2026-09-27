// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.backend.*;
import ironwood.compiler.bridge.*;
import ironwood.compiler.source.SourceFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class BridgeObjectCollisionTests {
    static final String NAME = "Java Bridge object bootstrap rejects collisions and preserves established worlds";
    private record Input(String id, String packageName, String name, int value) {}
    private record Built(Path jar, String support, String type, int value) {}
    private static final List<Input> INPUTS = List.of(new Input("a", "collision", "Common", 17),
            new Input("b", "collision", "Common", 29), new Input("c", "collision", "Other", 41),
            new Input("d", "disjoint", "Common", 53));
    private BridgeObjectCollisionTests() {}

    static void collisions() throws Exception {
        Path base = Path.of("workspace/java-bridge/evidence/p3d/object-collisions").toAbsolutePath(); Files.createDirectories(base);
        Path directory = Files.createTempDirectory(base, "run-");
        Path javaHome = Path.of(System.getProperty("java.home")), driver = directory.resolve("ObjectCollisionConsumer.java");
        Files.writeString(driver, CONSUMER);
        BridgeEntryTests.run(directory, List.of(javaHome.resolve("bin/javac").toString(), "--release", "21", "-Xlint:all", "-Werror", driver.toString()), "driver-javac");
        var discovered = LlvmToolchain.discover(null); check(discovered.successful(), discovered.error());
        var toolchain = discovered.toolchain().orElseThrow(); var producer = BridgeProducerInputs.discover();
        for (var level : List.of(OptimizationLevel.O0, OptimizationLevel.O3)) {
            var built = new TreeMap<String, Built>();
            for (var input : INPUTS) {
                String source = SOURCE.replace("@PACKAGE@", input.packageName()).replace("@TYPE@", input.name()).replace("@VALUE@", Integer.toString(input.value()));
                var artifact = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(List.of(SourceFile.of(input.name() + ".iron", source)));
                check(artifact.valid(), artifact.diagnostics().toString());
                var proof = BridgeObjectAdmission.prove(artifact, List.of(input.packageName())); check(proof.contract().isPresent(), proof.reason());
                var admission = proof.contract().orElseThrow();
                var generation = BridgeGeneration.createObjects(input.id() + ".jar", artifact, admission,
                        producer.compilerVersion(), producer.compilerIdentity(), producer.runtimeIdentity());
                var java = BridgePermanentJavaSources.generateRoots(artifact, admission, generation);
                var adapters = BridgePermanentNativeSources.generateRoots(artifact, admission, generation, java);
                String llvmText = new LlvmEmitter().emit(admission.program());
                for (String variant : input.id().equals("a") ? List.of("a", "signature", "late") : List.of(input.id())) {
                    Path folder = directory.resolve(level + "-" + variant); Files.createDirectories(folder);
                    Path llvm = folder.resolve("program.ll"); Files.writeString(llvm, llvmText); Files.writeString(folder.resolve(input.name() + ".iron"), source);
                    var build = generation.nativeBuild("macos-arm64", Map.of("fixture", "object-collision-" + variant, "llvm", digest(llvmText),
                            "adapters", digest(adapters.source()), "optimization", level.toString()));
                    String nativeSource = adapters.source() + BridgeBootstrapSources.generate(generation, build, java.declarations(), adapters);
                    var overrides = new TreeMap<String, String>();
                    if (variant.equals("signature")) {
                        String path = input.packageName() + "/" + input.name() + ".java";
                        var binding = java.declarations().rootDestructions().getFirst();
                        String original = java.declarations().sources().get(path); check(original.contains(binding.nativeName()), "missing destruction signature anchor");
                        overrides.put(path, original.replace(binding.nativeName(), binding.nativeName() + "Changed"));
                    } else if (variant.equals("late")) {
                        String register = "(*env)->RegisterNatives(env, validated[index], binding->methods, binding->method_count)";
                        check(nativeSource.contains(register), "missing late registration anchor");
                        nativeSource = FAULT + nativeSource.replace(register, "fixture_register(env, validated[index], binding->methods, binding->method_count)")
                                .replace("(*env)->NewGlobalRef(env,", "fixture_global(env,")
                                .replace("(*env)->DeleteGlobalRef(env,", "fixture_delete_global(env,")
                                .replace("(*env)->UnregisterNatives(env,", "fixture_unregister(env,");
                        nativeSource += "\nJNIEXPORT jlong JNICALL Java_" + generation.supportPackage().replace('.', '_')
                                + "_Probe_metric(JNIEnv *env, jclass type, jint which) { (void)env; (void)type; switch (which) {"
                                + " case 0: return fixture_globals; case 1: return iw_bound; case 2: return iw_ready; case 3: return registrations;"
                                + " case 4: return unregistrations; case 5: return binding_globals; case 6: return (jlong)iw_root_occupied; default: return -1; } }\n";
                        overrides.put(generation.supportPackage().replace('.', '/') + "/Probe.java", "package " + generation.supportPackage()
                                + "; public final class Probe { public static native long metric(int which); }");
                    }
                    Path jar = BridgeGeneratedJarTests.build(folder, llvm, toolchain, level, generation, build, java.declarations(), nativeSource, overrides);
                    var hashes = new TreeMap<String, String>(); overrides.forEach((name, value) -> hashes.put(name, digest(value)));
                    Files.writeString(folder.resolve("scope.txt"), "variant=" + variant + "\ncompiler=" + producer.compilerIdentity() + "\nruntime=" + producer.runtimeIdentity()
                            + "\noriginal-adapters=" + digest(adapters.source()) + "\ncompiled-adapters=" + digest(nativeSource)
                            + "\njava-overrides=" + (hashes.isEmpty() ? "none" : BridgeGeneration.contentIdentity(hashes)) + "\n");
                    built.put(variant, new Built(jar, generation.supportPackage() + ".Support", input.packageName() + "." + input.name(), input.value()));
                }
            }
            for (String scenario : List.of("ab", "ba", "ac", "ca", "ad", "anchor", "signature", "late")) {
                String first = scenario.length() == 2 ? scenario.substring(0, 1) : scenario.equals("anchor") ? "a" : scenario;
                String second = scenario.length() == 2 ? scenario.substring(1) : scenario.equals("late") ? "d" : first;
                var a = built.get(first); var b = built.get(second);
                Path temporary = directory.resolve(level + "-tmp-" + scenario); Files.createDirectories(temporary);
                String output = BridgeEntryTests.run(directory, List.of(javaHome.resolve("bin/java").toString(), "-Xcheck:jni", "-Djava.io.tmpdir=" + temporary,
                        "-cp", directory.toString(), "ObjectCollisionConsumer", scenario, a.jar().toString(), b.jar().toString(), a.support(), b.support(),
                        a.type(), b.type(), Integer.toString(a.value()), Integer.toString(b.value())), "consumer-" + level + "-" + scenario);
                check(output.equals("object-collision-ok:" + scenario + "\n"), output);
            }
        }
        System.out.println("object collision evidence: " + directory);
    }

    private static final String SOURCE = """
            package @PACKAGE@;
            public final class @TYPE@ {
                private static int made;
                private final View view = new View();
                public @TYPE@() { made++; }
                destructor { free view; }
                public static int made() { return made; }
                public int value() { return @VALUE@; }
                public @TYPE@ self() { return this; }
                public View view() { return view; }
                public static final class View { private View() {} public int value() { return 19; } }
            }
            """;
    private static final String FAULT = """
            /* Test-only late registration failure and exact global-reference accounting. */
            #include <jni.h>
            static int registrations, unregistrations;
            static jlong fixture_globals, binding_globals;
            static jobject fixture_global(JNIEnv *env, jobject value) {
                jobject result = (*env)->NewGlobalRef(env, value); if (result != NULL) fixture_globals++; return result;
            }
            static void fixture_delete_global(JNIEnv *env, jobject value) { fixture_globals--; (*env)->DeleteGlobalRef(env, value); }
            static jint fixture_unregister(JNIEnv *env, jclass type) { unregistrations++; return (*env)->UnregisterNatives(env, type); }
            static jint fixture_register(JNIEnv *env, jclass type, const JNINativeMethod *methods, jint count) {
                binding_globals = fixture_globals;
                if (++registrations != 2) return (*env)->RegisterNatives(env, type, methods, count);
                if (count < 1 || (*env)->RegisterNatives(env, type, methods, 1) != 0) return JNI_ERR;
                jclass error = (*env)->FindClass(env, "java/lang/LinkageError");
                if (error != NULL) { (*env)->ThrowNew(env, error, "injected object registration"); (*env)->DeleteLocalRef(env, error); }
                return JNI_ERR;
            }
            """;
    private static final String CONSUMER = """
            import java.lang.ref.WeakReference;
            import java.lang.reflect.*;
            import java.net.URLClassLoader;
            import java.nio.file.*;
            public final class ObjectCollisionConsumer {
                private static void check(boolean value) { if (!value) throw new AssertionError(); }
                @FunctionalInterface private interface Action { void run() throws Throwable; }
                private static void refuse(Action action) throws Throwable {
                    try { action.run(); throw new AssertionError("missing loader refusal"); } catch (LinkageError expected) { }
                }
                private static Object invoke(Object receiver, Class<?> type, String name, Class<?>[] signature, Object... args) throws Throwable {
                    Method method = type.getDeclaredMethod(name, signature); method.setAccessible(true);
                    try { return method.invoke(receiver, args); } catch (InvocationTargetException failed) { throw failed.getCause(); }
                }
                private static Object call(Object value, String name) throws Throwable { return invoke(value, value.getClass(), name, new Class<?>[0]); }
                private static void ensure(ClassLoader loader, String support) throws Throwable {
                    Class<?> type = Class.forName(support, true, loader);
                    Method method = java.util.Arrays.stream(type.getDeclaredMethods()).filter(value -> value.getName().startsWith("$ironwood$ensure")).findFirst().orElseThrow();
                    invoke(null, type, method.getName(), new Class<?>[0]);
                }
                private static long extracted() throws Exception {
                    try (var paths = Files.walk(Path.of(System.getProperty("java.io.tmpdir")))) { return paths.filter(Files::isRegularFile).count(); }
                }
                public static void main(String[] args) throws Throwable {
                    WeakReference<ClassLoader> weak = run(args);
                    if (args[0].equals("anchor")) {
                        for (int i = 0; i < 20; i++) { System.gc(); Thread.sleep(10); }
                        check(weak.get() != null);
                        try (var other = new URLClassLoader(new java.net.URL[]{Path.of(args[1]).toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
                            refuse(() -> ensure(other, args[3]));
                        }
                        Class<?> type = Class.forName(args[5], true, weak.get()); Object value = type.getConstructor().newInstance();
                        check(invoke(null, type, "made", new Class<?>[0]).equals(2)); call(value, "free");
                    }
                    System.out.println("object-collision-ok:" + args[0]);
                }
                private static WeakReference<ClassLoader> run(String[] args) throws Throwable {
                    try (var loader = new URLClassLoader(new java.net.URL[]{Path.of(args[1]).toUri().toURL(), Path.of(args[2]).toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
                        if (args[0].equals("signature") || args[0].equals("late")) {
                            Object disjoint = args[0].equals("late") ? Class.forName(args[6], true, loader).getConstructor().newInstance() : null;
                            refuse(() -> ensure(loader, args[3])); refuse(() -> ensure(loader, args[3]));
                            if (args[0].equals("signature")) check(extracted() == 0);
                            else {
                                Class<?> probe = Class.forName(args[3].replace(".Support", ".Probe"), true, loader);
                                long globals = (Long) invoke(null, probe, "metric", new Class<?>[]{int.class}, 0);
                                check(globals > 2 && invoke(null, probe, "metric", new Class<?>[]{int.class}, 5).equals(globals));
                                long[] expected = {1, 0, 2, 2};
                                for (int i = 0; i < expected.length; i++) check(invoke(null, probe, "metric", new Class<?>[]{int.class}, i + 1).equals(expected[i]));
                                check(invoke(null, probe, "metric", new Class<?>[]{int.class}, 6).equals(0L));
                                check(call(disjoint, "value").equals(Integer.valueOf(args[8]))); call(disjoint, "free");
                            }
                            return new WeakReference<>(loader);
                        }
                        Class<?> type = Class.forName(args[5], true, loader); Object value = type.getConstructor().newInstance();
                        check(call(value, "value").equals(Integer.valueOf(args[7])) && call(value, "self") == value);
                        Object view = call(value, "view"); check(call(value, "view") == view && call(view, "value").equals(19));
                        long files = extracted();
                        if (args[0].equals("ad")) {
                            Class<?> disjoint = Class.forName(args[6], true, loader); Object other = disjoint.getConstructor().newInstance();
                            check(call(other, "value").equals(Integer.valueOf(args[8]))); call(other, "free");
                        } else if (!args[0].equals("anchor")) {
                            refuse(() -> ensure(loader, args[4])); refuse(() -> ensure(loader, args[4])); check(extracted() == files);
                        }
                        check(call(value, "value").equals(Integer.valueOf(args[7])) && call(value, "view") == view);
                        check(invoke(null, type, "made", new Class<?>[0]).equals(1));
                        int hash = value.hashCode(); call(value, "free"); call(value, "free"); check(value.hashCode() == hash);
                        try { call(view, "value"); throw new AssertionError(); } catch (IllegalStateException expected) { }
                        return new WeakReference<>(loader);
                    }
                }
            }
            """;
    private static String digest(String value) { return BridgeGeneration.bytesDigest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
