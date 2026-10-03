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
        int holderStart = SOURCE.indexOf("final class Holder"), driverStart = SOURCE.indexOf("final class Driver");
        String holderSource = "package carrierfixture;\n" + SOURCE.substring(holderStart, driverStart)
                .replace("final class Holder", "public final class Holder").replace("void store(", "public void store(")
                .replace("long fire()", "public long fire()");
        var sources = new ArrayList<>(List.of(SourceFile.of("Listener.iron", SOURCE.substring(0, holderStart) + SOURCE.substring(driverStart)),
                SourceFile.of("Holder.iron", holderSource), SourceFile.of("OtherListener.iron", """
                package carrierfixture;
                public interface OtherListener { long onOther(long value); }
                """), SourceFile.of("Anchor.iron", """
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
        var ownerAdmission = BridgeOwnedCallbackAdmission.prove(artifact, proxies, carrier, List.of("carrierfixture"));
        check(ownerAdmission.status() == BridgeProof.Status.PROVED, ownerAdmission.reason());
        var admittedOwner = ownerAdmission.contract().orElseThrow();
        var ownerGeneration = BridgeGeneration.createOwnedCallbacks("native-holder.jar", admittedOwner, "test", "1".repeat(64), "2".repeat(64));
        check(BridgeGeneration.fromManifest(ownerGeneration.manifest()).matchesOwnedCallbacks(admittedOwner), "owner generation did not restore");
        var operations = BridgeCallbackCarrierEntries.create(artifact, carrier);
        try {
            BridgeCallbackCarrierNativeSources.generate(initial, operations);
            throw new AssertionError("stale carrier entries accepted");
        } catch (IllegalArgumentException expected) { check(expected.getMessage().contains("matching"), expected.toString()); }
        var surface = BridgeExportSurface.scalarValues(artifact, List.of("translationfixture")).surface().orElseThrow();
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
        var holderOwnership = BridgeEntryModule.callbackOwnerStorage(artifact, BridgeRootSet.resolve(original,
                List.of(BridgeCallableId.of(holderConstructor))));
        var holderCreate = rename(holderOwnership.entries().getFirst().function(), "listener_holder_create");
        var holderDestroy = rename(holderOwnership.destructions().getFirst().function(), "listener_holder_destroy");
        var slotRoots = BridgeRootSet.resolve(original, original.functions().stream()
                .filter(function -> function.ownerClass().equals("carrierfixture.Holder") && function.sourceName().equals("store"))
                .map(BridgeCallableId::of).toList());
        var finalHolder = BridgeFinalRootRetention.prove(artifact, holderOwnership);
        check(finalHolder.status() == BridgeProof.Status.PROVED, finalHolder.reason());
        var slotEntries = ironwood.compiler.semantic.BridgeOwnedListenerSlots.prove(artifact, proxies, slotRoots,
                holderOwnership, finalHolder.contract().orElseThrow()).entries();
        try {
            BridgeListenerNativeSources.generate(initial, proxyEntries, proxy.listener().binaryName());
            throw new AssertionError("stale listener operations accepted");
        } catch (IllegalArgumentException expected) { check(expected.getMessage().contains("matching"), expected.toString()); }
        var callback = original.functions().stream().filter(function -> function.ownerClass().equals(proxy.binaryName())
                && function.sourceName().equals("onResult")).findFirst().orElseThrow();
        var foreign = (IrForeignCallInstruction) callback.blocks().getFirst().instructions().get(1);
        var roots = BridgeRootSet.resolve(original, original.functions().stream().filter(function ->
                function.ownerClass().equals("carrierfixture.Driver") && List.of("run", "temporary", "text", "other").contains(function.sourceName())
                || function.ownerClass().equals("carrierfixture.Holder") && function.sourceName().equals("fire"))
                .map(BridgeCallableId::of).toList());
        var temporaryRoots = BridgeRootSet.resolve(original, roots.roots().stream().filter(root -> !root.callable().name().equals("run"))
                .map(BridgeRootSet.Root::callable).toList());
        for (var root : roots.roots()) check(artifact.bridgeConstructionFacts().orElseThrow().borrowsInput(root.callable(), 0),
                "private transport fixture must not publish its listener");
        var cleanup = BridgeCallbackCarrierCleanup.prove(artifact, carrier, temporaryRoots);
        var context = BridgeCallbackContextLowering.lower(original, roots, BridgeCallbackReachability.analyze(original));
        var ownerRoots = BridgeRootSet.resolve(original, roots.roots().stream()
                .filter(root -> root.callable().owner().equals("carrierfixture.Holder")).map(BridgeRootSet.Root::callable).toList());
        var ownerEntries = BridgeOwnedCallbackEntries.create(artifact, proxies, ownerRoots, holderOwnership,
                finalHolder.contract().orElseThrow(), roots, context);
        var stringEntries = BridgeCallbackStringEntries.create(artifact, proxies, roots, context);
        check(stringEntries.size() == 1, "missing copied callback inputs");
        var contextualRoots = BridgeRootSet.resolve(context.program(), context.entries().values().stream().map(BridgeCallableId::of).toList());
        var entries = new ArrayList<>(contextualRoots.roots().stream()
                .filter(root -> !List.of("text", "fire").contains(root.callable().name()))
                .map(root -> BridgeProtectedEntryLowering.lower(root,
                "carrier_" + root.callable().name(), true)).toList());
        entries.add(rename(ownerEntries.entries().getFirst().function(), "carrier_fire"));
        var functions = new ArrayList<>(context.program().functions());
        functions.addAll(entries);
        functions.addAll(stringEntries);
        functions.addAll(proxyEntries.functions());
        functions.add(holderCreate); functions.add(holderDestroy);
        admittedOwner.storage().entries().forEach(entry -> functions.add(entry.function()));
        admittedOwner.storage().destructions().forEach(entry -> functions.add(entry.function()));
        slotEntries.entries().forEach(entry -> functions.add(entry.function()));
        functions.add(cleanup.destruction());
        functions.addAll(operations.functions());
        functions.addAll(exceptions.accessors().values());
        functions.add(exceptions.trace());
        var exports = new LinkedHashSet<String>();
        entries.forEach(entry -> exports.add(entry.linkageName()));
        stringEntries.forEach(entry -> exports.add(entry.linkageName()));
        proxyEntries.functions().forEach(function -> exports.add(function.linkageName()));
        exports.add(holderCreate.linkageName()); exports.add(holderDestroy.linkageName());
        exports.addAll(admittedOwner.storage().entrySymbols());
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
        Files.writeString(directory.resolve("Listener.iron"), sources.getFirst().content());
        Files.writeString(directory.resolve("Holder.iron"), holderSource);
        Files.writeString(directory.resolve("OtherListener.iron"), sources.stream()
                .filter(source -> source.content().contains("public interface OtherListener")).findFirst().orElseThrow().content());
        Files.writeString(directory.resolve("Carrier.iron"), carrier.source().content());
        var generation = BridgeGeneration.create("carriers.jar", artifact, surface, "test", "1".repeat(64), "2".repeat(64));
        var declarations = BridgeJavaSources.generate(artifact, surface, generation, scalar, projection, operations);
        var javaSources = new java.util.TreeMap<>(declarations.sources());
        javaSources.put(generation.supportPackage().replace('.', '/') + "/Support.java", BridgeLoaderSources.generate(generation, declarations,
                new BridgeLoaderSources.Payload(generation.nativeBuild("macos-arm64", Map.of("fixture", "private-carriers")), "11.0", "3".repeat(64))));
        String factoryPath = generation.supportPackage().replace('.', '/') + "/ExceptionFactory.java";
        String factory = javaSources.get(factoryPath);
        String graphStart = "if (types == null || types.length == 0";
        check(factory.contains(graphStart), "missing graph fault injection site");
        // Test-only Java snapshot allocation failure, after protected native
        // extraction. Production graph generation is otherwise unchanged.
        javaSources.put(factoryPath, factory.replace(graphStart,
                "if (Boolean.getBoolean(\"ironwood.test.graphFailure\")) throw new OutOfMemoryError(\"injected graph allocation failure\");\n"
                        + graphStart));
        var guards = ironwood.compiler.bridge.BridgeCallbackActiveUseTests.nativeFixture(admittedOwner, ownerGeneration);
        javaSources.putAll(guards.sources());
        javaSources.put("CallbackCarrierConsumer.java", CONSUMER.replace("@GUARD_PACKAGE@", guards.supportPackage())
                .replace("@HOLDER_GUARD@", ownerEntries.guard(artifact, ownerRoots.roots().getFirst().callable(),
                        Map.of(0, "state"), "return holderRunNative();")));
        String transport = BridgeCallbackCarrierNativeSources.generate(artifact, operations)
                + BridgeCallbackCarrierNativeSources.cleanup(artifact, operations, temporaryRoots, cleanup);
        // Fault injection and reference accounting wrap only carrier ownership,
        // leaving production metadata and JNI operations unchanged.
        transport = transport.replace("(*env)->NewGlobalRef(env, local)", "test_global(env, local)")
                .replace("(*env)->DeleteGlobalRef(env, global)", "test_delete_global(env, global)")
                .replace("(*frame->env)->DeleteGlobalRef(frame->env, (jobject)(uintptr_t)reference.value.wide)",
                        "test_delete_global(frame->env, (jobject)(uintptr_t)reference.value.wide)");
        var listenerTransport = BridgeListenerNativeSources.generateAll(artifact, proxyEntries);
        check(listenerTransport.kinds().equals(List.of("carrierfixture.Listener", "carrierfixture.OtherListener")), "unstable listener kind order");
        String listeners = listenerTransport.source()
                .replace("(*env)->NewGlobalRef(env, value)", "test_listener_global(env, value)")
                .replace("(*env)->DeleteGlobalRef(env, reference)", "test_listener_delete(env, reference)")
                .replace("(*env)->DeleteGlobalRef(env, listener->reference)", "test_listener_delete(env, listener->reference)")
                .replace("malloc(sizeof(*listener))", "test_listener_allocate(sizeof(*listener))")
                .replace("free(listener)", "test_listener_free(listener)");
        listeners += BridgeListenerNativeSources.ownersAll(artifact, slotEntries)
                .replace("malloc(sizeof(*prepared))", "test_slot_allocate(sizeof(*prepared))")
                .replace("free(slot)", "test_slot_free(slot)").replace("free(prepared)", "test_slot_free(prepared)");
        var callbacks = BridgeCallbackNativeSources.generate(artifact, proxies);
        String rootIndex = BridgeRootIndexSources.generateOwnedCallbacks(admittedOwner, ownerGeneration).source()
                .replace("malloc(sizeof(*record))", "test_root_record(sizeof(*record))")
                .replace("free(record)", "test_root_free(record)")
                .replace("calloc(next, sizeof(*iw_root_table))", "test_root_capacity(next, sizeof(*iw_root_table))")
                .replace("(*env)->NewGlobalRef(env, state)", "test_root_global(env, state)")
                .replace("(*env)->DeleteGlobalRef(env, record->state)", "test_root_delete(env, record->state)");
        // The old carrier controls use a process-live zero-handle proxy with a
        // call-scoped test listener. All JNI calls now use the generated body;
        // retained listeners pass their actual owned global handle unchanged.
        String directSymbol = foreign.targetLinkageName() + "_direct";
        String nativeCallbacks = callbacks.source().replace(foreign.targetLinkageName() + "(", directSymbol + "(");
        Files.writeString(adapter, ADAPTER.replace("@EXCEPTIONS@", BridgeExceptionNativeSources.generate(artifact, projection, exceptions, operations))
                .replace("@CARRIERS@", transport).replace("@LISTENERS@", listeners).replace("@CALLBACK@", foreign.targetLinkageName())
                .replace("@ROOT_INDEX@", rootIndex).replace("@OWNER_CREATE@", admittedOwner.storage().entries().getFirst().function().linkageName())
                .replace("@CALLBACK_BODIES@", nativeCallbacks).replace("@DIRECT_CALLBACK@", directSymbol)
                .replace("@METHOD@", callbacks.methods().getFirst().methodField())
                .replace("@OTHER_METHOD@", callbacks.methods().stream().filter(method -> method.listener().equals("carrierfixture.OtherListener"))
                        .findFirst().orElseThrow().methodField())
                .replace("@TEXT_DECLARATIONS@", BridgeStringInputSources.declarations(List.of(1, 2)))
                .replace("@TEXT_ACQUIRE@", BridgeStringInputSources.acquire(List.of(1, 2))
                        .replace("(*env)->GetStringChars(env, ", "test_chars(env, "))
                .replace("@TEXT_RELEASE@", BridgeStringInputSources.release(List.of(1, 2))
                        .replace("(*env)->ReleaseStringChars(env, ", "test_release_chars(env, "))
                .replace("@PROXY_CREATE@", proxyEntries.operations().getFirst().create().linkageName())
                .replace("@GUARD_PACKAGE@", guards.supportPackage().replace('.', '/'))
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
            for (String mode : List.of("normal", "native-oom", "jni-oom", "listener-native-oom", "text-native-oom", "graph-oom")) {
                var command = new ArrayList<String>();
                if (mode.equals("native-oom")) command.addAll(List.of("env", "IRONWOOD_ALLOCATION_LIMIT=1"));
                if (mode.equals("listener-native-oom")) command.addAll(List.of("env", "IRONWOOD_ALLOCATION_LIMIT=2"));
                if (mode.equals("text-native-oom")) command.addAll(List.of("env", "IRONWOOD_ALLOCATION_LIMIT=2"));
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
                static long other(OtherListener listener) { return listener.onOther(21L); }
                static long text(Listener listener, String first, String second, long mode) {
                    long beforeFirst = hash(first);
                    long beforeSecond = hash(second);
                    listener.onResult(mode);
                    if (hash(first) != beforeFirst || hash(second) != beforeSecond) throw new IllegalStateException("changed text");
                    return beforeFirst + beforeSecond;
                }
                private static long hash(String value) {
                    if (value == null) return -1L;
                    long result = 1L;
                    for (int index = 0; index < value.length(); index++) result = result * 31L + (long) value.charAt(index);
                    return result;
                }
                static long run(Listener listener, long mode) {
                    if (mode == 4L) throw saved;
                    if (mode == 10L) {
                        try { return listener.onResult(mode); } finally { throw new IllegalStateException("native secondary"); }
                    }
                    if (mode == 12L) {
                        try { throw saved; } finally { throw new IllegalStateException("retained secondary"); }
                    }
                    if (mode == 14L) throw new IllegalStateException("outer", saved);
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
                        if (mode == 11L || mode == 13L) {
                            IllegalStateException addition = new IllegalStateException("native cause");
                            failure.initCause(addition);
                            if (mode == 13L) addition.initCause(failure);
                            saved = failure;
                        }
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
            static int64_t root_records, root_references;
            static void *test_root_record(size_t size) {
                void *record = fail_listener == 5 ? NULL : malloc(size);
                if (record != NULL) root_records++;
                return record;
            }
            static void test_root_free(void *record) { root_records--; free(record); }
            static void *test_root_capacity(size_t count, size_t size) {
                return fail_listener == 6 ? NULL : calloc(count, size);
            }
            static jobject test_root_global(JNIEnv *env, jobject state) {
                if (fail_listener == 7) return NULL;
                jobject reference = (*env)->NewGlobalRef(env, state);
                if (reference != NULL) root_references++;
                return reference;
            }
            static void test_root_delete(JNIEnv *env, jobject state) {
                root_references--; (*env)->DeleteGlobalRef(env, state);
            }
            @ROOT_INDEX@
            @CALLBACK_BODIES@
            static struct iw_listener *listeners[2];
            struct frame { struct iw_callback_frame carrier; jobject listener; };
            extern int32_t carrier_run(void *, int64_t, int64_t, struct ironwood_bridge_result *);
            extern int32_t carrier_temporary(void *, int64_t, int64_t, struct ironwood_bridge_result *);
            extern int32_t @PROXY_CREATE@(int64_t, struct ironwood_bridge_result *);
            extern void ironwood_bridge_bootstrap(void);
            int64_t @CALLBACK@(int64_t address, int64_t handle, int64_t value) {
                struct frame *frame = (struct frame *)(uintptr_t)address;
                jobject listener = handle == 0 ? frame->listener : (jobject)(uintptr_t)handle;
                return @DIRECT_CALLBACK@(address, (int64_t)(uintptr_t)listener, value);
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
                int32_t status = iw_listener_prepare(env, 0, value, metadata.classes[IW_EX_OOM], &prepared, &result);
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
            extern int32_t @OWNER_CREATE@(struct ironwood_bridge_result *);
            extern int32_t ironwood_bridge_listener_slots_0(void *, void *, int64_t, struct ironwood_bridge_result *);
            extern int32_t carrier_fire(void *, int64_t, struct ironwood_bridge_result *);
            static void *retained_holder;
            static struct iw_root_record *retained_record;
            #define holder_owner (retained_record->callbacks)
            #define holder_slot (retained_record->listeners[0])
            static int64_t holder_entries, holder_destructions;
            static void holder_open(JNIEnv *env, jclass type, jobject state) {
                (void)type;
                if (retained_holder != NULL) abort();
                struct iw_root_record *reserved = iw_root_reserve(env, state, 0);
                if (reserved == NULL) return;
                struct ironwood_bridge_result result = {0};
                if (@OWNER_CREATE@(&result) != 0) {
                    iw_root_discard(env, reserved); iw_exception_translate(env, &metadata, result.exception); return;
                }
                retained_holder = result.value.reference;
                retained_record = reserved;
                iw_root_publish(env, reserved, retained_holder);
                if ((*env)->GetLongField(env, state, iw_root_listener_owner) != (jlong)(uintptr_t)reserved) abort();
            }
            static void holder_set(JNIEnv *env, jclass type, jobject value) {
                (void)type;
                if (retained_holder == NULL) abort();
                if ((*env)->IsSameObject(env, holder_slot == NULL ? NULL : holder_slot->value->reference, value)) return;
                struct iw_listener_slot *prepared = NULL;
                struct ironwood_bridge_result preparation = {0};
                int32_t status = iw_listener_slot_prepare(env, 0, value, metadata.classes[IW_EX_OOM], &prepared, &preparation);
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
                holder_entries++;
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
            static void holder_close(JNIEnv *env, jclass type, jobject state) {
                (void)type;
                if (holder_owner.active != 0) abort();
                struct iw_root_record **found = iw_root_resolve(env, state, retained_holder);
                if (found == NULL) return;
                iw_root_destroy(env, found);
                holder_destructions++;
                retained_holder = NULL;
                retained_record = NULL;
            }
            static jlong holder_root_counts(JNIEnv *env, jclass type, jboolean records) {
                (void)env; (void)type; return records ? root_records : root_references;
            }
            static jlong holder_counts(JNIEnv *env, jclass type, jboolean destruction) {
                (void)env; (void)type; return destruction ? holder_destructions : holder_entries;
            }
            static int64_t string_buffers, string_entries;
            static int string_fail;
            static const jchar *test_chars(JNIEnv *env, jstring value, jboolean *copy) {
                if (string_fail != 0 && --string_fail == 0) {
                    (*env)->ThrowNew(env, metadata.classes[IW_EX_OOM], "injected String preparation failure"); return NULL;
                }
                const jchar *chars = (*env)->GetStringChars(env, value, copy);
                if (chars != NULL) string_buffers++;
                return chars;
            }
            static void test_release_chars(JNIEnv *env, jstring value, const jchar *chars) {
                string_buffers--; (*env)->ReleaseStringChars(env, value, chars);
            }
            extern int32_t ironwood_bridge_callback_strings_0(void *, int64_t, int32_t, int64_t, int32_t,
                    int64_t, int64_t, struct ironwood_bridge_result *);
            static jlong text_call(JNIEnv *env, jclass type, jobject listener, jstring arg1, jstring arg2, jlong mode) {
                (void)type;
            @TEXT_DECLARATIONS@
            @TEXT_ACQUIRE@
                struct frame frame = {{env, NULL}, listener};
                struct ironwood_bridge_result result = {0};
                string_entries++;
                int32_t status = ironwood_bridge_callback_strings_0(proxy, (int64_t)(uintptr_t)chars1, length1,
                        (int64_t)(uintptr_t)chars2, length2, mode, (int64_t)(uintptr_t)&frame, &result);
            @TEXT_RELEASE@
                if (status != 0 && !iw_callback_restore(env, &result)) iw_exception_translate(env, &metadata, result.exception);
                iw_callback_release(&frame.carrier);
                return result.value.wide;
            preparation_failed:
            @TEXT_RELEASE@
                return 0;
            }
            static jlong text_counts(JNIEnv *env, jclass type, jboolean entries) {
                (void)env; (void)type; return entries ? string_entries : string_buffers;
            }
            static void text_inject(JNIEnv *env, jclass type, jint fail) { (void)env; (void)type; string_fail = fail; }
            static jlong slot_storage(JNIEnv *env, jclass type) { (void)env; (void)type; return slot_records; }
            static void slot_snapshots(JNIEnv *env, jclass type, jobject value) {
                (void)type;
                struct ironwood_bridge_result created = {0};
                if (listener_holder_create(&created) != 0) { iw_exception_translate(env, &metadata, created.exception); return; }
                void *holder = created.value.reference;
                struct iw_listener *prepared = NULL;
                struct ironwood_bridge_result preparation = {0};
                int32_t status = iw_listener_prepare(env, 0, value, metadata.classes[IW_EX_OOM], &prepared, &preparation);
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
            extern int32_t carrier_other(void *, int64_t, struct ironwood_bridge_result *);
            static jlong nominal(JNIEnv *env, jclass type, jobject value) {
                (void)type;
                struct iw_listener *first = NULL, *second = NULL, *alias = NULL;
                struct ironwood_bridge_result result = {0};
                if (iw_listener_prepare(env, 0, value, metadata.classes[IW_EX_OOM], &first, &result) != 0
                        || iw_listener_prepare(env, 1, value, metadata.classes[IW_EX_OOM], &second, &result) != 0
                        || iw_listener_prepare(env, 1, value, metadata.classes[IW_EX_OOM], &alias, &result) != 0) abort();
                if (first == second || first->proxy == second->proxy || alias != second || second->uses != 2
                        || !(*env)->IsSameObject(env, first->reference, second->reference)) abort();
                iw_listener_release(env, alias);
                struct iw_callback_frame frame = {env, NULL};
                int32_t status = carrier_other(second->proxy, (int64_t)(uintptr_t)&frame, &result);
                if (status != 0 && !iw_callback_restore(env, &result)) iw_exception_translate(env, &metadata, result.exception);
                iw_callback_release(&frame);
                iw_listener_release(env, first); iw_listener_release(env, second);
                return result.value.wide;
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
                jclass guard = (*env)->FindClass(env, "@GUARD_PACKAGE@/RootState");
                if (guard == NULL) return JNI_ERR;
                if (!iw_root_metadata_init(env, guard)) return JNI_ERR;
                JNINativeMethod methods[] = {
                    {"invoke", "(LCallbackCarrierConsumer$Listener;J)J", (void *)invoke},
                    {"temporary", "(LCallbackCarrierConsumer$Listener;J)J", (void *)temporary}, {"live", "()J", (void *)live},
                    {"allocated", "()J", (void *)allocated}, {"references", "()J", (void *)references}, {"inject", "(Z)V", (void *)inject},
                    {"setListener", "(ILCallbackCarrierConsumer$Listener;)V", (void *)set_listener},
                    {"retained", "(IJ)J", (void *)retained}, {"listenerReferences", "()J", (void *)listener_references},
                    {"listenerStorage", "()J", (void *)listener_storage}, {"listenerInject", "(I)V", (void *)listener_inject},
                    {"slotSnapshots", "(LCallbackCarrierConsumer$Listener;)V", (void *)slot_snapshots},
                    {"holderOpenNative", "(L@GUARD_PACKAGE@/RootState;)V", (void *)holder_open},
                    {"holderSet", "(LCallbackCarrierConsumer$Listener;)V", (void *)holder_set},
                    {"holderRunNative", "()J", (void *)holder_run},
                    {"holderCloseNative", "(L@GUARD_PACKAGE@/RootState;)V", (void *)holder_close},
                    {"slotStorage", "()J", (void *)slot_storage}, {"holderCounts", "(Z)J", (void *)holder_counts},
                    {"holderRootCounts", "(Z)J", (void *)holder_root_counts},
                    {"nominal", "(LCallbackCarrierConsumer$Dual;)J", (void *)nominal},
                    {"text", "(LCallbackCarrierConsumer$Listener;Ljava/lang/String;Ljava/lang/String;J)J", (void *)text_call},
                    {"textCounts", "(Z)J", (void *)text_counts}, {"textInject", "(I)V", (void *)text_inject}
                };
                if ((*env)->RegisterNatives(env, type, methods, 23) != 0) return JNI_ERR;
                jclass listener = (*env)->FindClass(env, "CallbackCarrierConsumer$Listener");
                if (listener == NULL) return JNI_ERR;
                @METHOD@ = (*env)->GetMethodID(env, listener, "onResult", "(J)J");
                if (@METHOD@ == NULL) return JNI_ERR;
                jclass other = (*env)->FindClass(env, "CallbackCarrierConsumer$OtherListener");
                if (other == NULL) return JNI_ERR;
                @OTHER_METHOD@ = (*env)->GetMethodID(env, other, "onOther", "(J)J");
                if (@OTHER_METHOD@ == NULL) return JNI_ERR;
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
            import @GUARD_PACKAGE@.RootState;
            import @GUARD_PACKAGE@.GuardCheck;
            public final class CallbackCarrierConsumer {
                interface Listener { long onResult(long value); }
                interface OtherListener { long onOther(long value); }
                static final class Dual implements Listener, OtherListener {
                    private final RuntimeException failure;
                    Dual(RuntimeException failure) { this.failure = failure; }
                    @Override public long onResult(long value) { return value + 1L; }
                    @Override public long onOther(long value) { if (failure != null) throw failure; return value * 2L; }
                }
                private static native long nominal(Dual listener);
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
                private static RootState holderState;
                private static native void holderOpenNative(RootState state);
                private static native void holderSet(Listener listener);
                private static native long holderRunNative();
                private static native void holderCloseNative(RootState state);
                private static native long slotStorage();
                private static native long holderCounts(boolean destruction);
                private static native long holderRootCounts(boolean records);
                private static native long text(Listener listener, String first, String second, long mode);
                private static native long textCounts(boolean entries);
                private static native void textInject(int fail);
                private static volatile Object callbackAllocation;
                private static void holderOpen() { RootState state = new RootState(); holderOpenNative(state); holderState = state; }
                private static long holderRun() { RootState state = holderState; @HOLDER_GUARD@ }
                private static void holderClose() {
                    RootState state = holderState;
                    if (state.prepareFree(state.address())) holderCloseNative(state);
                }
                private static void refusedFree() {
                    long before = holderCounts(true);
                    try { holderClose(); throw new AssertionError("missing active-holder refusal"); }
                    catch (IllegalStateException expected) { check(GuardCheck.refusal(expected)); }
                    check(holderCounts(true) == before);
                }
                public static void main(String[] args) {
                    System.load(args[0]);
                    RuntimeException first = new RuntimeException("first"), second = new RuntimeException("second");
                    Listener throwing = value -> { throw first; };
                    long before = allocated();
                    for (int i = 0; i < 10000; i++) check(invoke(value -> value + 42L, 0L) == 42L);
                    check(allocated() == before && references() == 0);
                    if (args[1].equals("graph-oom")) {
                        System.setProperty("ironwood.test.graphFailure", "true");
                        try {
                            expect(first, () -> invoke(throwing, 0L));
                            try { invoke(throwing, 10L); throw new AssertionError("missing snapshot OOM"); }
                            catch (OutOfMemoryError failed) { check(failed.getMessage().equals("injected graph allocation failure")); }
                        } finally { System.clearProperty("ironwood.test.graphFailure"); }
                        check(first.getCause() == null && first.getSuppressed().length == 0);
                        modified(throwing, 10L, first, false, "native secondary");
                        expect(first, () -> invoke(throwing, 0L));
                    } else if (args[1].equals("text-native-oom")) {
                        try { text(value -> { throw new AssertionError("native copy failure ran callback"); }, "first", "second", 0L);
                            throw new AssertionError("missing native second-copy failure"); }
                        catch (OutOfMemoryError expected) { check(textCounts(false) == 0 && live() == 1 && references() == 0); }
                    } else if (args[1].equals("listener-native-oom")) {
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
                        catch (IllegalStateException expected) { check(expected.getMessage().equals("stored listener") && !GuardCheck.refusal(expected)); }
                        check(storedCalls[0] == 1 && listenerReferences() == 0 && listenerStorage() == 0 && live() == beforeSlots + 1);
                        listenerLifecycle(first);
                        holderLifecycle(first);
                        long nominalLive = live();
                        check(nominal(new Dual(null)) == 42L);
                        expect(first, () -> nominal(new Dual(first)));
                        check(live() == nominalLive && listenerReferences() == 0 && listenerStorage() == 0 && references() == 0);
                        textLifecycle(first);
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
                        RuntimeException enriched = modified(throwing, 10L, first, false, "native secondary");
                        check(enriched != first);
                        modified(throwing, 11L, first, true, null);
                        for (int i = 0; i < 3; i++) modified(value -> 0L, 4L, first, true, null);
                        modified(value -> 0L, 12L, first, true, "retained secondary");
                        modified(value -> 0L, 4L, first, true, "retained secondary");
                        try { invoke(value -> 0L, 14L); throw new AssertionError("missing embedded enriched carrier"); }
                        catch (IllegalStateException outer) {
                            check(outer.getMessage().equals("outer"));
                            checkModified((RuntimeException)outer.getCause(), first, true, "retained secondary");
                        }
                        RuntimeException cycle = modified(throwing, 13L, first, true, null);
                        check(cycle.getSuppressed()[0].getCause().getCause() == cycle);
                        RuntimeException bounded = null;
                        for (int i = 0; i < 40; i++) {
                            try { invoke(value -> 0L, 12L); throw new AssertionError("missing bounded enrichment"); }
                            catch (RuntimeException value) { bounded = value; check(value.getCause() == first); }
                        }
                        check(bounded != null && bounded.getSuppressed().length == 33);
                        check(bounded.getSuppressed()[32].getMessage().contains("copy limit"));
                        RuntimeException disabled = new NoSuppression();
                        modified(value -> { throw disabled; }, 10L, disabled, false, "native secondary");
                        check(disabled.getSuppressed().length == 0 && disabled.getCause() == null && disabled.getStackTrace().length == 0);
                        check(first.getCause() == cause && first.getSuppressed().length == 1 && first.getSuppressed()[0] == suppressed
                                && java.util.Arrays.equals(first.getStackTrace(), trace));
                    }
                    System.out.println("callback-carriers-ok:" + args[1]);
                }
                private static final class NoSuppression extends RuntimeException {
                    private static final long serialVersionUID = 1L;
                    NoSuppression() { super("disabled", null, false, false); }
                }
                private static RuntimeException modified(Listener listener, long mode, Throwable original, boolean cause, String secondary) {
                    try { invoke(listener, mode); throw new AssertionError("missing modified carrier"); }
                    catch (RuntimeException value) { checkModified(value, original, cause, secondary); return value; }
                }
                private static void checkModified(RuntimeException value, Throwable original, boolean cause, String secondary) {
                    check(value != original && value.getClass() == RuntimeException.class
                            && value.getMessage().equals("Ironwood callback exception modified by native code") && value.getCause() == original);
                    Throwable[] additions = value.getSuppressed();
                    check(additions.length == (cause ? 1 : 0) + (secondary == null ? 0 : 1));
                    if (cause) check(additions[0].getMessage().equals("Ironwood native cause")
                            && additions[0].getCause() instanceof IllegalStateException
                            && additions[0].getCause().getMessage().equals("native cause"));
                    if (secondary != null) check(additions[additions.length - 1] instanceof IllegalStateException
                            && additions[additions.length - 1].getMessage().equals(secondary));
                }
                private static long hash(String value) {
                    if (value == null) return -1L;
                    long result = 1L;
                    for (int index = 0; index < value.length(); index++) result = result * 31L + value.charAt(index);
                    return result;
                }
                private static void textLifecycle(RuntimeException failure) {
                    long baseline = live();
                    String first = new String(new char[] {'a', 0, (char)0xd800, 'z'});
                    String second = new String(new char[] {(char)0xdc00, 'b', 0});
                    for (int index = 0; index < 100; index++) {
                        check(text(value -> {
                            callbackAllocation = new byte[4096];
                            check(textCounts(false) == 2);
                            check(text(inner -> { callbackAllocation = new byte[4096]; check(textCounts(false) == 4); return 0L; },
                                    second, first, 0L) == hash(first) + hash(second));
                            check(textCounts(false) == 2);
                            return 0L;
                        }, first, second, 0L) == hash(first) + hash(second));
                        expect(failure, () -> text(value -> {
                            expect(failure, () -> text(inner -> { throw failure; }, second, first, 0L));
                            check(textCounts(false) == 2);
                            throw failure;
                        }, first, second, 0L));
                        check(textCounts(false) == 0 && live() == baseline && references() == 0);
                    }
                    check(text(value -> 0L, null, "", 0L) == 0L);
                    for (int fail = 1; fail <= 2; fail++) {
                        long entered = textCounts(true);
                        textInject(fail);
                        try { text(value -> { throw new AssertionError("JNI preparation failure ran callback"); }, first, second, 0L);
                            throw new AssertionError("missing String preparation failure"); }
                        catch (OutOfMemoryError expected) { check(textCounts(false) == 0 && textCounts(true) == entered && live() == baseline); }
                    }
                }
                private static void holderLifecycle(RuntimeException failure) {
                    long baseline = live();
                    long before = allocated();
                    for (int fail = 5; fail <= 7; fail++) {
                        listenerInject(fail);
                        try { holderOpen(); throw new AssertionError("missing root preparation failure"); }
                        catch (OutOfMemoryError expected) {
                            check(holderRootCounts(true) == 0 && holderRootCounts(false) == 0);
                            check(allocated() == before && holderState == null);
                        }
                    }
                    listenerInject(0);
                    holderOpen();
                    check(holderRootCounts(true) == 1 && holderRootCounts(false) == 1);
                    Listener second = value -> {
                        refusedFree();
                        if (value == 20L) holderSet(null);
                        check(listenerReferences() == 2 && slotStorage() == 2);
                        return 4L;
                    };
                    Listener first = value -> {
                        refusedFree();
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
                        refusedFree();
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
                    holderSet(second);
                    check(listenerReferences() == 1 && slotStorage() == 1);
                    holderClose();
                    check(holderRootCounts(true) == 0 && holderRootCounts(false) == 0);
                    long destroyed = holderCounts(true), entered = holderCounts(false);
                    holderClose();
                    try { holderRun(); throw new AssertionError("dead holder entered native code"); }
                    catch (IllegalStateException expected) { check(GuardCheck.refusal(expected)); }
                    check(holderCounts(true) == destroyed && holderCounts(false) == entered);
                    check(live() == baseline && listenerStorage() == 0 && listenerReferences() == 0 && slotStorage() == 0);
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
