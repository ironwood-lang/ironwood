// SPDX-License-Identifier: MIT OR Apache-2.0
package ironwood.compiler;

import ironwood.compiler.backend.*;
import ironwood.compiler.bridge.*;
import ironwood.compiler.source.SourceFile;
import java.nio.file.*;
import java.util.*;

final class Gc6CallbackTests {
    static final String NAME = "GC6 managed callbacks bind exact contexts and invocation JNI completion";
    private Gc6CallbackTests() {}

    record Fixture(CompilationArtifact artifact, BridgeListenerProxies listeners, BridgeCallbackCarrierSources carrier) {}

    static Fixture fixture(int escape) {

        var sources = new ArrayList<>(List.of(SourceFile.of("Listener.iron", """
                package gc6callbacks;
                public interface Listener {

                    int call(int value, Node node);

                    void event(long value);
                }
                """), SourceFile.of("Node.iron", """
                package gc6callbacks;
                public final class Node {

                    private final int number;
                    private Listener listener;
                    private static Node saved;

                    public Node(int value) {

                        this.number = value;
                    }

                    public Node(int value, Listener listener, boolean fail) {

                        this.number = value;
                        this.listener = listener;
                        saved = this;
                        if (fail) {
                            throw new IllegalArgumentException("constructed listener");
                        }
                    }

                    public int install(Listener listener, boolean fail) {

                        this.listener = listener;
                        if (fail) {
                            throw new IllegalArgumentException("committed listener");
                        }
                        return this.number;
                    }

                    public Node installAlias(Listener listener) {

                        this.listener = listener;
                        return this;
                    }

                    public int installIf(Listener listener, boolean write, int marker) {

                        if (write) {
                            this.listener = listener;
                        }
                        return this.number + marker;
                    }

                    public int installAndCall(Listener listener) {

                        this.listener = listener;
                        return listener.call(this.number, this);
                    }

                    public void unregister() {

                        this.listener = null;
                    }

                    public void burst(int count) {

                        Listener listener = this.listener;
                        for (int i = 0; i < count; i++) {
                            listener.event((long) i);
                        }
                    }

                    public void impureBurst(int count) {

                        Listener listener = this.listener;
                        for (int i = 0; i < count; i++) {
                            ironwood.lang.Memory.collect();
                            listener.event((long) i);
                        }
                    }

                    public int fire(int value) {

                        Listener listener = this.listener;
                        return listener == null ? -1 : listener.call(value, this);
                    }

                    public int fireBoth(int value, Listener second) {

                        Listener first = this.listener;
                        int initial = second.call(value, this);
                        return initial + first.call(value + 1, this);
                    }

                    public static Node saved() {

                        return saved;
                    }

                    public static void clearSaved() {

                        saved = null;
                        ironwood.lang.Memory.collect();
                    }

                    public int value() {

                        ironwood.lang.Memory.collect();
                        return this.number;
                    }

                    public static Node run(Listener listener, int value) {

                        Node outer = new Node(value);
                        try {
                            int result = listener.call(value, outer);
                            ironwood.lang.Memory.collect();
                            if (result != value + 1) {
                                throw new IllegalArgumentException("callback result");
                            }
                            return outer;
                        } finally {
                            ironwood.lang.Memory.collect();
                        }
                    }

                    public static int failure(Listener listener, int mode) {

                        try {
                            return listener.call(9, null);
                        } catch (RuntimeException failure) {
                            ironwood.lang.Memory.collect();
                            if (mode == 0) {
                                return 77;
                            }
                            if (mode == 2) {
                                throw new IllegalArgumentException("replacement");
                            }
                            throw failure;
                        } finally {
                            ironwood.lang.Memory.collect();
                        }
                    }

                    public static int deferred(Listener listener) {

                        defer listener.event(2L);
                        return listener.call(3, null);
                    }

                    public static Node pending(Listener listener, int value) {

                        Node unseen = new Node(value);
                        defer listener.event(6L);
                        return unseen;
                    }

                    public static void collect() {

                        ironwood.lang.Memory.collect();
                    }

                    public static String borrowed(Listener listener, ironwood.bridge.ByteView view, int[] values, String text, boolean fail) {

                        view.put(0, (byte) (view.get(0) + 1));
                        values[0] += 2;
                        listener.call(11, null);
                        ironwood.lang.Memory.collect();
                        view.put(1, (byte) (view.get(0) + 1));
                        values[0] += 3;
                        if (fail) {
                            throw new IllegalArgumentException("borrowed failure");
                        }
                        return text;
                    }

                    public static long live() {

                        return System.liveAllocationCount();
                    }

                    public static long allocations() {

                        return System.allocationCount();
                    }
                }
                """)));
        if (escape == 1) sources.set(1, SourceFile.of("Node.iron", sources.get(1).content()
                .replace("private final int number;", "private final int number; private static Throwable escapedFailure;")
                .replace("throw failure;", "escapedFailure = failure; throw failure;")));
        if (escape == 2) sources.set(1, SourceFile.of("Node.iron", sources.get(1).content()
                .replace("private final int number;", "private final int number; private static Throwable escapedFailure;")
                .replace("public static long live()", """
                    public static int external(Listener listener) {

                        RuntimeException primary = new RuntimeException("published");
                        escapedFailure = primary;
                        try {
                            throw primary;
                        } finally {
                            listener.call(9, null);
                        }
                    }

                    public static long live()
                    """)));
        if (escape == 3) sources.set(1, SourceFile.of("Node.iron", sources.get(1).content()
                .replace("public static long live()", """
                    private static final class Cached {

                        private static int value = initialize();

                        private static int initialize() {

                            throw new IllegalArgumentException("cached");
                        }
                    }

                    public static int cached(Listener listener) {

                        try {
                            return Cached.value;
                        } finally {
                            listener.call(9, null);
                        }
                    }

                    public static long live()
                    """)));
        if (escape == 4) sources.set(1, SourceFile.of("Node.iron", sources.get(1).content()
                .replace("private final int number;", "private final int number; private static ironwood.bridge.ByteView escapedView;")
                .replace("view.put(0,", "escapedView = view; view.put(0,")));
        if (escape == 5) sources.set(1, SourceFile.of("Node.iron", sources.get(1).content()
                .replace("private final int number;", "private final int number; private static int[] escapedArray;")
                .replace("values[0] += 2;", "escapedArray = values; values[0] += 2;")));
        var pipeline = new CompilerPipeline(UnfreedMode.ERROR);
        var initial = pipeline.analyzeForBridge(sources);
        check(initial.valid(), initial.diagnostics().toString());
        var carrier = BridgeCallbackCarrierSources.discover(initial);
        sources.add(carrier.source());
        var complete = pipeline.analyzeForBridge(sources);
        check(complete.valid(), complete.diagnostics().toString());
        var listeners = BridgeListenerProxies.discover(complete, List.of("gc6callbacks"));
        var artifact = pipeline.analyzeForBridge(sources, listeners);
        check(artifact.valid(), artifact.diagnostics().toString());
        return new Fixture(artifact, listeners, carrier);
    }

    static void proofs() throws Exception {

        var fixture = fixture(0);
        check(BridgeObjectAdmission.proveManaged(fixture.artifact(), List.of("gc6callbacks")).status() != BridgeProof.Status.PROVED,
                "listener admitted without exact proxy capabilities");
        var proof = admission(fixture);
        check(proof.batching().orElseThrow().entries().keySet().stream().map(BridgeCallableId::name).toList().equals(List.of("burst")),
                "batching omitted the pure schedule or admitted the collecting loop");
        var generation = BridgeGeneration.createObjects("gc6callbacks", fixture.artifact(), proof, "test", "a".repeat(64), "b".repeat(64));
        var java = BridgePermanentJavaSources.generateRoots(fixture.artifact(), proof, generation);
        var nativeSources = BridgePermanentNativeSources.generateRoots(fixture.artifact(), proof, generation, java);
        check(java.declarations().sources().get("gc6callbacks/Listener.java").contains("public interface Listener"), "listener signature omitted");
        check(java.declarations().supportNatives().stream().anyMatch(value -> value.nativeName().equals("$release")), "callback dispatch lost handle release");
        check(nativeSources.source().contains("&callback_frame.created_carriers") && nativeSources.source().contains("iw_gc_callback_wrap"),
                "callback omitted exact native roots or argument anchors");
        check(!nativeSources.source().contains("ironwood_deallocate("), "managed callback generated reclamation");
        check(new LlvmEmitter().emit(proof).contains("$bridge_context"), "callback lost explicit invocation context");
        String facade = java.declarations().sources().get("gc6callbacks/Node.java");
        check(!facade.contains("public int install(") && !facade.contains(" installAlias("),
                "retaining call bypasses registration owner");
        for (int escape : new int[]{4, 5}) {
            var unsafe = fixture(escape);
            var refused = BridgeObjectAdmission.proveManaged(unsafe.artifact(), List.of("gc6callbacks"), unsafe.listeners(), unsafe.carrier());
            check(refused.status() != BridgeProof.Status.PROVED, "borrowed storage escaped its invocation");
        }
        check(java.declarations().sources().get(generation.supportPackage().replace('.', '/') + "/Listeners.java").contains("ListenerRegistration"),
                "retaining projection omitted the acquired owner");
        for (int escape : new int[]{1, 2, 3}) {
            var escaping = fixture(escape);
            var refused = BridgeObjectAdmission.proveManaged(escaping.artifact(), List.of("gc6callbacks"), escaping.listeners(), escaping.carrier());
            check(refused.status() != BridgeProof.Status.PROVED && refused.reason().contains("invocation completion"), refused.reason());
        }
        Path directory = Files.createTempDirectory(Path.of("workspace"), "gc6-callback-inputs-");
        var units = proof.surface().types().stream().filter(type -> type.enclosingType().isEmpty()).toList();
        var loose = new ArrayList<Path>();
        for (var type : units) {
            Path file = directory.resolve(type.binaryName().substring(type.binaryName().lastIndexOf('.') + 1) + ".ironclass");
            IronClass.write(file, SourceParser.parse(type.source()).unit().orElseThrow(), type.binaryName()); loose.add(file);
        }
        Path archive = directory.resolve("callbacks.ironjar"); IronJar.create(archive, loose);
        for (boolean archived : new boolean[]{false, true}) {
            var sources = new ArrayList<SourceFile>();
            for (int i = 0; i < units.size(); i++) sources.add(archived
                    ? IronJar.read(archive).source(units.get(i).binaryName()).orElseThrow()
                    : IronClass.read(loose.get(i)).source(units.get(i).binaryName()).orElseThrow());
            var pipeline = new CompilerPipeline(UnfreedMode.ERROR);
            var initial = pipeline.analyzeForBridge(sources);
            var carrier = BridgeCallbackCarrierSources.discover(initial); sources.add(carrier.source());
            var proxies = BridgeListenerProxies.discover(pipeline.analyzeForBridge(sources), List.of("gc6callbacks"));
            var restored = pipeline.analyzeForBridge(sources, proxies);
            var restoredAdmission = admission(new Fixture(restored, proxies, carrier));
            var restoredGeneration = BridgeGeneration.createObjects("gc6callbacks", restored, restoredAdmission, "test", "a".repeat(64), "b".repeat(64));
            check(restoredGeneration.identity().equals(generation.identity()), "callback reconstruction changed generation");
            check(BridgePermanentJavaSources.generateRoots(restored, restoredAdmission, restoredGeneration).declarations().sources()
                    .equals(java.declarations().sources()), "callback reconstruction changed lifetime projection");
        }
    }

    static BridgeObjectAdmission admission(Fixture fixture) {

        var proof = BridgeObjectAdmission.proveManaged(fixture.artifact(), List.of("gc6callbacks"), fixture.listeners(), fixture.carrier());
        check(proof.status() == BridgeProof.Status.PROVED, proof.reason());
        return proof.contract().orElseThrow();
    }

    public static void main(String[] args) throws Exception {

        var fixture = fixture(0);
        var admission = admission(fixture);
        var inputs = BridgeProducerInputs.discover();
        var generation = BridgeGeneration.createObjects("gc6callbacks", fixture.artifact(), admission,
                inputs.compilerVersion(), inputs.compilerIdentity(), inputs.runtimeIdentity());
        var java = BridgePermanentJavaSources.generateRoots(fixture.artifact(), admission, generation);
        var nativeSources = BridgePermanentNativeSources.generateRoots(fixture.artifact(), admission, generation, java);
        var level = OptimizationLevel.valueOf(args[2]);
        var tools = LlvmToolchain.discover(Path.of(args[1])); check(tools.successful(), tools.error());
        var buildInputs = new TreeMap<>(BridgeGcBuildInputs.discover(tools.toolchain().orElseThrow()));
        buildInputs.putAll(Map.of("fixture", "managed-callbacks", "optimization", level.toString()));
        var build = generation.nativeBuild(BridgeGeneratedJarTests.target(), buildInputs);
        var out = Path.of(args[0]).toAbsolutePath(); Files.createDirectories(out);
        var llvm = out.resolve("program.ll"); Files.writeString(llvm, new LlvmEmitter().emit(admission));
        var source = BridgeGcTransitions.compose(admission, nativeSources.source()
                + BridgeBootstrapSources.generate(generation, build, java.declarations(), nativeSources));
        // Qualification-only counters observe actual JNI acquisitions/releases.
        // Production adapters and their scalar paths contain no such accounting.
        source = source.replace("(*env)->NewGlobalRef(env,", "iw_test_global_new(env,")
                .replace("(*env)->DeleteGlobalRef(env,", "iw_test_global_delete(env,")
                .replace("(*frame->env)->DeleteGlobalRef(frame->env,", "iw_test_global_delete(frame->env,")
                .replace("(*env)->NewObjectV(env,", "iw_test_object_new(env,")
                .replace("(*env)->NewString(env,", "iw_test_string_new(env,")
                .replace("(*env)->GetStringChars(env,", "iw_test_string_chars(env,")
                .replace("iw_gc_jni_SetIntArrayRegion(env, arg", "iw_test_int_region(env, arg")
                .replace("calloc(", "iw_test_calloc(");
        source = """
                #include <jni.h>
                #include <stdlib.h>
                #include <stdarg.h>
                static jlong iw_test_globals;
                static jthrowable iw_gc_java_oom;
                extern size_t iw_gc_test_capture_failure;
                static int iw_test_failure_kind, iw_test_failure_after;
                static int iw_test_fail(int kind) {
                    if (iw_test_failure_kind != kind || --iw_test_failure_after != 0) return 0;
                    iw_test_failure_kind = 0; return 1;
                }
                static void *iw_test_calloc(size_t count, size_t size) {
                    return iw_test_fail(1) ? NULL : calloc(count, size);
                }
                static jobject iw_test_object_new(JNIEnv *env, jclass type, jmethodID method, va_list arguments) {
                    return iw_test_fail(3) ? NULL : (*env)->NewObjectV(env, type, method, arguments);
                }
                static jstring iw_test_string_new(JNIEnv *env, const jchar *units, jsize length) {
                    return iw_test_fail(4) ? NULL : (*env)->NewString(env, units, length);
                }
                static const jchar *iw_test_string_chars(JNIEnv *env, jstring value, jboolean *copied) {
                    return iw_test_fail(7) ? NULL : (*env)->GetStringChars(env, value, copied);
                }
                static jobject iw_test_global_new(JNIEnv *env, jobject value) {
                    if (iw_test_fail(2)) return NULL;
                    if (iw_test_fail(6)) {
                        jclass consumer = (*env)->FindClass(env, "Gc6Callbacks");
                        if (consumer == NULL) return NULL;
                        jmethodID hook = (*env)->GetStaticMethodID(env, consumer, "deliveryHook", "()V");
                        if (hook != NULL) (*env)->CallStaticVoidMethod(env, consumer, hook);
                        (*env)->DeleteLocalRef(env, consumer);
                        if ((*env)->ExceptionCheck(env)) return NULL;
                    }
                    jobject result = (*env)->NewGlobalRef(env, value);
                    if (result != NULL) iw_test_globals++;
                    return result;
                }
                static void iw_test_int_region(JNIEnv *env, jintArray array, jsize start, jsize length, const jint *values) {
                    if (iw_test_fail(8)) { (*env)->Throw(env, iw_gc_java_oom); return; }
                    (*env)->SetIntArrayRegion(env, array, start, length, values);
                }
                static void iw_test_global_delete(JNIEnv *env, jobject value) {
                    if (value != NULL) iw_test_globals--;
                    (*env)->DeleteGlobalRef(env, value);
                }
                JNIEXPORT jlong JNICALL Java_Gc6Callbacks_globals(JNIEnv *env, jclass type) {
                    (void)env; (void)type; return iw_test_globals;
                }
                JNIEXPORT void JNICALL Java_Gc6Callbacks_failure(JNIEnv *env, jclass type, jint kind, jint after) {
                    (void)env; (void)type;
                    iw_gc_test_capture_failure = kind == 5 ? (size_t)after : 0;
                    iw_test_failure_kind = kind; iw_test_failure_after = after;
                }
                """ + source;
        BridgeGeneratedJarTests.build(out, llvm, tools.toolchain().orElseThrow(), level, generation, build, java.declarations(), source, Map.of(), admission,
                List.of("Java_Gc6Callbacks_globals", "Java_Gc6Callbacks_failure"));
        Files.writeString(out.resolve("support.txt"), generation.supportPackage());
    }

    private static void check(boolean condition, String message) {

        if (!condition) throw new AssertionError(message);
    }
}
