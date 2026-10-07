// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.source.SourceFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * M6.1's Bridge helpers: the port's Java 21 name validation and export
 * checks against JDK 21; its identity, inventory, properties and JAR
 * manifest serialization against the Java baseline and JDK 21; their
 * ownership controls and their allocation failure sweep.
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
     * Inputs are borrowed; selections, maps, manifests and the texts and bytes
     * they hand out belong to the caller.
     */
    static void ownership() throws Exception {
        List<SourceFile> helpers = PortFixtures.portSources(List.of("JavaNames", "JavaIdentifiers", "BridgeExports",
                "TextList", "TextMap", "BridgeIdentity", "BridgeProperties", "JarManifest", "Sha256", "Bytes"));
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
                            PORT + "Bytes.iron"))) {
                PortFixtures.sweep(executable, List.of(), 10);
            }
        } finally {
            PortFixtures.delete(root);
        }
    }
}
