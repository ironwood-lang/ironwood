// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.ast.ClassDeclaration;
import ironwood.compiler.ast.ForStatement;
import ironwood.compiler.ast.LocalVariableDeclaration;
import ironwood.compiler.diagnostic.Diagnostic;
import ironwood.compiler.lexer.Lexer;
import ironwood.compiler.parser.ParseResult;
import ironwood.compiler.parser.Parser;
import ironwood.compiler.source.SourceFile;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/** Allocation-specific exemptions must not alter ownership proofs or generated code. */
final class SuppressUnfreedTests {
    private static final String DIRECTIVE = "@SuppressUnfreed";

    private SuppressUnfreedTests() {}

    static void parserForms() {
        ParseResult parsed = parse("""
                class SuppressUnfreed {

                    void example() {

                        @SuppressUnfreed Object first = new Object();
                        final @SuppressUnfreed Object second = first;
                        @ /* contextual directive */ SuppressUnfreed final int[] values = { 1 };
                        Object plain = null;
                        for (@SuppressUnfreed Object loop = new Object(); false;) { }
                    }
                }
                """);
        require(parsed.diagnostics().isEmpty(), parsed.diagnostics().toString());
        ClassDeclaration type = (ClassDeclaration) parsed.unit().orElseThrow().declarations().getFirst();
        var statements = type.methods().getFirst().body().orElseThrow().statements();
        for (int index = 0; index < 3; index++) {
            var local = (LocalVariableDeclaration) statements.get(index);
            require(local.hasSuppressUnfreedDirective(), "lost directive at " + index);
            require(local.isFinal() == (index != 0), "lost final modifier at " + index);
        }
        require(!((LocalVariableDeclaration) statements.get(3)).hasSuppressUnfreedDirective(),
                "directive leaked to next declaration");
        var loop = (ForStatement) statements.get(4);
        require(((LocalVariableDeclaration) loop.initializer().orElseThrow()).hasSuppressUnfreedDirective(),
                "classic for initializer lost directive");

        String placement = "the @SuppressUnfreed directive may only be used on local variable declarations";
        for (String source : List.of(
                "@SuppressUnfreed class Bad {}",
                "@SuppressUnfreed interface Bad {}",
                "class Bad { @SuppressUnfreed Object field; }",
                "class Bad { @SuppressUnfreed void method() {} }",
                "class Bad { @SuppressUnfreed Bad() {} }",
                "class Bad { @SuppressUnfreed static {} }",
                "class Bad { void method() { @SuppressUnfreed class Local {} } }",
                "class Bad { void method(@SuppressUnfreed Object argument) {} }",
                "class Bad { void method() { try {} catch (@SuppressUnfreed RuntimeException e) {} } }",
                "class Bad { void method(Object[] items) { for (@SuppressUnfreed Object item : items) {} } }")) {
            requireParserError(source, placement);
        }
        requireParserError(local("@SuppressUnfreed @SuppressUnfreed Object value = null;"),
                "duplicate '@SuppressUnfreed' directive");
        requireParserError(local("@SuppressUnfreed() Object value = null;"),
                "the @SuppressUnfreed directive does not accept arguments");
        requireParserError(local("@SuppressUnfreed(\"reason\") Object value = null;"),
                "the @SuppressUnfreed directive does not accept arguments");
        for (String directive : List.of("@suppressUnfreed", "@pkg.SuppressUnfreed", "@Unknown")) {
            requireParserError(local(directive + " Object value = null;"), "general annotations are not supported");
        }
        requireParserError(local("@Override Object value = null;"), "@Override directive may only be used on methods");
        requireParserError(local("@Test Object value = null;"), "@Test directive may only be used on methods");
        for (String body : List.of("Object @SuppressUnfreed value = null;", "@SuppressUnfreed new Object();",
                "@SuppressUnfreed Object value;", "@SuppressUnfreed final final Object value = null;")) {
            require(parse(local(body)).diagnostics().stream().anyMatch(Diagnostic::isError), body);
        }
        for (UnfreedMode mode : UnfreedMode.values()) {
            var primitive = analyze(local("@SuppressUnfreed int value = 1;"), mode);
            require(!primitive.valid() && primitive.diagnostics().stream().anyMatch(d ->
                    d.message().contains("requires a reference local variable")), primitive.diagnostics().toString());
        }
    }

    static void allocationExemptions() {
        String source = """
                class Item {

                    int counter;
                }
                class Cases {

                    static String word() {

                        return "World";
                    }

                    static Item fresh() {

                        return new Item();
                    }

                    static void aliases() {

                        @SuppressUnfreed Item state = new Item();
                        Item alias = state;
                        state = null;
                        alias = null;
                    }

                    static void markedAlias() {

                        Item original = new Item();
                        @SuppressUnfreed Item alias = original;
                    }

                    static void initializers() {

                        @SuppressUnfreed Item item = fresh();
                        @SuppressUnfreed int[] array = new int[2];
                        @SuppressUnfreed int[] literal = { 1, 2 };
                        @SuppressUnfreed String text = "Hello " + word();
                        @SuppressUnfreed Object nothing = null;
                        @SuppressUnfreed String constant = "immortal";
                    }

                    static void nestedAlias() {

                        Item survivor = null;
                        {
                            @SuppressUnfreed Item original = new Item();
                            survivor = original;
                        }
                        survivor = null;
                    }

                    static void loops() {

                        for (int i = 0; i < 3; i++) {
                            @SuppressUnfreed Item item = new Item();
                            if (i == 1) continue;
                            item.counter++;
                        }
                        for (@SuppressUnfreed Item item = new Item(); item.counter < 3; item.counter++) { }
                    }

                    static void cleanupCopies(boolean early) {

                        try {
                            if (early) return;
                        } finally {
                            @SuppressUnfreed Item item = new Item();
                        }
                    }

                    static void safeCleanup() {

                        @SuppressUnfreed Item item = new Item();
                        free item;
                        item = null;
                    }
                }
                """;
        // Ensure the fixtures would exercise real findings without the exemptions.
        var baseline = analyze(withoutDirective(source), UnfreedMode.WARN);
        require(baseline.valid() && baseline.diagnostics().size() == 10, baseline.diagnostics().toString());
        for (UnfreedMode mode : UnfreedMode.values()) {
            var result = analyze(source, mode);
            require(result.valid() && result.diagnostics().isEmpty(), mode + ": " + result.diagnostics());
        }
        // Allocation exemptions must not depend on which branch is lowered first.
        for (String body : List.of(
                "if (flag) { value = null; } else { @SuppressUnfreed Object alias = value; }",
                "if (flag) { @SuppressUnfreed Object alias = value; } else { value = null; }")) {
            String branch = "class Cases { static void check(boolean flag) { Object value = new Object(); "
                    + body + " } }";
            var warned = analyze(withoutDirective(branch), UnfreedMode.WARN);
            require(warned.valid() && warned.diagnostics().size() == 1, warned.diagnostics().toString());
            var marked = analyze(branch, UnfreedMode.ERROR);
            require(marked.valid() && marked.diagnostics().isEmpty(), marked.diagnostics().toString());
        }
    }

    static void suppressionBoundaries() {
        String source = """
                class Item {

                }
                class Cases {

                    static Item fresh() {

                        Item unrelated = new Item();
                        return new Item();
                    }

                    static void reassign() {

                        @SuppressUnfreed Item original = new Item();
                        original = new Item();
                        original = null;
                    }

                    static void emptyInitializer() {

                        @SuppressUnfreed Item empty = null;
                        empty = new Item();
                    }

                    static void shadow() {

                        { @SuppressUnfreed Item value = new Item(); }
                        { Item value = new Item(); }
                    }

                    static void helper() {

                        @SuppressUnfreed Item result = fresh();
                    }

                    static void separateAllocations() {

                        @SuppressUnfreed Item ignored = new Item();
                        new Item();
                        @SuppressUnfreed Item[] array = { new Item() };
                        free array;
                    }

                    static int consume(Item item) {

                        return 1;
                    }

                    static void nestedInitializer() {

                        @SuppressUnfreed int[] array = new int[consume(new Item())];
                    }
                }
                """;
        for (UnfreedMode mode : List.of(UnfreedMode.WARN, UnfreedMode.ERROR)) {
            var result = analyze(source, mode);
            require(result.valid() == (mode == UnfreedMode.WARN), result.diagnostics().toString());
            require(result.diagnostics().size() == 7, result.diagnostics().toString());
            require(result.diagnostics().stream().allMatch(d -> d.isError() == (mode == UnfreedMode.ERROR)),
                    "wrong diagnostic severity");
            for (String name : List.of("unrelated", "value")) {
                require(result.diagnostics().stream().anyMatch(d -> d.message().contains("'" + name + "'")),
                        "lost finding for " + name);
            }
            for (String assignment : List.of("original = new Item();", "empty = new Item();")) {
                int offset = source.lastIndexOf(assignment) + assignment.indexOf("new Item()");
                require(result.diagnostics().stream().anyMatch(d -> d.span().start().offset() == offset),
                        "lost finding for later allocation: " + assignment);
            }
            require(result.diagnostics().stream().allMatch(d -> d.span() != null && d.source() != null),
                    "findings lost source locations");
        }
        var off = analyze(source, UnfreedMode.OFF);
        require(off.valid() && off.diagnostics().isEmpty(), off.diagnostics().toString());
    }

    static void mandatorySafety() {
        for (String body : List.of(
                "@SuppressUnfreed Object value = new Object(); free value; free value;",
                "@SuppressUnfreed Object value = new Object(); free value; Object alias = value;",
                "@SuppressUnfreed Object value = new Object(); Object alias = value; free value;",
                "@SuppressUnfreed Object value = new Object(); saved = value; free value;",
                "@SuppressUnfreed Object value = input; free value;")) {
            String source = "class Cases { static Object saved; static void check(Object input) { " + body + " } }";
            for (UnfreedMode mode : UnfreedMode.values()) {
                var marked = analyze(source, mode);
                var plain = analyze(withoutDirective(source), mode);
                require(!marked.valid(), "suppression accepted unsafe free: " + body);
                require(marked.diagnostics().stream().filter(Diagnostic::isError).map(Diagnostic::message).toList()
                        .equals(plain.diagnostics().stream().filter(Diagnostic::isError).map(Diagnostic::message).toList()),
                        "suppression changed safety diagnostics: " + marked.diagnostics());
            }
        }
    }

    static void unchangedCodeGeneration() {
        String source = """
                class Main {

                    public static int main(String[] args) {

                        @SuppressUnfreed int[] values = new int[2];
                        values[0] = 42;
                        System.out.println(values[0]);
                        return 0;
                    }
                }
                """;
        var marked = new CompilerPipeline(UnfreedMode.ERROR).compile(SourceFile.of("Main.iron", source));
        var plain = new CompilerPipeline(UnfreedMode.OFF).compile(SourceFile.of("Main.iron", withoutDirective(source)));
        require(marked.successful() && plain.successful(), marked.diagnostics() + " / " + plain.diagnostics());
        require(marked.program().equals(plain.program()), "directive changed typed IR");
        require(marked.llvmIr().equals(plain.llvmIr()), "directive changed emitted LLVM");
    }

    static void artifactAndNativeOutput() throws Exception {
        Path root = Files.createTempDirectory("ironwood-suppress-unfreed-");
        try {
            Path input = root.resolve("Main.iron");
            Files.writeString(input, """
                    // SPDX-License-Identifier: MIT OR Apache-2.0
                    class Item {

                        int counter;
                    }
                    public class Main {

                        static String word() {

                            return "World";
                        }

                        public static int main(String[] args) {

                            long before = System.liveAllocationCount();
                            @SuppressUnfreed Item state = new Item();
                            Item alias = state;
                            state = null;
                            alias = null;
                            for (int i = 0; i < 3; i++) {
                                @SuppressUnfreed Item repeated = new Item();
                                repeated.counter = i;
                            }
                            @SuppressUnfreed String message = "Hello " + word() + "!";
                            System.out.println(message);
                            // Five allocations remain live; suppression never inserts cleanup.
                            return System.liveAllocationCount() == before + 5L ? 0 : 1;
                        }
                    }
                    """);
            Path classes = root.resolve("classes");
            requireSuccess(run(input.toString(), "-d", classes.toString()));
            requireSuccess(run(input.toString(), "--unfreed=error", "-d", root.resolve("strict").toString()));
            Path archive = root.resolve("app.ironjar");
            archive(classes, archive);
            Files.delete(input);
            // Both transports must carry the exemption without access to the original source.
            for (Path classPath : List.of(classes, archive)) {
                Path executable = root.resolve(classPath.equals(classes) ? "classes-app" : "archive-app");
                requireSuccess(run("--link", "-cp", classPath.toString(), "--main-class", "Main",
                        "--unfreed=error", "-O3", "-o", executable.toString()));
                Process process = new ProcessBuilder(executable.toString()).start();
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                String errors = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                require(process.waitFor() == 0 && output.equals("Hello World!\n") && errors.isEmpty(),
                        "native allocation count or output: " + output + errors);
            }
            Path mixed = root.resolve("Mixed.iron");
            Files.writeString(mixed, """
                    class Mixed {

                        public static void main(String[] args) {

                            @SuppressUnfreed Object exempt = new Object();
                            Object report = new Object();
                        }
                    }
                    """);
            Path mixedClasses = root.resolve("mixed-classes");
            requireSuccess(run(mixed.toString(), "--unfreed=off", "-d", mixedClasses.toString()));
            Path mixedArchive = root.resolve("mixed.ironjar");
            archive(mixedClasses, mixedArchive);
            Files.delete(mixed);
            Path rejected = root.resolve("rejected");
            Result result = run("--link", "-cp", mixedArchive.toString(), "--main-class", "Mixed",
                    "--unfreed=error", "-o", rejected.toString());
            require(result.exit() == 1 && result.stderr().contains("allocation assigned to 'report'")
                    && !result.stderr().contains("allocation assigned to 'exempt'") && !Files.exists(rejected),
                    result.toString());
        } finally {
            try (var files = Files.walk(root)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static String local(String body) {
        return "class Cases { void example() { " + body + " } }";
    }

    private static String withoutDirective(String source) {
        // Preserve offsets so source spans can be compared along with IR and diagnostics.
        return source.replace(DIRECTIVE, " ".repeat(DIRECTIVE.length()));
    }

    private static CompilationArtifact analyze(String source, UnfreedMode mode) {
        return new CompilerPipeline(mode).analyze(List.of(SourceFile.of("test/Cases.iron", source)));
    }

    private static ParseResult parse(String text) {
        SourceFile source = SourceFile.of("test/Directive.iron", text);
        var lexed = new Lexer(source).lex();
        require(lexed.diagnostics().isEmpty(), lexed.diagnostics().toString());
        return new Parser(source, lexed.tokens()).parse();
    }

    private static void requireParserError(String source, String message) {
        var parsed = parse(source);
        require(parsed.diagnostics().stream().anyMatch(d -> d.isError() && d.message().contains(message)),
                source + ": " + parsed.diagnostics());
    }

    private static void archive(Path classes, Path output) {
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        int exit = IronJarMain.run(new String[]{"--create", "--file", output.toString(), classes.toString()},
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(errors));
        require(exit == 0, errors.toString(StandardCharsets.UTF_8));
    }

    private static Result run(String... args) {
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        int exit = Main.run(args, new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(errors, true, StandardCharsets.UTF_8));
        return new Result(exit, errors.toString(StandardCharsets.UTF_8));
    }

    private static void requireSuccess(Result result) {
        require(result.exit() == 0 && result.stderr().isEmpty(), result.toString());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private record Result(int exit, String stderr) {}
}
