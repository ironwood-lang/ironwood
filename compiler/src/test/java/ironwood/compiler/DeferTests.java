// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.ast.DeferStatement;
import ironwood.compiler.ast.ClassDeclaration;
import ironwood.compiler.ir.IrCallInstruction;
import ironwood.compiler.ir.IrConstant;
import ironwood.compiler.ir.IrInvokeTerminator;
import ironwood.compiler.ir.IrInstruction;
import ironwood.compiler.source.SourceFile;

import java.util.ArrayList;
import java.util.List;

/** Focused deferred-call coverage, retained across both Milestone 1 stages. */
final class DeferTests {
    private DeferTests() {}

    static void syntax() {
        String source = "class Main { static void action() {} static void test() { defer action(); } }";
        ParsedSource parsed = SourceParser.parse(SourceFile.of("test/Main.iron", source));
        require(parsed.diagnostics().isEmpty(), parsed.diagnostics().toString());
        var owner = (ClassDeclaration) parsed.unit().orElseThrow().declarations().getFirst();
        require(owner.methods().get(1).body().orElseThrow().statements().getFirst()
                instanceof DeferStatement, "missing dedicated defer AST");
        for (String statement : List.of("defer ;", "defer {}", "defer return;", "defer defer action();",
                "defer int x = 1;", "defer new Main();", "defer x = 1;", "defer action()",
                "int defer = 0;", "if (true) defer action();", "while (true) defer action();",
                "for (;;) defer action();", "do defer action(); while (false);",
                "label: defer action();", "switch (1) { case 1: defer action(); }",
                "switch (1) { default -> defer action(); }")) {
            ParsedSource invalid = SourceParser.parse(SourceFile.of("test/Main.iron",
                    source.replace("defer action();", statement)));
            require(!invalid.diagnostics().isEmpty(), "accepted malformed defer: " + statement);
        }
        ParsedSource free = SourceParser.parse(SourceFile.of("test/Main.iron",
                source.replace("defer action();", "defer free value;")));
        require(free.diagnostics().isEmpty(), "deferred local free should parse: " + free.diagnostics());
        for (String statement : List.of("if (true) { defer action(); }", "while (false) { defer action(); }",
                "label: { defer action(); }", "switch (1) { case 1: { defer action(); } }",
                "switch (1) { default -> { defer action(); } }",
                "try { defer action(); } catch (Exception e) { defer action(); } finally { defer action(); }")) {
            ParsedSource valid = SourceParser.parse(SourceFile.of("test/Main.iron",
                    source.replace("defer action();", statement)));
            require(valid.diagnostics().isEmpty(), valid.diagnostics().toString());
        }
    }

    static void semantics() {
        for (String result : List.of("boolean", "Main", "Object")) {
            String value = result.equals("boolean") ? "true" : "new Main()";
            reject("class Main { static " + result + " action() { return " + value
                    + "; } public static int main(String[] args) { defer action(); return 0; } }",
                    "defer requires a void method invocation", UnfreedMode.OFF);
        }
        String checked = "class Failure extends Exception {} class Main { static void action() throws Failure {} "
                + "public static int main(String[] args) { defer action(); return 0; } }";
        reject(checked, "unreported checked exception", UnfreedMode.OFF);
        reject(checked.replace("return 0;", "try { return 0; } catch (Failure e) { return 1; }"),
                "unreported checked exception", UnfreedMode.OFF);
        accept(checked.replace("defer action(); return 0;", "try { defer action(); return 0; } catch (Failure e) { return 1; }"));
        accept(checked.replace("main(String[] args)", "main(String[] args) throws Failure"));
        reject(checked.replace("defer action(); return 0;",
                "try { action(); return 0; } catch (Failure e) { return 1; } finally { defer action(); }"),
                "unreported checked exception", UnfreedMode.OFF);
        reject("class Other { private static void hide() {} } class Main { public static int main(String[] args) { defer Other.hide(); return 0; } }",
                "private", UnfreedMode.OFF);
        reject("class Main { public static int main(String[] args) { defer missing(); return 0; } }",
                "unknown", UnfreedMode.OFF);
        reject("class Main { static void action() {} public static int main(String[] args) { return 0; defer action(); } }",
                "unreachable statement", UnfreedMode.OFF);
        CompilationArtifact repeated = compile(checked.replace("defer action(); return 0;",
                "try { if (args.length == 0) return 1; return 2; } finally { defer action(); }"), UnfreedMode.OFF);
        require(repeated.diagnostics().stream().filter(d -> d.message().contains("unreported checked exception")).count() == 1,
                "duplicate cleanup diagnostics: " + repeated.diagnostics());
        // Initializer/delegation and destructor restrictions still apply to delayed calls.
        accept("class Main { static { defer action(); } { defer action(); } Main() { defer action(); } static void action() {} public static int main(String[] args) { Main m = new Main(); free m; return 0; } }");
        reject("class Main { Main() { defer action(); super(); } static void action() {} public static int main(String[] args) { return 0; } }",
                "first statement", UnfreedMode.OFF);
        reject("class Main { static void action() { Object o = new Object(); } destructor { defer action(); } public static int main(String[] args) { Main m = new Main(); free m; return 0; } }",
                "destructor", UnfreedMode.OFF);
        reject("class Main { static Error failure; static void action() { throw failure; } "
                + "destructor { defer action(); } public static int main(String[] args) { return 0; } }",
                "exception may escape", UnfreedMode.OFF);
    }

    static void ownership() {
        String prefix = "class Box { int value; void touch() { this.value++; } } class Main { static Box saved; "
                + "static void use(Box b) { b.touch(); } static void publish(Box b) { saved = b; } "
                + "public static int main(String[] args) { Box b = new Box(); ";
        for (UnfreedMode mode : UnfreedMode.values()) {
            String destructor = "final class Leaf {} class Main { private Leaf owned = new Leaf(); "
                    + "Leaf get() { return owned; } static int next() { return 0; } "
                    + "static void consume(Leaf value) {} static void consume(Leaf value, int n) {} destructor { "
                    + "defer consume(owned); free this.owned; } "
                    + "public static int main(String[] args) { Main value = new Main(); free value; return 0; } }";
            reject(destructor, "pending deferred call", mode);
            CompilationArtifact safeDestructor = compile(destructor.replace(
                    "defer consume(owned);", "{ defer consume(owned); }"), mode);
            require(safeDestructor.successful(), safeDestructor.diagnostics().toString());
            reject(destructor.replace("consume(owned)", "consume(get())"),
                    "pending deferred call", mode);
            safeDestructor = compile(destructor.replace("defer consume(owned);",
                    "{ defer consume(get()); }"), mode);
            require(safeDestructor.successful(), safeDestructor.diagnostics().toString());
            for (String capture : List.of("consume(owned, next())", "consume(get(), next())")) {
                reject(destructor.replace("consume(owned)", capture), "pending deferred call", mode);
                safeDestructor = compile(destructor.replace("defer consume(owned);",
                        "{ defer " + capture + "; }"), mode);
                require(safeDestructor.successful(), safeDestructor.diagnostics().toString());
            }
            for (String body : List.of("defer b.touch(); free b;", "defer use(b); free b;",
                    "defer b.touch(); if (args.length == 0) { free b; }",
                    "defer b.touch(); try {} finally { free b; }",
                    "defer b.touch(); Box c = b; b = null; free c;",
                    "{ defer publish(b); } free b;",
                    "free b; defer b.touch();",
                    "defer use(b); while (args.length > 0) { free b; }")) {
                CompilationArtifact artifact = compile(prefix + body + " return 0; } }", mode);
                require(!artifact.successful(), "unsafe capture accepted in " + mode + ": " + body);
                require(artifact.diagnostics().stream().anyMatch(d -> d.isError()
                        && (d.message().contains("free") || d.message().contains("freed"))),
                        artifact.diagnostics().toString());
            }
            CompilationArtifact safe = compile(prefix + "{ defer b.touch(); defer use(b); b.value = 40; } free b; return 0; } }", mode);
            require(safe.successful(), safe.diagnostics().toString());
            safe = compile(prefix + "Box other = new Box(); { defer b.touch(); b = other; } b = null; free other; return 0; } }", UnfreedMode.OFF);
            require(safe.successful(), safe.diagnostics().toString());
        }
        reject(prefix.replace("Box b = new Box();", "@SuppressUnfreed Box b = new Box();")
                + "defer b.touch(); free b; return 0; } }", "pending deferred call", UnfreedMode.OFF);
        // A helper capture keeps the actual owning allocation alive.
        reject("import ironwood.io.ByteArrayOutputStream; import ironwood.io.OutputStream; class Main { "
                + "static void use(OutputStream out) throws Exception { out.flush(); } "
                + "public static int main(String[] args) throws Exception { ByteArrayOutputStream out = new ByteArrayOutputStream(); "
                + "defer use(out); free out; return 0; } }", "pending deferred call", UnfreedMode.OFF);
        for (UnfreedMode mode : UnfreedMode.values()) {
            reject("import ironwood.net.Socket; class Main { public static int main(String[] args) throws Exception { "
                    + "Socket socket = new Socket(); defer socket.getInputStream().close(); free socket; return 0; } }",
                    "pending deferred call", mode);
            reject("class Main { static Object published; static void publish(Object value) { published = value; } "
                    + "Main() { defer publish(this); } public static int main(String[] args) { return 0; } }",
                    "constructor may publish", mode);
        }
        // Operand-side writes are immediate for final-field and lexical-capture visitors.
        reject("class Main { final int x; Main() { defer action(this.x = 1); this.x = 2; } "
                + "static void action(int value) {} public static int main(String[] args) { return 0; } }",
                "final field", UnfreedMode.OFF);
        accept("interface Runnable { void run(); } class Main { static void action(Runnable r) { r.run(); } public static int main(String[] args) { "
                + "int number = 7; defer action(new Runnable() { @Override public void run() { int x = number; } }); return 0; } }");
        // Publication through a deferred-only callee contributes to its enclosing effect summary.
        reject(prefix.replace("public static int main", "static void later(Box b) { defer publish(b); } public static int main")
                + "later(b); free b; return 0; } }", "free", UnfreedMode.OFF);
        reject(prefix + "defer use(new Box()); return 0; } }", "without being freed", UnfreedMode.ERROR);
    }

    static void typedCaptures() {
        CompilationArtifact artifact = accept("class Main { static int next() { return 7; } static void action(int n) {} "
                + "static int test(boolean early) { int x = next(); defer action(x); x = 9; if (early) return x; return 2; } "
                + "public static int main(String[] args) { return test(args.length == 0); } }");
        var method = artifact.program().orElseThrow().functions().stream()
                .filter(f -> f.ownerClass().equals("Main") && f.sourceName().equals("test"))
                .findFirst().orElseThrow();
        List<IrCallInstruction> calls = new ArrayList<>();
        method.blocks().forEach(block -> {
            List<IrInstruction> instructions = new ArrayList<>(block.instructions());
            if (block.terminator() instanceof IrInvokeTerminator invoked) instructions.add(invoked.call());
            instructions.stream().filter(IrCallInstruction.class::isInstance)
                    .map(IrCallInstruction.class::cast).forEach(calls::add);
        });
        var captures = calls.stream().filter(c -> c.targetLinkageName().contains("next")).toList();
        var cleanups = calls.stream().filter(c -> c.targetLinkageName().contains("action")).toList();
        require(captures.size() == 1 && cleanups.size() == 2, "capture/replay count: " + calls);
        var captured = captures.getFirst().result().orElseThrow();
        require(cleanups.stream().allMatch(c -> c.arguments().getFirst().equals(captured)), "cleanup reread reassigned x");
        require(cleanups.stream().noneMatch(c -> c.arguments().getFirst() instanceof IrConstant), "late constant captured");
        require(cleanups.stream().allMatch(c -> c.sourceSpan().equals(cleanups.getFirst().sourceSpan())),
                "cleanup copies lost their original call span");
    }

    static void artifacts() throws Exception {
        java.nio.file.Path root = java.nio.file.Path.of("integration-tests/target/defer-artifacts").toAbsolutePath();
        java.nio.file.Files.createDirectories(root.resolve("sources/library"));
        String library = "// SPDX-License-Identifier: MIT OR Apache-2.0\npackage library; public class Cleanup { static int calls; "
                + "public static <T> void action(T value) { calls++; } "
                + "public static int work(int value) { defer Cleanup.<int>action(value); "
                + "if (value == 0) return 0; { defer Cleanup.<int>action(value); } return value; } "
                + "public static int count() { return calls; } }";
        var source = root.resolve("sources/library/Cleanup.iron");
        java.nio.file.Files.writeString(source, library);
        var application = root.resolve("Main.iron");
        java.nio.file.Files.writeString(application, "// SPDX-License-Identifier: MIT OR Apache-2.0\nimport library.Cleanup; class Main { "
                + "public static int main(String[] args) { Cleanup.work(2); Cleanup.work(0); "
                + "return Cleanup.count() == 3 ? 42 : 1; } }");
        cli(0, "--source-path", root.resolve("sources").toString(), application.toString(),
                "-d", root.resolve("source-classes").toString());
        linkAndRun(root, "source", root.resolve("source-classes").toString());
        cli(0, source.toString(), "-d", root.resolve("library-classes").toString());
        var archive = root.resolve("cleanup.ironjar");
        IronJar.create(archive, List.of(root.resolve("library-classes")));
        for (var dependency : List.of(root.resolve("library-classes"), archive)) {
            String kind = dependency.equals(archive) ? "archive" : "class";
            var classes = root.resolve(kind + "-classes");
            cli(0, "-cp", dependency.toString(), "--source-path", root.resolve("absent").toString(),
                    application.toString(), "-d", classes.toString());
            linkAndRun(root, kind, classes + java.io.File.pathSeparator + dependency);
        }
        // Deliberately invalid semantic source inside otherwise valid format-1 artifacts
        // must be rechecked both during dependency loading and final linking.
        String invalid = library.replace("defer Cleanup.<int>action(value);",
                "Object captured = new Object(); defer action(captured); free captured;");
        var unit = SourceParser.parse(SourceFile.of("library/Cleanup.iron", invalid)).unit().orElseThrow();
        var badClass = root.resolve("invalid/library/Cleanup.ironclass");
        IronClass.write(badClass, unit, "library.Cleanup");
        var badArchive = root.resolve("invalid.ironjar");
        IronJar.create(badArchive, List.of(badClass));
        for (var dependency : List.of(root.resolve("invalid"), badArchive)) {
            String errors = cli(1, "-cp", dependency.toString(), "--source-path", root.resolve("absent").toString(),
                    application.toString(), "-d", root.resolve("invalid-output").toString());
            require(errors.contains("pending deferred call"), errors);
            errors = cli(1, "--link", "-cp", root.resolve("class-classes")
                    + java.io.File.pathSeparator + dependency, "--main-class", "Main",
                    "-o", root.resolve("invalid-program").toString());
            require(errors.contains("pending deferred call"), errors);
        }
        // Format-1 pre-reservation source uses defer as an identifier. Construct the
        // old container layout directly so the current parser cannot sanitize it.
        var legacy = root.resolve("legacy/library/Cleanup.ironclass");
        java.nio.file.Files.createDirectories(legacy.getParent());
        try (var zip = new java.util.zip.ZipOutputStream(java.nio.file.Files.newOutputStream(legacy))) {
            for (var entry : java.util.Map.of("META-INF/IRONWOOD.MF", "Ironwood-Class-Format: 1\n",
                    "META-INF/types.tsv", "library.Cleanup\tclass\n",
                    "source/Cleanup.iron", library.replace("static int calls;", "static int calls; static int defer;")
                            .replace("defer Cleanup.<int>action(value);", "Cleanup.<int>action(value);")).entrySet()) {
                zip.putNextEntry(new java.util.zip.ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        var legacyArchive = root.resolve("legacy.ironjar");
        IronJar.create(legacyArchive, List.of(legacy));
        for (var dependency : List.of(root.resolve("legacy"), legacyArchive)) {
            String errors = cli(1, "-cp", dependency.toString(), "--source-path", root.resolve("absent").toString(),
                    application.toString(), "-d", root.resolve("legacy-output").toString());
            require(errors.contains("expected member name"), errors);
        }
    }

    private static void linkAndRun(java.nio.file.Path root, String name, String classPath) throws Exception {
        var executable = root.resolve(name + "-program");
        cli(0, "--link", "-cp", classPath, "--main-class", "Main", "-O3", "-o", executable.toString());
        Process process = new ProcessBuilder(executable.toString()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        require(process.waitFor() == 42, name + ": " + output);
    }

    private static String cli(int expected, String... arguments) {
        var errors = new java.io.ByteArrayOutputStream();
        int result = Main.run(arguments, new java.io.PrintStream(new java.io.ByteArrayOutputStream()),
                new java.io.PrintStream(errors));
        String message = errors.toString(java.nio.charset.StandardCharsets.UTF_8);
        require(result == expected, String.join(" ", arguments) + ": " + message);
        return message;
    }

    private static CompilationArtifact accept(String source) {
        CompilationArtifact result = compile(source, UnfreedMode.OFF);
        require(result.successful(), result.diagnostics().toString());
        return result;
    }

    private static CompilationArtifact compile(String source, UnfreedMode mode) {
        return new CompilerPipeline(mode).compile(SourceFile.of("test/Main.iron", source));
    }

    private static void reject(String source, String diagnostic, UnfreedMode mode) {
        CompilationArtifact result = compile(source, mode);
        require(!result.successful() && result.diagnostics().stream().anyMatch(d -> d.isError()
                && d.message().contains(diagnostic)), diagnostic + ": " + result.diagnostics());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
