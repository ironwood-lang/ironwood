// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.backend.*;
import ironwood.compiler.bridge.*;
import ironwood.compiler.ir.IrType;
import ironwood.compiler.source.SourceFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

final class BridgeRootIndexTests {
    static final String NAME = "Java Bridge generated root index reserves before invocation and commits exact destruction";
    static final String SOURCE = """
            package rootindex;
            public final class Root {
                private final String label;
                public Root(boolean fail) { label = new String("root"); if (fail) throw null; }
                destructor { free label; }
                public static Root fresh(boolean absent, boolean fail) { return absent ? null : new Root(fail); }
                public int value() { return 17; }
            }
            """;
    private BridgeRootIndexTests() {}

    static void index() throws Exception {
        var artifact = analyze(SOURCE, "Root.iron");
        var admission = admit(artifact, "rootindex");
        var producer = BridgeProducerInputs.discover();
        var generation = BridgeGeneration.createObjects("root-index.jar", artifact, admission,
                producer.compilerVersion(), producer.compilerIdentity(), producer.runtimeIdentity());
        var index = BridgeRootIndexSources.generate(artifact, admission, generation);
        check(index.kinds().equals(List.of(IrType.reference("rootindex.Root"))), "unexpected destruction inventory");
        try { BridgeRootIndexSources.generate(analyze(SOURCE.replace("return 17", "return 19"), "Root.iron"), admission, generation);
            throw new AssertionError("stale root index admitted"); }
        catch (IllegalArgumentException expected) { check(expected.getMessage().contains("matching final root admission"), expected.getMessage()); }
        var retainedArtifact = analyze(BridgeMixedLifetimeTests.SOURCE, "Holder.iron");
        var retained = admit(retainedArtifact, "mixedlife");
        var retainedGeneration = BridgeGeneration.createObjects("retained.jar", retainedArtifact, retained,
                producer.compilerVersion(), producer.compilerIdentity(), producer.runtimeIdentity());
        var retainedIndex = BridgeRootIndexSources.generate(retainedArtifact, retained, retainedGeneration);
        check(retainedIndex.source().contains("iw_root_dependencies")
                && retainedIndex.source().contains("incoming - 1"), "retained root destruction lost outgoing dependency release");
        Path base = Path.of("workspace/java-bridge/evidence/p3c/root-index").toAbsolutePath(); Files.createDirectories(base);
        Path directory = Files.createTempDirectory(base, "run-");
        Files.writeString(directory.resolve("Root.iron"), SOURCE);
        String llvmText = new LlvmEmitter().emit(admission.program());
        Path llvm = directory.resolve("program.ll"); Files.writeString(llvm, llvmText);
        var java = BridgeRootStateSources.generate(artifact, admission, generation);
        Path classes = directory.resolve("classes"), javaHome = Path.of(System.getProperty("java.home"));
        var sources = new java.util.TreeMap<>(java.sources());
        sources.put(generation.supportPackage().replace('.', '/') + "/Identity.java", "package " + generation.supportPackage() + "; @interface Identity { String value(); }");
        sources.put(generation.supportPackage().replace('.', '/') + "/TestIdentity.java", "package " + generation.supportPackage()
                + "; public final class TestIdentity { public static boolean refusal(Throwable value) { return value.getClass() == BridgeLifetimeException.class; } }");
        sources.put("IndexProbe.java", "import " + generation.supportPackage() + ".RootState;\nimport " + generation.supportPackage() + ".TestIdentity;\n" + CONSUMER);
        var javac = new ArrayList<>(List.of(javaHome.resolve("bin/javac").toString(), "--release", "21", "-Xlint:all", "-Werror", "-d", classes.toString()));
        var hashes = new java.util.TreeMap<String, String>();
        for (var entry : sources.entrySet()) {
            Path path = directory.resolve("sources").resolve(entry.getKey()); Files.createDirectories(path.getParent()); Files.writeString(path, entry.getValue());
            javac.add(path.toString()); hashes.put(entry.getKey(), digest(entry.getValue()));
        }
        BridgeEntryTests.run(directory, javac, "javac");
        var discovery = LlvmToolchain.discover(null); check(discovery.successful(), discovery.error());
        var toolchain = discovery.toolchain().orElseThrow(); boolean mac = System.getProperty("os.name").startsWith("Mac");
        var fresh = admission.entries().entries().stream().filter(entry -> entry.root().callable().name().equals("fresh")).findFirst().orElseThrow();
        check(admission.roots().orElseThrow().protocol().resultOrigins().get(fresh.root().callable()).kind() == BridgeResultOriginContract.Kind.FRESH_ROOT,
                "test invocation lost bounded fresh-root proof");
        String harness = HARNESS.replace("@ROOT@", generation.supportPackage().replace('.', '/') + "/RootState")
                .replace("@FRESH@", fresh.function().linkageName());
        for (boolean faults : List.of(false, true)) {
            String nativeSource = "#include <ironwood_bridge.h>\n#include <ironwood_runtime.h>\n";
            nativeSource += faults ? "static int iw_test_fault;\n" + injected(index.source()) : index.source();
            nativeSource += harness.replace("@FAULT@", faults ? "iw_test_fault = value;" : "(void)value;");
            Path folder = directory.resolve(faults ? "injected" : "production"); Files.createDirectories(folder);
            Path adapter = folder.resolve("adapter.c"); Files.writeString(adapter, nativeSource);
            Files.writeString(folder.resolve("identity.txt"), "generation=" + generation.identity() + "\nllvm=" + digest(llvmText)
                    + "\ncomponent=" + digest(index.source()) + "\nadapter=" + digest(nativeSource) + "\njava=" + BridgeGeneration.contentIdentity(hashes)
                    + "\ncompiler=" + producer.compilerIdentity() + "\nruntime=" + producer.runtimeIdentity()
                    + "\nscope=generated registration/destruction component with test-only invocation and bootstrap; not public facade qualification\n");
            for (var level : List.of(OptimizationLevel.O0, OptimizationLevel.O3)) {
                Path object = folder.resolve("adapter-" + level + ".o");
                BridgeEntryTests.run(folder, List.of(toolchain.clang().toString(), "-std=c11", "-Wall", "-Wextra", "-Werror", "-fPIC", "-fvisibility=hidden",
                        level.clangArgument(), "-I" + javaHome.resolve("include"), "-I" + javaHome.resolve(mac ? "include/darwin" : "include/linux"),
                        "-I" + Path.of("runtime/include").toAbsolutePath(), "-c", adapter.toString(), "-o", object.toString()), "adapter-" + level);
                Path image = folder.resolve("index-" + level + (mac ? ".dylib" : ".so"));
                var linked = new NativeBackend().linkShared(toolchain, llvm, image, level, List.of(object));
                Files.writeString(folder.resolve("link-" + level + ".log"), linked.output()); check(linked.success(), linked.output());
                Files.writeString(folder.resolve("payload-" + level + ".sha256"), BridgeGeneration.bytesDigest(Files.readAllBytes(image)) + "\n");
                var command = BridgeEntryTests.grantNativeAccess(List.of(javaHome.resolve("bin/java").toString(), "-Xcheck:jni", "-Xmx64m", "-cp", classes.toString(),
                        "IndexProbe", image.toString(), faults ? "faults" : "production"));
                String output = BridgeEntryTests.run(folder, command, "consumer-" + level);
                check(output.equals(faults ? "root-index-faults-ok\n" : "root-index-ok:registration:collection:destruction:growth\n"), output);
                if (!faults) {
                    BridgeEntryTests.run(folder, List.of(toolchain.clang().resolveSibling("llvm-objdump").toString(),
                            "--disassemble", "--reloc", object.toString()), "adapter-disassembly-" + level);
                    for (int budget : List.of(0, 1, 2)) {
                        var limited = new ArrayList<>(command); limited.set(limited.size() - 1, "budget"); limited.add(Integer.toString(budget));
                        String name = "budget-" + budget + "-" + level;
                        Files.writeString(folder.resolve(name + ".command.txt"), String.join("\n", limited) + "\nIRONWOOD_ALLOCATION_LIMIT=" + budget + "\n");
                        var builder = new ProcessBuilder(limited).redirectErrorStream(true).redirectOutput(folder.resolve(name + ".log").toFile());
                        builder.environment().put("IRONWOOD_ALLOCATION_LIMIT", Integer.toString(budget));
                        var child = builder.start();
                        if (!child.waitFor(60, TimeUnit.SECONDS)) { child.destroyForcibly(); throw new AssertionError("root budget child timed out"); }
                        String result = Files.readString(folder.resolve(name + ".log"));
                        check(child.exitValue() == 0 && result.equals("root-index-budget-ok:" + budget + "\n"), result);
                    }
                }
            }
        }
        System.out.println("root index component evidence: " + directory);
    }

    private static CompilationArtifact analyze(String source, String file) {
        var artifact = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(List.of(SourceFile.of(file, source)));
        check(artifact.valid(), artifact.diagnostics().toString()); return artifact;
    }
    private static BridgeObjectAdmission admit(CompilationArtifact artifact, String pkg) {
        var proof = BridgeObjectAdmission.prove(artifact, List.of(pkg)); check(proof.contract().isPresent(), proof.reason()); return proof.contract().orElseThrow();
    }
    private static String digest(String value) { return BridgeGeneration.bytesDigest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static String injected(String source) {
        for (var replacement : Map.of(
                "if ((*env)->EnsureLocalCapacity(env, 8) != JNI_OK) return NULL;",
                "if (iw_test_fault == 1) { (*env)->ThrowNew(env, iw_root_oom, \"injected local capacity\"); return NULL; }\n"
                        + "    if ((*env)->EnsureLocalCapacity(env, 8) != JNI_OK) return NULL;",
                "struct iw_root_record *record = malloc(sizeof(*record));", "struct iw_root_record *record = iw_test_fault == 2 ? NULL : malloc(sizeof(*record));",
                "struct iw_root_record **replacement = calloc(next, sizeof(*iw_root_table));",
                "struct iw_root_record **replacement = iw_test_fault == 3 ? NULL : calloc(next, sizeof(*iw_root_table));",
                "record->state = (*env)->NewGlobalRef(env, state);", "record->state = iw_test_fault == 4 ? NULL : (*env)->NewGlobalRef(env, state);").entrySet()) {
            check(source.contains(replacement.getKey()), "root index fault anchor changed"); source = source.replace(replacement.getKey(), replacement.getValue());
        }
        return source;
    }

    private static final String CONSUMER = """
            import java.lang.ref.WeakReference;
            public final class IndexProbe {
                private static native long create(RootState reserved, boolean absent, boolean fail);
                private static native RootState lookup(long address);
                private static native void destroy(RootState state, long address);
                private static native long metric(int what);
                private static native void fault(int site);
                private static void check(boolean value) { if (!value) throw new AssertionError(); }
                private static void free(RootState state) { if (state.prepareFree(state.address())) destroy(state, state.address()); }
                public static void main(String[] args) throws Exception {
                    System.load(args[0]);
                    if (args[1].equals("faults")) { faults(); return; }
                    if (args[1].equals("budget")) { budget(Integer.parseInt(args[2])); return; }
                    RootState empty = new RootState(); check(create(empty, true, false) == 0 && empty.address() == 0 && metric(1) == 0);
                    long baseline = metric(2), allocated = metric(5);
                    try { create(new RootState(), false, true); throw new AssertionError("missing constructor failure"); }
                    catch (OutOfMemoryError expected) {
                        // Root and its copied label are reclaimed; the thrown native exception remains allocated.
                        if (metric(1) != 0 || metric(2) != baseline + 1 || metric(5) != allocated + 3) {
                            throw new AssertionError("failed constructor: roots=" + metric(1) + ", live=" + metric(2)
                                    + ", baseline=" + baseline + ", allocations=" + (metric(5) - allocated));
                        }
                    }
                    baseline++;
                    RootState first = new RootState(); long address = create(first, false, false);
                    check(address != 0 && address == first.address() && lookup(address) == first && metric(1) == 1);
                    RootState foreign = new RootState();
                    try { destroy(foreign, address); throw new AssertionError("foreign state destroyed root"); }
                    catch (IllegalArgumentException expected) { first.checkLive(); check(lookup(address) == first); }
                    long oldAddress = first.address(); free(first); free(first);
                    check(lookup(oldAddress) == null && first.address() == oldAddress && metric(1) == 0 && metric(2) == baseline);
                    try { first.checkLive(); throw new AssertionError("dead state accepted"); }
                    catch (IllegalStateException expected) { check(TestIdentity.refusal(expected)); }
                    for (int pass = 0; pass < 8; pass++) {
                        WeakReference<RootState> weak = abandoned();
                        for (int i = 0; i < 8; i++) { System.gc(); Thread.sleep(5); }
                        RootState restored = lookup(metric(3)); check(restored != null && weak.get() == restored); free(restored);
                    }
                    RootState[] held = new RootState[257];
                    for (int i = 0; i < held.length; i++) { held[i] = new RootState(); create(held[i], false, false); }
                    check(metric(1) == held.length && metric(4) >= 1024);
                    for (RootState state : held) { check(lookup(state.address()) == state); free(state); }
                    check(metric(1) == 0 && metric(2) == baseline);
                    // Fill tombstones repeatedly, including absent lookups in a table with no empty cells.
                    for (int i = 0; i < 4096; i++) { RootState state = new RootState(); create(state, false, false); free(state); check(lookup(1) == null); }
                    check(metric(1) == 0 && metric(2) == baseline);
                    WeakReference<RootState> released = released();
                    for (int i = 0; i < 200 && released.get() != null; i++) { System.gc(); Thread.sleep(10); }
                    check(released.get() == null && metric(1) == 0 && metric(2) == baseline);
                    System.out.println("root-index-ok:registration:collection:destruction:growth");
                }
                private static WeakReference<RootState> abandoned() {
                    RootState state = new RootState(); create(state, false, false); return new WeakReference<>(state);
                }
                private static WeakReference<RootState> released() {
                    RootState state = new RootState(); create(state, false, false); free(state); return new WeakReference<>(state);
                }
                private static void budget(int budget) {
                    RootState state = new RootState();
                    try {
                        create(state, false, false); check(budget == 2 && lookup(state.address()) == state); free(state);
                    } catch (OutOfMemoryError expected) { check(budget < 2 && state.address() == 0); }
                    check(metric(0) == 1 && metric(1) == 0 && metric(2) == 0);
                    System.out.println("root-index-budget-ok:" + budget);
                }
                private static void faults() {
                    for (int site : new int[]{1, 2, 3, 4}) {
                        fault(site); long calls = metric(0), live = metric(2);
                        RootState state = new RootState();
                        try { create(state, false, false); throw new AssertionError("missing preparation failure " + site); }
                        catch (OutOfMemoryError expected) { check(metric(0) == calls && metric(1) == 0 && metric(2) == live && state.address() == 0); }
                    }
                    fault(0); RootState[] held = new RootState[3];
                    for (int i = 0; i < held.length; i++) { held[i] = new RootState(); create(held[i], false, false); }
                    for (int site : new int[]{3, 4}) {
                        fault(site); long calls = metric(0), live = metric(2);
                        try { create(new RootState(), false, false); throw new AssertionError("missing growth/reference failure"); }
                        catch (OutOfMemoryError expected) { check(metric(0) == calls && metric(1) == held.length && metric(2) == live); }
                        for (RootState state : held) check(lookup(state.address()) == state);
                    }
                    fault(0); for (RootState state : held) free(state);
                    RootState retry = new RootState(); create(retry, false, false); free(retry); check(metric(1) == 0);
                    System.out.println("root-index-faults-ok");
                }
            }
            """;

    private static final String HARNESS = """
            /* Test-only JNI probe of the generated component and exact admitted typed factory. */
            extern int32_t @FRESH@(uint8_t, uint8_t, int64_t);
            extern void ironwood_bridge_bootstrap(void);
            static int64_t test_calls;
            static void *test_last;
            JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *unused) {
                (void)unused; JNIEnv *env = NULL;
                if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_8) != JNI_OK) return JNI_ERR;
                jclass state = (*env)->FindClass(env, "@ROOT@");
                if (state == NULL) return JNI_ERR;
                int ok = iw_root_metadata_init(env, state); (*env)->DeleteLocalRef(env, state);
                if (!ok) return JNI_ERR;
                ironwood_bridge_bootstrap(); return JNI_VERSION_1_8;
            }
            JNIEXPORT jlong JNICALL Java_IndexProbe_create(JNIEnv *env, jclass type, jobject state, jboolean absent, jboolean fail) {
                (void)type; struct iw_root_record *reserved = iw_root_reserve(env, state, 0);
                if (reserved == NULL) return 0;
                struct ironwood_bridge_result result; test_calls++;
                int32_t status = @FRESH@(absent, fail, (int64_t)(uintptr_t)&result);
                if (status == 0 && result.value.reference != NULL) {
                    iw_root_publish(env, reserved, result.value.reference); test_last = result.value.reference;
                    return (jlong)(uintptr_t)result.value.reference;
                }
                iw_root_discard(env, reserved);
                if (status != 0) (*env)->ThrowNew(env, iw_root_oom, "test protected constructor failed");
                return 0;
            }
            JNIEXPORT jobject JNICALL Java_IndexProbe_lookup(JNIEnv *env, jclass type, jlong address) {
                (void)type; struct iw_root_record **found = iw_root_find((void *)(uintptr_t)address);
                return found == NULL ? NULL : (*env)->NewLocalRef(env, (*found)->state);
            }
            JNIEXPORT void JNICALL Java_IndexProbe_destroy(JNIEnv *env, jclass type, jobject state, jlong address) {
                (void)type; struct iw_root_record **found = iw_root_resolve(env, state, (void *)(uintptr_t)address);
                if (found != NULL) iw_root_destroy(env, found);
            }
            JNIEXPORT jlong JNICALL Java_IndexProbe_metric(JNIEnv *env, jclass type, jint metric) {
                (void)env; (void)type;
                switch (metric) {
                    case 0: return test_calls; case 1: return (jlong)iw_root_occupied;
                    case 2: return ironwood_live_allocation_count(); case 3: return (jlong)(uintptr_t)test_last;
                    case 4: return (jlong)iw_root_capacity; case 5: return ironwood_allocation_count(); default: return -1;
                }
            }
            JNIEXPORT void JNICALL Java_IndexProbe_fault(JNIEnv *env, jclass type, jint value) {
                (void)env; (void)type; @FAULT@
            }
            """;
}
