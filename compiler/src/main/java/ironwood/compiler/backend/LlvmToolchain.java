// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.backend;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public record LlvmToolchain(
        Path home,
        Path clang,
        Path llvmAs,
        Path opt,
        Path llc,
        Path llvmObjcopy,
        Path llvmConfig,
        String version
) {
    public static final int REQUIRED_MAJOR = 23;
    private static final List<String> REQUIRED_TOOLS = List.of(
            "clang", "llvm-as", "opt", "llc", "llvm-objcopy", "llvm-config");

    public static ToolchainDiscovery discover(Path explicitHome) {
        if (explicitHome != null) {
            return validate(explicitHome.toAbsolutePath().normalize(), "--llvm-home");
        }

        String environmentHome = System.getenv("IRONWOOD_LLVM_HOME");
        if (environmentHome != null && !environmentHome.isBlank()) {
            return validate(Path.of(environmentHome).toAbsolutePath().normalize(),
                    "IRONWOOD_LLVM_HOME");
        }

        Set<Path> candidates = new LinkedHashSet<>();
        discoverHomebrewPrefix().ifPresent(candidates::add);
        candidates.add(Path.of("/opt/homebrew/opt/llvm"));
        candidates.add(Path.of("/usr/local/opt/llvm"));
        candidates.add(Path.of("/usr/lib/llvm-" + REQUIRED_MAJOR));
        locateOnPath("llvm-config-" + REQUIRED_MAJOR).ifPresent(candidates::add);
        locateOnPath("llvm-config").ifPresent(candidates::add);

        List<String> failures = new ArrayList<>();
        for (Path candidate : candidates) {
            ToolchainDiscovery result = validate(candidate, candidate.toString());
            if (result.successful()) {
                return result;
            }
            if (Files.exists(candidate)) {
                failures.add(result.error());
            }
        }

        StringBuilder message = new StringBuilder()
                .append("LLVM ").append(REQUIRED_MAJOR).append(" toolchain not found. ")
                .append("Install LLVM ").append(REQUIRED_MAJOR)
                .append(" and set IRONWOOD_LLVM_HOME, or pass --llvm-home <directory>. ")
                .append("The directory must contain bin/{")
                .append(String.join(",", REQUIRED_TOOLS)).append("}.");
        if (!failures.isEmpty()) {
            message.append(" Checked installations: ").append(String.join("; ", failures));
        }
        return ToolchainDiscovery.notFound(message.toString());
    }

    private static ToolchainDiscovery validate(Path home, String source) {
        Path normalizedHome = home.toAbsolutePath().normalize();
        Path bin = normalizedHome.resolve("bin");
        List<String> missing = REQUIRED_TOOLS.stream()
                .filter(tool -> !Files.isExecutable(bin.resolve(tool)))
                .toList();
        if (!missing.isEmpty()) {
            return ToolchainDiscovery.notFound(source + " at '" + normalizedHome
                    + "' is missing executable tool(s): " + String.join(", ", missing));
        }

        Path llvmConfig = bin.resolve("llvm-config");
        CommandResult versionResult = run(List.of(llvmConfig.toString(), "--version"));
        if (!versionResult.successful()) {
            return ToolchainDiscovery.notFound("cannot query " + source + " at '" + normalizedHome
                    + "': " + versionResult.output());
        }
        String version = versionResult.output().strip();
        int major = parseMajor(version);
        if (major != REQUIRED_MAJOR) {
            return ToolchainDiscovery.notFound(source + " at '" + normalizedHome + "' is LLVM "
                    + version + "; Ironwood requires LLVM " + REQUIRED_MAJOR + ".x");
        }

        return ToolchainDiscovery.found(new LlvmToolchain(
                normalizedHome,
                bin.resolve("clang"),
                bin.resolve("llvm-as"),
                bin.resolve("opt"),
                bin.resolve("llc"),
                bin.resolve("llvm-objcopy"),
                llvmConfig,
                version));
    }

    private static Optional<Path> discoverHomebrewPrefix() {
        for (String formula : List.of("llvm@" + REQUIRED_MAJOR, "llvm")) {
            CommandResult result = run(List.of("brew", "--prefix", formula));
            if (result.successful() && !result.output().isBlank()) {
                return Optional.of(Path.of(result.output().strip()).toAbsolutePath().normalize());
            }
        }
        return Optional.empty();
    }

    private static Optional<Path> locateOnPath(String executable) {
        String pathValue = System.getenv("PATH");
        if (pathValue == null || pathValue.isBlank()) {
            return Optional.empty();
        }
        for (String entry : pathValue.split(java.io.File.pathSeparator)) {
            if (entry.isBlank()) {
                continue;
            }
            Path tool = Path.of(entry).resolve(executable);
            if (!Files.isExecutable(tool)) {
                continue;
            }
            try {
                Path realTool = tool.toRealPath();
                Path bin = realTool.getParent();
                if (bin != null && bin.getParent() != null) {
                    return Optional.of(bin.getParent());
                }
            } catch (IOException ignored) {
                // Other candidates can still provide a complete toolchain.
            }
        }
        return Optional.empty();
    }

    private static int parseMajor(String version) {
        int separator = version.indexOf('.');
        String major = separator < 0 ? version : version.substring(0, separator);
        try {
            return Integer.parseInt(major);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static CommandResult run(List<String> command) {
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = process.waitFor();
            return new CommandResult(exitCode == 0, output.strip());
        } catch (IOException exception) {
            return new CommandResult(false, exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new CommandResult(false, "toolchain discovery interrupted");
        }
    }

    private record CommandResult(boolean successful, String output) {
    }
}
