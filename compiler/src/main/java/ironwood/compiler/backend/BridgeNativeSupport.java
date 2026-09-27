// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.backend;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/** Pinned Linux shared-image support, including the source/notices that travel with it. */
record BridgeNativeSupport(Path home, Properties manifest, List<String> compileFlags, String directory) {
    static BridgeNativeSupport discover(LlvmToolchain toolchain) throws IOException {
        Path pinsPath = pinsPath();
        String override = System.getenv("IRONWOOD_BRIDGE_SUPPORT_HOME");
        if (override != null && override.isBlank()) throw failure("IRONWOOD_BRIDGE_SUPPORT_HOME is empty");
        Path home = override == null ? pinsPath.getParent().getParent().resolve("toolchain/ironwood-bridge-support")
                : Path.of(override).toAbsolutePath().normalize();
        return validate(pinsPath, home, toolchain, System.getProperty("os.arch"));
    }

    static BridgeNativeSupport validate(Path pinsPath, Path home, LlvmToolchain toolchain, String arch) throws IOException {
        Properties pins = read(pinsPath);
        Properties manifest = read(home.resolve("build.properties"));
        String target = switch (arch) {
            case "aarch64" -> "linux-arm64";
            case "amd64", "x86_64" -> "linux-x86_64";
            default -> throw failure("unsupported architecture " + arch);
        };
        if (!pins.equals(read(home.resolve("dependencies.properties")))
                || !"1".equals(manifest.getProperty("format"))
                || !target.equals(manifest.getProperty("platform"))
                || !TlsDependency.sha256(pinsPath).equals(manifest.getProperty("pins.sha256"))
                || !toolchain.version().equals(pins.getProperty("llvm.version"))) {
            throw failure("wrong target, pins, format or LLVM version at " + home);
        }
        for (String required : List.of("lib/libgcc_s.so.1", "lib/libstdc++.so.6", "lib/libgcc_s.so", "lib/libstdc++.so",
                "sources/gcc-16.2.0.tar.gz", "sources/zlib-1.3.1.tar.gz", "licenses/GPL-3.0.txt",
                "licenses/GCC-exception-3.1.txt", "recipes/libgcc/info/recipe/meta.yaml", "recipes/libstdcxx/info/recipe/meta.yaml")) {
            if (!manifest.containsKey("sha256." + required)) throw failure("missing support input " + required);
        }
        for (String key : manifest.stringPropertyNames()) {
            if (!key.startsWith("sha256.")) continue;
            Path relative = relative(key.substring(7));
            Path file = home.resolve(relative);
            if (!Files.isRegularFile(file) || !TlsDependency.sha256(file).equals(manifest.getProperty(key))) {
                throw failure("support checksum mismatch: " + file);
            }
        }
        requireHash(home.resolve("lib/libgcc_s.so.1"), pins.getProperty(target + ".libgcc.file.sha256"));
        requireHash(home.resolve("lib/libstdc++.so.6"), pins.getProperty(target + ".libstdcxx.file.sha256"));
        requireHash(home.resolve("sources/gcc-16.2.0.tar.gz"), pins.getProperty("gcc.source.sha256"));
        requireHash(home.resolve("sources/zlib-1.3.1.tar.gz"), pins.getProperty("zlib.source.sha256"));
        Path sysroot = toolchain.home().resolve(relative(pins.getProperty(target + ".sysroot")));
        String features = Files.readString(sysroot.resolve("usr/include/features.h"));
        if (!features.matches("(?s).*#define\\s+__GLIBC__\\s+2\\s.*")
                || !features.matches("(?s).*#define\\s+__GLIBC_MINOR__\\s+17\\s.*")) {
            throw failure("matching glibc 2.17 sysroot missing: " + sysroot);
        }
        // The prepared Clang config adds a build-prefix RPATH. Preserve its pinned
        // sysroot/GCC selection explicitly while disabling that loader-path injection.
        List<String> flags = List.of("--no-default-config", "--sysroot=" + sysroot,
                "--gcc-toolchain=" + toolchain.home());
        return new BridgeNativeSupport(home, manifest, flags,
                ".ironwood-bridge-support-" + TlsDependency.sha256(home.resolve("build.properties")));
    }

    List<String> linkFlags() {
        var result = new ArrayList<>(compileFlags);
        result.addAll(List.of("-L" + home.resolve("lib"), "-Xlinker", "-rpath", "-Xlinker", "$ORIGIN/" + directory + "/lib"));
        return List.copyOf(result);
    }

    void deliver(Path image) throws IOException {
        Path destination = image.toAbsolutePath().normalize().getParent().resolve(directory);
        if (Files.exists(destination)) {
            verifyDelivery(destination);
            return;
        }
        Path staged = Files.createTempDirectory(destination.getParent(), ".ironwood-support-staging-");
        try {
            // Include source/recipes and notices before publication, so interrupted
            // copying cannot leave a seemingly complete runtime-only directory.
            for (String key : manifest.stringPropertyNames().stream().sorted().toList()) {
                if (!key.startsWith("sha256.")) continue;
                Path relative = relative(key.substring(7));
                Path output = staged.resolve(relative);
                Files.createDirectories(output.getParent());
                Files.copy(home.resolve(relative), output);
                requireHash(output, manifest.getProperty(key));
            }
            Files.copy(home.resolve("build.properties"), staged.resolve("build.properties"));
            try {
                Files.move(staged, destination);
            } catch (java.nio.file.FileAlreadyExistsException competingBuild) {
                verifyDelivery(destination);
            }
        } finally {
            if (Files.exists(staged)) {
                try (var paths = Files.walk(staged)) {
                    for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
                }
            }
        }
    }

    private void verifyDelivery(Path destination) throws IOException {
        if (Files.isSymbolicLink(destination)) throw failure("support destination is a symbolic link: " + destination);
        requireHash(destination.resolve("build.properties"), TlsDependency.sha256(home.resolve("build.properties")));
        for (String key : manifest.stringPropertyNames()) {
            if (key.startsWith("sha256.")) requireHash(destination.resolve(relative(key.substring(7))), manifest.getProperty(key));
        }
    }

    private static Path pinsPath() throws IOException {
        List<Path> roots = new ArrayList<>();
        try {
            Path code = Path.of(BridgeNativeSupport.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            roots.add(Files.isDirectory(code) ? code : code.getParent());
        } catch (URISyntaxException | NullPointerException ignored) {
            // Source launches also support the current checkout below.
        }
        roots.add(Path.of("").toAbsolutePath());
        for (Path root : roots) {
            for (Path parent = root; parent != null; parent = parent.getParent()) {
                Path candidate = parent.resolve("packaging/java-bridge-support.properties");
                if (Files.isRegularFile(candidate)) return candidate;
            }
        }
        throw failure("compiler distribution is missing packaging/java-bridge-support.properties");
    }

    private static Path relative(String name) throws IOException {
        if (name == null || name.isBlank()) throw failure("missing relative support path");
        Path path = Path.of(name);
        if (path.isAbsolute() || path.normalize().startsWith("..")) throw failure("invalid relative support path: " + name);
        return path;
    }

    private static Properties read(Path path) throws IOException {
        var result = new Properties();
        try (var reader = Files.newBufferedReader(path)) { result.load(reader); }
        catch (IOException problem) { throw failure("cannot read " + path); }
        return result;
    }

    private static void requireHash(Path file, String expected) throws IOException {
        if (expected == null || !Files.isRegularFile(file) || !TlsDependency.sha256(file).equals(expected)) {
            throw failure("pinned support checksum mismatch: " + file);
        }
    }

    private static IOException failure(String detail) {
        return new IOException("Java Bridge Linux support: " + detail
                + "; prepare the pinned SDK and set IRONWOOD_BRIDGE_SUPPORT_HOME");
    }
}
