// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.source.SourceFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * M5.4's documentation and CLI helpers (D277): the port's DocText scans,
 * Java identifier-part table and link callback against the Java
 * documentation tools, IronDoc's source walks, the table's regeneration,
 * ownership and allocation failure.
 */
final class DocHelperTests {
    private static final String EVIDENCE = "docs/self-hosting/m5/doc-evidence/";
    private static final String PORT = PortFixtures.PORT;

    private DocHelperTests() { }

    /**
     * Every corpus input gives the Java tools' splits, prefixes, entities,
     * removals, collapses, identifier runs, package checks, stripLeading,
     * region matches and anchors; every tag suffix gives DocComment.parse's
     * tag name or diagnostic; and the identifier-part ranges equal Java's for
     * every char and code point.
     */
    static void javaDifferential() throws Exception {
        Path root = Files.createTempDirectory("ironwood-doc-text-");
        try {
            Path corpus = root.resolve("corpus.txt");
            String expected = PortFixtures.reference(EVIDENCE + "DocTextReference.java", List.of(PortFixtures.CLASSES),
                    List.of(corpus.toString()));
            for (Path executable : PortFixtures.links(root, "compiler_doc_text",
                    List.of(PORT + "DocText.iron", PORT + "JavaIdentifiers.iron", PORT + "Splits.iron"))) {
                PortFixtures.execute(List.of(executable.toString(), corpus.toString()), null, 42, expected);
            }
        } finally {
            PortFixtures.delete(root);
        }
    }

    /** The checked-in identifier table is exactly what the generator writes from this JDK 21. */
    static void tableRegenerates() throws Exception {
        String generated = PortFixtures.reference(EVIDENCE + "JavaIdentifierTable.java", List.of(), List.of());
        String checkedIn = Files.readString(Path.of(PORT + "JavaIdentifiers.iron"));
        if (!generated.equals(checkedIn)) {
            throw new AssertionError("JavaIdentifiers.iron differs from its generator: "
                    + ArchiveContractTests.difference(checkedIn, generated));
        }
    }

    /**
     * IronDoc's walks: the regular .iron files at depth 1 and at any depth, in
     * Path order, links listed but not entered, equal Java's
     * Files.walk selection.
     */
    static void walks() throws Exception {
        Path root = Files.createTempDirectory("ironwood-doc-walk-").toRealPath();
        try {
            Path tree = Files.createDirectories(root.resolve("tree"));
            for (String name : List.of("a.iron", "b.txt", ".iron", "z.iron", "Z.iron", "é.iron", "中.iron",
                    "😀.iron", "�.iron", "x.ironclass")) {
                Files.writeString(tree.resolve(name), "");
            }
            Files.createDirectories(tree.resolve("x.iron"));
            Files.writeString(tree.resolve("x.iron/c.iron"), "");
            Files.createDirectories(tree.resolve("sub/deep"));
            Files.writeString(tree.resolve("sub/d.iron"), "");
            Files.writeString(tree.resolve("sub/deep/e.iron"), "");
            Files.createSymbolicLink(tree.resolve("link.iron"), tree.resolve("sub/d.iron"));
            Files.createSymbolicLink(tree.resolve("dirlink"), tree.resolve("sub"));
            Files.createSymbolicLink(tree.resolve("dangling.iron"), tree.resolve("missing.iron"));
            StringBuilder expected = new StringBuilder();
            for (int depth : new int[]{1, Integer.MAX_VALUE}) {
                List<Path> files;
                try (var paths = Files.walk(tree, depth)) {
                    files = paths.filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".iron"))
                            .sorted().toList();
                }
                expected.append("depth ").append(depth).append(' ').append(files.size()).append('\n');
                for (Path file : files) expected.append("  ").append(file).append('\n');
            }
            for (Path executable : PortFixtures.links(root.resolve("build"), "compiler_doc_walk",
                    List.of(PORT + "FileCollector.iron", PORT + "TextList.iron"))) {
                String actual = ArchiveServiceTests.run(List.of(executable.toString(), tree.toString()), null,
                        java.util.Map.of(), 42);
                if (!actual.equals(expected.toString())) {
                    throw new AssertionError("IronDoc walk differs: " + ArchiveContractTests.difference(expected.toString(), actual));
                }
            }
        } finally {
            PortFixtures.delete(root);
        }
    }

    /** Inputs are borrowed; every scan result and a callback's Markdown belong to the caller. */
    static void ownership() throws Exception {
        List<SourceFile> helpers = PortFixtures.portSources(List.of("DocText", "JavaIdentifiers", "LinkRenderer",
                "Splits"));
        String renderer = """
                final class Brackets implements LinkRenderer {

                    @Override
                    public String link(String reference, boolean code) {

                        return "[" + reference + "]";
                    }
                }
                """;
        String prefix = "import ironwood.compiler.port.*;\n" + renderer + "class Main { static String render(LinkRenderer"
                + " links, String reference) { return links.link(reference, true); }"
                + " public static int main(String[] args) { String input = new String(args[0]); ";
        for (UnfreedMode mode : UnfreedMode.values()) {
            PortFixtures.require(mode, helpers, prefix + "String head = DocText.head(input); String tail ="
                    + " DocText.tail(input); int[] lines = DocText.lineFields(input); String text ="
                    + " DocText.entities(input); free input; int r = head.length() + tail.length() + lines.length"
                    + " + text.length(); free head; free tail; free lines; free text; return r; }}", null);
            PortFixtures.require(mode, helpers, prefix + "Brackets brackets = new Brackets(); String link ="
                    + " render(brackets, input); free input; int r = link.length(); free link; free brackets;"
                    + " return r; }}", null);
            PortFixtures.require(mode, helpers, prefix + "String head = DocText.head(input); free head; free input;"
                    + " return head.length(); }}", "after its allocation was freed");
            PortFixtures.require(mode, helpers, prefix + "String anchor = DocText.anchor(input); free anchor;"
                    + " free anchor; free input; return 0; }}", "allocation was already freed");
        }
    }

    /** Every allocation failure unwinds to the baseline. */
    static void failures() throws Exception {
        Path root = Files.createTempDirectory("ironwood-doc-text-failure-");
        try {
            for (Path executable : PortFixtures.links(root, "compiler_doc_text_failure",
                    List.of(PORT + "DocText.iron", PORT + "JavaIdentifiers.iron", PORT + "LinkRenderer.iron"))) {
                PortFixtures.sweep(executable, List.of(), 10);
            }
        } finally {
            PortFixtures.delete(root);
        }
    }
}
