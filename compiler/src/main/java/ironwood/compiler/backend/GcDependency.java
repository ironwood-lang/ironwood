// SPDX-License-Identifier: MIT OR Apache-2.0
package ironwood.compiler.backend;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;

/** Required, private-per-image GC inputs. Discovery never downloads or builds. */
record GcDependency(Path home, List<String> compileFlags, List<String> linkFlags,
                    String identity, NativeTarget target) {
    static GcDependency discover(Path distribution, LlvmToolchain toolchain) throws IOException {
        String override = System.getenv("IRONWOOD_GC_HOME");
        Path home = override == null ? distribution.resolve("toolchain/ironwood-gc")
                : Path.of(override).toAbsolutePath().normalize();
        if (override != null && override.isBlank()) throw failure("IRONWOOD_GC_HOME is empty");
        String os = System.getProperty("os.name"), arch = System.getProperty("os.arch");
        String platform = os.startsWith("Mac") && arch.equals("aarch64") ? "macos-arm64"
                : os.equals("Linux") && arch.equals("amd64") ? "linux-x86_64" : "unqualified";
        Properties qualified = read(distribution.resolve("packaging/bdwgc-qualified.properties"));
        Properties build = read(home.resolve("build.properties"));
        var profile = qualifiedProfile(qualified, platform, build.getProperty("configuration.sha256"));
        String identity = profile.identity();
        if (identity == null || !identity.matches("[a-f0-9]{64}")
                || !"1".equals(build.getProperty("format")) || !platform.equals(build.getProperty("target"))
                || !"23.1.0".equals(toolchain.version()) || !toolchain.version().equals(build.getProperty("llvm.version"))
                || !"ironwood-gc-engine-3".equals(build.getProperty("adapter.abi"))
                || !"2".equals(build.getProperty("roots.version")) || !"1".equals(build.getProperty("runtime.owner.version"))
                || !"3".equals(build.getProperty("foreign.roots.version"))
                || !"2".equals(build.getProperty("foreign.runtime.owner.version"))
                || !"1".equals(build.getProperty("foreign.segments.version"))
                || !identity.equals(build.getProperty("configuration.sha256"))
                || !identity.equals(TlsDependency.sha256(home.resolve("configuration.json")))) {
            throw failure("unqualified target or stale configuration/ABI at " + home);
        }
        var expected = new HashSet<String>();
        for (String key : build.stringPropertyNames()) {
            if (!key.startsWith("sha256.")) continue;
            String name = key.substring(7);
            Path relative = Path.of(name);
            if (relative.isAbsolute() || relative.normalize().startsWith("..")) throw failure("invalid manifest path");
            Path file = home.resolve(relative);
            if (Files.isSymbolicLink(file) || !Files.isRegularFile(file)
                    || !TlsDependency.sha256(file).equals(build.getProperty(key))) throw failure("checksum mismatch: " + name);
            expected.add(name);
        }
        for (String required : List.of("lib/libgc.a", "lib/libironwood_gc_engine.a", "include/gc/gc.h",
                "include/ironwood_gc_configuration.h", "configuration.json", "packaging/bdwgc.json",
                "runtime/include/ironwood_gc_engine.h", "runtime/src/ironwood_gc_engine.c",
                "compiler/src/main/cpp/GcNonmovingStatepoints.cpp", "scripts/prepare-gc.py",
                "runtime/src/ironwood_runtime.c", "runtime/include/ironwood_runtime.h",
                "runtime/src/ironwood_gc_roots.c", "runtime/include/ironwood_gc_roots.h",
                "sources/gc-8.2.12.tar.gz", "licenses/BDWGC-NOTICES.txt", "configuration-audit.json")) {
            if (!expected.contains(required)) throw failure("missing required input: " + required);
        }
        try (var files = Files.walk(home)) {
            var actual = new HashSet<String>();
            files.filter(Files::isRegularFile).forEach(p -> actual.add(home.relativize(p).toString()));
            actual.remove("build.json"); actual.remove("build.properties");
            if (!actual.equals(expected)) throw failure("unmanifested SDK inputs");
        }
        for (String source : List.of("packaging/bdwgc.json", "runtime/include/ironwood_gc_engine.h",
                "runtime/src/ironwood_gc_engine.c", "compiler/src/main/cpp/GcNonmovingStatepoints.cpp", "scripts/prepare-gc.py",
                "runtime/src/ironwood_runtime.c", "runtime/include/ironwood_runtime.h",
                "runtime/src/ironwood_gc_roots.c", "runtime/include/ironwood_gc_roots.h")) {
            if (!TlsDependency.sha256(distribution.resolve(source)).equals(build.getProperty("sha256." + source))) {
                throw failure("SDK does not match current compiler/runtime input: " + source);
            }
        }
        List<String> compile = new ArrayList<>(), link = new ArrayList<>();
        if (platform.equals("macos-arm64")) {
            Path sdk = Path.of(TlsDependency.appleSdk());
            if (!profile.matchesSystemInput(TlsDependency.sha256(sdk.resolve("SDKSettings.json")))) throw failure("unqualified Apple SDK");
            compile.addAll(List.of("-isysroot", sdk.toString(), "-mmacosx-version-min=14.0"));
        } else {
            Path sdk = toolchain.home().resolve("x86_64-conda-linux-gnu/sysroot");
            Path featuresPath = sdk.resolve("usr/include/features.h");
            if (!Files.isRegularFile(featuresPath)) throw failure("glibc 2.17 sysroot missing");
            String features = Files.readString(featuresPath);
            if (!features.matches("(?s).*#define\\s+__GLIBC__\\s+2\\s.*")
                    || !features.matches("(?s).*#define\\s+__GLIBC_MINOR__\\s+17\\s.*")) {
                throw failure("selected sysroot is not glibc 2.17");
            }
            List<Path> records;
            try (var files = Files.list(toolchain.home().resolve("conda-meta"))) {
                records = files.filter(path -> path.getFileName().toString().startsWith("sysroot_linux-64-"))
                        .filter(path -> path.toString().endsWith(".json")).toList();
            }
            if (records.size() != 1 || !profile.matchesSystemInput(TlsDependency.sha256(records.getFirst()))) {
                throw failure("unqualified glibc sysroot package identity");
            }
            compile.add("--sysroot=" + sdk);
            link.addAll(List.of("-fuse-ld=lld", "-pie", "-L" + toolchain.home().resolve("lib"),
                    "-Wl,-rpath," + toolchain.home().resolve("lib"),
                    "-Wl,--exclude-libs,ALL,-z,defs,-z,now,-z,relro,--eh-frame-hdr"));
        }
        compile.add("--target=" + build.getProperty("target.triple"));
        compile.add("-I" + home.resolve("include/gc"));
        compile.add("-I" + distribution.resolve("runtime/include"));
        link.addAll(compile);
        link.add(home.resolve("lib/libironwood_gc_engine.a").toString());
        link.add(home.resolve("lib/libgc.a").toString());
        return new GcDependency(home, List.copyOf(compile), List.copyOf(link), identity,
                new NativeTarget(build.getProperty("target.triple"), build.getProperty("target.data-layout")));
    }

    record QualifiedProfile(String identity, String systemInput) {
        boolean matchesSystemInput(String actual) { return systemInput.equals(actual); }
    }

    static QualifiedProfile qualifiedProfile(Properties qualified, String platform, String identity) throws IOException {
        List<String> profiles = platform.equals("linux-x86_64")
                ? List.of(platform, platform + ".native-host")
                : platform.equals("macos-arm64") ? List.of(platform) : List.of();
        if (identity != null && identity.matches("[a-f0-9]{64}")) {
            for (String prefix : profiles) {
                if (!identity.equals(qualified.getProperty(prefix + ".configuration.sha256"))) continue;
                String system = qualified.getProperty(prefix + (platform.equals("macos-arm64")
                        ? ".sdk.settings.sha256" : ".sysroot.record.sha256"));
                if (system != null && system.matches("[a-f0-9]{64}")) return new QualifiedProfile(identity, system);
                throw failure("qualified build profile has no exact system-input identity");
            }
        }
        throw failure("unqualified target or stale configuration/ABI");
    }

    private static Properties read(Path path) throws IOException {
        var properties = new Properties();
        try (var reader = Files.newBufferedReader(path)) { properties.load(reader); }
        return properties;
    }

    private static IOException failure(String message) { return new IOException("GC dependency: " + message); }
}
