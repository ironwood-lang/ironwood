// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.backend.*;
import ironwood.compiler.bridge.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Local experiment assembly, not the public producer's distribution pipeline. */
final class BridgeGeneratedJarTests {
    private BridgeGeneratedJarTests() {}

    static String target() {
        String os = System.getProperty("os.name"), arch = System.getProperty("os.arch");
        if (os.equals("Mac OS X") && List.of("aarch64", "arm64").contains(arch)) return "macos-arm64";
        if (os.equals("Linux") && arch.equals("aarch64")) return "linux-arm64";
        if (os.equals("Linux") && List.of("amd64", "x86_64").contains(arch)) return "linux-x86_64";
        throw new AssertionError("unsupported bridge fixture host: " + os + "/" + arch);
    }

    static String imageName() { return target().startsWith("macos") ? "libbridge.dylib" : "libbridge.so"; }

    static Path build(Path folder, Path llvm, LlvmToolchain toolchain, OptimizationLevel level,
            BridgeGeneration generation, BridgeGeneration.NativeBuild build, BridgeJavaSources declarations,
            String nativeSource, Map<String, String> overrides) throws Exception {
        Files.createDirectories(folder);
        String host = target(); boolean mac = host.equals("macos-arm64");
        if (!build.target().equals(host)) throw new AssertionError("fixture build identity does not match host: " + build.target() + " / " + host);
        var support = mac ? null : BridgeNativeSupport.discover(toolchain);
        Path javaHome = Path.of(System.getProperty("java.home"));
        Path c = folder.resolve("adapter.c"), object = folder.resolve("adapter.o"), image = folder.resolve(imageName());
        Files.writeString(c, nativeSource);
        var compile = new ArrayList<>(List.of(toolchain.clang().toString(), "-std=c11", "-Wall", "-Wextra", "-Werror", "-fPIC", "-fvisibility=hidden",
                level.clangArgument(), "-I" + javaHome.resolve("include"), "-I" + javaHome.resolve(mac ? "include/darwin" : "include/linux"),
                "-I" + Path.of("runtime/include").toAbsolutePath(), "-c", c.toString(), "-o", object.toString()));
        if (support != null) compile.addAll(support.compileFlags());
        BridgeEntryTests.run(folder, compile, "compile");
        var linked = new NativeBackend().linkShared(toolchain, llvm, image, level, List.of(object));
        Files.writeString(folder.resolve("link.log"), linked.output());
        if (!linked.success()) throw new AssertionError(linked.output());
        if (mac) BridgeEntryTests.run(folder, List.of("/usr/bin/codesign", "--verify", "--strict", image.toString()), "codesign");
        String sha = BridgeGeneration.bytesDigest(Files.readAllBytes(image));
        Files.writeString(folder.resolve("payload.sha256"), sha + "\n");
        String minimum;
        Map<String, byte[]> supportEntries = Map.of(); Map<String, String> extracted = Map.of();
        var identity = new java.util.TreeMap<String, String>();
        identity.put("scope", "internal generated fixture; not a production paired artifact");
        identity.put("generation", generation.identity()); identity.put("native.target", host);
        identity.put("native.build", build.identity()); identity.put("native.sha256", sha);
        if (mac) minimum = BridgeMacPayload.inspect(Files.readAllBytes(image)).minimumOs();
        else {
            var payload = BridgeLinuxPayload.inspect(folder, image, host, toolchain, support);
            minimum = payload.metadata().get("native.linux.glibc.minimum");
            supportEntries = payload.supportEntries(); extracted = payload.extractedDependencies(); identity.putAll(payload.metadata());
        }
        Files.write(folder.resolve("payload.properties"), BridgePackageManifest.serialize(identity));
        var sources = new java.util.TreeMap<>(declarations.sources());
        sources.putAll(overrides);
        sources.put(generation.supportPackage().replace('.', '/') + "/Support.java", BridgeLoaderSources.generate(generation, declarations,
                new BridgeLoaderSources.Payload(build, minimum, sha, extracted)));
        Path classes = folder.resolve("classes");
        var javac = new ArrayList<>(List.of(javaHome.resolve("bin/javac").toString(), "--release", "21", "-Xlint:all", "-Werror", "-d", classes.toString()));
        for (var entry : sources.entrySet()) {
            Path file = folder.resolve("sources").resolve(entry.getKey()); Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue()); javac.add(file.toString());
        }
        BridgeEntryTests.run(folder, javac, "javac");
        Path nativeRoot = classes.resolve("META-INF/ironwood/native/" + host + "/" + generation.identity());
        Path resource = nativeRoot.resolve(imageName());
        Files.createDirectories(resource.getParent()); Files.copy(image, resource);
        for (var entry : supportEntries.entrySet()) {
            Path file = nativeRoot.resolve(entry.getKey()); Files.createDirectories(file.getParent()); Files.write(file, entry.getValue());
        }
        Path jar = folder.resolve("permanent.jar");
        BridgeEntryTests.run(folder, List.of(javaHome.resolve("bin/jar").toString(), "--create", "--file", jar.toString(), "-C", classes.toString(), "."), "jar");
        Files.writeString(folder.resolve("jar.sha256"), BridgeGeneration.bytesDigest(Files.readAllBytes(jar)) + "\n");
        return jar;
    }
}
