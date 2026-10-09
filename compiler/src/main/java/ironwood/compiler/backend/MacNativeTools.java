// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.backend;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Build-time Apple SDK/linker selection; LLVM still owns compilation and optimization. */
public record MacNativeTools(Path sdk, Path linker, String sdkVersion, String linkerVersion) {
    /** The fixed Apple tool selector; a PATH-selected substitute is never launched. */
    static final String XCRUN = "/usr/bin/xcrun";

    public static MacNativeTools discover() throws IOException {
        String override = System.getenv("SDKROOT");
        if (override != null && override.isBlank()) throw failure("SDKROOT is empty");
        Path sdk = Path.of(override == null
                ? run(List.of(XCRUN, "--sdk", "macosx", "--show-sdk-path")) : override);
        Path linker = Path.of(run(List.of(XCRUN, "--sdk", "macosx", "--find", "ld")));
        return validate(sdk, linker);
    }

    static MacNativeTools validate(Path sdk, Path linker) throws IOException {
        sdk = sdk.toAbsolutePath().normalize();
        linker = linker.toAbsolutePath().normalize();
        for (String name : List.of("SDKSettings.plist", "usr/lib/libSystem.tbd", "usr/lib/libc++.tbd")) {
            if (!Files.isReadable(sdk.resolve(name))) throw failure("selected SDK is missing " + sdk.resolve(name));
        }
        if (!Files.isRegularFile(linker) || !Files.isExecutable(linker)) {
            throw failure("selected Apple linker is missing or not executable: " + linker);
        }
        String version = run(List.of("/usr/bin/plutil", "-extract", "Version", "raw", "-o", "-",
                sdk.resolve("SDKSettings.plist").toString()));
        return new MacNativeTools(sdk, linker, version, run(List.of(linker.toString(), "-v")).lines().findFirst().orElseThrow());
    }

    public List<String> compileFlags() { return List.of("-isysroot", sdk.toString()); }

    public List<String> linkFlags() { return List.of("-isysroot", sdk.toString(), "--ld-path=" + linker); }

    public Map<String, String> identity() throws IOException {
        return Map.of("sdk.version", sdkVersion, "sdk.settings.sha256", TlsDependency.sha256(sdk.resolve("SDKSettings.plist")),
                "sdk.libSystem.sha256", TlsDependency.sha256(sdk.resolve("usr/lib/libSystem.tbd")),
                "sdk.libcxx.sha256", TlsDependency.sha256(sdk.resolve("usr/lib/libc++.tbd")),
                "linker.version", linkerVersion, "linker.sha256", TlsDependency.sha256(linker));
    }

    private static String run(List<String> command) throws IOException {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            if (process.waitFor() != 0 || output.isEmpty()) {
                throw failure("cannot run " + command.get(0) + ": " + output);
            }
            return output;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw failure("Apple tool discovery interrupted");
        } catch (IOException error) {
            throw failure(error.getMessage());
        }
    }

    private static IOException failure(String detail) {
        return new IOException("macOS native tooling: " + detail
                + "; select an installed Apple Command Line Tools or Xcode environment with xcode-select or DEVELOPER_DIR");
    }
}
