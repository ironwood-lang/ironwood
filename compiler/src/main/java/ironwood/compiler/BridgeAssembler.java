// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.bridge.BridgeGeneration;
import ironwood.compiler.bridge.BridgeJavaSources;
import ironwood.compiler.bridge.BridgeLoaderSources;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.zip.ZipFile;

/** Combines paired host artifacts without rebuilding or rewriting native bytes. */
final class BridgeAssembler {
    private static final String NATIVE = "META-INF/ironwood/native/";
    private static final String SOURCES = "META-INF/ironwood/java-sources/";
    private BridgeAssembler() {}

    static int run(String[] arguments, PrintStream out, PrintStream err) {
        try {
            Path output = null; var inputs = new ArrayList<Path>();
            for (int i = 0; i < arguments.length; i++) {
                String argument = arguments[i];
                if (argument.equals("--java-bridge-assemble")) continue;
                if (argument.equals("-o") && ++i < arguments.length && output == null) output = Path.of(arguments[i]);
                else if (argument.startsWith("-")) throw new IllegalArgumentException("unsupported assembly option: " + argument);
                else inputs.add(Path.of(argument));
            }
            if (output == null || !output.toString().endsWith(".jar") || inputs.isEmpty()) {
                throw new IllegalArgumentException("usage: ironwoodc --java-bridge-assemble -o artifact.jar <host.jar>...");
            }
            assemble(output, inputs, err); out.println("assembled " + output.toAbsolutePath().normalize()); return 0;
        } catch (IOException | IllegalArgumentException failure) {
            err.println("error: Java Bridge assembly failed: " + failure.getMessage()); return 1;
        }
    }

    static void assemble(Path output, List<Path> inputs, PrintStream diagnostics) throws IOException {
        if (inputs.isEmpty()) throw new IOException("assembly requires host artifacts");
        if (Runtime.version().feature() != 21 || javax.tools.ToolProvider.getSystemJavaCompiler() == null) {
            throw new IOException("Java Bridge assembly requires a Java 21 JDK");
        }
        var hosts = new TreeMap<String, Host>();
        for (Path input : inputs) {
            Host host = read(input);
            if (hosts.putIfAbsent(host.payload().build().target(), host) != null) throw new IOException("duplicate assembly target: " + host.payload().build().target());
        }
        Host first = hosts.firstEntry().getValue(); var generation = first.generation();
        var producer = BridgeProducerInputs.discover();
        if (!generation.manifest().get("compiler.sha256").equals(producer.compilerIdentity())
                || !generation.manifest().get("runtime.sha256").equals(producer.runtimeIdentity())) {
            throw new IOException("assembly requires the matching producer/runtime generation; rebuild host artifacts with this compiler");
        }
        String support = generation.supportPackage().replace('.', '/') + "/Support";
        var common = common(first.entries(), support);
        var commonMetadata = commonMetadata(first.metadata());
        var entries = new TreeMap<>(common); var metadata = new TreeMap<>(commonMetadata);
        for (Host host : hosts.values()) {
            if (!generation.manifest().equals(host.generation().manifest()) || !commonMetadata.equals(commonMetadata(host.metadata()))) {
                throw new IOException("assembly generation/API/declaration mismatch: " + host.path());
            }
            Map<String, byte[]> other = common(host.entries(), support);
            if (!common.keySet().equals(other.keySet())) throw new IOException("assembly common content inventory mismatch: " + host.path());
            for (String name : common.keySet()) {
                if (!Arrays.equals(common.get(name), other.get(name))) throw new IOException("assembly common content differs: " + name + " in " + host.path());
            }
            for (var entry : host.entries().entrySet()) {
                if (entry.getKey().startsWith(NATIVE) && entries.putIfAbsent(entry.getKey(), entry.getValue()) != null) {
                    throw new IOException("duplicate assembled native resource: " + entry.getKey());
                }
            }
            host.metadata().forEach((key, value) -> {
                if (key.startsWith("native.")) metadata.put("target." + host.payload().build().target() + "." + key.substring(7), value);
            });
        }
        List<String> types = indexed(first.metadata(), "java.type.");
        var declarations = new ArrayList<BridgeJavaSources.NativeDeclaration>();
        for (String prefix : List.of("java.binding.", "java.facade.registration.", "java.root.destruction.")) {
            int count = 0;
            for (int index = 0; first.metadata().containsKey(prefix + index + ".type"); index++) {
                declarations.add(new BridgeJavaSources.NativeDeclaration(required(first.metadata(), prefix + index + ".type"),
                        required(first.metadata(), prefix + index + ".name"), required(first.metadata(), prefix + index + ".descriptor")));
                count++;
            }
            int fields = prefix.equals("java.binding.") ? 4 : 3;
            if (first.metadata().keySet().stream().filter(key -> key.startsWith(prefix)).count() != (long)count * fields) {
                throw new IOException("incomplete native declaration inventory: " + prefix);
            }
        }
        String source = BridgeLoaderSources.generate(generation, types, declarations, required(first.metadata(), "java.ensure"),
                hosts.values().stream().map(Host::payload).toList());
        Path destination = output.toAbsolutePath().normalize(); Files.createDirectories(destination.getParent());
        Path stage = Files.createTempDirectory(destination.getParent(), ".ironwood-bridge-assembly-");
        try {
            Path file = stage.resolve("source/" + support + ".java"); Files.createDirectories(file.getParent()); Files.writeString(file, source);
            Path classes = stage.resolve("classes"); Files.createDirectories(classes);
            var compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
            int status = compiler.run(null, diagnostics, diagnostics, "--release", "21", "-encoding", "UTF-8", "-proc:none", "-Xlint:all", "-Werror",
                    "--class-path", first.path().toAbsolutePath().toString(), "--source-path", "", "-d", classes.toString(), file.toString());
            if (status != 0) throw new IOException("assembled loader compilation failed");
            try (var files = Files.walk(classes)) {
                var emitted = files.filter(Files::isRegularFile).map(classes::relativize).toList();
                if (!emitted.equals(List.of(Path.of(support + ".class")))) throw new IOException("unexpected assembled class inventory: " + emitted);
            }
            entries.put(support + ".class", Files.readAllBytes(classes.resolve(support + ".class")));
            entries.put(SOURCES + support + ".java", source.getBytes(StandardCharsets.UTF_8));
            metadata.put("native.targets", String.join(",", hosts.keySet()));
            entries.forEach((name, bytes) -> metadata.put("content.sha256." + name, BridgeGeneration.bytesDigest(bytes)));
            entries.put(BridgePackageManifest.PATH, BridgePackageManifest.serialize(metadata));
            if (!BridgeProducerInputs.discover().equals(producer)) throw new IOException("assembly compiler/runtime changed during the build");
            BridgeJarArchive.publish(destination, entries);
        } finally {
            try (var files = Files.walk(stage)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
            }
        }
    }

    private record Host(Path path, BridgeGeneration generation, Map<String, String> metadata,
                        Map<String, byte[]> entries, BridgeLoaderSources.Payload payload) {}

    private static Host read(Path path) throws IOException {
        var entries = new TreeMap<String, byte[]>();
        try (var zip = new ZipFile(path.toFile())) {
            for (var entry : zip.stream().toList()) {
                if (entry.isDirectory()) throw new IOException("unexpected directory entry in host jar: " + entry.getName());
                try (var stream = zip.getInputStream(entry)) {
                    if (entries.putIfAbsent(entry.getName(), stream.readAllBytes()) != null) throw new IOException("duplicate host jar entry: " + entry.getName());
                }
            }
        }
        byte[] bytes = entries.remove(BridgePackageManifest.PATH);
        if (bytes == null) throw new IOException("missing host pairing manifest: " + path);
        var properties = new Properties(); properties.load(new ByteArrayInputStream(bytes));
        var metadata = new TreeMap<String, String>(); properties.forEach((key, value) -> metadata.put((String)key, (String)value));
        if (!Arrays.equals(bytes, BridgePackageManifest.serialize(metadata))) throw new IOException("noncanonical host pairing manifest: " + path);
        for (var entry : entries.entrySet()) {
            if (!BridgeGeneration.bytesDigest(entry.getValue()).equals(metadata.get("content.sha256." + entry.getKey()))) {
                throw new IOException("host content digest mismatch: " + entry.getKey());
            }
        }
        if (metadata.keySet().stream().filter(key -> key.startsWith("content.sha256.")).count() != entries.size()) {
            throw new IOException("host content inventory mismatch: " + path);
        }
        var generation = BridgeGeneration.fromManifest(metadata);
        String supportClass = generation.supportPackage().replace('.', '/') + "/Support";
        var distribution = new TreeMap<String, String>(); var javaSources = new TreeMap<String, String>();
        entries.forEach((name, content) -> {
            if (name.startsWith("META-INF/ironwood/licenses/") || name.startsWith("META-INF/ironwood/source/")) distribution.put(name, BridgeGeneration.bytesDigest(content));
            if (name.startsWith(SOURCES) && !name.equals(SOURCES + supportClass + ".java")) javaSources.put(name.substring(SOURCES.length()), BridgeGeneration.bytesDigest(content));
        });
        if (!BridgeGeneration.contentIdentity(distribution).equals(required(metadata, "native.input.distribution"))
                || !BridgeGeneration.contentIdentity(javaSources).equals(required(metadata, "native.input.java.projection.sha256"))) {
            throw new IOException("host source/license/projection inventory does not match native build inputs");
        }
        String target = required(metadata, "native.target");
        var buildInputs = new TreeMap<String, String>(); metadata.forEach((key, value) -> {
            if (key.startsWith("native.input.")) buildInputs.put(key.substring(13), value);
        });
        var build = generation.nativeBuild(target, buildInputs);
        if (!build.identity().equals(required(metadata, "native.build"))) throw new IOException("native build identity mismatch: " + path);
        var dependencies = new TreeMap<String, String>();
        for (int index = 0; metadata.containsKey("native.extract." + index + ".path"); index++) {
            String name = required(metadata, "native.extract." + index + ".path");
            if (dependencies.putIfAbsent(name, required(metadata, "native.extract." + index + ".sha256")) != null) {
                throw new IOException("duplicate extracted native dependency: " + name);
            }
        }
        if (metadata.keySet().stream().filter(key -> key.startsWith("native.extract.")).count() != dependencies.size() * 2L) {
            throw new IOException("incomplete native extraction inventory");
        }
        var payload = new BridgeLoaderSources.Payload(build, required(metadata, target.equals("macos-arm64") ? "native.macos.minimum" : "native.linux.glibc.minimum"),
                required(metadata, "native.sha256"), dependencies);
        String root = NATIVE + target + "/" + generation.identity() + "/";
        String resource = root + payload.filename();
        if (!resource.equals(required(metadata, "native.resource")) || !entries.containsKey(resource)
                || !BridgeGeneration.bytesDigest(entries.get(resource)).equals(payload.imageSha256())) throw new IOException("host image pairing mismatch: " + path);
        for (var dependency : dependencies.entrySet()) {
            if (!entries.containsKey(root + dependency.getKey()) || !BridgeGeneration.bytesDigest(entries.get(root + dependency.getKey())).equals(dependency.getValue())) {
                throw new IOException("host dependency pairing mismatch: " + dependency.getKey());
            }
        }
        if (entries.keySet().stream().anyMatch(name -> name.startsWith(NATIVE) && !name.startsWith(root))) throw new IOException("host jar contains another native target");
        if (!"64".equals(required(metadata, "native.pointer.bits")) || !"little".equals(required(metadata, "native.endian"))) {
            throw new IOException("unsupported native width/endianness");
        }
        if (target.equals("macos-arm64")) {
            var mac = ironwood.compiler.bridge.BridgeMacPayload.inspect(entries.get(resource));
            if (!mac.minimumOs().equals(payload.minimumOs()) || !mac.sdk().equals(required(metadata, "native.macos.sdk"))) {
                throw new IOException("macOS deployment metadata differs from image");
            }
            if (entries.keySet().stream().filter(name -> name.startsWith(root)).count() != 1) throw new IOException("unexpected macOS native resources");
        } else {
            verifyLinux(entries, metadata, root, resource, target, dependencies);
        }
        var actualClasses = entries.keySet().stream().filter(name -> name.endsWith(".class")).collect(java.util.stream.Collectors.toSet());
        var declaredClasses = indexed(metadata, "java.type.").stream().map(name -> name.replace('.', '/') + ".class").collect(java.util.stream.Collectors.toSet());
        if (!actualClasses.equals(declaredClasses)) throw new IOException("host Java class inventory mismatch");
        return new Host(path, generation, metadata, entries, payload);
    }

    private static void verifyLinux(Map<String, byte[]> entries, Map<String, String> metadata, String root,
            String resource, String target, Map<String, String> dependencies) throws IOException {
        byte[] image = entries.get(resource); var elf = java.nio.ByteBuffer.wrap(image).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        if (image.length < 64 || elf.getInt(0) != 0x464c457f || image[4] != 2 || image[5] != 1 || elf.getShort(16) != 3
                || elf.getShort(18) != (target.equals("linux-arm64") ? 183 : 62)
                || !"glibc".equals(required(metadata, "native.linux.libc")) || !"2.17".equals(required(metadata, "native.linux.glibc.minimum"))
                || !"now".equals(required(metadata, "native.linux.binding"))) throw new IOException("Linux image/ABI metadata mismatch");
        String support = required(metadata, "native.linux.support");
        byte[] manifest = entries.get(root + support + "/build.properties");
        if (manifest == null || !BridgeGeneration.bytesDigest(manifest).equals(required(metadata, "native.input.support.manifest.sha256"))
                || !support.equals(".ironwood-bridge-support-" + BridgeGeneration.bytesDigest(manifest))) throw new IOException("unpaired Linux support manifest");
        if (!dependencies.keySet().equals(java.util.Set.of(support + "/lib/libgcc_s.so.1", support + "/lib/libstdc++.so.6"))) {
            throw new IOException("incomplete private Linux runtime extraction");
        }
        var properties = new Properties(); properties.load(new ByteArrayInputStream(manifest));
        var inventory = new java.util.HashSet<>(List.of(resource, root + support + "/build.properties"));
        for (String key : properties.stringPropertyNames()) {
            if (!key.startsWith("sha256.")) continue;
            String name = root + support + "/" + key.substring(7); byte[] bytes = entries.get(name);
            if (bytes == null || !BridgeGeneration.bytesDigest(bytes).equals(properties.getProperty(key))) throw new IOException("missing/changed Linux support source or runtime: " + name);
            inventory.add(name);
        }
        if (!inventory.equals(entries.keySet().stream().filter(name -> name.startsWith(root)).collect(java.util.stream.Collectors.toSet()))) {
            throw new IOException("Linux support inventory differs from pinned delivery");
        }
    }

    private static List<String> indexed(Map<String, String> metadata, String prefix) throws IOException {
        var result = new ArrayList<String>();
        for (int i = 0; metadata.containsKey(prefix + i); i++) result.add(required(metadata, prefix + i));
        if (result.isEmpty() || metadata.keySet().stream().filter(key -> key.startsWith(prefix)).count() != result.size()
                || result.stream().distinct().count() != result.size()) throw new IOException("invalid indexed metadata: " + prefix);
        return List.copyOf(result);
    }

    private static String required(Map<String, String> metadata, String key) throws IOException {
        String value = metadata.get(key);
        if (value == null || value.isBlank()) throw new IOException("missing bridge manifest property: " + key);
        return value;
    }

    private static Map<String, byte[]> common(Map<String, byte[]> entries, String support) {
        var result = new TreeMap<String, byte[]>();
        entries.forEach((name, bytes) -> {
            if (!name.startsWith(NATIVE) && !name.equals(support + ".class") && !name.equals(SOURCES + support + ".java")) result.put(name, bytes);
        });
        return result;
    }

    private static Map<String, String> commonMetadata(Map<String, String> metadata) {
        var result = new TreeMap<String, String>();
        metadata.forEach((key, value) -> { if (!key.startsWith("native.") && !key.startsWith("content.sha256.")) result.put(key, value); });
        return result;
    }
}
