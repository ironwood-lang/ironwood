// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.backend;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class NativeBackend {
    private static final int MAX_CACHED_RUNTIME_OBJECTS = 8;
    private static final Map<RuntimeObjectKey, byte[]> RUNTIME_OBJECTS = new LinkedHashMap<>();

    /** Development-only object path. Caller must verify/link the GC2 SDK and root reader. */
    public LinkResult compileGcExperiment(LlvmToolchain toolchain, ironwood.compiler.ir.IrProgram program,
                                          NativeTarget target, Path directory, OptimizationLevel level) {
        return compileGcExperiment(toolchain, program, target, directory, level, false);
    }

    public LinkResult compileGcExperiment(LlvmToolchain toolchain, ironwood.compiler.ir.IrProgram program,
                                          NativeTarget target, Path directory, OptimizationLevel level, boolean runtimeOwners) {
        return compileGcExperiment(toolchain, program, target, directory, level, runtimeOwners, false);
    }

    public LinkResult compileGcExperiment(LlvmToolchain toolchain, ironwood.compiler.ir.IrProgram program,
                                          NativeTarget target, Path directory, OptimizationLevel level, boolean runtimeOwners,
                                          boolean foreignSegments) {
        boolean macho = target.triple().startsWith("arm64-apple-macosx")
                && target.dataLayout().equals("e-m:o-p270:32:32-p271:32:32-p272:64:64-i64:64-i128:128-n32:64-S128-Fn32");
        boolean elf = target.triple().equals("x86_64-unknown-linux-gnu")
                && target.dataLayout().equals("e-m:e-p270:32:32-p271:32:32-p272:64:64-i64:64-i128:128-f80:128-n8:16:32:64-S128");
        if (!macho && !elf) {
            return new LinkResult(false, "GC3 native root reader is not qualified for " + target.triple());
        }
        if (!toolchain.version().equals("23.1.0")) return new LinkResult(false, "GC3 requires qualified LLVM 23.1.0");
        try {
            Files.createDirectories(directory);
            Path input = directory.resolve("program.ll"), bitcode = directory.resolve("program.bc"),
                    optimized = directory.resolve("program.opt.ll"), traced = directory.resolve("program.traced.ll"),
                    rooted = directory.resolve("program.roots.ll"), object = directory.resolve("program.o");
            Files.writeString(input, target.applyTo(new LlvmEmitter().emitGcExperiment(program, runtimeOwners, foreignSegments)), StandardCharsets.UTF_8);
            LinkResult result = run("GC3 LLVM assembly", List.of(toolchain.llvmAs().toString(), input.toString(), "-o", bitcode.toString()));
            if (!result.success()) return result;
            var optimize = new java.util.ArrayList<>(List.of(toolchain.opt().toString(), "-passes=" + level.optPassPipeline()));
            optimize.addAll(level.optExtraArguments());
            optimize.addAll(List.of("-S", bitcode.toString(), "-o", optimized.toString()));
            result = run("GC3 LLVM optimization", optimize);
            if (!result.success()) return result;
            // Trace attribution sees optimized function identities. Statepoints are
            // inserted afterward in every mode; no optimizer runs after them.
            String optimizedText = GcLateVerification.optimizedStrategies(Files.readString(input), Files.readString(optimized));
            Files.writeString(traced, OptimizedTraceMetadata.injectGc(optimizedText, macho), StandardCharsets.UTF_8);
            var statepoints = new java.util.ArrayList<>(List.of(toolchain.opt().toString()));
            if (runtimeOwners) {
                Path plugin = directory.resolve("nonmoving-statepoints.so");
                var hostBuild = new java.util.ArrayList<>(List.of(toolchain.clang().toString(), "--driver-mode=g++"));
                // The pass runs inside host opt. Conda's runtime-only GCC can
                // shadow installed C++ headers; use the native host development
                // toolchain here, independently of the program's SDK/sysroot.
                if (System.getProperty("os.name").equals("Linux")) hostBuild.add("--gcc-toolchain=/usr");
                hostBuild.addAll(List.of(
                        "-std=c++17", "-fno-exceptions", "-fPIC", "-shared", "-O2",
                        "-I" + toolchain.home().resolve("include"), "-L" + toolchain.home().resolve("lib"),
                        "-Wl,-rpath," + toolchain.home().resolve("lib"), "-lLLVM",
                        "compiler/src/main/cpp/GcNonmovingStatepoints.cpp", "-o", plugin.toString()));
                result = run("GC4 nonmoving statepoint pass build", hostBuild);
                if (!result.success()) return result;
                statepoints.add("-load-pass-plugin=" + plugin);
                statepoints.add("-passes=ironwood-nonmoving-statepoints,verify");
            } else statepoints.add("-passes=rewrite-statepoints-for-gc,verify");
            statepoints.addAll(List.of("-S", traced.toString(), "-o", rooted.toString()));
            result = run("GC late statepoints", statepoints);
            if (!result.success()) return result;
            GcLateVerification.stackRegions(Files.readString(rooted));
            result = run("GC3 object generation", List.of(toolchain.llc().toString(), "-filetype=obj", "--relocation-model=pic",
                    level.llcArgument(), rooted.toString(), "-o", object.toString()));
            if (result.success()) {
                Files.write(object, GcRootSections.retain(Files.readAllBytes(object)));
                Files.writeString(directory.resolve("root-contract.c"), GcRootSections.contract(Files.readAllBytes(object)));
                if (elf) {
                    Files.writeString(directory.resolve("root-contract.ld"), GcRootSections.elfLinkerScript());
                    result = run("GC3 ELF root section naming", List.of(toolchain.llvmObjcopy().toString(),
                            "--rename-section", ".llvm_stackmaps=ironwood_gc_maps", object.toString()));
                }
            }
            return result;
        } catch (IOException | IllegalArgumentException exception) {
            return new LinkResult(false, "GC3 development compilation refused: " + exception.getMessage());
        }
    }

    public LinkResult link(LlvmToolchain toolchain, Path llvmIr, Path output) {
        return link(toolchain, llvmIr, output, OptimizationLevel.O0);
    }

    public LinkResult link(LlvmToolchain toolchain, Path llvmIr, Path output,
                           OptimizationLevel optimizationLevel) {
        return link(toolchain, llvmIr, output, optimizationLevel, NativeLinkRequirements.NONE);
    }

    public LinkResult link(LlvmToolchain toolchain, Path llvmIr, Path output,
                           OptimizationLevel optimizationLevel, NativeLinkRequirements requirements) {
        return link(toolchain, llvmIr, output, optimizationLevel, requirements, TargetMachine.DEFAULT);
    }

    public LinkResult link(LlvmToolchain toolchain, Path llvmIr, Path output,
                           OptimizationLevel optimizationLevel, NativeLinkRequirements requirements,
                           TargetMachine targetMachine) {
        return link(toolchain, llvmIr, output, optimizationLevel, requirements, targetMachine, null);
    }

    public LinkResult link(LlvmToolchain toolchain, Path llvmIr, Path output,
                           OptimizationLevel optimizationLevel, NativeLinkRequirements requirements,
                           TargetMachine targetMachine, Integer inlineThreshold) {
        return link(toolchain, llvmIr, output, optimizationLevel, requirements, targetMachine,
                inlineThreshold, null);
    }

    public LinkResult link(LlvmToolchain toolchain, Path llvmIr, Path output,
                           OptimizationLevel optimizationLevel, NativeLinkRequirements requirements,
                           TargetMachine targetMachine, Integer inlineThreshold, Boolean partialInlining) {
        return link(toolchain, llvmIr, output, optimizationLevel, requirements, targetMachine,
                inlineThreshold, partialInlining, null);
    }

    public LinkResult link(LlvmToolchain toolchain, Path llvmIr, Path output,
                           OptimizationLevel optimizationLevel, NativeLinkRequirements requirements,
                           TargetMachine targetMachine, Integer inlineThreshold, Boolean partialInlining,
                           Path optimizationReport) {
        return linkImage(toolchain, llvmIr, output, optimizationLevel, requirements, targetMachine,
                inlineThreshold, partialInlining, optimizationReport, NativeOutputKind.EXECUTABLE, List.of(), null);
    }

    /** Internal shared-image path; native adapters are compiled separately with Clang. */
    public LinkResult linkShared(LlvmToolchain toolchain, Path llvmIr, Path output,
                                 OptimizationLevel optimizationLevel, List<Path> adapterObjects) {
        return linkShared(toolchain, llvmIr, output, optimizationLevel, adapterObjects, null);
    }

    public LinkResult linkShared(LlvmToolchain toolchain, Path llvmIr, Path output,
                                 OptimizationLevel optimizationLevel, List<Path> adapterObjects, MacNativeTools macTools) {
        return linkImage(toolchain, llvmIr, output, optimizationLevel, NativeLinkRequirements.NONE,
                TargetMachine.DEFAULT, null, null, null, NativeOutputKind.SHARED_LIBRARY, List.copyOf(adapterObjects), macTools);
    }

    /** Managed shared image with verified foreign roots and an explicit export policy. */
    public LinkResult linkManagedBridge(LlvmToolchain toolchain, ironwood.compiler.BridgeObjectAdmission admission,
            Path output, OptimizationLevel level, List<Path> adapters, List<String> exports) {
        if (!admission.managedHeap() || exports.isEmpty() || exports.stream().anyMatch(s -> !s.matches("[A-Za-z_][A-Za-z0-9_]*")))
            return new LinkResult(false, "managed bridge requires managed admission and explicit native exports");
        Path scratch = null;
        try {
            scratch = Files.createTempDirectory("ironwood-gc6-qualified-");
            Path llvm = scratch.resolve("program.ll");
            Files.writeString(llvm, new LlvmEmitter().emit(admission), StandardCharsets.UTF_8);
            return linkImage(toolchain, llvm, output, level, NativeLinkRequirements.NONE, TargetMachine.DEFAULT,
                    null, null, null, NativeOutputKind.SHARED_LIBRARY, List.copyOf(adapters), null, exports);
        } catch (IOException | IllegalArgumentException failure) {
            return new LinkResult(false, "managed bridge refused: " + failure.getMessage());
        } finally { deleteTree(scratch); }
    }

    public LinkResult link(LlvmToolchain toolchain, Path llvmIr, Path output,
                           OptimizationLevel optimizationLevel, NativeLinkRequirements requirements,
                           TargetMachine targetMachine, NativeOutputKind kind, List<Path> adapterObjects) {
        if (kind == NativeOutputKind.EXECUTABLE && !adapterObjects.isEmpty()) {
            return new LinkResult(false, "native adapter objects require shared-library output");
        }
        return linkImage(toolchain, llvmIr, output, optimizationLevel, requirements, targetMachine,
                null, null, null, kind, List.copyOf(adapterObjects), null);
    }

    private LinkResult linkImage(LlvmToolchain toolchain, Path llvmIr, Path output,
                                 OptimizationLevel optimizationLevel, NativeLinkRequirements requirements,
                                 TargetMachine targetMachine, Integer inlineThreshold, Boolean partialInlining,
                                 Path optimizationReport, NativeOutputKind kind, List<Path> adapterObjects, MacNativeTools selectedMacTools) {
        return linkImage(toolchain, llvmIr, output, optimizationLevel, requirements, targetMachine, inlineThreshold,
                partialInlining, optimizationReport, kind, adapterObjects, selectedMacTools, List.of());
    }

    private LinkResult linkImage(LlvmToolchain toolchain, Path llvmIr, Path output,
                                 OptimizationLevel optimizationLevel, NativeLinkRequirements requirements,
                                 TargetMachine targetMachine, Integer inlineThreshold, Boolean partialInlining,
                                 Path optimizationReport, NativeOutputKind kind, List<Path> adapterObjects, MacNativeTools selectedMacTools,
                                 List<String> foreignExports) {
        if (foreignExports.isEmpty() && (kind != NativeOutputKind.EXECUTABLE || !adapterObjects.isEmpty())) {
            return new LinkResult(false, "shared-library output requires verified managed bridge admission and image exports");
        }
        boolean shared = kind == NativeOutputKind.SHARED_LIBRARY;
        Path temporaryDirectory = null;
        try {
            Path outputParent = output.toAbsolutePath().normalize().getParent();
            if (outputParent != null) {
                Files.createDirectories(outputParent);
            }
            temporaryDirectory = outputParent == null
                    ? Files.createTempDirectory("ironwoodc-native-")
                    : Files.createTempDirectory(outputParent, ".ironwoodc-native-");
            Path assembledBitcode = temporaryDirectory.resolve("program.bc");
            Path optimizedLlvm = temporaryDirectory.resolve("program.opt.ll");
            Path tracedLlvm = temporaryDirectory.resolve("program.traced.ll");
            Path optimizedBitcode = temporaryDirectory.resolve("program.opt.bc");
            Path objectFile = temporaryDirectory.resolve("program.o");
            Path runtimeObjectFile = temporaryDirectory.resolve("ironwood_runtime.o");
            Path caseObjectFile = temporaryDirectory.resolve("ironwood_case.o");
            Path tcpObjectFile = temporaryDirectory.resolve("ironwood_tcp.o");
            Path hostObjectFile = temporaryDirectory.resolve("ironwood_host.o");

            RuntimeLibrary.Discovery runtime = RuntimeLibrary.discover();
            if (!runtime.successful()) {
                return new LinkResult(false, runtime.error());
            }

            TlsDependency tls = requirements.tls() ? TlsDependency.discover(
                    runtime.source().orElseThrow().getParent().getParent().getParent(), toolchain) : null;
            Path distribution = runtime.source().orElseThrow().getParent().getParent().getParent();
            GcDependency gc = GcDependency.discover(distribution, toolchain);
            BridgeNativeSupport bridgeSupport = shared && System.getProperty("os.name").startsWith("Linux")
                    ? BridgeNativeSupport.discover(toolchain) : null;
            MacNativeTools macTools = System.getProperty("os.name").startsWith("Mac")
                    ? selectedMacTools == null ? MacNativeTools.discover() : selectedMacTools : null;
            List<String> targetFlags = new java.util.ArrayList<>(targetMachine.clangArguments());
            targetFlags.add("-fvisibility=hidden");
            if (macTools != null) targetFlags.addAll(macTools.compileFlags());
            if (bridgeSupport != null) targetFlags.addAll(bridgeSupport.compileFlags());
            if (tls != null) targetFlags.addAll(tls.compileFlags());
            targetFlags.addAll(gc.compileFlags());

            // Resolve the runtime's target before even assembling the program:
            // assembly also assigns implicit load/store alignment from the layout.
            Path targetSource = temporaryDirectory.resolve("target.c");
            Path targetLlvm = temporaryDirectory.resolve("target.ll");
            Files.writeString(targetSource, "", StandardCharsets.UTF_8);
            List<String> targetCommand = new java.util.ArrayList<>(List.of(
                    toolchain.clang().toString(), "-std=c11", "-S", "-emit-llvm", "-x", "c"));
            targetCommand.addAll(targetFlags);
            targetCommand.addAll(List.of(targetSource.toString(), "-o", targetLlvm.toString()));
            LinkResult targetQuery = run("native target discovery", targetCommand);
            if (!targetQuery.success()) return targetQuery;
            NativeTarget target = NativeTarget.fromLlvm(Files.readString(targetLlvm, StandardCharsets.UTF_8));
            if (!target.equals(gc.target())) return new LinkResult(false, "GC dependency target/layout mismatch");
            targetFlags.add("--target=" + target.triple());
            Path targetedLlvm = temporaryDirectory.resolve("program.target.ll");
            Files.writeString(targetedLlvm, target.applyTo(Files.readString(llvmIr, StandardCharsets.UTF_8)
                            + "\n@iw_gc_expected_configuration = hidden constant [65 x i8] c\"" + gc.identity() + "\\00\"\n"),
                    StandardCharsets.UTF_8);
            LinkResult assemble = run("LLVM IR assembly", List.of(
                    toolchain.llvmAs().toString(), targetedLlvm.toString(), "-o", assembledBitcode.toString()));
            if (!assemble.success()) {
                return assemble;
            }
            List<String> optimizeCommand = new java.util.ArrayList<>(List.of(
                    toolchain.opt().toString(), "-passes=" + optimizationLevel.optPassPipeline()));
            optimizeCommand.addAll(optimizationLevel.optExtraArguments(inlineThreshold, partialInlining));
            optimizeCommand.addAll(targetMachine.llvmArguments());
            List<String> tuning = targetMachine == TargetMachine.DEFAULT
                    ? portableTuning(target.triple()) : List.of();
            optimizeCommand.addAll(tuning);
            if (optimizationReport != null) {
                optimizeCommand.addAll(List.of("-pass-remarks-output="
                        + optimizationReport.toAbsolutePath().normalize(), "-pass-remarks-format=yaml",
                        "-remarks-section=false"));
            }
            optimizeCommand.addAll(List.of("-S", assembledBitcode.toString(),
                    "-o", optimizedLlvm.toString()));
            LinkResult optimize = run("LLVM optimization", optimizeCommand);
            if (!optimize.success()) {
                return optimize;
            }
            try {
                Files.writeString(tracedLlvm, OptimizedTraceMetadata.injectGc(
                        GcLateVerification.optimizedStrategies(Files.readString(llvmIr),
                                Files.readString(optimizedLlvm, StandardCharsets.UTF_8)),
                        System.getProperty("os.name").startsWith("Mac")), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException exception) {
                return new LinkResult(false, "cannot finalize stack-trace metadata: "
                        + exception.getMessage());
            }
            Path plugin = temporaryDirectory.resolve("nonmoving-statepoints.so");
            var passBuild = new java.util.ArrayList<>(List.of(toolchain.clang().toString(), "--driver-mode=g++"));
            if (System.getProperty("os.name").equals("Linux")) passBuild.add("--gcc-toolchain=/usr");
            passBuild.addAll(List.of("-std=c++17", "-fno-exceptions", "-fPIC", "-shared", "-O2",
                    "-I" + toolchain.home().resolve("include"), "-L" + toolchain.home().resolve("lib"),
                    "-Wl,-rpath," + toolchain.home().resolve("lib"), "-lLLVM",
                    distribution.resolve("compiler/src/main/cpp/GcNonmovingStatepoints.cpp").toString(), "-o", plugin.toString()));
            LinkResult pass = run("required nonmoving statepoint pass build", passBuild);
            if (!pass.success()) return pass;
            Path rooted = temporaryDirectory.resolve("program.roots.ll");
            LinkResult rewrite = run("required nonmoving statepoints and final verification", List.of(toolchain.opt().toString(),
                    "-load-pass-plugin=" + plugin, "-passes=ironwood-nonmoving-statepoints,verify", "-S",
                    tracedLlvm.toString(), "-o", rooted.toString()));
            if (!rewrite.success()) return rewrite;
            GcLateVerification.stackRegions(Files.readString(rooted));
            LinkResult assembleTraces = run("verified statepoint assembly", List.of(toolchain.llvmAs().toString(),
                    rooted.toString(), "-o", optimizedBitcode.toString()));
            if (!assembleTraces.success()) return assembleTraces;
            List<String> codeCommand = new java.util.ArrayList<>(List.of(toolchain.llc().toString(),
                    "-filetype=obj", "--relocation-model=pic", optimizationLevel.llcArgument()));
            codeCommand.addAll(targetMachine.llvmArguments());
            codeCommand.addAll(tuning);
            codeCommand.addAll(List.of(optimizedBitcode.toString(), "-o", objectFile.toString()));
            LinkResult codeGeneration = run("LLVM object generation", codeCommand);
            if (!codeGeneration.success()) {
                return codeGeneration;
            }
            Files.write(objectFile, GcRootSections.retain(Files.readAllBytes(objectFile)));
            Path contract = temporaryDirectory.resolve("root-contract.c");
            Files.writeString(contract, GcRootSections.contract(Files.readAllBytes(objectFile)));
            Path contractObject = temporaryDirectory.resolve("root-contract.o");
            var contractCommand = new java.util.ArrayList<>(List.of(toolchain.clang().toString(), "-std=c11",
                    optimizationLevel.clangArgument(), "-fvisibility=hidden", "-c", contract.toString(), "-o", contractObject.toString()));
            contractCommand.addAll(targetFlags);
            LinkResult contractBuild = run("verified root contract compilation", contractCommand);
            if (!contractBuild.success()) return contractBuild;
            Path rootObject = temporaryDirectory.resolve("ironwood_gc_roots.o");
            var rootFlags = new java.util.ArrayList<>(targetFlags);
            if (!foreignExports.isEmpty() && Boolean.getBoolean("ironwood.gc.rootDiagnostics")) rootFlags.add("-DIW_GC_ROOT_DIAGNOSTICS");
            if (!foreignExports.isEmpty() && Boolean.getBoolean("ironwood.gc.rootFailureTests")) rootFlags.add("-DIW_GC_ROOT_FAILURE_TESTS");
            LinkResult rootBuild = prepareRuntimeObject(toolchain, distribution.resolve("runtime/src/ironwood_gc_roots.c"),
                    optimizationLevel, rootObject, rootFlags, gc.identity());
            if (!rootBuild.success()) return rootBuild;
            if (System.getProperty("os.name").equals("Linux")) {
                LinkResult rename = run("retained GC root section naming", List.of(toolchain.llvmObjcopy().toString(),
                        "--rename-section", ".llvm_stackmaps=ironwood_gc_maps", objectFile.toString()));
                if (!rename.success()) return rename;
            }
            if (System.getProperty("os.name").startsWith("Linux")) {
                LinkResult traceSection = run("LLVM stack-trace section preparation", List.of(
                        toolchain.llvmObjcopy().toString(), "--rename-section",
                        ".pseudo_probe=ironwood_trace,alloc,load,readonly,data,contents",
                        objectFile.toString()));
                if (!traceSection.success()) {
                    return traceSection;
                }
            }
            LinkResult runtimeCompilation = prepareRuntimeObject(toolchain,
                    runtime.source().orElseThrow(), optimizationLevel, runtimeObjectFile, targetFlags, gc.identity());
            if (!runtimeCompilation.success()) {
                return runtimeCompilation;
            }
            LinkResult caseCompilation = prepareRuntimeObject(toolchain,
                    runtime.source().orElseThrow().resolveSibling("ironwood_case.c"),
                    optimizationLevel, caseObjectFile, targetFlags, gc.identity());
            if (!caseCompilation.success()) return caseCompilation;
            LinkResult tcpCompilation = prepareRuntimeObject(toolchain,
                    runtime.source().orElseThrow().resolveSibling("ironwood_tcp.c"),
                    optimizationLevel, tcpObjectFile, targetFlags, gc.identity());
            if (!tcpCompilation.success()) return tcpCompilation;
            LinkResult hostCompilation = prepareRuntimeObject(toolchain,
                    runtime.source().orElseThrow().resolveSibling("ironwood_host.c"),
                    optimizationLevel, hostObjectFile, targetFlags, gc.identity());
            if (!hostCompilation.success()) return hostCompilation;
            List<String> linkCommand = new java.util.ArrayList<>(List.of(
                    toolchain.clang().toString(), "--driver-mode=g++", "--target=" + target.triple(),
                    objectFile.toString(), runtimeObjectFile.toString(), caseObjectFile.toString(),
                    tcpObjectFile.toString(), hostObjectFile.toString()));
            linkCommand.add(contractObject.toString());
            linkCommand.add(rootObject.toString());
            linkCommand.addAll(gc.linkFlags().stream().filter(flag -> (!shared || !flag.equals("-pie"))
                    && (bridgeSupport == null || !flag.equals("-L" + toolchain.home().resolve("lib"))
                        && !flag.equals("-Wl,-rpath," + toolchain.home().resolve("lib")))).toList());
            if (System.getProperty("os.name").equals("Linux")) {
                Path script = temporaryDirectory.resolve("root-contract.ld");
                Files.writeString(script, GcRootSections.elfLinkerScript());
                linkCommand.add("-Wl,-T," + script);
            }
            if (macTools != null) linkCommand.addAll(macTools.linkFlags());
            if (shared) linkCommand.add(System.getProperty("os.name").startsWith("Mac") ? "-dynamiclib" : "-shared");
            if (shared && System.getProperty("os.name").startsWith("Mac")) {
                // The linker otherwise embeds the temporary producer path in LC_ID_DYLIB.
                linkCommand.addAll(List.of("-Xlinker", "-install_name", "-Xlinker", "@rpath/" + output.getFileName()));
            }
            if (shared && System.getProperty("os.name").startsWith("Linux")) linkCommand.add("-Wl,-z,now");
            if (!foreignExports.isEmpty()) {
                Path policy = temporaryDirectory.resolve("native-exports");
                if (System.getProperty("os.name").startsWith("Mac")) {
                    Files.writeString(policy, foreignExports.stream().map(s -> "_" + s + "\n").collect(java.util.stream.Collectors.joining()));
                    linkCommand.addAll(List.of("-Wl,-exported_symbols_list," + policy, "-Wl,-twolevel_namespace"));
                } else {
                    Files.writeString(policy, "{ global: " + String.join("; ", foreignExports) + "; local: *; };\n");
                    linkCommand.addAll(List.of("-Wl,--version-script=" + policy,
                            "-Wl,-Bsymbolic,--exclude-libs,ALL,-z,defs,-z,relro,-z,start-stop-visibility=hidden"));
                }
            }
            if (bridgeSupport != null) linkCommand.addAll(bridgeSupport.linkFlags());
            adapterObjects.forEach(object -> linkCommand.add(object.toString()));
            if (tls != null) {
                Path tlsObject = temporaryDirectory.resolve("ironwood_tls.o");
                List<String> tlsFlags = new java.util.ArrayList<>(targetFlags);
                tlsFlags.addAll(List.of("-I", tls.home().resolve("include").toString(),
                        "-I", tls.home().resolve("share").toString()));
                LinkResult compiled = prepareRuntimeObject(toolchain,
                        runtime.source().orElseThrow().resolveSibling("ironwood_tls.c"),
                        optimizationLevel, tlsObject, tlsFlags, tls.identity() + gc.identity());
                if (!compiled.success()) return compiled;
                linkCommand.add(tlsObject.toString());
                linkCommand.addAll(tls.linkFlags());
            }
            linkCommand.addAll(List.of(System.getProperty("os.name").startsWith("Mac")
                    ? "-Wl,-dead_strip" : "-Wl,--gc-sections", "-o", output.toString()));
            LinkResult linked = run("native link", linkCommand);
            if (linked.success() && !foreignExports.isEmpty()) {
                var audit = auditForeignImage(toolchain, output, foreignExports);
                if (!audit.success()) return audit;
            }
            if (linked.success() && bridgeSupport != null) bridgeSupport.deliver(output);
            return linked;
        } catch (IOException | IllegalArgumentException exception) {
            return new LinkResult(false, "cannot prepare native backend: " + exception.getMessage());
        } finally {
            deleteTree(temporaryDirectory);
        }
    }

    private static LinkResult auditForeignImage(LlvmToolchain toolchain, Path image, List<String> expected) {
        boolean mac = System.getProperty("os.name").startsWith("Mac");
        Path tools = toolchain.home().resolve("bin");
        var table = run("native export audit", mac
                ? List.of(tools.resolve("llvm-objdump").toString(), "--macho", "--exports-trie", image.toString())
                : List.of(tools.resolve("llvm-nm").toString(), "--dynamic", "--defined-only", "--extern-only", image.toString()));
        if (!table.success()) return table;
        var observed = new java.util.ArrayList<String>();
        for (String line : table.output().lines().toList()) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || mac && !trimmed.matches("0x[0-9A-Fa-f]+\\s+.*")) continue;
            var fields = trimmed.split("\\s+");
            observed.add(fields[fields.length - 1]);
        }
        var wanted = expected.stream().map(name -> mac ? "_" + name : name).sorted().toList();
        if (!observed.stream().sorted().toList().equals(wanted)) return new LinkResult(false, "native image export policy mismatch: " + observed);
        var arguments = new java.util.ArrayList<>(List.of(tools.resolve("llvm-nm").toString()));
        if (!mac) arguments.add("--dynamic");
        arguments.addAll(List.of("--undefined-only", image.toString()));
        var imports = run("native collector import audit", arguments);
        if (!imports.success()) return imports;
        if (java.util.regex.Pattern.compile("\\b_?(?:GC_|iw_gc_)\\w+").matcher(imports.output()).find())
            return new LinkResult(false, "native image imports a collector or root adapter");
        return new LinkResult(true, "");
    }

    /**
     * Tuning for portable images on the default target machine, executables and
     * shared images alike; it selects no instruction-set extension. LLVM's
     * baseline x86-64 model assumes slow unaligned 16-byte memory access, which
     * holds only for processors older than SSE4.2/SSE4A. It then zeroes and copies
     * adjacent fields one word at a time. Portable images keep the baseline SSE2
     * instruction set and use its unaligned 16-byte moves. {@code -march=native}
     * takes the host processor's own tuning instead, and other targets are unchanged.
     */
    public static List<String> portableTuning(String triple) {
        return triple.startsWith("x86_64-") ? List.of("-mattr=-slow-unaligned-mem-16") : List.of();
    }

    private static synchronized LinkResult prepareRuntimeObject(LlvmToolchain toolchain,
                                                                 Path runtimeSource,
                                                                 OptimizationLevel optimizationLevel,
                                                                 Path output, List<String> extraArguments,
                                                                 String dependencyIdentity) throws IOException {
        Path clang = toolchain.clang().toAbsolutePath().normalize();
        StringBuilder headers = new StringBuilder();
        Path runtimeRoot = runtimeSource.getParent().getParent();
        try (var files = Files.walk(runtimeRoot)) {
            for (Path header : files.filter(path -> path.toString().endsWith(".h")).sorted().toList()) {
                headers.append(header).append(TlsDependency.sha256(header));
            }
        }
        RuntimeObjectKey key = new RuntimeObjectKey(clang, Files.size(clang),
                Files.getLastModifiedTime(clang).toMillis(), runtimeSource.toAbsolutePath().normalize(),
                Files.readString(runtimeSource, StandardCharsets.UTF_8), headers.toString(),
                List.copyOf(extraArguments), dependencyIdentity,
                String.valueOf(System.getenv("SDKROOT")) + System.getenv("DEVELOPER_DIR"), optimizationLevel);
        byte[] cached = RUNTIME_OBJECTS.get(key);
        if (cached != null) {
            Files.write(output, cached);
            return new LinkResult(true, "");
        }

        List<String> command = new java.util.ArrayList<>(List.of(clang.toString(), "-std=c11", "-fPIC",
                optimizationLevel.clangArgument(), "-ffunction-sections", "-fdata-sections"));
        command.addAll(extraArguments);
        command.addAll(List.of("-c", runtimeSource.toString(), "-o", output.toString()));
        LinkResult compilation = run("bootstrap runtime compilation", command);
        if (compilation.success()) {
            RUNTIME_OBJECTS.put(key, Files.readAllBytes(output));
            if (RUNTIME_OBJECTS.size() > MAX_CACHED_RUNTIME_OBJECTS) {
                RUNTIME_OBJECTS.remove(RUNTIME_OBJECTS.keySet().iterator().next());
            }
        }
        return compilation;
    }

    private static LinkResult run(String stage, List<String> command) {
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            String toolOutput = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                return new LinkResult(true, toolOutput.strip());
            }
            return new LinkResult(false, stage + " failed with exit code " + exitCode
                    + (toolOutput.isBlank() ? "" : ":\n" + toolOutput.strip()));
        } catch (IOException exception) {
            return new LinkResult(false, "cannot run " + stage + ": " + exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new LinkResult(false, stage + " interrupted");
        }
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Temporary backend files must not hide the compilation result.
                }
            });
        } catch (IOException ignored) {
            // Temporary backend files must not hide the compilation result.
        }
    }

    private record RuntimeObjectKey(Path clang, long clangSize, long clangModified,
                                    Path runtimeSource, String runtimeSourceContents,
                                    String headerIdentity, List<String> arguments,
                                    String dependencyIdentity, String sdkEnvironment,
                                    OptimizationLevel optimizationLevel) {
    }
}
