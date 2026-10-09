// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.source.SourceFile;

import java.io.ByteArrayOutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * M5.2's codec and writer evaluation (D275): the port's raw DEFLATE decoder
 * against Java 21's Inflater, and the STORED writer against Java's
 * ZipOutputStream bytes, readers and JAR tools; ownership and allocation
 * failure for both.
 */
final class CodecTests {
    private static final String EVIDENCE = "docs/self-hosting/m5/inflate-evidence/";
    private static final Path FIXTURES = Path.of("docs/self-hosting/m5/archive-evidence/fixtures");

    private CodecTests() { }

    static void inflateDifferential() throws Exception {
        Path root = Files.createTempDirectory("ironwood-inflate-");
        try {
            Path corpus = root.resolve("corpus");
            String expected = PortFixtures.reference(EVIDENCE + "InflateCorpus.java", List.of(),
                    List.of(corpus.toString()));
            List<String> command = new ArrayList<>();
            try (var files = Files.list(corpus)) {
                files.map(Path::toString).sorted().forEach(command::add);
            }
            if (command.size() != expected.split("\n").length) throw new AssertionError("inflate corpus size");
            for (Path executable : PortFixtures.links(root, "compiler_inflate",
                    List.of(PortFixtures.PORT + "Inflate.iron", PortFixtures.PORT + "Sha256.iron"))) {
                List<String> run = new ArrayList<>(List.of(executable.toString()));
                run.addAll(command);
                PortFixtures.execute(run, null, 42, expected);
            }
        } finally {
            PortFixtures.delete(root);
        }
    }

    /**
     * The native writer reproduces the frozen archive and Java's STORED bytes
     * for class artifacts, Unicode and empty entries, an empty archive and
     * the ZIP64 count threshold, and its class artifact and JAR open in
     * Java's readers, the jar tool and a class loader.
     */
    static void writerInterop() throws Exception {
        Path root = Files.createTempDirectory("ironwood-zip-writer-");
        try {
            List<Path> executables = PortFixtures.links(root, "compiler_zip_writer",
                    List.of(PortFixtures.PORT + "ZipWriter.iron"));
            for (Path executable : executables) {
                Path work = root.resolve(executable.getFileName() + "-work");
                Files.createDirectories(work);
                byte[] frozen = Files.readAllBytes(FIXTURES.resolve("lib.ironjar"));
                if (!Arrays.equals(frozen, write(executable, work, "lib", entries(frozen)))) {
                    throw new AssertionError("native writer does not reproduce the frozen lib.ironjar");
                }
                List<Map.Entry<String, byte[]>> classEntries = entries(Files.readAllBytes(FIXTURES.resolve("classes/p/A.ironclass")));
                byte[] storedClass = write(executable, work, "A", classEntries);
                if (!Arrays.equals(storedClass, javaStored(classEntries))) {
                    throw new AssertionError("native STORED class artifact differs from Java's STORED bytes");
                }
                Path classFile = work.resolve("A.ironclass");
                Files.write(classFile, storedClass);
                IronClass ironClass = IronClass.read(classFile);
                if (!ironClass.declaredTypes().contains("p.A") || !"p.A".equals(ironClass.entryPoint().orElse(null))
                        || !ironClass.source("p.A").orElseThrow().content()
                        .equals(Files.readString(FIXTURES.resolve("src/p/A.iron")))) {
                    throw new AssertionError("Java's IronClass reader decodes the STORED artifact differently");
                }
                List<Map.Entry<String, byte[]>> mixed = new ArrayList<>();
                mixed.add(Map.entry("café/中文 😀.txt", "text".getBytes(StandardCharsets.UTF_8)));
                mixed.add(Map.entry("empty", new byte[0]));
                byte[] every = new byte[256];
                for (int value = 0; value < 256; value++) every[value] = (byte) value;
                mixed.add(Map.entry("bytes", every));
                if (!Arrays.equals(write(executable, work, "mixed", mixed), javaStored(mixed))
                        || !Arrays.equals(write(executable, work, "none", List.of()), javaStored(List.of()))) {
                    throw new AssertionError("native writer differs from Java for Unicode, empty or no entries");
                }
                for (int count : new int[]{65534, 65535, 65536}) {
                    Path output = work.resolve("synthetic-" + count + ".zip");
                    PortFixtures.execute(List.of(executable.toString(), "--synthetic", output.toString(),
                            Integer.toString(count)), null, 42, "");
                    List<Map.Entry<String, byte[]>> synthetic = new ArrayList<>();
                    for (int index = 0; index < count; index++) synthetic.add(Map.entry(String.format("e%05d", index), new byte[0]));
                    if (!Arrays.equals(Files.readAllBytes(output), javaStored(synthetic))) {
                        throw new AssertionError("native writer differs from Java at " + count + " entries");
                    }
                    try (ZipFile zip = new ZipFile(output.toFile())) {
                        if (zip.size() != count) throw new AssertionError("ZipFile counts " + zip.size());
                    }
                }
                bridgeShapedJar(executable, work);
            }
        } finally {
            PortFixtures.delete(root);
        }
    }

    // A STORED jar with its manifest first: JarFile, JarInputStream, the jar
    // tool, java -jar and a class loader all accept it.
    private static void bridgeShapedJar(Path executable, Path work) throws Exception {
        Path sources = work.resolve("hello-src");
        Files.createDirectories(sources);
        Files.writeString(sources.resolve("Hello.java"), """
                public final class Hello {
                    public static void main(String[] args) {
                        System.out.println("hello from a STORED jar");
                    }
                }
                """);
        Path classes = work.resolve("hello-classes");
        tool(List.of(jdkTool("javac"), "--release", "21", "-d", classes.toString(),
                sources.resolve("Hello.java").toString()), work);
        List<Map.Entry<String, byte[]>> jarEntries = new ArrayList<>();
        jarEntries.add(Map.entry("META-INF/MANIFEST.MF",
                "Manifest-Version: 1.0\r\nMain-Class: Hello\r\nCreated-By: Ironwood\r\n\r\n".getBytes(StandardCharsets.UTF_8)));
        jarEntries.add(Map.entry("Hello.class", Files.readAllBytes(classes.resolve("Hello.class"))));
        Path jar = work.resolve("hello.jar");
        Files.write(jar, write(executable, work, "hello", jarEntries));
        try (JarFile file = new JarFile(jar.toFile())) {
            if (!"Hello".equals(file.getManifest().getMainAttributes().getValue("Main-Class"))) {
                throw new AssertionError("JarFile manifest");
            }
        }
        try (JarInputStream stream = new JarInputStream(Files.newInputStream(jar))) {
            if (stream.getManifest() == null || !"Hello.class".equals(stream.getNextJarEntry().getName())) {
                throw new AssertionError("JarInputStream manifest or entry");
            }
        }
        if (!tool(List.of(jdkTool("jar"), "tf", jar.toString()), work).equals("META-INF/MANIFEST.MF\nHello.class\n")) {
            throw new AssertionError("jar tool listing");
        }
        if (!tool(List.of(jdkTool("java"), "-jar", jar.toString()), work).equals("hello from a STORED jar\n")) {
            throw new AssertionError("java -jar output");
        }
        try (URLClassLoader loader = new URLClassLoader(new URL[]{jar.toUri().toURL()}, null)) {
            if (!loader.loadClass("Hello").getName().equals("Hello")) throw new AssertionError("class loader");
        }
    }

    private static String jdkTool(String name) {
        return Path.of(System.getProperty("java.home"), "bin", name).toString();
    }

    // Runs a JDK tool with inherited options cleared; standard error is discarded.
    private static String tool(List<String> command, Path directory) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile());
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        for (String variable : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")) {
            builder.environment().remove(variable);
        }
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(120, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new AssertionError(command + " failed: " + output);
        }
        return output;
    }

    // The decoded entries of an archive, in central-directory order.
    private static List<Map.Entry<String, byte[]>> entries(byte[] archive) throws Exception {
        List<Map.Entry<String, byte[]>> result = new ArrayList<>();
        for (ZipBytes.Record record : ZipBytes.parse(archive)) result.add(Map.entry(record.name(), record.data()));
        return result;
    }

    // Writes the entries' data to files and runs the native writer over them.
    private static byte[] write(Path executable, Path work, String name, List<Map.Entry<String, byte[]>> entries)
            throws Exception {
        Path data = work.resolve(name + "-data");
        Files.createDirectories(data);
        Path output = work.resolve(name + ".zip");
        List<String> command = new ArrayList<>(List.of(executable.toString(), output.toString()));
        for (int index = 0; index < entries.size(); index++) {
            Path file = data.resolve(Integer.toString(index));
            Files.write(file, entries.get(index).getValue());
            command.add(entries.get(index).getKey());
            command.add(file.toString());
        }
        PortFixtures.execute(command, null, 42, "");
        return Files.readAllBytes(output);
    }

    // Java's ZipOutputStream spelling of STORED entries with explicit size and CRC and time 0.
    static byte[] javaStored(List<Map.Entry<String, byte[]>> entries) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> item : entries) {
                byte[] content = item.getValue();
                CRC32 crc = new CRC32();
                crc.update(content);
                ZipEntry entry = new ZipEntry(item.getKey());
                entry.setMethod(ZipEntry.STORED);
                entry.setSize(content.length);
                entry.setCompressedSize(content.length);
                entry.setCrc(crc.getValue());
                entry.setTime(0);
                zip.putNextEntry(entry);
                zip.write(content);
                zip.closeEntry();
            }
        }
        byte[] result = bytes.toByteArray();
        try (ZipInputStream check = new ZipInputStream(new java.io.ByteArrayInputStream(result))) {
            while (check.getNextEntry() != null) check.readAllBytes();
        }
        return result;
    }

    /** Inputs are borrowed for each call; results and the helpers belong to the caller. */
    static void ownership() throws Exception {
        List<SourceFile> helpers = PortFixtures.portSources(List.of("Inflate", "ZipWriter"));
        String prefix = """
                import ironwood.compiler.port.*;
                import ironwood.io.IOException;
                class Main { public static int main(String[] args) { try {
                """;
        String suffix = " } catch (IOException failure) { return 1; } }}";
        String stream = "byte[] input = new byte[6]; input[0] = 1; input[1] = 1; input[3] = (byte) 0xFE;"
                + " input[4] = (byte) 0xFF; input[5] = 9;";
        for (UnfreedMode mode : UnfreedMode.values()) {
            PortFixtures.require(mode, helpers, prefix + stream + " Inflate inflate = new Inflate();"
                    + " byte[] decoded = inflate.inflate(input, 0, 6, 100); byte[] exact = new byte[1];"
                    + " inflate.inflate(input, 0, 6, exact); free input; int r = decoded[0] + exact[0];"
                    + " free decoded; free exact; free inflate; return r;" + suffix, null);
            PortFixtures.require(mode, helpers, prefix + stream + " Inflate inflate = new Inflate(); free inflate;"
                    + " byte[] decoded = inflate.inflate(input, 0, 6, 100); free decoded; free input; return 0;"
                    + suffix, "after its allocation was freed");
            PortFixtures.require(mode, helpers, prefix + stream + " Inflate inflate = new Inflate();"
                    + " byte[] decoded = inflate.inflate(input, 0, 6, 100); free decoded; free decoded;"
                    + " free inflate; free input; return 0;" + suffix, "allocation was already freed");
            PortFixtures.require(mode, helpers, prefix + "byte[] data = new byte[3]; ZipWriter writer = new ZipWriter();"
                    + " writer.add(\"a\", data, 0, 3); free data; byte[] archive = writer.finish(); int r = archive.length;"
                    + " free archive; free writer; return r;" + suffix, null);
            PortFixtures.require(mode, helpers, prefix + "byte[] data = new byte[3]; ZipWriter writer = new ZipWriter();"
                    + " free data; writer.add(\"a\", data, 0, 3); free writer; return 0;" + suffix,
                    "after its allocation was freed");
            PortFixtures.require(mode, helpers, prefix + "ZipWriter writer = new ZipWriter(); byte[] archive = writer.finish();"
                    + " free writer; free archive; free archive; return 0;" + suffix, "allocation was already freed");
        }
    }

    /** Every allocation failure unwinds to the baseline. */
    static void failures() throws Exception {
        Path root = Files.createTempDirectory("ironwood-codec-failure-");
        try {
            for (Path executable : PortFixtures.links(root, "compiler_codec_failure",
                    List.of(PortFixtures.PORT + "Inflate.iron", PortFixtures.PORT + "ZipWriter.iron"))) {
                PortFixtures.sweep(executable, List.of(), 10);
            }
        } finally {
            PortFixtures.delete(root);
        }
    }
}
