// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.backend.LlvmEmitter;
import ironwood.compiler.backend.LlvmToolchain;
import ironwood.compiler.backend.NativeBackend;
import ironwood.compiler.backend.OptimizationLevel;
import ironwood.compiler.diagnostic.Diagnostic;
import ironwood.compiler.ir.IrFunction;
import ironwood.compiler.ir.IrNullCheckInstruction;
import ironwood.compiler.ir.IrProgram;
import ironwood.compiler.source.SourceFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * A null check is omitted where a null test, an instanceof test or an earlier null
 * check of the same reference dominates it (D287): a local or SSA value, its reference
 * conversions, or a final field of one receiver that the function cannot store. A
 * destructor that tests a field before calling it therefore neither allocates nor lets
 * a NullPointerException escape, while every path that can reach null keeps its check.
 * A value merged at a join or loop header is covered when every value it merges is
 * non-null on its edge (D288), and a destructor trusts the final fields of its object
 * that every constructor sets to a non-null value until it frees them (D289).
 */
final class NullGuardTests {
    private static final Map<String, String> KINDS = Map.of(
            "destructor may allocate; destructor cleanup must be allocation-free", "allocates",
            "an exception may escape this destructor", "throws",
            "destructor may publish or resurrect 'this'", "publishes");

    private static final String PARTS = """
            class Part { int n; void touch() { n++; } boolean ready() { return n < 3; } }
            """;

    /** Destructors whose calls are guarded are accepted; the others keep both reports. */
    static void destructorsTrustTheirGuards() {
        expect("guarded final field", """
                class Holder { private final Part part; Holder(Part p) { part = p; } destructor { if (part != null) { part.touch(); } } }
                """);
        expect("guarded local of a mutable field", """
                class Holder { private Part part; destructor { Part current = part; if (current != null) { current.touch(); current.touch(); } } }
                """);
        expect("right operand and negated guard", """
                class Holder { private final Part part; int seen; Holder(Part p) { part = p; } destructor { if (part == null || !part.ready()) { seen = 0; } else { part.touch(); } } }
                """);
        expect("conditional expression and pattern binding", """
                class Holder { private final Part part; private Object value; boolean seen; Holder(Part p) { part = p; } destructor { seen = part != null ? part.ready() : false; if (value instanceof Part p) { p.touch(); } } }
                """);
        expect("owned field guarded before its free", """
                class Holder { private final Part part; Holder() { part = new Part(); } destructor { if (part != null) { part.touch(); } free part; } }
                """);
        expect("guard inside the loop that frees", """
                class Holder { private final Part part; Holder() { part = new Part(); } destructor { int i = 0; while (i < 2) { if (part != null) { part.touch(); } free part; i++; } } }
                """);
        expect("unguarded final field", """
                class Holder { private final Part part; Holder(Part p) { part = p; } destructor { part.touch(); } }
                """, "allocates 2", "throws 2");
        // Another call may store a mutable field between the test and the use.
        expect("guarded mutable field", """
                class Holder { private Part part; destructor { if (part != null) { part.touch(); } } }
                """, "allocates 2", "throws 2");
        expect("local reassigned after its guard", """
                class Holder { private Part part; private Part spare; destructor { Part current = part; if (current != null) { current = spare; current.touch(); } } }
                """, "allocates 2", "throws 2");
        expect("use after the guarded branch", """
                class Holder { private final Part part; int seen; Holder(Part p) { part = p; } destructor { if (part != null) { seen = 1; } part.touch(); } }
                """, "allocates 2", "throws 2");
        // Freeing an owned field stores null into it.
        expect("owned field freed before the use", """
                class Holder { private final Part part; Holder() { part = new Part(); } destructor { if (part != null) { free part; part.touch(); } } }
                """, "allocates 2", "throws 2");
        expect("owned field freed in a loop entered after the guard", """
                class Holder { private final Part part; Holder() { part = new Part(); } destructor { if (part != null) { int i = 0; while (i < 2) { part.touch(); free part; i++; } } } }
                """, "allocates 2", "throws 2");
        expect("loop condition after the guard of a freed field", """
                class Holder { private final Part part; Holder() { part = new Part(); } destructor { if (part != null) { while (part.ready()) { free part; } } } }
                """, "allocates 2", "throws 2");
    }

    /** Guards reach through joins and loop headers when every merged value keeps them. */
    static void destructorsTrustGuardsThroughMerges() {
        expect("local guarded before a loop", """
                class Holder { private Part part; destructor { Part current = part; if (current != null) { int i = 0; while (i < 2) { current.touch(); i++; } } } }
                """);
        expect("fallback merged from a guarded final field", """
                class Holder { private Part first; private final Part spare; Holder(Part p) { spare = p; } destructor { if (spare != null) { Part current = first; if (current == null) { current = spare; } current.touch(); } } }
                """);
        expect("guarded local used after an inner loop", """
                class Holder { private Part part; destructor { Part current = part; if (current != null) { int i = 0; while (i < 2) { i++; } current.touch(); } } }
                """);
        expect("local advanced inside the loop", """
                class Holder { private Part part; private Part other; destructor { Part current = part; if (current != null) { int i = 0; while (i < 2) { current.touch(); current = other; i++; } } } }
                """, "allocates 2", "throws 2");
        expect("fallback merged from an unguarded field", """
                class Holder { private Part first; private Part second; destructor { Part current = first; if (current == null) { current = second; } current.touch(); } }
                """, "allocates 2", "throws 2");
    }

    private static final String MERGES = """
            class Node {
                int value;
                Node next;
                Node(int value, Node next) { this.value = value; this.next = next; }
                int get() { return value; }
            }
            class Main {
                static Node none() { return null; }
                static int loopAfterGuard(Node x) { if (x == null) { return -1; } int s = 0; for (int i = 0; i < 3; i++) { s += x.get(); } return s; }
                static int nestedLoops(Node x) { if (x == null) { return -1; } int s = 0; for (int i = 0; i < 2; i++) { for (int j = 0; j < 2; j++) { s += x.get(); } } return s; }
                static int loopWithBreak(Node x) { if (x == null) { return -1; } int s = 0; while (true) { s += x.get(); if (s > 5) { break; } } return s + x.get(); }
                static int joinOfGuarded(Node a, Node b) { Node x = null; if (a != null) { x = a; } else { if (b == null) { return -1; } x = b; } return x.get(); }
                static int joinWithAllocation(Node a) { Node x = a; if (x == null) { x = new Node(7, null); } return x.get(); }
                static int conditionalJoin(Node a) { Node x = a != null ? a : new Node(8, null); return x.get(); }
                static int loopAdvance(Node x) { if (x == null) { return -1; } int s = 0; for (int i = 0; i < 3; i++) { s += x.get(); x = x.next; } return s; }
                static int loopNulled(Node x) { if (x == null) { return -1; } int s = 0; for (int i = 0; i < 3; i++) { s += x.get(); if (i == 1) { x = none(); } } return s; }
                static int joinWithNull(Node a) { Node x = a; if (a == null) { x = none(); } return x.get(); }
                static int joinUnguarded(Node a, Node b) { Node x = null; if (a != null) { x = a; } else { x = b; } return x.get(); }
                static void run(String name, int value) { System.out.println(name + " " + value); }
                static void npe(String name) { System.out.println(name + " NPE"); }
                public static int main(String[] args) {
                    Node list = new Node(1, new Node(2, null));
                    run("loopAfterGuard", loopAfterGuard(list));
                    run("nestedLoops", nestedLoops(list));
                    run("loopWithBreak", loopWithBreak(list));
                    run("joinOfGuarded", joinOfGuarded(null, list));
                    run("joinWithAllocation", joinWithAllocation(null));
                    run("conditionalJoin", conditionalJoin(null));
                    try { run("loopAdvance", loopAdvance(list)); } catch (NullPointerException e) { npe("loopAdvance"); }
                    try { run("loopNulled", loopNulled(list)); } catch (NullPointerException e) { npe("loopNulled"); }
                    try { run("joinWithNull", joinWithNull(null)); } catch (NullPointerException e) { npe("joinWithNull"); }
                    try { run("joinUnguarded", joinUnguarded(null, null)); } catch (NullPointerException e) { npe("joinUnguarded"); }
                    return 0;
                }
            }
            """;

    private static final String MERGES_EXPECTED = """
            loopAfterGuard 3
            nestedLoops 4
            loopWithBreak 7
            joinOfGuarded 1
            joinWithAllocation 7
            conditionalJoin 8
            loopAdvance NPE
            loopNulled NPE
            joinWithNull NPE
            joinUnguarded NPE
            """;

    /** Merged values lose their checks only when every merged value is non-null. */
    static void mergesOmitOnlyRedundantChecks() {
        IrProgram program = compile(MERGES).program().orElseThrow();
        for (String merged : List.of("Main.loopAfterGuard", "Main.nestedLoops", "Main.loopWithBreak",
                "Main.joinOfGuarded", "Main.joinWithAllocation", "Main.conditionalJoin")) {
            require(nullChecks(program, merged) == 0, merged + " kept a redundant null check");
        }
        for (String reachable : List.of("Main.loopAdvance", "Main.loopNulled", "Main.joinWithNull",
                "Main.joinUnguarded")) {
            require(nullChecks(program, reachable) > 0, reachable + " lost a needed null check");
        }
    }

    /** Fields every constructor sets to a non-null value stay non-null until the destructor frees them. */
    static void destructorsTrustConstructedFields() {
        expect("field initialized with an allocation", """
                class Holder { private final Part part = new Part(); destructor { part.touch(); free part; } }
                """);
        expect("field assigned an allocation by the constructor", """
                class Holder { private final Part part; Holder() { part = new Part(); } destructor { part.touch(); free part; } }
                """);
        expect("field assigned a guarded parameter", """
                class Holder { private final Part part; Holder(Part p) { if (p == null) { throw new IllegalArgumentException(); } part = p; } destructor { part.touch(); } }
                """);
        expect("superclass field in a subclass destructor", """
                class Base { protected final Part part = new Part(); }
                class Holder extends Base { destructor { part.touch(); } }
                """);
        expect("field assigned an unchecked parameter", """
                class Holder { private final Part part; Holder(Part p) { part = p; } destructor { part.touch(); } }
                """, "allocates 2", "throws 2");
        expect("field one constructor sets to null", """
                class Holder { private final Part part; Holder() { part = new Part(); } Holder(int empty) { part = null; } destructor { part.touch(); } }
                """, "allocates 2", "throws 2");
        // Freeing an owned field stores null into it.
        expect("field used after its free", """
                class Holder { private final Part part = new Part(); destructor { free part; part.touch(); } }
                """, "allocates 2", "throws 2");
        expect("field freed inside a loop", """
                class Holder { private final Part part = new Part(); destructor { int i = 0; while (i < 2) { part.touch(); free part; i++; } } }
                """, "allocates 2", "throws 2");
        // A subclass destructor runs first and may free its own fields.
        expect("subclass field in a superclass destructor", """
                class Holder { destructor { if (this instanceof Leaf leaf) { leaf.part.touch(); } } }
                class Leaf extends Holder { final Part part = new Part(); }
                """, "allocates 2", "allocates 3", "throws 2", "throws 3");
        // A method may also run during construction or after the free.
        expect("field used by a method the destructor calls", """
                class Holder { private final Part part = new Part(); destructor { use(); free part; } void use() { part.touch(); } }
                """, "allocates 2", "throws 2");
    }

    /** A `throw new` whose fresh object is no longer null-checked still calls the cold helper. */
    static void explicitThrowsStayOutlined() {
        IrProgram program = compile("""
                class Main {
                    static int check(int value) { if (value < 0) { throw new IllegalStateException(); } return value; }
                    public static int main(String[] args) { return check(args.length); }
                }
                """).program().orElseThrow();
        require(nullChecks(program, "Main.check") == 0, "the thrown allocation kept its null check");
        String llvm = new LlvmEmitter().emit(program);
        int start = llvm.indexOf("@\"ironwood.Main.check\"(");
        require(start >= 0, "missing Main.check in LLVM");
        String body = llvm.substring(start, llvm.indexOf("\n}\n", start));
        require(body.contains("call void @\"ironwood.throw.ironwood.ironwood.lang.IllegalStateException.<init>"),
                "throw new was not outlined:\n" + body);
        require(!body.contains("@ironwood_allocate"), "throw new allocates inline:\n" + body);
    }

    private static final String PROGRAM = """
            class Node {
                int value;
                Node next;
                Node(int value) { this.value = value; }
                int get() { return value; }
            }
            class Box {
                final Node node;
                Node mutable;
                Box(Node node) { this.node = node; this.mutable = node; }
                int guarded() { if (node != null) { return node.get() + node.get(); } return -1; }
                int mutableGuard() { if (mutable != null) { clear(); return mutable.get(); } return -1; }
                void clear() { mutable = null; }
            }
            class Main {
                static Node none() { return null; }
                static int earlyOr(Node x) { if (x == null || x.get() == 0) { return -1; } return x.get(); }
                static int walk(Node x) { int sum = 0; while (x != null) { sum += x.get(); x = x.next; } return sum; }
                static int pattern(Object o) { if (o instanceof Node n) { return n.get(); } return -1; }
                static int ternary(Node x) { return x != null && x.get() > 0 ? x.get() : -1; }
                static int afterLoop(Node x, Node y) { do { if (x == null) { x = y; } } while (x == null); return x.get(); }
                static int reassigned(Node x) { if (x != null) { x = none(); return x.get(); } return -1; }
                static int merged(Node x) { int seen = 0; if (x != null) { seen = 1; } return seen + x.get(); }
                static int loopReassign(Node x) {
                    int sum = 0;
                    if (x != null) { for (int i = 0; i < 2; i++) { sum += x.get(); x = null; } }
                    return sum;
                }
                static int repeated(Node x) { int first = x.get(); return first + x.get(); }
                static int handler(Node x) {
                    try { if (x == null) { throw new IllegalStateException(); } return x.get(); }
                    catch (IllegalStateException e) { return x.get(); }
                }
                static int labeled(Node x) {
                    int sum = 0;
                    found: { if (x == null) { break found; } sum = x.get(); }
                    return sum + x.get();
                }
                static int fallthrough(int k, Node x) {
                    int sum = 0;
                    switch (k) {
                        case 1: if (x == null) { return -1; } sum = x.get();
                        case 2: sum += x.get();
                    }
                    return sum;
                }
                static int cleanup(Node x) {
                    try { if (x == null) { return -1; } } finally { System.out.println("finally " + x.get()); }
                    return 0;
                }
                static void run(String name, int value) { System.out.println(name + " " + value); }
                static void npe(String name) { System.out.println(name + " NPE"); }
                public static int main(String[] args) {
                    Node one = new Node(1);
                    one.next = new Node(2);
                    run("guarded", new Box(one).guarded());
                    run("guarded-null", new Box(null).guarded());
                    run("earlyOr", earlyOr(null));
                    run("walk", walk(one));
                    run("pattern", pattern(null));
                    run("ternary", ternary(null));
                    run("afterLoop", afterLoop(null, one));
                    try { run("mutableGuard", new Box(one).mutableGuard()); } catch (NullPointerException e) { npe("mutableGuard"); }
                    try { run("reassigned", reassigned(one)); } catch (NullPointerException e) { npe("reassigned"); }
                    try { run("merged", merged(null)); } catch (NullPointerException e) { npe("merged"); }
                    try { run("loopReassign", loopReassign(one)); } catch (NullPointerException e) { npe("loopReassign"); }
                    try { run("repeated", repeated(null)); } catch (NullPointerException e) { npe("repeated"); }
                    try { run("handler", handler(null)); } catch (NullPointerException e) { npe("handler"); }
                    try { run("labeled", labeled(null)); } catch (NullPointerException e) { npe("labeled"); }
                    try { run("fallthrough", fallthrough(2, null)); } catch (NullPointerException e) { npe("fallthrough"); }
                    run("fallthrough-guarded", fallthrough(1, null));
                    try { run("cleanup", cleanup(null)); } catch (NullPointerException e) { npe("cleanup"); }
                    run("cleanup-value", cleanup(one));
                    return 0;
                }
            }
            """;

    private static final String EXPECTED = """
            guarded 2
            guarded-null -1
            earlyOr -1
            walk 3
            pattern -1
            ternary -1
            afterLoop 1
            mutableGuard NPE
            reassigned NPE
            merged NPE
            loopReassign NPE
            repeated NPE
            handler NPE
            labeled NPE
            fallthrough NPE
            fallthrough-guarded -1
            cleanup NPE
            finally 1
            cleanup-value 0
            """;

    /** Guarded uses lose their checks; uses that a null can reach keep them. */
    static void guardsOmitOnlyRedundantChecks() {
        IrProgram program = compile(PROGRAM).program().orElseThrow();
        for (String guarded : List.of("Box.guarded", "Main.earlyOr", "Main.walk", "Main.pattern",
                "Main.ternary", "Main.afterLoop")) {
            require(nullChecks(program, guarded) == 0, guarded + " kept a guarded null check");
        }
        for (String reachable : List.of("Box.mutableGuard", "Main.reassigned", "Main.merged",
                "Main.loopReassign", "Main.repeated", "Main.handler", "Main.labeled",
                "Main.fallthrough", "Main.cleanup")) {
            require(nullChecks(program, reachable) > 0, reachable + " lost a needed null check");
        }
    }

    /** Every path that reaches null still throws a catchable NullPointerException. */
    static void nullsStillThrowAtRuntime() throws Exception {
        run("guards", PROGRAM, EXPECTED);
        run("merges", MERGES, MERGES_EXPECTED);
        run("constructed", CONSTRUCTED, CONSTRUCTED_EXPECTED);
    }

    private static final String CONSTRUCTED = """
            class Part { static int touched; void touch() { touched++; } }
            class Initialized { private final Part part = new Part(); destructor { part.touch(); free part; } }
            class Guarded { private final Part part; Guarded(Part p) { if (p == null) { throw new IllegalArgumentException(); } part = p; } destructor { part.touch(); } }
            class Base { protected final Part shared = new Part(); }
            class Derived extends Base { destructor { shared.touch(); } }
            class Main {
                public static int main(String[] args) {
                    Initialized initialized = new Initialized();
                    free initialized;
                    Guarded guarded = new Guarded(new Part());
                    free guarded;
                    try {
                        Guarded rejected = new Guarded(null);
                        free rejected;
                    } catch (IllegalArgumentException e) {
                        System.out.println("rejected null");
                    }
                    Derived derived = new Derived();
                    free derived;
                    System.out.println("touched " + Part.touched);
                    return 0;
                }
            }
            """;

    private static final String CONSTRUCTED_EXPECTED = """
            rejected null
            touched 3
            """;

    private static void run(String name, String program, String expected) throws Exception {
        CompilationArtifact artifact = compile(program);
        Path root = Path.of("integration-tests/target/null-guards").toAbsolutePath();
        Files.createDirectories(root);
        Path llvm = root.resolve(name + ".ll");
        Files.writeString(llvm, new LlvmEmitter().emit(artifact.program().orElseThrow()));
        for (OptimizationLevel level : List.of(OptimizationLevel.O0, OptimizationLevel.O3)) {
            Path binary = root.resolve(name + "-" + level);
            var linked = new NativeBackend().link(
                    LlvmToolchain.discover(null).toolchain().orElseThrow(), llvm, binary, level);
            require(linked.success(), linked.output());
            Process process = new ProcessBuilder(binary.toString()).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = process.waitFor();
            require(exit == 0 && output.equals(expected),
                    binary + " exited " + exit + " with:\n" + output);
        }
    }

    private static CompilationArtifact compile(String program) {
        CompilationArtifact artifact = new CompilerPipeline(UnfreedMode.OFF)
                .compile(SourceFile.of("Main.iron", program));
        require(artifact.successful(), artifact.diagnostics().toString());
        return artifact;
    }

    private static long nullChecks(IrProgram program, String method) {
        IrFunction function = program.functions().stream()
                .filter(candidate -> candidate.linkageName().equals("ironwood." + method))
                .findFirst().orElseThrow(() -> new AssertionError("missing " + method));
        return function.blocks().stream().flatMap(block -> block.instructions().stream())
                .filter(IrNullCheckInstruction.class::isInstance).count();
    }

    private static void expect(String name, String declarations, String... expected) {
        SourceFile source = SourceFile.of("Main.iron", PARTS + declarations
                + "class Main { public static int main(String[] args) { return 0; } }\n");
        List<Diagnostic> diagnostics = new CompilerPipeline(UnfreedMode.OFF)
                .analyze(List.of(source)).diagnostics();
        List<String> reported = diagnostics.stream()
                .map(d -> KINDS.containsKey(d.message()) && d.source() != null
                        && d.source().path().equals(source.path())
                        ? KINDS.get(d.message()) + " " + d.span().start().line()
                        : d.message() + " @ " + (d.source() == null ? "program" : d.source().path()))
                .sorted().toList();
        List<String> wanted = java.util.Arrays.stream(expected).sorted().toList();
        require(reported.equals(wanted), name + ": expected " + wanted + " but got " + reported);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
