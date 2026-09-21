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
            List<String> targetFlags = new java.util.ArrayList<>(targetMachine.clangArguments());
            if (tls != null) targetFlags.addAll(tls.compileFlags());

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
            targetFlags.add("--target=" + target.triple());
            Path targetedLlvm = temporaryDirectory.resolve("program.target.ll");
            Files.writeString(targetedLlvm, target.applyTo(Files.readString(llvmIr, StandardCharsets.UTF_8)),
                    StandardCharsets.UTF_8);
            LinkResult assemble = run("LLVM IR assembly", List.of(
                    toolchain.llvmAs().toString(), targetedLlvm.toString(), "-o", assembledBitcode.toString()));
            if (!assemble.success()) {
                return assemble;
            }
            List<String> optimizeCommand = new java.util.ArrayList<>(List.of(
                    toolchain.opt().toString(), "-passes=" + optimizationLevel.optPassPipeline()));
            optimizeCommand.addAll(optimizationLevel.optExtraArguments());
            optimizeCommand.addAll(targetMachine.llvmArguments());
            optimizeCommand.addAll(List.of("-S", assembledBitcode.toString(),
                    "-o", optimizedLlvm.toString()));
            LinkResult optimize = run("LLVM optimization", optimizeCommand);
            if (!optimize.success()) {
                return optimize;
            }
            try {
                Files.writeString(tracedLlvm, OptimizedTraceMetadata.inject(
                        Files.readString(optimizedLlvm, StandardCharsets.UTF_8),
                        System.getProperty("os.name").startsWith("Mac")), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException exception) {
                return new LinkResult(false, "cannot finalize stack-trace metadata: "
                        + exception.getMessage());
            }
            LinkResult assembleTraces = run("LLVM stack-trace metadata assembly", List.of(
                    toolchain.llvmAs().toString(), tracedLlvm.toString(),
                    "-o", optimizedBitcode.toString()));
            if (!assembleTraces.success()) {
                return assembleTraces;
            }
            List<String> codeCommand = new java.util.ArrayList<>(List.of(toolchain.llc().toString(),
                    "-filetype=obj", "--relocation-model=pic", optimizationLevel.llcArgument()));
            codeCommand.addAll(targetMachine.llvmArguments());
            codeCommand.addAll(List.of(optimizedBitcode.toString(), "-o", objectFile.toString()));
            LinkResult codeGeneration = run("LLVM object generation", codeCommand);
            if (!codeGeneration.success()) {
                return codeGeneration;
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
                    runtime.source().orElseThrow(), optimizationLevel, runtimeObjectFile, targetFlags, "");
            if (!runtimeCompilation.success()) {
                return runtimeCompilation;
            }
            LinkResult caseCompilation = prepareRuntimeObject(toolchain,
                    runtime.source().orElseThrow().resolveSibling("ironwood_case.c"),
                    optimizationLevel, caseObjectFile, targetFlags, "");
            if (!caseCompilation.success()) return caseCompilation;
            LinkResult tcpCompilation = prepareRuntimeObject(toolchain,
                    runtime.source().orElseThrow().resolveSibling("ironwood_tcp.c"),
                    optimizationLevel, tcpObjectFile, targetFlags, "");
            if (!tcpCompilation.success()) return tcpCompilation;
            LinkResult hostCompilation = prepareRuntimeObject(toolchain,
                    runtime.source().orElseThrow().resolveSibling("ironwood_host.c"),
                    optimizationLevel, hostObjectFile, targetFlags, "");
            if (!hostCompilation.success()) return hostCompilation;
            List<String> linkCommand = new java.util.ArrayList<>(List.of(
                    toolchain.clang().toString(), "--driver-mode=g++", "--target=" + target.triple(),
                    objectFile.toString(), runtimeObjectFile.toString(), caseObjectFile.toString(),
                    tcpObjectFile.toString(), hostObjectFile.toString()));
            if (tls != null) {
                Path tlsObject = temporaryDirectory.resolve("ironwood_tls.o");
                List<String> tlsFlags = new java.util.ArrayList<>(targetFlags);
                tlsFlags.addAll(List.of("-I", tls.home().resolve("include").toString(),
                        "-I", tls.home().resolve("share").toString()));
                LinkResult compiled = prepareRuntimeObject(toolchain,
                        runtime.source().orElseThrow().resolveSibling("ironwood_tls.c"),
                        optimizationLevel, tlsObject, tlsFlags, tls.identity());
                if (!compiled.success()) return compiled;
                linkCommand.add(tlsObject.toString());
                linkCommand.addAll(tls.linkFlags());
            }
            linkCommand.addAll(List.of(System.getProperty("os.name").startsWith("Mac")
                    ? "-Wl,-dead_strip" : "-Wl,--gc-sections", "-o", output.toString()));
            return run("native link", linkCommand);
        } catch (IOException exception) {
            return new LinkResult(false, "cannot prepare native backend: " + exception.getMessage());
        } finally {
            deleteTree(temporaryDirectory);
        }
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
