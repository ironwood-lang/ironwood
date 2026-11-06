// SPDX-License-Identifier: MIT OR Apache-2.0
package ironwood.compiler;

import ironwood.compiler.backend.*;
import ironwood.compiler.bridge.*;
import ironwood.compiler.source.SourceFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Normal source/frontend/native pipeline with cold, qualification-only observers. */
public final class Gc8Qualification {
    private Gc8Qualification() {}

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]).toAbsolutePath(), llvm = Path.of(args[1]);
        var sources = List.of("integration-tests/cases/gc8/Graphs.iron", "integration-tests/cases/gc8/Gc8Kernels.iron",
                "integration-tests/cases/gc8/GraphPhases.iron")
                .stream().map(Path::of).map(path -> {
                    try { return SourceFile.of(path.toString(), Files.readString(path)); }
                    catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
                }).toList();
        var artifact = new CompilerPipeline().analyzeForBridge(sources);
        if (!artifact.valid()) throw new AssertionError(artifact.diagnostics());
        var proof = BridgeObjectAdmission.proveManaged(artifact, List.of("gc8"));
        if (proof.status() != BridgeProof.Status.PROVED) throw new AssertionError(proof.reason());
        var admission = proof.contract().orElseThrow();
        var inputs = BridgeProducerInputs.discover();
        var generation = BridgeGeneration.createObjects("gc8", artifact, admission,
                inputs.compilerVersion(), inputs.compilerIdentity(), inputs.runtimeIdentity());
        var java = BridgeFacadeSources.generateRoots(artifact, admission, generation);
        var nativeSources = BridgeFacadeNativeSources.generateRoots(artifact, admission, generation, java);
        var tools = LlvmToolchain.discover(llvm).toolchain().orElseThrow();
        var buildInputs = new TreeMap<>(BridgeGcBuildInputs.discover(tools));
        buildInputs.put("fixture", "gc8-resident-qualification");
        var build = generation.nativeBuild(ManagedBridgeTestBuild.target(), buildInputs);
        String source = BridgeGcTransitions.compose(admission, nativeSources.source()
                + BridgeBootstrapSources.generate(generation, build, java.declarations(), nativeSources));
        // Count actual JNI acquisitions only in this adapter. No runtime/SDK edits.
        source = source.replace("(*env)->NewGlobalRef(env,", "gc8_global_new(env,")
                .replace("(*env)->DeleteGlobalRef(env,", "gc8_global_delete(env,")
                .replace("(*frame->env)->DeleteGlobalRef(frame->env,", "gc8_global_delete(frame->env,");
        source = """
                #if defined(__linux__) && !defined(_POSIX_C_SOURCE)
                #define _POSIX_C_SOURCE 200809L
                #endif
                #include <jni.h>
                static jlong gc8_globals;
                static jobject gc8_global_new(JNIEnv *env, jobject value) {
                    jobject result = (*env)->NewGlobalRef(env, value);
                    if (result != NULL) gc8_globals++;
                    return result;
                }
                static void gc8_global_delete(JNIEnv *env, jobject value) {
                    if (value != NULL) gc8_globals--;
                    (*env)->DeleteGlobalRef(env, value);
                }
                """ + source + Files.readString(Path.of("integration-tests/native/gc8_observer.c"));
        Files.createDirectories(out);
        Files.writeString(out.resolve("program.ll"), new LlvmEmitter().emit(admission));
        ManagedBridgeTestBuild.build(out, llvm, tools, OptimizationLevel.valueOf(args[2]), generation, build,
                java.declarations(), source, Map.of(), admission, List.of("Java_Gc8Consumer_observe"));
        Files.writeString(out.resolve("support.txt"), generation.supportPackage());
    }
}
