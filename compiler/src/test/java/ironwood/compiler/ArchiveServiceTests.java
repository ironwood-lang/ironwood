// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.source.SourceFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

/**
 * M5.3's artifact integration (D275): the port's IronClass and IronJar
 * services against the Java baseline in both directions, the frozen M5.1
 * corpus, creation diagnostics, staged publication, ownership, allocation
 * failure and standard-library discovery.
 */
final class ArchiveServiceTests {
    private static final String PORT = PortFixtures.PORT;
    private static final Path FIXTURES = ArchiveContractTests.FIXTURES;
    private static final List<String> SERVICES = List.of("TextList", "ZipArchive", "ZipStream", "IronClassArtifact",
            "IronJarArchive", "Inflate", "ZipWriter", "Splits", "Bytes", "FileCollector", "ArchiveEntries");
    private static final List<String> CLASS_FIXTURES = ArchiveContractTests.CLASS_FILES;

    // Corpus variants where the native policy of D275 deliberately differs from
    // the frozen Java verdict: CRC and size are verified for every entry read,
    // and the first missing indexed entry is named in index order.
    private static final Map<String, String> POLICY = Map.of(
            "jar.crc-mismatch-payload", "jar.crc-mismatch-payload ok entries=META-INF/IRONWOOD.MF,META-INF/LICENSES/LICENSE,"
                    + "META-INF/types.tsv,p/A.ironclass,q/C.ironclass\n  source p.A ok path=<root>/jar/crc-mismatch-payload"
                    + ".ironjar!/p/A.ironclass!/source/A.iron source=42b6b004e3180ac2\n  source q.C error <container>",
            "jar.crc-mismatch-index", "jar.crc-mismatch-index error <container>",
            "jar.central-size-short", "jar.central-size-short error <container>",
            "jar.central-size-long", "jar.central-size-long error <container>",
            "jar.indexed-missing-two", "jar.indexed-missing-two error missing indexed Ironwood class entry 'p/B.ironclass'"
                    + " in <root>/jar/indexed-missing-two.ironjar");

    private ArchiveServiceTests() { }

    private static List<String> sources(String... extra) {
        List<String> result = new ArrayList<>();
        for (String name : SERVICES) result.add(PORT + name + ".iron");
        for (String name : extra) result.add(PORT + name + ".iron");
        return result;
    }

    /**
     * Every corpus variant gets Java's verdict: profile messages exactly,
     * container failures as failures, and the D275 policy differences above.
     */
    static void corpusVerdicts() throws Exception {
        Path root = Files.createTempDirectory("ironwood-archive-services-corpus-");
        try {
            Path corpus = root.resolve("corpus");
            String frozen = Files.readString(Path.of(ArchiveContractTests.EVIDENCE + "java-verdicts.txt"));
            if (!ArchiveCorpus.javaVerdicts(corpus).equals(frozen)) throw new AssertionError("Java corpus verdicts changed");
            List<String> arguments = new ArrayList<>(List.of("--corpus"));
            for (ArchiveCorpus.Variant variant : ArchiveCorpus.CLASSES) {
                arguments.add(variant.name());
                arguments.add(corpus.resolve("class").resolve(variant.name().substring(6) + ".ironclass").toString());
            }
            for (ArchiveCorpus.Variant variant : ArchiveCorpus.JARS) {
                arguments.add(variant.name());
                arguments.add(corpus.resolve("jar").resolve(ArchiveCorpus.fileName(variant.name()) + ".ironjar").toString());
            }
            String expected = expectedNative(frozen);
            String rootText = corpus.toAbsolutePath().normalize().toString();
            for (Path executable : PortFixtures.links(root, "compiler_archive_reader", sources("Sha256"))) {
                List<String> command = new ArrayList<>(List.of(executable.toString()));
                command.addAll(arguments);
                String actual = normalize(run(command, null, Map.of(), 42), rootText);
                if (!actual.equals(expected)) {
                    throw new AssertionError("native corpus verdicts differ: " + ArchiveContractTests.difference(expected, actual));
                }
            }
        } finally {
            PortFixtures.delete(root);
        }
    }

    // The frozen Java transcript as the native readers must print it.
    static String expectedNative(String frozen) {
        List<String> blocks = new ArrayList<>();
        for (String line : frozen.split("\n")) {
            String mapped = mapJava(line);
            if (line.startsWith("  ")) blocks.set(blocks.size() - 1, blocks.getLast() + "\n" + mapped);
            else blocks.add(mapped);
        }
        StringBuilder result = new StringBuilder();
        for (String block : blocks) {
            String label = block.split(" (ok|error) ", 2)[0];
            result.append(POLICY.getOrDefault(label, block)).append('\n');
        }
        return result.toString();
    }

    private static String mapJava(String line) {
        int error = line.indexOf(" error ");
        int ok = line.indexOf(" ok ");
        if (error < 0 || ok >= 0 && ok < error) return line;
        String verdict = line.substring(error + 7);
        String head = line.substring(0, error) + " error ";
        if (verdict.startsWith("java.io.IOException: ")) return head + verdict.substring("java.io.IOException: ".length());
        return head + "<container>";
    }

    // Native output with the corpus root shown as <root>, control characters
    // escaped as the Java transcript escapes them, and container failures marked.
    private static String normalize(String output, String rootText) {
        StringBuilder result = new StringBuilder();
        for (String line : output.replace(rootText, "<root>").split("\n")) {
            String visible = ArchiveCorpus.visible(line);
            int error = visible.indexOf(" error invalid ZIP data in ");
            result.append(error >= 0 ? visible.substring(0, error) + " error <container>" : visible).append('\n');
        }
        return result.toString();
    }

    /**
     * Java-written artifacts read natively: every standard-library class
     * artifact, the frozen fixtures, the standard-library archive (DEFLATED
     * class payloads in STORED entries) and the frozen archive give Java's
     * types, entry points, source paths and sources.
     */
    static void javaWriterNativeReader() throws Exception {
        Path root = Files.createTempDirectory("ironwood-archive-services-java-native-");
        try {
            List<Path> classFiles = new ArrayList<>();
            try (var paths = Files.walk(Path.of("compiler/build/stdlib"))) {
                paths.filter(path -> path.toString().endsWith(".ironclass")).sorted().forEach(classFiles::add);
            }
            for (String name : CLASS_FIXTURES) classFiles.add(FIXTURES.resolve(name));
            List<String> command = new ArrayList<>(List.of("--class"));
            StringBuilder expected = new StringBuilder();
            for (Path file : classFiles) {
                String spelling = file.toAbsolutePath().normalize().toString();
                command.add(spelling);
                expected.append(spelling).append(' ').append(javaClass(IronClass.read(file))).append('\n');
            }
            List<Path> executables = PortFixtures.links(root, "compiler_archive_reader", sources("Sha256"));
            for (Path executable : executables) {
                List<String> run = new ArrayList<>(List.of(executable.toString()));
                run.addAll(command);
                String actual = run(run, null, Map.of(), 42);
                if (!actual.equals(expected.toString())) {
                    throw new AssertionError("native class reader differs: " + ArchiveContractTests.difference(expected.toString(), actual));
                }
                for (Path archive : List.of(Path.of("compiler/build/ironwood-stdlib.ironjar"), FIXTURES.resolve("lib.ironjar"))) {
                    String javaArchive = javaArchive("archive", archive);
                    String nativeArchive = run(List.of(executable.toString(), "--jar",
                            archive.toAbsolutePath().normalize().toString()), null, Map.of(), 42);
                    if (!nativeArchive.equals(javaArchive)) {
                        throw new AssertionError("native archive reader differs for " + archive + ": "
                                + ArchiveContractTests.difference(javaArchive, nativeArchive));
                    }
                }
            }
        } finally {
            PortFixtures.delete(root);
        }
    }

    static String digest(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)))
                .substring(0, 16);
    }

    // The reader driver's line for a class artifact, from Java's reader.
    static String javaClass(IronClass ironClass) throws Exception {
        List<String> types = ironClass.declaredTypes().stream().sorted().toList();
        SourceFile source = ironClass.source(types.getFirst()).orElseThrow();
        return "ok types=" + String.join(",", types) + " entry=" + ironClass.entryPoint().orElse("-") + " path="
                + source.path() + " source=" + digest(source.content());
    }

    // The reader driver's block for an archive, from Java's reader.
    static String javaArchive(String label, Path file) throws Exception {
        StringBuilder out = new StringBuilder(label).append(' ');
        IronJar archive = IronJar.read(file);
        List<String> names = archive.entries();
        if (names.size() > 20) out.append("ok entries=").append(names.size()).append(" names=").append(digest(String.join("\n", names)));
        else out.append("ok entries=").append(String.join(",", names));
        for (String type : new TreeSet<>(archive.declaredTypes())) {
            SourceFile source = archive.source(type).orElseThrow();
            out.append("\n  source ").append(type).append(" ok path=").append(source.path()).append(" source=")
                    .append(digest(source.content()));
        }
        return out.append('\n').toString();
    }

    /**
     * Native-written artifacts read by Java: every standard-library class
     * rewritten natively equals Java's STORED spelling of its entries and
     * reads back through IronClass.read; a native archive of Java's class
     * directory equals Java's standard-library archive byte for byte; and a
     * native archive of the native class directory reads back through
     * IronJar.read with every source intact.
     */
    static void nativeWriterJavaReader() throws Exception {
        Path root = Files.createTempDirectory("ironwood-archive-services-native-java-");
        try {
            Path stdlib = Path.of("compiler/build/stdlib").toAbsolutePath();
            List<Path> classFiles;
            try (var paths = Files.walk(stdlib)) {
                classFiles = paths.filter(path -> path.toString().endsWith(".ironclass")).sorted().toList();
            }
            Path nativeClasses = root.resolve("native-classes");
            Path contents = Files.createDirectories(root.resolve("contents"));
            List<String> command = new ArrayList<>(List.of("--class"));
            for (int index = 0; index < classFiles.size(); index++) {
                Path file = classFiles.get(index);
                Map<String, byte[]> entries = new LinkedHashMap<>();
                for (ZipBytes.Record record : ZipBytes.parse(Files.readAllBytes(file))) entries.put(record.name(), record.data());
                String[] typeLine = new String(entries.get("META-INF/types.tsv"), StandardCharsets.UTF_8).strip().split("\t");
                byte[] entryPoint = entries.get("META-INF/entry-point");
                String sourceEntry = entries.keySet().stream().filter(name -> name.startsWith("source/")).findFirst().orElseThrow();
                Path content = contents.resolve(Integer.toString(index));
                Files.write(content, entries.get(sourceEntry));
                command.addAll(List.of(nativeClasses.resolve(stdlib.relativize(file)).toString(), typeLine[0], typeLine[1],
                        entryPoint == null ? "-" : new String(entryPoint, StandardCharsets.UTF_8).strip(),
                        sourceEntry.substring("source/".length()), content.toString()));
            }
            Path licenses = Files.createDirectories(root.resolve("licenses"));
            List<String> licenseArguments = new ArrayList<>();
            byte[] javaArchive = Files.readAllBytes(Path.of("compiler/build/ironwood-stdlib.ironjar"));
            for (ZipBytes.Record record : ZipBytes.parse(javaArchive)) {
                if (!record.name().startsWith("META-INF/LICENSES/")) continue;
                Path license = licenses.resolve(record.name().substring("META-INF/LICENSES/".length()));
                Files.write(license, record.data());
                licenseArguments.addAll(List.of("--license", license.toString()));
            }
            for (Path executable : PortFixtures.links(root, "compiler_archive_writer", sources())) {
                PortFixtures.delete(nativeClasses);
                List<String> writeClasses = new ArrayList<>(List.of(executable.toString()));
                writeClasses.addAll(command);
                run(writeClasses, null, Map.of(), 42);
                for (Path file : classFiles) {
                    Path written = nativeClasses.resolve(stdlib.relativize(file));
                    List<Map.Entry<String, byte[]>> entries = new ArrayList<>();
                    for (ZipBytes.Record record : ZipBytes.parse(Files.readAllBytes(file))) entries.add(Map.entry(record.name(), record.data()));
                    if (!Arrays.equals(Files.readAllBytes(written), CodecTests.javaStored(entries))) {
                        throw new AssertionError("native class artifact differs from Java's STORED entries: " + written);
                    }
                    if (!javaClass(IronClass.read(written)).replace(written.toString(), "<file>")
                            .equals(javaClass(IronClass.read(file)).replace(file.toString(), "<file>"))) {
                        throw new AssertionError("Java reads the native class artifact differently: " + written);
                    }
                }
                Path fromJava = root.resolve(executable.getFileName() + "-java.ironjar");
                List<String> archive = new ArrayList<>(List.of(executable.toString(), "--jar", fromJava.toString()));
                archive.addAll(licenseArguments);
                archive.add(stdlib.toString());
                run(archive, null, Map.of(), 42);
                if (!Arrays.equals(Files.readAllBytes(fromJava), javaArchive)) {
                    throw new AssertionError("native archive of Java's classes differs from Java's standard-library archive");
                }
                Path fromNative = root.resolve(executable.getFileName() + "-native.ironjar");
                archive.set(2, fromNative.toString());
                archive.set(archive.size() - 1, nativeClasses.toString());
                run(archive, null, Map.of(), 42);
                String expected = javaArchive("archive", Path.of("compiler/build/ironwood-stdlib.ironjar"))
                        .replace(Path.of("compiler/build/ironwood-stdlib.ironjar").toAbsolutePath().toString(), "<archive>");
                String actual = javaArchive("archive", fromNative).replace(fromNative.toString(), "<archive>");
                if (!actual.equals(expected)) {
                    throw new AssertionError("Java reads the native archive differently: " + ArchiveContractTests.difference(expected, actual));
                }
            }
        } finally {
            PortFixtures.delete(root);
        }
    }

    /**
     * Native round trips are deterministic: a native class artifact reads back
     * natively with its fields; the same archive results from repeated runs,
     * a directory input and its files in reverse order, and equals the
     * frozen Java archive.
     */
    static void roundTrips() throws Exception {
        Path root = Files.createTempDirectory("ironwood-archive-services-round-");
        try {
            List<Path> writers = PortFixtures.links(root.resolve("writer"), "compiler_archive_writer", sources());
            List<Path> readers = PortFixtures.links(root.resolve("reader"), "compiler_archive_reader", sources("Sha256"));
            byte[] frozen = Files.readAllBytes(FIXTURES.resolve("lib.ironjar"));
            String license = FIXTURES.resolve("LICENSE.txt").toString();
            for (int link = 0; link < writers.size(); link++) {
                Path work = Files.createDirectories(root.resolve("work-" + link));
                String writer = writers.get(link).toString();
                List<byte[]> archives = new ArrayList<>();
                for (int repeat = 0; repeat < 2; repeat++) {
                    Path output = work.resolve("directory-" + repeat + ".ironjar");
                    run(List.of(writer, "--jar", output.toString(), "--license", license, FIXTURES.resolve("classes").toString()),
                            null, Map.of(), 42);
                    archives.add(Files.readAllBytes(output));
                }
                List<String> files = new ArrayList<>(List.of(writer, "--jar", work.resolve("files.ironjar").toString(),
                        "--license", license));
                for (String name : CLASS_FIXTURES.reversed()) files.add(FIXTURES.resolve(name).toString());
                run(files, null, Map.of(), 42);
                archives.add(Files.readAllBytes(work.resolve("files.ironjar")));
                for (byte[] archive : archives) {
                    if (!Arrays.equals(archive, frozen)) throw new AssertionError("native archive output is not deterministic");
                }
                Path classFile = work.resolve("p/A.ironclass");
                Path content = work.resolve("A.source");
                Files.copy(FIXTURES.resolve("src/p/A.iron"), content);
                run(List.of(writer, "--class", classFile.toString(), "p.A", "class", "p.A", "A.iron", content.toString()),
                        null, Map.of(), 42);
                String expected = classFile + " " + javaClass(IronClass.read(classFile)) + "\n";
                String actual = run(List.of(readers.get(link).toString(), "--class", classFile.toString()), null, Map.of(), 42);
                if (!actual.equals(expected) || !expected.contains("entry=p.A")) {
                    throw new AssertionError("native class round trip differs: " + actual + " versus " + expected);
                }
            }
        } finally {
            PortFixtures.delete(root);
        }
    }

    /** Native archive creation reports IronJar.create's diagnostics for every invalid input. */
    static void creationDiagnostics() throws Exception {
        Path root = Files.createTempDirectory("ironwood-archive-services-create-").toRealPath();
        try {
            Path classes = FIXTURES.resolve("classes").toAbsolutePath();
            Path cases = Files.createDirectories(root.resolve("cases"));
            Path empty = Files.createDirectories(cases.resolve("empty"));
            Path invalidName = Files.createDirectories(cases.resolve("invalid/1bad"));
            Files.copy(classes.resolve("q/C.ironclass"), invalidName.resolve("C.ironclass"));
            Path implied = Files.createDirectories(cases.resolve("implied/q"));
            Files.copy(classes.resolve("q/C.ironclass"), implied.resolve("D.ironclass"));
            Path malformed = Files.createDirectories(cases.resolve("malformed/q"));
            Files.write(malformed.resolve("C.ironclass"), new byte[]{1, 2, 3});
            Path renamed = cases.resolve("Z.ironclass");
            Files.copy(classes.resolve("q/C.ironclass"), renamed);
            Path nestedInput = cases.resolve("lib.ironjar");
            Files.copy(FIXTURES.resolve("lib.ironjar"), nestedInput);
            Path text = Files.writeString(cases.resolve("notes.txt"), "x");
            Path licenseDirectory = Files.createDirectories(cases.resolve("license-dir"));
            Path classLicense = Files.copy(FIXTURES.resolve("LICENSE.txt"), cases.resolve("LICENSE.ironclass"));
            Path otherLicenses = Files.createDirectories(cases.resolve("other"));
            Path duplicateLicense = Files.copy(FIXTURES.resolve("LICENSE.txt"), otherLicenses.resolve("LICENSE.txt"));
            String license = FIXTURES.resolve("LICENSE.txt").toAbsolutePath().toString();
            List<List<String>> invalid = List.of(
                    List.of(),
                    List.of(cases.resolve("missing").toString()),
                    List.of(nestedInput.toString()),
                    List.of(text.toString()),
                    List.of(renamed.toString()),
                    List.of(empty.toString()),
                    List.of(cases.resolve("invalid").toString()),
                    List.of(cases.resolve("implied").toString()),
                    List.of(cases.resolve("malformed").toString()),
                    List.of(classes.toString(), classes.resolve("q/C.ironclass").toString()),
                    List.of(classes.toString(), "--license", cases.resolve("absent.txt").toString()),
                    List.of(classes.toString(), "--license", licenseDirectory.toString()),
                    List.of(classes.toString(), "--license", classLicense.toString()),
                    List.of(classes.toString(), "--license", license, "--license", duplicateLicense.toString()));
            List<Path> executables = PortFixtures.links(root.resolve("build"), "compiler_archive_writer", sources());
            for (List<String> arguments : invalid) {
                List<Path> inputs = new ArrayList<>();
                List<Path> licenses = new ArrayList<>();
                for (int index = 0; index < arguments.size(); index++) {
                    if (arguments.get(index).equals("--license")) licenses.add(Path.of(arguments.get(++index)));
                    else inputs.add(Path.of(arguments.get(index)));
                }
                String expected;
                try {
                    IronJar.create(root.resolve("java.ironjar"), inputs, licenses);
                    throw new AssertionError("Java accepted invalid archive inputs " + arguments);
                } catch (IOException failure) {
                    expected = "error " + failure.getMessage() + "\n";
                }
                for (Path executable : executables) {
                    List<String> command = new ArrayList<>(List.of(executable.toString(), "--jar",
                            root.resolve("native.ironjar").toString()));
                    command.addAll(arguments);
                    String actual = run(command, null, Map.of(), 1);
                    if (!actual.equals(expected)) {
                        throw new AssertionError("creation diagnostic for " + arguments + ": native " + actual + " Java " + expected);
                    }
                }
                if (Files.exists(root.resolve("native.ironjar")) || Files.exists(root.resolve("java.ironjar"))) {
                    throw new AssertionError("a failed creation published an archive for " + arguments);
                }
            }
        } finally {
            PortFixtures.delete(root);
        }
    }

    /**
     * Publication keeps IronJar.write's policy: a staged file beside the
     * destination replaces it, a failure before or at the move keeps the
     * earlier destination, no staged file survives, and parents are created;
     * a class artifact is written directly, replacing an earlier file.
     */
    static void publication() throws Exception {
        Path root = Files.createTempDirectory("ironwood-archive-services-publish-");
        try {
            List<Path> executables = PortFixtures.links(root.resolve("build"), "compiler_archive_writer", sources());
            String classes = FIXTURES.resolve("classes").toString();
            String license = FIXTURES.resolve("LICENSE.txt").toString();
            byte[] frozen = Files.readAllBytes(FIXTURES.resolve("lib.ironjar"));
            for (int link = 0; link < executables.size(); link++) {
                String writer = executables.get(link).toString();
                Path work = Files.createDirectories(root.resolve("work-" + link));
                Path archive = Files.writeString(work.resolve("lib.ironjar"), "earlier");
                run(List.of(writer, "--jar", archive.toString(), "--license", license, classes), null, Map.of(), 42);
                if (!Arrays.equals(Files.readAllBytes(archive), frozen) || staged(work)) {
                    throw new AssertionError("publication did not replace the earlier archive cleanly");
                }
                Files.writeString(archive, "earlier");
                run(List.of(writer, "--jar", archive.toString(), work.resolve("absent").toString()), null, Map.of(), 1);
                if (!Files.readString(archive).equals("earlier") || staged(work)) {
                    throw new AssertionError("a failed creation changed the earlier archive or left a stage");
                }
                Path directory = Files.createDirectories(work.resolve("taken.ironjar"));
                Files.writeString(directory.resolve("keep"), "kept");
                run(List.of(writer, "--jar", directory.toString(), "--license", license, classes), null, Map.of(), 1);
                if (!Files.readString(directory.resolve("keep")).equals("kept") || staged(work)) {
                    throw new AssertionError("a failed move changed the destination or left a stage");
                }
                Path nested = work.resolve("a/b/c/lib.ironjar");
                run(List.of(writer, "--jar", nested.toString(), "--license", license, classes), null, Map.of(), 42);
                if (!Arrays.equals(Files.readAllBytes(nested), frozen) || staged(nested.getParent())) {
                    throw new AssertionError("publication into new parent directories failed");
                }
                Path classFile = Files.createDirectories(work.resolve("classes/p")).resolve("A.ironclass");
                Files.writeString(classFile, "earlier");
                Path content = work.resolve("A.source");
                Files.writeString(content, "package p;\nclass A {\n}\n");
                run(List.of(writer, "--class", classFile.toString(), "p.A", "class", "-", "A.iron", content.toString()),
                        null, Map.of(), 42);
                if (!IronClass.read(classFile).declaredTypes().contains("p.A") || staged(classFile.getParent())) {
                    throw new AssertionError("class artifact was not written directly");
                }
                Path deep = work.resolve("x/y/B.ironclass");
                run(List.of(writer, "--class", deep.toString(), "x.y.B", "interface", "-", "B.iron", content.toString()),
                        null, Map.of(), 42);
                if (!IronClass.read(deep).declaredTypes().contains("x.y.B")) throw new AssertionError("class parents");
            }
        } finally {
            PortFixtures.delete(root);
        }
    }

    // Whether a staged archive (".ironjar-*.tmp") remains in the directory.
    private static boolean staged(Path directory) throws Exception {
        try (var files = Files.list(directory)) {
            return files.anyMatch(path -> path.getFileName().toString().startsWith(".ironjar-"));
        }
    }

    /** Inputs are borrowed; artifacts, archives, payloads and their fresh texts belong to the caller. */
    static void ownership() throws Exception {
        List<SourceFile> helpers = PortFixtures.portSources(SERVICES);
        String prefix = """
                import ironwood.compiler.port.*;
                import ironwood.io.IOException;
                import ironwood.nio.file.Path;
                class Main { public static int main(String[] args) { try {
                """;
        String suffix = " } catch (IOException failure) { return 1; } }}";
        String read = "Inflate inflate = new Inflate(); Path path = Path.of(args[0]);"
                + " IronClassArtifact artifact = IronClassArtifact.read(path, inflate); free path;";
        String jar = "Inflate inflate = new Inflate(); Path path = Path.of(args[0]);"
                + " IronJarArchive archive = new IronJarArchive(path, inflate); free path;";
        for (UnfreedMode mode : UnfreedMode.values()) {
            PortFixtures.require(mode, helpers, prefix + read + " String type = artifact.type(0); String where ="
                    + " artifact.sourcePath(); int r = artifact.source().length() + type.length() + where.length();"
                    + " free type; free where; free artifact; free inflate; return r;" + suffix, null);
            PortFixtures.require(mode, helpers, prefix + read + " String source = artifact.source(); free source;"
                    + " free artifact; free inflate; return 0;" + suffix, "cannot free 'source'");
            PortFixtures.require(mode, helpers, prefix + read + " free artifact; int r = artifact.typeCount();"
                    + " free inflate; return r;" + suffix, "after its allocation was freed");
            PortFixtures.require(mode, helpers, prefix + jar + " String name = archive.type(0);"
                    + " IronClassArtifact payload = archive.payload(name, inflate); free name; String entry ="
                    + " archive.entryName(0); free entry; int r = payload.source().length(); free payload;"
                    + " free archive; free inflate; return r;" + suffix, null);
            PortFixtures.require(mode, helpers, prefix + jar + " IronClassArtifact payload = archive.payload(\"p.A\","
                    + " inflate); free archive; int r = payload.typeCount(); free payload; free inflate; return r;"
                    + suffix, null);
            PortFixtures.require(mode, helpers, prefix + jar + " free archive; free archive; free inflate; return 0;"
                    + suffix, "allocation was already freed");
            PortFixtures.require(mode, helpers, prefix + "Inflate inflate = new Inflate(); Path out = Path.of(args[0]);"
                    + " TextList inputs = new TextList(); String spelling = new String(args[1]); inputs.add(spelling);"
                    + " free spelling; TextList licenses = new TextList(); IronJarArchive.create(out, inputs, licenses,"
                    + " inflate); free inputs; free licenses; free out; free inflate; return 0;" + suffix, null);
        }
    }

    /** Every allocation failure unwinds to the baseline and leaves no staged archive. */
    static void failures() throws Exception {
        Path root = Files.createTempDirectory("ironwood-archive-services-failure-");
        try {
            for (Path executable : PortFixtures.links(root, "compiler_archive_failure", sources())) {
                Path work = root.resolve(executable.getFileName() + "-work");
                List<String> command = List.of(executable.toString(), FIXTURES.toAbsolutePath().toString(), work.toString());
                int limit = 0;
                while (true) {
                    PortFixtures.delete(work);
                    Files.createDirectories(work);
                    int exit = PortFixtures.execute(command, limit, -1, null);
                    if (staged(work)) throw new AssertionError("staged archive left at allocation limit " + limit);
                    if (exit == 43) break;
                    if (exit != 42) throw new AssertionError("archive services failure exit " + exit + " at limit " + limit);
                    limit++;
                }
                if (limit < 50) throw new AssertionError("archive services allocation sweep ended at limit " + limit);
                PortFixtures.delete(work);
                Files.createDirectories(work);
                PortFixtures.execute(command, null, 43, null);
            }
        } finally {
            PortFixtures.delete(root);
        }
    }

    /**
     * Files.walkFileTree, which the archive and discovery walks use, unwinds
     * every allocation failure, including one while a directory's stream or
     * traversal-loop check is being built.
     */
    static void walkFailures() throws Exception {
        Path root = Files.createTempDirectory("ironwood-walk-failure-");
        try {
            Path tree = Files.createDirectories(root.resolve("tree/a/b"));
            Files.writeString(tree.resolve("file"), "x");
            Files.createDirectories(root.resolve("tree/c"));
            Files.writeString(root.resolve("tree/c/other"), "y");
            Files.createSymbolicLink(root.resolve("tree/link"), root.resolve("tree/a"));
            for (Path executable : PortFixtures.links(root.resolve("build"), "stdlib_walk_failure", List.of())) {
                PortFixtures.sweep(executable, List.of(root.resolve("tree").toString()), 20);
            }
        } finally {
            PortFixtures.delete(root);
        }
    }

    /** Native discovery finds Java's archives, class roots, source roots and owned types in every layout. */
    static void discovery() throws Exception {
        Path root = Files.createTempDirectory("ironwood-archive-services-discovery-");
        try {
            Path checkout = Path.of("").toAbsolutePath();
            Path classes = checkout.resolve(PortFixtures.CLASSES);
            Path jar = checkout.resolve("compiler/build/ironwoodc.jar");
            Path layouts = Files.createDirectories(root.resolve("layouts")).toRealPath();
            Path installed = Files.createDirectories(layouts.resolve("installed"));
            Path lib = Files.createDirectories(installed.resolve("lib"));
            Files.copy(checkout.resolve("compiler/build/ironwood-stdlib.ironjar"), lib.resolve("ironwood-stdlib.ironjar"));
            Path classRoot = Files.createDirectories(lib.resolve("stdlib/extra"));
            Files.copy(FIXTURES.resolve("classes/q/C.ironclass"), classRoot.resolve("C.ironclass"));
            Path sourceRoot = Files.createDirectories(installed.resolve("stdlib/src/main/ironwood/extra"));
            Files.copy(FIXTURES.resolve("src/q/C.iron"), sourceRoot.resolve("Source.iron"));
            Files.writeString(sourceRoot.resolve(".iron"), "");
            Path installedJar = Files.copy(jar, lib.resolve("ironwoodc.jar"));
            Path broken = Files.createDirectories(layouts.resolve("broken/lib"));
            Files.writeString(broken.resolve("ironwood-stdlib.ironjar"), "not an archive");
            Path brokenJar = Files.copy(jar, broken.resolve("ironwoodc.jar"));
            Path bare = Files.createDirectories(layouts.resolve("bare"));
            Path bareJar = Files.copy(jar, bare.resolve("ironwoodc.jar"));
            Path empty = Files.createDirectories(layouts.resolve("empty"));
            record Scenario(Path directory, Path classPath, String location, Map<String, String> environment) { }
            List<Scenario> scenarios = List.of(
                    new Scenario(checkout, classes, classes.toString(), Map.of()),
                    new Scenario(layouts, installedJar, installedJar.toString(), Map.of()),
                    new Scenario(checkout, installedJar, installedJar.toString(), Map.of()),
                    new Scenario(checkout, classes, classes.toString(), Map.of("IRONWOOD_STDLIB_HOME", installed.toString())),
                    new Scenario(checkout, classes, classes.toString(), Map.of("IRONWOOD_STDLIB_HOME", empty.toString())),
                    new Scenario(checkout, classes, classes.toString(), Map.of("IRONWOOD_STDLIB_HOME", "  ")),
                    new Scenario(layouts, brokenJar, brokenJar.toString(), Map.of()),
                    new Scenario(checkout, bareJar, "-", Map.of()));
            List<Path> executables = PortFixtures.links(root.resolve("build"), "compiler_library_discovery",
                    sources("Installation", "LibraryRoots", "LibraryTypes"));
            for (Scenario scenario : scenarios) {
                String expected = javaReference("docs/self-hosting/m5/artifact-evidence/LibraryReference.java",
                        scenario.classPath(), scenario.directory(), scenario.environment());
                for (Path executable : executables) {
                    String actual = run(List.of(executable.toString(), scenario.location()), scenario.directory(),
                            scenario.environment(), 42);
                    if (!actual.equals(expected)) {
                        throw new AssertionError("library discovery differs in " + scenario + ": "
                                + ArchiveContractTests.difference(expected, actual));
                    }
                }
            }
        } finally {
            PortFixtures.delete(root);
        }
    }

    private static String javaReference(String path, Path classPath, Path directory, Map<String, String> environment)
            throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        List<String> command = List.of(java.toString(), "-cp", classPath.toString(),
                Path.of(path).toAbsolutePath().toString());
        return run(command, directory, environment, 0);
    }

    // Runs a program with the environment changes (IRONWOOD_STDLIB_HOME
    // removed unless given) and returns standard output; standard error is
    // discarded. The exit status must be the expected one.
    static String run(List<String> command, Path directory, Map<String, String> environment, int expectedExit)
            throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command);
        if (directory != null) builder.directory(directory.toFile());
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        Map<String, String> variables = builder.environment();
        for (String variable : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS",
                "IRONWOOD_ALLOCATION_LIMIT", "IRONWOOD_STDLIB_HOME")) {
            variables.remove(variable);
        }
        variables.putAll(environment);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(600, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError(command.getFirst() + " timed out");
        }
        if (process.exitValue() != expectedExit) {
            throw new AssertionError(Path.of(command.getFirst()).getFileName() + " exit " + process.exitValue() + ": "
                    + output.substring(0, Math.min(2000, output.length())));
        }
        return output;
    }
}
