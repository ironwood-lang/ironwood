// SPDX-License-Identifier: MIT OR Apache-2.0

import javax.lang.model.SourceVersion;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;
import java.util.function.IntPredicate;

/**
 * Java 21 reference for integration-tests/cases/compiler_java_names.iron. It
 * writes the corpus to the file named by its argument (one name per line as
 * UTF-16 hex, then `exports` and groups of requested export lists separated by
 * `group` lines) and prints, per name, SourceVersion.isName(name, RELEASE_21),
 * isIdentifier(name) and isKeyword(name, RELEASE_21); per export group, the
 * packages and diagnostics of BridgePackageInputs.discover's and
 * BridgeExportSurface.select's exact export loops; and the ranges of
 * Character.isJavaIdentifierStart and isJavaIdentifierPart over every code
 * point. Text results are UTF-16 hex.
 */
public final class JavaNamesReference {
    private JavaNamesReference() { }

    static String hex(String text) {
        StringBuilder out = new StringBuilder();
        for (char unit : text.toCharArray()) out.append(String.format("%04x", (int) unit));
        return out.length() == 0 ? "-" : out.toString();
    }

    static List<String> names() {
        List<String> names = new ArrayList<>(List.of("", ".", "..", "a", "A", "_", "__", "_a", "a_", "$", "$a", "a$",
                "a.b", "a..b", ".a", "a.", "a.b.", "java.lang", "java.lang.String", "a.record.b", "a._.b", "a.int",
                "int.a", "Int", "int1", "INT", "record", "var", "yield", "sealed", "permits", "non-sealed", "when",
                "module", "open", "opens", "exports", "requires", "transitive", "uses", "provides", "to", "with",
                "true", "false", "null", "True", "nul", "ironwood.bridge", "ironwood.bridge.x", "ironwood.bridges",
                "é", "中文.包", "a١", "١a", "a­", "­a", "a​", "​a", "á", "́a",
                "€", "a€", "‿", "Ⅻ", "aⅫ", "𝐀", "a𝐀", "𝟎", "a𝟎",
                "a\ud800", "\ud800a", "a\udc00", "\udc00", "\ud800\ud800", "a\udc00\ud800", "\u0000", "a\u0000",
                "a\u0008", "a\u000e", "a\u001b", "a\u001c", "a\u007f", "a\u009f", "a ", "a b", "a-b", "a/b",
                "1a", "a1", "a.1", "Ａ", "a０", "〇", "Ⅰ.Ⅱ"));
        for (String keyword : List.of("abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
                "const", "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
                "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native", "new",
                "package", "private", "protected", "public", "return", "short", "static", "strictfp", "super", "switch",
                "synchronized", "this", "throw", "throws", "transient", "try", "void", "volatile", "while")) {
            names.add(keyword);
            names.add("a." + keyword);
            names.add(keyword + "s");
        }
        char[] alphabet = {'a', 'Z', '0', '9', '_', '$', '.', '.', ' ', '-', 'é', '中', '١', '­', '​',
            '́', '€', '‿', 'Ⅻ', '\ud835', '\udc00', '\udfce', '\ud800', '\u0000', '\u007f', 'i', 'n', 't',
            'r', 'u', 'e', 'f', 'o', 'l'};
        Random random = new Random(20261007);
        for (int index = 0; index < 4000; index++) {
            char[] units = new char[random.nextInt(12)];
            for (int unit = 0; unit < units.length; unit++) units[unit] = alphabet[random.nextInt(alphabet.length)];
            names.add(new String(units));
        }
        return names;
    }

    static List<List<String>> groups() {
        List<List<String>> groups = new ArrayList<>(List.of(List.of(), List.of("a"), List.of("b", "a", "b"),
                List.of("ironwood.bridge"), List.of("ironwood.bridge", "app"), List.of("a.", "int", "x.y"),
                List.of("z.y", "a.b", "a.b", "é.中"), List.of(""), List.of("a\ud800", "ok"), List.of("record.var")));
        Random random = new Random(7);
        List<String> pool = List.of("a", "b.c", "c", "a.b", "1x", "ironwood.bridge", "int", "x.record", "é", "a..b", "Z");
        for (int index = 0; index < 60; index++) {
            List<String> group = new ArrayList<>();
            for (int item = random.nextInt(5); item > 0; item--) group.add(pool.get(random.nextInt(pool.size())));
            groups.add(group);
        }
        return groups;
    }

    // BridgePackageInputs.discover's export loop.
    static void packageInputs(List<String> exports) {
        var diagnostics = new ArrayList<String>();
        var packages = new TreeSet<String>();
        for (String name : exports) {
            if (name == null || !SourceVersion.isName(name, SourceVersion.RELEASE_21)) {
                diagnostics.add("invalid Java Bridge export package: '" + name + "'");
            } else {
                packages.add(name);
            }
        }
        if (packages.isEmpty() && diagnostics.isEmpty()) {
            diagnostics.add("Java Bridge requires at least one exact export package");
        }
        print("inputs", packages, diagnostics);
    }

    // BridgeExportSurface.select's export loop.
    static void surface(List<String> exports) {
        var diagnostics = new ArrayList<String>();
        var packages = new TreeSet<String>();
        for (String name : exports) {
            if (name == null || !SourceVersion.isName(name, SourceVersion.RELEASE_21)) {
                diagnostics.add("invalid Java Bridge export package: '" + name + "'");
            } else if (name.equals("ironwood.bridge")) {
                diagnostics.add("ironwood.bridge is reserved for the shared Java Bridge value API");
            } else packages.add(name);
        }
        print("surface", packages, diagnostics);
    }

    static void print(String label, TreeSet<String> packages, List<String> diagnostics) {
        System.out.println(label + " " + packages.size() + " " + diagnostics.size());
        for (String name : packages) System.out.println("  package " + hex(name));
        for (String message : diagnostics) System.out.println("  diagnostic " + hex(message));
    }

    static void ranges(String label, IntPredicate accepted) {
        System.out.println(label);
        int code = 0;
        while (code <= Character.MAX_CODE_POINT) {
            if (!accepted.test(code)) {
                code++;
                continue;
            }
            int start = code;
            while (code <= Character.MAX_CODE_POINT && accepted.test(code)) code++;
            System.out.println(Integer.toHexString(start) + " " + Integer.toHexString(code - 1));
        }
    }

    public static void main(String[] args) throws Exception {
        List<String> names = names();
        List<List<String>> groups = groups();
        StringBuilder corpus = new StringBuilder();
        for (String name : names) corpus.append(hex(name)).append('\n');
        corpus.append("exports\n");
        for (List<String> group : groups) {
            corpus.append("group\n");
            for (String name : group) corpus.append(hex(name)).append('\n');
        }
        Files.writeString(Path.of(args[0]), corpus.toString(), StandardCharsets.UTF_8);
        for (String name : names) {
            System.out.println("name " + hex(name) + " " + SourceVersion.isName(name, SourceVersion.RELEASE_21) + " "
                    + SourceVersion.isIdentifier(name) + " " + SourceVersion.isKeyword(name, SourceVersion.RELEASE_21));
        }
        for (List<String> group : groups) {
            System.out.println("group " + group.size());
            packageInputs(group);
            surface(group);
        }
        ranges("start ranges", Character::isJavaIdentifierStart);
        ranges("part ranges", Character::isJavaIdentifierPart);
    }
}
