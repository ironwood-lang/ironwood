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

    private static final String TEMPLATE = """
            import ironwood.pool.*;
            class Item { }
            class Builder implements ObjectBuilder<Item> {
                @Override public Item newInstance() { return new Item(); }
            }
            public class Case {
                static Object saved;
                static void publish(Object value) { saved = value; }
                static void use(ObjectPool<Item> pool, ObjectPool<Item> other, boolean flag) {
                    %s
                }
                public static int check(boolean flag) {
                    Builder builder = new Builder(); defer free builder;
                    ArrayObjectPool<Item> pool = new ArrayObjectPool<Item>(1, builder); defer free pool;
                    MultiArrayObjectPool<Item> other = new MultiArrayObjectPool<Item>(1, builder); defer free other;
                    use(pool, other, flag);
                    return 0;
                }
            }
            """;

    static void safety() throws Exception {
        List<String> bodies = List.of(
                "Item item = pool.get(); ObjectPool<Item> alias = pool; alias.release(item);",
                "Item item = pool.get(); pool.release(item); saved = pool;",
                "Item item = pool.get(); pool.release(item); saved = item;",
                "Item item = pool.get(); defer pool.release(item); publish(item);",
                "Item item = pool.get(); other.release(item);",
                "Item item = flag ? pool.get() : other.get(); pool.release(item);",
                "ObjectPool<Item> selected = flag ? pool : other; Item item = selected.get(); pool.release(item);",
                "Item item = pool.get(); try { if (flag) pool = other; } finally { pool.release(item); }",
                "Item item = pool.get(); while (flag) { pool = other; flag = false; } pool.release(item);",
                "Item item = pool.get(); ObjectPool<Item> selected = other; defer selected.release(item); selected = pool;",
                "Item item = pool.get(); defer pool.release(item); free item;",
                "Item item = new Item(); pool.release(item);",
                "Item item = pool.get(); pool.release(item); publish(pool);");
        List<SourceFile> cases = new ArrayList<>();
        for (String body : bodies) addCase(cases, TEMPLATE.formatted(body));
        // Fresh results must not hide independent publication by the same call.
        for (String published : List.of("pool", "item")) {
            addCase(cases, TEMPLATE.formatted("Item item = pool.get(); defer pool.release(item); "
                    + "publish(" + published + "); return new String(\"copy\");")
                    .replace("static void use(", "static String use(")
                    .replace("use(pool, other, flag);", "String text = use(pool, other, flag); defer free text;"));
        }
        addCase(cases, """
                import ironwood.pool.*;
                class Item { }
                class Builder implements ObjectBuilder<Item> {
                    @Override public Item newInstance() { return new Item(); }
                }
                class Owner {
                    private final ArrayObjectPool<Item> first;
                    private final ArrayObjectPool<Item> second;
                    Owner(Builder builder) {
                        this.first = new ArrayObjectPool<Item>(1, builder);
                        this.second = new ArrayObjectPool<Item>(1, builder);
                    }
                    void use() { this.second.release(this.first.get()); }
                    destructor { free this.second; free this.first; }
                }
                public class Case {
                    public static int check(boolean flag) {
                        Builder builder = new Builder(); defer free builder;
                        Owner owner = new Owner(builder); defer free owner;
                        owner.use(); return 0;
                    }
                }
                """);
        addCase(cases, """
                import ironwood.pool.*;
                class Item { }
                class Builder implements ObjectBuilder<Item> {
                    @Override public Item newInstance() { return new Item(); }
                }
                class Retaining implements ObjectPool<Item> {
                    static Object saved;
                    private final ObjectPool<Item> delegate;
                    Retaining(ObjectPool<Item> delegate) { this.delegate = delegate; }
                    @Override public Item get() { return this.delegate.get(); }
                    @Override public void release(Item item) { saved = item; this.delegate.release(item); }
                }
                public class Case {
                    static void use(ObjectPool<Item> pool) { Item item = pool.get(); pool.release(item); }
                    public static int check(boolean flag) {
                        Builder builder = new Builder(); defer free builder;
                        ArrayObjectPool<Item> pool = new ArrayObjectPool<Item>(1, builder); defer free pool;
                        Retaining retaining = new Retaining(pool); defer free retaining;
                        use(flag ? pool : retaining); return 0;
                    }
                }
                """);
        StringBuilder driver = new StringBuilder("class Driver { public static int main(String[] args) {");
        for (int i = 0; i < cases.size(); i++) driver.append("poolcase").append(i)
                .append(".Case.check(args.length == 0);");
        driver.append("return 0; } }");
        List<SourceFile> sources = new ArrayList<>(cases);
        sources.add(SourceFile.of("test/Driver.iron", driver.toString()));
        String accepted = Files.readString(Path.of("integration-tests/cases/pool_release_helpers.iron"));
        for (UnfreedMode mode : UnfreedMode.values()) {
            CompilationArtifact positive = new CompilerPipeline(mode).compile(SourceFile.of("test/Main.iron", accepted));
            require(positive.successful(), mode + " safe helpers: " + positive.diagnostics());
            CompilationArtifact negative = new CompilerPipeline(mode).compile(sources);
            require(!negative.successful(), mode + " accepted unsafe pool helpers");
            for (int i = 0; i < cases.size(); i++) {
                Path path = cases.get(i).path();
                var diagnostics = negative.diagnostics().stream().filter(d -> d.isError()
                        && d.source() != null && d.source().path().equals(path)).toList();
                if (i == 0) require(diagnostics.isEmpty(), mode + " safe alias control: " + diagnostics);
                else require(diagnostics.stream().anyMatch(d -> d.message().contains("free")
                                || d.message().contains("owned by another pool")),
                        mode + " case " + i + " lost its safety diagnostic: " + diagnostics);
            }
        }
    }

    private static void addCase(List<SourceFile> cases, String source) {
        String name = "poolcase" + cases.size();
        cases.add(SourceFile.of("test/" + name + "/Case.iron", "package " + name + ";\n" + source));
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
        Files.writeString(application, """
                // SPDX-License-Identifier: MIT OR Apache-2.0
                import ironwood.pool.*;
                import library.Messages;
                class Builder implements ObjectBuilder<StringBuilder> {
                    @Override public StringBuilder newInstance() { return new StringBuilder(); }
                }
                class Main {
                    static int check() {
                        Builder builder = new Builder(); defer free builder;
                        ArrayObjectPool<StringBuilder> pool = new ArrayObjectPool<StringBuilder>(1, builder); defer free pool;
                        String first = Messages.message(pool, "Ironwood"); defer free first;
                        String next = Messages.message(pool, "Reader"); defer free next;
                        return first.equals("Hello Ironwood") && next.equals("Hello Reader") ? 42 : 1;
                    }
                    public static int main(String[] args) {
                        long live = System.liveAllocationCount();
                        int result = check();
                        return result == 42 && System.liveAllocationCount() == live ? 42 : 2;
                    }
                }
                """);
        cli("--unfreed=error", "--source-path", root.resolve("sources").toString(), application.toString(),
                "-d", root.resolve("source-classes").toString());
        linkAndRun(root, "source", root.resolve("source-classes").toString());
        cli("--unfreed=error", library.toString(), "-d", root.resolve("library-classes").toString());
        Path archive = root.resolve("messages.ironjar");
        IronJar.create(archive, List.of(root.resolve("library-classes")));
        for (Path dependency : List.of(root.resolve("library-classes"), archive)) {
            String name = dependency.equals(archive) ? "archive" : "class";
            Path classes = root.resolve(name + "-classes");
            cli("--unfreed=error", "--source-path", root.resolve("absent").toString(),
                    "-cp", dependency.toString(), application.toString(), "-d", classes.toString());
            linkAndRun(root, name, classes + java.io.File.pathSeparator + dependency);
        }
    }

    private static void linkAndRun(Path root, String name, String classPath) throws Exception {
        Path executable = root.resolve(name + "-program");
        cli("--link", "--unfreed=error", "-cp", classPath, "--main-class", "Main", "-O3", "-o", executable.toString());
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
