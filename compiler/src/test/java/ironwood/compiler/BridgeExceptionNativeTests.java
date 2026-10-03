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

    static final String SOURCE = """
            package snapshotnative;
            public final class Errors {
                private Errors() {}
                private static final String PARSED = "te" + '\\u0000' + '\\uD800' + "xt";
                public static int fail() { throw new ironwood.time.format.DateTimeParseException("detail", PARSED, 2); }
                public static int ping() { return 42; }
                public static int directory() {
                    ironwood.io.IOException cause = new ironwood.io.IOException("cause");
                    throw new ironwood.nio.file.DirectoryIteratorException(cause);
                }
                public static int file() throws ironwood.nio.file.FileSystemException {
                    throw new ironwood.nio.file.FileSystemException("file", "other", "reason");
                }
                public static int path() { throw new ironwood.nio.file.InvalidPathException("input", "reason", 2); }
                public static int cycle() {
                    ironwood.io.IOException first = new ironwood.io.IOException("first");
                    ironwood.io.IOException second = new ironwood.io.IOException("second", first);
                    first.initCause(second);
                    throw new ironwood.io.UncheckedIOException("cycle", first);
                }
                public static int chain(int count) {
                    ironwood.io.IOException value = new ironwood.io.IOException("end");
                    for (int i = 0; i < count; i++) value = new ironwood.io.IOException("node", value);
                    throw new ironwood.io.UncheckedIOException("chain", value);
                }
                public static int secondary(int depth) {
                    if (depth == 0) throw new IllegalArgumentException("primary");
                    try { return secondary(depth - 1); } finally { throw new IllegalStateException("secondary"); }
                }
                public static int initialized() { return Initialization.value; }
            }
            class Initialization { static int value = Errors.secondary(1); }
            """;

    static void getters() throws Exception {
        var artifact = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(List.of(SourceFile.of("Errors.iron", SOURCE)));
        check(artifact.valid(), artifact.diagnostics().toString());
        var surface = BridgeExportSurface.scalarValues(artifact, List.of("snapshotnative")).surface().orElseThrow();
        var module = BridgeEntryModule.scalars(artifact, surface.roots());
        var closure = BridgeExceptionClosure.builtins(artifact, module);
        check(closure.status() == BridgeProof.Status.PROVED, closure.reason());
        var projection = closure.contract().orElseThrow().projection();
        var parsedType = projection.types().stream().filter(type -> type.nativeName().equals("ironwood.time.format.DateTimeParseException"))
                .findFirst().orElseThrow();
        var entries = closure.contract().orElseThrow().entries();
        var rebound = BridgeExceptionProjection.builtins(artifact, List.of("ironwood.time.format.DateTimeParseException"))
                .contract().orElseThrow();
        try {
            BridgeExceptionNativeSources.generate(artifact, rebound, entries);
            throw new AssertionError("native transport accepted another projection's entries");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains("matching"), expected.toString());
        }
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
                .replace("@GENERATED@", BridgeExceptionNativeSources.generate(artifact, projection, entries))
                .replace("@TYPE@", Integer.toString(parsedType.typeId()))
                .replace("@TRACE@", entries.trace().linkageName());
        for (var entry : module.entries()) nativeSource = nativeSource.replace("@" + entry.root().callable().name() + "@", entry.function().linkageName());
        for (var property : parsedType.properties()) {
            nativeSource = nativeSource.replace("@" + property.name() + "@", entries.accessors().get(property).linkageName());
        }
        check(parsedType.properties().stream().filter(property -> property.name().equals("getParsedString"))
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
            for (String budget : List.of("normal", "0", "1", "2", "generated-0", "generated-1", "generated-2",
                    "secondary-1", "secondary-2", "secondary-0")) {
                var command = BridgeEntryTests.grantNativeAccess(List.of(javaHome.resolve("bin/java").toString(), "-Xcheck:jni", "-cp", classes.toString(),
                        "ExceptionGetters", image.toString(), budget));
                String name = "consumer-" + level + "-" + budget;
                String limit = budget.replace("generated-", "").replace("secondary-", "");
                Files.writeString(directory.resolve(name + ".command.txt"), String.join("\n", command) + "\nIRONWOOD_ALLOCATION_LIMIT=" + limit + "\n");
                var builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(directory.resolve(name + ".log").toFile());
                if (budget.equals("normal")) builder.environment().remove("IRONWOOD_ALLOCATION_LIMIT");
                else builder.environment().put("IRONWOOD_ALLOCATION_LIMIT", limit);
                var process = builder.start();
                if (!process.waitFor(90, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new AssertionError("getter child timed out"); }
                Files.writeString(directory.resolve(name + ".exit.txt"), process.exitValue() + "\n");
                String output = Files.readString(directory.resolve(name + ".log"));
                boolean nestedExhaustion = budget.equals("secondary-0");
                String expected = nestedExhaustion ? "ironwood: allocation failed while implicit OutOfMemoryError is active\n"
                        : "getters-ok:" + budget + "\n";
                Files.writeString(directory.resolve(name + ".expectation.txt"), nestedExhaustion
                        ? "documented target fatal-exhaustion control; exit 1; no JVM recovery claim\n"
                        : "recoverable transport; exit 0; subsequent calls succeed\n");
                String controlled = nestedExhaustion ? controlledFatalOutput(output) : output;
                check(process.exitValue() == (nestedExhaustion ? 1 : 0) && controlled.equals(expected), name + ": " + output);
            }
        }
        System.out.println("protected exception getter evidence: " + directory);
    }

    /**
     * The fatal exhaustion control exits through the C runtime while the JVM is still running. Under
     * Rosetta the -Xcheck:jni watcher has been observed reporting modified signal handlers during that
     * exit; native Linux and macOS hosts print nothing more. Only that trailing JVM report is removed.
     */
    private static String controlledFatalOutput(String output) {
        int report = output.indexOf("\nWarning: SIG");
        return report < 0 ? output : output.substring(0, report + 1);
    }

    private static final String ADAPTER = """
            // SPDX-License-Identifier: MIT OR Apache-2.0
            #include <jni.h>
            #include <stdint.h>
            #include <string.h>
            #include "ironwood_bridge.h"
            @GENERATED@
            static struct iw_exception_metadata generated_metadata;
            extern void ironwood_bridge_bootstrap(void);
            extern int32_t @fail@(int64_t), @ping@(int64_t);
            extern int32_t @directory@(int64_t), @file@(int64_t), @path@(int64_t), @cycle@(int64_t), @chain@(int32_t, int64_t);
            extern int32_t @secondary@(int32_t, int64_t), @initialized@(int64_t);
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
            static void generated_fail(JNIEnv *env, jclass type) {
                (void)type;
                struct ironwood_bridge_result result;
                int32_t status = @fail@((int64_t)(uintptr_t)&result);
                if (status == 2 || (status == 1 && strcmp(result.failure.type_name, "ironwood.lang.OutOfMemoryError") == 0)) {
                    problem(env, oom, "contained primary allocation failure"); return;
                }
                if (status != 1) { problem(env, assertion, "generated target did not throw"); return; }
                iw_exception_translate(env, &generated_metadata, result.exception);
            }
            static jclass global(JNIEnv *env, const char *name) {
                jclass local = (*env)->FindClass(env, name);
                if (local == NULL) return NULL;
                jclass kept = (jclass)(*env)->NewGlobalRef(env, local);
                (*env)->DeleteLocalRef(env, local);
                return kept;
            }
            static void generated_graph(JNIEnv *env, jclass type, jint kind) {
                (void)type;
                struct ironwood_bridge_result result;
                int64_t frame = (int64_t)(uintptr_t)&result;
                int32_t status;
                switch (kind) {
                    case 0: status = @directory@(frame); break;
                    case 1: status = @file@(frame); break;
                    case 2: status = @path@(frame); break;
                    case 3: status = @cycle@(frame); break;
                    case 5: status = @secondary@(1, frame); break;
                    case 6: status = @secondary@(40, frame); break;
                    case 7: status = @initialized@(frame); break;
                    default: status = @chain@(40, frame); break;
                }
                if (status != 1) { problem(env, assertion, "graph target did not throw"); return; }
                iw_exception_translate(env, &generated_metadata, result.exception);
            }
            static jstring metadata_text(JNIEnv *env, jclass type, jint kind) {
                (void)type;
                static const unsigned char sequences[][5] = {
                    {0xf0, 0x9f, 0x98, 0x80}, {0}, {0xc2}, {0xe0, 0x80, 0x80}, {0xed, 0xa0, 0x80}, {0xf4, 0x90, 0x80, 0x80}, {'a', 0, 'b'}
                };
                static const size_t lengths[] = {4, 0, 1, 3, 3, 4, 3};
                if (kind == 1) {
                    char long_name[320]; memset(long_name, 'x', sizeof(long_name));
                    return iw_exception_utf8(env, &generated_metadata, long_name, sizeof(long_name));
                }
                return iw_exception_utf8(env, &generated_metadata, (const char *)sequences[kind], lengths[kind]);
            }
            JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
                (void)reserved; JNIEnv *env;
                if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_8) != JNI_OK) return JNI_ERR;
                factory = global(env, "@FACTORY@"); if (factory == NULL) return JNI_ERR;
                if (!iw_exception_metadata_init(env, factory, &generated_metadata)) return JNI_ERR;
                oom = global(env, "java/lang/OutOfMemoryError"); if (oom == NULL) return JNI_ERR;
                assertion = global(env, "java/lang/AssertionError"); if (assertion == NULL) return JNI_ERR;
                create = (*env)->GetStaticMethodID(env, factory, "create",
                        "(ILjava/lang/String;Ljava/lang/Throwable;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)Ljava/lang/Throwable;");
                if (create == NULL) return JNI_ERR;
                jclass type = (*env)->FindClass(env, "ExceptionGetters"); if (type == NULL) return JNI_ERR;
                JNINativeMethod methods[] = {{"fail", "(Z)V", (void *)fail}, {"ping", "()I", (void *)ping}, {"live", "()J", (void *)live},
                    {"generatedFail", "()V", (void *)generated_fail}, {"metadataText", "(I)Ljava/lang/String;", (void *)metadata_text},
                    {"generatedGraph", "(I)V", (void *)generated_graph}};
                jint status = (*env)->RegisterNatives(env, type, methods, 6);
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
                private static native void generatedFail();
                private static native String metadataText(int kind);
                private static native void generatedGraph(int kind) throws java.io.IOException;
                private static native int ping();
                private static native long live();
                private static void graphs() throws java.io.IOException {
                    try { generatedGraph(0); throw new AssertionError("missing directory exception"); }
                    catch (java.nio.file.DirectoryIteratorException value) {
                        if (!value.getMessage().equals("ironwood.io.IOException: cause") || value.getCause().getClass() != java.io.IOException.class
                                || !value.getCause().getMessage().equals("cause")) throw new AssertionError("directory message/cause");
                    }
                    try { generatedGraph(1); throw new AssertionError("missing file exception"); }
                    catch (java.nio.file.FileSystemException value) {
                        if (!value.getMessage().equals("file -> other: reason") || !value.getFile().equals("file")
                                || !value.getOtherFile().equals("other") || !value.getReason().equals("reason")) throw new AssertionError("file fields/message");
                    }
                    try { generatedGraph(2); throw new AssertionError("missing path exception"); }
                    catch (java.nio.file.InvalidPathException value) {
                        if (!value.getMessage().equals("reason at index 2: input") || !value.getInput().equals("input")
                                || !value.getReason().equals("reason") || value.getIndex() != 2) throw new AssertionError("path fields/message");
                    }
                    try { generatedGraph(3); throw new AssertionError("missing cycle"); }
                    catch (java.io.UncheckedIOException value) {
                        Throwable first = value.getCause(), second = first.getCause();
                        if (!first.getMessage().equals("first") || !second.getMessage().equals("second") || second.getCause() != first) {
                            throw new AssertionError("native cycle identity");
                        }
                    }
                    try { generatedGraph(4); throw new AssertionError("missing bounded chain"); }
                    catch (java.io.UncheckedIOException value) {
                        Throwable last = value; int count = 1;
                        while (last.getCause() != null && count <= 34) { last = last.getCause(); count++; }
                        if (count != 33 || !last.getMessage().contains("copy limit")) throw new AssertionError("native graph bound: " + count);
                    }
                    try { generatedGraph(5); throw new AssertionError("missing native secondary"); }
                    catch (IllegalArgumentException value) {
                        if (!value.getMessage().equals("primary") || value.getSuppressed().length != 1
                                || value.getSuppressed()[0].getClass() != IllegalStateException.class
                                || !value.getSuppressed()[0].getMessage().equals("secondary")) throw new AssertionError("native secondary data");
                    }
                    try { generatedGraph(6); throw new AssertionError("missing bounded native secondary graph"); }
                    catch (IllegalArgumentException value) {
                        Throwable[] secondary = value.getSuppressed();
                        if (secondary.length != 32 || !(secondary[31] instanceof java.io.IOException)
                                || !secondary[31].getMessage().contains("copy limit")) throw new AssertionError("native secondary limit");
                        for (int i = 0; i < 31; i++) if (secondary[i].getClass() != IllegalStateException.class) throw new AssertionError("secondary order");
                        if (java.util.Arrays.stream(value.getStackTrace()).noneMatch(frame -> frame.getMethodName().equals("nativeFramesTruncated"))) {
                            throw new AssertionError("native trace truncation marker");
                        }
                    }
                    for (int i = 0; i < 3; i++) {
                        try { generatedGraph(7); throw new AssertionError("missing initializer failure"); }
                        catch (IllegalArgumentException value) {
                            if (!value.getMessage().equals("primary") || value.getSuppressed().length != 1
                                    || !value.getSuppressed()[0].getMessage().equals("secondary")) throw new AssertionError("retained initializer snapshot");
                        }
                    }
                    if (ping() != 42) throw new AssertionError("continuation after graph snapshots");
                }
                public static void main(String[] args) throws java.io.IOException {
                    System.load(args[0]);
                    if (args[1].equals("normal")) {
                        if (!metadataText(0).equals("\\ud83d\\ude00") || !metadataText(1).equals("x".repeat(320))
                                || !metadataText(6).equals("a\\u0000b")) throw new AssertionError("metadata UTF-8 decoding");
                        for (int kind = 2; kind <= 5; kind++) {
                            try { metadataText(kind); throw new AssertionError("invalid metadata UTF-8 accepted"); }
                            catch (LinkageError expected) {}
                        }
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
                        try { generatedFail(); throw new AssertionError("missing generated exception"); }
                        catch (DateTimeParseException expected) {
                            if (!expected.getMessage().equals("detail") || !expected.getParsedString().equals("te\\u0000\\uD800xt")
                                    || expected.getErrorIndex() != 2 || expected.getCause() != null) throw new AssertionError("generated snapshot data");
                            StackTraceElement[] trace = expected.getStackTrace();
                            int nativeSite = -1, javaSite = -1;
                            for (int i = 0; i < trace.length; i++) {
                                var frame = trace[i];
                                if (frame.getClassName().equals("snapshotnative.Errors") && frame.getMethodName().equals("fail")
                                        && frame.getFileName().equals("Errors.iron") && frame.getLineNumber() == 5) nativeSite = i;
                                if (frame.getClassName().equals("ExceptionGetters") && javaSite == -1) javaSite = i;
                            }
                            if (nativeSite < 0 || javaSite <= nativeSite) {
                                throw new AssertionError("generated native/Java trace: " + java.util.Arrays.toString(trace));
                            }
                        }
                        if (ping() != 42 || live() != 6) throw new AssertionError("generated snapshot cleanup");
                        graphs();
                    } else if (args[1].startsWith("secondary-")) {
                        try { generatedGraph(5); throw new AssertionError("missing allocation-limited secondary failure"); }
                        catch (OutOfMemoryError expected) {
                            if (!args[1].endsWith("0")) throw new AssertionError("lost primary exception", expected);
                        } catch (IllegalArgumentException expected) {
                            Class<?> secondary = args[1].endsWith("1") ? OutOfMemoryError.class : IllegalStateException.class;
                            if (!expected.getMessage().equals("primary") || expected.getSuppressed().length != 1
                                    || expected.getSuppressed()[0].getClass() != secondary) throw new AssertionError("allocation-limited secondary snapshot");
                        }
                        try { generatedFail(); throw new AssertionError("allocation limit disappeared"); } catch (OutOfMemoryError expected) {}
                        if (ping() != 42) throw new AssertionError("continuation after allocation-limited secondary");
                    } else {
                        for (int index = 0; index < 3; index++) {
                            try {
                                if (args[1].startsWith("generated-")) generatedFail(); else fail(false);
                                throw new AssertionError("missing native allocation failure");
                            }
                            catch (OutOfMemoryError expected) {}
                            if (ping() != 42 || live() != (args[1].endsWith("2") ? 2 : 0)) throw new AssertionError("allocation fallback cleanup");
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
