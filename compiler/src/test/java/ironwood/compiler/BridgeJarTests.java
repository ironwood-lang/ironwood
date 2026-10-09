// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.bridge.BridgeGeneration;
import ironwood.compiler.source.SourceFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * M6.2's Bridge JAR writer profile: the port's BridgeJar against the Java
 * baseline's BridgeJarArchive.publish. Native jars hold the same entries in
 * the same order with the same bytes, spelled as Java's ZipOutputStream
 * spells STORED entries, and open in ZipFile, JarFile, JarInputStream, a
 * class loader and the jar tool; invalid contents, manifests and
 * destinations give the publisher's messages and keep an earlier jar; every
 * allocation failure keeps the earlier jar and leaves no stage.
 */
final class BridgeJarTests {
    private static final String PORT = PortFixtures.PORT;
    private static final List<String> SOURCES = List.of(PORT + "BridgeJar.iron", PORT + "JarManifest.iron",
            PORT + "ZipWriter.iron", PORT + "ZipArchive.iron", PORT + "Inflate.iron", PORT + "TextList.iron",
            PORT + "Bytes.iron", PORT + "Splits.iron");

    private BridgeJarTests() { }

    static String hex(String text) {
        StringBuilder out = new StringBuilder();
        for (char unit : text.toCharArray()) out.append(String.format("%04x", (int) unit));
        return out.length() == 0 ? "-" : out.toString();
    }

    static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte value : bytes) out.append(String.format("%02x", value & 0xFF));
        return out.length() == 0 ? "-" : out.toString();
    }

    static byte[] manifest(String... attributes) throws IOException {
        var manifest = new Manifest();
        for (int index = 0; index < attributes.length; index += 2) {
            manifest.getMainAttributes().putValue(attributes[index], attributes[index + 1]);
        }
        var output = new ByteArrayOutputStream();
        manifest.write(output);
        return output.toByteArray();
    }

    // A class Java can load from a jar: compiled here with the running JDK.
    static byte[] compiledClass(Path root) throws Exception {
        Path source = root.resolve("src/bridge/probe/Probe.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package bridge.probe; public final class Probe {"
                + " public static String answer() { return \"native jar\"; } }");
        Path classes = Files.createDirectories(root.resolve("probe-classes"));
        var compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        if (compiler.run(null, null, null, "--release", "21", "-d", classes.toString(), source.toString()) != 0) {
            throw new AssertionError("probe class did not compile");
        }
        return Files.readAllBytes(classes.resolve("bridge/probe/Probe.class"));
    }

    // Content sets shaped like the Bridge's paired artifact, companions and values jar.
    static List<TreeMap<String, byte[]>> contents(Path root) throws Exception {
        String generation = "0123456789abcdef".repeat(4);
        Random random = new Random(20261010);
        byte[] image = new byte[200_000];
        random.nextBytes(image);
        var paired = new TreeMap<String, byte[]>();
        paired.put("META-INF/MANIFEST.MF", manifest("Manifest-Version", "1.0", "Automatic-Module-Name",
                "ironwood.bridge.a" + generation, "Ironwood-Bridge-Schema", "1", "Ironwood-Bridge-Generation", generation));
        paired.put("bridge/probe/Probe.class", compiledClass(root));
        var metadata = new TreeMap<String, String>();
        metadata.put("schema", "1");
        metadata.put("generation", generation);
        metadata.put("native.resource", "META-INF/ironwood/native/macos-arm64/" + generation + "/libbridge.dylib");
        paired.put("META-INF/ironwood/bridge.properties", BridgePackageManifest.serialize(metadata));
        paired.put("META-INF/ironwood/native/macos-arm64/" + generation + "/libbridge.dylib", image);
        paired.put("META-INF/ironwood/java-sources/bridge/probe/Probe.java", "class Probe {}".getBytes(StandardCharsets.UTF_8));
        paired.put("META-INF/ironwood/licenses/LICENSE", "MIT OR Apache-2.0\n".getBytes(StandardCharsets.UTF_8));
        paired.put("META-INF/ironwood/licenses/é/中/😀.txt", new byte[0]);
        paired.put("META-INF/ironwood/javadoc/index.html", "<html></html>".getBytes(StandardCharsets.UTF_8));
        var companion = new TreeMap<String, byte[]>();
        companion.put("META-INF/MANIFEST.MF", manifest("Manifest-Version", "1.0", "Ironwood-Bridge-Generation", generation));
        companion.put("bridge/probe/Probe.java", "class Probe {}".getBytes(StandardCharsets.UTF_8));
        companion.put("META-INF/ironwood/licenses/LICENSE", "MIT\n".getBytes(StandardCharsets.UTF_8));
        var values = new TreeMap<String, byte[]>();
        values.put("META-INF/MANIFEST.MF", ("Manifest-Version: 1.0\r\nAutomatic-Module-Name: ironwood.bridge.values\r\n"
                + "Implementation-Version: 0.7.0\r\nIronwood-ByteView-ABI: 1\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        values.put("ironwood/bridge/ByteView.class", new byte[]{(byte) 0xca, (byte) 0xfe, (byte) 0xba, (byte) 0xbe});
        var many = new TreeMap<String, byte[]>();
        many.put("META-INF/MANIFEST.MF", manifest("Manifest-Version", "1.0"));
        for (int index = 0; index < 3000; index++) {
            byte[] bytes = new byte[random.nextInt(64)];
            random.nextBytes(bytes);
            many.put("data/" + (index % 37) + "/entry-" + index + ".bin", bytes);
        }
        return List.of(paired, companion, values, many);
    }

    static String spec(Map<String, byte[]> content, Path root, String label) throws IOException {
        StringBuilder spec = new StringBuilder();
        int file = 0;
        for (var entry : content.entrySet()) {
            if (entry.getValue().length > 4096) {
                Path data = root.resolve(label + "-content-" + file++);
                Files.write(data, entry.getValue());
                spec.append("file ").append(hex(entry.getKey())).append(' ').append(data).append('\n');
            } else {
                spec.append("entry ").append(hex(entry.getKey())).append(' ').append(hex(entry.getValue())).append('\n');
            }
        }
        Path path = root.resolve(label + ".spec");
        Files.writeString(path, spec.toString());
        return path.toString();
    }

    // Java's ZipOutputStream spelling of STORED entries with time 0, manifest first.
    static byte[] storedSpelling(Map<String, byte[]> content) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            List<String> names = new ArrayList<>();
            names.add("META-INF/MANIFEST.MF");
            for (String name : content.keySet()) if (!name.equals("META-INF/MANIFEST.MF")) names.add(name);
            for (String name : names) {
                byte[] data = content.get(name);
                var entry = new ZipEntry(name);
                entry.setMethod(ZipEntry.STORED);
                entry.setSize(data.length);
                entry.setCompressedSize(data.length);
                var crc = new CRC32();
                crc.update(data);
                entry.setCrc(crc.getValue());
                entry.setTime(0);
                zip.putNextEntry(entry);
                zip.write(data);
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    static List<String> names(Path jar) throws IOException {
        List<String> names = new ArrayList<>();
        try (var zip = new ZipFile(jar.toFile())) {
            zip.stream().forEach(entry -> names.add(entry.getName()));
        }
        return names;
    }

    static String tool(String name, List<String> arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", name).toString()));
        command.addAll(arguments);
        Process process = new ProcessBuilder(command).start();
        // Standard error stays apart: a JVM startup warning is not the tool's output.
        var error = new ByteArrayOutputStream();
        Thread drain = Thread.ofVirtual().start(() -> {
            try {
                process.getErrorStream().transferTo(error);
            } catch (IOException ignored) {
                // The failure message shows what arrived.
            }
        });
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = process.waitFor();
        drain.join();
        if (exit != 0) throw new AssertionError(name + " " + arguments + ": " + output + error.toString(StandardCharsets.UTF_8));
        return output;
    }

    /**
     * Native and Java jars hold the same entries, in the same order, with the
     * same bytes; the native jar is Java's STORED spelling byte for byte and
     * opens in ZipFile, JarFile, JarInputStream, a class loader and the jar
     * tool. Entry-content identities agree while whole-jar identities differ.
     */
    static void javaReaders() throws Exception {
        Path root = Files.createTempDirectory("ironwood-bridge-jar-").toRealPath();
        try {
            List<Path> programs = PortFixtures.links(root.resolve("build"), "compiler_bridge_jar", SOURCES);
            List<TreeMap<String, byte[]>> sets = contents(root);
            for (int set = 0; set < sets.size(); set++) {
                var content = sets.get(set);
                String spec = spec(content, root, "set" + set);
                Path javaJar = root.resolve("java/set" + set + ".jar");
                BridgeJarArchive.publish(javaJar, content);
                for (Path program : programs) {
                    Path nativeJar = root.resolve("native-" + program.getFileName() + "/set" + set + ".jar");
                    PortFixtures.execute(List.of(program.toString(), spec, nativeJar.toString()), null, 43, "published\n");
                    List<String> expected = names(javaJar);
                    if (!names(nativeJar).equals(expected)) throw new AssertionError("entry order differs for set " + set);
                    byte[] spelling = storedSpelling(content);
                    if (!Arrays.equals(Files.readAllBytes(nativeJar), spelling)) {
                        throw new AssertionError("native jar is not Java's STORED spelling for set " + set);
                    }
                    try (var zip = new ZipFile(nativeJar.toFile()); var javaZip = new ZipFile(javaJar.toFile())) {
                        for (String name : expected) {
                            var entry = zip.getEntry(name);
                            if (entry.getMethod() != ZipEntry.STORED || javaZip.getEntry(name).getMethod() != ZipEntry.DEFLATED) {
                                throw new AssertionError("unexpected methods for " + name);
                            }
                            byte[] bytes = zip.getInputStream(entry).readAllBytes();
                            if (!Arrays.equals(bytes, content.get(name))
                                    || !Arrays.equals(bytes, javaZip.getInputStream(javaZip.getEntry(name)).readAllBytes())) {
                                throw new AssertionError("entry bytes differ: " + name);
                            }
                            if (!BridgeGeneration.bytesDigest(bytes).equals(BridgeGeneration.bytesDigest(content.get(name)))) {
                                throw new AssertionError("content identity differs: " + name);
                            }
                        }
                    }
                    if (BridgeGeneration.bytesDigest(Files.readAllBytes(nativeJar))
                            .equals(BridgeGeneration.bytesDigest(Files.readAllBytes(javaJar)))) {
                        throw new AssertionError("STORED and DEFLATED jars unexpectedly share an identity");
                    }
                    try (var jar = new JarFile(nativeJar.toFile());
                         var stream = new JarInputStream(new ByteArrayInputStream(Files.readAllBytes(nativeJar)))) {
                        var attributes = stream.getManifest().getMainAttributes();
                        if (!"1.0".equals(attributes.getValue("Manifest-Version"))
                                || !jar.getManifest().getMainAttributes().equals(attributes)) {
                            throw new AssertionError("manifest differs through JarInputStream for set " + set);
                        }
                    }
                    String listing = tool("jar", List.of("-J-Dstdout.encoding=UTF-8", "tf", nativeJar.toString()));
                    if (!listing.equals(String.join("\n", expected) + "\n")) throw new AssertionError("jar tf listing differs");
                    if (set == 0) {
                        try (var loader = new URLClassLoader(new URL[]{nativeJar.toUri().toURL()}, null)) {
                            Object answer = loader.loadClass("bridge.probe.Probe").getMethod("answer").invoke(null);
                            if (!"native jar".equals(answer)) throw new AssertionError("class loaded from the jar answered " + answer);
                        }
                        String module = tool("jar", List.of("--describe-module", "--file", nativeJar.toString()));
                        if (!module.contains("ironwood.bridge.a" + "0123456789abcdef".repeat(4) + " automatic")) {
                            throw new AssertionError("automatic module name differs: " + module);
                        }
                    }
                }
            }
        } finally {
            PortFixtures.delete(root);
        }
    }

    static String javaOutcome(Path destination, Map<String, byte[]> content) {
        try {
            BridgeJarArchive.publish(destination, content);
            return "published\n";
        } catch (IOException failure) {
            return "error " + hex(failure.getMessage()) + "\n";
        }
    }

    static void requireNoStage(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) return;
        try (var entries = Files.list(directory)) {
            List<Path> stages = entries.filter(path -> path.getFileName().toString().startsWith(".ironwood-bridge-")).toList();
            if (!stages.isEmpty()) throw new AssertionError("stage left behind: " + stages);
        }
    }

    /**
     * Invalid names, missing, unversioned and malformed manifests, and
     * destinations that are not regular files give the Java publisher's
     * messages; the earlier jar survives and no stage is left. A valid
     * publication replaces the earlier jar.
     */
    static void failures() throws Exception {
        Path root = Files.createTempDirectory("ironwood-bridge-jar-failures-").toRealPath();
        try {
            Path program = PortFixtures.links(root.resolve("build"), "compiler_bridge_jar", SOURCES).getFirst();
            byte[] valid = manifest("Manifest-Version", "1.0");
            List<TreeMap<String, byte[]>> cases = new ArrayList<>();
            for (String name : List.of("", "/a", "a/", "a//b", "./a", "a/./b", "a/../b", "..", ".", "a\\b", "a\u0000b",
                    "a/.", "../a")) {
                var content = new TreeMap<String, byte[]>();
                content.put("META-INF/MANIFEST.MF", valid);
                content.put("b", new byte[]{1});
                content.put(name, new byte[]{2});
                cases.add(content);
            }
            for (byte[] manifest : List.of("Created-By: x\r\n\r\n".getBytes(StandardCharsets.UTF_8), manifest("Manifest-Version", "2.0"),
                    "Manifest-Version 1.0\r\n\r\n".getBytes(StandardCharsets.UTF_8),
                    "manifest-version: 1.0\r\n\r\n".getBytes(StandardCharsets.UTF_8), new byte[0],
                    "Manifest-Version: 1.0".getBytes(StandardCharsets.UTF_8), " x\n".getBytes(StandardCharsets.UTF_8))) {
                var content = new TreeMap<String, byte[]>();
                content.put("META-INF/MANIFEST.MF", manifest);
                content.put("a.txt", new byte[]{3});
                cases.add(content);
            }
            var missing = new TreeMap<String, byte[]>();
            missing.put("a.txt", new byte[]{4});
            cases.add(missing);
            var earlier = new TreeMap<String, byte[]>();
            earlier.put("META-INF/MANIFEST.MF", valid);
            earlier.put("earlier.txt", "earlier".getBytes(StandardCharsets.UTF_8));
            var later = new TreeMap<String, byte[]>();
            later.put("META-INF/MANIFEST.MF", valid);
            later.put("later.txt", "later".getBytes(StandardCharsets.UTF_8));
            int published = 0;
            for (int index = 0; index < cases.size(); index++) {
                Path directory = Files.createDirectories(root.resolve("case" + index));
                Path javaJar = directory.resolve("java.jar");
                Path nativeJar = directory.resolve("native.jar");
                BridgeJarArchive.publish(javaJar, earlier);
                Files.copy(javaJar, nativeJar);
                byte[] before = Files.readAllBytes(nativeJar);
                String expected = javaOutcome(javaJar, cases.get(index));
                PortFixtures.execute(List.of(program.toString(), spec(cases.get(index), root, "case" + index),
                        nativeJar.toString()), null, 43, expected);
                if (expected.equals("published\n")) {
                    // A lowercase manifest-version header is the same attribute name.
                    if (!names(nativeJar).equals(names(javaJar))) throw new AssertionError("case " + index + " entries differ");
                    published++;
                } else if (!Arrays.equals(Files.readAllBytes(nativeJar), before)) {
                    throw new AssertionError("case " + index + " replaced the jar");
                }
                requireNoStage(directory);
            }
            if (published != 1) throw new AssertionError(published + " invalid-content cases published");
            // Destinations that exist without following a final link and are not regular files.
            Path targets = Files.createDirectories(root.resolve("targets"));
            Path file = Files.writeString(targets.resolve("file.txt"), "x");
            List<Path> destinations = List.of(Files.createDirectories(targets.resolve("directory.jar")),
                    Files.createSymbolicLink(targets.resolve("link.jar"), file),
                    Files.createSymbolicLink(targets.resolve("dangling.jar"), targets.resolve("missing.jar")));
            for (Path destination : destinations) {
                String expected = javaOutcome(destination, later);
                PortFixtures.execute(List.of(program.toString(), spec(later, root, "later"), destination.toString()), null, 43,
                        expected);
                if (!expected.startsWith("error ")) throw new AssertionError("Java published to " + destination);
                requireNoStage(targets);
            }
            // A stage that cannot be created keeps the earlier jar.
            Path locked = Files.createDirectories(root.resolve("locked"));
            Path lockedJar = locked.resolve("bridge.jar");
            BridgeJarArchive.publish(lockedJar, earlier);
            byte[] before = Files.readAllBytes(lockedJar);
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("r-xr-xr-x"));
            try {
                String output = new String(new ProcessBuilder(program.toString(), spec(later, root, "locked"), lockedJar.toString())
                        .redirectErrorStream(true).start().getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                if (!output.startsWith("error ")) throw new AssertionError("publication into a read-only directory: " + output);
            } finally {
                Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwxr-xr-x"));
            }
            if (!Arrays.equals(Files.readAllBytes(lockedJar), before)) throw new AssertionError("read-only directory lost its jar");
            requireNoStage(locked);
            // A valid publication replaces an earlier jar, and its parent is created.
            Path replaced = root.resolve("new/parent/bridge.jar");
            Files.createDirectories(replaced.getParent());
            BridgeJarArchive.publish(replaced, earlier);
            PortFixtures.execute(List.of(program.toString(), spec(later, root, "replace"), replaced.toString()), null, 43,
                    "published\n");
            if (!names(replaced).equals(List.of("META-INF/MANIFEST.MF", "later.txt"))) throw new AssertionError("jar not replaced");
            Path created = root.resolve("created/deeper/bridge.jar");
            PortFixtures.execute(List.of(program.toString(), spec(later, root, "create"), created.toString()), null, 43,
                    "published\n");
            requireNoStage(created.getParent());
        } finally {
            PortFixtures.delete(root);
        }
    }

    /**
     * Raises the allocation limit from zero until a publication completes:
     * every stopped run unwinds its allocations, keeps the earlier jar and
     * leaves no stage, and the completed run replaces the jar.
     */
    static void allocationFailures() throws Exception {
        Path root = Files.createTempDirectory("ironwood-bridge-jar-sweep-").toRealPath();
        try {
            var earlier = new TreeMap<String, byte[]>();
            earlier.put("META-INF/MANIFEST.MF", manifest("Manifest-Version", "1.0"));
            earlier.put("earlier.txt", "earlier".getBytes(StandardCharsets.UTF_8));
            var later = new TreeMap<String, byte[]>();
            later.put("META-INF/MANIFEST.MF", manifest("Manifest-Version", "1.0", "Ironwood-Bridge-Generation", "g"));
            later.put("é/later.txt", "later".getBytes(StandardCharsets.UTF_8));
            later.put("data.bin", new byte[5000]);
            Path directory = Files.createDirectories(root.resolve("out"));
            Path jar = directory.resolve("bridge.jar");
            BridgeJarArchive.publish(jar, earlier);
            byte[] before = Files.readAllBytes(jar);
            String spec = spec(later, root, "sweep");
            for (Path program : PortFixtures.links(root.resolve("build"), "compiler_bridge_jar", SOURCES)) {
                int limit = 0;
                while (true) {
                    Files.write(jar, before);
                    int exit = PortFixtures.execute(List.of(program.toString(), spec, jar.toString()), limit, -1, null);
                    if (exit != 42) {
                        if (exit != 43) throw new AssertionError("limit " + limit + " exit " + exit);
                        break;
                    }
                    if (!Arrays.equals(Files.readAllBytes(jar), before)) throw new AssertionError("limit " + limit + " lost the jar");
                    requireNoStage(directory);
                    limit++;
                }
                if (limit < 20 || !names(jar).equals(List.of("META-INF/MANIFEST.MF", "data.bin", "é/later.txt"))) {
                    throw new AssertionError("sweep ended at limit " + limit);
                }
                requireNoStage(directory);
            }
        } finally {
            PortFixtures.delete(root);
        }
    }

    static byte[] zip(List<String[]> entries, boolean stored) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            for (String[] pair : entries) {
                byte[] data = pair[1].getBytes(StandardCharsets.UTF_8);
                var entry = new ZipEntry(pair[0]);
                if (stored) {
                    entry.setMethod(ZipEntry.STORED);
                    entry.setSize(data.length);
                    var crc = new CRC32();
                    crc.update(data);
                    entry.setCrc(crc.getValue());
                }
                zip.putNextEntry(entry);
                zip.write(data);
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    static String validateOutcome(Map<String, String> metadata, byte[] companion) {
        var entries = new TreeMap<String, byte[]>();
        if (companion != null) entries.put(ironwood.compiler.bridge.BridgeByteViewSources.RESOURCE, companion);
        try {
            BridgeValuesLibrary.validate(metadata, entries);
            return "valid\n";
        } catch (IOException failure) {
            return "error " + hex(failure.getMessage()) + "\n";
        }
    }

    /**
     * Values companions built by the Java publisher and by the native writer,
     * and handcrafted variants of their manifests and layout, give
     * BridgeValuesLibrary.validate's verdicts under matching and mismatching
     * pairing metadata; a distribution stage gives
     * BridgeDistributionCommand's bridge-distribution.properties. The native
     * companion's identity differs from the Java one's, so their metadata
     * differ while each validates.
     */
    static void dependencyManifests() throws Exception {
        Path root = Files.createTempDirectory("ironwood-bridge-values-").toRealPath();
        try {
            Path program = PortFixtures.links(root.resolve("build"), "compiler_bridge_values",
                    List.of(PORT + "BridgeIdentity.iron", PORT + "BridgePatterns.iron", PORT + "BridgeProperties.iron",
                            PORT + "FileCollector.iron", PORT + "Inflate.iron", PORT + "JarManifest.iron",
                            PORT + "JarStreams.iron", PORT + "TextMap.iron",
                            PORT + "TextList.iron", PORT + "Sha256.iron", PORT + "Bytes.iron", PORT + "ZipStream.iron",
                            PORT + "ZipArchive.iron")).getFirst();
            Path jarProgram = PortFixtures.links(root.resolve("jar-build"), "compiler_bridge_jar", SOURCES).getFirst();
            var licenses = new TreeMap<String, byte[]>();
            for (String name : List.of("LICENSE", "LICENSE-MIT", "LICENSE-APACHE")) {
                licenses.put("META-INF/ironwood/licenses/" + name, (name + "\n").getBytes(StandardCharsets.UTF_8));
            }
            Path stage = Files.createDirectories(root.resolve("values-stage"));
            byte[] javaCompanion = BridgeValuesLibrary.packageClass(stage, new byte[]{(byte) 0xca, (byte) 0xfe}, licenses);
            String version;
            var companionEntries = new TreeMap<String, byte[]>();
            try (var zip = new ZipFile(stage.resolve("ironwood-bridge-values.jar").toFile())) {
                version = new Manifest(zip.getInputStream(zip.getEntry("META-INF/MANIFEST.MF")))
                        .getMainAttributes().getValue("Implementation-Version");
                for (var entry : zip.stream().toList()) companionEntries.put(entry.getName(), zip.getInputStream(entry).readAllBytes());
            }
            Path nativePath = root.resolve("native-values/ironwood-bridge-values.jar");
            PortFixtures.execute(List.of(jarProgram.toString(), spec(companionEntries, root, "values"), nativePath.toString()),
                    null, 43, "published\n");
            byte[] nativeCompanion = Files.readAllBytes(nativePath);
            if (Arrays.equals(nativeCompanion, javaCompanion)) throw new AssertionError("STORED companion equals the DEFLATED one");
            String goodManifest = "Manifest-Version: 1.0\r\nAutomatic-Module-Name: ironwood.bridge.values\r\nImplementation-Version: "
                    + version + "\r\nIronwood-ByteView-ABI: 1\r\n\r\n";
            List<byte[]> companions = new ArrayList<>(List.of(javaCompanion, nativeCompanion));
            for (boolean stored : new boolean[]{false, true}) {
                companions.add(zip(List.of(new String[]{"META-INF/", ""}, new String[]{"META-INF/MANIFEST.MF", goodManifest}), stored));
                companions.add(zip(List.<String[]>of(new String[]{"meta-inf/manifest.mf", goodManifest}), stored));
                companions.add(zip(List.<String[]>of(new String[]{"META-INF/MANIFEſT.MF", goodManifest}), stored));
                companions.add(zip(List.of(new String[]{"META-ıNF/", ""}, new String[]{"META-INF/MANIFEST.MF", goodManifest}), stored));
                companions.add(zip(List.of(new String[]{"a", "x"}, new String[]{"META-INF/MANIFEST.MF", goodManifest}), stored));
                companions.add(zip(List.<String[]>of(new String[]{"META-INF/MANIFEST.MF", goodManifest.replace(version, "9.9")}), stored));
                companions.add(zip(List.<String[]>of(new String[]{"META-INF/MANIFEST.MF",
                        goodManifest.replace("ABI: 1", "ABI: 2")}), stored));
                companions.add(zip(List.<String[]>of(new String[]{"META-INF/MANIFEST.MF",
                        goodManifest.replace("ironwood.bridge.values", "other")}), stored));
                companions.add(zip(List.<String[]>of(new String[]{"META-INF/MANIFEST.MF", "Manifest-Version 1.0\r\n"}), stored));
                companions.add(zip(List.<String[]>of(new String[]{"META-INF/MANIFEST.MF", ""}), stored));
                companions.add(zip(List.<String[]>of(new String[]{"META-INF/MANIFEST.MF",
                        goodManifest.replace("Implementation-Version", "implementation-version")}), stored));
            }
            companions.add(zip(List.of(), true));
            companions.add(new byte[]{1, 2, 3});
            int cases = 0;
            for (byte[] companion : companions) {
                String digest = BridgeGeneration.bytesDigest(companion);
                List<Map<String, String>> metadatas = List.of(
                        Map.of("java.values.abi", "1", "java.values.version", version, "java.values.sha256", digest),
                        Map.of("java.values.abi", "1", "java.values.version", version, "java.values.sha256", "0".repeat(64)),
                        Map.of("java.values.abi", "2", "java.values.version", version, "java.values.sha256", digest),
                        Map.of("java.values.abi", "1", "java.values.sha256", digest),
                        Map.of("java.values.abi", "1", "java.values.version", "-x", "java.values.sha256", digest),
                        Map.of("java.values.abi", "1", "java.values.version", "9.9", "java.values.sha256", digest));
                for (Map<String, String> metadata : metadatas) {
                    Path metadataFile = Files.write(root.resolve("metadata-" + cases + ".properties"),
                            BridgePackageManifest.serialize(metadata));
                    Path companionFile = Files.write(root.resolve("companion-" + cases + ".jar"), companion);
                    PortFixtures.execute(List.of(program.toString(), "validate", metadataFile.toString(), companionFile.toString()),
                            null, 43, validateOutcome(metadata, companion));
                    cases++;
                }
            }
            for (Map<String, String> metadata : List.of(Map.of("java.values.version", "1"), Map.of("schema", "1"))) {
                Path metadataFile = Files.write(root.resolve("metadata-absent-" + cases++ + ".properties"),
                        BridgePackageManifest.serialize(metadata));
                PortFixtures.execute(List.of(program.toString(), "validate", metadataFile.toString(), "absent"), null, 43,
                        validateOutcome(metadata, null));
            }
            // The distribution inventory of a stage holding jars, a POM, a link and a subdirectory.
            Path distribution = Files.createDirectories(root.resolve("distribution"));
            Files.copy(nativePath, distribution.resolve("ironwood-bridge-values-" + version + ".jar"));
            Files.write(distribution.resolve("artifact-1.0.jar"), javaCompanion);
            Files.writeString(distribution.resolve("artifact-1.0.pom"), "<project/>\n");
            Files.writeString(distribution.resolve("artifact-1.0-sources.jar"), "sources");
            Files.createDirectories(distribution.resolve("sub"));
            Files.writeString(distribution.resolve("sub/hidden.jar"), "hidden");
            Files.createSymbolicLink(distribution.resolve("link.pom"), distribution.resolve("artifact-1.0.pom"));
            var inventory = new TreeMap<String, String>();
            inventory.put("generation", "g".repeat(64));
            inventory.put("api", "a".repeat(64));
            inventory.put("maven.coordinates", "org.example:artifact:1.0");
            try (var files = Files.list(distribution)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    inventory.put("sha256." + file.getFileName(), BridgeGeneration.bytesDigest(Files.readAllBytes(file)));
                }
            }
            PortFixtures.execute(List.of(program.toString(), "distribution", distribution.toString(), "g".repeat(64),
                    "a".repeat(64), "org.example:artifact:1.0"), null, 43, hex(BridgePackageManifest.serialize(inventory)) + "\n");
            // BridgeAssembler.assemble's host targets: reversed, duplicate and empty sets.
            for (List<String> targets : List.of(List.of("linux-x86_64", "linux-arm64", "macos-arm64"),
                    List.of("macos-arm64", "linux-arm64"), List.of("linux-arm64", "macos-arm64", "linux-arm64"), List.<String>of(),
                    List.of("b", "B", "a"))) {
                var hosts = new TreeMap<String, String>();
                StringBuilder expected = new StringBuilder();
                String duplicate = null;
                for (String target : targets) {
                    if (hosts.putIfAbsent(target, target) != null) {
                        duplicate = "duplicate assembly target: " + target;
                        break;
                    }
                }
                if (duplicate != null) {
                    expected.append("error ").append(hex(duplicate)).append('\n');
                } else if (hosts.isEmpty()) {
                    expected.append("error assembly requires host artifacts\n");
                } else {
                    expected.append("first ").append(hosts.firstEntry().getKey()).append('\n');
                    for (String target : hosts.keySet()) expected.append("  ").append(target).append('\n');
                }
                List<String> command = new ArrayList<>(List.of(program.toString(), "targets"));
                command.addAll(targets);
                PortFixtures.execute(command, null, 43, expected.toString());
            }
        } finally {
            PortFixtures.delete(root);
        }
    }

    static TreeMap<String, byte[]> tree(Path root) throws IOException {
        var files = new TreeMap<String, byte[]>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) files.put(root.relativize(path).toString(), Files.readAllBytes(path));
        }
        return files;
    }

    static boolean sameTree(TreeMap<String, byte[]> first, TreeMap<String, byte[]> second) {
        if (!first.keySet().equals(second.keySet())) return false;
        for (String name : first.keySet()) if (!Arrays.equals(first.get(name), second.get(name))) return false;
        return true;
    }

    /** Contents are copied in; jars, contents and fetched copies belong to their owners. */
    static void ownership() throws Exception {
        List<SourceFile> helpers = PortFixtures.portSources(List.of("BridgeJar", "JarManifest", "JarStreams", "ZipWriter",
                "ZipArchive", "ZipStream", "Inflate", "TextList", "Bytes"));
        String prefix = "import ironwood.compiler.port.*;\nclass Main { public static int main(String[] args)"
                + " throws ironwood.io.IOException { String name = new String(args[0]); byte[] data = new byte[4];"
                + " BridgeJar jar = new BridgeJar(); jar.put(name, data, 0, 4); ";
        for (UnfreedMode mode : UnfreedMode.values()) {
            PortFixtures.require(mode, helpers, prefix + "free data; byte[] copy = jar.get(name); free name;"
                    + " ironwood.nio.file.Path out = ironwood.nio.file.Path.of(args[1]); jar.publish(out); free out;"
                    + " free jar; int r = copy.length; free copy; return r; }}", null);
            PortFixtures.require(mode, helpers, prefix + "byte[] copy = jar.get(name); free copy; free data; free name;"
                    + " free jar; return copy.length; }}", "after its allocation was freed");
            PortFixtures.require(mode, helpers, prefix + "free jar; free jar; free data; free name; return 0; }}",
                    "allocation was already freed");
            PortFixtures.require(mode, helpers, prefix + "free jar; free name; Inflate inflate = new Inflate(); JarManifest"
                    + " manifest = JarStreams.manifest(data, inflate, \"values\"); free data; free inflate; int r = 0;"
                    + " if (manifest != null) { r = manifest.size(); free manifest; } return r; }}", null);
        }
    }
}
