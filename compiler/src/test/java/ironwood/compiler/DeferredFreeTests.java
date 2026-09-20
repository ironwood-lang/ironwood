// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.ast.DeferredFreeStatement;
import ironwood.compiler.ast.ClassDeclaration;
import ironwood.compiler.ir.IrFreeInstruction;
import ironwood.compiler.source.SourceFile;

import java.util.List;

/** Milestone 1 combined call/free semantics; no performance-acceptance or adoption gate. */
final class DeferredFreeTests {
    private DeferredFreeTests() {}

    private static final String PREFIX = "class Box { int x; void touch() { this.x++; } } class Main { "
            + "static Box saved; static void observe(Box value) {} static void publish(Box value) { saved = value; } "
            + "static void pair(Box value, int n) {} "
            + "static Box fresh() { return new Box(); } static int number(Box value) { return 1; } "
            + "public static int main(String[] args) throws Exception { ";

    static void bindings() {
        String source = PREFIX + "Box b = new Box(); defer free b; return 0; } }";
        var parsed = SourceParser.parse(SourceFile.of("test/Main.iron", source));
        require(parsed.diagnostics().isEmpty(), parsed.diagnostics().toString());
        var owner = (ClassDeclaration) parsed.unit().orElseThrow().declarations().get(1);
        require(owner.methods().getLast().body().orElseThrow().statements().get(1)
                instanceof DeferredFreeStatement, "missing local-bound deferred-free AST");
        for (String action : List.of("defer free;", "defer free (b);", "defer free this;",
                "defer free b.x;", "defer free list[0];", "defer free new Box();",
                "defer free fresh();", "defer free b = null;", "defer free b",
                "if (true) defer free b;", "while (true) defer free b;",
                "for (;;) defer free b;", "do defer free b; while (false);",
                "label: defer free b;", "switch (1) { case 1: defer free b; }",
                "switch (1) { default -> defer free b; }")) {
            var invalid = SourceParser.parse(SourceFile.of("test/Main.iron", source.replace("defer free b;", action)));
            require(!invalid.diagnostics().isEmpty(), "accepted forbidden syntax: " + action);
        }
        for (UnfreedMode mode : UnfreedMode.values()) {
            for (String write : List.of("b = b;", "b = null;", "b = new Box();",
                    "observe(b = b);", "int n = number(b = null);", "if (args.length == 0) { b = null; }",
                    "try {} finally { b = null; }", "b++;", "b += 1;",
                    "defer observe(b = null);")) {
                reject("Box b = new Box(); defer free b; " + write, "deferred free is pending", mode);
            }
            reject("int n = 1; defer free n;", "proven owned local reference", mode);
            reject("defer free missing;", "local variable", mode);
            reject("defer free saved;", "local variable", mode);
            reject("defer free args;", "proven owned local reference", mode);
            reject("Box b = null; defer free b;", "proven owned local reference", mode);
            accept("Box b = null; b = new Box(); defer free b; b.x = 2; b.touch();", mode);
            accept("Box b = new Box(); { defer free b; } b = new Box(); { defer free b; }", mode);
            accept("{ Box b = new Box(); defer free b; } { Box b = new Box(); defer free b; }", mode);
            accept("byte[] values = new byte[2]; defer free values; values[0] = 42;", mode);
            accept("Box b = null; for (int i = 0; i < 3; i++) { b = new Box(); { defer free b; } b = null; }", mode);
        }
    }

    static void ownership() {
        for (UnfreedMode mode : UnfreedMode.values()) {
            for (String body : List.of("Box b = new Box(); defer free b; free b;",
                    "Box b = new Box(); free b; defer free b;",
                    "Box b = new Box(); defer free b; defer free b;",
                    "Box b = new Box(); defer free b; Box a = b; defer free a;",
                    "Box b = new Box(); defer observe(b); defer free b;",
                    "Box b = new Box(); defer free b; publish(b);",
                    "Box b = new Box(); defer free b; defer publish(b);",
                    "Box b = new Box(); Box a = b; { defer free b; } observe(a);",
                    "Box b = new Box(); { defer free b; } observe(b);",
                    "Box b = new Box(); while (args.length > 0) { defer free b; }",
                    "Box b = new Box(); if (args.length == 0) { defer free b; } observe(b);",
                    "Box b = null; for (int i = 0; i < 3; i++) { b = new Box(); defer free b; }",
                    "Box b = new Box(); defer free b; if (args.length == 0) publish(b);",
                    "Box b = new Box(); defer free b; try { observe(b); } finally { free b; }",
                    "Box b = new Box(); try { defer free b; return 1; } finally { observe(b); }",
                    "Box b = new Box(); pair(b, switch (1) { default -> { defer free b; yield 1; } });",
                    "Box b = new Box(); b.x = switch (1) { default -> { defer free b; yield 1; } };",
                    "Box b = new Box(); Box result = switch (1) { default -> { defer free b; yield b; } };",
                    "Box b = new Box(); observe(switch (1) { default -> { defer free b; yield b; } });")) {
                reject(body, "free", mode);
            }
            for (String body : List.of("Box b = new Box(); defer free b; defer observe(b);",
                    "Box b = fresh(); defer free b; defer b.touch();",
                    "Box b = new Box(); defer free b; Box a = b; observe(a);",
                    "Box b = new Box(); defer free b; { Box a = b; observe(a); }",
                    "Box b = new Box(); defer free b; defer observe(b); defer b.touch();",
                    "Box b = new Box(); try { defer free b; observe(b); } finally {}",
                    "Box b = new Box(); { Box a = b; observe(a); } { defer free b; }",
                    "Box b = new Box(); { defer free b; if (args.length == 0) return 1; }",
                    "Box b = new Box(); try { defer free b; } finally { b = new Box(); free b; }",
                    "try { if (args.length == 0) return 1; } finally { Box b = new Box(); defer free b; defer observe(b); }",
                    "Box b = new Box(); defer free b; if (args.length == 0) throw new Exception();")) {
                accept(body, mode);
            }
            reject("@SuppressUnfreed Box b = new Box(); defer free b; b = null;",
                    "deferred free is pending", mode);
            String returned = "class Main { static Object make() { Object value = new Object(); defer free value; return value; } "
                    + "public static int main(String[] args) { return 0; } }";
            require(!compile(returned, mode).successful(), "returned freed allocation accepted");
            String attached = "class Main { private Object value = new Object(); destructor { Object alias = value; defer free alias; } "
                    + "public static int main(String[] args) { return 0; } }";
            require(!compile(attached, mode).successful(), "attached field alias accepted");
            String self = "class Main { destructor { Main alias = this; defer free alias; } "
                    + "public static int main(String[] args) { return 0; } }";
            require(!compile(self, mode).successful(), "destructor receiver alias accepted");
            String pooled = "import ironwood.pool.ArrayObjectPool; import ironwood.pool.ObjectBuilder; "
                    + "class Builder implements ObjectBuilder<Object> { @Override public Object newInstance() { return new Object(); } } "
                    + "class Main { public static int main(String[] args) { Builder builder = new Builder(); defer free builder; "
                    + "ArrayObjectPool<Object> pool = new ArrayObjectPool<Object>(1, 1, builder, 2.0f); defer free pool; "
                    + "Object item = pool.get(); defer pool.release(item); defer free item; return 0; } }";
            var invalidPoolFree = compile(pooled, mode);
            require(invalidPoolFree.diagnostics().stream().anyMatch(d -> d.isError()
                    && d.message().contains("cannot defer free of 'item'")), invalidPoolFree.diagnostics().toString());
            String borrowed = "import ironwood.net.Socket; class Main { public static int main(String[] args) throws Exception { "
                    + "Socket socket = new Socket(); defer free socket; defer socket.getInputStream().close(); return 0; } }";
            require(compile(borrowed, mode).successful(), "owner must outlive its earlier closing stream");
            require(!compile(borrowed.replace("defer free socket; defer socket.getInputStream().close();",
                    "defer socket.getInputStream().close(); defer free socket;"), mode).successful(),
                    "owner free precedes a pending stream observer");
            var invalidStream = compile(borrowed.replace("defer free socket;",
                    "defer free socket; ironwood.io.InputStream stream = socket.getInputStream(); defer free stream;"), mode);
            require(invalidStream.diagnostics().stream().anyMatch(d -> d.isError()
                    && d.message().contains("proven owned local reference")), invalidStream.diagnostics().toString());
            String nonterminating = PREFIX + "Box b = new Box(); defer free b; while (true) {} } }";
            require(compile(nonterminating, mode).successful(), "unreachable cleanup must not become a leak diagnostic");
        }
    }

    static void typedCleanup() {
        var artifact = compile(PREFIX + "Box b = new Box(); defer free b; defer observe(b); "
                + "if (args.length == 0) return 1; return 2; } }", UnfreedMode.ERROR);
        require(artifact.successful(), artifact.diagnostics().toString());
        var method = artifact.program().orElseThrow().functions().stream()
                .filter(f -> f.ownerClass().equals("Main") && f.sourceName().equals("main")).findFirst().orElseThrow();
        var frees = method.blocks().stream().flatMap(b -> b.instructions().stream())
                .filter(IrFreeInstruction.class::isInstance).map(IrFreeInstruction.class::cast).toList();
        require(frees.size() >= 2, "expected mutually exclusive cleanup copies");
        require(frees.stream().map(IrFreeInstruction::allocation).distinct().count() == 1,
                "deferred free introduced a separate reference capture");
        require(frees.stream().map(IrFreeInstruction::sourceSpan).distinct().count() == 1,
                "cleanup source provenance changed between copies");
    }

    static void artifacts() throws Exception {
        var root = java.nio.file.Path.of("integration-tests/target/defer-free-artifacts").toAbsolutePath();
        java.nio.file.Files.createDirectories(root.resolve("sources/library"));
        String library = "// SPDX-License-Identifier: MIT OR Apache-2.0\npackage library; public class Cleanup { "
                + "static int count; public static <T> void action(T value) { count++; } "
                + "public static void work(boolean early) { byte[] bytes = new byte[3]; defer free bytes; "
                + "defer Cleanup.<byte[]>action(bytes); if (early) return; "
                + "{ Object value = new Object(); defer free value; defer Cleanup.<Object>action(value); } } "
                + "public static int count() { return count; } }";
        var source = root.resolve("sources/library/Cleanup.iron");
        java.nio.file.Files.writeString(source, library);
        var application = root.resolve("Main.iron");
        java.nio.file.Files.writeString(application, "// SPDX-License-Identifier: MIT OR Apache-2.0\nimport library.Cleanup; class Main { "
                + "public static int main(String[] args) { long live = System.liveAllocationCount(); "
                + "Cleanup.work(true); Cleanup.work(false); return Cleanup.count() == 3 "
                + "&& System.liveAllocationCount() == live ? 42 : 1; } }");
        cli(0, "--unfreed=error", "--source-path", root.resolve("sources").toString(), application.toString(),
                "-d", root.resolve("source-classes").toString());
        linkAndRun(root, "source", root.resolve("source-classes").toString());
        cli(0, "--unfreed=error", source.toString(), "-d", root.resolve("library-classes").toString());
        var archive = root.resolve("cleanup.ironjar");
        IronJar.create(archive, List.of(root.resolve("library-classes")));
        for (var dependency : List.of(root.resolve("library-classes"), archive)) {
            String name = dependency.equals(archive) ? "archive" : "class";
            var classes = root.resolve(name + "-classes");
            cli(0, "--unfreed=error", "-cp", dependency.toString(), "--source-path", root.resolve("absent").toString(),
                    application.toString(), "-d", classes.toString());
            linkAndRun(root, name, classes + java.io.File.pathSeparator + dependency);
        }
        for (String invalid : List.of("bytes = null;", "free bytes;")) {
            String badSource = library.replace("defer free bytes;", "defer free bytes; " + invalid);
            java.nio.file.Files.writeString(source, badSource);
            String errors = cli(1, "--source-path", root.resolve("sources").toString(), application.toString(),
                    "-d", root.resolve("bad-source").toString());
            require(errors.contains("deferred free"), errors);
            var unit = SourceParser.parse(SourceFile.of("library/Cleanup.iron", badSource)).unit().orElseThrow();
            var badClass = root.resolve("invalid/library/Cleanup.ironclass");
            IronClass.write(badClass, unit, "library.Cleanup");
            var badArchive = root.resolve("invalid.ironjar");
            IronJar.create(badArchive, List.of(badClass));
            for (var dependency : List.of(root.resolve("invalid"), badArchive)) {
                errors = cli(1, "--unfreed=off", "-cp", dependency.toString(), "--source-path", root.resolve("absent").toString(),
                        application.toString(), "-d", root.resolve("bad-classes").toString());
                require(errors.contains("deferred free"), errors);
                errors = cli(1, "--link", "--unfreed=off", "-cp", root.resolve("class-classes")
                        + java.io.File.pathSeparator + dependency, "--main-class", "Main", "-o", root.resolve("bad-program").toString());
                require(errors.contains("deferred free"), errors);
            }
        }
        java.nio.file.Files.writeString(source, library);
    }

    private static void linkAndRun(java.nio.file.Path root, String name, String classPath) throws Exception {
        var executable = root.resolve(name + "-program");
        cli(0, "--link", "--unfreed=error", "-cp", classPath, "--main-class", "Main", "-O3", "-o", executable.toString());
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

    private static void accept(String body, UnfreedMode mode) {
        var result = compile(PREFIX + body + " return 0; } }", mode);
        require(result.successful(), body + ": " + result.diagnostics());
    }

    private static void reject(String body, String message, UnfreedMode mode) {
        var result = compile(PREFIX + body + " return 0; } }", mode);
        require(!result.successful() && result.diagnostics().stream().anyMatch(d -> d.isError()
                && d.message().contains(message)), body + ": " + result.diagnostics());
    }

    private static CompilationArtifact compile(String source, UnfreedMode mode) {
        return new CompilerPipeline(mode).compile(SourceFile.of("test/Main.iron", source));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
