// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.backend.*;
import ironwood.compiler.bridge.*;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** One-target P2 composition of existing proof, lowering, transport and packaging stages. */
final class BridgeProducer {
    private BridgeProducer() {}

    static void build(CompilationArtifact artifact, BridgeExportSurface surface, Path output,
            LlvmToolchain toolchain, OptimizationLevel optimization, BridgeDistributionInputs.Options packaging,
            PrintStream diagnostics) throws IOException {
        if (!System.getProperty("os.name").equals("Mac OS X") || !Set.of("aarch64", "arm64").contains(System.getProperty("os.arch"))) {
            throw new IOException("Java Bridge preview produces macos-arm64 only; Linux packaging is not yet enabled");
        }
        if (Runtime.version().feature() != 21 || javax.tools.ToolProvider.getSystemJavaCompiler() == null) {
            throw new IOException("Java Bridge producer requires a Java 21 JDK; detected " + Runtime.version());
        }
        Path destination = output.toAbsolutePath().normalize();
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Java Bridge output is not a regular file: " + destination);
        }
        var module = BridgeEntryModule.stringValues(artifact, surface.roots());
        var closure = BridgeExceptionClosure.builtins(artifact, module);
        if (closure.status() != BridgeProof.Status.PROVED) throw new IOException(closure.reason());
        var snapshot = closure.contract().orElseThrow();
        var program = NativeLinkPipeline.finish(NativeLinkPipeline.optimize(snapshot.entries().program()));
        if (NativeLinkRequirements.from(program).tls()) throw new IOException("Java Bridge preview does not yet package optional TLS dependencies");
        var producer = BridgeProducerInputs.discover();
        var distribution = BridgeDistributionInputs.discover(artifact, packaging);
        var generation = BridgeGeneration.create(destination.getFileName().toString(), artifact, surface,
                producer.compilerVersion(), producer.compilerIdentity(), producer.runtimeIdentity());
        var java = BridgeJavaSources.generate(artifact, surface, generation, module, snapshot.projection());
        var values = BridgeValueNativeSources.generate(artifact, module, snapshot.projection(), snapshot.entries());
        String llvm = new LlvmEmitter().emit(program);
        Files.createDirectories(destination.getParent());
        Path stage = Files.createTempDirectory(destination.getParent(), ".ironwood-bridge-build-");
        try {
            Path javaHome = Path.of(System.getProperty("java.home"));
            Path runtime = RuntimeLibrary.discover().source().orElseThrow().getParent().getParent();
            var inputs = nativeInputs(stage, toolchain, optimization, javaHome, producer, distribution, llvm, java, values, generation);
            var build = generation.nativeBuild("macos-arm64", inputs);
            Path llvmFile = stage.resolve("program.ll"), adapter = stage.resolve("adapter.c"), object = stage.resolve("adapter.o"), image = stage.resolve("libbridge.dylib");
            Files.writeString(llvmFile, llvm);
            Files.writeString(adapter, values.source() + BridgeBootstrapSources.generate(generation, build, java, values));
            BridgeBuildTools.run(stage, "JNI adapter compilation", List.of(toolchain.clang().toString(), "-std=c11", "-Wall", "-Wextra", "-Werror",
                    "-fPIC", "-fvisibility=hidden", "--target=" + inputs.get("target.triple"), optimization.clangArgument(),
                    "-I" + javaHome.resolve("include"), "-I" + javaHome.resolve("include/darwin"), "-I" + runtime.resolve("include"),
                    "-c", adapter.toString(), "-o", object.toString()));
            var linked = new NativeBackend().linkShared(toolchain, llvmFile, image, optimization, List.of(object));
            if (!linked.success()) throw new IOException("Java Bridge native link failed: " + linked.output());
            if (!linked.output().isBlank()) diagnostics.println(linked.output());
            BridgeBuildTools.run(stage, "macOS signature verification", List.of("/usr/bin/codesign", "--verify", "--strict", image.toString()));
            byte[] payload = Files.readAllBytes(image);
            var target = BridgeMacPayload.inspect(payload);
            for (String dependency : target.dependencies()) {
                if (!Set.of("/usr/lib/libSystem.B.dylib", "/usr/lib/libc++.1.dylib").contains(dependency)) {
                    throw new IOException("Java Bridge preview cannot package native dependency: " + dependency);
                }
            }
            var sources = new TreeMap<>(java.sources());
            sources.put(generation.supportPackage().replace('.', '/') + "/Support.java", BridgeLoaderSources.generate(generation, java,
                    new BridgeLoaderSources.Payload(build, target.minimumOs(), BridgeGeneration.bytesDigest(payload))));
            Path sourceRoot = stage.resolve("sources"), classes = stage.resolve("classes"), docs = stage.resolve("javadoc");
            var sourceFiles = new ArrayList<Path>();
            for (var source : sources.entrySet()) {
                Path path = sourceRoot.resolve(source.getKey()); Files.createDirectories(path.getParent());
                Files.writeString(path, source.getValue()); sourceFiles.add(path);
            }
            Files.createDirectories(classes);
            BridgeBuildTools.java(sourceFiles, classes, diagnostics);
            var apiSources = surface.types().stream().filter(type -> type.enclosingType().isEmpty())
                    .map(type -> sourceRoot.resolve(type.binaryName().replace('.', '/') + ".java")).toList();
            BridgeBuildTools.javadoc(apiSources, classes, docs, diagnostics);
            var entries = new TreeMap<>(distribution.entries());
            add(entries, "META-INF/MANIFEST.MF", BridgePackageManifest.javaManifest(generation));
            var classEntries = directory(classes);
            var expectedClasses = java.generatedTypes().stream().map(name -> name.replace('.', '/') + ".class").collect(Collectors.toSet());
            if (!classEntries.keySet().equals(expectedClasses)) throw new IOException("generated Java Bridge class inventory differs from preflight manifest");
            for (var entry : classEntries.entrySet()) {
                byte[] bytes = entry.getValue();
                if (bytes.length < 8 || bytes[0] != (byte)0xca || bytes[1] != (byte)0xfe || bytes[2] != (byte)0xba || bytes[3] != (byte)0xbe
                        || bytes[6] != 0 || bytes[7] != 65) throw new IOException("generated Java Bridge class is not Java 21: " + entry.getKey());
                add(entries, entry.getKey(), bytes);
            }
            for (var source : sources.entrySet()) add(entries, "META-INF/ironwood/java-sources/" + source.getKey(), source.getValue().getBytes(StandardCharsets.UTF_8));
            for (var doc : directory(docs).entrySet()) add(entries, "META-INF/ironwood/javadoc/" + doc.getKey(), doc.getValue());
            String imagePath = "META-INF/ironwood/native/macos-arm64/" + generation.identity() + "/libbridge.dylib";
            add(entries, imagePath, payload);
            add(entries, BridgePackageManifest.PATH, BridgePackageManifest.create(generation, build, java, target, imagePath, entries));
            if (!BridgeProducerInputs.discover().equals(producer) || !BridgeDistributionInputs.discover(artifact, packaging).identity().equals(distribution.identity())) {
                throw new IOException("Java Bridge producer/runtime/distribution inputs changed during the build; previous output preserved");
            }
            BridgeJarArchive.publish(destination, entries);
        } finally {
            try (var paths = Files.walk(stage)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            } catch (IOException cleanup) {
                diagnostics.println("note: could not remove Java Bridge staging directory " + stage + ": " + cleanup.getMessage());
            }
        }
    }

    private static Map<String, String> nativeInputs(Path stage, LlvmToolchain toolchain, OptimizationLevel optimization,
            Path javaHome, BridgeProducerInputs producer, BridgeDistributionInputs distribution, String llvm,
            BridgeJavaSources java, BridgeValueNativeSources values, BridgeGeneration generation) throws IOException {
        Path probe = stage.resolve("target.c"), targetLlvm = stage.resolve("target.ll"); Files.writeString(probe, "");
        BridgeBuildTools.run(stage, "native target discovery", List.of(toolchain.clang().toString(), "-std=c11", "-S", "-emit-llvm", "-x", "c",
                "-fvisibility=hidden", probe.toString(), "-o", targetLlvm.toString()));
        var target = NativeTarget.fromLlvm(Files.readString(targetLlvm));
        if (!target.triple().startsWith("arm64-apple-macosx") && !target.triple().startsWith("aarch64-apple-macosx")) {
            throw new IOException("Java Bridge preview requires an ARM64 macOS Clang target; found " + target.triple());
        }
        var inputs = new TreeMap<String, String>();
        inputs.put("target.triple", target.triple()); inputs.put("target.layout", target.dataLayout());
        inputs.put("llvm.version", toolchain.version()); inputs.put("clang.version", toolchain.clangVersion());
        inputs.put("optimization", optimization.toString()); inputs.put("cpu", "default-baseline");
        inputs.put("adapter.flags", "-std=c11 -Wall -Wextra -Werror -fPIC -fvisibility=hidden " + optimization.clangArgument());
        inputs.put("sdk.version", BridgeBuildTools.run(stage, "macOS SDK discovery", List.of("/usr/bin/xcrun", "--show-sdk-version")).trim());
        inputs.put("deployment.environment", System.getenv().getOrDefault("MACOSX_DEPLOYMENT_TARGET", "default"));
        inputs.put("jdk.version", System.getProperty("java.runtime.version")); inputs.put("jdk.vendor", System.getProperty("java.vendor"));
        inputs.put("jdk.jni.sha256", BridgeGeneration.bytesDigest(Files.readAllBytes(javaHome.resolve("include/jni.h"))));
        inputs.put("jdk.jni_md.sha256", BridgeGeneration.bytesDigest(Files.readAllBytes(javaHome.resolve("include/darwin/jni_md.h"))));
        inputs.put("producer.compiler", producer.compilerIdentity()); inputs.put("producer.runtime", producer.runtimeIdentity());
        inputs.put("distribution", distribution.identity()); inputs.put("llvm.ir.sha256", digest(llvm)); inputs.put("value.adapters.sha256", digest(values.source()));
        var sourceHashes = new TreeMap<String, String>(); java.sources().forEach((name, source) -> sourceHashes.put(name, digest(source)));
        inputs.put("java.projection.sha256", BridgeGeneration.contentIdentity(sourceHashes));
        // Hash the unpaired bootstrap template, then embed the resulting build
        // identity. The final signed image digest is a separate manifest field.
        var unpaired = new BridgeGeneration.NativeBuild(generation.identity(), generation.apiIdentity(), "macos-arm64", "0".repeat(64), Map.of());
        inputs.put("bootstrap.template.sha256", digest(BridgeBootstrapSources.generate(generation, unpaired, java, values)));
        return inputs;
    }

    private static Map<String, byte[]> directory(Path root) throws IOException {
        var result = new TreeMap<String, byte[]>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                result.put(root.relativize(path).toString().replace(java.io.File.separatorChar, '/'), Files.readAllBytes(path));
            }
        }
        return result;
    }
    private static void add(Map<String, byte[]> entries, String name, byte[] bytes) throws IOException {
        if (entries.putIfAbsent(name, bytes) != null) throw new IOException("duplicate Java Bridge jar entry: " + name);
    }
    private static String digest(String text) { return BridgeGeneration.bytesDigest(text.getBytes(StandardCharsets.UTF_8)); }
}
