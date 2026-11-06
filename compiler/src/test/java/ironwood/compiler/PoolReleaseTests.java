// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.source.SourceFile;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Safe wrapper extraction must not lose pool identity or hide real publication. */
final class PoolReleaseTests {
    private PoolReleaseTests() {}

    private static final String TEMPLATE = "import ironwood.pool.*;\nclass Item { }\nclass Builder implements ObjectBuilder<Item> {\n    @Override public Item newInstance() { return new Item(); }\n}\npublic class Case {\n    static Object saved;\n    static void publish(Object value) { saved = value; }\n    static void use(ObjectPool<Item> pool, ObjectPool<Item> other, boolean flag) {\n        %s\n    }\n    public static int check(boolean flag) {\n        Builder builder = new Builder(); ;\n        ArrayObjectPool<Item> pool = new ArrayObjectPool<Item>(1, builder); ;\n        MultiArrayObjectPool<Item> other = new MultiArrayObjectPool<Item>(1, builder); ;\n        use(pool, other, flag);\n        return 0;\n    }\n}\n";

    static void safety() throws Exception {
        String accepted = Files.readString(Path.of("integration-tests/cases/pool_release_helpers.iron"));
        var positive = new CompilerPipeline().compile(SourceFile.of("Main.iron", accepted));
        require(positive.successful(), positive.diagnostics().toString());
        String prefix = "import ironwood.pool.*; class Item {} class Builder implements ObjectBuilder<Item> { @Override public Item newInstance() { return new Item(); } } class Main { static Item saved; public static int main(String[] args) { use(); return 0; } static void use() { ObjectPool<Item> first = new ArrayObjectPool<Item>(1, new Builder()); ObjectPool<Item> second = new MultiArrayObjectPool<Item>(1, new Builder()); ";
        for (String body : List.of("Item item = first.get(); ObjectPool<Item> alias = first; alias.release(item);",
                "Item item = first.get(); saved = item; defer first.release(item);",
                "Item item = new Item(); first.release(item);")) {
            var shared = new CompilerPipeline().compile(SourceFile.of("Main.iron", prefix + body + " }}"));
            require(shared.successful(), shared.diagnostics().toString());
        }
        var wrong = new CompilerPipeline().compile(SourceFile.of("Main.iron", prefix + "second.release(first.get()); }}"));
        require(!wrong.valid() && wrong.diagnostics().stream().anyMatch(d -> d.message().contains("owned by another pool")), wrong.diagnostics().toString());
    }

    static void artifacts() throws Exception {
        Path root = Path.of("integration-tests/target/pool-release-artifacts").toAbsolutePath();
        Files.createDirectories(root.resolve("sources/library"));
        Path library = root.resolve("sources/library/Messages.iron");
        Files.writeString(library, """
                // SPDX-License-Identifier: MIT OR Apache-2.0
                package library;
                import ironwood.pool.ObjectPool;
                public class Messages {

                    public static String message(ObjectPool<StringBuilder> pool, String name) {

                        StringBuilder sb = pool.get();
                        defer pool.release(sb);
                        sb.setLength(0);
                        sb.append("Hello ").append(name);
                        return sb.toString();
                    }
                }
                """);
        Path application = root.resolve("Main.iron");
        Files.writeString(application, "// SPDX-License-Identifier: MIT OR Apache-2.0\nimport ironwood.pool.*;\nimport library.Messages;\nclass Builder implements ObjectBuilder<StringBuilder> {\n    @Override public StringBuilder newInstance() { return new StringBuilder(); }\n}\nclass Main {\n    static int check() {\n        Builder builder = new Builder(); ;\n        ArrayObjectPool<StringBuilder> pool = new ArrayObjectPool<StringBuilder>(1, builder); ;\n        String first = Messages.message(pool, \"Ironwood\"); ;\n        String next = Messages.message(pool, \"Reader\"); ;\n        return first.equals(\"Hello Ironwood\") && next.equals(\"Hello Reader\") ? 42 : 1;\n    }\n    public static int main(String[] args) {\n        long live = System.liveAllocationCount();\n        int result = check();\n        return result == 42 ? 42 : 2;\n    }\n}\n");
        cli("--source-path", root.resolve("sources").toString(), application.toString(),
                "-d", root.resolve("source-classes").toString());
        linkAndRun(root, "source", root.resolve("source-classes").toString());
        cli(library.toString(), "-d", root.resolve("library-classes").toString());
        Path archive = root.resolve("messages.ironjar");
        IronJar.create(archive, List.of(root.resolve("library-classes")));
        for (Path dependency : List.of(root.resolve("library-classes"), archive)) {
            String name = dependency.equals(archive) ? "archive" : "class";
            Path classes = root.resolve(name + "-classes");
            cli("--source-path", root.resolve("absent").toString(),
                    "-cp", dependency.toString(), application.toString(), "-d", classes.toString());
            linkAndRun(root, name, classes + java.io.File.pathSeparator + dependency);
        }
    }

    private static void linkAndRun(Path root, String name, String classPath) throws Exception {
        Path executable = root.resolve(name + "-program");
        cli("--link", "-cp", classPath, "--main-class", "Main", "-O3", "-o", executable.toString());
        Process process = new ProcessBuilder(executable.toString()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        require(process.waitFor() == 42 && output.isEmpty(), name + ": " + output);
    }

    private static void cli(String... arguments) {
        var errors = new ByteArrayOutputStream();
        int result = Main.run(arguments, new PrintStream(new ByteArrayOutputStream()), new PrintStream(errors));
        require(result == 0, String.join(" ", arguments) + ": " + errors.toString(StandardCharsets.UTF_8));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
