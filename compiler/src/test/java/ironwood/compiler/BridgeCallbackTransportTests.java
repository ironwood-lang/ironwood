// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.backend.LlvmEmitter;
import ironwood.compiler.backend.LlvmToolchain;
import ironwood.compiler.backend.NativeBackend;
import ironwood.compiler.backend.OptimizationLevel;
import ironwood.compiler.bridge.BridgeCallableId;
import ironwood.compiler.bridge.BridgeProtectedEntryLowering;
import ironwood.compiler.bridge.BridgeRootSet;
import ironwood.compiler.ir.*;
import ironwood.compiler.semantic.BridgeCallbackContextLowering;
import ironwood.compiler.semantic.BridgeCallbackReachability;
import ironwood.compiler.source.SourceFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Private transport experiment, not listener lifetime or public producer admission. */
final class BridgeCallbackTransportTests {
    static final String NAME = "Java Bridge private long callbacks preserve nested frames and contain native unwinding";
    private static final String SYMBOL = "ironwood_bridge_callback_transport_test";

    private BridgeCallbackTransportTests() {}

    static void nativeTransport() throws Exception {
        var artifact = new CompilerPipeline(UnfreedMode.ERROR).analyzeForBridge(List.of(SourceFile.of("CallbackKernel.iron", """
                final class CallbackKernel {
                    static long callback(long value) { return value; }
                    static long single(long value) { return callback(value); }
                    static long run(long count) {
                        long sum = 0L;
                        for (long index = 0L; index < count; index++) sum += callback(index);
                        return sum;
                    }
                    static void fail() { throw null; }
                }
                """)));
        check(artifact.valid(), artifact.diagnostics().toString());
        var original = artifact.program().orElseThrow();
        var callback = function(original, "callback");
        emitterGuards(callback);
        var span = callback.sourceSpan();
        var result = new IrValueReference(1, IrType.I64, span);
        var foreign = new IrForeignCallInstruction(Optional.of(result), SYMBOL, IrType.I64,
                List.of(callback.parameters().getFirst().value()), span);
        var replacement = new IrFunction(callback.ownerClass(), callback.sourceName(), callback.linkageName(), IrType.I64,
                callback.parameters(), List.of(new IrBasicBlock("entry", List.of(foreign),
                        new IrReturnTerminator(Optional.of(result), span), span)), span, callback.sourceFileName(), callback.kind());
        var program = copy(original, original.functions().stream().map(function -> function.equals(callback) ? replacement : function).toList(), Set.of());
        var roots = BridgeRootSet.resolve(program, List.of(BridgeCallableId.of(function(program, "run")),
                BridgeCallableId.of(function(program, "single"))));
        var context = BridgeCallbackContextLowering.lower(program, roots, BridgeCallbackReachability.analyze(program));
        var contextualRoots = BridgeRootSet.resolve(context.program(), context.entries().values().stream().map(BridgeCallableId::of).toList());
        var entries = contextualRoots.roots().stream().map(root -> BridgeProtectedEntryLowering.lower(root,
                root.callable().linkage().contains(".single") ? "transport_single" : "transport_run", false)).toList();
        var functions = new ArrayList<>(context.program().functions());
        functions.addAll(entries);
        var failure = function(program, "fail");
        // C invokes the failure helper only inside a protected native entry, after
        // saving and clearing the Java exception. This is a transport sentinel,
        // not P5's eventual catch/replace/retain foreign-failure carrier.
        functions.add(new IrFunction(failure.ownerClass(), "<transport-failure>", "transport_native_failure", IrType.VOID,
                List.of(), List.of(new IrBasicBlock("entry", List.of(new IrCallInstruction(Optional.empty(), failure.linkageName(),
                IrType.VOID, List.of(), span)), new IrReturnTerminator(Optional.empty(), span), span)), span,
                failure.sourceFileName(), IrCallableKind.METHOD));
        var linkedProgram = NativeLinkTransformation.apply(copy(context.program(), functions,
                Set.of("transport_single", "transport_run", "transport_native_failure"))).program();
        String llvm = new LlvmEmitter().emit(linkedProgram);
        check(llvm.contains("declare i64 @\"" + SYMBOL + "\"(i64, i64)"), "callback ABI declaration missing");
        Path evidence = Path.of("workspace/java-bridge/evidence/p5/long-transport").toAbsolutePath();
        Files.createDirectories(evidence);
        Path directory = Files.createTempDirectory(evidence, "run-");
        Path module = directory.resolve("callbacks.ll");
        Files.writeString(module, llvm);
        Path adapter = directory.resolve("adapter.c");
        Files.writeString(adapter, ADAPTER);
        Path java = directory.resolve("CallbackConsumer.java");
        Files.writeString(java, CONSUMER);
        var discovery = LlvmToolchain.discover(null);
        check(discovery.successful(), discovery.error());
        var toolchain = discovery.toolchain().orElseThrow();
        Path jdk = Path.of(System.getProperty("java.home"));
        boolean mac = System.getProperty("os.name").startsWith("Mac");
        BridgeEntryTests.run(directory, List.of(jdk.resolve("bin/javac").toString(), "--release", "21", java.toString()), "javac");
        BridgeEntryTests.run(directory, List.of(jdk.resolve("bin/java").toString(), "-version"), "java-version");
        BridgeEntryTests.run(directory, List.of(toolchain.clang().toString(), "--version"), "clang-version");
        for (var level : List.of(OptimizationLevel.O0, OptimizationLevel.O3)) {
            Path object = directory.resolve("adapter-" + level + ".o");
            BridgeEntryTests.run(directory, List.of(toolchain.clang().toString(), "-std=c11", "-fPIC", "-fvisibility=hidden",
                    level.clangArgument(), "-I" + jdk.resolve("include"), "-I" + jdk.resolve(mac ? "include/darwin" : "include/linux"),
                    "-I" + Path.of("runtime/include").toAbsolutePath(), "-c", adapter.toString(), "-o", object.toString()), "adapter-" + level);
            Path image = directory.resolve("callbacks-" + level + (mac ? ".dylib" : ".so"));
            var linked = new NativeBackend().linkShared(toolchain, module, image, level, List.of(object));
            Files.writeString(directory.resolve("link-" + level + ".log"), linked.output());
            check(linked.success(), linked.output());
            List<String> command = List.of(jdk.resolve("bin/java").toString(), "-Xcheck:jni", "-cp", directory.toString(),
                    "CallbackConsumer", image.toString());
            String output = BridgeEntryTests.run(directory, command, "consumer-" + level);
            check(output.equals("callback-transport-ok\n"), "unexpected callback output: " + output);
            var constrained = new ArrayList<>(List.of("env", "IRONWOOD_ALLOCATION_LIMIT=0"));
            constrained.addAll(command);
            check(BridgeEntryTests.run(directory, constrained, "allocation-limit-" + level).equals("callback-transport-ok\n"),
                    "callback failure escaped under allocation exhaustion");
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(image)));
            Files.writeString(directory.resolve("sha256-" + level + ".txt"), hash + "  " + image.getFileName() + "\n");
            if (level == OptimizationLevel.O3) BridgeEntryTests.run(directory,
                    List.of(toolchain.clang().getParent().resolve("llvm-objdump").toString(), "--disassemble", image.toString()), "disassembly-O3");
            System.out.println("private callback transport " + level + ": " + image + " sha256=" + hash);
        }
    }

    private static IrFunction function(IrProgram program, String name) {
        return program.functions().stream().filter(function -> function.sourceName().equals(name)).findFirst().orElseThrow();
    }

    private static void emitterGuards(IrFunction source) {
        var span = source.sourceSpan();
        var context = new IrValueReference(0, IrType.I64, span);
        var result = new IrValueReference(1, IrType.I64, span);
        var first = new IrForeignCallInstruction(Optional.of(result), SYMBOL, IrType.I64,
                List.of(context), Optional.of(context), span);
        for (var bad : List.of(new IrForeignCallInstruction(Optional.of(result), SYMBOL, IrType.I64,
                        List.of(new IrConstant(IrType.I32, 1, span)), Optional.of(context), span),
                new IrForeignCallInstruction(Optional.empty(), SYMBOL, IrType.VOID, List.of(context), Optional.of(context), span))) {
            var function = new IrFunction(source.ownerClass(), "bad", "bad", IrType.I64,
                    List.of(new IrParameter("context", context, span)), List.of(new IrBasicBlock("entry", List.of(first, bad),
                    new IrReturnTerminator(Optional.of(result), span), span)), span);
            var program = new IrProgram("bad-foreign", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                    List.of(function), Optional.empty(), Optional.empty());
            try {
                new LlvmEmitter().emit(program);
                throw new AssertionError("unsupported or inconsistent foreign ABI emitted");
            } catch (IllegalArgumentException expected) {
                check(expected.getMessage().contains("long arguments") || expected.getMessage().contains("inconsistent callback ABI"),
                        expected.toString());
            }
        }
    }

    private static IrProgram copy(IrProgram program, List<IrFunction> functions, Set<String> roots) {
        return new IrProgram(program.moduleName(), program.classes(), program.staticFields(), program.typeInitializations(),
                program.arrayTypes(), program.stringConstants(), program.dispatchSlots(), functions, Optional.empty(), program.allocationFailure(), roots);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final String ADAPTER = """
            // SPDX-License-Identifier: MIT OR Apache-2.0
            #include <jni.h>
            #include <stdint.h>
            #include "ironwood_bridge.h"
            struct callback_frame { JNIEnv *env; jobject listener; jmethodID method; jthrowable pending; };
            extern int32_t transport_run(int64_t, int64_t, struct ironwood_bridge_result *);
            extern int32_t transport_single(int64_t, int64_t, struct ironwood_bridge_result *);
            extern void transport_native_failure(void);
            extern void ironwood_bridge_bootstrap(void);
            int64_t ironwood_bridge_callback_transport_test(int64_t address, int64_t value) {
                struct callback_frame *frame = (struct callback_frame *)(uintptr_t)address;
                JNIEnv *env = frame->env;
                jlong result = (*env)->CallLongMethod(env, frame->listener, frame->method, (jlong)value);
                if ((*env)->ExceptionCheck(env)) {
                    frame->pending = (*env)->ExceptionOccurred(env);
                    (*env)->ExceptionClear(env);
                    transport_native_failure();
                    __builtin_unreachable();
                }
                return result;
            }
            static jlong invoke(JNIEnv *env, jclass type, jobject listener, jlong value, jboolean once) {
                (void)type;
                jclass listener_type = (*env)->GetObjectClass(env, listener);
                if (listener_type == NULL) return 0;
                jmethodID method = (*env)->GetMethodID(env, listener_type, "onResult", "(J)J");
                (*env)->DeleteLocalRef(env, listener_type);
                if (method == NULL) return 0;
                struct callback_frame frame = {env, listener, method, NULL};
                struct ironwood_bridge_result result = {0};
                int32_t status = once ? transport_single(value, (int64_t)(uintptr_t)&frame, &result)
                    : transport_run(value, (int64_t)(uintptr_t)&frame, &result);
                if (frame.pending != NULL) {
                    (*env)->Throw(env, frame.pending);
                    (*env)->DeleteLocalRef(env, frame.pending);
                    return 0;
                }
                if (status != 0) {
                    jclass failure = (*env)->FindClass(env, "java/lang/AssertionError");
                    if (failure != NULL) (*env)->ThrowNew(env, failure, "unexpected native transport failure");
                    return 0;
                }
                return result.value.wide;
            }
            static jlong allocations(JNIEnv *env, jclass type) { (void)env; (void)type; return ironwood_allocation_count(); }
            JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
                (void)reserved;
                JNIEnv *env = NULL;
                if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_8) != JNI_OK) return JNI_ERR;
                jclass type = (*env)->FindClass(env, "CallbackConsumer");
                if (type == NULL) return JNI_ERR;
                JNINativeMethod methods[] = {
                    {"invoke", "(LCallbackConsumer$Listener;JZ)J", (void *)invoke},
                    {"allocations", "()J", (void *)allocations}
                };
                if ((*env)->RegisterNatives(env, type, methods, 2) != 0) return JNI_ERR;
                ironwood_bridge_bootstrap();
                return JNI_VERSION_1_8;
            }
            """;

    private static final String CONSUMER = """
            // SPDX-License-Identifier: MIT OR Apache-2.0
            public final class CallbackConsumer {
                interface Listener { long onResult(long value); }
                private static native long invoke(Listener listener, long value, boolean once);
                private static native long allocations();
                public static void main(String[] args) {
                    System.load(args[0]);
                    long before = allocations();
                    check(invoke(value -> value + 1L, 1000L, false) == 500500L);
                    check(invoke(value -> value, Long.MIN_VALUE, true) == Long.MIN_VALUE);
                    check(invoke(value -> value, Long.MAX_VALUE, true) == Long.MAX_VALUE);
                    check(invoke(value -> invoke(inner -> inner + 2L, value, true) + 1L, 1000L, false) == 502500L);
                    check(allocations() == before);
                    RuntimeException expected = new RuntimeException("original callback identity");
                    int[] calls = {0};
                    try {
                        invoke(value -> { calls[0]++; if (value == 17L) throw expected; return value; }, 1000L, false);
                        throw new AssertionError("missing callback failure");
                    } catch (RuntimeException actual) { check(actual == expected && calls[0] == 18); }
                    check(invoke(value -> {
                        try { invoke(inner -> { throw expected; }, value, true); }
                        catch (RuntimeException actual) { check(actual == expected); }
                        return value + 4L;
                    }, 21L, true) == 25L);
                    try {
                        invoke(value -> invoke(inner -> { throw expected; }, value, true), 1L, true);
                        throw new AssertionError("missing nested failure");
                    } catch (RuntimeException actual) { check(actual == expected); }
                    check(invoke(value -> value + 1L, 1000L, false) == 500500L);
                    System.out.println("callback-transport-ok");
                }
                private static void check(boolean value) { if (!value) throw new AssertionError(); }
            }
            """;
}
