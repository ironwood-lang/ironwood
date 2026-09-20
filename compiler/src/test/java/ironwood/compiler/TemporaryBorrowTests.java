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

final class TemporaryBorrowTests {
    private TemporaryBorrowTests() {}

    private static final String TEMPLATE = """
            class Item { int value; }
            class Wrapper {
                private final Item item;
                Wrapper(Item item) { this.item = item; }
                void touch() { this.item.value++; }
                void close() { if (this.item.value == 99) throw Case.FAILURE; }
            }
            public class Case {
                static Object saved;
                static final RuntimeException FAILURE = new RuntimeException();
                static void publish(Object item) { saved = item; }
                static void use(Item item, Item other, boolean flag) { %s }
                public static int check(boolean flag) {
                    Item item = new Item(); defer free item;
                    Item other = new Item(); defer free other;
                    use(item, other, flag); return item.value;
                }
            }
            """;

    static void safety() throws Exception {
        List<String> safe = List.of(
                "Wrapper wrapper = new Wrapper(item); free wrapper;",
                "Wrapper wrapper = new Wrapper(item); defer free wrapper; wrapper.touch();",
                "Wrapper wrapper = new Wrapper(item); try { wrapper.touch(); } finally { free wrapper; }",
                "Wrapper wrapper = new Wrapper(item); defer free wrapper; defer wrapper.close(); if (flag) return; wrapper.touch();",
                "Wrapper wrapper = new Wrapper(item); defer free wrapper; defer wrapper.close(); if (flag) throw FAILURE;",
                "if (flag) { Wrapper wrapper = new Wrapper(item); defer free wrapper; wrapper.touch(); }",
                "for (int i = 0; i < 2; i++) { Wrapper wrapper = new Wrapper(item); defer free wrapper; wrapper.touch(); }",
                "Wrapper wrapper = new Wrapper(item); try { try { wrapper.touch(); } finally { wrapper.close(); } } finally { free wrapper; }");
        List<String> unsafe = List.of(
                "Wrapper wrapper = new Wrapper(item); defer free wrapper; saved = wrapper;",
                "Wrapper wrapper = new Wrapper(item); defer free wrapper; publish(wrapper);",
                "Wrapper wrapper = new Wrapper(item); saved = wrapper;",
                "Wrapper wrapper = new Wrapper(item); if (flag) return; free wrapper;",
                "Wrapper wrapper = new Wrapper(item); if (flag) throw FAILURE; free wrapper;",
                "Wrapper wrapper = new Wrapper(item); try { wrapper.touch(); } finally { wrapper.close(); free wrapper; }",
                "Wrapper wrapper = new Wrapper(item); defer free wrapper; publish(item);",
                "Wrapper wrapper = new Wrapper(item); defer free wrapper; if (flag) saved = item;",
                "Wrapper wrapper = new Wrapper(item); Wrapper[] slots = new Wrapper[]{wrapper}; defer free slots; defer free wrapper;",
                "Wrapper wrapper = new Wrapper(item); defer free wrapper; Wrapper alias = flag ? wrapper : new Wrapper(other); saved = alias;");
        List<SourceFile> accepted = cases(safe);
        List<SourceFile> rejected = cases(unsafe);
        String nativeSource = Files.readString(Path.of("integration-tests/cases/borrow_helper_boundaries/Lifecycle.iron"));
        String fieldSource = """
                class Item { int value; }
                class Wrapper {
                    private final Item item;
                    Wrapper(Item item) { this.item = item; }
                    void touch() { this.item.value++; }
                }
                class Owner {
                    private final Item item = new Item();
                    static Object saved;
                    void use() {
                        Wrapper wrapper = new Wrapper(this.item); defer free wrapper;
                        wrapper.touch();
                    }
                    destructor { free this.item; }
                }
                class Main {
                    public static int main(String[] args) {
                        Owner owner = new Owner(); defer free owner; owner.use(); return 0;
                    }
                }
                """;
        for (UnfreedMode mode : UnfreedMode.values()) {
            // Unqualified access isolates publication from the unrelated
            // potentially throwing null check on a qualified field read.
            String destructorPublication = TEMPLATE.formatted("Wrapper wrapper = new Wrapper(item); defer free wrapper;")
                    .replace("void touch()", "static Item saved; destructor { saved = item; } void touch()");
            CompilationArtifact destructive = new CompilerPipeline(mode).compile(SourceFile.of("test/Case.iron",
                    destructorPublication + "\nclass Main { public static int main(String[] args) { return Case.check(false); } }"));
            require(destructive.diagnostics().stream().anyMatch(d -> d.isError() && d.message().contains("free")),
                    mode + " accepted publication from borrower destruction: " + destructive.diagnostics());
            CompilationArtifact field = new CompilerPipeline(mode).compile(SourceFile.of("test/Main.iron", fieldSource));
            require(field.successful(), mode + " owned field temporary: " + field.diagnostics());
            CompilationArtifact publishedField = new CompilerPipeline(mode).compile(SourceFile.of("test/Main.iron",
                    fieldSource.replace("wrapper.touch();", "saved = wrapper; wrapper.touch();")));
            require(!publishedField.successful(), mode + " accepted publication of an owned field borrower");
            CompilationArtifact lifecycle = new CompilerPipeline(mode).compile(SourceFile.of("test/Main.iron", nativeSource));
            require(lifecycle.successful(), mode + " lifecycle: " + lifecycle.diagnostics());
            CompilationArtifact positive = new CompilerPipeline(mode).compile(accepted);
            require(positive.successful(), mode + " confined helpers: " + positive.diagnostics());
            CompilationArtifact negative = new CompilerPipeline(mode).compile(rejected);
            require(!negative.successful(), mode + " accepted escaping/incomplete helper");
            for (int index = 0; index < unsafe.size(); index++) {
                Path path = rejected.get(index).path();
                require(negative.diagnostics().stream().anyMatch(d -> d.isError() && d.source() != null
                                && d.source().path().equals(path) && d.message().contains("free")),
                        mode + " lost safety error for " + index + ": " + negative.diagnostics());
            }
        }
    }

    private static List<SourceFile> cases(List<String> bodies) {
        return cases(TEMPLATE, bodies);
    }

    private static List<SourceFile> cases(String template, List<String> bodies) {
        List<SourceFile> result = new ArrayList<>();
        StringBuilder driver = new StringBuilder("class Driver { public static int main(String[] args) {");
        for (int index = 0; index < bodies.size(); index++) {
            String name = "temporary" + index;
            result.add(SourceFile.of("test/" + name + "/Case.iron", "package " + name + ";\n" + template.formatted(bodies.get(index))));
            driver.append(name).append(".Case.check(args.length == 0);");
        }
        driver.append("return 0; } }");
        result.add(SourceFile.of("test/Driver.iron", driver.toString()));
        return result;
    }

    static void lists() {
        String template = """
                import ironwood.ds.ArrayList;
                class Item { int value; }
                class Owner {
                    private final Item item = new Item();
                    Item item() { return this.item; }
                    ArrayList<Item> values() {
                        ArrayList<Item> list = new ArrayList<Item>(1);
                        try { list.add(this.item()); return list; }
                        catch (RuntimeException | Error failure) { free list; throw failure; }
                    }
                    destructor { free this.item; }
                }
                public class Case {
                    static Object saved;
                    static final RuntimeException FAILURE = new RuntimeException();
                    static void publish(Object value) { saved = value; }
                    static Item use(Owner owner, Owner other, boolean flag) { %s }
                    public static int check(boolean flag) {
                        Owner owner = new Owner(); defer free owner;
                        Owner other = new Owner(); defer free other;
                        Item value = use(owner, other, flag);
                        return value == null ? 0 : value.value;
                    }
                }
                """;
        List<String> safe = List.of(
                "ArrayList<Item> list = owner.values(); defer free list; return list.get(0);",
                "ArrayList<Item> list = new ArrayList<Item>(1); defer free list; list.add(owner.item()); return list.get(0);",
                "ArrayList<Item> list = owner.values(); try { return list.get(0); } finally { free list; }",
                "ArrayList<Item> list = owner.values(); defer free list; if (flag) return null; Item item = list.get(0); list.clear(); list.add(item); return list.get(0);",
                "ArrayList<Item> list = owner.values(); defer free list; if (flag) throw FAILURE; return list.get(0);",
                "ArrayList<Item> list = owner.values(); defer free list; owner = other; return list.get(0);",
                "ArrayList<Item> list = new ArrayList<Item>(1); defer free list; list.add(flag ? owner.item() : null); return list.get(0);");
        List<String> unsafe = List.of(
                "ArrayList<Item> list = owner.values(); defer free list; saved = list; return list.get(0);",
                "ArrayList<Item> list = owner.values(); defer free list; publish(list); return list.get(0);",
                "ArrayList<Item> list = owner.values(); defer free list; publish(list.get(0)); return list.get(0);",
                "ArrayList<Item> list = owner.values(); defer free list; list.add(other.item()); return list.get(0);",
                "ArrayList<Item> list = owner.values(); if (flag) return list.get(0); Item value = list.get(0); free list; return value;",
                "ArrayList<Item> list = owner.values(); defer free list; free owner; return list.get(0);",
                "ArrayList<Item> list = owner.values(); defer free list; Item value = list.get(0); free value; return value;",
                "ArrayList<Item> list = owner.values(); defer free list; Item item = list.get(0); free list; publish(item); return item;");
        for (UnfreedMode mode : UnfreedMode.values()) {
            CompilationArtifact positive = new CompilerPipeline(mode).compile(cases(template, safe));
            require(positive.successful(), mode + " list helpers: " + positive.diagnostics());
            String callerAfterFree = template.replace("Owner owner = new Owner(); defer free owner;", "Owner owner = new Owner();")
                    .replace("return value == null", "free owner; return value == null");
            CompilationArtifact lateUse = new CompilerPipeline(mode).compile(cases(callerAfterFree, List.of(safe.getFirst())));
            require(!lateUse.successful(), mode + " accepted returned element after caller owner free");
            String callerFreesBorrow = template.replace("return value == null", "free value; return value == null");
            CompilationArtifact independentFree = new CompilerPipeline(mode).compile(cases(callerFreesBorrow, List.of(safe.getFirst())));
            require(!independentFree.successful(), mode + " accepted independent free of returned element");
            List<SourceFile> sources = cases(template, unsafe);
            CompilationArtifact negative = new CompilerPipeline(mode).compile(sources);
            for (int index = 0; index < unsafe.size(); index++) {
                Path path = sources.get(index).path();
                require(negative.diagnostics().stream().anyMatch(d -> d.isError() && d.source() != null
                                && d.source().path().equals(path) && d.message().contains("free")),
                        mode + " lost list safety error for " + index + ": " + negative.diagnostics());
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    static void artifacts() throws Exception {
        Path root = Path.of("integration-tests/target/temporary-borrow-artifacts").toAbsolutePath();
        List<String> libraries = List.of("constructorcase", "listcase");
        List<String> fixtures = List.of("Lifecycle.iron", "ListLifecycle.iron");
        List<Path> sources = new ArrayList<>();
        for (int index = 0; index < libraries.size(); index++) {
            Path source = root.resolve("sources/" + libraries.get(index) + "/Main.iron");
            Files.createDirectories(source.getParent());
            String fixture = Files.readString(Path.of("integration-tests/cases/borrow_helper_boundaries/" + fixtures.get(index)))
                    .replace("class Main {", "public class Main {").replace("static int check()", "public static int check()");
            Files.writeString(source, "// SPDX-License-Identifier: MIT OR Apache-2.0\npackage " + libraries.get(index) + ";\n" + fixture);
            sources.add(source);
        }
        Path application = root.resolve("Driver.iron");
        Files.writeString(application, """
                // SPDX-License-Identifier: MIT OR Apache-2.0
                class Driver {
                    public static int main(String[] args) {
                        int first = constructorcase.Main.check();
                        if (first != 42) return first;
                        return listcase.Main.check();
                    }
                }
                """);
        cli("--unfreed=error", "--source-path", root.resolve("sources").toString(), application.toString(),
                "-d", root.resolve("source-classes").toString());
        linkAndRun(root, "source", root.resolve("source-classes").toString());
        cli("--unfreed=error", sources.get(0).toString(), sources.get(1).toString(),
                "-d", root.resolve("library-classes").toString());
        Path archive = root.resolve("helpers.ironjar");
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
        cli("--link", "--unfreed=error", "-cp", classPath, "--main-class", "Driver", "-O3", "-o", executable.toString());
        Process process = new ProcessBuilder(executable.toString()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        require(process.waitFor() == 42 && output.isEmpty(), name + ": " + output);
    }

    private static void cli(String... arguments) {
        var errors = new ByteArrayOutputStream();
        int result = Main.run(arguments, new PrintStream(new ByteArrayOutputStream()), new PrintStream(errors));
        require(result == 0, String.join(" ", arguments) + ": " + errors.toString(StandardCharsets.UTF_8));
    }
}
