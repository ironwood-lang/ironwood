// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.backend;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class MacNativeToolsTests {
    public static final String NAME = "macOS native SDK and Apple linker selection preserves explicit inputs";
    private MacNativeToolsTests() {}

    public static void selection() throws Exception {
        if (!System.getProperty("os.name").startsWith("Mac")) return;
        MacNativeTools tools = MacNativeTools.discover();
        Path root = Files.createTempDirectory("ironwood-mac-tools-");
        try {
            reject(root.resolve("missing-sdk"), tools.linker(), "selected SDK is missing");
            reject(tools.sdk(), root.resolve("missing-linker"), "selected Apple linker is missing");
            Path sdk = root.resolve("sdk with spaces");
            Files.createDirectories(sdk.resolve("usr/lib"));
            for (String name : List.of("SDKSettings.plist", "usr/lib/libSystem.tbd", "usr/lib/libc++.tbd")) {
                Files.createSymbolicLink(sdk.resolve(name), tools.sdk().resolve(name));
            }
            MacNativeTools explicit = MacNativeTools.validate(sdk, tools.linker());
            if (!explicit.sdk().equals(sdk) || !explicit.sdkVersion().equals(tools.sdkVersion())
                    || !explicit.identity().equals(tools.identity())) {
                throw new AssertionError("explicit SDK selection or content identity changed");
            }
            Path source = root.resolve("probe.c"), image = root.resolve("probe");
            Files.writeString(source, "int main(void) { return 42; }\n");
            LlvmToolchain llvm = LlvmToolchain.discover(null).toolchain().orElseThrow();
            var command = new java.util.ArrayList<>(List.of(llvm.clang().toString()));
            command.addAll(explicit.linkFlags());
            command.addAll(List.of(source.toString(), "-o", image.toString()));
            Process compiler = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(compiler.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            if (compiler.waitFor() != 0) throw new AssertionError("selected SDK/linker failed: " + output);
            if (new ProcessBuilder(image.toString()).start().waitFor() != 42) throw new AssertionError("native probe failed");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void reject(Path sdk, Path linker, String message) throws Exception {
        try {
            MacNativeTools.validate(sdk, linker);
            throw new AssertionError("missing native component accepted");
        } catch (IOException expected) {
            if (!expected.getMessage().contains(message)) throw new AssertionError(expected.getMessage());
        }
    }
}
