// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.backend;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

/** Small synthetic SDKs exercise integrity and publication, without native tools or downloads. */
public final class BridgeNativeSupportTests {
    private BridgeNativeSupportTests() {}

    public static void integrity() throws Exception {
        Path directory = Files.createTempDirectory("bridge support integrity ");
        try {
            Path home = directory.resolve("sdk");
            var names = List.of("lib/libgcc_s.so.1", "lib/libstdc++.so.6", "lib/libgcc_s.so", "lib/libstdc++.so",
                    "sources/gcc-16.2.0.tar.gz", "sources/zlib-1.3.1.tar.gz", "licenses/GPL-3.0.txt",
                    "licenses/GCC-exception-3.1.txt", "recipes/libgcc/info/recipe/meta.yaml", "recipes/libstdcxx/info/recipe/meta.yaml");
            var pins = new Properties();
            pins.setProperty("llvm.version", "23.1.0");
            pins.setProperty("linux-arm64.sysroot", "sysroot");
            for (var name : names) {
                Path path = home.resolve(name);
                Files.createDirectories(path.getParent());
                Files.writeString(path, "fixture " + name);
            }
            pins.setProperty("linux-arm64.libgcc.file.sha256", TlsDependency.sha256(home.resolve(names.get(0))));
            pins.setProperty("linux-arm64.libstdcxx.file.sha256", TlsDependency.sha256(home.resolve(names.get(1))));
            pins.setProperty("gcc.source.sha256", TlsDependency.sha256(home.resolve(names.get(4))));
            pins.setProperty("zlib.source.sha256", TlsDependency.sha256(home.resolve(names.get(5))));
            Path pinsPath = directory.resolve("pins.properties");
            write(pinsPath, pins);
            write(home.resolve("dependencies.properties"), pins);
            var manifest = new Properties();
            manifest.setProperty("format", "1");
            manifest.setProperty("platform", "linux-arm64");
            manifest.setProperty("pins.sha256", TlsDependency.sha256(pinsPath));
            for (var name : names) manifest.setProperty("sha256." + name, TlsDependency.sha256(home.resolve(name)));
            manifest.setProperty("sha256.dependencies.properties", TlsDependency.sha256(home.resolve("dependencies.properties")));
            write(home.resolve("build.properties"), manifest);
            Path toolchainHome = directory.resolve("llvm");
            Path features = toolchainHome.resolve("sysroot/usr/include/features.h");
            Files.createDirectories(features.getParent());
            Files.writeString(features, "#define __GLIBC__ 2\n#define __GLIBC_MINOR__ 17\n");
            var toolchain = new LlvmToolchain(toolchainHome, null, null, null, null, null, null, "23.1.0");
            var support = BridgeNativeSupport.validate(pinsPath, home, toolchain, "aarch64");
            check(support.linkFlags().contains("$ORIGIN/" + support.directory() + "/lib"), "relative path lost");
            expectFailure(() -> BridgeNativeSupport.validate(pinsPath, home, toolchain, "amd64"), "wrong target");
            for (String name : List.of(names.get(0), names.get(4))) {
                Path file = home.resolve(name);
                String original = Files.readString(file);
                String hash = manifest.getProperty("sha256." + name);
                Files.writeString(file, "changed");
                expectFailure(() -> BridgeNativeSupport.validate(pinsPath, home, toolchain, "aarch64"), "checksum mismatch");
                manifest.setProperty("sha256." + name, TlsDependency.sha256(file));
                write(home.resolve("build.properties"), manifest);
                expectFailure(() -> BridgeNativeSupport.validate(pinsPath, home, toolchain, "aarch64"), "pinned support checksum");
                Files.writeString(file, original);
                manifest.setProperty("sha256." + name, hash);
                write(home.resolve("build.properties"), manifest);
            }
            String licenseKey = "sha256.licenses/GPL-3.0.txt";
            String licenseHash = (String) manifest.remove(licenseKey);
            write(home.resolve("build.properties"), manifest);
            expectFailure(() -> BridgeNativeSupport.validate(pinsPath, home, toolchain, "aarch64"), "missing support input");
            manifest.setProperty(licenseKey, licenseHash);
            write(home.resolve("build.properties"), manifest);
            Files.writeString(features, "#define __GLIBC__ 2\n#define __GLIBC_MINOR__ 39\n");
            expectFailure(() -> BridgeNativeSupport.validate(pinsPath, home, toolchain, "aarch64"), "glibc 2.17");
            Files.writeString(features, "#define __GLIBC__ 2\n#define __GLIBC_MINOR__ 17\n");
            support = BridgeNativeSupport.validate(pinsPath, home, toolchain, "aarch64");
            Path output = directory.resolve("output/image.so");
            Files.createDirectories(output.getParent());
            support.deliver(output);
            support.deliver(output);
            Path delivered = output.getParent().resolve(support.directory());
            for (String name : names) check(Files.readString(delivered.resolve(name)).equals(Files.readString(home.resolve(name))), "delivery changed " + name);
            Path changed = delivered.resolve(names.get(0));
            Files.writeString(changed, "preserve existing change");
            var validated = support;
            expectFailure(() -> validated.deliver(output), "checksum mismatch");
            check(Files.readString(changed).equals("preserve existing change"), "delivery overwrote existing content");
            try (var paths = Files.list(output.getParent())) {
                check(paths.noneMatch(path -> path.getFileName().toString().startsWith(".ironwood-support-staging-")), "staging directory leaked");
            }
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void write(Path path, Properties properties) throws IOException {
        Files.writeString(path, String.join("\n", properties.stringPropertyNames().stream().sorted()
                .map(key -> key + "=" + properties.getProperty(key)).toList()) + "\n");
    }

    private static void expectFailure(Checked action, String message) throws Exception {
        try { action.run(); throw new AssertionError("expected refusal: " + message); }
        catch (IOException expected) { check(expected.getMessage().contains(message), expected.toString()); }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    @FunctionalInterface
    private interface Checked { void run() throws Exception; }
}
