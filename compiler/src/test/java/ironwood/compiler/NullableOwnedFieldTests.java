// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.ast.DeclaredTypes;
import ironwood.compiler.source.SourceFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

final class NullableOwnedFieldTests {
    static final String NAME = "nullable owned-field returns preserve dependent borrowing and mandatory safety";
    private static final String SOURCE = """
            final class Leaf { int read() { return 42; } }
            final class Owner {
                private final Leaf leaf = new Leaf();
                Leaf value(boolean absent) { BODY }
                destructor { free leaf; }
            }
            class Main {
                static Leaf saved;
                public static int main(String[] args) {
                    Owner owner = new Owner();
                    Leaf value = owner.value(false);
                    int result = value.read();
                    free owner;
                    return result;
                }
            }
            """;

    private NullableOwnedFieldTests() {}

    static void proofs() throws Exception {
        for (String body : List.of("return leaf;", "return absent ? null : leaf;",
                "if (absent) return null; return leaf;", "if (!absent) return leaf; return null;",
                "return (Leaf) (absent ? (Leaf) null : leaf);",
                "return absent ? null : (absent ? null : leaf);")) {
            String safe = SOURCE.replace("BODY", body);
            checkSource(safe, true);
            parity(safe, true);
            for (String operation : List.of(
                    "free value; int result = 0; free owner;",
                    "free owner; int result = value.read();",
                    "Main.saved = value; int result = 0; free owner;")) {
                checkSource(safe.replace("int result = value.read();\n        free owner;", operation), false);
            }
        }
        for (String body : List.of("return absent ? new Leaf() : leaf;", "return absent ? Main.saved : leaf;",
                "if (absent) return new Leaf(); return leaf;",
                "return publish(leaf) ? null : leaf;")) {
            String unsafe = SOURCE.replace("BODY", body).replace("destructor {",
                    "static boolean publish(Leaf input) { Main.saved = input; return false; } destructor {");
            checkSource(unsafe, false);
            parity(unsafe, false);
        }
    }

    private static void checkSource(String text, boolean accepted) {
        for (var mode : UnfreedMode.values()) {
            var compiler = new CompilerPipeline(mode);
            var sources = List.of(SourceFile.of("Main.iron", text));
            var ordinary = compiler.analyze(sources);
            var bridge = compiler.analyzeForBridge(sources);
            check(ordinary.valid() == accepted, "unexpected ordinary ownership in " + mode + ": " + ordinary.diagnostics()
                    + "\n" + text);
            check(ordinary.program().equals(bridge.program()) && ordinary.diagnostics().equals(bridge.diagnostics()),
                    "bridge projection changed nullable ownership semantics");
            if (!accepted) check(ordinary.diagnostics().stream().anyMatch(diagnostic -> diagnostic.isError()
                    && (diagnostic.message().contains("free") || diagnostic.message().contains("borrow"))),
                    "negative failed without an ownership diagnostic: " + ordinary.diagnostics());
        }
    }

    private static void parity(String text, boolean accepted) throws Exception {
        Path directory = Files.createTempDirectory("nullable owned field ");
        try {
            var source = SourceFile.of("Main.iron", text);
            var unit = SourceParser.parse(source).unit().orElseThrow();
            Path classes = directory.resolve("classes");
            for (var type : DeclaredTypes.in(unit)) {
                IronClass.write(classes.resolve(type.binaryName().replace('.', '/') + IronClass.EXTENSION), unit, type.binaryName());
            }
            Path archive = directory.resolve("nullable.ironjar");
            IronJar.create(archive, List.of(classes));
            var compiler = new CompilerPipeline(UnfreedMode.OFF);
            var expected = compiler.analyze(List.of(source)).diagnostics().stream().map(diagnostic -> diagnostic.message()).toList();
            for (Path input : List.of(classes, archive)) {
                var loaded = new SourceSetLoader(List.of(), List.of(input)).load(List.of(), List.of("Main"));
                check(loaded.diagnostics().isEmpty(), loaded.diagnostics().toString());
                var actual = compiler.analyze(loaded.sources());
                check(actual.valid() == accepted && actual.diagnostics().stream().map(diagnostic -> diagnostic.message())
                        .toList().equals(expected), "nullable ownership changed after reconstruction: " + input);
            }
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
