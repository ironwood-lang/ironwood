// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.diagnostic.Diagnostic;
import ironwood.compiler.semantic.SemanticObserverBridge;
import ironwood.compiler.source.SourceFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Ownership verdicts come only from converged, refined facts, also after earlier errors. */
final class FreeAnalysisReadinessTests {
    private static final String SOURCE = """
            class Sink {

                void accept(byte[] value) {
                }
            }

            class Quiet extends Sink {

                void accept(byte[] value) {
                }
            }

            class Main {

                static void use(Sink sink) {

                    byte[] data = new byte[16];
                    sink.accept(data);
                    free data;
                }

                public static void main(String[] args) {

                    use(new Quiet());
                }
            }
            """;

    /** Safe and unsafe reclamation whose verdicts must not depend on unrelated errors. */
    private static final String VERDICTS = """
            class Sink {

                void accept(byte[] value) {
                }
            }

            class Keeper extends Sink {
                static byte[] kept;

                @Override void accept(byte[] value) {
                    kept = value;
                }
            }

            class Quiet extends Sink {

                @Override void accept(byte[] value) {
                }
            }

            class Holder {
                private byte[] owned;

                Holder(byte[] value) {
                    owned = value;
                }

                destructor {
                    free owned;
                }
            }

            class Main {
                static byte[] direct;

                static void polymorphic(Sink sink) {
                    byte[] data = new byte[16];
                    sink.accept(data);
                    free data;
                }

                static void exact(Quiet sink) {
                    byte[] data = new byte[16];
                    sink.accept(data);
                    free data;
                }

                static void store() {
                    byte[] data = new byte[4];
                    direct = data;
                    free data;
                }

                static void twice() {
                    byte[] data = new byte[4];
                    free data;
                    free data;
                }

                static int useAfterFree() {
                    byte[] data = new byte[4];
                    free data;
                    return data.length;
                }

                static void deferred() {
                    byte[] data = new byte[4];
                    defer free data;
                    data[0] = 1;
                }

                static void leak() {
                    byte[] data = new byte[4];
                    data[0] = 1;
                }

                static void trace() {
                    RuntimeException failure = new RuntimeException("failure");
                    failure.printStackTrace();
                    free failure;
                }

                public static int main(String[] args) {
                    polymorphic(new Keeper());
                    polymorphic(new Quiet());
                    Quiet quiet = new Quiet();
                    exact(quiet);
                    free quiet;
                    store();
                    twice();
                    useAfterFree();
                    deferred();
                    leak();
                    trace();
                    Holder holder = new Holder(new byte[2]);
                    free holder;
                    return 0;
                }
            }
            """;

    /** Declarations that fail checking in ways that drop, substitute, or misroute facts. */
    private static final Map<String, String> UNRELATED_ERRORS = errorFiles();

    private static final String NON_CONVERGENCE =
            "cannot prove ownership: field and return analysis did not converge";

    private FreeAnalysisReadinessTests() {}

    static void earlierErrorsAndRefinement() {
        // A missing @Override is reported, but the dispatch it describes is
        // complete: refinement still accepts the non-retaining override.
        CompilationArtifact missingOverride = analyze(SOURCE);
        rejected(missingOverride);
        require(messages(missingOverride).equals(List.of("method 'accept(byte[])' overrides or "
                        + "implements an inherited method and must be declared @Override")),
                "missing override reported secondary diagnostics: " + missingOverride.diagnostics());

        String corrected = SOURCE.replace("class Quiet extends Sink {\n",
                "class Quiet extends Sink {\n\n    @Override");
        CompilationArtifact accepted = analyze(corrected);
        require(accepted.valid() && accepted.diagnostics().isEmpty(),
                "non-retaining dispatch rejected: " + accepted.diagnostics());

        String retaining = corrected.replace("class Sink {", "class Sink {\n    static byte[] saved;")
                .replace("void accept(byte[] value) {", "void accept(byte[] value) {\n        saved = value;");
        CompilationArtifact unsafe = analyze(retaining);
        rejected(unsafe);
        require(hasError(unsafe, "cannot free 'data':"),
                "retaining dispatch must prevent reclamation: " + unsafe.diagnostics());
        CompilationArtifact unsafeAfterError = analyze(retaining.replace("\n    @Override", ""));
        rejected(unsafeAfterError);
        require(messages(unsafeAfterError).stream().filter(message -> !message.contains("@Override"))
                        .toList().equals(messages(unsafe)),
                "earlier error changed the retaining dispatch verdict: " + unsafeAfterError.diagnostics());

        // A body error leaves refined dispatch in place.
        CompilationArtifact bodyError = analyze(corrected.replace("use(new Quiet());",
                "use(new Quiet());\n        int invalid = missingName;"));
        rejected(bodyError);
        require(hasError(bodyError, "missingName"),
                "body error not found: " + bodyError.diagnostics());
        require(!hasError(bodyError, "cannot free 'data':"),
                "body error lost refined dispatch: " + bodyError.diagnostics());

        // An unresolved import is its only diagnostic in every mode; bundled
        // Throwable stays provable.
        String emptyMain = "class Main { public static int main(String[] args) { return 0; } }\n";
        List<String> missingImports = List.of("import ironwood.util.Missing;\n",
                "import static ironwood.util.Missing.value;\n");
        for (UnfreedMode mode : UnfreedMode.values()) {
            for (String missingImport : missingImports) {
                CompilationArtifact importOnly = analyze(mode, false, missingImport + emptyMain);
                rejected(importOnly);
                require(importOnly.diagnostics().size() == 1
                                && hasError(importOnly, "type 'ironwood.util.Missing' does not exist"),
                        mode + " unresolved import reported secondary diagnostics: "
                                + importOnly.diagnostics());
            }
        }
        // The non-retaining override stays accepted, and a retaining one is
        // rejected with its final message and no note about earlier errors.
        for (String missingImport : missingImports) {
            CompilationArtifact importSafe = analyze(missingImport + corrected);
            rejected(importSafe);
            require(importSafe.diagnostics().size() == 1 && !hasError(importSafe, "cannot free 'data':"),
                    "unresolved import lost refined dispatch: " + importSafe.diagnostics());
            CompilationArtifact importUnsafe = analyze(UnfreedMode.OFF, true, missingImport + retaining);
            rejected(importUnsafe);
            require(importUnsafe.diagnostics().size() == 2 && hasError(importUnsafe, "cannot free 'data':")
                            && importUnsafe.diagnostics().stream().flatMap(d -> d.notes().stream())
                                    .noneMatch(note -> note.message().contains("earlier errors")),
                    "unresolved import changed retaining dispatch rejection: " + importUnsafe.diagnostics());
        }
    }

    static void unrelatedDeclarationErrors() {
        SourceFile program = SourceFile.of("Main.iron", VERDICTS);
        for (UnfreedMode mode : List.of(UnfreedMode.OFF, UnfreedMode.ERROR)) {
            CompilationArtifact alone = analyze(mode, false, List.of(program));
            List<String> expected = located(alone, "Main.iron");
            require(expected.size() == alone.diagnostics().size()
                            && expected.size() == (mode == UnfreedMode.OFF ? 7 : 9),
                    mode + " verdict program changed: " + alone.diagnostics());
            for (Map.Entry<String, String> error : UNRELATED_ERRORS.entrySet()) {
                CompilationArtifact combined = analyze(mode, false,
                        List.of(program, SourceFile.of("Broken.iron", error.getValue())));
                rejected(combined);
                List<String> broken = located(combined, "Broken.iron");
                require(!broken.isEmpty() && broken.size() + expected.size() == combined.diagnostics().size()
                                && located(combined, "Main.iron").equals(expected),
                        mode + " " + error.getKey() + " changed unrelated verdicts: "
                                + combined.diagnostics());
            }
        }
    }

    static void genuineErrorsBesideEarlierErrors() {
        // The missing directive is on the retaining override the verdicts
        // depend on; its dispatch is complete, so every verdict is unchanged.
        String withoutDirective = VERDICTS.replace("@Override void accept(byte[] value) {\n        kept",
                "void accept(byte[] value) {\n        kept");
        require(!withoutDirective.equals(VERDICTS), "fixture lost the Keeper directive");
        for (boolean explain : List.of(false, true)) {
            CompilationArtifact fixed = analyze(UnfreedMode.ERROR, explain, VERDICTS);
            CompilationArtifact earlier = analyze(UnfreedMode.ERROR, explain, withoutDirective);
            rejected(earlier);
            List<String> verdicts = explained(earlier).stream()
                    .filter(rendered -> !rendered.contains("@Override")).toList();
            require(earlier.diagnostics().size() == verdicts.size() + 1
                            && verdicts.equals(explained(fixed)),
                    "earlier error changed genuine verdicts: " + earlier.diagnostics()
                            + " versus " + fixed.diagnostics());
            require(hasError(earlier, "allocation escapes through argument 1 of method 'accept'")
                            && hasError(earlier, "allocation escapes through static field 'Main.direct'")
                            && hasError(earlier, "allocation was already freed")
                            && hasError(earlier, "after its allocation was freed"),
                    "genuine verdicts missing beside the earlier error: " + earlier.diagnostics());
        }
    }

    static void declarationErrorsUsedAroundFrees() {
        String[][] families = {
                {"interface Runner { void run(byte[] d); }\nclass Impl implements Runner { }\n",
                        "interface Runner { void run(byte[] d); }\n"
                                + "class Impl implements Runner { @Override public void run(byte[] d) { } }\n",
                        "Runner r = new Impl(); byte[] d = new byte[4]; r.run(d); free d; free r;",
                        "class 'Impl' does not implement interface method 'run(byte[])'"},
                {"class C { abstract void run(byte[] d); }\n", "class C { void run(byte[] d) { } }\n",
                        "C c = new C(); byte[] d = new byte[4]; c.run(d); free d; free c;",
                        "abstract method 'run' may only be declared in an abstract class",
                        "concrete class 'C' does not implement abstract method 'run(byte[])' inherited from 'C'"},
                {"class Util { static void m(MissingType x, byte[] d) { } }\n",
                        "class Util { static void m(Object x, byte[] d) { } }\n",
                        "byte[] d = new byte[4]; Util.m(null, d); free d;",
                        "unknown class type 'MissingType'"},
                {"class Util { static MissingType make(byte[] d) { return null; } }\n",
                        "class Util { static Object make(byte[] d) { return null; } }\n",
                        "byte[] d = new byte[4]; Util.make(d); free d;",
                        "unknown class type 'MissingType'"},
                {"class Box { MissingType tag; int n; }\n", "class Box { Object tag; int n; }\n",
                        "Box b = new Box(); free b;",
                        "unknown class type 'MissingType'"},
                {"class Sub extends MissingBase { void accept(byte[] d) { } }\n",
                        "class Sub { void accept(byte[] d) { } }\n",
                        "Sub s = new Sub(); byte[] d = new byte[4]; s.accept(d); free d; free s;",
                        "unknown superclass 'MissingBase'"},
                {"class Two { void put(byte[] d) { } void put(byte[] d) { } }\n",
                        "class Two { void put(byte[] d) { } }\n",
                        "Two t = new Two(); byte[] d = new byte[4]; t.put(d); free d; free t;",
                        "duplicate method 'put(byte[])' in class 'Two'"},
                {"class Dup { void use(byte[] d) { } }\n"
                        + "class Dup { static byte[] kept; void use(byte[] d) { kept = d; } }\n",
                        "class Dup { void use(byte[] d) { } }\n",
                        "Dup u = new Dup(); byte[] d = new byte[4]; u.use(d); free d; free u;",
                        "duplicate class 'Dup'"},
                {"class A { Object get(byte[] d) { return null; } }\n"
                        + "class B extends A { @Override long get(byte[] d) { return 0; } }\n",
                        "class A { Object get(byte[] d) { return null; } }\n"
                                + "class B extends A { @Override Object get(byte[] d) { return null; } }\n",
                        "A a = new B(); byte[] d = new byte[4]; a.get(d); free d; free a;",
                        "method 'get' has incompatible return type long; inherited declaration "
                                + "in 'A' returns ironwood.lang.Object"},
                {"class A { final void use(byte[] d) { } }\n"
                        + "class B extends A { @Override void use(byte[] d) { } }\n",
                        "class A { void use(byte[] d) { } }\n"
                                + "class B extends A { @Override void use(byte[] d) { } }\n",
                        "A a = new B(); byte[] d = new byte[4]; a.use(d); free d; free a;",
                        "method 'use' cannot override final method inherited from 'A'"},
                {"class A { void run(byte[] d) { } }\nclass B extends A { static void run(byte[] d) { } }\n",
                        "class A { void run(byte[] d) { } }\n"
                                + "class B extends A { @Override void run(byte[] d) { } }\n",
                        "A a = new B(); byte[] d = new byte[4]; a.run(d); free d; free a;",
                        "method 'run' cannot change between static and instance form when inherited from 'A'"},
                {"interface P { Object get(byte[] d); }\ninterface Q { long get(byte[] d); }\n"
                        + "class PQ implements P, Q { @Override public String get(byte[] d) { return null; } }\n",
                        "interface P { Object get(byte[] d); }\n"
                                + "class PQ implements P { @Override public String get(byte[] d) { return null; } }\n",
                        "P p = new PQ(); byte[] d = new byte[4]; p.get(d); free d; free p;",
                        "method 'get' has incompatible return type ironwood.lang.String; "
                                + "inherited declaration in 'Q' returns long",
                        "incompatible inherited interface method 'get(byte[])' from 'P' and 'Q'",
                        "method 'get' in type 'PQ' does not provide the required public instance "
                                + "signature 'get(byte[])' from interface 'Q'"},
                {"class G<T extends MissingBound> { void take(byte[] d) { } }\n",
                        "class G<T> { void take(byte[] d) { } }\n",
                        "G<Object> g = new G<Object>(); byte[] d = new byte[4]; g.take(d); free d; free g;",
                        "unknown bound type 'MissingBound'"},
        };
        for (String[] family : families) {
            String main = "class Main {\n    public static int main(String[] args) {\n        "
                    + family[2] + "\n        return 0;\n    }\n}\n";
            CompilationArtifact fixed = analyze(family[1] + main);
            require(fixed.valid() && fixed.diagnostics().isEmpty(),
                    "fixed declaration was rejected: " + fixed.diagnostics());
            // Only the declaration errors remain.
            CompilationArtifact broken = analyze(family[0] + main);
            rejected(broken);
            require(messages(broken).equals(List.of(family).subList(3, family.length)),
                    "declaration error cascaded: " + broken.diagnostics());
        }
    }

    static void missingImplementationPlaceholders() {
        String runner = "interface Runner { void run(byte[] d); }\n";
        String missing = runner + "class Impl implements Runner { }\n";
        String fixed = runner + "class Impl implements Runner { @Override public void run(byte[] d) { } }\n";
        String runError = "class 'Impl' does not implement interface method 'run(byte[])'";
        // The placeholder retains nothing, so frees around a call that reaches
        // it, also through other methods, are proved; a genuine error in the
        // same body is still reported.
        expect(missing + """
                class Helper {
                    static void pass(Runner r, byte[] d) { r.run(d); }
                    static void passTwice(Runner r, byte[] d) { pass(r, d); }
                }
                class Main {
                    static byte[] kept;
                    static void interprocedural(Runner r) {
                        byte[] d = new byte[4];
                        Helper.passTwice(r, d);
                        free d;
                    }
                    static void unrelatedInSameBody(Runner r) {
                        byte[] d = new byte[4];
                        r.run(d);
                        byte[] e = new byte[4];
                        kept = e;
                        free e;
                    }
                    public static int main(String[] args) {
                        Runner r = new Impl();
                        interprocedural(r);
                        unrelatedInSameBody(r);
                        free r;
                        return 0;
                    }
                }
                """, runError, "cannot free 'e': allocation escapes through static field 'Main.kept'");
        // A leak is reported once the implementation exists, but not while
        // the missing one might still retain the argument.
        String leak = """
                class Main {
                    static void pass(Runner r) {
                        byte[] d = new byte[4];
                        r.run(d);
                    }
                    public static int main(String[] args) {
                        Runner r = new Impl();
                        pass(r);
                        free r;
                        return 0;
                    }
                }
                """;
        expect(missing + leak, runError);
        expect(fixed + leak, "allocation assigned to 'd' leaves scope without being freed");
        // A real retaining implementation beside the missing one still rejects.
        expect(runner + """
                class Keeper implements Runner { static byte[] kept; @Override public void run(byte[] d) { kept = d; } }
                class Impl implements Runner { }
                class Main {
                    static void use(Runner r) {
                        byte[] d = new byte[4];
                        r.run(d);
                        free d;
                    }
                    public static int main(String[] args) {
                        Runner a = new Keeper();
                        Runner b = new Impl();
                        use(a);
                        use(b);
                        free a;
                        free b;
                        return 0;
                    }
                }
                """, runError, "cannot free 'd': allocation escapes through argument 1 of method 'run'");
        // A result counts as a fresh allocation the caller owns, directly or
        // through another method, but never as an abandoned one. Publishing it
        // is still rejected, as it would be for every implementation.
        String factory = "interface Factory { byte[] make(); }\nclass Impl implements Factory { }\n";
        String makeError = "class 'Impl' does not implement interface method 'make()'";
        expect(factory + """
                class Main {
                    static byte[] kept;
                    static byte[] build(Factory f) { return f.make(); }
                    static void drop(Factory f) { f.make(); byte[] unused = f.make(); unused[0] = 1; }
                    public static int main(String[] args) {
                        Factory f = new Impl();
                        byte[] direct = f.make();
                        free direct;
                        byte[] wrapped = build(f);
                        free wrapped;
                        byte[] deferred = f.make();
                        defer free deferred;
                        byte[] published = f.make();
                        kept = published;
                        free published;
                        drop(f);
                        free f;
                        return 0;
                    }
                }
                """, makeError, "cannot free 'published': allocation escapes through static field 'Main.kept'");
        expect("""
                interface Factory { byte[] make(byte[] seed); }
                class First implements Factory { }
                class Second implements Factory { }
                class Main {
                    static void use(Factory f) {
                        byte[] seed = new byte[4];
                        byte[] d = f.make(seed);
                        free d;
                        free seed;
                    }
                    public static int main(String[] args) {
                        Factory a = new First();
                        Factory b = new Second();
                        use(a);
                        use(b);
                        free a;
                        free b;
                        return 0;
                    }
                }
                """, "class 'First' does not implement interface method 'make(byte[])'",
                "class 'Second' does not implement interface method 'make(byte[])'");
        // Generic, anonymous, and primitive-returning requirements.
        expect("""
                interface Sink<T> { void put(T value); }
                class Impl implements Sink<byte[]> { }
                interface Mirror { <T> T reflect(T value); }
                class Glass implements Mirror { }
                interface Counter { long count(byte[] d); char letter(); boolean ok(); }
                class Tally implements Counter { }
                class Main {
                    public static int main(String[] args) {
                        Sink<byte[]> sink = new Impl();
                        Mirror mirror = new Glass();
                        Counter counter = new Tally();
                        Runnable task = new Runnable() { };
                        byte[] d = new byte[4];
                        sink.put(d);
                        mirror.reflect(d);
                        long n = counter.count(d);
                        task.run();
                        free d;
                        free sink;
                        free mirror;
                        free counter;
                        free task;
                        return (int) n;
                    }
                }
                """, "class 'Impl' does not implement interface method 'put(byte[])'",
                "class 'Glass' does not implement interface method 'reflect(T)'",
                "class 'Tally' does not implement interface method 'count(byte[])'",
                "class 'Tally' does not implement interface method 'letter()'",
                "class 'Tally' does not implement interface method 'ok()'",
                "class 'Main$1' does not implement interface method 'run()'");
        // Destructor checks keep exactly the verdicts of the corrected program.
        String holder = """
                class Holder {
                    private Runner runner;
                    Holder(Runner runner) { this.runner = runner; }
                    destructor { runner.run(null); }
                }
                class Main {
                    public static int main(String[] args) {
                        Runner r = new Impl();
                        Holder h = new Holder(r);
                        free h;
                        free r;
                        return 0;
                    }
                }
                """;
        List<String> corrected = messages(analyze(UnfreedMode.ERROR, false, fixed + holder));
        require(!corrected.isEmpty(), "destructor fixture lost its genuine verdicts");
        List<String> expected = new java.util.ArrayList<>(List.of(runError));
        expected.addAll(corrected);
        expect(missing + holder, expected.toArray(String[]::new));
    }

    private static void expect(String source, String... messages) {
        CompilationArtifact artifact = analyze(UnfreedMode.ERROR, false, source);
        require(!artifact.valid() && messages(artifact).equals(List.of(messages)),
                "unexpected diagnostics: " + artifact.diagnostics());
    }

    static void unconvergedAnalysis() {
        // Without converged facts no ownership verdict is reported; with them,
        // this program has nine findings in strict mode.
        SemanticObserverBridge.Counts counts = new SemanticObserverBridge.Counts();
        CompilationArtifact valid = unconverged(UnfreedMode.ERROR, true, VERDICTS, counts);
        rejected(valid);
        require(messages(valid).equals(List.of(NON_CONVERGENCE))
                        && valid.diagnostics().getFirst().notes().isEmpty(),
                "unconverged analysis reported verdicts: " + valid.diagnostics());
        var finalLowerings = counts.lowerings().stream()
                .filter(lowering -> lowering.finalPhase()).toList();
        require(counts.entered() == 0 && counts.finished() == 1 && !counts.completed()
                        && !finalLowerings.isEmpty() && finalLowerings.stream().allMatch(lowering ->
                        !lowering.refinementCompleted() && !lowering.collectorPresent()),
                "unconverged final lowering was not observed without evidence");

        // Final lowering still reports non-ownership errors.
        CompilationArtifact bodyError = unconverged(UnfreedMode.OFF, false,
                VERDICTS.replace("store();\n", "store();\n        int invalid = missingName;\n"),
                new SemanticObserverBridge.Counts());
        rejected(bodyError);
        require(messages(bodyError).equals(List.of(NON_CONVERGENCE,
                        "unknown local variable 'missingName'")),
                "unconverged analysis lost a body error: " + bodyError.diagnostics());

        // An earlier error may cause the failure, so it is not reported either.
        CompilationArtifact earlier = unconverged(UnfreedMode.ERROR, false,
                VERDICTS + "class BrokenField { MissingType value; }\n",
                new SemanticObserverBridge.Counts());
        rejected(earlier);
        require(messages(earlier).equals(List.of("unknown class type 'MissingType'")),
                "unconverged analysis after an earlier error reported more: " + earlier.diagnostics());
    }

    static void commandLineAfterDeclarationErrors() throws IOException {
        Path root = Files.createTempDirectory("ironwood-earlier-errors");
        try {
            Path source = Files.writeString(root.resolve("Main.iron"), SOURCE);
            for (String mode : List.of("--unfreed=warn", "--unfreed=error")) {
                Result result = run(mode, source.toString(), "-d", root.resolve("classes").toString());
                require(result.status() == 1 && result.stdout().isEmpty()
                                && result.stderr().contains("must be declared @Override")
                                && result.stderr().lines().filter(line -> line.startsWith("error:")
                                        || line.startsWith("warning:")).count() == 1,
                        mode + " reported diagnostics beyond the declaration error: " + result);
            }
        } finally {
            delete(root);
        }
    }

    static void linkWithoutEntryPoint() throws IOException {
        Path root = Files.createTempDirectory("ironwood-link-entry");
        try {
            Path main = Files.writeString(root.resolve("Main.iron"), """
                    class Main {

                        public static int main(String[] args) {
                            byte[] data = new byte[4];
                            data[0] = 1;
                            free data;
                            RuntimeException failure = new RuntimeException("failure");
                            free failure;
                            return 0;
                        }
                    }
                    """);
            Path helper = Files.writeString(root.resolve("Helper.iron"), """
                    class Helper {

                        static int twice(int value) {
                            return value * 2;
                        }
                    }
                    """);
            Path classes = root.resolve("classes");
            Result compiled = run("--unfreed=error", main.toString(), helper.toString(),
                    "-d", classes.toString());
            require(compiled.status() == 0, "link fixture did not compile: " + compiled);
            Result linked = run("--link", "--unfreed=error", "-cp", classes.toString(),
                    "--main-class", "Helper", "-o", root.resolve("program").toString());
            require(linked.status() == 1 && linked.stderr().contains(
                            "main class 'Helper' does not declare main(String[] args)")
                            && linked.stderr().lines().filter(line -> line.startsWith("error:")
                                    || line.startsWith("warning:")).count() == 1,
                    "missing entry point reported more than its own error: " + linked);
        } finally {
            delete(root);
        }
    }

    private static Map<String, String> errorFiles() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("missing @Override", "class BrokenBase { void ping() { } }\n"
                + "class BrokenChild extends BrokenBase { void ping() { } }\n");
        files.put("unresolved import", "import ironwood.util.Missing;\nclass BrokenImport { }\n");
        files.put("unknown field type", "class BrokenField { MissingType value; }\n");
        files.put("duplicate class", "class BrokenDuplicate { }\nclass BrokenDuplicate { int value; }\n");
        files.put("unknown supertype", "class BrokenSub extends MissingBase { }\n");
        files.put("unknown parameter type", "class BrokenParameter { static void take(MissingType value) { } }\n");
        files.put("incompatible override", "class BrokenGetter { Object get() { return null; } }\n"
                + "class BrokenNarrow extends BrokenGetter { @Override long get() { return 0; } }\n");
        files.put("unknown bound", "class BrokenBound<T extends MissingBound> { }\n");
        files.put("missing implementation", "interface BrokenRunner { void run(byte[] d); }\n"
                + "class BrokenImpl implements BrokenRunner { }\n");
        return files;
    }

    private static CompilationArtifact analyze(String source) {
        return analyze(UnfreedMode.OFF, false, source);
    }

    private static CompilationArtifact analyze(UnfreedMode mode, boolean explain, String source) {
        return analyze(mode, explain, List.of(SourceFile.of("Main.iron", source)));
    }

    private static CompilationArtifact analyze(UnfreedMode mode, boolean explain, List<SourceFile> sources) {
        return new CompilerPipeline(mode, explain, null).analyze(sources);
    }

    private static CompilationArtifact unconverged(UnfreedMode mode, boolean explain, String source,
                                                   SemanticObserverBridge.Counts counts) {
        SourceFile file = SourceFile.of("Main.iron", source);
        return new CompilerPipeline(mode, explain, (selected, explained) ->
                SemanticObserverBridge.createWithRefinementPassLimit(selected, explained,
                        counts, file.path(), 0)).analyze(List.of(file));
    }

    private static List<String> messages(CompilationArtifact artifact) {
        return artifact.diagnostics().stream().map(Diagnostic::message).toList();
    }

    private static List<String> located(CompilationArtifact artifact, String file) {
        return artifact.diagnostics().stream()
                .filter(d -> d.source() != null && d.source().path().toString().equals(file))
                .map(d -> (d.isError() ? "error " : "warning ") + d.message() + " @ "
                        + d.span().start().line() + ":" + d.span().start().column())
                .toList();
    }

    /** Each diagnostic with its location and the text of its notes. */
    private static List<String> explained(CompilationArtifact artifact) {
        return artifact.diagnostics().stream()
                .map(d -> d.message() + " @ " + d.source().path() + ":" + d.span().start().line()
                        + ":" + d.span().start().column() + " "
                        + d.notes().stream().map(note -> note.message()).toList())
                .toList();
    }

    private static Result run(String... arguments) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int status = Main.run(arguments, new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
        return new Result(status, stdout.toString(StandardCharsets.UTF_8),
                stderr.toString(StandardCharsets.UTF_8));
    }

    private static void delete(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    private record Result(int status, String stdout, String stderr) {
    }

    private static void rejected(CompilationArtifact artifact) {
        require(!artifact.valid() && artifact.program().isEmpty() && artifact.llvmIr().isEmpty(),
                "invalid source produced a program: " + artifact.diagnostics());
    }

    private static boolean hasError(CompilationArtifact artifact, String text) {
        return artifact.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.isError() && diagnostic.message().contains(text));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
