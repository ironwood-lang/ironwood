// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.backend.*;
import ironwood.compiler.bridge.*;
import ironwood.compiler.ir.*;
import ironwood.compiler.semantic.BridgeCallbackContextLowering;
import ironwood.compiler.semantic.BridgeCallbackReachability;
import ironwood.compiler.source.SourceFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Private native carrier integration; public listener admission remains separately gated. */
final class BridgeCallbackCarrierNativeTests {
    static final String NAME = "Java Bridge native carriers preserve catch replacement retained identity and allocation containment";
    private BridgeCallbackCarrierNativeTests() {}

    static void carriers() throws Exception {
        var pipeline = new CompilerPipeline(UnfreedMode.ERROR);
        var sources = new ArrayList<>(List.of(SourceFile.of("Driver.iron", SOURCE)));
        var initial = pipeline.analyzeForBridge(sources);
        check(initial.valid(), initial.diagnostics().toString());
        var carrier = BridgeCallbackCarrierSources.discover(initial);
        sources.add(carrier.source());
        var artifact = pipeline.analyzeForBridge(sources);
        check(artifact.valid(), artifact.diagnostics().toString());
        var operations = BridgeCallbackCarrierEntries.create(artifact, carrier);
        try {
            BridgeCallbackCarrierNativeSources.generate(initial, operations);
            throw new AssertionError("stale carrier entries accepted");
        } catch (IllegalArgumentException expected) { check(expected.getMessage().contains("matching"), expected.toString()); }
        var surface = BridgeExportSurface.scalarPreview(artifact, List.of("carrierfixture")).surface().orElseThrow();
        var scalar = BridgeEntryModule.scalars(artifact, surface.roots());
        var projected = BridgeExceptionProjection.builtins(artifact,
                List.of("ironwood.lang.IllegalStateException", "ironwood.lang.OutOfMemoryError"));
        check(projected.status() == BridgeProof.Status.PROVED, projected.reason());
        var projection = projected.contract().orElseThrow();
        var exceptions = BridgeExceptionEntries.attach(artifact, scalar, projection);
        var original = artifact.program().orElseThrow();
        var callback = original.functions().stream().filter(function -> function.sourceName().equals("callback")).findFirst().orElseThrow();
        var span = callback.sourceSpan();
        var result = new IrValueReference(1, IrType.I64, span);
        var replacement = new IrFunction(callback.ownerClass(), callback.sourceName(), callback.linkageName(), IrType.I64,
                callback.parameters(), List.of(new IrBasicBlock("entry", List.of(new IrForeignCallInstruction(Optional.of(result),
                "ironwood_bridge_callback_carrier_test", IrType.I64, List.of(callback.parameters().getFirst().value()), span)),
                new IrReturnTerminator(Optional.of(result), span), span)), span, callback.sourceFileName(), callback.kind());
        // Only this private harness replaces a stub. Production proxies are bound
        // before mandatory source analysis; this grants no production admission.
        var program = copy(original, original.functions().stream().map(function -> function.equals(callback) ? replacement : function).toList(), Set.of());
        var context = BridgeCallbackContextLowering.lower(program, surface.roots(), BridgeCallbackReachability.analyze(program));
        var contextualRoots = BridgeRootSet.resolve(context.program(), context.entries().values().stream().map(BridgeCallableId::of).toList());
        var entry = BridgeProtectedEntryLowering.lower(contextualRoots.roots().getFirst(), "carrier_run", true);
        var functions = new ArrayList<>(context.program().functions());
        functions.add(entry);
        functions.addAll(operations.functions());
        functions.addAll(exceptions.accessors().values());
        functions.add(exceptions.trace());
        var exports = new LinkedHashSet<String>();
        exports.add(entry.linkageName());
        operations.functions().forEach(function -> exports.add(function.linkageName()));
        exceptions.accessors().values().forEach(function -> exports.add(function.linkageName()));
        exports.add(exceptions.trace().linkageName());
        var linkedProgram = NativeLinkTransformation.apply(copy(context.program(), functions, exports)).program();
        Path evidence = Path.of("workspace/java-bridge/evidence/p5/carriers").toAbsolutePath();
        Files.createDirectories(evidence);
        Path directory = Files.createTempDirectory(evidence, "run-");
        Path llvm = directory.resolve("carriers.ll"), adapter = directory.resolve("adapter.c");
        Files.writeString(llvm, new LlvmEmitter().emit(linkedProgram));
        Files.writeString(directory.resolve("Driver.iron"), SOURCE);
        Files.writeString(directory.resolve("Carrier.iron"), carrier.source().content());
        var generation = BridgeGeneration.create("carriers.jar", artifact, surface, "test", "1".repeat(64), "2".repeat(64));
        var declarations = BridgeJavaSources.generate(artifact, surface, generation, scalar, projection);
        var javaSources = new java.util.TreeMap<>(declarations.sources());
        javaSources.put(generation.supportPackage().replace('.', '/') + "/Support.java", BridgeLoaderSources.generate(generation, declarations,
                new BridgeLoaderSources.Payload(generation.nativeBuild("macos-arm64", Map.of("fixture", "private-carriers")), "11.0", "3".repeat(64))));
        javaSources.put("CallbackCarrierConsumer.java", CONSUMER);
        String transport = BridgeCallbackCarrierNativeSources.generate(artifact, operations);
        // Fault injection and reference accounting wrap only carrier ownership,
        // leaving production metadata and JNI operations unchanged.
        transport = transport.replace("(*env)->NewGlobalRef(env, local)", "test_global(env, local)")
                .replace("(*env)->DeleteGlobalRef(env, global)", "test_delete_global(env, global)");
        Files.writeString(adapter, ADAPTER.replace("@EXCEPTIONS@", BridgeExceptionNativeSources.generate(artifact, projection, exceptions))
                .replace("@CARRIERS@", transport).replace("@FACTORY@", generation.supportPackage().replace('.', '/') + "/ExceptionFactory"));
        Path jdk = Path.of(System.getProperty("java.home"));
        var compile = new ArrayList<>(List.of(jdk.resolve("bin/javac").toString(), "--release", "21", "-Xlint:all", "-Werror",
                "-d", directory.toString()));
        for (var source : javaSources.entrySet()) {
            Path file = directory.resolve("sources").resolve(source.getKey());
            Files.createDirectories(file.getParent()); Files.writeString(file, source.getValue()); compile.add(file.toString());
        }
        BridgeEntryTests.run(directory, compile, "javac");
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
            Path image = directory.resolve("carriers-" + level + (mac ? ".dylib" : ".so"));
            var linked = new NativeBackend().linkShared(toolchain, llvm, image, level, List.of(object));
            Files.writeString(directory.resolve("link-" + level + ".log"), linked.output());
            check(linked.success(), linked.output());
            for (String mode : List.of("normal", "native-oom", "jni-oom")) {
                var command = new ArrayList<String>();
                if (mode.equals("native-oom")) command.addAll(List.of("env", "IRONWOOD_ALLOCATION_LIMIT=0"));
                command.addAll(List.of(jdk.resolve("bin/java").toString(), "-Xcheck:jni", "-cp", directory.toString(),
                        "CallbackCarrierConsumer", image.toString(), mode));
                String output = BridgeEntryTests.run(directory, command, "consumer-" + level + "-" + mode);
                check(output.equals("callback-carriers-ok:" + mode + "\n"), output);
            }
            Files.writeString(directory.resolve("sha256-" + level + ".txt"), BridgeGeneration.bytesDigest(Files.readAllBytes(image)) + "\n");
            if (level == OptimizationLevel.O3) BridgeEntryTests.run(directory,
                    List.of(toolchain.clang().getParent().resolve("llvm-objdump").toString(), "--disassemble", image.toString()), "disassembly-O3");
        }
        System.out.println("private callback carrier evidence: " + directory);
    }

    private static IrProgram copy(IrProgram program, List<IrFunction> functions, Set<String> roots) {
        return new IrProgram(program.moduleName(), program.classes(), program.staticFields(), program.typeInitializations(),
                program.arrayTypes(), program.stringConstants(), program.dispatchSlots(), functions, Optional.empty(), program.allocationFailure(), roots);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }

    private static final String SOURCE = """
            package carrierfixture;
            public final class Driver {
                private Driver() {}
                private static RuntimeException saved;
                private static long callback(long value) { return value; }
                public static long run(long mode) {
                    if (mode == 4L) throw saved;
                    try {
                        if (mode == 6L) { try { callback(60L); } catch (RuntimeException first) {} }
                        return callback(mode);
                    } catch (RuntimeException failure) {
                        if (mode == 1L) return 91L;
                        if (mode == 2L) throw new IllegalStateException("replacement");
                        if (mode == 3L) { saved = failure; return 93L; }
                        throw failure;
                    } catch (OutOfMemoryError failure) {
                        if (mode == 7L) return 97L;
                        throw failure;
                    }
                }
            }
            """;

    private static final String ADAPTER = """
            @EXCEPTIONS@
            static struct iw_exception_metadata metadata;
            static jmethodID callback;
            static int fail_global;
            static int64_t globals;
            static jobject test_global(JNIEnv *env, jobject local) {
                if (fail_global) {
                    (*env)->ThrowNew(env, metadata.classes[IW_EX_OOM], "injected global reference failure");
                    return NULL;
                }
                jobject global = (*env)->NewGlobalRef(env, local);
                if (global != NULL) globals++;
                return global;
            }
            static void test_delete_global(JNIEnv *env, jobject global) { globals--; (*env)->DeleteGlobalRef(env, global); }
            @CARRIERS@
            struct frame { struct iw_callback_frame carrier; jobject listener; };
            extern int32_t carrier_run(int64_t, int64_t, struct ironwood_bridge_result *);
            extern void ironwood_bridge_bootstrap(void);
            int64_t ironwood_bridge_callback_carrier_test(int64_t address, int64_t value) {
                struct frame *frame = (struct frame *)(uintptr_t)address;
                JNIEnv *env = frame->carrier.env;
                jlong result = (*env)->CallLongMethod(env, frame->listener, callback, (jlong)value);
                if ((*env)->ExceptionCheck(env)) iw_callback_capture(&frame->carrier);
                return result;
            }
            static jlong invoke(JNIEnv *env, jclass type, jobject listener, jlong mode) {
                (void)type;
                struct frame frame = {{env, NULL}, listener};
                struct ironwood_bridge_result result = {0};
                int32_t status = carrier_run(mode, (int64_t)(uintptr_t)&frame, &result);
                if (status != 0 && !iw_callback_restore(env, &result)) iw_exception_translate(env, &metadata, result.exception);
                // Conservative D227 process lifetime for this private harness.
                // There is no unproved destruction of the created-carrier chain.
                return result.value.wide;
            }
            static jlong allocated(JNIEnv *env, jclass type) { (void)env; (void)type; return ironwood_allocation_count(); }
            static jlong references(JNIEnv *env, jclass type) { (void)env; (void)type; return globals; }
            static void inject(JNIEnv *env, jclass type, jboolean fail) { (void)env; (void)type; fail_global = fail; }
            JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
                (void)reserved;
                JNIEnv *env = NULL;
                if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_8) != JNI_OK) return JNI_ERR;
                jclass type = (*env)->FindClass(env, "CallbackCarrierConsumer");
                if (type == NULL) return JNI_ERR;
                JNINativeMethod methods[] = {
                    {"invoke", "(LCallbackCarrierConsumer$Listener;J)J", (void *)invoke},
                    {"allocated", "()J", (void *)allocated}, {"references", "()J", (void *)references}, {"inject", "(Z)V", (void *)inject}
                };
                if ((*env)->RegisterNatives(env, type, methods, 4) != 0) return JNI_ERR;
                jclass listener = (*env)->FindClass(env, "CallbackCarrierConsumer$Listener");
                if (listener == NULL) return JNI_ERR;
                callback = (*env)->GetMethodID(env, listener, "onResult", "(J)J");
                if (callback == NULL) return JNI_ERR;
                jclass factory = (*env)->FindClass(env, "@FACTORY@");
                if (factory == NULL || !iw_exception_metadata_init(env, factory, &metadata)) return JNI_ERR;
                ironwood_bridge_bootstrap();
                return JNI_VERSION_1_8;
            }
            """;

    private static final String CONSUMER = """
            // SPDX-License-Identifier: MIT OR Apache-2.0
            public final class CallbackCarrierConsumer {
                interface Listener { long onResult(long value); }
                private static native long invoke(Listener listener, long mode);
                private static native long allocated();
                private static native long references();
                private static native void inject(boolean fail);
                public static void main(String[] args) {
                    System.load(args[0]);
                    RuntimeException first = new RuntimeException("first"), second = new RuntimeException("second");
                    Listener throwing = value -> { throw first; };
                    long before = allocated();
                    for (int i = 0; i < 10000; i++) check(invoke(value -> value + 42L, 0L) == 42L);
                    check(allocated() == before && references() == 0);
                    if (!args[1].equals("normal")) {
                        inject(args[1].equals("jni-oom"));
                        for (int i = 0; i < 3; i++) {
                            try { invoke(throwing, 0L); throw new AssertionError("missing OOM"); }
                            catch (OutOfMemoryError expected) { check(references() == 0); }
                            check(invoke(throwing, 7L) == 97L && references() == 0);
                        }
                        inject(false);
                        check(invoke(value -> 42L, 0L) == 42L);
                    } else {
                        expect(first, () -> invoke(throwing, 0L));
                        check(references() == 1L);
                        check(invoke(throwing, 1L) == 91L);
                        try { invoke(throwing, 2L); throw new AssertionError("missing replacement"); }
                        catch (IllegalStateException replaced) { check(replaced.getMessage().equals("replacement")); }
                        check(invoke(throwing, 3L) == 93L);
                        long retained = references();
                        System.gc();
                        expect(first, () -> invoke(value -> { throw second; }, 4L));
                        check(references() == retained);
                        expect(second, () -> invoke(value -> { if (value == 60L) throw first; throw second; }, 6L));
                        check(references() == retained + 2L);
                        expect(second, () -> invoke(value -> {
                            check(invoke(throwing, 3L) == 93L);
                            throw second;
                        }, 0L));
                        expect(first, () -> invoke(throwing, 4L));
                        expect(first, () -> invoke(value -> invoke(throwing, 0L), 0L));
                        check(invoke(value -> { expect(second, () -> invoke(inner -> { throw second; }, 0L)); return 55L; }, 0L) == 55L);
                        check(invoke(value -> 42L, 0L) == 42L);
                    }
                    System.out.println("callback-carriers-ok:" + args[1]);
                }
                private static void expect(Throwable expected, Runnable action) {
                    try { action.run(); throw new AssertionError("missing callback failure"); }
                    catch (RuntimeException actual) { check(actual == expected); }
                }
                private static void check(boolean value) { if (!value) throw new AssertionError(); }
            }
            """;
}
