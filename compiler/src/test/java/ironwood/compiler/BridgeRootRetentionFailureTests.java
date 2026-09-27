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

/** Separately identified fault copies; no recovery or fault API enters production artifacts. */
final class BridgeRootRetentionFailureTests {
    static final String NAME = "Java Bridge retention failures preserve committed counts and indexed holder state";
    private BridgeRootRetentionFailureTests() {}

    static void failures() throws Exception {
        String input = BridgeRootRetentionNativeTests.SOURCE;
        var artifact = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(List.of(SourceFile.of("Holder.iron", input)));
        check(artifact.valid(), artifact.diagnostics().toString());
        var proof = BridgeObjectAdmission.prove(artifact, List.of("retaining")); check(proof.contract().isPresent(), proof.reason());
        var admission = proof.contract().orElseThrow(); var producer = BridgeProducerInputs.discover();
        var generation = BridgeGeneration.createObjects("retention-faults.jar", artifact, admission,
                producer.compilerVersion(), producer.compilerIdentity(), producer.runtimeIdentity());
        var projected = BridgePermanentJavaSources.generateRoots(artifact, admission, generation);
        var declarations = projected.declarations(); var adapters = BridgePermanentNativeSources.generateRoots(artifact, admission, generation, projected);
        String support = generation.supportPackage(), prefix = support.replace('.', '/') + "/";
        var overrides = new TreeMap<String, String>();
        overrides.put(prefix + "RootCache.java", replace(declarations.sources().get(prefix + "RootCache.java"),
                "Entry entry = new Entry(address, facade, collected);", "RetentionFault.trip(8); Entry entry = new Entry(address, facade, collected);"));
        overrides.put(prefix + "RootState.java", replace(declarations.sources().get(prefix + "RootState.java"),
                "if (cache == null) cache = new RootCache();", "if (cache == null) { RetentionFault.trip(9); cache = new RootCache(); }"));
        var facade = projected.facades().stream().filter(value -> value.binaryName().equals("retaining.Holder")).findFirst().orElseThrow();
        String source = declarations.sources().get("retaining/Holder.java"); var changed = new StringBuilder(); int anchors = 0;
        for (String line : source.split("\n", -1)) {
            changed.append(line).append('\n');
            if (line.contains("this." + facade.addressField() + " = ") && line.contains("$ironwood$native")) {
                changed.append("        ").append(support).append(".RetentionFault.afterReturn();\n"); anchors++;
            }
        }
        check(anchors >= 1, "missing constructor interruption anchor"); overrides.put("retaining/Holder.java", changed.toString());
        overrides.put(prefix + "RetentionFault.java", "package " + support + ";\n" + HELPER);
        var hashes = new TreeMap<String, String>(); overrides.forEach((name, value) -> hashes.put(name, digest(value)));
        int rootIndex = projected.facades().stream().filter(BridgePermanentJavaSources.Facade::rooted).toList().indexOf(facade);
        String recovery = RECOVERY.replace("@SYMBOL@", "Java_" + support.replace('.', '_') + "_RetentionFault").replace("@INDEX@", Integer.toString(rootIndex));
        String llvmText = new LlvmEmitter().emit(admission.program());
        Path base = Path.of("workspace/java-bridge/evidence/p3d/retention-failures").toAbsolutePath(); Files.createDirectories(base);
        Path directory = Files.createTempDirectory(base, "run-"), llvm = directory.resolve("program.ll");
        Files.writeString(llvm, llvmText); Files.writeString(directory.resolve("Holder.iron"), input);
        Files.writeString(directory.resolve("scope.txt"), "Test-only preparation/delivery faults, resource accounting and authoritative-index recovery.\n"
                + "Real recursive Java stack exhaustion occurs after native registration/commit with -Xss1m.\nllvm=" + digest(llvmText)
                + "\noriginal-adapters=" + digest(adapters.source()) + "\njava-overrides=" + BridgeGeneration.contentIdentity(hashes)
                + "\ncompiler=" + producer.compilerIdentity() + "\nruntime=" + producer.runtimeIdentity() + "\n");
        var discovery = LlvmToolchain.discover(null); check(discovery.successful(), discovery.error()); var toolchain = discovery.toolchain().orElseThrow();
        var javaHome = Path.of(System.getProperty("java.home"));
        for (var level : List.of(OptimizationLevel.O0, OptimizationLevel.O3)) {
            Path folder = directory.resolve(level.toString()); Files.createDirectories(folder);
            var build = generation.nativeBuild(BridgeGeneratedJarTests.target(), Map.of("fixture", "retention-faults", "llvm", digest(llvmText),
                    "adapters", digest(adapters.source()), "java-overrides", BridgeGeneration.contentIdentity(hashes),
                    "injection", digest(INJECTION + recovery), "optimization", level.toString()));
            String nativeSource = adapters.source() + BridgeBootstrapSources.generate(generation, build, declarations, adapters);
            nativeSource = nativeSource.replace("(*env)->NewGlobalRef(env,", "fixture_global(env,")
                    .replace("(*env)->DeleteGlobalRef(env,", "fixture_delete_global(env,")
                    .replace("(*env)->EnsureLocalCapacity(env,", "fixture_capacity(env,")
                    .replace("(*env)->PushLocalFrame(env,", "fixture_frame(env,");
            nativeSource = replace(nativeSource, "jobject old = (*env)->GetObjectField(env,", "jobject old = fixture_old(env,");
            nativeSource = replace(nativeSource, "outgoing[index] = (*env)->GetObjectField(env,", "outgoing[index] = fixture_outgoing(env,");
            nativeSource = replace(nativeSource, "struct iw_root_record *record = malloc(sizeof(*record));", "struct iw_root_record *record = fixture_record(sizeof(*record));");
            nativeSource = replace(nativeSource, "free(record);", "fixture_free_record(record);");
            nativeSource = replace(nativeSource, "record->address = address; iw_root_insert", "fixture_last = address; record->address = address; iw_root_insert");
            nativeSource = INJECTION + nativeSource + recovery;
            Path jar = BridgeGeneratedJarTests.build(folder, llvm, toolchain, level, generation, build, declarations, nativeSource, overrides);
            Files.writeString(folder.resolve("adapter.sha256"), digest(nativeSource) + "\n");
            Path consumer = folder.resolve("RetentionFailureConsumer.java"); Files.writeString(consumer, "import " + support + ".RetentionFault;\n" + CONSUMER);
            BridgeEntryTests.run(folder, List.of(javaHome.resolve("bin/javac").toString(), "--release", "21", "-Xlint:all", "-Werror", "-cp", jar.toString(), consumer.toString()), "consumer-javac");
            for (int site = 1; site <= 13; site++) {
                var command = new java.util.ArrayList<String>();
                if (site >= 11) command.addAll(List.of("/usr/bin/env", "IRONWOOD_ALLOCATION_LIMIT=" + (site - 7)));
                command.addAll(List.of(javaHome.resolve("bin/java").toString(), "-Xcheck:jni", "-Xmx32m", "-Xss1m", "-cp",
                        jar + java.io.File.pathSeparator + folder, "RetentionFailureConsumer", Integer.toString(site)));
                String output = BridgeEntryTests.run(folder, command, "consumer-" + site);
                check(output.equals("retention-failure-ok:" + site + "\n"), output);
            }
        }
        System.out.println("retention failure evidence: " + directory);
    }

    private static final String HELPER = """
            public final class RetentionFault {
                private static int selected;
                private static boolean consumed;
                private static native void armNative(int site);
                public static native retaining.Holder recover(long address);
                public static native long metric(int which);
                public static void arm(int site) { selected = site; consumed = false; armNative(site); }
                public static void trip(int site) {
                    if (!consumed && selected == site) { consumed = true; throw new OutOfMemoryError("injected retention delivery"); }
                }
                public static void afterReturn() {
                    trip(6);
                    if (!consumed && selected == 7) { consumed = true; exhaust(0); }
                }
                private static int exhaust(int depth) { return exhaust(depth + 1) + 1; }
                public static boolean refusal(Throwable value) { return value.getClass() == BridgeLifetimeException.class; }
                private static RootState state(Object value) throws Exception {
                    for (var field : value.getClass().getDeclaredFields()) if (field.getType() == RootState.class) {
                        field.setAccessible(true); return (RootState) field.get(value);
                    }
                    throw new AssertionError();
                }
                public static long count(Object value) throws Exception {
                    var field = RootState.class.getDeclaredField("incoming"); field.setAccessible(true); return field.getLong(state(value));
                }
                public static long address(Object value) throws Exception { return state(value).address(); }
            }
            """;
    private static final String CONSUMER = """
            import retaining.Holder;
            import java.lang.ref.WeakReference;
            public final class RetentionFailureConsumer {
                private static void check(boolean value) { if (!value) throw new AssertionError(); }
                private static void count(Object value, long expected) throws Exception { check(RetentionFault.count(value) == expected); }
                private static void retained(Holder.Item value) {
                    int entered = Holder.entered(), destroyed = Holder.destroyed();
                    try { value.free(); throw new AssertionError(); }
                    catch (IllegalStateException expected) { check(RetentionFault.refusal(expected)); }
                    check(Holder.entered() == entered && Holder.destroyed() == destroyed);
                }
                public static void main(String[] args) throws Exception {
                    int site = Integer.parseInt(args[0]); long live = Holder.live(), globals = RetentionFault.metric(0);
                    Holder.Item a = new Holder.Item(11), b = new Holder.Item(29); Holder h = new Holder(null, a, false);
                    long allocations = Holder.allocations(); int entered = Holder.entered(), destroyed = Holder.destroyed();
                    if (site == 10) {
                        long address = RetentionFault.address(h); WeakReference<Holder> weak = new WeakReference<>(h); h = null;
                        for (int i = 0; i < 200 && weak.get() != null; i++) { System.gc(); Thread.sleep(10); }
                        check(weak.get() == null); count(a, 1); retained(a);
                        check(RetentionFault.metric(1) == 3 && RetentionFault.metric(0) == globals + 3);
                        h = RetentionFault.recover(address); check(h.value() == 11 && RetentionFault.recover(address) == h);
                    } else {
                        RetentionFault.arm(site);
                        try {
                            if (site <= 2) h.two(b);
                            else if (site == 3) h.free();
                            else if (site == 5) h.fail(b);
                            else new Holder(h, b, site >= 11);
                            throw new AssertionError("missing fault " + site);
                        } catch (OutOfMemoryError expected) { check(site != 7); }
                        catch (StackOverflowError expected) { check(site == 7); }
                        check(Holder.destroyed() == destroyed);
                        if (site <= 4) {
                            count(a, 1); count(b, 0); check(h.value() == 11 && Holder.entered() == entered && Holder.allocations() == allocations);
                            check(RetentionFault.metric(1) == 3 && RetentionFault.metric(0) == globals + 3);
                        } else if (site >= 11) {
                            count(a, site == 11 ? 1 : 0); count(b, site == 11 ? 0 : 1);
                            check(h.value() == (site == 11 ? 11 : 29) && Holder.entered() == entered + (site == 11 ? 0 : 1));
                            check(RetentionFault.metric(1) == 3 && RetentionFault.metric(0) == globals + 3);
                        } else if (site == 5) {
                            count(a, 0); count(b, 1); retained(b); check(h.value() == 29 && Holder.entered() == entered + 1);
                        } else {
                            count(a, 0); count(b, 2); retained(b); check(h.value() == 29 && Holder.entered() == entered + 1);
                            check(RetentionFault.metric(1) == 4 && RetentionFault.metric(0) == globals + 4);
                            Holder recovered = RetentionFault.recover(0); check(recovered != h && recovered.value() == 29);
                            check(RetentionFault.recover(0) == recovered); recovered.free(); recovered.free(); count(b, 1);
                        }
                    }
                    h.free(); h.free(); count(a, 0); count(b, 0); a.free(); b.free();
                    check(RetentionFault.metric(0) == globals && RetentionFault.metric(1) == 0 && RetentionFault.metric(2) == 0);
                    check(Holder.live() == live + (site == 5 ? 1 : 0));
                    System.out.println("retention-failure-ok:" + site);
                }
            }
            """;
    private static final String INJECTION = """
            /* Test-only resource accounting and armed one-shot faults. */
            #include <jni.h>
            #include <stdint.h>
            #include <stdlib.h>
            static int fixture_site, fixture_consumed, fixture_reads;
            static int64_t fixture_globals, fixture_records;
            static void *fixture_last;
            static int fixture_fail(int site) {
                if (!fixture_consumed && fixture_site == site) { fixture_consumed = 1; return 1; } return 0;
            }
            static void fixture_oom(JNIEnv *env) {
                jclass type = (*env)->FindClass(env, "java/lang/OutOfMemoryError");
                if (type != NULL) { (*env)->ThrowNew(env, type, "injected retention host failure"); (*env)->DeleteLocalRef(env, type); }
            }
            static jobject fixture_global(JNIEnv *env, jobject value) {
                jobject result = (*env)->NewGlobalRef(env, value); if (result != NULL) fixture_globals++; return result;
            }
            static void fixture_delete_global(JNIEnv *env, jobject value) { fixture_globals--; (*env)->DeleteGlobalRef(env, value); }
            static jint fixture_capacity(JNIEnv *env, jint count) {
                if (fixture_fail(1)) { fixture_oom(env); return JNI_ERR; } return (*env)->EnsureLocalCapacity(env, count);
            }
            static jint fixture_frame(JNIEnv *env, jint count) {
                if (fixture_fail(5)) { fixture_oom(env); return JNI_ERR; } return (*env)->PushLocalFrame(env, count);
            }
            static jobject fixture_old(JNIEnv *env, jobject value, jfieldID field) {
                if (++fixture_reads == 2 && fixture_fail(2)) { fixture_oom(env); return NULL; } return (*env)->GetObjectField(env, value, field);
            }
            static jobject fixture_outgoing(JNIEnv *env, jobject value, jfieldID field) {
                if (++fixture_reads == 2 && fixture_fail(3)) { fixture_oom(env); return NULL; } return (*env)->GetObjectField(env, value, field);
            }
            static void *fixture_record(size_t size) {
                if (fixture_fail(4)) return NULL; void *record = malloc(size); if (record != NULL) fixture_records++; return record;
            }
            static void fixture_free_record(void *record) { fixture_records--; free(record); }
            """;
    private static final String RECOVERY = """
            JNIEXPORT void JNICALL @SYMBOL@_armNative(JNIEnv *env, jclass type, jint site) {
                (void)env; (void)type; fixture_site = site; fixture_consumed = 0; fixture_reads = 0;
            }
            JNIEXPORT jobject JNICALL @SYMBOL@_recover(JNIEnv *env, jclass type, jlong address) {
                (void)type; struct iw_root_record **found = iw_root_find(address == 0 ? fixture_last : (void *)(uintptr_t)address);
                return found == NULL ? NULL : iw_root_wrap(env, @INDEX@, (*found)->address, (*found)->state);
            }
            JNIEXPORT jlong JNICALL @SYMBOL@_metric(JNIEnv *env, jclass type, jint which) {
                (void)env; (void)type;
                switch (which) { case 0: return fixture_globals; case 1: return fixture_records; case 2: return (jlong)iw_root_occupied; default: return -1; }
            }
            """;
    private static String replace(String text, String before, String after) { check(text.contains(before), "missing retention fault anchor: " + before); return text.replace(before, after); }
    private static String digest(String value) { return BridgeGeneration.bytesDigest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
