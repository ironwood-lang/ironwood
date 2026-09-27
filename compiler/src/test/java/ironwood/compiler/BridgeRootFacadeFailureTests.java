// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.backend.*;
import ironwood.compiler.bridge.*;
import ironwood.compiler.source.SourceFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Fault hooks and recovery entrypoints exist only in separately identified test artifacts. */
final class BridgeRootFacadeFailureTests {
    static final String NAME = "Java Bridge root delivery failures preserve authoritative registration and preparation resources";
    private BridgeRootFacadeFailureTests() {}

    static void failures() throws Exception {
        String source = BridgeRootFacadeNativeTests.SOURCE.replace("public Root() {}", """
                public Root() {}
                public Root(String text) { if (text.length() == 0) throw null; }
                public static int use(Root value) { entered++; return value == null ? 0 : value.fastValue(); }
                """);
        var artifact = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(List.of(SourceFile.of("Root.iron", source)));
        check(artifact.valid(), artifact.diagnostics().toString());
        var proof = BridgeObjectAdmission.prove(artifact, List.of("rootjava")); check(proof.contract().isPresent(), proof.reason());
        var admission = proof.contract().orElseThrow(); var producer = BridgeProducerInputs.discover();
        var generation = BridgeGeneration.createObjects("root-failures.jar", artifact, admission,
                producer.compilerVersion(), producer.compilerIdentity(), producer.runtimeIdentity());
        var projected = BridgePermanentJavaSources.generateRoots(artifact, admission, generation);
        var declarations = projected.declarations(); var adapters = BridgePermanentNativeSources.generateRoots(artifact, admission, generation, projected);
        String support = generation.supportPackage(), prefix = support.replace('.', '/') + "/";
        var overrides = new java.util.TreeMap<String, String>();
        overrides.put(prefix + "RootCache.java", replace(declarations.sources().get(prefix + "RootCache.java"),
                "Entry entry = new Entry(address, facade, collected);", "TestFault.trip(\"cache\"); Entry entry = new Entry(address, facade, collected);"));
        overrides.put(prefix + "RootState.java", replace(declarations.sources().get(prefix + "RootState.java"),
                "if (cache == null) cache = new RootCache();", "if (cache == null) { TestFault.trip(\"state-cache\"); cache = new RootCache(); }"));
        var rootFacade = projected.facades().stream().filter(value -> value.binaryName().equals("rootjava.Root")).findFirst().orElseThrow();
        String rootSource = declarations.sources().get("rootjava/Root.java");
        var changed = new StringBuilder(); int anchors = 0;
        for (String line : rootSource.split("\n", -1)) {
            changed.append(line).append('\n');
            if (line.contains("this." + rootFacade.addressField() + " = ") && line.contains("$ironwood$native")) {
                changed.append("        ").append(support).append(".TestFault.afterReturn();\n"); anchors++;
            }
        }
        check(anchors >= 3, "root constructor post-return anchors changed"); overrides.put("rootjava/Root.java", changed.toString());
        overrides.put(prefix + "TestFault.java", "package " + support + ";\n" + FAULT);
        String llvmText = new LlvmEmitter().emit(admission.program());
        Path base = Path.of("workspace/java-bridge/evidence/p3c/root-host-failures").toAbsolutePath(); Files.createDirectories(base);
        Path directory = Files.createTempDirectory(base, "run-"), llvm = directory.resolve("program.ll");
        Files.writeString(llvm, llvmText); Files.writeString(directory.resolve("Root.iron"), source);
        var hashes = new java.util.TreeMap<String, String>(); overrides.forEach((name, value) -> hashes.put(name, digest(value)));
        Files.writeString(directory.resolve("scope.txt"), "Test-only preparation/delivery faults, resource counters and native-index recovery. No production fault hooks.\n"
                + "Injected StackOverflowError is interruption evidence; real-stack recursively exhausts Java stack after registration with -Xss1m.\nllvm=" + digest(llvmText)
                + "\noriginal-adapters=" + digest(adapters.source()) + "\njava-overrides=" + BridgeGeneration.contentIdentity(hashes)
                + "\ncompiler=" + producer.compilerIdentity() + "\nruntime=" + producer.runtimeIdentity() + "\n");
        var discovery = LlvmToolchain.discover(null); check(discovery.successful(), discovery.error()); var toolchain = discovery.toolchain().orElseThrow();
        Path javaHome = Path.of(System.getProperty("java.home"));
        int rootIndex = projected.facades().stream().filter(BridgePermanentJavaSources.Facade::rooted).toList().indexOf(rootFacade);
        String recovery = RECOVERY.replace("@SYMBOL@", "Java_" + support.replace('.', '_') + "_TestFault").replace("@INDEX@", Integer.toString(rootIndex));
        for (var level : List.of(OptimizationLevel.O0, OptimizationLevel.O3)) {
            Path folder = directory.resolve(level.toString()); Files.createDirectories(folder);
            var build = generation.nativeBuild("macos-arm64", Map.of("fixture", "injected-root-failures", "llvm", digest(llvmText),
                    "adapters", digest(adapters.source()), "java-overrides", BridgeGeneration.contentIdentity(hashes),
                    "injection", digest(INJECTION + recovery), "optimization", level.toString()));
            String nativeSource = adapters.source() + BridgeBootstrapSources.generate(generation, build, declarations, adapters);
            nativeSource = nativeSource.replace("(*env)->NewGlobalRef(env,", "fixture_global(env,")
                    .replace("(*env)->DeleteGlobalRef(env,", "fixture_delete_global(env,")
                    .replace("(*env)->GetStringChars(env,", "fixture_chars(env,")
                    .replace("(*env)->ReleaseStringChars(env,", "fixture_release_chars(env,");
            nativeSource = replace(nativeSource, "record->state = fixture_global(env, state);", "record->state = fixture_root_global(env, state);");
            nativeSource = replace(nativeSource, "if ((*env)->EnsureLocalCapacity(env, 8) != JNI_OK)", "if (fixture_capacity(env, 8) != JNI_OK)");
            nativeSource = replace(nativeSource, "struct iw_root_record *record = malloc(sizeof(*record));", "struct iw_root_record *record = fixture_record(env, sizeof(*record));");
            nativeSource = replace(nativeSource, "free(record);", "fixture_free_record(record);");
            nativeSource = replace(nativeSource, "calloc(next, sizeof(*iw_root_table))", "fixture_table(next, sizeof(*iw_root_table))");
            nativeSource = replace(nativeSource, "record->address = address; iw_root_insert", "fixture_last = address; record->address = address; iw_root_insert");
            nativeSource = replace(nativeSource, "(*env)->NewObject(env, iw_root_types[index], iw_root_constructors[index], bits, state, (jobject)NULL)",
                    "fixture_facade(env, iw_root_types[index], iw_root_constructors[index], bits, state)");
            nativeSource = INJECTION + nativeSource + recovery;
            Path jar = BridgeGeneratedJarTests.build(folder, llvm, toolchain, level, generation, build, declarations, nativeSource, overrides);
            Files.writeString(folder.resolve("adapter.sha256"), digest(nativeSource) + "\n");
            Path consumer = folder.resolve("RootFailureConsumer.java"); Files.writeString(consumer, "import " + support + ".TestFault;\n" + CONSUMER);
            BridgeEntryTests.run(folder, List.of(javaHome.resolve("bin/javac").toString(), "--release", "21", "-Xlint:all", "-Werror", "-cp", jar.toString(), consumer.toString()), "consumer-javac");
            for (String site : List.of("capacity", "record", "growth", "global", "facade", "cache", "state-cache", "after-return", "stack-after-return", "real-stack", "heap",
                    "budget0", "budget1", "budget2", "budget3", "budget4")) {
                var command = new java.util.ArrayList<>(List.of("/usr/bin/env", "IRONWOOD_ROOT_FAILURE=" + site));
                if (site.startsWith("budget")) command.add("IRONWOOD_ALLOCATION_LIMIT=" + site.substring(6));
                command.addAll(List.of(javaHome.resolve("bin/java").toString(), "-Xcheck:jni", "-Xmx32m", "-Xss1m", "-Dironwood.fixture.site=" + site,
                        "-cp", jar + java.io.File.pathSeparator + folder, "RootFailureConsumer", site));
                String output = BridgeEntryTests.run(folder, command, "consumer-" + site);
                check(output.equals("root-host-failure-ok:" + site + "\n"), output);
            }
        }
        System.out.println("root host failure evidence: " + directory);
    }
    private static String replace(String text, String before, String after) { check(text.contains(before), "missing root fault anchor: " + before); return text.replace(before, after); }
    private static String digest(String value) { return BridgeGeneration.bytesDigest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }

    private static final String FAULT = """
            public final class TestFault {
                private static final String site = System.getProperty("ironwood.fixture.site", "");
                private static boolean consumed;
                public static native Object recover();
                public static native long metric(int which);
                public static void trip(String current) {
                    if (!consumed && site.equals(current)) { consumed = true; throw new OutOfMemoryError("injected " + current); }
                }
                public static void afterReturn() {
                    trip("after-return");
                    if (!consumed && site.equals("stack-after-return")) { consumed = true; throw new StackOverflowError("injected after registration"); }
                    if (!consumed && site.equals("real-stack")) { consumed = true; exhaust(0); }
                }
                private static int exhaust(int depth) { return exhaust(depth + 1) + 1; }
            }
            """;
    private static final String CONSUMER = """
            import rootjava.Root;
            public final class RootFailureConsumer {
                private static Object pressure;
                private static void check(boolean value) { if (!value) throw new AssertionError(); }
                public static void main(String[] args) {
                    String site = args[0]; long live = Root.live(), allocations = Root.allocations(), globals = TestFault.metric(0);
                    if (site.equals("heap")) { heap(live, globals); return; }
                    if (site.startsWith("budget")) { budget(site, live, globals); return; }
                    boolean prepare = java.util.Set.of("capacity", "record", "growth", "global").contains(site);
                    try {
                        if (site.equals("facade")) Root.fresh(false); else new Root("fixture");
                        throw new AssertionError("missing " + site);
                    } catch (OutOfMemoryError expected) { check(!site.equals("stack-after-return") && !site.equals("real-stack")); }
                    catch (StackOverflowError expected) { check(site.equals("stack-after-return") || site.equals("real-stack")); }
                    check(TestFault.metric(2) == 0);
                    Root recovered;
                    if (prepare) {
                        check(Root.live() == live && Root.allocations() == allocations && TestFault.metric(0) == globals
                                && TestFault.metric(1) == 0 && TestFault.metric(3) == 0 && TestFault.recover() == null);
                        recovered = new Root();
                    } else {
                        check(Root.live() == live + 3 && TestFault.metric(0) == globals + 1 && TestFault.metric(1) == 1 && TestFault.metric(3) == 1);
                        recovered = (Root) TestFault.recover();
                    }
                    check(recovered != null && recovered.fastValue() == 17 && recovered.self() == recovered && TestFault.recover() == recovered);
                    check(TestFault.metric(0) == globals + 1 && TestFault.metric(1) == 1 && TestFault.metric(3) == 1);
                    int destroyed = Root.destroyed(); recovered.free(); recovered.free();
                    check(Root.destroyed() == destroyed + 1 && Root.live() == live && TestFault.metric(0) == globals
                            && TestFault.metric(1) == 0 && TestFault.metric(2) == 0 && TestFault.metric(3) == 0 && TestFault.recover() == null);
                    System.out.println("root-host-failure-ok:" + site);
                }
                private static void budget(String site, long live, long globals) {
                    int budget = Integer.parseInt(site.substring(6));
                    try {
                        Root value = new Root("fixture"); check(budget == 4 && value.fastValue() == 17 && value.self() == value); value.free();
                    } catch (OutOfMemoryError expected) { check(budget < 4); }
                    check(Root.live() == live && TestFault.metric(0) == globals && TestFault.metric(1) == 0 && TestFault.metric(2) == 0 && TestFault.metric(3) == 0);
                    System.out.println("root-host-failure-ok:" + site);
                }
                private static final class Bulk {
                    final Object next;
                    final byte[] data = new byte[32768];
                    Bulk(Object next) { this.next = next; }
                }
                private static final class Tiny {
                    final Object next;
                    Tiny(Object next) { this.next = next; }
                }
                private static void heap(long live, long globals) {
                    Root owner = new Root(); Root.Child child = owner.child();
                    for (int i = 0; i < 10000; i++) { check(owner.value() == 17 && Root.use(owner) == 17); }
                    owner.free(); int entered = Root.entered(), destroyed = Root.destroyed();
                    pressure = new Tiny(null);
                    try { while (true) pressure = new Bulk(pressure); } catch (OutOfMemoryError exhausted) { }
                    try { while (true) pressure = new Tiny(pressure); } catch (OutOfMemoryError exhausted) { }
                    Throwable receiverFailure = null, argumentFailure = null;
                    try { child.value(); } catch (Throwable failed) { receiverFailure = failed; }
                    try { Root.use(owner); } catch (Throwable failed) { argumentFailure = failed; }
                    pressure = null; System.gc();
                    check(receiverFailure instanceof OutOfMemoryError && argumentFailure instanceof OutOfMemoryError);
                    check(Root.entered() == entered && Root.destroyed() == destroyed && Root.live() == live);
                    Root retry = new Root(); check(retry.fastValue() == 17); retry.free();
                    check(Root.live() == live && TestFault.metric(0) == globals && TestFault.metric(1) == 0 && TestFault.metric(2) == 0);
                    System.out.println("root-host-failure-ok:heap");
                }
            }
            """;
    private static final String INJECTION = """
            /* Test-only resource accounting and one-shot faults. */
            #include <jni.h>
            #include <stdint.h>
            #include <stdlib.h>
            #include <string.h>
            static int fixture_consumed;
            static int64_t fixture_globals, fixture_records, fixture_pins;
            static void *fixture_last;
            static int fixture_fail(const char *site) {
                const char *selected = getenv("IRONWOOD_ROOT_FAILURE");
                if (!fixture_consumed && selected != NULL && strcmp(selected, site) == 0) { fixture_consumed = 1; return 1; }
                return 0;
            }
            static void fixture_oom(JNIEnv *env) {
                jclass type = (*env)->FindClass(env, "java/lang/OutOfMemoryError");
                if (type != NULL) { (*env)->ThrowNew(env, type, "injected root host failure"); (*env)->DeleteLocalRef(env, type); }
            }
            static jobject fixture_global(JNIEnv *env, jobject value) {
                jobject result = (*env)->NewGlobalRef(env, value); if (result != NULL) fixture_globals++; return result;
            }
            static void fixture_delete_global(JNIEnv *env, jobject value) { fixture_globals--; (*env)->DeleteGlobalRef(env, value); }
            static jobject fixture_root_global(JNIEnv *env, jobject value) { return fixture_fail("global") ? NULL : fixture_global(env, value); }
            static jint fixture_capacity(JNIEnv *env, jint count) {
                if (fixture_fail("capacity")) { fixture_oom(env); return JNI_ERR; } return (*env)->EnsureLocalCapacity(env, count);
            }
            static void *fixture_record(JNIEnv *env, size_t size) {
                (void)env; if (fixture_fail("record")) return NULL;
                void *result = malloc(size); if (result != NULL) fixture_records++; return result;
            }
            static void fixture_free_record(void *record) { fixture_records--; free(record); }
            static void *fixture_table(size_t count, size_t size) { return fixture_fail("growth") ? NULL : calloc(count, size); }
            static const jchar *fixture_chars(JNIEnv *env, jstring value, jboolean *copy) {
                const jchar *result = (*env)->GetStringChars(env, value, copy); if (result != NULL) fixture_pins++; return result;
            }
            static void fixture_release_chars(JNIEnv *env, jstring value, const jchar *chars) { fixture_pins--; (*env)->ReleaseStringChars(env, value, chars); }
            static jobject fixture_facade(JNIEnv *env, jclass type, jmethodID constructor, jlong address, jobject state) {
                if (fixture_fail("facade")) { fixture_oom(env); return NULL; }
                return (*env)->NewObject(env, type, constructor, address, state, (jobject)NULL);
            }
            """;
    private static final String RECOVERY = """
            /* Recovery probes the authoritative index after interrupted delivery; it grants no production API. */
            JNIEXPORT jobject JNICALL @SYMBOL@_recover(JNIEnv *env, jclass type) {
                (void)type; struct iw_root_record **found = iw_root_find(fixture_last);
                return found == NULL ? NULL : iw_root_wrap(env, @INDEX@, (*found)->address, (*found)->state);
            }
            JNIEXPORT jlong JNICALL @SYMBOL@_metric(JNIEnv *env, jclass type, jint which) {
                (void)env; (void)type;
                switch (which) {
                    case 0: return fixture_globals; case 1: return fixture_records; case 2: return fixture_pins;
                    case 3: return (jlong)iw_root_occupied; default: return -1;
                }
            }
            """;
}
