// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.source.SourceFile;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** A receiver stored in fields it owns must not escape through those fields. */
final class ReceiverPublicationTests {
    private static final String CONSTRUCTOR = "constructor may publish in-progress 'this'";
    private static final String DESTRUCTOR = "destructor may publish or resurrect 'this'";
    private static final String MAIN = """
            class Main {
                static RuntimeException failure = new RuntimeException();
                public static int main(String[] args) {
                    try { new Parent(); } catch (RuntimeException expected) { return 1; }
                    return 0;
                }
            }
            """;
    private static final String PUBLISHING_HELPER = """
            class Helper {
                static Object saved;
                private Object owner;
                Helper(Object owner) { this.owner = owner; reset(); }
                private void reset() { saved = this.owner; throw Main.failure; }
            }
            """;

    private ReceiverPublicationTests() { }

    /** Each source was admitted before the retention-aware publication check. */
    static void fieldAliasesAreRejected() {
        List<String> constructors = List.of(
                PUBLISHING_HELPER + """
                        class Parent {
                            private Parent self; private Helper helper;
                            Parent() { this.self = this; this.helper = new Helper(this.self); }
                            destructor { free this.helper; }
                        }
                        """,
                """
                        class Parent {
                            static Parent saved; private Parent self;
                            Parent() { this.self = this; saved = this.self; throw Main.failure; }
                        }
                        """,
                """
                        class Parent {
                            static Parent saved; private Parent self;
                            Parent() { this.self = this; publish(this.self); }
                            static void publish(Parent value) { saved = value; throw Main.failure; }
                        }
                        """,
                """
                        class Parent {
                            static Parent saved; private Parent self;
                            Parent() { this.self = this; this.self.leak(); }
                            void leak() { saved = this; throw Main.failure; }
                        }
                        """,
                """
                        class Parent {
                            static Parent saved; private Parent self;
                            Parent() { this.self = this; leak(); }
                            private void leak() { saved = this.self; throw Main.failure; }
                        }
                        """,
                PUBLISHING_HELPER + """
                        class Parent {
                            private Helper helper;
                            Parent() { this.helper = make(); }
                            private Helper make() { return new Helper(this); }
                            destructor { free this.helper; }
                        }
                        """,
                PUBLISHING_HELPER + """
                        class Parent {
                            private Helper helper;
                            Parent() { this.helper = make(this); }
                            private static Helper make(Parent owner) { return new Helper(owner); }
                            destructor { free this.helper; }
                        }
                        """,
                """
                        class Box { Object item; }
                        class Parent {
                            static Box box = new Box();
                            Parent() { this(true); }
                            Parent(boolean pick) {
                                Object target = pick ? (Object) box : (Object) this;
                                if (target instanceof Box chosen) { chosen.item = this; }
                                throw Main.failure;
                            }
                        }
                        """,
                """
                        class Holder {
                            private Parent owner;
                            Holder(Parent owner) { this.owner = owner; }
                            int peek() { return this.owner.value(); }
                        }
                        class Parent {
                            static Holder saved; private int marker = 7;
                            Parent() { Holder holder = new Holder(this); saved = holder; throw Main.failure; }
                            int value() { return this.marker; }
                        }
                        """,
                """
                        class Holder {
                            private Parent owner;
                            Holder(Parent owner) { this.owner = owner; }
                        }
                        class Parent {
                            static Holder saved; private Holder holder;
                            Parent() { this.holder = new Holder(this); saved = this.holder; throw Main.failure; }
                        }
                        """,
                """
                        class Base {
                            static Base saved; Base self;
                            void leak() { saved = this.self; throw Main.failure; }
                        }
                        class Parent extends Base {
                            Parent() { super(); this.self = this; leak(); }
                        }
                        """,
                """
                        class Parent {
                            static Parent saved; private Parent self;
                            Parent() {
                                this.self = this;
                                try { throw new RuntimeException(); }
                                catch (RuntimeException ignored) { saved = this.self; }
                                throw Main.failure;
                            }
                        }
                        """);
        for (String source : constructors) {
            for (UnfreedMode mode : UnfreedMode.values()) {
                requireRejected(mode, List.of(SourceFile.of("test/Main.iron", source + MAIN)), CONSTRUCTOR);
            }
        }
        List<String> destructors = List.of("""
                class Parent {
                    static Parent saved; private Parent self;
                    Parent() { this.self = this; }
                    destructor { saved = this.self; }
                }
                """, """
                class Holder {
                    private Parent owner;
                    Holder(Parent owner) { this.owner = owner; }
                }
                class Parent {
                    static Holder saved; private Holder holder;
                    Parent() { this.holder = new Holder(this); }
                    destructor { saved = this.holder; }
                }
                """, """
                class Parent extends Error {
                    static Throwable saved; private Parent self;
                    Parent() { this.self = this; }
                    destructor {
                        try { throw this; }
                        catch (Throwable caught) { saved = caught; }
                    }
                }
                """);
        for (String source : destructors) {
            String main = "class Main { public static int main(String[] args) { Parent value = new Parent(); "
                    + "free value; return 0; } }\n";
            for (UnfreedMode mode : UnfreedMode.values()) {
                requireRejected(mode, List.of(SourceFile.of("test/Main.iron", source + main)), DESTRUCTOR);
            }
        }
    }

    /** Retention that never reaches outside the receiver keeps compiling. */
    static void confinedFieldAliasesAreAccepted() {
        List<String> sources = List.of(
                """
                        class Parent {
                            private Parent self; private int marker = 7;
                            Parent() { this.self = this; if (this.self.marker == 8) throw Main.failure; }
                        }
                        """,
                """
                        class Helper {
                            private Parent owner;
                            Helper(Parent owner) { this.owner = owner; reset(); }
                            void reset() { if (this.owner.value() == 8) throw Main.failure; }
                        }
                        class Parent {
                            private Parent self; private Helper helper; private int marker = 7;
                            Parent() { this.self = this; this.helper = new Helper(this.self); this.helper.reset(); }
                            int value() { return this.marker; }
                            destructor { free this.helper; }
                        }
                        """,
                """
                        class Holder {
                            private Parent owner;
                            Holder(Parent owner) { this.owner = owner; }
                            int peek() { return this.owner.value(); }
                        }
                        class Parent {
                            private int marker = 7; private int seen;
                            Parent() { Holder holder = new Holder(this); this.seen = holder.peek(); free holder; }
                            int value() { return this.marker; }
                        }
                        """,
                """
                        class Helper {
                            private Parent owner;
                            Helper(Parent owner) { this.owner = owner; }
                            int peek() { return this.owner.value(); }
                        }
                        class Parent {
                            private Helper helper; private int marker = 7;
                            Parent() { this.helper = make(); if (this.helper.peek() == 8) throw Main.failure; }
                            private Helper make() { return new Helper(this); }
                            int value() { return this.marker; }
                            destructor { free this.helper; }
                        }
                        """,
                """
                        class Child {
                            private Parent parent; private int index;
                            Child(Parent parent, int index) { this.parent = parent; this.index = index; }
                            int marker() { return this.parent.base() + this.index; }
                        }
                        class Parent {
                            private final Child[] children; private int base = 40;
                            Parent() {
                                this.children = new Child[3];
                                for (int index = 0; index < this.children.length; index++) {
                                    Child child = new Child(this, index);
                                    this.children[index] = child;
                                }
                                if (this.base == 0) throw Main.failure;
                            }
                            int base() { return this.base; }
                            Child at(int index) { return this.children[index]; }
                            destructor {
                                for (int index = 0; index < this.children.length; index++) {
                                    free this.children[index];
                                }
                                free this.children;
                            }
                        }
                        """,
                """
                        class Parent extends Error {
                            private Parent self;
                            Parent() { this.self = this; }
                            destructor {
                                try { throw this; }
                                catch (Throwable ignored) { }
                            }
                        }
                        """);
        for (String source : sources) {
            for (UnfreedMode mode : List.of(UnfreedMode.OFF, UnfreedMode.WARN)) {
                CompilationArtifact artifact = new CompilerPipeline(mode)
                        .compile(SourceFile.of("test/Main.iron", source + MAIN));
                if (!artifact.successful()) {
                    throw new AssertionError("confined receiver field rejected under " + mode + ": "
                            + messages(artifact) + "\n" + source);
                }
            }
        }
    }

    /** An application built against a confined helper is rejected when linked with a publishing one. */
    static void reconstructedHelpersAreChecked() throws Exception {
        Path root = Files.createTempDirectory("ironwood-receiver-publication-");
        try {
            String helper = """
                    public class Helper {
                        public static Object saved;
                        public static RuntimeException failure = new RuntimeException();
                        private Object owner;
                        public Helper(Object owner, boolean fail) { this.owner = owner; reset(fail); }
                        private void reset(boolean fail) { if (fail) throw failure; }
                    }
                    """;
            Path safe = write(root, "safe/Helper.iron", helper);
            Path unsafe = write(root, "unsafe/Helper.iron", helper.replace("if (fail) throw failure;",
                    "if (fail) { saved = this.owner; throw failure; }"));
            Path main = write(root, "app/Main.iron", """
                    class Parent {
                        private Parent self;
                        private Helper helper;
                        Parent(boolean fail) { this.self = this; this.helper = new Helper(this.self, fail); }
                        destructor { free this.helper; }
                    }
                    class Main {
                        public static int main(String[] args) {
                            Parent value = new Parent(false);
                            free value;
                            try { new Parent(true); } catch (RuntimeException expected) { return 42; }
                            return 0;
                        }
                    }
                    """);
            Path safeClasses = root.resolve("safe-classes");
            Path unsafeClasses = root.resolve("unsafe-classes");
            Path unsafeArchive = root.resolve("unsafe.ironjar");
            requireExit(0, List.of(safe.toString(), "--unfreed=off", "-d", safeClasses.toString()), "safe helper");
            requireExit(0, List.of(unsafe.toString(), "--unfreed=off", "-d", unsafeClasses.toString()),
                    "publishing helper alone");
            ByteArrayOutputStream ignored = new ByteArrayOutputStream();
            if (IronJarMain.run(new String[]{"--create", "--file", unsafeArchive.toString(), unsafeClasses.toString()},
                    new PrintStream(ignored), new PrintStream(ignored)) != 0) {
                throw new AssertionError("publishing helper archive: " + ignored);
            }
            for (UnfreedMode mode : UnfreedMode.values()) {
                String flag = "--unfreed=" + mode.name().toLowerCase(java.util.Locale.ROOT);
                Path app = root.resolve("app-" + mode);
                requireExit(0, List.of(main.toString(), "-cp", safeClasses.toString(), flag, "-d", app.toString()),
                        "application against confined helper");
                Path executable = root.resolve("safe-" + mode);
                requireExit(0, List.of("--link", "-cp", app + java.io.File.pathSeparator + safeClasses,
                        "--main-class", "Main", flag, "-O3", "-o", executable.toString()), "confined helper link");
                runNative(executable, 42);
                for (Path input : List.of(unsafeClasses, unsafeArchive)) {
                    String output = requireExit(1, List.of("--link", "-cp", app + java.io.File.pathSeparator + input,
                            "--main-class", "Main", flag, "-O3", "-o", root.resolve("rejected").toString()),
                            "publishing helper link " + input.getFileName() + " under " + mode);
                    if (!output.contains(CONSTRUCTOR)) throw new AssertionError("missing publication diagnostic: " + output);
                    if (Files.exists(root.resolve("rejected"))) throw new AssertionError("rejected link wrote an executable");
                }
            }
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static Path write(Path root, String name, String source) throws Exception {
        Path path = root.resolve(name);
        Files.createDirectories(path.getParent());
        Files.writeString(path, "// SPDX-License-Identifier: MIT OR Apache-2.0\n" + source);
        return path;
    }

    private static String requireExit(int expected, List<String> arguments, String label) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PrintStream stream = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            int exit = Main.run(new ArrayList<>(arguments).toArray(String[]::new), stream, stream);
            if (exit != expected) {
                throw new AssertionError(label + ": exit " + exit + ": " + output.toString(StandardCharsets.UTF_8));
            }
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    private static void runNative(Path executable, int expectedExit) throws Exception {
        Process process = new ProcessBuilder(executable.toString()).redirectErrorStream(true).start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("receiver publication program timed out");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.exitValue() != expectedExit || !output.isEmpty()) {
            throw new AssertionError("receiver publication program: exit " + process.exitValue() + ", output " + output);
        }
    }

    private static void requireRejected(UnfreedMode mode, List<SourceFile> sources, String message) {
        CompilationArtifact artifact = new CompilerPipeline(mode).compile(sources);
        String messages = messages(artifact);
        if (artifact.successful() || !messages.contains(message)) {
            throw new AssertionError("receiver publication admitted under " + mode + ": " + messages
                    + "\n" + sources.getLast().content());
        }
    }

    private static String messages(CompilationArtifact artifact) {
        return String.join("; ", artifact.diagnostics().stream().map(diagnostic -> diagnostic.message()).toList());
    }
}
