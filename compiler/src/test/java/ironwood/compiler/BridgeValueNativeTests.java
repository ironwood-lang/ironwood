// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.backend.*;
import ironwood.compiler.bridge.*;
import ironwood.compiler.ir.IrCallableKind;
import ironwood.compiler.source.SourceFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class BridgeValueNativeTests {
    static final String NAME = "Java Bridge generated value adapters preserve carriers and cleanup";
    private BridgeValueNativeTests() {}

    private static final String SOURCE = """
            package valueadapter;
            public final class Anchor { private Anchor() {} public static int ping() { return 42; } }
            final class Values {
                static int entered;
                static boolean bool(boolean value) { return value; }
                static byte small(byte value) { return value; }
                static short narrow(short value) { return value; }
                static char unit(char value) { return value; }
                static int add(int a, int b) { return a + b; }
                static long wide(long value) { return value; }
                static float single(float value) { return value; }
                static double real(double value) { return value; }
                static void nothing() {}
                static String alias(String a, String b, boolean choose) { entered++; return choose ? a : b; }
                static String fresh(String a, String b, boolean choose) { entered++; return new String(choose ? a : b); }
                static String literal() { return "literal"; }
                static int fail(String a, String b) { entered++; throw new IllegalArgumentException("failure"); }
                static int calls() { return entered; }
            }
            """;

    static void adapters() throws Exception {
        var artifact = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(List.of(SourceFile.of("Anchor.iron", SOURCE)));
        check(artifact.valid(), artifact.diagnostics().toString());
        var roots = BridgeRootSet.resolve(artifact.program().orElseThrow(), artifact.program().orElseThrow().functions().stream()
                .filter(function -> function.ownerClass().equals("valueadapter.Values") && function.kind() == IrCallableKind.METHOD)
                .map(BridgeCallableId::of).toList());
        var module = BridgeEntryModule.stringValues(artifact, roots);
        var closure = BridgeExceptionClosure.builtins(artifact, module);
        check(closure.status() == BridgeProof.Status.PROVED, closure.reason());
        var snapshot = closure.contract().orElseThrow();
        var generated = BridgeValueNativeSources.generate(artifact, module, snapshot.projection(), snapshot.entries());
        var surface = BridgeExportSurface.scalarPreview(artifact, List.of("valueadapter")).surface().orElseThrow();
        try {
            BridgeValueNativeSources.generate(artifact, BridgeEntryModule.scalars(artifact, surface.roots()), snapshot.projection(), snapshot.entries());
            throw new AssertionError("accepted exception closure attached to different entries");
        } catch (IllegalArgumentException expected) { check(expected.getMessage().contains("matching"), expected.toString()); }
        var changed = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(List.of(SourceFile.of("Anchor.iron", SOURCE.replace("return 42;", "return 43;"))));
        try {
            BridgeValueNativeSources.generate(changed, module, snapshot.projection(), snapshot.entries());
            throw new AssertionError("accepted another artifact's typed entries");
        } catch (IllegalArgumentException expected) { check(expected.getMessage().contains("matching"), expected.toString()); }
        var generation = BridgeGeneration.create("values.jar", artifact, surface, "test", "1".repeat(64), "2".repeat(64));
        var declarations = BridgeJavaSources.generate(artifact, surface, generation,
                BridgeEntryModule.scalars(artifact, surface.roots()), snapshot.projection());
        Path base = Path.of("workspace/java-bridge/evidence/p2/value-adapters").toAbsolutePath();
        Files.createDirectories(base);
        Path directory = Files.createTempDirectory(base, "run-");
        Path javaHome = Path.of(System.getProperty("java.home")), classes = directory.resolve("classes");
        var compile = new ArrayList<>(List.of(javaHome.resolve("bin/javac").toString(), "--release", "21", "-Xlint:all", "-Werror", "-d", classes.toString()));
        var sources = new java.util.TreeMap<>(declarations.sources());
        sources.put(generation.supportPackage().replace('.', '/') + "/Support.java", "package " + generation.supportPackage()
                + "; public final class Support { public static void " + declarations.ensureMethod() + "() {} }");
        sources.put("ValueConsumer.java", CONSUMER);
        for (var source : sources.entrySet()) {
            Path path = directory.resolve("sources").resolve(source.getKey());
            Files.createDirectories(path.getParent()); Files.writeString(path, source.getValue()); compile.add(path.toString());
        }
        BridgeEntryTests.run(directory, compile, "javac");
        Path llvm = directory.resolve("program.ll");
        Files.writeString(llvm, new LlvmEmitter().emit(NativeLinkPipeline.finish(NativeLinkPipeline.optimize(snapshot.entries().program()))));
        Files.writeString(directory.resolve("Anchor.iron"), SOURCE);
        var found = LlvmToolchain.discover(null);
        check(found.successful(), found.error());
        var toolchain = found.toolchain().orElseThrow();
        boolean mac = System.getProperty("os.name").startsWith("Mac");
        String registration = registration(generated, generation);
        Files.writeString(directory.resolve("environment.txt"), "java=" + System.getProperty("java.runtime.version") + "\nos="
                + System.getProperty("os.name") + "\narch=" + System.getProperty("os.arch") + "\nllvm=" + toolchain.version()
                + "\ngenerated value adapters; private test registration; separate fault-injected images\n");
        for (boolean faults : List.of(false, true)) {
            String variant = faults ? "faults" : "production";
            String body = generated.source();
            if (faults) body = body.replace("(*env)->GetStringChars(env,", "fixture_acquire(env,")
                    .replace("(*env)->ReleaseStringChars(env,", "fixture_release(env,")
                    .replace("(*env)->NewString(env,", "fixture_copy(env,");
            Path adapter = directory.resolve("adapter-" + variant + ".c");
            Files.writeString(adapter, (faults ? FAULTS : "") + body + registration);
            for (var level : List.of(OptimizationLevel.O0, OptimizationLevel.O3)) {
                String cell = variant + "-" + level;
                Path object = directory.resolve(cell + ".o");
                BridgeEntryTests.run(directory, List.of(toolchain.clang().toString(), "-std=c11", "-Wall", "-Wextra", "-Werror", "-fPIC",
                        "-fvisibility=hidden", level.clangArgument(), "-I" + javaHome.resolve("include"),
                        "-I" + javaHome.resolve(mac ? "include/darwin" : "include/linux"), "-I" + Path.of("runtime/include").toAbsolutePath(),
                        "-c", adapter.toString(), "-o", object.toString()), "compile-" + cell);
                Path image = directory.resolve(cell + (mac ? ".dylib" : ".so"));
                var linked = new NativeBackend().linkShared(toolchain, llvm, image, level, List.of(object));
                Files.writeString(directory.resolve("link-" + cell + ".log"), linked.output()); check(linked.success(), linked.output());
                Files.writeString(directory.resolve("sha256-" + cell + ".txt"), BridgeGeneration.bytesDigest(Files.readAllBytes(image)) + "\n");
                BridgeEntryTests.run(directory, List.of(toolchain.home().resolve("bin/llvm-objdump").toString(), "--disassemble",
                        "--no-show-raw-insn", image.toString()), "disassembly-" + cell);
                for (String budget : faults ? List.of("faults") : List.of("normal", "0", "1", "2")) {
                    var command = new ArrayList<String>();
                    if (!budget.equals("normal") && !faults) command.addAll(List.of("/usr/bin/env", "IRONWOOD_ALLOCATION_LIMIT=" + budget));
                    command.addAll(List.of(javaHome.resolve("bin/java").toString(), "-Xcheck:jni", "-cp", classes.toString(), "ValueConsumer", image.toString(), budget));
                    String output = BridgeEntryTests.run(directory, command, "consumer-" + cell + "-" + budget);
                    check(output.matches("(?:scalar-ns:[0-9]+:[0-9]+\\n)*values-ok:" + budget + "\\n"), output);
                }
            }
        }
        System.out.println("generated value adapter evidence: " + directory);
    }

    private static String registration(BridgeValueNativeSources generated, BridgeGeneration generation) {
        String addEntry = generated.adapters().stream().filter(adapter -> adapter.callable().name().equals("add")).findFirst().orElseThrow().entrySymbol();
        var text = new StringBuilder("static jint handwritten(JNIEnv *env, jclass type, jint a, jint b) {\n")
                .append("    (void)type; struct ironwood_bridge_result result; int32_t status = ").append(addEntry)
                .append("(a, b, (int64_t)(uintptr_t)&result);\n")
                .append("    if (status != 0) { iw_value_failure(env, status, result.exception); return 0; }\n")
                .append("    return result.value.integer;\n}\n").append("""
                extern void ironwood_bridge_bootstrap(void);
                static jlong live(JNIEnv *env, jclass type) { (void)env; (void)type; return ironwood_live_allocation_count(); }
                static jlong allocations(JNIEnv *env, jclass type) { (void)env; (void)type; return ironwood_allocation_count(); }
                #ifndef FIXTURE_FAULTS
                static void fault(JNIEnv *env, jclass type, jint mode) { (void)env; (void)type; (void)mode; }
                static jint buffers(JNIEnv *env, jclass type) { (void)env; (void)type; return 0; }
                #endif
                JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
                    (void)reserved; JNIEnv *env;
                    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_8) != JNI_OK) return JNI_ERR;
                """);
        text.append("    jclass factory = (*env)->FindClass(env, \"").append(generation.supportPackage().replace('.', '/')).append("/ExceptionFactory\");\n")
                .append("    if (factory == NULL) return JNI_ERR;\n")
                .append("    int initialized = iw_exception_metadata_init(env, factory, &iw_exceptions);\n")
                .append("    (*env)->DeleteLocalRef(env, factory); if (!initialized) return JNI_ERR;\n")
                .append("    jclass type = (*env)->FindClass(env, \"ValueConsumer\"); if (type == NULL) return JNI_ERR;\n")
                .append("    JNINativeMethod methods[] = {{\"live\", \"()J\", (void *)live}, {\"allocations\", \"()J\", (void *)allocations},\n")
                .append("        {\"fault\", \"(I)V\", (void *)fault}, {\"buffers\", \"()I\", (void *)buffers},\n")
                .append("        {\"handwritten\", \"(II)I\", (void *)handwritten},\n");
        for (var adapter : generated.adapters()) text.append("        {\"").append(adapter.callable().name()).append("\", \"")
                .append(adapter.descriptor()).append("\", (void *)").append(adapter.functionName()).append("},\n");
        return text.append("    }; int status = (*env)->RegisterNatives(env, type, methods, sizeof(methods)/sizeof(methods[0]));\n")
                .append("    (*env)->DeleteLocalRef(env, type); if (status != 0) return JNI_ERR;\n")
                .append("    ironwood_bridge_bootstrap(); return JNI_VERSION_1_8;\n}\n").toString();
    }

    private static final String FAULTS = """
            // Test-only JNI fault injection. The production variant is unmodified.
            #include <jni.h>
            #define FIXTURE_FAULTS 1
            static int failure, acquired, released, attempts;
            static void fault(JNIEnv *env, jclass type, jint mode) {
                (void)env; (void)type; failure = mode; acquired = released = attempts = 0;
            }
            static jint buffers(JNIEnv *env, jclass type) { (void)env; (void)type; return acquired - released; }
            static void injected(JNIEnv *env) {
                jclass oom = (*env)->FindClass(env, "java/lang/OutOfMemoryError");
                if (oom != NULL) { (*env)->ThrowNew(env, oom, "injected JNI failure"); (*env)->DeleteLocalRef(env, oom); }
            }
            static const jchar *fixture_acquire(JNIEnv *env, jstring value, jboolean *copy) {
                attempts++;
                if (failure == attempts && failure < 3) { injected(env); return NULL; }
                const jchar *units = (*env)->GetStringChars(env, value, copy);
                if (units != NULL) acquired++;
                return units;
            }
            static void fixture_release(JNIEnv *env, jstring value, const jchar *units) {
                released++; (*env)->ReleaseStringChars(env, value, units);
            }
            static jstring fixture_copy(JNIEnv *env, const jchar *units, jsize length) {
                if (failure == 3) { injected(env); return NULL; }
                return (*env)->NewString(env, units, length);
            }
            """;

    private static final String CONSUMER = """
            public final class ValueConsumer {
                private static native boolean bool(boolean value);
                private static native byte small(byte value);
                private static native short narrow(short value);
                private static native char unit(char value);
                private static native int add(int a, int b);
                private static native int handwritten(int a, int b);
                private static native long wide(long value);
                private static native float single(float value);
                private static native double real(double value);
                private static native void nothing();
                private static native String alias(String a, String b, boolean choose);
                private static native String fresh(String a, String b, boolean choose);
                private static native String literal();
                private static native int fail(String a, String b);
                private static native int calls();
                private static native long live();
                private static native long allocations();
                private static native void fault(int mode);
                private static native int buffers();
                public static void main(String[] args) {
                    System.load(args[0]); long baseline = live();
                    if (args[1].equals("faults")) {
                        for (int mode = 1; mode <= 3; mode++) for (boolean owned : new boolean[]{false, true}) {
                            fault(mode); int before = calls();
                            try { if (owned) fresh("a", "b", true); else alias("a", "b", true); throw new AssertionError("missing JNI failure"); }
                            catch (OutOfMemoryError expected) { if (!expected.getMessage().equals("injected JNI failure")) throw expected; }
                            if (buffers() != 0 || live() != baseline || calls() != before + (mode == 3 ? 1 : 0)) throw new AssertionError("JNI failure cleanup");
                            fault(0); if (!alias("a", "b", true).equals("a") || add(20, 22) != 42) throw new AssertionError("recovery");
                        }
                    } else if (!args[1].equals("normal")) {
                        for (int i = 0; i < 3; i++) {
                            try { fresh("a", "b", true); throw new AssertionError("missing native OOM"); } catch (OutOfMemoryError expected) {}
                            if (live() != baseline || add(20, 22) != 42 || !literal().equals("literal")) throw new AssertionError("native OOM cleanup");
                        }
                        if (calls() != (args[1].equals("2") ? 1 : 0)) throw new AssertionError("conversion entered target");
                    } else {
                        long before = allocations();
                        if (!bool(true) || bool(false) || small((byte)-128) != -128 || narrow((short)-32768) != -32768
                                || unit((char)65535) != 65535 || add(Integer.MAX_VALUE, 1) != Integer.MIN_VALUE
                                || wide(Long.MIN_VALUE) != Long.MIN_VALUE) throw new AssertionError("integer ABI");
                        for (float value : new float[]{-0.0f, Float.NaN, Float.POSITIVE_INFINITY, 1.25f}) {
                            if (Float.floatToRawIntBits(single(value)) != Float.floatToRawIntBits(value)) throw new AssertionError("float ABI");
                        }
                        for (double value : new double[]{-0.0, Double.NaN, Double.NEGATIVE_INFINITY, 1.25}) {
                            if (Double.doubleToRawLongBits(real(value)) != Double.doubleToRawLongBits(value)) throw new AssertionError("double ABI");
                        }
                        nothing(); if (allocations() != before) throw new AssertionError("scalar allocation");
                        for (String a : new String[]{null, "", "a\\0b", "" + (char)0xd800 + 'x' + (char)0xdc00}) {
                            for (boolean choose : new boolean[]{false, true}) {
                                String expected = choose ? a : "second";
                                if (!java.util.Objects.equals(alias(a, "second", choose), expected)) throw new AssertionError("alias UTF16");
                                if (expected != null && !fresh(a, "second", choose).equals(expected)) throw new AssertionError("fresh UTF16");
                                if (live() != baseline) throw new AssertionError("value leak");
                            }
                        }
                        try { fail("a", "b"); throw new AssertionError("missing native exception"); }
                        catch (IllegalArgumentException expected) {
                            if (!expected.getMessage().equals("failure") || expected.getStackTrace().length == 0
                                    || !expected.getStackTrace()[0].getClassName().equals("valueadapter.Values")) throw new AssertionError("snapshot");
                        }
                        if (live() != baseline + 1) throw new AssertionError("native failure buffer cleanup");
                        for (int i = 0; i < 50000; i++) if (add(i, 7) != handwritten(i, 7)) throw new AssertionError("JNI baseline");
                        before = allocations();
                        for (int i = 0; i < 10; i++) { measure(true); measure(false); }
                        for (int i = 0; i < 5; i++) {
                            long generatedTime, handwrittenTime;
                            if ((i & 1) == 0) { generatedTime = measure(true); handwrittenTime = measure(false); }
                            else { handwrittenTime = measure(false); generatedTime = measure(true); }
                            System.out.println("scalar-ns:" + generatedTime + ":" + handwrittenTime);
                        }
                        if (allocations() != before) throw new AssertionError("scalar benchmark allocation");
                    }
                    System.out.println("values-ok:" + args[1]);
                }
                private static long measure(boolean generated) {
                    long start = System.nanoTime(), sum = 0;
                    for (int i = 0; i < 200000; i++) sum += generated ? add(i, 7) : handwritten(i, 7);
                    long elapsed = System.nanoTime() - start;
                    if (sum != 20001300000L) throw new AssertionError("scalar benchmark checksum");
                    return elapsed;
                }
            }
            """;

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
