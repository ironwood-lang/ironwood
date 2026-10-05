// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.lexer.LexResult;
import ironwood.compiler.lexer.Lexer;
import ironwood.compiler.parser.ParseResult;
import ironwood.compiler.parser.Parser;
import ironwood.compiler.source.SourceFile;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** The M2.1 native frontend pilot: model, dispatch, ownership, Java parity and failure safety. */
final class FrontendPilotTests {
    private static final String PORT = "compiler/src/main/ironwood";
    private static final String AST = PORT + "/ironwood/compiler/ast/";
    private static final String NATIVE = "scripts/self-hosting/native/";
    private static final List<String> ADAPTER = List.of(NATIVE + "FrontendCapture.iron", NATIVE + "FrontendWire.iron",
            NATIVE + "FrontendCensus.iron", NATIVE + "FrontendLiterals.iron");
    private static final String MISSING = "switch expression must cover every enum constant or declare default";
    // Positive, malformed and Unicode inputs. TruncatedParse abandons exactly
    // the 25-allocation class subtree that Java leaves to its collector, and
    // Qualified the one receiver name a qualified this replaces.
    private static final Map<String, String> INPUTS = inputs();

    private FrontendPilotTests() { }

    private static Map<String, String> inputs() {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("Shapes.iron", """
                package p.q;
                import a.b.C;
                import static a.b.C.d;
                class Shapes<T extends Comparable<T>> extends Base implements Runnable {
                    private final java.util.Map<String, java.util.List<? extends Number>> values = null;
                    static final String TEXT = \"""
                        one\\t
                          two
                        \""";
                    Shapes(int x) { super(x); }
                    @Override public void run() {
                        int total = 0x1F + 0b101 + 1_000 + 'x' + "esc\\n\\u0041".length() + 2.5e3f;
                        switch (total) { case 1, 2: total++; break; default: total--; }
                        int kind = switch (total) { case 3 -> 4; default -> { yield 5; } };
                        try { new Runnable() { public void run() { } }; } catch (E1 | E2 failure) { }
                        int[][] grid = new int[][] {{1}, {2, 3}};
                        outer: for (int i = 0; i < 3; i++) { if (i >> 1 > 0 && grid[i] instanceof Object o) continue outer; }
                        Object o = null; total >>>= 2; defer free o;
                    }
                }
                """);
        result.put("Unicode.iron", "class Unicode {\r\n    int x\u0663 = 1; // \uD83D\uDE00\r\n    String s = \"\u00e9\\u00e9\";\r}\n");
        result.put("BadLexemes.iron", "class BadLexemes { int a = 0x; int b = 1__; char c = 'ab'; String s = \"\\q\"; # }");
        result.put("TruncatedParse.iron",
                "// Deliberately malformed source.\nclass TruncatedParse { public static int main(String[] args) { if (true) return");
        result.put("Unterminated.iron", "class Unterminated { /* never closed\n");
        result.put("Qualified.iron", "class Qualified { class Inner { Object f() { return Qualified.this; } } }\n");
        return result;
    }

    /** The variant enums equal the Java sealed permits; ported enums keep their constants. */
    static void matchesJavaModel() throws Exception {
        Map<String, List<String>> roots = new LinkedHashMap<>();
        roots.put("ExpressionVariant", List.of("Expression"));
        roots.put("StatementVariant", List.of("Statement"));
        roots.put("SwitchRuleBodyVariant", List.of("SwitchRuleBody"));
        roots.put("TypeDeclarationVariant", List.of("TypeDeclaration"));
        roots.put("InitializationVariant", List.of("InstanceInitialization", "StaticInitialization"));
        for (var root : roots.entrySet()) {
            List<String> native_ = constants(Files.readString(Path.of(AST + root.getKey() + ".iron")), root.getKey())
                    .stream().sorted().toList();
            for (String sealed : root.getValue()) {
                List<String> java = Arrays.stream(Class.forName("ironwood.compiler.ast." + sealed).getPermittedSubclasses())
                        .map(type -> snake(type.getSimpleName())).sorted().toList();
                if (!native_.equals(java)) throw new AssertionError(root.getKey() + " " + native_ + " != " + java);
            }
        }
        Map<String, String> enums = new LinkedHashMap<>();
        enums.put(PORT + "/ironwood/compiler/lexer/TokenKind.iron", "ironwood.compiler.lexer.TokenKind");
        for (String name : List.of("AccessModifier", "AssignmentOperator", "BinaryOperator", "InterfaceMethodKind",
                "UnaryOperator", "UpdateOperator")) {
            enums.put(AST + name + ".iron", "ironwood.compiler.ast." + name);
        }
        for (var entry : enums.entrySet()) {
            String simple = entry.getValue().substring(entry.getValue().lastIndexOf('.') + 1);
            List<String> native_ = constants(Files.readString(Path.of(entry.getKey())), simple);
            List<String> java = Arrays.stream(Class.forName(entry.getValue()).getEnumConstants()).map(Object::toString).toList();
            if (!native_.equals(java)) throw new AssertionError(simple + " " + native_ + " != " + java);
        }
        String typeName = Files.readString(Path.of(AST + "TypeName.iron"));
        expect(constants(typeName, "Kind"), "ironwood.compiler.ast.TypeName$Kind");
        expect(constants(typeName, "WildcardKind"), "ironwood.compiler.ast.TypeName$WildcardKind");
        expect(constants(Files.readString(Path.of(PORT + "/ironwood/compiler/diagnostic/Diagnostic.iron")), "Severity"),
                "ironwood.compiler.diagnostic.Diagnostic$Severity");
    }

    /** Removing a wire treatment or adding an untreated variant fails compilation in every mode. */
    static void failsClosed() throws Exception {
        String wire = Files.readString(Path.of(NATIVE + "FrontendWire.iron"));
        String variants = Files.readString(Path.of(AST + "ExpressionVariant.iron"));
        Map<String, String> removedArm = Map.of(NATIVE + "FrontendWire.iron",
                wire.replace("            case BLOCK -> this.writeBlock((Block) value);\n", ""));
        Map<String, String> addedVariant = Map.of(AST + "ExpressionVariant.iron",
                variants.replace("    UPDATE_EXPRESSION\n", "    UPDATE_EXPRESSION,\n    PHANTOM_EXPRESSION\n"));
        for (Map<String, String> mutation : List.of(removedArm, addedVariant)) {
            for (var entry : mutation.entrySet()) {
                if (entry.getValue().equals(Files.readString(Path.of(entry.getKey())))) {
                    throw new AssertionError("variant mutation did not apply: " + entry.getKey());
                }
            }
            for (UnfreedMode mode : UnfreedMode.values()) {
                CompilationArtifact artifact = new CompilerPipeline(mode).compile(sources(mutation, true));
                String messages = String.join("; ", artifact.diagnostics().stream().map(d -> d.message()).toList());
                if (artifact.successful() || !messages.contains(MISSING)) {
                    throw new AssertionError("untreated frontend variant compiled under " + mode + ": " + messages);
                }
            }
        }
    }

    /** Builders and token lists retire; frees of state a node or wrapper still observes are rejected. */
    static void ownership() throws Exception {
        String head = """
                import ironwood.compiler.ast.*;
                import ironwood.compiler.lexer.*;
                import ironwood.compiler.parser.*;
                import ironwood.compiler.port.*;
                import ironwood.compiler.source.*;
                import ironwood.ds.ArrayList;
                class Main {
                    public static int main(String[] args) {
                """;
        String frozen = """
                        SourceSpan span = SourceSpan.at(new SourcePosition(0, 1, 1));
                        ArrayList<Statement> builder = new ArrayList<Statement>(2);
                        builder.add(new EmptyStatement(span));
                        SnapshotList<Statement> frozen = new SnapshotList<Statement>(builder);
                        free builder;
                        Block block = new Block(frozen, span);
                """;
        String lexed = """
                        SourceFile source = SourceFile.of("A.iron", "class A { }");
                        Lexer lexer = new Lexer(source);
                        LexResult lexed = lexer.lex();
                        free lexer;
                """;
        Map<String, String> programs = new LinkedHashMap<>();
        // A frozen list stays with the node that stored it; only the builder and node retire.
        programs.put("", frozen + "int size = block.statements().size(); free block; return size == 1 ? 42 : 1;");
        programs.put("cannot free 'frozen': allocation escapes through constructor argument 1",
                frozen + "free frozen; int size = block.statements().size(); free block; return size;");
        programs.put("\u0000", lexed + "Parser parser = new Parser(source, lexed.tokens()); ParseResult parsed = parser.parse();"
                + " free parser; free lexed; int count = parsed.diagnostics().size(); free parsed; return count == 0 ? 42 : 1;");
        programs.put("cannot free 'lexed': allocation is still borrowed by a live wrapper",
                lexed + "Parser parser = new Parser(source, lexed.tokens()); free lexed; ParseResult parsed = parser.parse();"
                        + " free parser; free parsed; return 0;");
        programs.put("cannot use 'lexed' after its allocation was freed",
                lexed + "free lexed; Parser parser = new Parser(source, lexed.tokens()); free parser; return 0;");
        programs.put("cannot prove free of 'first' safe",
                lexed + "Token first = lexed.tokens().get(0); free first; free lexed; return 0;");
        for (UnfreedMode mode : UnfreedMode.values()) {
            for (var program : programs.entrySet()) {
                Map<String, String> extra = Map.of("test/Main.iron", head + program.getValue() + "\n    }\n}\n");
                CompilationArtifact artifact = new CompilerPipeline(mode).compile(sources(extra, false));
                String messages = String.join("; ", artifact.diagnostics().stream().map(d -> d.message()).toList());
                boolean accepted = program.getKey().isEmpty() || program.getKey().equals("\u0000");
                if (accepted ? !artifact.successful() || !messages.isEmpty()
                        : artifact.successful() || !messages.contains(program.getKey())) {
                    throw new AssertionError("frontend ownership control under " + mode + ": " + program.getKey()
                            + ": " + messages);
                }
            }
        }
    }

    /** Native output equals the Java frontend's wire from classes and archive; accepted inputs leave no temporaries. */
    static void artifacts() throws Exception {
        Path root = Files.createTempDirectory("ironwood-frontend-pilot-");
        try {
            Path classes = root.resolve("classes");
            List<String> arguments = new ArrayList<>(portSourcePaths());
            arguments.addAll(ADAPTER);
            arguments.addAll(List.of("--unfreed=warn", "-d", classes.toString()));
            run(arguments);
            Path archive = root.resolve("frontend.ironjar");
            ByteArrayOutputStream ignored = new ByteArrayOutputStream();
            if (IronJarMain.run(new String[]{"--create", "--file", archive.toString(), classes.toString()},
                    new PrintStream(ignored), new PrintStream(ignored)) != 0) {
                throw new AssertionError("frontend archive: " + ignored);
            }
            Map<String, String> units = new LinkedHashMap<>(INPUTS);
            units.put(PORT + "/ironwood/compiler/lexer/Lexer.iron",
                    Files.readString(Path.of(PORT + "/ironwood/compiler/lexer/Lexer.iron")));
            List<String> manifest = new ArrayList<>();
            int index = 0;
            for (var unit : units.entrySet()) {
                Path input = root.resolve("input-" + index++ + ".iron");
                Files.writeString(input, unit.getValue(), StandardCharsets.UTF_8);
                manifest.add(input + "\t" + unit.getKey() + "\t" + sha256(unit.getValue()));
            }
            Path manifestFile = root.resolve("manifest.txt");
            Files.writeString(manifestFile, String.join("\n", manifest) + "\n");
            for (Path input : List.of(classes, archive)) {
                Path executable = root.resolve(input.getFileName() + "-capture");
                run(List.of("--link", "-cp", input.toString(), "--main-class", "FrontendCapture", "--unfreed=warn",
                        "-O3", "-o", executable.toString()));
                Path output = root.resolve(input.getFileName() + "-output");
                for (int unit = 0; unit < units.size(); unit++) Files.createDirectories(output.resolve(Integer.toString(unit)));
                String summary = execute(List.of(executable.toString(), manifestFile.toString(), output.toString()), Map.of(), 0);
                // Only the truncated class's abandoned subtree and the replaced
                // qualifier remain; every other unit retires all temporaries.
                List<String> residue = summary.lines().filter(line -> line.startsWith("unit ")).toList();
                if (!residue.equals(List.of("unit 3 outstanding 25", "unit 5 outstanding 1"))
                        || !summary.contains("outstanding_temporaries 26 ")) {
                    throw new AssertionError("frontend census: " + summary);
                }
                int unit = 0;
                for (var entry : units.entrySet()) {
                    SourceFile source = SourceFile.of(entry.getKey(), entry.getValue());
                    LexResult lexResult = new Lexer(source).lex();
                    ParseResult parseResult = new Parser(source, lexResult.tokens()).parse();
                    Path directory = output.resolve(Integer.toString(unit++));
                    compare(directory.resolve("tokens.json"), wire(lexResult.tokens()));
                    compare(directory.resolve("lex-diagnostics.json"), wire(lexResult.diagnostics()));
                    compare(directory.resolve("ast.json"), wire(parseResult.unit()));
                    compare(directory.resolve("parse-diagnostics.json"), wire(parseResult.diagnostics()));
                }
            }
        } finally {
            delete(root);
        }
    }

    /** Every allocation failure while lexing and parsing unwinds cleanly through the deferred cleanup. */
    static void failures() throws Exception {
        Path root = Files.createTempDirectory("ironwood-frontend-failures-");
        try {
            Path classes = root.resolve("classes");
            List<String> arguments = new ArrayList<>(portSourcePaths());
            arguments.addAll(List.of("integration-tests/cases/compiler_frontend_failure.iron", "--unfreed=warn",
                    "-d", classes.toString()));
            run(arguments);
            Path executable = root.resolve("program");
            run(List.of("--link", "-cp", classes.toString(), "--main-class", "Main", "--unfreed=warn", "-O3",
                    "-o", executable.toString()));
            int limit = 0;
            while (true) {
                int status = status(executable, Map.of("IRONWOOD_ALLOCATION_LIMIT", Integer.toString(limit)));
                if (status == 43) break;
                if (status != 42) throw new AssertionError("frontend allocation limit " + limit + ": exit " + status);
                limit++;
            }
            if (limit < 1800) throw new AssertionError("frontend sweep ended early at " + limit);
            if (status(executable, Map.of()) != 43) throw new AssertionError("frontend run without a limit failed");
        } finally {
            delete(root);
        }
    }

    private static List<SourceFile> sources(Map<String, String> replacements, boolean adapter) throws Exception {
        List<SourceFile> result = new ArrayList<>();
        List<String> paths = new ArrayList<>(portSourcePaths());
        if (adapter) paths.addAll(ADAPTER);
        for (String path : paths) {
            String text = replacements.getOrDefault(path, Files.readString(Path.of(path)));
            result.add(SourceFile.of("test/" + path, text));
        }
        for (var entry : replacements.entrySet()) {
            if (!paths.contains(entry.getKey())) result.add(SourceFile.of(entry.getKey(), entry.getValue()));
        }
        return result;
    }

    private static List<String> portSourcePaths() throws Exception {
        try (Stream<Path> files = Files.walk(Path.of(PORT))) {
            return files.filter(path -> path.toString().endsWith(".iron")).map(Path::toString).sorted().toList();
        }
    }

    private static List<String> constants(String source, String enumName) {
        Matcher matcher = Pattern.compile("enum " + enumName + " \\{([^}]*)\\}").matcher(source);
        if (!matcher.find()) throw new AssertionError("missing enum " + enumName);
        return Arrays.stream(matcher.group(1).split(",")).map(String::trim).filter(name -> !name.isEmpty()).toList();
    }

    private static void expect(List<String> native_, String javaType) throws Exception {
        List<String> java = Arrays.stream(Class.forName(javaType).getEnumConstants()).map(Object::toString).toList();
        if (!native_.equals(java)) throw new AssertionError(javaType + " " + native_ + " != " + java);
    }

    private static String snake(String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT);
    }

    private static String sha256(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static void compare(Path actual, String expected) throws Exception {
        String text = Files.readString(actual, StandardCharsets.UTF_8);
        if (!text.equals(expected + "\n")) throw new AssertionError("native frontend output differs: " + actual);
    }

    // The same structural wire as scripts/self-hosting/ReferenceCapture.
    private static String wire(Object value) throws Exception {
        if (value == null) return "null";
        if (value instanceof String text) return quote(text);
        if (value instanceof Boolean flag) return flag ? "true" : "false";
        if (value instanceof Integer number) return "{\"number_kind\":\"Integer\",\"payload\":" + quote(number.toString()) + "}";
        if (value instanceof Enum<?> constant) {
            return "{\"enum\":" + quote(constant.getDeclaringClass().getSimpleName()) + ",\"name\":" + quote(constant.name()) + "}";
        }
        if (value instanceof SourceFile source) {
            return "{\"source\":" + quote(source.path().getFileName().toString()) + ",\"sha256_utf8\":"
                    + quote(sha256(source.content())) + "}";
        }
        if (value instanceof Optional<?> optional) return "{\"optional\":" + (optional.isPresent() ? wire(optional.get()) : "null") + "}";
        if (value instanceof Iterable<?> items) {
            List<String> elements = new ArrayList<>();
            for (Object item : items) elements.add(wire(item));
            return "[" + String.join(",", elements) + "]";
        }
        if (value.getClass().isRecord()) {
            List<String> fields = new ArrayList<>();
            fields.add("\"node\":" + quote(value.getClass().getSimpleName()));
            for (var component : value.getClass().getRecordComponents()) {
                var accessor = component.getAccessor();
                accessor.setAccessible(true);
                fields.add(quote(component.getName()) + ":" + wire(accessor.invoke(value)));
            }
            return "{" + String.join(",", fields) + "}";
        }
        throw new AssertionError("unhandled wire type " + value.getClass().getName());
    }

    private static String quote(String text) {
        StringBuilder result = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '\\') result.append('\\').append(c);
            else if (c >= 32 && c <= 126) result.append(c);
            else result.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
        }
        return result.append('"').toString();
    }

    private static void run(List<String> arguments) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PrintStream stream = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            int exit = Main.run(new ArrayList<>(arguments).toArray(String[]::new), stream, stream);
            String text = output.toString(StandardCharsets.UTF_8);
            if (exit != 0 || text.contains("warning") || text.contains("error")) {
                throw new AssertionError("frontend compiler run " + arguments + ": exit " + exit + ": " + text);
            }
        }
    }

    private static String execute(List<String> command, Map<String, String> environment, int expected) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().putAll(environment);
        Process process = builder.start();
        byte[] output = process.getInputStream().readAllBytes();
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("frontend program timed out");
        }
        String text = new String(output, StandardCharsets.UTF_8);
        if (process.exitValue() != expected) throw new AssertionError("frontend program exit " + process.exitValue() + ": " + text);
        return text;
    }

    private static int status(Path executable, Map<String, String> environment) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(executable.toString()).redirectErrorStream(true);
        builder.environment().putAll(environment);
        Process process = builder.start();
        process.getInputStream().readAllBytes();
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("frontend failure program timed out");
        }
        return process.exitValue();
    }

    private static void delete(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
}
