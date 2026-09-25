// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.diagnostic.Diagnostic;
import ironwood.compiler.ir.IrBasicBlock;
import ironwood.compiler.ir.IrFreeInstruction;
import ironwood.compiler.ir.IrFunction;
import ironwood.compiler.ir.IrInstruction;
import ironwood.compiler.ir.IrProgram;
import ironwood.compiler.source.SourceFile;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/** Unnamed temporaries are reclaimed at the end of their statement when the free proof succeeds. */
final class TemporaryReclamationTests {
    private static final String COMMON = """
            class Keeper {
                static int destroyed;
                final int tag;
                Keeper(int tag) { this.tag = tag; }
                destructor { destroyed++; }
            }
            class Holder {
                final Keeper held;
                Holder(Keeper held) { this.held = held; }
            }
            class Wrapper {
                Wrapper(Keeper keeper) { }
            }
            class Sink {
                static Keeper kept;
                static int seen;
                static void use(Keeper keeper) { seen += keeper.tag; }
                static void use(Keeper keeper, int value) { seen += keeper.tag + value; }
                static void keep(Keeper keeper) { kept = keeper; }
                static Keeper make(int tag) { return new Keeper(tag); }
                static Keeper maybe(int tag) { return tag < 0 ? null : new Keeper(tag); }
                static int boom() { throw new RuntimeException("boom"); }
                static void fail(Keeper keeper) { throw new RuntimeException("fail " + keeper.tag); }
            }
            """;

    private TemporaryReclamationTests() {}

    static void reclaimsTemporaries() {
        String source = COMMON + """
                class Main {
                    static int length(String text) { return text.length(); }
                    public static void main(String[] args) {
                        Sink.use(new Keeper(1));
                        new Keeper(2);
                        Sink.use(Sink.make(3));
                        System.out.println("Hello " + args.length + "!");
                        int size = length("a" + args.length);
                        Sink.use(new Keeper(4), new int[4].length);
                        new Wrapper(new Keeper(5));
                    }
                }
                """;
        CompilationArtifact warned = compile(source, UnfreedMode.WARN);
        require(warned.valid() && warned.diagnostics().isEmpty(),
                "temporaries must not warn: " + warned.diagnostics());
        // Keeper 1, 2, 3, 4, 5, the greeting, the concatenated argument, the array,
        // and the wrapper: nine reclaimed temporaries.
        require(frees(warned, "Main") == 9, "expected nine frees but found "
                + frees(warned, "Main"));
        // Every temporary that is live across a call also has an unwind free.
        require(unwindFrees(warned, "Main") >= 5, "expected unwind frees but found "
                + unwindFrees(warned, "Main"));
        CompilationArtifact off = compile(source, UnfreedMode.OFF);
        CompilationArtifact strict = compile(source, UnfreedMode.ERROR);
        require(off.valid() && strict.valid() && strict.diagnostics().isEmpty(),
                "the rule holds in every mode: " + strict.diagnostics());
        require(warned.program().equals(off.program()) && warned.program().equals(strict.program()),
                "typed IR must not depend on the unfreed mode");
    }

    static void keepsRetainedEscapedAndNamed() {
        expectFrees("static escape", COMMON + """
                class Main { public static void main(String[] args) { Sink.keep(new Keeper(1)); } }
                """, 0, List.of());
        expectFrees("named local", COMMON + """
                class Main { public static void main(String[] args) { Keeper k = new Keeper(1); } }
                """, 0, List.of("allocation assigned to 'k' leaves scope without being freed"));
        expectFrees("named concatenation", COMMON + """
                class Main { public static void main(String[] args) {
                    String s = "Hello " + args.length; System.out.println(s);
                } }
                """, 0, List.of("allocation assigned to 's' leaves scope without being freed"));
        expectFrees("constructor borrow", COMMON + """
                class Main { public static void main(String[] args) {
                    Holder h = new Holder(new Keeper(1)); free h;
                } }
                """, 1, List.of());
        // Freeing the container releases only the container; the element it borrowed
        // is then reported as an ordinary finding. It was observed by the container
        // at its statement, so it was never a temporary and carries no note.
        String containerSource = COMMON + """
                class Main { public static void main(String[] args) {
                    ironwood.ds.ArrayList<Keeper> list = new ironwood.ds.ArrayList<Keeper>();
                    list.add(new Keeper(1));
                    free list;
                } }
                """;
        CompilationArtifact container = compile(containerSource, UnfreedMode.WARN);
        require(container.valid() && frees(container, "Main") == 1
                        && container.diagnostics().size() == 1
                        && container.diagnostics().getFirst().message()
                        .equals("new allocation is discarded without being freed")
                        && container.diagnostics().getFirst().notes().isEmpty(),
                "container borrow: " + container.diagnostics());
        CompilationArtifact strictContainer = compile(containerSource, UnfreedMode.ERROR);
        require(!strictContainer.valid() && strictContainer.diagnostics().size() == 1
                        && strictContainer.diagnostics().getFirst().notes().isEmpty(),
                "observed allocation gained a temporary note: " + strictContainer.diagnostics());
        // A deferred operand is captured until block exit; it is not a candidate and
        // keeps today's finding once the deferred call has run.
        expectFrees("deferred operand", COMMON + """
                class Main { public static void main(String[] args) {
                    defer Sink.use(new Keeper(1));
                    Sink.use(new Keeper(2));
                } }
                """, 1, List.of("new allocation leaves scope without being freed"));
        expectFrees("nullable factory", COMMON + """
                class Main { public static void main(String[] args) { Sink.use(Sink.maybe(args.length)); } }
                """, 0, List.of());
        // Freeing the array releases only the container; its element is then reported.
        expectFrees("array slot", COMMON + """
                class Main { public static void main(String[] args) {
                    Keeper[] slots = new Keeper[1]; slots[0] = new Keeper(1); free slots;
                } }
                """, 1, List.of("new allocation is discarded without being freed"));
        // The argument escapes through the result and is then named; it is reported as
        // that local, not reclaimed as a temporary.
        expectFrees("returned result", COMMON + """
                class Main {
                    static Keeper pass(Keeper keeper) { return keeper; }
                    public static void main(String[] args) { Keeper k = pass(new Keeper(1)); Sink.use(k); }
                }
                """, 0, List.of("allocation assigned to 'k' leaves scope without being freed"));
    }

    static void reclaimsOnExceptionalPaths() throws Exception {
        String source = COMMON + """
                class Main {
                    static void lateOperand() { Sink.use(new Keeper(1), Sink.boom()); }
                    static void failingCallee() { Sink.fail(new Keeper(2)); }
                    static void nested() { Sink.fail(Sink.make(3)); }
                    static int attempt(int which) {
                        try {
                            if (which == 0) lateOperand();
                            if (which == 1) failingCallee();
                            if (which == 2) nested();
                        } catch (RuntimeException e) {
                            return 1;
                        }
                        return 0;
                    }
                    public static int main(String[] args) {
                        long before = System.liveAllocationCount();
                        int caught = attempt(0) + attempt(1) + attempt(2);
                        Sink.use(new Keeper(4));
                        // The three caught exceptions and two dynamic messages stay
                        // allocated; every Keeper was reclaimed.
                        long remaining = System.liveAllocationCount() - before;
                        System.out.println(caught + " " + Keeper.destroyed + " " + remaining);
                        return 0;
                    }
                }
                """;
        NativeRun run = runNative(source);
        require(run.exit() == 0 && run.stdout().equals("3 4 5\n") && run.stderr().isEmpty(),
                "exceptional-path reclamation: " + run);
    }

    static void matchesHandwrittenCleanup() {
        String automatic = COMMON + """
                class Main {
                    static void run(int tag) { Sink.use(new Keeper(tag), Sink.boom()); }
                }
                """;
        String handwritten = COMMON + """
                class Main {
                    static void run(int tag) {
                        Keeper keeper = new Keeper(tag);
                        try {
                            Sink.use(keeper, Sink.boom());
                        } finally {
                            free keeper;
                        }
                    }
                }
                """;
        CompilationArtifact left = compile(automatic, UnfreedMode.WARN);
        CompilationArtifact right = compile(handwritten, UnfreedMode.WARN);
        require(left.valid() && right.valid(), "parity programs must compile: "
                + left.diagnostics() + right.diagnostics());
        String leftShape = shape(left, "run");
        String rightShape = shape(right, "run");
        require(leftShape.equals(rightShape), "temporary lowering differs from handwritten cleanup:\n"
                + leftShape + "\n---\n" + rightShape);
    }

    static void preservesProvisionalAndFinalSummaries() throws Exception {
        String source = COMMON + """
                class Owner {
                    private Keeper child = new Keeper(7);
                    destructor { free child; }
                }
                class Main {
                    static void first() { second(); }
                    static void second() { Sink.use(new Keeper(1)); new Owner(); }
                    public static int main(String[] args) {
                        long before = System.liveAllocationCount();
                        first();
                        second();
                        boolean reclaimed = System.liveAllocationCount() == before;
                        System.out.println(Keeper.destroyed + " " + reclaimed);
                        return 0;
                    }
                }
                """;
        CompilationArtifact artifact = compile(source, UnfreedMode.ERROR);
        require(artifact.valid() && artifact.diagnostics().isEmpty(),
                "helper temporaries must reclaim under refinement: " + artifact.diagnostics());
        require(frees(artifact, "Main", "second") == 2, "second must free the Keeper and the Owner");
        NativeRun run = runNative(source);
        // Each call to second destroys its Keeper temporary and, through the Owner's
        // destructor, the Keeper the Owner allocated: four across the two calls.
        require(run.exit() == 0 && run.stdout().equals("4 true\n") && run.stderr().isEmpty(),
                "destructor effects through helpers: " + run);
    }

    /**
     * A synthesized anonymous constructor forwards its parameters to the superclass
     * constructor. Its arguments must keep that constructor's retention, both for a
     * source free and for the temporary rule. Found while implementing the rule: the
     * generic anonymous inner form previously accepted the free.
     */
    static void anonymousConstructorArgumentsStayRetained() {
        String types = """
                class Token { final int amount; Token(int amount) { this.amount = amount; } }
                class Outer<T> {
                    final T value;
                    Outer(T value) { this.value = value; }
                    class Inner<U> {
                        final U token;
                        Inner(U token) { this.token = token; }
                        int result() { return 0; }
                    }
                }
                abstract class Base<T> {
                    final T token;
                    Base(T token) { this.token = token; }
                    abstract int result();
                }
                abstract class Plain {
                    final Token token;
                    Plain(Token token) { this.token = token; }
                    abstract int result();
                }
                """;
        List<String> creations = List.of(
                "outer.new Inner<Token>(token) { @Override int result() { return 1; } }",
                "new Base<Token>(token) { @Override int result() { return 1; } }",
                "new Plain(token) { @Override int result() { return 1; } }",
                "outer.new Inner<Token>(token)");
        for (String creation : creations) {
            String source = types
                    + "class Main { public static void main(String[] args) {\n"
                    + "    Outer<String> outer = new Outer<String>(\"x\");\n"
                    + "    Token token = new Token(1);\n"
                    + "    Object kept = " + creation + ";\n"
                    + "    free token;\n"
                    + "} }\n";
            CompilationArtifact artifact = compile(source, UnfreedMode.OFF);
            require(!artifact.valid() && artifact.diagnostics().stream().anyMatch(d ->
                            d.isError() && d.message().startsWith("cannot free 'token'")),
                    creation + " must reject freeing the retained argument: " + artifact.diagnostics());
            String temporary = types
                    + "class Main { public static void main(String[] args) {\n"
                    + "    Outer<String> outer = new Outer<String>(\"x\");\n"
                    + "    Object kept = " + creation.replace("(token)", "(new Token(1))") + ";\n"
                    + "} }\n";
            CompilationArtifact reclaimed = compile(temporary, UnfreedMode.OFF);
            require(reclaimed.valid() && frees(reclaimed, "Main") == 0,
                    creation + " must not reclaim a retained temporary argument: "
                            + reclaimed.diagnostics());
        }
    }

    /** Every full-expression context of Milestone 3 reclaims its temporaries natively. */
    private static final String EVERY_CONTEXT = """
                class Config {

                    static int destroyed;

                    final int size;

                    Config(int size) {

                        this.size = size;
                    }

                    destructor {

                        destroyed++;
                    }
                }

                class Failure extends RuntimeException {

                    final int code;

                    Failure(int code) {

                        this.code = code;
                    }
                }

                class Base {

                    final int width;

                    Base(int width) {

                        this.width = width;
                    }
                }

                class Derived extends Base {

                    final int height = Main.count(new Config(7));

                    static final int DEPTH = Main.count(new Config(8));

                    Derived() {

                        this(Main.count(new Config(9)));
                    }

                    Derived(int height) {

                        super(Main.count(new Config(10)));
                    }
                }

                public class Main {

                    static int count(Config config) {

                        return config.size;
                    }

                    static int[] values(Config config) {

                        int[] result = new int[config.size];
                        for (int index = 0; index < result.length; index++) {
                            result[index] = index;
                        }
                        return result;
                    }

                    static int[] pick(Config config, int[] values) {

                        return values;
                    }

                    static Config pass() {

                        return new Config(20);
                    }

                    static Object widened() {

                        return new Config(21);
                    }

                    static int viaYield(int n) {

                        return switch (n) {
                            default -> {
                                yield count(new Config(11));
                            }
                        };
                    }

                    static void fail() {

                        throw new Failure(count(new Config(12)));
                    }

                    public static void main(String[] args) {

                        long before = System.liveAllocationCount();
                        int total = 0;
                        if (count(new Config(1)) > 0) {
                            total += 1;
                        }
                        int spins = 0;
                        while (count(new Config(2)) > spins) {
                            spins++;
                        }
                        total += spins;
                        do {
                            total += 100;
                        } while (count(new Config(3)) < 0);
                        for (int index = count(new Config(4)); index < count(new Config(5)); index += count(new Config(1))) {
                            total += 1000;
                        }
                        int[] values = values(new Config(3));
                        for (int value : pick(new Config(6), values)) {
                            total += value * 10000;
                        }
                        free values;
                        switch (count(new Config(13))) {
                            case 13 -> total += 100000;
                            default -> total += 0;
                        }
                        total += viaYield(0);
                        try {
                            fail();
                        } catch (Failure failure) {
                            total += failure.code;
                        }
                        Derived derived = new Derived();
                        total += derived.height + derived.width + Derived.DEPTH;
                        free derived;
                        Config passed = pass();
                        Object wide = widened();
                        total += passed.size + ((Config) wide).size;
                        free passed;
                        free wide;
                        System.out.println(total + " destroyed " + Config.destroyed + " live " + (System.liveAllocationCount() - before));
                    }
                }
                """;

    /** Values that move on, and the bound value of a pattern condition, are never reclaimed. */
    private static final String TRANSFERRED = """
                class Keeper {

                    final int tag;

                    Keeper(int tag) {

                        this.tag = tag;
                    }
                }

                class Failure extends RuntimeException {
                }

                public class Main {

                    static Keeper made() {

                        return new Keeper(1);
                    }

                    static Object widened() {

                        return (Object) new Keeper(2);
                    }

                    static Keeper yielded(int n) {

                        return switch (n) {
                            default -> {
                                yield new Keeper(3);
                            }
                        };
                    }

                    static void thrown() {

                        throw new Failure();
                    }

                    static String name() {

                        return "a" + System.liveAllocationCount();
                    }

                    static Keeper[] keepers() {

                        return new Keeper[] { new Keeper(4) };
                    }

                    static int matched(Object value) {

                        if (widened() instanceof Keeper keeper) {
                            return keeper.tag;
                        }
                        return 0;
                    }

                    static int selected() {

                        switch (name()) {
                            case "a0" -> { return 1; }
                            default -> { return 2; }
                        }
                    }

                    static int iterated() {

                        int total = 0;
                        for (Keeper keeper : keepers()) {
                            total += keeper.tag;
                        }
                        return total;
                    }

                    public static void main(String[] args) {

                        Keeper k = made();
                        Object w = widened();
                        Keeper y = yielded(0);
                        System.out.println(k.tag + " " + ((Keeper) w).tag + " " + y.tag + " " + matched(null) + " " + selected() + " " + iterated());
                        free k;
                        free w;
                    }
                }
                """;

    static void coversEveryFullExpressionContext() throws Exception {
        CompilationArtifact artifact = compile(EVERY_CONTEXT, UnfreedMode.ERROR);
        require(artifact.valid() && artifact.diagnostics().isEmpty(),
                "every context must compile without findings: " + artifact.diagnostics());
        NativeRun run = runNative(EVERY_CONTEXT);
        // Twenty Config temporaries across conditions, for headers, an enhanced-for source
        // expression, a switch selector, yield, throw, field initializers, and explicit
        // constructor invocations are reclaimed; only the caught Failure stays live.
        require(run.exit() == 0 && run.stdout().equals("131192 destroyed 20 live 1\n")
                        && run.stderr().isEmpty(),
                "every-context reclamation: " + run);
    }

    static void keepsTransferredValues() throws Exception {
        CompilationArtifact artifact = compile(TRANSFERRED, UnfreedMode.OFF);
        require(artifact.valid(), "transferred values must compile: " + artifact.diagnostics());
        for (String function : List.of("made", "widened", "yielded", "thrown", "name",
                "keepers", "matched", "selected", "iterated")) {
            require(frees(artifact, "Main", function) == 0,
                    function + " must not reclaim its transferred value");
        }
        Path root = Files.createTempDirectory("ironwood-transferred-");
        try {
            Path input = root.resolve("Main.iron");
            Files.writeString(input, TRANSFERRED);
            Path classes = root.resolve("classes");
            require(run(input.toString(), "-d", classes.toString(), "--unfreed=off").isEmpty(),
                    "transferred compile");
            Path executable = root.resolve("app");
            require(run("--link", "-cp", classes.toString(), "--main-class", "Main",
                    "-o", executable.toString(), "-O3", "--unfreed=off").isEmpty(), "transferred link");
            Process process = new ProcessBuilder(executable.toString()).start();
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            require(process.waitFor() == 0 && stdout.equals("1 2 3 2 2 4\n"),
                    "transferred values stay usable: " + stdout);
        } finally {
            deleteTree(root);
        }
    }

    static void survivesArtifactReconstruction() throws Exception {
        Path root = Files.createTempDirectory("ironwood-temporaries-artifacts-");
        try {
            Path input = root.resolve("Main.iron");
            Files.writeString(input, EVERY_CONTEXT);
            Path classes = root.resolve("classes");
            require(run(input.toString(), "-d", classes.toString(), "--unfreed=error").isEmpty(),
                    "artifact compile");
            Path archive = root.resolve("app.ironjar");
            ByteArrayOutputStream archiveErrors = new ByteArrayOutputStream();
            require(IronJarMain.run(new String[]{"--create", "--file", archive.toString(),
                    classes.toString()}, new PrintStream(new ByteArrayOutputStream()),
                    new PrintStream(archiveErrors)) == 0, archiveErrors.toString());
            for (String classPath : List.of(classes.toString(), archive.toString())) {
                Path executable = root.resolve(classPath.endsWith(".ironjar") ? "archive-app" : "class-app");
                require(run("--link", "-cp", classPath, "--main-class", "Main",
                        "-o", executable.toString(), "-O3", "--unfreed=error").isEmpty(),
                        "artifact link from " + classPath);
                Process process = new ProcessBuilder(executable.toString()).start();
                String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                require(process.waitFor() == 0 && stdout.equals("131192 destroyed 20 live 1\n"),
                        "reconstructed program from " + classPath + ": " + stdout);
            }
        } finally {
            deleteTree(root);
        }
    }

    /**
     * An alias that exists only where an exception leaves the statement must keep
     * the object alive on that path. Found by review: the normal path, where the
     * alias is already cleared, approved a free in the unwind pad.
     */
    static void keepsAliasesThatExistOnlyOnExceptionPaths() throws Exception {
        String source = COMMON + """
                class Main {
                    static void take(Keeper first, int second, Keeper third) { }
                    static int probe() {
                        Keeper saved = null;
                        try {
                            take(saved = new Keeper(42), Sink.boom(), saved = null);
                        } catch (RuntimeException e) {
                            return saved.tag;
                        }
                        return -1;
                    }
                    public static int main(String[] args) {
                        int tag = probe();
                        System.out.println(Keeper.destroyed + " " + tag);
                        return 0;
                    }
                }
                """;
        CompilationArtifact artifact = compile(source, UnfreedMode.ERROR);
        require(artifact.valid() && artifact.diagnostics().isEmpty(),
                "exception-path alias program must compile: " + artifact.diagnostics());
        long unwind = artifact.program().orElseThrow().functions().stream()
                .filter(function -> function.ownerClass().equals("Main")
                        && function.sourceName().equals("probe"))
                .flatMap(function -> function.blocks().stream())
                .filter(block -> block.label().startsWith("temporary.cleanup"))
                .flatMap(block -> block.instructions().stream())
                .filter(IrFreeInstruction.class::isInstance).count();
        require(frees(artifact, "Main", "probe") == 1 && unwind == 0,
                "the Keeper is freed on the normal path only: normal="
                        + frees(artifact, "Main", "probe") + " unwind=" + unwind);
        NativeRun run = runNative(source);
        require(run.exit() == 0 && run.stdout().equals("0 42\n") && run.stderr().isEmpty(),
                "alias on the exception path must stay valid: " + run);
    }

    /**
     * A wrapper freed in its unwind pad releases the child it retained, so the child's
     * own pad can reclaim it. Found by review: the pad emitted a bare free without
     * applying its ownership consequences, and the child leaked on every caught failure.
     */
    static void reclaimsRetainedChildrenOnExceptionPaths() throws Exception {
        String source = """
                class Child { static int destroyed; destructor { destroyed++; } }
                class Wrapper {
                    static int destroyed;
                    private final Child child;
                    Wrapper(Child child) { this.child = child; }
                    destructor { destroyed++; }
                }
                class Sink {
                    static void use(Wrapper wrapper) { }
                    static void fail(Wrapper wrapper) { throw new RuntimeException("fail"); }
                }
                class Main {
                    public static int main(String[] args) {
                        Sink.use(new Wrapper(new Child()));
                        int normal = Wrapper.destroyed * 10 + Child.destroyed;
                        try {
                            Sink.fail(new Wrapper(new Child()));
                        } catch (RuntimeException e) { }
                        System.out.println(normal + " " + (Wrapper.destroyed * 10 + Child.destroyed));
                        return 0;
                    }
                }
                """;
        NativeRun run = runNative(source);
        require(run.exit() == 0 && run.stdout().equals("11 22\n") && run.stderr().isEmpty(),
                "wrapper and child must both be reclaimed on the exceptional path: " + run);
    }

    /**
     * An unobserved temporary the proof cannot decide can never be reclaimed, so it is
     * reported at its statement with the blocking fact. Found by review: the ordinary
     * observation skips non-active states, so the leak was silent.
     */
    static void reportsUndecidableTemporaries() throws Exception {
        String source = COMMON + """
                class Main {
                    static void take(Keeper first, Keeper second, Keeper third) { }
                    public static int main(String[] args) {
                        Keeper saved = null;
                        take(saved = new Keeper(1), args.length > 0 ? saved : null, saved = null);
                        System.out.println(Keeper.destroyed);
                        return 0;
                    }
                }
                """;
        CompilationArtifact warned = compile(source, UnfreedMode.WARN);
        require(warned.valid() && warned.diagnostics().size() == 1
                        && warned.diagnostics().getFirst().message()
                        .equals("new allocation is discarded without being freed")
                        && warned.diagnostics().getFirst().notes().size() == 1
                        && warned.diagnostics().getFirst().notes().getFirst().message()
                        .startsWith("temporary could not be reclaimed: "),
                "undecidable temporary must be reported with its blocking fact: "
                        + warned.diagnostics());
        CompilationArtifact strict = compile(source, UnfreedMode.ERROR);
        require(!strict.valid() && strict.diagnostics().size() == 1
                        && strict.diagnostics().getFirst().isError(),
                "undecidable temporary must fail in error mode: " + strict.diagnostics());
        require(frees(warned, "Main", "main") == 0, "an undecidable temporary is not freed");
        CompilationArtifact off = compile(source, UnfreedMode.OFF);
        require(off.valid() && off.diagnostics().isEmpty() && warned.program().equals(off.program()),
                "off mode changes only diagnostics");
    }

    /**
     * A child created after the wrapper that retains it sits in the inner cleanup
     * region, so its pad runs first, while the wrapper is still live. Found by
     * review: each pad probed only its own temporary, so the child leaked on the
     * exceptional path. Each pad now reclaims every candidate provably free at all
     * of its edges, in dependency order.
     */
    static void reclaimsRetainedChainsOnExceptionPaths() throws Exception {
        String source = """
                class K { static int destroyed; destructor { destroyed++; } }
                class Holder {
                    static int destroyed;
                    private K kept;
                    void set(K value, int marker) { kept = value; }
                    destructor { destroyed++; }
                }
                class Main {
                    static int boom() { throw new RuntimeException("boom"); }
                    static int ok() { return 1; }
                    public static int main(String[] args) {
                        long before = System.liveAllocationCount();
                        new Holder().set(new K(), ok());
                        int normal = Holder.destroyed * 10 + K.destroyed;
                        long leaked = System.liveAllocationCount() - before;
                        try {
                            new Holder().set(new K(), boom());
                        } catch (RuntimeException e) { }
                        // Both objects are reclaimed on both paths; the caught exception stays.
                        System.out.println(normal + " " + leaked + " " + (Holder.destroyed * 10 + K.destroyed)
                                + " " + (System.liveAllocationCount() - before));
                        return 0;
                    }
                }
                """;
        NativeRun run = runNative(source);
        require(run.exit() == 0 && run.stdout().equals("11 0 22 1\n") && run.stderr().isEmpty(),
                "a retained chain must be fully reclaimed on the exceptional path: " + run);
    }

    private static void deleteTree(Path root) throws java.io.IOException {
        try (var files = Files.walk(root)) {
            for (Path path : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void expectFrees(String label, String source, int expectedFrees,
                                    List<String> expectedWarnings) {
        CompilationArtifact artifact = compile(source, UnfreedMode.WARN);
        require(artifact.valid(), label + " must compile: " + artifact.diagnostics());
        List<String> warnings = artifact.diagnostics().stream().map(Diagnostic::message).toList();
        require(warnings.equals(expectedWarnings), label + " warnings: " + warnings);
        require(frees(artifact, "Main") == expectedFrees, label + " expected " + expectedFrees
                + " frees but found " + frees(artifact, "Main"));
        CompilationArtifact strict = compile(source, UnfreedMode.ERROR);
        require(strict.valid() == expectedWarnings.isEmpty(), label + " strict mode");
    }

    /** Frees on normal paths; each reclaimed temporary also has one in its unwind pad. */
    private static long frees(CompilationArtifact artifact, String owner) {
        return artifact.program().orElseThrow().functions().stream()
                .filter(function -> function.ownerClass().equals(owner))
                .flatMap(function -> function.blocks().stream())
                .filter(block -> !block.label().startsWith("temporary.cleanup"))
                .flatMap(block -> block.instructions().stream())
                .filter(IrFreeInstruction.class::isInstance).count();
    }

    private static long frees(CompilationArtifact artifact, String owner, String functionName) {
        return artifact.program().orElseThrow().functions().stream()
                .filter(function -> function.ownerClass().equals(owner)
                        && function.sourceName().equals(functionName))
                .flatMap(function -> function.blocks().stream())
                .filter(block -> !block.label().startsWith("temporary.cleanup"))
                .flatMap(block -> block.instructions().stream())
                .filter(IrFreeInstruction.class::isInstance).count();
    }

    private static long unwindFrees(CompilationArtifact artifact, String owner) {
        return artifact.program().orElseThrow().functions().stream()
                .filter(function -> function.ownerClass().equals(owner))
                .flatMap(function -> function.blocks().stream())
                .filter(block -> block.label().startsWith("temporary.cleanup"))
                .flatMap(block -> block.instructions().stream())
                .filter(IrFreeInstruction.class::isInstance).count();
    }

    /**
     * Operation shape of one function: instruction kinds in block order plus the
     * terminators that do work. Plain jumps and unreachable blocks are layout only;
     * the finally form keeps a few more of them than the temporary form.
     */
    private static String shape(CompilationArtifact artifact, String functionName) {
        IrProgram program = artifact.program().orElseThrow();
        IrFunction function = program.functions().stream()
                .filter(candidate -> candidate.ownerClass().equals("Main")
                        && candidate.sourceName().equals(functionName))
                .findFirst().orElseThrow();
        StringBuilder text = new StringBuilder();
        for (IrBasicBlock block : function.blocks()) {
            for (IrInstruction instruction : block.instructions()) {
                text.append(instruction.getClass().getSimpleName()).append('\n');
            }
            String terminator = block.terminator().getClass().getSimpleName();
            if (!terminator.equals("IrJump") && !terminator.equals("IrUnreachable")) {
                text.append("-> ").append(terminator).append('\n');
            }
        }
        return text.toString();
    }

    private static CompilationArtifact compile(String source, UnfreedMode mode) {
        return new CompilerPipeline(mode).analyze(List.of(SourceFile.of("Main.iron", source)));
    }

    private record NativeRun(int exit, String stdout, String stderr) {}

    private static NativeRun runNative(String source) throws Exception {
        Path root = Files.createTempDirectory("ironwood-temporaries-");
        try {
            Path input = root.resolve("Main.iron");
            Files.writeString(input, source);
            Path classes = root.resolve("classes");
            String compileErrors = run(input.toString(), "-d", classes.toString(), "--unfreed=error");
            require(compileErrors.isEmpty(), "compile: " + compileErrors);
            Path executable = root.resolve("app");
            String linkErrors = run("--link", "-cp", classes.toString(), "--main-class", "Main",
                    "-o", executable.toString(), "-O3", "--unfreed=error");
            require(linkErrors.isEmpty(), "link: " + linkErrors);
            Process process = new ProcessBuilder(executable.toString()).start();
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            return new NativeRun(process.waitFor(), stdout, stderr);
        } finally {
            deleteTree(root);
        }
    }

    private static String run(String... args) {
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        int exit = Main.run(args, new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(errors, true, StandardCharsets.UTF_8));
        String text = errors.toString(StandardCharsets.UTF_8);
        return exit == 0 && text.isEmpty() ? "" : "exit " + exit + ": " + text;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
