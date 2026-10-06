// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.backend;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * M4.3's caller adaptations in the Java seed (D273): launches use absolute
 * paths, so Homebrew is resolved by the compiler-owned ExecutableSearch and
 * the TLS SDK query runs the fixed /usr/bin/xcrun. Child JVMs run with
 * hostile PATHs; decoy tools record any launch.
 */
public final class ToolDiscoveryTests {
    private static final String REFERENCE = "docs/self-hosting/m4/driver-evidence/DiscoveryReference.java";

    private ToolDiscoveryTests() { }

    /** Candidate order, empty and relative entries, non-executables, directories and unnormalized paths. */
    public static void executableSearch() throws Exception {
        Path root = Files.createTempDirectory("ironwood-executable-search-").toRealPath();
        try {
            Path plain = tool(root.resolve("plain"), false);
            Files.createDirectories(root.resolve("directory/tool"));
            Path first = tool(root.resolve("first"), true);
            tool(root.resolve("second"), true);
            String path = String.join(":", List.of(plain.getParent().toString(), root.resolve("directory").toString(),
                    first.getParent().toString(), root.resolve("second").toString()));
            require(ExecutableSearch.find("tool", path, root).equals(Optional.of(first)), "first executable wins");
            require(ExecutableSearch.find("tool", "::" + root.resolve("second") + "::", root)
                    .equals(Optional.of(root.resolve("second/tool"))), "empty entries are skipped");
            // Empty entries never mean the working directory, even when it holds the tool.
            tool(root.resolve("here"), true);
            require(ExecutableSearch.find("tool", ":", root.resolve("here")).isEmpty(), "an empty entry searched");
            require(ExecutableSearch.find("tool", "second", root).equals(Optional.of(root.resolve("second/tool"))),
                    "a relative entry resolves against the working directory");
            // No lexical normalization: '..' after a link follows the link, as exec would.
            Path elsewhere = Files.createDirectories(root.resolve("elsewhere/inner"));
            tool(root.resolve("elsewhere/bin"), true);
            Files.createSymbolicLink(root.resolve("link"), elsewhere);
            Optional<Path> linked = ExecutableSearch.find("tool", "link/../bin", root);
            require(linked.equals(Optional.of(root.resolve("link/../bin/tool")))
                    && linked.orElseThrow().toRealPath().equals(root.resolve("elsewhere/bin/tool")), "normalized: " + linked);
            require(ExecutableSearch.find("tool", null, root).isEmpty() && ExecutableSearch.find("tool", "", root).isEmpty()
                    && ExecutableSearch.find("missing", path, root).isEmpty()
                    && ExecutableSearch.find("dir/tool", path, root).isEmpty(), "absent candidates");
        } finally {
            delete(root);
        }
    }

    /** Homebrew is launched by its resolved absolute path, and its absence leaves discovery optional. */
    public static void homebrew() throws Exception {
        Path root = Files.createTempDirectory("ironwood-homebrew-").toRealPath();
        try {
            Path marker = root.resolve("launched");
            Path decoy = Files.createDirectories(root.resolve("decoy"));
            Files.writeString(decoy.resolve("brew"), "not executable");
            Path fake = Files.createDirectories(root.resolve("fake"));
            Files.writeString(fake.resolve("brew"), "#!/bin/sh\necho \"$0 $*\" >> " + marker + "\n"
                    + "[ \"$2\" = llvm@" + LlvmToolchain.REQUIRED_MAJOR + " ] && echo /fake/prefix/llvm\n");
            Files.setPosixFilePermissions(fake.resolve("brew"), PosixFilePermissions.fromString("rwx------"));
            String found = reference("brew", Map.of("PATH", "::" + decoy + ":" + fake + ":/usr/bin:/bin"));
            require(found.equals("Optional[/fake/prefix/llvm]\n"), "Homebrew prefix " + found);
            require(Files.readString(marker).equals(fake.resolve("brew") + " --prefix llvm@"
                    + LlvmToolchain.REQUIRED_MAJOR + "\n"), "launch " + Files.readString(marker));
            String missing = reference("brew", Map.of("PATH", decoy + ":" + root.resolve("none")));
            require(missing.equals("Optional.empty\n"), "missing Homebrew " + missing);
        } finally {
            delete(root);
        }
    }

    /** TLS and Apple tool discovery run /usr/bin/xcrun even when PATH offers another xcrun. */
    public static void xcrun() throws Exception {
        if (!System.getProperty("os.name").startsWith("Mac")) return;
        Path root = Files.createTempDirectory("ironwood-xcrun-").toRealPath();
        try {
            Path marker = root.resolve("launched");
            Path fake = Files.createDirectories(root.resolve("fake"));
            Files.writeString(fake.resolve("xcrun"), "#!/bin/sh\ntouch " + marker + "\necho /fake/sdk\n");
            Files.setPosixFilePermissions(fake.resolve("xcrun"), PosixFilePermissions.fromString("rwx------"));
            Process real = new ProcessBuilder("/usr/bin/xcrun", "--show-sdk-path").start();
            String sdk = new String(real.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            require(real.waitFor() == 0, "xcrun failed");
            Map<String, String> hostile = Map.of("PATH", fake + ":/usr/bin:/bin");
            require(reference("sdk", hostile).equals(sdk + "\n"), "TLS SDK through PATH");
            require(reference("sdk", Map.of("PATH", fake.toString(), "SDKROOT", "/explicit/sdk"))
                    .equals("/explicit/sdk\n"), "SDKROOT is used as given");
            Process macosx = new ProcessBuilder("/usr/bin/xcrun", "--sdk", "macosx", "--show-sdk-path").start();
            String macSdk = Path.of(new String(macosx.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip())
                    .toAbsolutePath().normalize().toString();
            require(macosx.waitFor() == 0, "xcrun --sdk macosx failed");
            String mac = reference("mac", hostile);
            require(mac.startsWith(macSdk + "\n"), "Apple SDK through PATH: " + mac);
            require(!Files.exists(marker), "a PATH-selected xcrun ran");
        } finally {
            delete(root);
        }
    }

    private static Path tool(Path directory, boolean executable) throws Exception {
        Files.createDirectories(directory);
        Path tool = directory.resolve("tool");
        Files.writeString(tool, "#!/bin/sh\n");
        Files.setPosixFilePermissions(tool, PosixFilePermissions.fromString(executable ? "rwx------" : "rw-------"));
        return tool;
    }

    // The reference answers on stdout; stderr carries only JVM startup warnings.
    private static String reference(String mode, Map<String, String> environment) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        ProcessBuilder builder = new ProcessBuilder(java.toString(), "-cp",
                Path.of("compiler/build/classes").toAbsolutePath().toString(),
                Path.of(REFERENCE).toAbsolutePath().toString(), mode).redirectError(ProcessBuilder.Redirect.DISCARD);
        for (String variable : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "SDKROOT")) {
            builder.environment().remove(variable);
        }
        builder.environment().putAll(environment);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        require(process.waitFor(120, TimeUnit.SECONDS) && process.exitValue() == 0, "reference " + mode + ": " + output);
        return output;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void delete(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            List<Path> all = new ArrayList<>(paths.sorted(Comparator.reverseOrder()).toList());
            for (Path path : all) Files.delete(path);
        }
    }
}
