// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.bridge.BridgeGeneration;
import ironwood.compiler.source.SourceFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeMap;

/**
 * M6.1's Bridge helpers: the port's Java 21 name validation and export
 * checks against JDK 21; its identity, inventory, properties and JAR
 * manifest serialization against the Java baseline and JDK 21; its generator
 * text conversions and patterns against JDK 21 and the baseline's
 * generators; the Bridge consumers' file inventories against Java's walks and
 * listings; their ownership controls and their allocation failure sweep.
 */
final class BridgeHelperTests {
    private static final String EVIDENCE = "docs/self-hosting/m6/";
    private static final String PORT = PortFixtures.PORT;
    private static final List<String> NAMES = List.of(PORT + "JavaNames.iron", PORT + "JavaIdentifiers.iron",
            PORT + "BridgeExports.iron", PORT + "TextList.iron", PORT + "Splits.iron");
    private static final List<String> INVENTORIES = List.of(PORT + "BridgeIdentity.iron",
            PORT + "BridgeProperties.iron", PORT + "JarManifest.iron", PORT + "TextMap.iron", PORT + "TextList.iron",
            PORT + "Sha256.iron", PORT + "Bytes.iron", PORT + "Splits.iron");

    private BridgeHelperTests() { }

    /**
     * Every corpus name gives SourceVersion.isName, isIdentifier and
     * isKeyword's answers for Java 21, every export group gives both Java
     * callers' packages and diagnostics, and the start and part predicates
     * equal Character's for every code point.
     */
    static void namesDifferential() throws Exception {
        Path root = Files.createTempDirectory("ironwood-java-names-");
        try {
            Path corpus = root.resolve("corpus.txt");
            String expected = PortFixtures.reference(EVIDENCE + "names-evidence/JavaNamesReference.java", List.of(),
                    List.of(corpus.toString()));
            for (Path executable : PortFixtures.links(root, "compiler_java_names", NAMES)) {
                PortFixtures.execute(List.of(executable.toString(), corpus.toString()), null, 42, expected);
            }
        } finally {
            PortFixtures.delete(root);
        }
    }

    /**
     * Every map gives the baseline's BridgeGeneration identity digest and
     * contentIdentity verdict and BridgePackageManifest's serialization,
     * every byte vector bytesDigest, every properties text Properties.load's
     * entries or failure and the canonical check, every attribute list
     * Manifest.write's bytes, and every manifest text new Manifest's main
     * attributes or failure.
     */
    static void inventoriesDifferential() throws Exception {
        Path root = Files.createTempDirectory("ironwood-bridge-inventories-");
        try {
            Path corpus = root.resolve("corpus.txt");
            String expected = PortFixtures.reference(EVIDENCE + "inventory-evidence/InventoryReference.java",
                    List.of(PortFixtures.CLASSES), List.of(corpus.toString()));
            for (Path executable : PortFixtures.links(root, "compiler_bridge_inventories", INVENTORIES)) {
                PortFixtures.execute(List.of(executable.toString(), corpus.toString()), null, 42, expected);
            }
        } finally {
            PortFixtures.delete(root);
        }
    }

    /**
     * Every float and double bit pattern gives Float.toHexString,
     * Double.toHexString and BridgeJavaSources.literal's spelling, every
     * integer String.format's octal and \\u escapes, and every string the
     * baseline's quote and cString, stripTrailing and the seven Bridge
     * String.matches patterns' verdicts, and every llvm-readelf output
     * BridgeLinuxPayload.auditDynamic's regular-expression results.
     */
    static void textDifferential() throws Exception {
        Path root = Files.createTempDirectory("ironwood-bridge-text-");
        try {
            Path corpus = root.resolve("corpus.txt");
            String expected = PortFixtures.reference(EVIDENCE + "text-evidence/BridgeTextReference.java",
                    List.of(PortFixtures.CLASSES), List.of(corpus.toString()));
            for (Path executable : PortFixtures.links(root, "compiler_bridge_text", List.of(PORT + "BridgeText.iron",
                    PORT + "BridgePatterns.iron", PORT + "ReadelfScan.iron", PORT + "BridgeIdentity.iron", PORT + "TextMap.iron",
                    PORT + "TextList.iron", PORT + "Sha256.iron", PORT + "Splits.iron"))) {
                PortFixtures.execute(List.of(executable.toString(), corpus.toString()), null, 42, expected);
            }
        } finally {
            PortFixtures.delete(root);
        }
    }

    /**
     * The runtime source inventory, the export package listings, the
     * distribution stage listing and the assembler's class walk select,
     * order and spell what Java's Files.walk and Files.list give, on a tree
     * with links to files, directories and nothing, directories named like
     * sources, and Unicode names.
     */
    static void walks() throws Exception {
        Path root = Files.createTempDirectory("ironwood-bridge-walks-").toRealPath();
        try {
            Path runtime = Files.createDirectories(root.resolve("runtime"));
            Files.createDirectories(runtime.resolve("include"));
            Files.createDirectories(runtime.resolve("src/x.c"));
            Files.createDirectories(runtime.resolve("src/deep/deeper"));
            for (String name : List.of("include/ironwood_runtime.h", "include/ironwood_bridge.h", "src/ironwood_runtime.c",
                    "src/x.c/y.h", "src/deep/deeper/q.h", "src/é.c", "src/中.h", "src/😀.c", "src/z.C", "src/a.cc",
                    "src/notes.txt", "src/.c", "src/Z.h")) {
                Files.writeString(runtime.resolve(name), "content of " + name);
            }
            Files.createSymbolicLink(runtime.resolve("src/link.c"), runtime.resolve("include/ironwood_bridge.h"));
            Files.createSymbolicLink(runtime.resolve("src/dir.h"), runtime.resolve("include"));
            Files.createSymbolicLink(runtime.resolve("src/dangling.c"), runtime.resolve("missing.c"));
            Path exports = Files.createDirectories(root.resolve("exports/app/api"));
            for (String name : List.of("A.iron", "b.iron", "Z.iron", "é.iron", "x.ironclass", "y.ironclass", "notes.md")) {
                Files.writeString(exports.resolve(name), name);
            }
            Files.createDirectories(exports.resolve("dir.iron"));
            Files.writeString(exports.resolve("dir.iron/c.iron"), "nested");
            Files.createSymbolicLink(exports.resolve("link.iron"), exports.resolve("A.iron"));
            Files.createSymbolicLink(exports.resolve("gone.ironclass"), exports.resolve("missing.ironclass"));
            Path stage = Files.createDirectories(root.resolve("stage"));
            for (String name : List.of("artifact-1.0.jar", "artifact-1.0-sources.jar", "artifact-1.0.pom", "😀.jar")) {
                Files.writeString(stage.resolve(name), name);
            }
            Files.createDirectories(stage.resolve("sub"));
            Files.writeString(stage.resolve("sub/inner.jar"), "inner");
            Files.createSymbolicLink(stage.resolve("link.jar"), stage.resolve("artifact-1.0.pom"));
            Path classes = Files.createDirectories(root.resolve("classes/ironwood/bridge/generated/g0"));
            Files.writeString(classes.resolve("Support.class"), "class");
            Files.writeString(classes.resolve("Support$1.class"), "inner");
            classes = root.resolve("classes");
            StringBuilder expected = new StringBuilder();
            List<Path> sources;
            try (var files = Files.walk(runtime)) {
                sources = files.filter(Files::isRegularFile)
                        .filter(file -> file.toString().endsWith(".c") || file.toString().endsWith(".h")).sorted().toList();
            }
            expected.append("runtime ").append(sources.size()).append('\n');
            var inventory = new TreeMap<String, String>();
            for (Path file : sources) {
                String name = runtime.relativize(file).toString().replace(java.io.File.separatorChar, '/');
                String digest = BridgeGeneration.bytesDigest(Files.readAllBytes(file));
                expected.append("  ").append(name).append(' ').append(digest).append('\n');
                inventory.put(name, digest);
            }
            expected.append("identity ").append(BridgeGeneration.contentIdentity(inventory)).append('\n');
            for (String[] listing : new String[][]{{"sources", ".iron"}, {"artifacts", IronClass.EXTENSION}}) {
                List<Path> files;
                try (var paths = Files.list(exports)) {
                    files = paths.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(listing[1]))
                            .sorted().toList();
                }
                expected.append(listing[0]).append(' ').append(files.size()).append('\n');
                for (Path file : files) expected.append("  ").append(exports.relativize(file)).append('\n');
            }
            List<Path> staged;
            try (var paths = Files.list(stage)) {
                staged = paths.filter(Files::isRegularFile).sorted().toList();
            }
            expected.append("stage ").append(staged.size()).append('\n');
            for (Path file : staged) expected.append("  ").append(file.getFileName()).append('\n');
            List<Path> compiled;
            Path classRoot = classes;
            try (var files = Files.walk(classes)) {
                compiled = files.filter(Files::isRegularFile).map(classRoot::relativize).sorted().toList();
            }
            expected.append("classes ").append(compiled.size()).append('\n');
            for (Path file : compiled) expected.append("  ").append(file).append('\n');
            for (Path executable : PortFixtures.links(root.resolve("build"), "compiler_bridge_walks",
                    List.of(PORT + "SourceFiles.iron", PORT + "FileCollector.iron", PORT + "TextList.iron",
                            PORT + "TextMap.iron", PORT + "BridgeIdentity.iron", PORT + "Sha256.iron"))) {
                PortFixtures.execute(List.of(executable.toString(), runtime.toString(), exports.toString(),
                        stage.toString(), classes.toString()), null, 42, expected.toString());
            }
        } finally {
            PortFixtures.delete(root);
        }
    }

    /**
     * Inputs are borrowed; selections, maps, manifests and the texts and bytes
     * they hand out belong to the caller.
     */
    static void ownership() throws Exception {
        List<SourceFile> helpers = PortFixtures.portSources(List.of("JavaNames", "JavaIdentifiers", "BridgeExports",
                "TextList", "TextMap", "BridgeIdentity", "BridgeProperties", "JarManifest", "Sha256", "Bytes",
                "BridgeText", "BridgePatterns", "ReadelfScan", "SourceFiles", "FileCollector"));
        String prefix = "import ironwood.compiler.port.*;\nclass Main { public static int main(String[] args) {"
                + " String input = new String(args[0]); TextList requested = new TextList(); requested.add(input);"
                + " requested.add(\"a.b\"); ";
        for (UnfreedMode mode : UnfreedMode.values()) {
            PortFixtures.require(mode, helpers, prefix + "BridgeExports exports = new BridgeExports(requested, true);"
                    + " free requested; boolean valid = JavaNames.isName(input); free input; String name ="
                    + " exports.packageName(0); int r = name.length() + exports.diagnosticCount() + (valid ? 1 : 0);"
                    + " free name; free exports; return r; }}", null);
            PortFixtures.require(mode, helpers, prefix + "BridgeExports exports = new BridgeExports(requested, false);"
                    + " String name = exports.packageName(0); free name; free exports; free requested; free input;"
                    + " return name.length(); }}", "after its allocation was freed");
            PortFixtures.require(mode, helpers, prefix + "BridgeExports exports = new BridgeExports(requested, false);"
                    + " free exports; free exports; free requested; free input; return 0; }}",
                    "allocation was already freed");
            PortFixtures.require(mode, helpers, prefix + "free requested; TextMap map = new TextMap(); map.put(input,"
                    + " input); free input; String digest = BridgeIdentity.digest(map); byte[] bytes ="
                    + " BridgeProperties.serialize(map); TextMap loaded = new TextMap(); BridgeProperties.load(bytes, 0,"
                    + " bytes.length, loaded); boolean same = BridgeProperties.canonical(bytes, 0, bytes.length, loaded);"
                    + " String value = loaded.value(0); free map; free loaded; free bytes; int r = digest.length()"
                    + " + value.length() + (same ? 1 : 0); free digest; free value; return r; }}", null);
            PortFixtures.require(mode, helpers, prefix.replace("String[] args) {",
                    "String[] args) throws ironwood.io.IOException {") + "free requested; JarManifest manifest ="
                    + " new JarManifest(); manifest.putValue(\"Manifest-Version\", input); free input; byte[] bytes ="
                    + " manifest.write(); free manifest; JarManifest read = new JarManifest(bytes, 0, bytes.length);"
                    + " free bytes; String version = read.getValue(\"Manifest-Version\"); free read; int r ="
                    + " version.length(); free version; return r; }}", null);
            PortFixtures.require(mode, helpers, prefix + "free requested; TextMap map = new TextMap(); map.put(input,"
                    + " input); String value = map.get(input); free map; free value; free input; return"
                    + " value.length(); }}", "after its allocation was freed");
            PortFixtures.require(mode, helpers, prefix + "free requested; JarManifest manifest = new JarManifest();"
                    + " free manifest; free manifest; free input; return 0; }}", "allocation was already freed");
            PortFixtures.require(mode, helpers, prefix + "free requested; String stripped = BridgeText.stripTrailing(input);"
                    + " boolean group = BridgePatterns.isMavenGroup(input); free input; String hex ="
                    + " BridgeText.doubleHex(1.5); StringBuilder text = new StringBuilder();"
                    + " BridgeText.appendOctalEscape(text, 9); int r = stripped.length() + hex.length() + text.length()"
                    + " + (group ? 1 : 0); free stripped; free hex; free text; return r; }}", null);
            PortFixtures.require(mode, helpers, prefix.replace("String[] args) {",
                    "String[] args) throws ironwood.io.IOException {") + "free requested; ironwood.nio.file.Path root ="
                    + " ironwood.nio.file.Path.of(input); free input; SourceFiles files = new SourceFiles(root);"
                    + " free root; String name = files.relative(0); free files;"
                    + " int r = name.length(); free name; return r; }}", null);
            PortFixtures.require(mode, helpers, prefix + "free requested; String stripped = BridgeText.stripTrailing(input);"
                    + " free stripped; free input; return stripped.length(); }}", "after its allocation was freed");
            PortFixtures.require(mode, helpers, prefix + "free requested; TextList libraries = new TextList();"
                    + " ReadelfScan.sharedLibraries(input, libraries); free input; int r = libraries.size();"
                    + " free libraries; return r; }}", null);
        }
    }

    /** Every allocation failure unwinds to the baseline. */
    static void failures() throws Exception {
        Path root = Files.createTempDirectory("ironwood-bridge-helpers-failure-");
        try {
            for (Path executable : PortFixtures.links(root, "compiler_bridge_helpers_failure",
                    List.of(PORT + "JavaNames.iron", PORT + "JavaIdentifiers.iron", PORT + "BridgeExports.iron",
                            PORT + "TextList.iron", PORT + "TextMap.iron", PORT + "BridgeIdentity.iron",
                            PORT + "BridgeProperties.iron", PORT + "JarManifest.iron", PORT + "Sha256.iron",
                            PORT + "Bytes.iron", PORT + "BridgeText.iron", PORT + "BridgePatterns.iron",
                            PORT + "ReadelfScan.iron", PORT + "JarStreams.iron", PORT + "ZipWriter.iron",
                            PORT + "ZipStream.iron", PORT + "ZipArchive.iron", PORT + "Inflate.iron"))) {
                PortFixtures.sweep(executable, List.of(), 10);
            }
            Path tree = Files.createDirectories(root.resolve("tree/src"));
            Files.createDirectories(root.resolve("tree/include"));
            for (String name : List.of("src/a.c", "src/b.c", "include/c.h", "src/notes.txt")) {
                Files.writeString(root.resolve("tree").resolve(name), name);
            }
            for (Path executable : PortFixtures.links(root.resolve("walks"), "compiler_bridge_walks_failure",
                    List.of(PORT + "SourceFiles.iron", PORT + "FileCollector.iron", PORT + "TextList.iron",
                            PORT + "TextMap.iron", PORT + "BridgeIdentity.iron", PORT + "Sha256.iron"))) {
                PortFixtures.sweep(executable, List.of(tree.getParent().toString()), 10);
            }
        } finally {
            PortFixtures.delete(root);
        }
    }
}
