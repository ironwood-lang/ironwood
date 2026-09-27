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

    static Path build(Path folder, Path llvm, LlvmToolchain toolchain, OptimizationLevel level,
            BridgeGeneration generation, BridgeGeneration.NativeBuild build, BridgeJavaSources declarations,
            String nativeSource, Map<String, String> overrides) throws Exception {
        Files.createDirectories(folder);
        Path javaHome = Path.of(System.getProperty("java.home"));
        Path c = folder.resolve("adapter.c"), object = folder.resolve("adapter.o"), image = folder.resolve("libbridge.dylib");
        Files.writeString(c, nativeSource);
        BridgeEntryTests.run(folder, List.of(toolchain.clang().toString(), "-std=c11", "-Wall", "-Wextra", "-Werror", "-fPIC", "-fvisibility=hidden",
                level.clangArgument(), "-I" + javaHome.resolve("include"), "-I" + javaHome.resolve("include/darwin"),
                "-I" + Path.of("runtime/include").toAbsolutePath(), "-c", c.toString(), "-o", object.toString()), "compile");
        var linked = new NativeBackend().linkShared(toolchain, llvm, image, level, List.of(object));
        Files.writeString(folder.resolve("link.log"), linked.output());
        if (!linked.success()) throw new AssertionError(linked.output());
        BridgeEntryTests.run(folder, List.of("/usr/bin/codesign", "--verify", "--strict", image.toString()), "codesign");
        String sha = BridgeGeneration.bytesDigest(Files.readAllBytes(image));
        Files.writeString(folder.resolve("payload.sha256"), sha + "\n");
        var target = BridgeMacPayload.inspect(Files.readAllBytes(image));
        var sources = new java.util.TreeMap<>(declarations.sources());
        sources.putAll(overrides);
        sources.put(generation.supportPackage().replace('.', '/') + "/Support.java", BridgeLoaderSources.generate(generation, declarations,
                new BridgeLoaderSources.Payload(build, target.minimumOs(), sha)));
        Path classes = folder.resolve("classes");
        var javac = new ArrayList<>(List.of(javaHome.resolve("bin/javac").toString(), "--release", "21", "-Xlint:all", "-Werror", "-d", classes.toString()));
        for (var entry : sources.entrySet()) {
            Path file = folder.resolve("sources").resolve(entry.getKey()); Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue()); javac.add(file.toString());
        }
        BridgeEntryTests.run(folder, javac, "javac");
        Path resource = classes.resolve("META-INF/ironwood/native/macos-arm64/" + generation.identity() + "/libbridge.dylib");
        Files.createDirectories(resource.getParent()); Files.copy(image, resource);
        Path jar = folder.resolve("permanent.jar");
        BridgeEntryTests.run(folder, List.of(javaHome.resolve("bin/jar").toString(), "--create", "--file", jar.toString(), "-C", classes.toString(), "."), "jar");
        Files.writeString(folder.resolve("jar.sha256"), BridgeGeneration.bytesDigest(Files.readAllBytes(jar)) + "\n");
        return jar;
    }
}
