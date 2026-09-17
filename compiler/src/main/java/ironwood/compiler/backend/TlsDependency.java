// SPDX-License-Identifier: MIT OR Apache-2.0
package ironwood.compiler.backend;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;

/** Explicit optional SDK discovery. Called only after retained TLS operations select it. */
record TlsDependency(Path home, List<String> compileFlags, List<String> linkFlags, String identity) {
    static TlsDependency discover(Path distribution, LlvmToolchain toolchain) throws IOException {
        String override = System.getenv("IRONWOOD_TLS_HOME");
        Path home = override == null ? distribution.resolve("toolchain/ironwood-tls")
                : Path.of(override).toAbsolutePath().normalize();
        if (override != null && override.isBlank()) throw failure("IRONWOOD_TLS_HOME is empty");
        Properties pins = read(distribution.resolve("packaging/tls-dependencies.properties"));
        Properties suppliedPins = read(home.resolve("dependencies.properties"));
        Properties build = read(home.resolve("build.properties"));
        if (!pins.equals(suppliedPins) || !"1".equals(build.getProperty("format"))) {
            throw failure("dependency pins or build format mismatch at " + home);
        }
        String os = System.getProperty("os.name");
        String arch = System.getProperty("os.arch");
        String platform = os.startsWith("Mac") ? "macos-arm64"
                : "linux-" + (arch.equals("aarch64") ? "arm64" : "x86_64");
        if (!platform.equals(build.getProperty("platform"))
                || !pins.getProperty("llvm.version").equals(toolchain.version())
                || !toolchain.version().equals(build.getProperty("llvm.version"))
                || !pins.getProperty("configuration").equals(build.getProperty("configuration"))
                || !sha256(home.resolve("dependencies.properties")).equals(build.getProperty("pins.sha256"))) {
            throw failure("wrong platform, compiler or build configuration at " + home + " (need " + platform + ")");
        }
        for (String key : List.of("perl.version", "make.version")) {
            if (!pins.getProperty(key).equals(build.getProperty(key))) throw failure("build prerequisite mismatch: " + key);
        }
        for (String required : List.of("lib/libssl.a", "lib/libcrypto.a", "include/openssl/ssl.h",
                "include/openssl/configuration.h", "share/cacert.pem", "share/ironwood_ca_data.h",
                "licenses/OpenSSL.txt", "licenses/MPL-2.0.txt")) {
            if (build.getProperty("sha256." + required) == null) throw failure("missing SDK input " + required);
        }
        for (String key : build.stringPropertyNames()) {
            if (!key.startsWith("sha256.")) continue;
            Path relative = Path.of(key.substring(7));
            if (relative.isAbsolute() || relative.normalize().startsWith("..")) throw failure("invalid SDK manifest path");
            Path file = home.resolve(relative);
            if (!Files.isRegularFile(file) || !sha256(file).equals(build.getProperty(key))) {
                throw failure("SDK checksum mismatch: " + file);
            }
        }
        if (!pins.getProperty("ca.sha256").equals(sha256(home.resolve("share/cacert.pem")))) {
            throw failure("CA source identity mismatch");
        }
        List<String> compile = new ArrayList<>();
        List<String> link = new ArrayList<>();
        if (os.startsWith("Mac")) {
            if (!pins.getProperty("macos.deployment").equals(build.getProperty("deployment"))) {
                throw failure("macOS deployment target mismatch");
            }
            String sdk = System.getenv("SDKROOT");
            if (sdk == null || sdk.isBlank()) sdk = command(List.of("xcrun", "--show-sdk-path"));
            compile.addAll(List.of("-isysroot", sdk, "-mmacosx-version-min=" + build.getProperty("deployment")));
        } else {
            if (!"2.17".equals(build.getProperty("glibc"))) throw failure("Linux TLS SDK requires glibc 2.17");
            String sysrootRelative = build.getProperty("sysroot.relative", "");
            Path relative = Path.of(sysrootRelative);
            if (relative.isAbsolute() || relative.normalize().startsWith("..") || sysrootRelative.isEmpty()) {
                throw failure("invalid SDK sysroot metadata");
            }
            Path sysroot = toolchain.home().resolve(relative);
            if (!Files.isRegularFile(sysroot.resolve("usr/include/features.h"))) {
                throw failure("matching glibc 2.17 sysroot is absent from selected LLVM toolchain: " + sysroot);
            }
            String features = Files.readString(sysroot.resolve("usr/include/features.h"));
            if (!features.matches("(?s).*#define\\s+__GLIBC__\\s+2\\s.*")
                    || !features.matches("(?s).*#define\\s+__GLIBC_MINOR__\\s+17\\s.*")) {
                throw failure("selected LLVM sysroot is not glibc 2.17: " + sysroot);
            }
            compile.add("--sysroot=" + sysroot);
        }
        link.addAll(compile);
        // The pinned no-dso/no-threads configuration needs only the existing system C runtime.
        if (!"".equals(build.getProperty("link.flags"))) throw failure("unexpected platform library closure");
        link.add(home.resolve("lib/libssl.a").toString());
        link.add(home.resolve("lib/libcrypto.a").toString());
        return new TlsDependency(home, List.copyOf(compile), List.copyOf(link), sha256(home.resolve("build.properties")));
    }

    private static Properties read(Path path) throws IOException {
        Properties result = new Properties();
        try (var reader = Files.newBufferedReader(path)) { result.load(reader); }
        catch (IOException exception) { throw failure("cannot read " + path); }
        return result;
    }

    static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var stream = Files.newInputStream(path)) {
                byte[] buffer = new byte[65536];
                int size;
                while ((size = stream.read(buffer)) >= 0) digest.update(buffer, 0, size);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static String command(List<String> command) throws IOException {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String text = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).strip();
            if (process.waitFor() != 0) throw failure("cannot select Apple SDK: " + text);
            return text;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw failure("SDK discovery interrupted");
        }
    }

    private static IOException failure(String message) {
        return new IOException("TLS dependency: " + message
                + ". Prepare a matching prefix with scripts/prepare-tls.py and set IRONWOOD_TLS_HOME; no system fallback is used.");
    }
}
