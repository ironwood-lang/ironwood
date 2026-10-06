// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.source.SourceFile;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * M5.1's frozen archive contract (docs/self-hosting/m5/ARCHIVES.md): the
 * Java readers' verdicts over the archive corpus, and the Java writers'
 * serialization fixtures with their SHA-256 identities.
 */
final class ArchiveContractTests {
    static final String EVIDENCE = "docs/self-hosting/m5/archive-evidence/";
    static final Path FIXTURES = Path.of(EVIDENCE + "fixtures");
    static final List<String> CLASS_FILES = List.of("classes/p/A$Nested.ironclass", "classes/p/A.ironclass",
            "classes/p/Shape.ironclass", "classes/q/C.ironclass");

    private ArchiveContractTests() { }

    static void javaVerdicts() throws Exception {
        Path root = Files.createTempDirectory("ironwood-archive-corpus-");
        try {
            String actual = ArchiveCorpus.javaVerdicts(root);
            String expected = Files.readString(Path.of(EVIDENCE + "java-verdicts.txt"));
            if (!actual.equals(expected)) throw new AssertionError("Java archive verdicts changed: " + difference(expected, actual));
        } finally {
            PortFixtures.delete(root);
        }
    }

    /**
     * The frozen Java-written fixtures keep their identities under Java's
     * digest and the port's Sha256, Java's readers decode them to their
     * sources, a fresh Java compile writes the same entries and metadata,
     * and a fresh Java archive equals the frozen one byte for byte from
     * directory and reordered file inputs.
     */
    static void fixtures() throws Exception {
        List<String> manifest = Files.readAllLines(FIXTURES.resolve("fixtures.sha256"));
        List<String> arguments = new ArrayList<>();
        StringBuilder expectedNative = new StringBuilder();
        for (String line : manifest) {
            String hex = line.substring(0, 64);
            Path file = FIXTURES.resolve(line.substring(66)).toAbsolutePath();
            String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(file)));
            if (!actual.equals(hex)) throw new AssertionError("fixture identity changed: " + line);
            arguments.add(file.toString());
            expectedNative.append(hex).append("  ").append(file).append('\n');
        }
        Path root = Files.createTempDirectory("ironwood-archive-fixtures-");
        try {
            for (Path executable : PortFixtures.links(root, "archive_fixture_identity",
                    List.of(PortFixtures.PORT + "Sha256.iron"))) {
                List<String> command = new ArrayList<>(List.of(executable.toString()));
                command.addAll(arguments);
                PortFixtures.execute(command, null, 42, expectedNative.toString());
            }
            javaReadersDecodeFixtures();
            Path fresh = root.resolve("fresh");
            PortFixtures.compile(List.of(FIXTURES.resolve("src/p/A.iron").toString(),
                    FIXTURES.resolve("src/q/C.iron").toString(), "--unfreed=error", "-d", fresh.toString()));
            for (String name : CLASS_FILES) {
                String frozen = describe(Files.readAllBytes(FIXTURES.resolve(name)));
                String rewritten = describe(Files.readAllBytes(fresh.resolve(name.substring("classes/".length()))));
                if (!frozen.equals(rewritten)) throw new AssertionError("Java class writer changed for " + name);
            }
            byte[] frozenArchive = Files.readAllBytes(FIXTURES.resolve("lib.ironjar"));
            Path fromDirectory = root.resolve("directory.ironjar");
            archive(fromDirectory, FIXTURES.resolve("classes").toString());
            List<String> files = new ArrayList<>();
            for (String name : CLASS_FILES.reversed()) files.add(FIXTURES.resolve(name).toString());
            Path fromFiles = root.resolve("files.ironjar");
            archive(fromFiles, files.toArray(String[]::new));
            if (!java.util.Arrays.equals(frozenArchive, Files.readAllBytes(fromDirectory))
                    || !java.util.Arrays.equals(frozenArchive, Files.readAllBytes(fromFiles))) {
                throw new AssertionError("Java archive writer no longer reproduces the frozen lib.ironjar");
            }
        } finally {
            PortFixtures.delete(root);
        }
    }

    private static void javaReadersDecodeFixtures() throws Exception {
        String sourceA = Files.readString(FIXTURES.resolve("src/p/A.iron"));
        String sourceC = Files.readString(FIXTURES.resolve("src/q/C.iron"));
        List<String> declared = List.of("p.A$Nested", "p.A", "p.Shape", "q.C");
        for (int index = 0; index < CLASS_FILES.size(); index++) {
            IronClass ironClass = IronClass.read(FIXTURES.resolve(CLASS_FILES.get(index)));
            String type = declared.get(index);
            SourceFile source = ironClass.source(type).orElseThrow();
            boolean entry = ironClass.entryPoint().isPresent();
            if (!ironClass.declaredTypes().equals(Set.of(type)) || entry != type.equals("p.A")
                    || !source.content().equals(type.equals("q.C") ? sourceC : sourceA)) {
                throw new AssertionError("Java reader decodes " + CLASS_FILES.get(index) + " differently");
            }
        }
        IronJar archive = IronJar.read(FIXTURES.resolve("lib.ironjar"));
        if (!archive.entries().equals(List.of("META-INF/IRONWOOD.MF", "META-INF/LICENSES/LICENSE.txt",
                "META-INF/types.tsv", "p/A$Nested.ironclass", "p/A.ironclass", "p/Shape.ironclass", "q/C.ironclass"))
                || !new TreeSet<>(archive.declaredTypes()).equals(new TreeSet<>(declared))) {
            throw new AssertionError("Java reader lists lib.ironjar differently: " + archive.entries());
        }
        for (String type : declared) {
            if (!archive.source(type).orElseThrow().content().equals(type.equals("q.C") ? sourceC : sourceA)) {
                throw new AssertionError("Java reader decodes " + type + " from lib.ironjar differently");
            }
        }
    }

    // Entry order, metadata and decoded data; compressed bytes may come from another zlib build.
    private static String describe(byte[] bytes) throws Exception {
        StringBuilder result = new StringBuilder();
        for (ZipBytes.Record record : ZipBytes.parse(bytes)) {
            result.append(record.metadata()).append(" data=")
                    .append(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(record.data())))
                    .append('\n');
        }
        return result.toString();
    }

    private static void archive(Path output, String... inputs) {
        List<String> arguments = new ArrayList<>(List.of("--create", "--file", output.toString(), "--license",
                FIXTURES.resolve("LICENSE.txt").toString()));
        arguments.addAll(List.of(inputs));
        ByteArrayOutputStream messages = new ByteArrayOutputStream();
        if (IronJarMain.run(arguments.toArray(String[]::new), new PrintStream(messages), new PrintStream(messages)) != 0) {
            throw new AssertionError("ironjar " + arguments + ": " + messages);
        }
    }

    static String difference(String expected, String actual) {
        String[] first = expected.split("\n", -1);
        String[] second = actual.split("\n", -1);
        for (int line = 0; line < Math.max(first.length, second.length); line++) {
            String left = line < first.length ? first[line] : "<missing>";
            String right = line < second.length ? second[line] : "<missing>";
            if (!left.equals(right)) return "line " + (line + 1) + ":\n  expected " + left + "\n  actual   " + right;
        }
        return "same lines";
    }
}
