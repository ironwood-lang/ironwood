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
import java.util.concurrent.TimeUnit;

final class BridgeExceptionNativeTests {
    private BridgeExceptionNativeTests() {}

    private static final String SOURCE = """
            package snapshotnative;
            public final class Errors {
                private Errors() {}
                private static final String PARSED = "te" + '\\u0000' + '\\uD800' + "xt";
                public static int fail() { throw new ironwood.time.format.DateTimeParseException("detail", PARSED, 2); }
                public static int ping() { return 42; }
            }
            """;

    static void getters() throws Exception {
        var artifact = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(List.of(SourceFile.of("Errors.iron", SOURCE)));
        check(artifact.valid(), artifact.diagnostics().toString());
        var surface = BridgeExportSurface.scalarPreview(artifact, List.of("snapshotnative")).surface().orElseThrow();
        var module = BridgeEntryModule.scalars(artifact, surface.roots());
        var projection = BridgeExceptionProjection.builtins(artifact, List.of("ironwood.time.format.DateTimeParseException"))
                .contract().orElseThrow();
        var entries = BridgeExceptionEntries.attach(artifact, module, projection);
        var program = NativeLinkPipeline.finish(NativeLinkPipeline.optimize(entries.program()));
        var generation = BridgeGeneration.create("getters.jar", artifact, surface, "test", "1".repeat(64), "2".repeat(64));
        var declarations = BridgeJavaSources.generate(artifact, surface, generation, module, projection);
        var sources = new java.util.TreeMap<>(declarations.sources());
        sources.put(generation.supportPackage().replace('.', '/') + "/Support.java", BridgeLoaderSources.generate(generation, declarations,
                new BridgeLoaderSources.Payload(generation.nativeBuild("macos-arm64", Map.of("fixture", "private-getter-harness")),
                        "11.0", "3".repeat(64))));
        sources.put("ExceptionGetters.java", CONSUMER);
        Path base = Path.of("workspace/java-bridge/evidence/p2/exception-getters").toAbsolutePath();
        Files.createDirectories(base);
        Path directory = Files.createTempDirectory(base, "run-");
        Path javaHome = Path.of(System.getProperty("java.home")), classes = directory.resolve("classes");
        var compile = new ArrayList<>(List.of(javaHome.resolve("bin/javac").toString(), "--release", "21", "-Xlint:all", "-Werror",
                "-d", classes.toString()));
        for (var entry : sources.entrySet()) {
            Path file = directory.resolve("sources").resolve(entry.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue());
            compile.add(file.toString());
        }
        BridgeEntryTests.run(directory, compile, "javac");
        Path llvm = directory.resolve("program.ll"), adapter = directory.resolve("adapter.c");
        Files.writeString(llvm, new LlvmEmitter().emit(program));
        Files.writeString(directory.resolve("Errors.iron"), SOURCE);
        String nativeSource = ADAPTER.replace("@FACTORY@", generation.supportPackage().replace('.', '/') + "/ExceptionFactory")
                .replace("@TYPE@", Integer.toString(projection.types().getFirst().typeId()))
                .replace("@TRACE@", entries.trace().linkageName());
        for (var entry : module.entries()) nativeSource = nativeSource.replace("@" + entry.root().callable().name() + "@", entry.function().linkageName());
        for (var property : projection.types().getFirst().properties()) {
            nativeSource = nativeSource.replace("@" + property.name() + "@", entries.accessors().get(property).linkageName());
        }
        check(projection.types().getFirst().properties().stream().filter(property -> property.name().equals("getParsedString"))
                .findFirst().orElseThrow().ownedString(), "getter cleanup lacks fresh ownership proof");
        Files.writeString(adapter, nativeSource);
        var found = LlvmToolchain.discover(null);
        check(found.successful(), found.error());
        var toolchain = found.toolchain().orElseThrow();
        boolean mac = System.getProperty("os.name").startsWith("Mac");
        Files.writeString(directory.resolve("environment.txt"), "java=" + System.getProperty("java.runtime.version")
                + "\nos=" + System.getProperty("os.name") + "\narch=" + System.getProperty("os.arch") + "\nllvm=" + toolchain.version()
                + "\nprivate JNI bindings; generated factory; production typed/runtime getters; no loader/jar qualification\n");
        for (var level : List.of(OptimizationLevel.O0, OptimizationLevel.O3)) {
            Path object = directory.resolve("adapter-" + level + ".o");
            BridgeEntryTests.run(directory, List.of(toolchain.clang().toString(), "-std=c11", "-Wall", "-Wextra", "-Werror",
                    "-fPIC", "-fvisibility=hidden", level.clangArgument(), "-I" + javaHome.resolve("include"),
                    "-I" + javaHome.resolve(mac ? "include/darwin" : "include/linux"), "-I" + Path.of("runtime/include").toAbsolutePath(),
                    "-c", adapter.toString(), "-o", object.toString()), "adapter-" + level);
            Path image = directory.resolve("getters-" + level + (mac ? ".dylib" : ".so"));
            var linked = new NativeBackend().linkShared(toolchain, llvm, image, level, List.of(object));
            Files.writeString(directory.resolve("link-" + level + ".log"), linked.output());
            check(linked.success(), linked.output());
            Files.writeString(directory.resolve("sha256-" + level + ".txt"), BridgeGeneration.bytesDigest(Files.readAllBytes(image)) + "\n");
            BridgeEntryTests.run(directory, List.of(toolchain.home().resolve("bin/llvm-objdump").toString(), "--disassemble",
                    "--no-show-raw-insn", image.toString()), "disassembly-" + level);
            for (String budget : List.of("normal", "0", "1", "2")) {
                var command = List.of(javaHome.resolve("bin/java").toString(), "-Xcheck:jni", "-cp", classes.toString(),
                        "ExceptionGetters", image.toString(), budget);
                String name = "consumer-" + level + "-" + budget;
                Files.writeString(directory.resolve(name + ".command.txt"), String.join("\n", command) + "\nIRONWOOD_ALLOCATION_LIMIT=" + budget + "\n");
                var builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(directory.resolve(name + ".log").toFile());
                if (budget.equals("normal")) builder.environment().remove("IRONWOOD_ALLOCATION_LIMIT");
                else builder.environment().put("IRONWOOD_ALLOCATION_LIMIT", budget);
                var process = builder.start();
                if (!process.waitFor(90, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new AssertionError("getter child timed out"); }
                Files.writeString(directory.resolve(name + ".exit.txt"), process.exitValue() + "\n");
                String output = Files.readString(directory.resolve(name + ".log"));
                check(process.exitValue() == 0 && output.equals("getters-ok:" + budget + "\n"), name + ": " + output);
            }
        }
        System.out.println("protected exception getter evidence: " + directory);
    }

    private static final String ADAPTER = """
            // SPDX-License-Identifier: MIT OR Apache-2.0
            #include <jni.h>
            #include <stdint.h>
            #include <string.h>
            #include "ironwood_bridge.h"
            extern void ironwood_bridge_bootstrap(void);
            extern int32_t @fail@(int64_t), @ping@(int64_t);
            extern int32_t @getMessage@(void *, int64_t), @getParsedString@(void *, int64_t);
            extern int32_t @getErrorIndex@(void *, int64_t), @getCause@(void *, int64_t);
            extern int32_t @getSecondaryExceptionCount@(void *, int64_t), @TRACE@(void *, int64_t);
            static jclass factory, oom, assertion;
            static jmethodID create;
            static void problem(JNIEnv *env, jclass type, const char *message) { (*env)->ThrowNew(env, type, message); }
            static jlong live(JNIEnv *env, jclass type) { (void)env; (void)type; return ironwood_live_allocation_count(); }
            static jint ping(JNIEnv *env, jclass type) {
                (void)type;
                struct ironwood_bridge_result result;
                if (@ping@((int64_t)(uintptr_t)&result) != 0) { problem(env, assertion, "ping failed"); return 0; }
                return result.value.integer;
            }
            static jstring text(JNIEnv *env, void *value) {
                if (value == NULL) return NULL;
                const struct ironwood_string *string = value;
                return (*env)->NewString(env, string->units, string->utf16_length);
            }
            __attribute__((noinline)) static void translate(JNIEnv *env, void *failure, jboolean delivery) {
                struct ironwood_bridge_result result;
                if (@getMessage@(failure, (int64_t)(uintptr_t)&result) != 0) { problem(env, assertion, "message getter failed"); return; }
                jstring message = text(env, result.value.reference);
                if ((*env)->ExceptionCheck(env)) return;
                uint64_t before = ironwood_live_allocation_count(), allocated = ironwood_allocation_count();
                int32_t status = @getParsedString@(failure, (int64_t)(uintptr_t)&result);
                if (status != 0) {
                    if (ironwood_live_allocation_count() != before) problem(env, assertion, "failed getter leaked");
                    else problem(env, status == 2 ? oom : assertion, "contained getter failure");
                    (*env)->DeleteLocalRef(env, message); return;
                }
                if (delivery) problem(env, oom, "injected Java delivery failure");
                jstring parsed = delivery ? NULL : text(env, result.value.reference);
                ironwood_deallocate(result.value.reference);
                if (ironwood_live_allocation_count() != before || ironwood_allocation_count() - allocated != 1) {
                    if (!(*env)->ExceptionCheck(env)) problem(env, assertion, "getter allocation count");
                }
                if ((*env)->ExceptionCheck(env)) { (*env)->DeleteLocalRef(env, message); (*env)->DeleteLocalRef(env, parsed); return; }
                status = @getErrorIndex@(failure, (int64_t)(uintptr_t)&result);
                jint index = result.value.integer;
                if (status != 0 || index != 2) { problem(env, assertion, "error index getter"); goto cleanup; }
                if (@getCause@(failure, (int64_t)(uintptr_t)&result) != 0 || result.value.reference != NULL) {
                    problem(env, assertion, "cause getter"); goto cleanup;
                }
                if (@getSecondaryExceptionCount@(failure, (int64_t)(uintptr_t)&result) != 0 || result.value.integer != 0) {
                    problem(env, assertion, "secondary getter"); goto cleanup;
                }
                if (@TRACE@(failure, (int64_t)(uintptr_t)&result) != 0 || result.failure.frame_count <= 0
                        || strcmp(result.failure.type_name, "ironwood.time.format.DateTimeParseException") != 0) {
                    problem(env, assertion, "trace follow-up"); goto cleanup;
                }
                jobject value = (*env)->CallStaticObjectMethod(env, factory, create, (jint)@TYPE@, message,
                        (jobject)NULL, parsed, (jstring)NULL, (jstring)NULL, index);
                if (!(*env)->ExceptionCheck(env)) {
                    if (value == NULL) problem(env, assertion, "factory returned null");
                    else (*env)->Throw(env, (jthrowable)value);
                }
                (*env)->DeleteLocalRef(env, value);
            cleanup:
                (*env)->DeleteLocalRef(env, parsed);
                (*env)->DeleteLocalRef(env, message);
            }
            static void fail(JNIEnv *env, jclass type, jboolean delivery) {
                (void)type;
                struct ironwood_bridge_result result;
                int32_t status = @fail@((int64_t)(uintptr_t)&result);
                if (status == 2 || (status == 1 && strcmp(result.failure.type_name, "ironwood.lang.OutOfMemoryError") == 0)) {
                    problem(env, oom, "contained entry allocation failure"); return;
                }
                if (status != 1 || result.exception == NULL
                        || strcmp(result.failure.type_name, "ironwood.time.format.DateTimeParseException") != 0) {
                    problem(env, assertion, "unexpected primary failure"); return;
                }
                translate(env, result.exception, delivery);
            }
            static jclass global(JNIEnv *env, const char *name) {
                jclass local = (*env)->FindClass(env, name);
                if (local == NULL) return NULL;
                jclass kept = (jclass)(*env)->NewGlobalRef(env, local);
                (*env)->DeleteLocalRef(env, local);
                return kept;
            }
            JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
                (void)reserved; JNIEnv *env;
                if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_8) != JNI_OK) return JNI_ERR;
                factory = global(env, "@FACTORY@"); if (factory == NULL) return JNI_ERR;
                oom = global(env, "java/lang/OutOfMemoryError"); if (oom == NULL) return JNI_ERR;
                assertion = global(env, "java/lang/AssertionError"); if (assertion == NULL) return JNI_ERR;
                create = (*env)->GetStaticMethodID(env, factory, "create",
                        "(ILjava/lang/String;Ljava/lang/Throwable;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)Ljava/lang/Throwable;");
                if (create == NULL) return JNI_ERR;
                jclass type = (*env)->FindClass(env, "ExceptionGetters"); if (type == NULL) return JNI_ERR;
                JNINativeMethod methods[] = {{"fail", "(Z)V", (void *)fail}, {"ping", "()I", (void *)ping}, {"live", "()J", (void *)live}};
                jint status = (*env)->RegisterNatives(env, type, methods, 3);
                (*env)->DeleteLocalRef(env, type);
                if (status != 0) return JNI_ERR;
                ironwood_bridge_bootstrap(); return JNI_VERSION_1_8;
            }
            """;

    private static final String CONSUMER = """
            // SPDX-License-Identifier: MIT OR Apache-2.0
            import java.time.format.DateTimeParseException;
            public final class ExceptionGetters {
                private static native void fail(boolean delivery);
                private static native int ping();
                private static native long live();
                public static void main(String[] args) {
                    System.load(args[0]);
                    if (args[1].equals("normal")) {
                        try { fail(false); throw new AssertionError("missing exception"); }
                        catch (DateTimeParseException expected) {
                            if (!expected.getMessage().equals("detail") || !expected.getParsedString().equals("te\\u0000\\uD800xt")
                                    || expected.getErrorIndex() != 2 || expected.getCause() != null) throw new AssertionError("snapshot data");
                        }
                        if (ping() != 42 || live() != 2) throw new AssertionError("first snapshot cleanup");
                        try { fail(true); throw new AssertionError("missing delivery failure"); }
                        catch (OutOfMemoryError expected) {}
                        // Native exceptions and their owned char storage remain live;
                        // this private translator has no throwable reclamation grant.
                        if (ping() != 42 || live() != 4) throw new AssertionError("delivery cleanup");
                    } else {
                        for (int index = 0; index < 3; index++) {
                            try { fail(false); throw new AssertionError("missing native allocation failure"); }
                            catch (OutOfMemoryError expected) {}
                            if (ping() != 42 || live() != (args[1].equals("2") ? 2 : 0)) throw new AssertionError("allocation fallback cleanup");
                        }
                    }
                    System.out.println("getters-ok:" + args[1]);
                }
            }
            """;

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
