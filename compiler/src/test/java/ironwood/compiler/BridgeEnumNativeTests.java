// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.backend.*;
import ironwood.compiler.bridge.BridgeEntryModule;
import ironwood.compiler.ir.IrType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

final class BridgeEnumNativeTests {
    private BridgeEnumNativeTests() {}

    private static final String EXTRA = """
            enum Broken {
                ONE;
                Broken() { Tracker.initializations++; throw null; }
            }
            final class Tracker {
                static int initializations;
                static int calls;
                static int initialized() { return initializations; }
                static int called() { return calls; }
                static int broken(Broken value) { calls++; return value == null ? -1 : 9; }
            }
            """;

    static void conversions() throws Exception {
        String source = BridgeEnumTests.SOURCE + EXTRA;
        var artifact = BridgeEnumTests.artifact(source);
        var module = BridgeEntryModule.enums(artifact,
                BridgeEnumTests.roots(artifact, "index", "select", "initialized", "called", "broken"),
                Map.of(BridgeEnumTests.SIDE, BridgeEnumTests.TOKENS.get(BridgeEnumTests.SIDE),
                        IrType.reference("enumfixture.Broken"), Map.of("ONE", 83)));
        var discovery = LlvmToolchain.discover(null);
        BridgeEnumTests.check(discovery.successful(), discovery.error());
        var toolchain = discovery.toolchain().orElseThrow();
        Path base = Path.of("workspace/java-bridge/evidence/p0b/enums").toAbsolutePath();
        Files.createDirectories(base);
        Path directory = Files.createTempDirectory(base, "run-");
        Files.writeString(directory.resolve("BridgeEnums.iron"), source);
        Path llvm = directory.resolve("program.ll");
        Files.writeString(llvm, new LlvmEmitter().emit(module));
        Path adapter = directory.resolve("adapter.c");
        Files.writeString(adapter, adapter(module));
        Path consumer = directory.resolve("BridgeEnumConsumer.java");
        Files.writeString(consumer, CONSUMER);
        Path javaHome = Path.of(System.getProperty("java.home"));
        BridgeEntryTests.run(directory, List.of(javaHome.resolve("bin/javac").toString(), "--release", "21", consumer.toString()), "javac");
        boolean mac = System.getProperty("os.name").startsWith("Mac");
        for (var level : List.of(OptimizationLevel.O0, OptimizationLevel.O3)) {
            Path object = directory.resolve("adapter-" + level + ".o");
            BridgeEntryTests.run(directory, List.of(toolchain.clang().toString(), "-std=c11", "-fPIC", "-fvisibility=hidden",
                    level.clangArgument(), "-I" + javaHome.resolve("include"),
                    "-I" + javaHome.resolve(mac ? "include/darwin" : "include/linux"),
                    "-I" + Path.of("runtime/include").toAbsolutePath(), "-c", adapter.toString(), "-o", object.toString()), "adapter-" + level);
            Path image = directory.resolve("enums-" + level + (mac ? ".dylib" : ".so"));
            var linked = new NativeBackend().linkShared(toolchain, llvm, image, level, List.of(object));
            Files.writeString(directory.resolve("link-" + level + ".log"), linked.output());
            BridgeEnumTests.check(linked.success(), linked.output());
            Files.writeString(directory.resolve("sha256-" + level + ".txt"), HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(image))) + "  " + image.getFileName() + "\n");
            for (String scenario : List.of("receiver", "argument", "null", "failure", "invalid", "benchmark")) {
                String output = BridgeEntryTests.run(directory, List.of(javaHome.resolve("bin/java").toString(), "-Xcheck:jni",
                        "-cp", directory.toString(), "BridgeEnumConsumer", image.toString(), scenario), "consumer-" + level + "-" + scenario);
                BridgeEnumTests.check(output.matches((scenario.equals("benchmark")
                        ? "enums-benchmark:1000000:30000000:[0-9]+:[0-9]+\\n" : "") + "enums-ok:" + scenario + "\\n"), output);
            }
        }
        System.out.println("bridge enum evidence: " + directory);
    }

    private static String adapter(BridgeEntryModule module) {
        StringBuilder source = new StringBuilder("""
                #include <jni.h>
                #include <stdint.h>
                #include "ironwood_bridge.h"
                extern void ironwood_bridge_bootstrap(void);
                static jlong allocations(JNIEnv *env, jclass type) { (void)env; (void)type; return ironwood_allocation_count(); }
                static jint baselineIndex(JNIEnv *env, jclass type, jint token) { (void)env; (void)type; return token == 7 ? 1 : 0; }
                static jint baselineSelect(JNIEnv *env, jclass type, jint token) { (void)env; (void)type; return token == 7 ? 29 : 11; }
                static void failure(JNIEnv *env, const struct ironwood_bridge_result *result, int32_t status) {
                    const char *message = status == 1 ? result->failure.type_name : status == 3 ? "invalid token" : "snapshot unavailable";
                    jclass error = (*env)->FindClass(env, "java/lang/IllegalStateException");
                    if (error != NULL) { (*env)->ThrowNew(env, error, message); (*env)->DeleteLocalRef(env, error); }
                }
                """);
        for (var entry : module.entries()) {
            String name = entry.root().callable().name();
            boolean argument = !entry.root().callable().parameters().isEmpty();
            source.append("extern int32_t ").append(entry.function().linkageName()).append('(')
                    .append(argument ? "int32_t, " : "").append("int64_t);\n")
                    .append("static jint call_").append(name).append("(JNIEnv *env, jclass type")
                    .append(argument ? ", jint token" : "").append(") { (void)type;\n")
                    .append(" struct ironwood_bridge_result result; int32_t status = ").append(entry.function().linkageName()).append('(')
                    .append(argument ? "token, " : "").append("(int64_t)(uintptr_t)&result);\n")
                    .append(" if (status != 0) { failure(env, &result, status); return 0; } return result.value.integer; }\n");
        }
        source.append("JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) { (void)reserved; JNIEnv *env;\n")
                .append(" if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_8) != JNI_OK) return JNI_ERR;\n")
                .append(" jclass type = (*env)->FindClass(env, \"BridgeEnumConsumer\"); if (type == NULL) return JNI_ERR;\n JNINativeMethod methods[] = {\n")
                .append(" {\"allocations\", \"()J\", (void *)allocations}, {\"baselineIndex\", \"(I)I\", (void *)baselineIndex},\n")
                .append(" {\"baselineSelect\", \"(I)I\", (void *)baselineSelect},\n");
        for (var entry : module.entries()) source.append(" {\"").append(entry.root().callable().name()).append("\", \"")
                .append(entry.root().callable().parameters().isEmpty() ? "()I" : "(I)I")
                .append("\", (void *)call_").append(entry.root().callable().name()).append("},\n");
        return source.append(" }; if ((*env)->RegisterNatives(env, type, methods, sizeof(methods)/sizeof(methods[0])) != 0) return JNI_ERR;\n")
                .append(" (*env)->DeleteLocalRef(env, type); ironwood_bridge_bootstrap(); return JNI_VERSION_1_8; }\n").toString();
    }

    private static final String CONSUMER = """
            public final class BridgeEnumConsumer {
                enum Side {
                    SELL(7), BUY(41);
                    final int token;
                    Side(int token) { this.token = token; }
                    int index() { return BridgeEnumConsumer.index(token); }
                }
                static native int index(int token);
                static native int select(int token);
                static native int broken(int token);
                static native int initialized();
                static native int called();
                static native long allocations();
                static native int baselineIndex(int token);
                static native int baselineSelect(int token);
                static void check(boolean value) { if (!value) throw new AssertionError(); }
                public static void main(String[] args) throws Exception {
                    var initialization = new java.util.concurrent.FutureTask<Void>(() -> {
                        check(Side.SELL.name().equals("SELL") && Side.SELL.ordinal() == 0); return null;
                    });
                    Thread other = new Thread(initialization);
                    other.start(); initialization.get(); other.join();
                    System.load(args[0]);
                    switch (args[1]) {
                        case "receiver" -> check(Side.SELL.index() == 1 && Side.BUY.index() == 0);
                        case "argument" -> check(select(Side.SELL.token) == 29 && select(Side.BUY.token) == 11);
                        case "null" -> {
                            check(broken(-1) == -1 && initialized() == 0 && called() == 1);
                            check(select(-1) == -1);
                        }
                        case "failure" -> {
                            for (int attempt = 0; attempt < 2; attempt++) {
                                try { broken(83); throw new AssertionError(); }
                                catch (IllegalStateException expected) {
                                    if (!expected.getMessage().contains("NullPointerException"))
                                        throw new AssertionError("unexpected initialization failure: " + expected.getMessage());
                                }
                                check(initialized() == 1 && called() == 0);
                            }
                            check(select(7) == 29);
                        }
                        case "invalid" -> {
                            for (int token : new int[]{-1, 0, 1, 83, Integer.MAX_VALUE}) {
                                try { index(token); throw new AssertionError(); }
                                catch (IllegalStateException expected) { check(expected.getMessage().equals("invalid token")); }
                            }
                            check(Side.SELL.index() == 1);
                        }
                        case "benchmark" -> {
                            for (int warm = 0; warm < 10000; warm++) check(index(7) + select(7) == baselineIndex(7) + baselineSelect(7));
                            long before = allocations(), checksum = 0, start = System.nanoTime();
                            for (int iteration = 0; iteration < 1000000; iteration++) checksum += index(7) + select(7);
                            long elapsed = System.nanoTime() - start;
                            check(checksum == 30000000 && allocations() == before);
                            long reference = 0;
                            start = System.nanoTime();
                            for (int iteration = 0; iteration < 1000000; iteration++) reference += baselineIndex(7) + baselineSelect(7);
                            long baselineTime = System.nanoTime() - start;
                            check(reference == checksum && allocations() == before);
                            System.out.println("enums-benchmark:1000000:" + checksum + ":" + elapsed + ":" + baselineTime);
                        }
                        default -> throw new AssertionError(args[1]);
                    }
                    System.out.println("enums-ok:" + args[1]);
                }
            }
            """;
}
