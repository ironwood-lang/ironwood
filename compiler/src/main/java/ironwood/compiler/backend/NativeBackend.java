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

            RuntimeLibrary.Discovery runtime = RuntimeLibrary.discover();
            if (!runtime.successful()) {
                return new LinkResult(false, runtime.error());
            }

            LinkResult assemble = run("LLVM IR assembly", List.of(
                    toolchain.llvmAs().toString(), llvmIr.toString(), "-o", assembledBitcode.toString()));
            if (!assemble.success()) {
                return assemble;
            }
            List<String> optimizeCommand = new java.util.ArrayList<>(List.of(
                    toolchain.opt().toString(), "-passes=" + optimizationLevel.optPassPipeline()));
            optimizeCommand.addAll(optimizationLevel.optExtraArguments());
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
            LinkResult codeGeneration = run("LLVM object generation", List.of(
                    toolchain.llc().toString(), "-filetype=obj", "--relocation-model=pic",
                    optimizationLevel.llcArgument(),
                    optimizedBitcode.toString(),
                    "-o", objectFile.toString()));
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
                    runtime.source().orElseThrow(), optimizationLevel, runtimeObjectFile);
            if (!runtimeCompilation.success()) {
                return runtimeCompilation;
            }
            LinkResult caseCompilation = prepareRuntimeObject(toolchain,
                    runtime.source().orElseThrow().resolveSibling("ironwood_case.c"),
                    optimizationLevel, caseObjectFile);
            if (!caseCompilation.success()) return caseCompilation;
            return run("native link", List.of(
                    toolchain.clang().toString(), "--driver-mode=g++",
                    objectFile.toString(), runtimeObjectFile.toString(), caseObjectFile.toString(),
                    System.getProperty("os.name").startsWith("Mac") ? "-Wl,-dead_strip" : "-Wl,--gc-sections",
                    "-o", output.toString()));
        } catch (IOException exception) {
            return new LinkResult(false, "cannot prepare native backend: " + exception.getMessage());
        } finally {
            deleteTree(temporaryDirectory);
        }
    }

    private static synchronized LinkResult prepareRuntimeObject(LlvmToolchain toolchain,
                                                                 Path runtimeSource,
                                                                 OptimizationLevel optimizationLevel,
                                                                 Path output) throws IOException {
        Path clang = toolchain.clang().toAbsolutePath().normalize();
        Path runtimeHeader = runtimeSource.getParent().getParent()
                .resolve("include/ironwood_runtime.h").toAbsolutePath().normalize();
        String caseData = Files.readString(runtimeSource.resolveSibling("ironwood_case_data.h"), StandardCharsets.UTF_8)
                + Files.readString(runtimeHeader.resolveSibling("ironwood_case.h"), StandardCharsets.UTF_8);
        RuntimeObjectKey key = new RuntimeObjectKey(
                clang,
                Files.size(clang),
                Files.getLastModifiedTime(clang).toMillis(),
                runtimeSource.toAbsolutePath().normalize(),
                Files.readString(runtimeSource, StandardCharsets.UTF_8),
                runtimeHeader,
                Files.readString(runtimeHeader, StandardCharsets.UTF_8) + caseData,
                optimizationLevel);
        byte[] cached = RUNTIME_OBJECTS.get(key);
        if (cached != null) {
            Files.write(output, cached);
            return new LinkResult(true, "");
        }

        LinkResult compilation = run("bootstrap runtime compilation", List.of(
                clang.toString(), "-std=c11", "-fPIC", optimizationLevel.clangArgument(),
                "-ffunction-sections", "-fdata-sections",
                "-c", runtimeSource.toString(), "-o", output.toString()));
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
                                    Path runtimeHeader, String runtimeHeaderContents,
                                    OptimizationLevel optimizationLevel) {
    }
}
