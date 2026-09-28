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
        var sources = new ArrayList<>(List.of(SourceFile.of("Listener.iron", SOURCE), SourceFile.of("Anchor.iron", """
                package translationfixture;
                public final class Anchor { private Anchor() {} public static int ping() { return 42; } }
                """)));
        var initial = pipeline.analyzeForBridge(sources);
        check(initial.valid(), initial.diagnostics().toString());
        var carrier = BridgeCallbackCarrierSources.discover(initial);
        sources.add(carrier.source());
        var proxies = BridgeListenerProxies.discover(pipeline.analyzeForBridge(sources), List.of("carrierfixture"));
        var artifact = pipeline.analyzeForBridge(sources, proxies);
        check(artifact.valid(), artifact.diagnostics().toString());
        var operations = BridgeCallbackCarrierEntries.create(artifact, carrier);
        try {
            BridgeCallbackCarrierNativeSources.generate(initial, operations);
            throw new AssertionError("stale carrier entries accepted");
        } catch (IllegalArgumentException expected) { check(expected.getMessage().contains("matching"), expected.toString()); }
        var surface = BridgeExportSurface.scalarPreview(artifact, List.of("translationfixture")).surface().orElseThrow();
        var scalar = BridgeEntryModule.scalars(artifact, surface.roots());
        var projected = BridgeExceptionProjection.builtins(artifact,
                List.of("ironwood.lang.IllegalStateException", "ironwood.lang.OutOfMemoryError"));
        check(projected.status() == BridgeProof.Status.PROVED, projected.reason());
        var projection = projected.contract().orElseThrow();
        var exceptions = BridgeExceptionEntries.attach(artifact, scalar, projection);
        var original = artifact.program().orElseThrow();
        var proxy = proxies.proxies().getFirst();
        var proxyEntries = BridgeListenerProxyEntries.create(artifact, proxies);
        var holderConstructor = original.functions().stream().filter(function -> function.ownerClass().equals("carrierfixture.Holder")
                && function.constructor()).findFirst().orElseThrow();
        var holderOwnership = BridgeEntryModule.rootObjects(artifact, BridgeRootSet.resolve(original,
                List.of(BridgeCallableId.of(holderConstructor))));
        var holderCreate = rename(holderOwnership.entries().getFirst().function(), "listener_holder_create");
        var holderDestroy = rename(holderOwnership.destructions().getFirst().function(), "listener_holder_destroy");
        var slotRoots = BridgeRootSet.resolve(original, original.functions().stream()
                .filter(function -> function.ownerClass().equals("carrierfixture.Holder") && function.sourceName().equals("store"))
                .map(BridgeCallableId::of).toList());
        var slotEntries = BridgeListenerSlotEntries.create(artifact, proxies, slotRoots);
        try {
            BridgeListenerNativeSources.generate(initial, proxyEntries, proxy.listener().binaryName());
            throw new AssertionError("stale listener operations accepted");
        } catch (IllegalArgumentException expected) { check(expected.getMessage().contains("matching"), expected.toString()); }
        var callback = original.functions().stream().filter(function -> function.ownerClass().equals(proxy.binaryName())
                && function.sourceName().equals("onResult")).findFirst().orElseThrow();
        var foreign = (IrForeignCallInstruction) callback.blocks().getFirst().instructions().get(1);
        var roots = BridgeRootSet.resolve(original, original.functions().stream().filter(function ->
                function.ownerClass().equals("carrierfixture.Driver") && List.of("run", "temporary").contains(function.sourceName())
                || function.ownerClass().equals("carrierfixture.Holder") && function.sourceName().equals("fire"))
                .map(BridgeCallableId::of).toList());
        var temporaryRoots = BridgeRootSet.resolve(original, roots.roots().stream().filter(root -> !root.callable().name().equals("run"))
                .map(BridgeRootSet.Root::callable).toList());
        for (var root : roots.roots()) check(artifact.bridgeConstructionFacts().orElseThrow().borrowsInput(root.callable(), 0),
                "private transport fixture must not publish its listener");
        var cleanup = BridgeCallbackCarrierCleanup.prove(artifact, carrier, temporaryRoots);
        var context = BridgeCallbackContextLowering.lower(original, roots, BridgeCallbackReachability.analyze(original));
        var contextualRoots = BridgeRootSet.resolve(context.program(), context.entries().values().stream().map(BridgeCallableId::of).toList());
        var entries = contextualRoots.roots().stream().map(root -> BridgeProtectedEntryLowering.lower(root,
                "carrier_" + root.callable().name(), true)).toList();
        var functions = new ArrayList<>(context.program().functions());
        functions.addAll(entries);
        functions.addAll(proxyEntries.functions());
        functions.add(holderCreate); functions.add(holderDestroy);
        slotEntries.entries().forEach(entry -> functions.add(entry.function()));
        functions.add(cleanup.destruction());
        functions.addAll(operations.functions());
        functions.addAll(exceptions.accessors().values());
        functions.add(exceptions.trace());
        var exports = new LinkedHashSet<String>();
        entries.forEach(entry -> exports.add(entry.linkageName()));
        proxyEntries.functions().forEach(function -> exports.add(function.linkageName()));
        exports.add(holderCreate.linkageName()); exports.add(holderDestroy.linkageName());
        slotEntries.entries().forEach(entry -> exports.add(entry.function().linkageName()));
        exports.add(cleanup.destruction().linkageName());
        operations.functions().forEach(function -> exports.add(function.linkageName()));
        exceptions.accessors().values().forEach(function -> exports.add(function.linkageName()));
        exports.add(exceptions.trace().linkageName());
        var linkedProgram = NativeLinkTransformation.apply(copy(context.program(), functions, exports)).program();
        Path evidence = Path.of("workspace/java-bridge/evidence/p5/carriers").toAbsolutePath();
        Files.createDirectories(evidence);
        Path directory = Files.createTempDirectory(evidence, "run-");
        Path llvm = directory.resolve("carriers.ll"), adapter = directory.resolve("adapter.c");
        Files.writeString(llvm, new LlvmEmitter().emit(linkedProgram));
        Files.writeString(directory.resolve("Listener.iron"), SOURCE);
        Files.writeString(directory.resolve("Carrier.iron"), carrier.source().content());
        var generation = BridgeGeneration.create("carriers.jar", artifact, surface, "test", "1".repeat(64), "2".repeat(64));
        var declarations = BridgeJavaSources.generate(artifact, surface, generation, scalar, projection, operations);
        var javaSources = new java.util.TreeMap<>(declarations.sources());
        javaSources.put(generation.supportPackage().replace('.', '/') + "/Support.java", BridgeLoaderSources.generate(generation, declarations,
                new BridgeLoaderSources.Payload(generation.nativeBuild("macos-arm64", Map.of("fixture", "private-carriers")), "11.0", "3".repeat(64))));
        javaSources.put("CallbackCarrierConsumer.java", CONSUMER);
        String transport = BridgeCallbackCarrierNativeSources.generate(artifact, operations)
                + BridgeCallbackCarrierNativeSources.cleanup(artifact, operations, temporaryRoots, cleanup);
        // Fault injection and reference accounting wrap only carrier ownership,
        // leaving production metadata and JNI operations unchanged.
        transport = transport.replace("(*env)->NewGlobalRef(env, local)", "test_global(env, local)")
                .replace("(*env)->DeleteGlobalRef(env, global)", "test_delete_global(env, global)")
                .replace("(*frame->env)->DeleteGlobalRef(frame->env, (jobject)(uintptr_t)reference.value.wide)",
                        "test_delete_global(frame->env, (jobject)(uintptr_t)reference.value.wide)");
        String listeners = BridgeListenerNativeSources.generate(artifact, proxyEntries, proxy.listener().binaryName())
                .replace("(*env)->NewGlobalRef(env, value)", "test_listener_global(env, value)")
                .replace("(*env)->DeleteGlobalRef(env, reference)", "test_listener_delete(env, reference)")
                .replace("(*env)->DeleteGlobalRef(env, listener->reference)", "test_listener_delete(env, listener->reference)")
                .replace("malloc(sizeof(*listener))", "test_listener_allocate(sizeof(*listener))")
                .replace("free(listener)", "test_listener_free(listener)");
        listeners += BridgeListenerNativeSources.owners(artifact, slotEntries)
                .replace("malloc(sizeof(*prepared))", "test_slot_allocate(sizeof(*prepared))")
                .replace("free(slot)", "test_slot_free(slot)").replace("free(prepared)", "test_slot_free(prepared)");
        Files.writeString(adapter, ADAPTER.replace("@EXCEPTIONS@", BridgeExceptionNativeSources.generate(artifact, projection, exceptions, operations))
                .replace("@CARRIERS@", transport).replace("@LISTENERS@", listeners).replace("@CALLBACK@", foreign.targetLinkageName())
                .replace("@PROXY_CREATE@", proxyEntries.operations().getFirst().create().linkageName())
                .replace("@FACTORY@", generation.supportPackage().replace('.', '/') + "/ExceptionFactory"));
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
            for (String mode : List.of("normal", "native-oom", "jni-oom", "listener-native-oom")) {
                var command = new ArrayList<String>();
                if (mode.equals("native-oom")) command.addAll(List.of("env", "IRONWOOD_ALLOCATION_LIMIT=1"));
                if (mode.equals("listener-native-oom")) command.addAll(List.of("env", "IRONWOOD_ALLOCATION_LIMIT=2"));
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
    private static IrFunction rename(IrFunction function, String symbol) {
        return new IrFunction(function.ownerClass(), function.sourceName(), symbol, function.returnType(), function.parameters(),
                function.blocks(), function.sourceSpan(), function.sourceFileName(), function.kind());
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }

    private static final String SOURCE = """
            package carrierfixture;
            public interface Listener { long onResult(long value); }
            final class Holder {
                private Listener listener;
                public Holder() {}
                void store(Listener value, long mode) {
                    if (mode == 2L) return;
                    listener = value;
                    if (mode == 1L) throw new IllegalStateException("stored listener");
                }
                long fire() {
                    Listener first = listener;
                    first.onResult(10L);
                    Listener second = listener;
                    second.onResult(20L);
                    return first.onResult(30L) + second.onResult(40L);
                }
            }
            final class Driver {
                private Driver() {}
                private static RuntimeException saved;
                static long run(Listener listener, long mode) {
                    if (mode == 4L) throw saved;
                    if (mode == 9L) {
                        try { throw new IllegalStateException("primary"); } finally { listener.onResult(mode); }
                    }
                    try {
                        if (mode == 6L) { try { listener.onResult(60L); } catch (RuntimeException first) {} }
                        return listener.onResult(mode);
                    } catch (RuntimeException failure) {
                        if (mode == 1L) return 91L;
                        if (mode == 2L) throw new IllegalStateException("replacement");
                        if (mode == 3L) { saved = failure; return 93L; }
                        if (mode == 8L) throw new IllegalStateException("wrapped", failure);
                        throw failure;
                    } catch (OutOfMemoryError failure) {
                        if (mode == 7L) return 97L;
                        throw failure;
                    }
                }
                static long temporary(Listener listener, long mode) {
                    // Reading an already retained carrier cannot add it to this
                    // invocation's newly-created chain or grant ownership of it.
                    if (mode == 4L) throw saved;
                    try {
                        if (mode == 6L) { try { listener.onResult(60L); } catch (RuntimeException first) {} }
                        return listener.onResult(mode);
                    } catch (RuntimeException failure) {
                        if (mode == 1L) return 91L;
                        if (mode == 2L) throw new IllegalStateException("replacement");
                        throw failure;
                    }
                }
            }
            """;

    private static final String ADAPTER = """
            @EXCEPTIONS@
            static struct iw_exception_metadata metadata;
            static jmethodID callback;
            static void *proxy;
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
            #include <stdlib.h>
            static int64_t listener_globals, listener_records;
            static int fail_listener;
            static jobject test_listener_global(JNIEnv *env, jobject local) {
                if (fail_listener == 3) return NULL;
                if (fail_listener == 1) {
                    (*env)->ThrowNew(env, metadata.classes[IW_EX_OOM], "injected listener reference failure");
                    return NULL;
                }
                jobject reference = (*env)->NewGlobalRef(env, local);
                if (reference != NULL) listener_globals++;
                return reference;
            }
            static void test_listener_delete(JNIEnv *env, jobject reference) {
                listener_globals--; (*env)->DeleteGlobalRef(env, reference);
            }
            static void *test_listener_allocate(size_t size) {
                void *value = fail_listener == 2 ? NULL : malloc(size);
                if (value != NULL) listener_records++;
                return value;
            }
            static void test_listener_free(void *value) { listener_records--; free(value); }
            static int64_t slot_records;
            static void *test_slot_allocate(size_t size) {
                void *value = fail_listener == 4 ? NULL : malloc(size);
                if (value != NULL) slot_records++;
                return value;
            }
            static void test_slot_free(void *value) { slot_records--; free(value); }
            @LISTENERS@
            static struct iw_listener *listeners[2];
            struct frame { struct iw_callback_frame carrier; jobject listener; };
            extern int32_t carrier_run(void *, int64_t, int64_t, struct ironwood_bridge_result *);
            extern int32_t carrier_temporary(void *, int64_t, int64_t, struct ironwood_bridge_result *);
            extern int32_t @PROXY_CREATE@(int64_t, struct ironwood_bridge_result *);
            extern void ironwood_bridge_bootstrap(void);
            int64_t @CALLBACK@(int64_t address, int64_t handle, int64_t value) {
                struct frame *frame = (struct frame *)(uintptr_t)address;
                JNIEnv *env = frame->carrier.env;
                jobject listener = handle == 0 ? frame->listener : (jobject)(uintptr_t)handle;
                jlong result = (*env)->CallLongMethod(env, listener, callback, (jlong)value);
                if ((*env)->ExceptionCheck(env)) iw_callback_capture(&frame->carrier);
                return result;
            }
            static jlong invoke(JNIEnv *env, jclass type, jobject listener, jlong mode) {
                (void)type;
                struct frame frame = {{env, NULL}, listener};
                struct ironwood_bridge_result result = {0};
                int32_t status = carrier_run(proxy, mode, (int64_t)(uintptr_t)&frame, &result);
                if (status != 0 && !iw_callback_restore(env, &result)) iw_exception_translate(env, &metadata, result.exception);
                // Conservative D227 process lifetime for this private harness.
                // There is no unproved destruction of the created-carrier chain.
                return result.value.wide;
            }
            static jlong temporary(JNIEnv *env, jclass type, jobject listener, jlong mode) {
                (void)type;
                struct frame frame = {{env, NULL}, listener};
                struct ironwood_bridge_result result = {0};
                int32_t status = carrier_temporary(proxy, mode, (int64_t)(uintptr_t)&frame, &result);
                if (status != 0 && !iw_callback_restore(env, &result)) iw_exception_translate(env, &metadata, result.exception);
                iw_callback_release(&frame.carrier);
                return result.value.wide;
            }
            static jlong live(JNIEnv *env, jclass type) { (void)env; (void)type; return ironwood_live_allocation_count(); }
            static jlong allocated(JNIEnv *env, jclass type) { (void)env; (void)type; return ironwood_allocation_count(); }
            static jlong references(JNIEnv *env, jclass type) { (void)env; (void)type; return globals; }
            static void inject(JNIEnv *env, jclass type, jboolean fail) { (void)env; (void)type; fail_global = fail; }
            static void listener_inject(JNIEnv *env, jclass type, jint fail) { (void)env; (void)type; fail_listener = fail; }
            static jlong listener_references(JNIEnv *env, jclass type) { (void)env; (void)type; return listener_globals; }
            static jlong listener_storage(JNIEnv *env, jclass type) { (void)env; (void)type; return listener_records; }
            static void set_listener(JNIEnv *env, jclass type, jint index, jobject value) {
                (void)type;
                struct iw_listener *current = listeners[index];
                if ((*env)->IsSameObject(env, current == NULL ? NULL : current->reference, value)) return;
                struct iw_listener *prepared = NULL;
                struct ironwood_bridge_result result = {0};
                int32_t status = iw_listener_prepare(env, value, metadata.classes[IW_EX_OOM], &prepared, &result);
                if (status != 0) {
                    if (status > 0) iw_exception_translate(env, &metadata, result.exception);
                    return;
                }
                // These private test slots live in this adapter only. There is
                // no unproved native field publication or public facade export.
                iw_listener_commit(env, &listeners[index], prepared);
            }
            static jlong retained(JNIEnv *env, jclass type, jint index, jlong mode) {
                (void)type;
                struct iw_listener *listener = listeners[index];
                if (listener == NULL) return -1;
                if (!iw_listener_acquire(listener)) {
                    (*env)->ThrowNew(env, metadata.classes[IW_EX_OOM], "test listener count exhausted"); return 0;
                }
                struct frame frame = {{env, NULL}, NULL};
                struct ironwood_bridge_result result = {0};
                int64_t sum = 0;
                for (int i = 0; i < 2; i++) {
                    int32_t status = carrier_temporary(listener->proxy, mode, (int64_t)(uintptr_t)&frame, &result);
                    if (status != 0) {
                        if (!iw_callback_restore(env, &result)) iw_exception_translate(env, &metadata, result.exception);
                        break;
                    }
                    sum += result.value.wide;
                }
                iw_callback_release(&frame.carrier);
                iw_listener_release(env, listener);
                return sum;
            }
            extern int32_t listener_holder_create(struct ironwood_bridge_result *);
            extern void listener_holder_destroy(void *);
            extern int32_t ironwood_bridge_listener_slots_0(void *, void *, int64_t, struct ironwood_bridge_result *);
            extern int32_t carrier_fire(void *, int64_t, struct ironwood_bridge_result *);
            static void *retained_holder;
            static struct iw_listener_owner holder_owner;
            static struct iw_listener_slot *holder_slot;
            static void holder_open(JNIEnv *env, jclass type) {
                (void)type;
                if (retained_holder != NULL) abort();
                struct ironwood_bridge_result result = {0};
                if (listener_holder_create(&result) != 0) { iw_exception_translate(env, &metadata, result.exception); return; }
                retained_holder = result.value.reference;
            }
            static void holder_set(JNIEnv *env, jclass type, jobject value) {
                (void)type;
                if (retained_holder == NULL) abort();
                if ((*env)->IsSameObject(env, holder_slot == NULL ? NULL : holder_slot->value->reference, value)) return;
                struct iw_listener_slot *prepared = NULL;
                struct ironwood_bridge_result preparation = {0};
                int32_t status = iw_listener_slot_prepare(env, value, metadata.classes[IW_EX_OOM], &prepared, &preparation);
                if (status != 0) {
                    if (status > 0) iw_exception_translate(env, &metadata, preparation.exception);
                    return;
                }
                struct { struct ironwood_bridge_result result; struct ironwood_bridge_slot slots[1]; } frame = {0};
                void *proxy = prepared == NULL ? NULL : prepared->value->proxy;
                status = ironwood_bridge_listener_slots_0(retained_holder, proxy, 0, &frame.result);
                if (frame.slots[0].holder != retained_holder || frame.slots[0].value != proxy) abort();
                iw_listener_slot_commit(env, &holder_owner, &holder_slot, prepared);
                if (status != 0) iw_exception_translate(env, &metadata, frame.result.exception);
            }
            static jlong holder_run(JNIEnv *env, jclass type) {
                (void)type;
                if (retained_holder == NULL) abort();
                if (!iw_listener_owner_enter(&holder_owner)) {
                    (*env)->ThrowNew(env, metadata.classes[IW_EX_OOM], "test holder nesting exhausted"); return 0;
                }
                struct frame frame = {{env, NULL}, NULL};
                struct ironwood_bridge_result result = {0};
                int32_t status = carrier_fire(retained_holder, (int64_t)(uintptr_t)&frame, &result);
                if (status != 0 && !iw_callback_restore(env, &result)) iw_exception_translate(env, &metadata, result.exception);
                iw_callback_release(&frame.carrier);
                iw_listener_owner_leave(env, &holder_owner);
                return result.value.wide;
            }
            static void holder_close(JNIEnv *env, jclass type) {
                if (holder_owner.active != 0) abort();
                holder_set(env, type, NULL);
                if ((*env)->ExceptionCheck(env)) return;
                listener_holder_destroy(retained_holder);
                retained_holder = NULL;
            }
            static jlong slot_storage(JNIEnv *env, jclass type) { (void)env; (void)type; return slot_records; }
            static void slot_snapshots(JNIEnv *env, jclass type, jobject value) {
                (void)type;
                struct ironwood_bridge_result created = {0};
                if (listener_holder_create(&created) != 0) { iw_exception_translate(env, &metadata, created.exception); return; }
                void *holder = created.value.reference;
                struct iw_listener *prepared = NULL;
                struct ironwood_bridge_result preparation = {0};
                int32_t status = iw_listener_prepare(env, value, metadata.classes[IW_EX_OOM], &prepared, &preparation);
                if (status != 0) {
                    listener_holder_destroy(holder);
                    if (status > 0) iw_exception_translate(env, &metadata, preparation.exception);
                    return;
                }
                struct { struct ironwood_bridge_result result; struct ironwood_bridge_slot slots[1]; } stored = {0}, cleared = {0}, unchanged = {0};
                // This typed store throws after publication. Its final snapshot
                // remains authoritative before native failure translation.
                status = ironwood_bridge_listener_slots_0(holder, prepared->proxy, 1, &stored.result);
                if (status == 0 || stored.slots[0].holder != holder || stored.slots[0].value != prepared->proxy) abort();
                struct iw_listener *slot = NULL;
                iw_listener_commit(env, &slot, prepared);
                if (ironwood_bridge_listener_slots_0(holder, NULL, 2, &unchanged.result) != 0
                        || unchanged.slots[0].holder != holder || unchanged.slots[0].value != slot->proxy) abort();
                struct frame frame = {{env, NULL}, NULL};
                struct ironwood_bridge_result called = {0};
                int32_t call_status = carrier_temporary(unchanged.slots[0].value, 0, (int64_t)(uintptr_t)&frame, &called);
                if (call_status != 0 && !iw_callback_restore(env, &called)) iw_exception_translate(env, &metadata, called.exception);
                iw_callback_release(&frame.carrier);
                if (ironwood_bridge_listener_slots_0(holder, NULL, 0, &cleared.result) != 0
                        || cleared.slots[0].holder != holder || cleared.slots[0].value != NULL) abort();
                iw_listener_commit(env, &slot, NULL);
                listener_holder_destroy(holder);
                if (!(*env)->ExceptionCheck(env)) iw_exception_translate(env, &metadata, stored.result.exception);
            }
            JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
                (void)reserved;
                struct iw_listener full_listener = { .uses = UINT64_MAX };
                struct iw_listener_owner full_owner = { .active = UINT64_MAX };
                if (iw_listener_acquire(&full_listener) || iw_listener_owner_enter(&full_owner)
                        || full_listener.uses != UINT64_MAX || full_owner.active != UINT64_MAX) abort();
                JNIEnv *env = NULL;
                if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_8) != JNI_OK) return JNI_ERR;
                jclass type = (*env)->FindClass(env, "CallbackCarrierConsumer");
                if (type == NULL) return JNI_ERR;
                JNINativeMethod methods[] = {
                    {"invoke", "(LCallbackCarrierConsumer$Listener;J)J", (void *)invoke},
                    {"temporary", "(LCallbackCarrierConsumer$Listener;J)J", (void *)temporary}, {"live", "()J", (void *)live},
                    {"allocated", "()J", (void *)allocated}, {"references", "()J", (void *)references}, {"inject", "(Z)V", (void *)inject},
                    {"setListener", "(ILCallbackCarrierConsumer$Listener;)V", (void *)set_listener},
                    {"retained", "(IJ)J", (void *)retained}, {"listenerReferences", "()J", (void *)listener_references},
                    {"listenerStorage", "()J", (void *)listener_storage}, {"listenerInject", "(I)V", (void *)listener_inject},
                    {"slotSnapshots", "(LCallbackCarrierConsumer$Listener;)V", (void *)slot_snapshots},
                    {"holderOpen", "()V", (void *)holder_open}, {"holderSet", "(LCallbackCarrierConsumer$Listener;)V", (void *)holder_set},
                    {"holderRun", "()J", (void *)holder_run}, {"holderClose", "()V", (void *)holder_close}, {"slotStorage", "()J", (void *)slot_storage}
                };
                if ((*env)->RegisterNatives(env, type, methods, 17) != 0) return JNI_ERR;
                jclass listener = (*env)->FindClass(env, "CallbackCarrierConsumer$Listener");
                if (listener == NULL) return JNI_ERR;
                callback = (*env)->GetMethodID(env, listener, "onResult", "(J)J");
                if (callback == NULL) return JNI_ERR;
                jclass factory = (*env)->FindClass(env, "@FACTORY@");
                if (factory == NULL || !iw_exception_metadata_init(env, factory, &metadata)) return JNI_ERR;
                ironwood_bridge_bootstrap();
                struct ironwood_bridge_result result = {0};
                if (@PROXY_CREATE@(0, &result) != 0) {
                    iw_exception_translate(env, &metadata, result.exception);
                    iw_exception_metadata_dispose(env, &metadata);
                    return JNI_ERR;
                }
                // This harness retains one proxy until process exit. Callback
                // listener ownership/removal remains a separate admission gate.
                proxy = result.value.reference;
                return JNI_VERSION_1_8;
            }
            """;

    private static final String CONSUMER = """
            // SPDX-License-Identifier: MIT OR Apache-2.0
            public final class CallbackCarrierConsumer {
                interface Listener { long onResult(long value); }
                private static native long invoke(Listener listener, long mode);
                private static native long temporary(Listener listener, long mode);
                private static native long live();
                private static native long allocated();
                private static native long references();
                private static native void inject(boolean fail);
                private static native void setListener(int slot, Listener listener);
                private static native long retained(int slot, long mode);
                private static native long listenerReferences();
                private static native long listenerStorage();
                private static native void listenerInject(int fail);
                private static native void slotSnapshots(Listener listener);
                private static native void holderOpen();
                private static native void holderSet(Listener listener);
                private static native long holderRun();
                private static native void holderClose();
                private static native long slotStorage();
                public static void main(String[] args) {
                    System.load(args[0]);
                    RuntimeException first = new RuntimeException("first"), second = new RuntimeException("second");
                    Listener throwing = value -> { throw first; };
                    long before = allocated();
                    for (int i = 0; i < 10000; i++) check(invoke(value -> value + 42L, 0L) == 42L);
                    check(allocated() == before && references() == 0);
                    if (args[1].equals("listener-native-oom")) {
                        setListener(0, value -> 21L);
                        try { setListener(0, throwing); throw new AssertionError("missing replacement allocation failure"); }
                        catch (OutOfMemoryError expected) { check(listenerReferences() == 1 && listenerStorage() == 1); }
                        check(retained(0, 0L) == 42L);
                        setListener(0, null);
                        check(listenerReferences() == 0 && listenerStorage() == 0 && live() == 1);
                    } else if (!args[1].equals("normal")) {
                        if (args[1].equals("native-oom")) {
                            try { setListener(0, throwing); throw new AssertionError("missing proxy allocation failure"); }
                            catch (OutOfMemoryError expected) { check(listenerReferences() == 0 && listenerStorage() == 0); }
                        }
                        inject(args[1].equals("jni-oom"));
                        for (int i = 0; i < 3; i++) {
                            try { invoke(throwing, 0L); throw new AssertionError("missing OOM"); }
                            catch (OutOfMemoryError expected) { check(references() == 0); }
                            check(invoke(throwing, 7L) == 97L && references() == 0);
                        }
                        inject(false);
                        check(invoke(value -> 42L, 0L) == 42L);
                    } else {
                        int[] storedCalls = {0};
                        long beforeSlots = live();
                        try { slotSnapshots(value -> { storedCalls[0]++; return 21L; }); throw new AssertionError("missing stored failure"); }
                        catch (IllegalStateException expected) { check(expected.getMessage().equals("stored listener")); }
                        check(storedCalls[0] == 1 && listenerReferences() == 0 && listenerStorage() == 0 && live() == beforeSlots + 1);
                        listenerLifecycle(first);
                        holderLifecycle(first);
                        long initialLive = live();
                        for (int i = 0; i < 100; i++) {
                            expect(first, () -> temporary(throwing, 0L));
                            check(temporary(throwing, 1L) == 91L);
                            expect(second, () -> temporary(value -> { if (value == 60L) throw first; throw second; }, 6L));
                            expect(first, () -> temporary(value -> temporary(throwing, 0L), 0L));
                            check(temporary(value -> { expect(second, () -> temporary(inner -> { throw second; }, 0L)); return 55L; }, 0L) == 55L);
                            check(references() == 0 && live() == initialLive);
                        }
                        try { temporary(throwing, 2L); throw new AssertionError("missing temporary replacement"); }
                        catch (IllegalStateException expected) { check(expected.getMessage().equals("replacement")); }
                        check(references() == 0 && live() == initialLive + 1L);
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
                        expect(first, () -> temporary(throwing, 4L));
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
                        Throwable cause = new Exception("Java cause"), suppressed = new Exception("Java suppression");
                        first.initCause(cause); first.addSuppressed(suppressed);
                        StackTraceElement[] trace = { new StackTraceElement("Original", "callback", "Original.java", 17) };
                        first.setStackTrace(trace);
                        try { invoke(throwing, 8L); throw new AssertionError("missing native wrapper"); }
                        catch (IllegalStateException wrapped) {
                            check(wrapped.getMessage().equals("wrapped") && wrapped.getCause() == first);
                        }
                        try { invoke(throwing, 9L); throw new AssertionError("missing native primary"); }
                        catch (IllegalStateException primary) {
                            check(primary.getMessage().equals("primary") && primary.getSuppressed().length == 1
                                    && primary.getSuppressed()[0] == first);
                        }
                        check(first.getCause() == cause && first.getSuppressed().length == 1 && first.getSuppressed()[0] == suppressed
                                && java.util.Arrays.equals(first.getStackTrace(), trace));
                    }
                    System.out.println("callback-carriers-ok:" + args[1]);
                }
                private static void holderLifecycle(RuntimeException failure) {
                    long baseline = live();
                    holderOpen();
                    Listener second = value -> {
                        if (value == 20L) holderSet(null);
                        check(listenerReferences() == 2 && slotStorage() == 2);
                        return 4L;
                    };
                    Listener first = value -> {
                        if (value == 10L) holderSet(second);
                        check(listenerReferences() == 2 && slotStorage() == 2);
                        return 3L;
                    };
                    for (int i = 0; i < 100; i++) {
                        holderSet(first);
                        check(holderRun() == 7L);
                        check(listenerReferences() == 0 && slotStorage() == 0 && live() == baseline + 1);
                    }
                    boolean[] nested = {false};
                    holderSet(value -> {
                        if (value == 10L && !nested[0]) {
                            nested[0] = true;
                            check(holderRun() == 2L);
                            nested[0] = false;
                        }
                        return 1L;
                    });
                    long allocations = allocated();
                    check(holderRun() == 2L && allocated() == allocations);
                    for (int fail = 1; fail <= 4; fail++) {
                        listenerInject(fail);
                        try { holderSet(second); throw new AssertionError("missing slot preparation failure"); }
                        catch (OutOfMemoryError expected) { check(listenerReferences() == 1 && slotStorage() == 1); }
                        check(holderRun() == 2L);
                    }
                    listenerInject(0);
                    holderSet(value -> { holderSet(null); throw failure; });
                    expect(failure, () -> holderRun());
                    check(listenerReferences() == 0 && slotStorage() == 0 && references() == 0);
                    holderClose();
                    check(live() == baseline && listenerStorage() == 0);
                }
                private static void listenerLifecycle(RuntimeException failure) {
                    long baseline = live();
                    Listener stable = value -> 21L;
                    setListener(0, stable);
                    setListener(1, stable);
                    check(listenerReferences() == 1 && listenerStorage() == 1);
                    long allocations = allocated();
                    for (int i = 0; i < 10000; i++) check(retained(0, 0L) == 42L);
                    setListener(0, stable);
                    check(allocated() == allocations);
                    setListener(0, null);
                    System.gc();
                    check(retained(1, 0L) == 42L && listenerReferences() == 1);
                    for (int fail = 1; fail <= 3; fail++) {
                        listenerInject(fail);
                        try { setListener(1, value -> 99L); throw new AssertionError("missing preparation failure"); }
                        catch (OutOfMemoryError expected) { check(listenerReferences() == 1 && listenerStorage() == 1); }
                        check(retained(1, 0L) == 42L);
                    }
                    listenerInject(0);
                    setListener(1, null);
                    Listener[] self = new Listener[1];
                    self[0] = value -> {
                        setListener(0, null);
                        long before = allocated();
                        setListener(1, self[0]);
                        check(listenerReferences() == 1 && listenerStorage() == 1 && allocated() == before);
                        setListener(1, null);
                        return 9L;
                    };
                    setListener(0, self[0]);
                    check(retained(0, 0L) == 18L && listenerReferences() == 0 && live() == baseline);
                    for (int i = 0; i < 100; i++) {
                        int[] calls = {0};
                        setListener(0, value -> {
                            calls[0]++;
                            setListener(0, stable);
                            check(retained(0, 0L) == 42L);
                            check(listenerReferences() == 2);
                            return 7L;
                        });
                        check(retained(0, 0L) == 14L && calls[0] == 2 && listenerReferences() == 1);
                        setListener(0, value -> { setListener(0, null); return 8L; });
                        check(retained(0, 0L) == 16L && listenerReferences() == 0);
                        setListener(0, value -> { setListener(0, null); throw failure; });
                        expect(failure, () -> retained(0, 0L));
                        check(listenerReferences() == 0 && listenerStorage() == 0 && references() == 0 && live() == baseline);
                    }
                }
                private static void expect(Throwable expected, Runnable action) {
                    try { action.run(); throw new AssertionError("missing callback failure"); }
                    catch (RuntimeException actual) { check(actual == expected); }
                }
                private static void check(boolean value) { if (!value) throw new AssertionError(); }
            }
            """;
}
