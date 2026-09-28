// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.backend.*;
import ironwood.compiler.bridge.*;
import ironwood.compiler.ir.*;
import ironwood.compiler.source.SourceFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Full typed primitive round trips; no public callback admission is inferred. */
final class BridgePrimitiveCallbackNativeTests {
    static final String NAME = "Java Bridge primitive callbacks preserve values nested calls and exception identities";
    private BridgePrimitiveCallbackNativeTests() {}

    static void primitives() throws Exception {
        var pipeline = new CompilerPipeline(UnfreedMode.ERROR);
        var sources = new ArrayList<>(List.of(SourceFile.of("Primitives.iron", BridgeListenerProxyTests.PRIMITIVES + DRIVER)));
        var initial = pipeline.analyzeForBridge(sources);
        check(initial.valid(), initial.diagnostics().toString());
        var carrier = BridgeCallbackCarrierSources.discover(initial);
        sources.add(carrier.source());
        var proxies = BridgeListenerProxies.discover(pipeline.analyzeForBridge(sources), List.of("primitives"));
        var artifact = pipeline.analyzeForBridge(sources, proxies);
        check(artifact.valid(), artifact.diagnostics().toString());
        var original = artifact.program().orElseThrow();
        var roots = BridgeRootSet.resolve(original, original.functions().stream()
                .filter(function -> function.ownerClass().equals("primitives.Driver") && List.of("run", "loop").contains(function.sourceName()))
                .map(BridgeCallableId::of).toList());
        for (var root : roots.roots()) check(artifact.bridgeConstructionFacts().orElseThrow().borrowsInput(root.callable(), 0),
                "primitive driver must borrow its listener: " + root.callable());
        var admitted = BridgeSynchronousCallbackEntries.create(artifact, proxies, roots);
        check(admitted.matches(artifact, roots), "native primitive invocation lacks matching proof");
        var ownership = admitted.proxies();
        var operations = BridgeCallbackCarrierEntries.create(artifact, carrier);
        var cleanup = BridgeCallbackCarrierCleanup.prove(artifact, carrier, roots);
        var context = admitted.context();
        var entries = admitted.functions();
        String run = entries.stream().filter(function -> function.sourceSpan().equals(roots.roots().stream()
                .filter(root -> root.callable().name().equals("run")).findFirst().orElseThrow().span()))
                .findFirst().orElseThrow().linkageName();
        String loop = entries.stream().filter(function -> !function.linkageName().equals(run)).findFirst().orElseThrow().linkageName();
        var functions = new ArrayList<>(context.program().functions());
        var exported = new LinkedHashSet<String>();
        var additions = new ArrayList<>(entries);
        additions.addAll(ownership.functions()); additions.addAll(operations.functions()); additions.add(cleanup.destruction());
        functions.addAll(additions);
        additions.forEach(function -> exported.add(function.linkageName()));
        var linked = NativeLinkTransformation.apply(copy(context.program(), functions, exported)).program();
        var callbacks = BridgeCallbackNativeSources.generate(artifact, proxies);
        var metadata = new StringBuilder();
        for (var method : callbacks.methods()) {
            metadata.append("    ").append(method.methodField()).append(" = (*env)->GetMethodID(env, target, \"")
                    .append(method.name()).append("\", \"").append(method.descriptor()).append("\");\n")
                    .append("    if (").append(method.methodField()).append(" == NULL) return JNI_ERR;\n");
        }
        Path evidence = Path.of("workspace/java-bridge/evidence/p5/primitives").toAbsolutePath();
        Files.createDirectories(evidence);
        Path directory = Files.createTempDirectory(evidence, "run-");
        Path llvm = directory.resolve("primitives.ll"), adapter = directory.resolve("adapter.c");
        Files.writeString(llvm, new LlvmEmitter().emit(linked));
        Files.writeString(directory.resolve("Primitives.iron"), sources.getFirst().content());
        Files.writeString(adapter, ADAPTER.replace("@CARRIER@", BridgeCallbackCarrierNativeSources.generate(artifact, operations)
                + BridgeCallbackCarrierNativeSources.cleanup(artifact, operations, roots, cleanup))
                .replace("@CALLBACKS@", callbacks.source()).replace("@METADATA@", metadata)
                .replace("@WIDE_METHOD@", callbacks.methods().stream().filter(method -> method.name().equals("wide")).findFirst().orElseThrow().methodField())
                .replace("@CREATE@", ownership.operations().getFirst().create().linkageName())
                .replace("@DESTROY@", ownership.operations().getFirst().destroy().linkageName())
                .replace("primitive_run", run).replace("primitive_loop", loop));
        Path java = directory.resolve("PrimitiveConsumer.java");
        Files.writeString(java, CONSUMER);
        Path jdk = Path.of(System.getProperty("java.home"));
        BridgeEntryTests.run(directory, List.of(jdk.resolve("bin/javac").toString(), "--release", "21", "-Xlint:all", "-Werror",
                java.toString()), "javac");
        var discovery = LlvmToolchain.discover(null);
        check(discovery.successful(), discovery.error());
        var toolchain = discovery.toolchain().orElseThrow();
        boolean mac = System.getProperty("os.name").startsWith("Mac");
        Files.writeString(directory.resolve("environment.txt"), "java=" + System.getProperty("java.runtime.version")
                + "\nos=" + System.getProperty("os.name") + "\narch=" + System.getProperty("os.arch") + "\nllvm=" + toolchain.version() + "\n");
        for (var level : List.of(OptimizationLevel.O0, OptimizationLevel.O3)) {
            Path object = directory.resolve("adapter-" + level + ".o");
            BridgeEntryTests.run(directory, List.of(toolchain.clang().toString(), "-std=c11", "-Wall", "-Wextra", "-Werror",
                    "-fPIC", "-fvisibility=hidden", level.clangArgument(), "-I" + jdk.resolve("include"),
                    "-I" + jdk.resolve(mac ? "include/darwin" : "include/linux"), "-I" + Path.of("runtime/include").toAbsolutePath(),
                    "-c", adapter.toString(), "-o", object.toString()), "adapter-" + level);
            Path image = directory.resolve("primitives-" + level + (mac ? ".dylib" : ".so"));
            var result = new NativeBackend().linkShared(toolchain, llvm, image, level, List.of(object));
            Files.writeString(directory.resolve("link-" + level + ".log"), result.output());
            check(result.success(), result.output());
            String output = BridgeEntryTests.run(directory, List.of(jdk.resolve("bin/java").toString(), "-Xcheck:jni", "-cp",
                    directory.toString(), "PrimitiveConsumer", image.toString()), "consumer-" + level);
            check(output.equals("primitive-callbacks-ok\n"), output);
            Files.writeString(directory.resolve("sha256-" + level + ".txt"), BridgeGeneration.bytesDigest(Files.readAllBytes(image)) + "\n");
            if (level == OptimizationLevel.O3) {
                BridgeEntryTests.run(directory, List.of(toolchain.clang().getParent().resolve("llvm-objdump").toString(),
                        "--disassemble", image.toString()), "disassembly-O3");
                BridgeEntryTests.run(directory, List.of(jdk.resolve("bin/java").toString(), "-cp", directory.toString(),
                        "PrimitiveConsumer", image.toString(), "benchmark"), "benchmark-O3");
            }
        }
        System.out.println("private primitive callback evidence: " + directory);
    }

    private static IrProgram copy(IrProgram program, List<IrFunction> functions, Set<String> roots) {
        return new IrProgram(program.moduleName(), program.classes(), program.staticFields(), program.typeInitializations(),
                program.arrayTypes(), program.stringConstants(), program.dispatchSlots(), functions, Optional.empty(), program.allocationFailure(), roots);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }

    private static final String DRIVER = """
            final class Driver {
                static long run(Primitives listener, long bits) {
                    boolean a = (bits & 1L) != 0L;
                    boolean b = (bits & 2L) != 0L;
                    if (listener.bool(a, b) != (a != b)) return -1L;
                    if (listener.empty() != 7L) return -2L;
                    if (listener.bool(false, false) || !listener.bool(false, true)
                            || !listener.bool(true, false) || listener.bool(true, true)) return -3L;
                    if (listener.octet((byte)-128) != (byte)-128 || listener.octet((byte)127) != (byte)127)
                        return -4L;
                    if (listener.small((short)-32768) != (short)-32768 || listener.small((short)32767) != (short)32767)
                        return -5L;
                    if (listener.character((char)65535) != (char)65535 || listener.character((char)0) != (char)0)
                        return -6L;
                    if (listener.integer(-2147483648) != -2147483648 || listener.integer(2147483647) != 2147483647)
                        return -7L;
                    if (listener.wide(-9223372036854775808L) != -9223372036854775808L
                            || listener.wide(9223372036854775807L) != 9223372036854775807L) return -8L;
                    if (listener.single(1.25f) != 1.25f || 1.0f / listener.single(-0.0f) != -1.0f / 0.0f
                            || listener.single(1.0f / 0.0f) != 1.0f / 0.0f
                            || listener.single(-1.0f / 0.0f) != -1.0f / 0.0f) return -9L;
                    float singleNaN = listener.single(0.0f / 0.0f);
                    if (singleNaN == singleNaN) return -10L;
                    if (listener.real(1.25) != 1.25 || 1.0 / listener.real(-0.0) != -1.0 / 0.0
                            || listener.real(1.0 / 0.0) != 1.0 / 0.0
                            || listener.real(-1.0 / 0.0) != -1.0 / 0.0) return -11L;
                    double realNaN = listener.real(0.0 / 0.0);
                    if (realNaN == realNaN) return -12L;
                    listener.mixed(true, false, (byte)-123, (short)-32123, (char)65000,
                            -1234567890, -9123456789012345678L, -0.0f, -0.0);
                    return 42L;
                }
                static long loop(Primitives listener, long count) {
                    long sum = 0L;
                    for (long i = 0L; i < count; i++) sum += listener.wide(i);
                    return sum;
                }
            }
            """;

    private static final String ADAPTER = """
            // SPDX-License-Identifier: MIT OR Apache-2.0
            #include <jni.h>
            #include <stdint.h>
            #include "ironwood_bridge.h"
            @CARRIER@
            @CALLBACKS@
            extern void ironwood_bridge_bootstrap(void);
            extern int32_t @CREATE@(int64_t, struct ironwood_bridge_result *);
            extern void @DESTROY@(void *);
            extern int32_t primitive_run(void *, int64_t, int64_t, struct ironwood_bridge_result *);
            extern int32_t primitive_loop(void *, int64_t, int64_t, struct ironwood_bridge_result *);
            static jclass assertion;
            JNIEXPORT jlong JNICALL Java_PrimitiveConsumer_run(JNIEnv *env, jclass type, jobject listener, jlong count) {
                (void)type;
                struct ironwood_bridge_result proxy = {0}, result = {0};
                // The JNI local listener remains live through this synchronous
                // invocation and all nested callbacks. No native code retains it.
                if (listener != NULL && @CREATE@((int64_t)(uintptr_t)listener, &proxy) != 0) {
                    (*env)->ThrowNew(env, assertion, "proxy construction failed"); return 0;
                }
                struct iw_callback_frame frame = {env, NULL};
                int32_t status = count < 0 ? primitive_run(proxy.value.reference, count, (int64_t)(uintptr_t)&frame, &result)
                    : primitive_loop(proxy.value.reference, count, (int64_t)(uintptr_t)&frame, &result);
                if (proxy.value.reference != NULL) @DESTROY@(proxy.value.reference);
                if (status != 0 && !iw_callback_restore(env, &result)) {
                    (*env)->ThrowNew(env, assertion, result.failure.type_name == NULL ? "missing native failure" : result.failure.type_name);
                }
                iw_callback_release(&frame);
                return result.value.wide;
            }
            // Handwritten JNI comparison: identical checksum and exception check,
            // using the previous variadic dispatch shape instead of MethodA.
            JNIEXPORT jlong JNICALL Java_PrimitiveConsumer_plain(JNIEnv *env, jclass type, jobject listener, jlong count) {
                (void)type;
                jlong sum = 0;
                for (jlong i = 0; i < count; i++) {
                    jlong value = (*env)->CallLongMethod(env, listener, @WIDE_METHOD@, i);
                    if ((*env)->ExceptionCheck(env)) return 0;
                    sum += value;
                }
                return sum;
            }
            JNIEXPORT jlong JNICALL Java_PrimitiveConsumer_allocations(JNIEnv *env, jclass type) {
                (void)env; (void)type; return ironwood_live_allocation_count();
            }
            JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
                (void)reserved;
                JNIEnv *env = NULL;
                if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_8) != JNI_OK) return JNI_ERR;
                jclass target = (*env)->FindClass(env, "PrimitiveConsumer$Target");
                if (target == NULL) return JNI_ERR;
            @METADATA@
                (*env)->DeleteLocalRef(env, target);
                jclass local = (*env)->FindClass(env, "java/lang/AssertionError");
                if (local == NULL) return JNI_ERR;
                assertion = (jclass)(*env)->NewGlobalRef(env, local);
                (*env)->DeleteLocalRef(env, local);
                if (assertion == NULL) return JNI_ERR;
                ironwood_bridge_bootstrap();
                return JNI_VERSION_1_8;
            }
            """;

    private static final String CONSUMER = """
            // SPDX-License-Identifier: MIT OR Apache-2.0
            public final class PrimitiveConsumer {
                private static native long run(Target listener, long count);
                private static native long allocations();
                private static native long plain(Target listener, long count);
                private static void check(boolean condition) { if (!condition) throw new AssertionError(); }
                public static final class Target {
                    String failing = "";
                    final RuntimeException marker = new RuntimeException("original");
                    boolean nested;
                    boolean benchmark;
                    void visit(String name) { if (failing.equals(name)) throw marker; }
                    public boolean bool(boolean a, boolean b) {
                        visit("bool");
                        if (!nested) { nested = true; try { check(run(this, -1) == 42); } finally { nested = false; } }
                        return a != b;
                    }
                    public byte octet(byte value) { visit("octet"); check(value == -128 || value == 127); return value; }
                    public short small(short value) { visit("small"); check(value == -32768 || value == 32767); return value; }
                    public char character(char value) { visit("character"); check(value == 65535 || value == 0); return value; }
                    public int integer(int value) { visit("integer"); check(value == Integer.MIN_VALUE || value == Integer.MAX_VALUE); return value; }
                    public long empty() { visit("empty"); return 7L; }
                    public long wide(long value) { if (!benchmark) visit("wide"); return value; }
                    public float single(float value) {
                        visit("single");
                        check(value == 1.25f || Float.floatToRawIntBits(value) == 0x80000000 || Float.isInfinite(value) || Float.isNaN(value));
                        return value;
                    }
                    public double real(double value) {
                        visit("real");
                        check(value == 1.25 || Double.doubleToRawLongBits(value) == Long.MIN_VALUE || Double.isInfinite(value) || Double.isNaN(value));
                        return value;
                    }
                    public void mixed(boolean a, boolean b, byte c, short d, char e, int f, long g, float h, double i) {
                        visit("mixed");
                        check(a && !b && c == -123 && d == -32123 && e == 65000 && f == -1234567890 && g == -9123456789012345678L
                                && Float.floatToRawIntBits(h) == 0x80000000 && Double.doubleToRawLongBits(i) == Long.MIN_VALUE);
                    }
                }
                public static void main(String[] args) {
                    System.load(args[0]);
                    Target listener = new Target();
                    long before = allocations();
                    if (args.length > 1) {
                        listener.benchmark = true;
                        int count = 1000000;
                        long expected = (long)count * (count - 1) / 2;
                        for (int i = 0; i < 5; i++) { check(run(listener, count) == expected); check(plain(listener, count) == expected); }
                        for (int i = 0; i < 7; i++) {
                            long start = System.nanoTime();
                            long sum = run(listener, count);
                            long elapsed = System.nanoTime() - start;
                            check(sum == expected);
                            System.out.println("primitive-long-callback path=generated ns/op=" + (double)elapsed / count + " checksum=" + sum);
                            start = System.nanoTime();
                            sum = plain(listener, count);
                            elapsed = System.nanoTime() - start;
                            check(sum == expected);
                            System.out.println("primitive-long-callback path=handwritten ns/op=" + (double)elapsed / count + " checksum=" + sum);
                        }
                    } else {
                        check(run(listener, -1) == 42);
                        for (String name : new String[]{"empty", "bool", "octet", "small", "character", "integer", "wide", "single", "real", "mixed"}) {
                            listener.failing = name;
                            try { run(listener, -1); throw new AssertionError("missing callback failure: " + name); }
                            catch (RuntimeException failed) { check(failed == listener.marker); }
                            check(allocations() == before);
                        }
                        listener.failing = "";
                        for (int i = 0; i < 100; i++) check(run(listener, -1 - (i & 3)) == 42);
                    }
                    check(allocations() == before);
                    if (args.length == 1) {
                        try { run(null, -1); throw new AssertionError("missing native null check"); }
                        catch (AssertionError failure) { check("ironwood.lang.NullPointerException".equals(failure.getMessage())); }
                        // The caught native fault follows ordinary process lifetime.
                        check(allocations() == before + 1);
                        System.out.println("primitive-callbacks-ok");
                    }
                }
            }
            """;
}
