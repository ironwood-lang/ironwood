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

/** Host-target composition of proved projections, lowering, transport and packaging. */
final class BridgeProducer {
    private BridgeProducer() {}

    static void build(CompilationArtifact artifact, BridgeExportSurface surface, Path output,
            LlvmToolchain toolchain, OptimizationLevel optimization, BridgeDistributionInputs.Options packaging,
            PrintStream diagnostics) throws IOException {
        build(artifact, surface, null, null, null, output, toolchain, optimization, packaging, diagnostics);
    }

    static void build(CompilationArtifact artifact, BridgeObjectAdmission objects, Path output,
            LlvmToolchain toolchain, OptimizationLevel optimization, BridgeDistributionInputs.Options packaging,
            PrintStream diagnostics) throws IOException {
        if (!objects.matches(artifact, objects.surface())) {
            throw new IOException("Java Bridge object preview requires exact final object admission");
        }
        build(artifact, objects.surface(), objects, null, null, output, toolchain, optimization, packaging, diagnostics);
    }

    static void build(BridgeCallbackAdmission callbacks, Path output, LlvmToolchain toolchain,
            OptimizationLevel optimization, BridgeDistributionInputs.Options packaging, PrintStream diagnostics) throws IOException {
        build(callbacks.artifact(), callbacks.surface(), null, callbacks, null, output, toolchain, optimization, packaging, diagnostics);
    }

    static void build(BridgeOwnedCallbackAdmission callbacks, Path output, LlvmToolchain toolchain,
            OptimizationLevel optimization, BridgeDistributionInputs.Options packaging, PrintStream diagnostics) throws IOException {
        build(callbacks.artifact(), callbacks.surface(), null, null, callbacks, output, toolchain, optimization, packaging, diagnostics);
    }

    private static void build(CompilationArtifact artifact, BridgeExportSurface surface, BridgeObjectAdmission objects,
            BridgeCallbackAdmission callbacks, BridgeOwnedCallbackAdmission owners, Path output,
            LlvmToolchain toolchain, OptimizationLevel optimization, BridgeDistributionInputs.Options packaging,
            PrintStream diagnostics) throws IOException {
        String host = hostTarget(); boolean macos = host.equals("macos-arm64");
        Path javaHome = BridgeBuildTools.requireJniHeaders(macos);
        Path destination = output.toAbsolutePath().normalize();
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Java Bridge output is not a regular file: " + destination);
        }
        var producer = BridgeProducerInputs.discover();
        var support = macos ? null : BridgeNativeSupport.discover(toolchain);
        var distribution = BridgeDistributionInputs.discover(artifact, packaging);
        var projection = projection(artifact, surface, objects, callbacks, owners, destination.getFileName().toString(), producer);
        var generation = projection.generation(); var java = projection.java();
        if (NativeLinkRequirements.from(projection.program()).tls()) throw new IOException("Java Bridge preview does not yet package optional TLS dependencies");
        String llvm = new LlvmEmitter().emit(projection.program());
        Files.createDirectories(destination.getParent());
        Path stage = Files.createTempDirectory(destination.getParent(), ".ironwood-bridge-build-");
        try {
            Path runtime = RuntimeLibrary.discover().source().orElseThrow().getParent().getParent();
            var inputs = nativeInputs(stage, toolchain, optimization, javaHome, producer, distribution, llvm, projection, host, support);
            var build = generation.nativeBuild(host, inputs);
            String filename = macos ? "libbridge.dylib" : "libbridge.so";
            Path llvmFile = stage.resolve("program.ll"), adapter = stage.resolve("adapter.c"), object = stage.resolve("adapter.o"), image = stage.resolve(filename);
            Files.writeString(llvmFile, llvm);
            Files.writeString(adapter, projection.adapters() + projection.bootstrap().apply(build));
            var adapterCommand = new ArrayList<>(List.of(toolchain.clang().toString(), "-std=c11", "-Wall", "-Wextra", "-Werror",
                    "-fPIC", "-fvisibility=hidden", "--target=" + inputs.get("target.triple"), optimization.clangArgument(),
                    "-I" + javaHome.resolve("include"), "-I" + javaHome.resolve(macos ? "include/darwin" : "include/linux"), "-I" + runtime.resolve("include"),
                    "-c", adapter.toString(), "-o", object.toString()));
            if (support != null) adapterCommand.addAll(support.compileFlags());
            BridgeBuildTools.run(stage, "JNI adapter compilation", adapterCommand);
            var linked = new NativeBackend().linkShared(toolchain, llvmFile, image, optimization, List.of(object));
            if (!linked.success()) throw new IOException("Java Bridge native link failed: " + linked.output());
            if (!linked.output().isBlank()) diagnostics.println(linked.output());
            byte[] payload = Files.readAllBytes(image);
            var platform = new TreeMap<String, String>();
            Map<String, byte[]> supportEntries = Map.of(); Map<String, String> extracted = Map.of(); String minimum;
            if (macos) {
                BridgeBuildTools.run(stage, "macOS signature verification", List.of("/usr/bin/codesign", "--verify", "--strict", image.toString()));
                var target = BridgeMacPayload.inspect(payload); minimum = target.minimumOs();
                platform.put("native.cpu", "baseline-arm64"); platform.put("native.macos.minimum", minimum); platform.put("native.macos.sdk", target.sdk());
                for (int i = 0; i < target.dependencies().size(); i++) {
                    String dependency = target.dependencies().get(i);
                    if (!Set.of("/usr/lib/libSystem.B.dylib", "/usr/lib/libc++.1.dylib").contains(dependency)) {
                        throw new IOException("Java Bridge cannot package macOS native dependency: " + dependency);
                    }
                    platform.put("native.dependency." + i, dependency);
                }
            } else {
                var target = BridgeLinuxPayload.inspect(stage, image, host, toolchain, support);
                platform.putAll(target.metadata()); minimum = platform.get("native.linux.glibc.minimum");
                supportEntries = target.supportEntries(); extracted = target.extractedDependencies();
            }
            boolean views = BridgeByteViewSources.required(java);
            var sources = new TreeMap<>(java.sources());
            if (views) sources.put(BridgeByteViewSources.SOURCE_PATH, BridgeByteViewSources.SOURCE);
            sources.put(generation.supportPackage().replace('.', '/') + "/Support.java", BridgeLoaderSources.generate(generation, java,
                    new BridgeLoaderSources.Payload(build, minimum, BridgeGeneration.bytesDigest(payload), extracted)));
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
            var classEntries = new TreeMap<>(directory(classes));
            byte[] valuesJar = null;
            if (views) {
                byte[] bytecode = classEntries.remove(BridgeByteViewSources.CLASS_PATH);
                if (bytecode == null) throw new IOException("missing shared byte-view class");
                valuesJar = BridgeValuesLibrary.packageClass(stage, bytecode, distribution.entries());
                add(entries, BridgeByteViewSources.RESOURCE, valuesJar);
                platform.put("java.values.abi", BridgeByteViewSources.ABI);
                platform.put("java.values.version", producer.compilerVersion());
                platform.put("java.values.sha256", BridgeGeneration.bytesDigest(valuesJar));
                BridgeValuesLibrary.checkDestination(destination.resolveSibling(BridgeByteViewSources.JAR_NAME), valuesJar);
                sources.remove(BridgeByteViewSources.SOURCE_PATH);
            }
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
            String resourceRoot = "META-INF/ironwood/native/" + host + "/" + generation.identity() + "/";
            String imagePath = resourceRoot + filename;
            for (var entry : supportEntries.entrySet()) add(entries, resourceRoot + entry.getKey(), entry.getValue());
            int dependencyIndex = 0;
            for (var entry : new TreeMap<>(extracted).entrySet()) {
                platform.put("native.extract." + dependencyIndex + ".path", entry.getKey());
                platform.put("native.extract." + dependencyIndex++ + ".sha256", entry.getValue());
            }
            add(entries, imagePath, payload);
            add(entries, BridgePackageManifest.PATH, BridgePackageManifest.create(generation, build, java, platform, imagePath, entries));
            if (!BridgeProducerInputs.discover().equals(producer) || !BridgeDistributionInputs.discover(artifact, packaging).identity().equals(distribution.identity())) {
                throw new IOException("Java Bridge producer/runtime/distribution inputs changed during the build; previous output preserved");
            }
            if (views) BridgeValuesLibrary.copy(stage.resolve(BridgeByteViewSources.JAR_NAME),
                    destination.resolveSibling(BridgeByteViewSources.JAR_NAME), valuesJar);
            BridgeJarArchive.publish(destination, entries);
        } finally {
            try (var paths = Files.walk(stage)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            } catch (IOException cleanup) {
                diagnostics.println("note: could not remove Java Bridge staging directory " + stage + ": " + cleanup.getMessage());
            }
        }
    }

    private record Projection(ironwood.compiler.ir.IrProgram program, BridgeGeneration generation, BridgeJavaSources java,
                              String adapters, java.util.function.Function<BridgeGeneration.NativeBuild, String> bootstrap) {}

    private static Projection projection(CompilationArtifact artifact, BridgeExportSurface surface, BridgeObjectAdmission objects,
            BridgeCallbackAdmission callbacks, BridgeOwnedCallbackAdmission owners, String artifactName, BridgeProducerInputs producer) throws IOException {
        if (owners != null) {
            if (!owners.matches(artifact, surface)) throw new IOException("owner callback projection identity mismatch");
            var generation = BridgeGeneration.createOwnedCallbacks(artifactName, owners,
                    producer.compilerVersion(), producer.compilerIdentity(), producer.runtimeIdentity());
            var java = BridgeOwnedCallbackJavaSources.generate(owners, generation);
            var adapters = BridgeOwnedCallbackNativeSources.generate(owners, generation, java);
            return new Projection(java.batching().program(), generation, java.declarations(), adapters.source(),
                    build -> BridgeBootstrapSources.generate(generation, build, java.declarations(), adapters));
        }
        if (callbacks != null) {
            if (!callbacks.matches(artifact, surface)) throw new IOException("callback projection identity mismatch");
            var generation = BridgeGeneration.createCallbacks(artifactName, callbacks,
                    producer.compilerVersion(), producer.compilerIdentity(), producer.runtimeIdentity());
            var java = BridgeJavaSources.generateCallbacks(callbacks, generation);
            var adapters = BridgeSynchronousCallbackNativeSources.generate(callbacks, generation, java);
            return new Projection(callbacks.program(), generation, java, adapters.source(),
                    build -> BridgeBootstrapSources.generate(generation, build, java, adapters));
        }
        if (objects != null) {
            var generation = BridgeGeneration.createObjects(artifactName, artifact, objects,
                    producer.compilerVersion(), producer.compilerIdentity(), producer.runtimeIdentity());
            var projected = objects.roots().isPresent() ? BridgePermanentJavaSources.generateRoots(artifact, objects, generation)
                    : BridgePermanentJavaSources.generate(artifact, objects, generation);
            var adapters = objects.roots().isPresent() ? BridgePermanentNativeSources.generateRoots(artifact, objects, generation, projected)
                    : BridgePermanentNativeSources.generate(artifact, objects, generation, projected);
            // The final proof owns this exact already transformed program.
            return new Projection(objects.program(), generation, projected.declarations(), adapters.source(),
                    build -> BridgeBootstrapSources.generate(generation, build, projected.declarations(), adapters));
        }
        var module = BridgeEntryModule.stringValues(artifact, surface.roots());
        var closure = BridgeExceptionClosure.builtins(artifact, module);
        if (closure.status() != BridgeProof.Status.PROVED) throw new IOException(closure.reason());
        var snapshot = closure.contract().orElseThrow();
        var generation = BridgeGeneration.create(artifactName, artifact, surface,
                producer.compilerVersion(), producer.compilerIdentity(), producer.runtimeIdentity());
        var java = BridgeJavaSources.generate(artifact, surface, generation, module, snapshot.projection());
        var values = BridgeValueNativeSources.generate(artifact, module, snapshot.projection(), snapshot.entries());
        return new Projection(NativeLinkPipeline.finish(NativeLinkPipeline.optimize(snapshot.entries().program())), generation, java, values.source(),
                build -> BridgeBootstrapSources.generate(generation, build, java, values));
    }

    private static Map<String, String> nativeInputs(Path stage, LlvmToolchain toolchain, OptimizationLevel optimization,
            Path javaHome, BridgeProducerInputs producer, BridgeDistributionInputs distribution, String llvm,
            Projection projection, String host, BridgeNativeSupport support) throws IOException {
        var generation = projection.generation(); var java = projection.java();
        Path probe = stage.resolve("target.c"), targetLlvm = stage.resolve("target.ll"); Files.writeString(probe, "");
        var probeCommand = new ArrayList<>(List.of(toolchain.clang().toString(), "-std=c11", "-S", "-emit-llvm", "-x", "c",
                "-fvisibility=hidden", probe.toString(), "-o", targetLlvm.toString()));
        if (support != null) probeCommand.addAll(support.compileFlags());
        BridgeBuildTools.run(stage, "native target discovery", probeCommand);
        var target = NativeTarget.fromLlvm(Files.readString(targetLlvm));
        boolean macos = host.equals("macos-arm64");
        boolean matching = macos ? target.triple().startsWith("arm64-apple-macosx") || target.triple().startsWith("aarch64-apple-macosx")
                : target.triple().contains("linux-gnu") && target.triple().startsWith(host.equals("linux-arm64") ? "aarch64-" : "x86_64-");
        if (!matching) throw new IOException("Java Bridge host/Clang target mismatch: " + host + " / " + target.triple());
        var inputs = new TreeMap<String, String>();
        inputs.put("target.triple", target.triple()); inputs.put("target.layout", target.dataLayout());
        inputs.put("llvm.version", toolchain.version()); inputs.put("clang.version", toolchain.clangVersion());
        inputs.put("optimization", optimization.toString()); inputs.put("cpu", "default-baseline");
        inputs.put("adapter.flags", "-std=c11 -Wall -Wextra -Werror -fPIC -fvisibility=hidden " + optimization.clangArgument());
        if (macos) {
            inputs.put("sdk.version", BridgeBuildTools.run(stage, "macOS SDK discovery", List.of("/usr/bin/xcrun", "--show-sdk-version")).trim());
            inputs.put("deployment.environment", System.getenv().getOrDefault("MACOSX_DEPLOYMENT_TARGET", "default"));
        } else {
            inputs.put("libc", "glibc-2.17"); inputs.put("link.binding", "now");
            inputs.put("support.manifest.sha256", BridgeGeneration.bytesDigest(Files.readAllBytes(support.home().resolve("build.properties"))));
            inputs.put("support.compile.flags", "--no-default-config --sysroot=<pinned-glibc-2.17> --gcc-toolchain=<pinned-llvm>");
        }
        inputs.put("jdk.version", System.getProperty("java.runtime.version")); inputs.put("jdk.vendor", System.getProperty("java.vendor"));
        inputs.put("jdk.jni.sha256", BridgeGeneration.bytesDigest(Files.readAllBytes(javaHome.resolve("include/jni.h"))));
        inputs.put("jdk.jni_md.sha256", BridgeGeneration.bytesDigest(Files.readAllBytes(javaHome.resolve(macos ? "include/darwin/jni_md.h" : "include/linux/jni_md.h"))));
        inputs.put("producer.compiler", producer.compilerIdentity()); inputs.put("producer.runtime", producer.runtimeIdentity());
        inputs.put("distribution", distribution.identity()); inputs.put("llvm.ir.sha256", digest(llvm));
        inputs.put(generation.manifest().containsKey("projection") ? "object.adapters.sha256" : "value.adapters.sha256", digest(projection.adapters()));
        var sourceHashes = new TreeMap<String, String>(); java.sources().forEach((name, source) -> sourceHashes.put(name, digest(source)));
        inputs.put("java.projection.sha256", BridgeGeneration.contentIdentity(sourceHashes));
        // Hash the unpaired bootstrap template, then embed the resulting build
        // identity. The final signed image digest is a separate manifest field.
        var unpaired = new BridgeGeneration.NativeBuild(generation.identity(), generation.apiIdentity(), host, "0".repeat(64), Map.of());
        inputs.put("bootstrap.template.sha256", digest(projection.bootstrap().apply(unpaired)));
        return inputs;
    }

    private static String hostTarget() throws IOException {
        String os = System.getProperty("os.name"), arch = System.getProperty("os.arch");
        boolean arm = Set.of("aarch64", "arm64").contains(arch);
        if (os.equals("Mac OS X") && arm) return "macos-arm64";
        if (os.equals("Linux") && arm) return "linux-arm64";
        if (os.equals("Linux") && Set.of("amd64", "x86_64").contains(arch)) return "linux-x86_64";
        throw new IOException("Java Bridge producer requires macos-arm64, linux-arm64 or linux-x86_64; detected " + os + " " + arch);
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
