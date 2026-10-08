// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.source.SourceFile;

import java.util.List;

/**
 * An attached private-field allocation cannot cross code that may run while a local
 * alias of it is live (D041, D281): that code could replace and free the field, leaving
 * the alias dangling. Each unsafe case below reaches such code without a source call;
 * its safe twin moves the code out of the alias's lifetime.
 */
final class OwnedFieldReentryTests {
    /** Each case is one Buffer member; drop() is the reclamation that reentry reaches. */
    private static final String PROGRAM = """
            import ironwood.util.Iterator;

            class Buffer {
                private int[] values = new int[1];

                void drop() {
                    int[] old = values;
                    values = null;
                    free old;
                }

            %s
            }

            class Registry {
                static Buffer buffer;
                static int[] leaked;
            }

            class Hook {
                Buffer buffer;

                @Override
                public String toString() {
                    buffer.drop();
                    return "hook";
                }

                void fire() {
                    buffer.drop();
                }

                boolean more() {
                    buffer.drop();
                    return false;
                }
            }

            class Trigger {
                static int value = start();

                static int start() {
                    Registry.buffer.drop();
                    return 1;
                }

                static Object make() {
                    Registry.buffer.drop();
                    return new Object();
                }
            }

            enum Color {
                RED;

                Color() {
                    Registry.buffer.drop();
                }
            }

            interface Holder {
                Object VALUE = Trigger.make();
            }

            class Victim {
                Buffer buffer;

                destructor {
                    Buffer target = buffer;
                    if (target != null) {
                        target.drop();
                    }
                }
            }

            class Plain {
            }

            final class Walker implements Iterable<String> {
                Buffer buffer;
                final Empty empty = new Empty();

                @Override
                public Iterator<String> iterator() {
                    buffer.drop();
                    return empty;
                }
            }

            final class Empty implements Iterator<String> {
                @Override
                public boolean hasNext() {
                    return false;
                }

                @Override
                public String next() {
                    return null;
                }
            }

            class Main {
                public static int main(String[] args) {
                    return 0;
                }
            }
            """;

    private static final String DEFERRED = "cannot defer free of 'old': target must be a live, "
            + "proven owned local reference";
    private static final String RECLAIM = "cannot prove free of 'old' safe: value is not a known "
            + "allocation created by new in this method, returned by a proven fresh factory, or a "
            + "proven detached private backing array";
    private static final String FREE = "this free can run a destructor while a local alias "
            + "of the field's allocation remains active";
    private static final String CODE = "this expression can run other code while a local "
            + "alias of the field's allocation remains active";
    private static final String CALL = "this call occurs while a local alias of the field's "
            + "allocation remains active";

    /** One reentry route: the unsafe member, its safe twin and the reason the field fails. */
    private record Route(String name, String unsafe, String safe, String reason) {
    }

    static void reentryRoutes() {
        for (Route route : routes()) {
            CompilationArtifact unsafe = analyze(route.unsafe());
            require(!unsafe.valid() && ownershipFailures(unsafe).stream()
                            .anyMatch(note -> note.endsWith("failed: " + route.reason())),
                    route.name() + " was not rejected for reentry: " + explained(unsafe));
            CompilationArtifact safe = analyze(route.safe());
            require(safe.valid(), route.name() + " safe twin was rejected: " + explained(safe));
        }
    }

    /** Runtime checks, String and primitive conversion and own-class statics run no other code. */
    static void fixedOperationsInsideLoan() {
        CompilationArtifact artifact = analyze("""
                    static int limit = 4;

                    int use(int index, Object any, String text, int divisor, int[] numbers) {
                        int[] old = values;
                        int first = old[index];
                        String label = (String) any;
                        String note = "n" + old.length + text + label + true;
                        int total = first / divisor + limit + Buffer.limit;
                        for (int number : numbers) {
                            total += number;
                        }
                        values = new int[old.length + 1];
                        System.arraycopy(old, 0, values, 0, old.length);
                        free old;
                        return total + note.length();
                    }
                """);
        require(artifact.valid(), "fixed operations disqualified the field: " + explained(artifact));
    }

    /**
     * A store whose only reclamation of its field frees a local assigned from it after
     * its declaration, which no D297-D301 witness covers, so a free that may run code
     * during a loan still fails the field's proof (D281) and that free reports it.
     */
    private static final String STORE = """
            class Noisy {
                destructor {
                }
            }

            class Plain {
            }

            class Store {
                private int[] values = new int[1];

                void discard() {
                    int[] old = null;
                    old = values;
                    values = null;
                    free old;
                }

            %s
            }

            class Main {
                public static int main(String[] args) {
                    return 0;
                }
            }
            """;

    /**
     * Freeing an object with a destructor during a loan fails the field's proof, so the
     * store's reclamation is rejected, and the safe twin compiles (D281). A free that
     * lowering rejects has no instruction, so the type of the freed local decides
     * whether it may run code (D296): a class with a destructor still fails the proof,
     * one without leaves only the free's own error.
     */
    static void rejectedFrees() {
        CompilationArtifact accepted = store("""
                    void use() {
                        Noisy noisy = new Noisy();
                        int[] old = values;
                        free noisy;
                        old[0] = 7;
                    }
                """);
        require(!accepted.valid() && messages(accepted).equals(List.of(RECLAIM)) && failedBy(accepted, FREE),
                "a free that runs a destructor during a loan kept the field: " + explained(accepted));
        CompilationArtifact twin = store("""
                    void use() {
                        Noisy noisy = new Noisy();
                        free noisy;
                        int[] old = values;
                        old[0] = 7;
                    }
                """);
        require(twin.valid(), "freeing before the loan was rejected: " + explained(twin));
        CompilationArtifact running = store("""
                    void use(Noisy noisy) {
                        int[] old = values;
                        free noisy;
                        old[0] = 7;
                    }
                """);
        require(!running.valid() && messages(running).contains(RECLAIM) && failedBy(running, FREE),
                "a rejected free that runs a destructor kept the field: " + explained(running));
        CompilationArtifact inert = store("""
                    void use(Plain plain) {
                        int[] old = values;
                        free plain;
                        old[0] = 7;
                    }
                """);
        require(!inert.valid() && inert.diagnostics().stream()
                        .allMatch(diagnostic -> diagnostic.message().contains("free of 'plain'")),
                "a rejected free without a destructor disqualified the field: " + explained(inert));
    }

    private static CompilationArtifact store(String member) {
        SourceFile source = SourceFile.of("Main.iron", STORE.formatted(member));
        return new CompilerPipeline(UnfreedMode.OFF, true, null).analyze(List.of(source));
    }

    /**
     * When the program needs the field owned, because the owner's destructor frees it
     * (D297) or its instance code frees a local loaded from it, as Buffer.drop does,
     * directly or by a deferred free (D298-D301), a free that may run code during a loan
     * leaves the field owned and is rejected at the free: freeing an object whose
     * destructor reenters names the alias and field it would cross, its safe twin
     * compiles, and freeing the owner, whose destructor frees the field directly, inside
     * a branch or through drop(), names the alias.
     */
    static void contingentFrees() {
        CompilationArtifact reentrant = analyze("""
                    void use() {
                        Victim victim = new Victim();
                        int[] old = values;
                        free victim;
                        old[0] = 7;
                    }
                """);
        require(!reentrant.valid() && ownershipFailures(reentrant).isEmpty() && messages(reentrant).equals(List.of(
                        "cannot free 'victim': it can run a destructor while local 'old' aliases field 'values'")),
                "a reentrant free during a loan was not rejected at the free: " + explained(reentrant));
        CompilationArtifact safe = analyze("""
                    void use() {
                        Victim victim = new Victim();
                        free victim;
                        int[] old = values;
                        old[0] = 7;
                    }
                """);
        require(safe.valid(), "freeing before the loan was rejected: " + explained(safe));
        String leak = """

                    static int leak() {
                        Buffer buffer = new Buffer();
                        int[] old = buffer.values;
                        free buffer;
                        return old[0];
                    }
                """;
        for (String destructor : List.of("free values;", "if (values != null) {\n            free values;\n        }",
                "drop();")) {
            CompilationArtifact owner = analyze("""
                        destructor {
                            %s
                        }
                    """.formatted(destructor) + leak);
            require(!owner.valid() && ownershipFailures(owner).isEmpty() && messages(owner).equals(List.of(
                            "cannot free 'buffer': allocation may still be observed through local 'old'")),
                    "freeing the owner during a loan did not name the alias (" + destructor + "): "
                            + explained(owner));
        }
        // On the store, whose reclamation is no witness, each witness acts alone: a
        // branch free in the destructor (D297, D298), a destructor that frees through
        // drop() (D298), and one whose method declares the local in a branch (D299).
        String storeLeak = """

                    static int leak() {
                        Store store = new Store();
                        int[] old = store.values;
                        free store;
                        return old[0];
                    }
                """;
        for (String members : List.of("""
                    destructor {
                        if (values != null) {
                            free values;
                        }
                    }
                """, """
                    destructor {
                        drop();
                    }

                    void drop() {
                        int[] old = values;
                        values = null;
                        free old;
                    }
                """, """
                    destructor {
                        reset();
                    }

                    void reset() {
                        if (values != null) {
                            int[] old = values;
                            values = null;
                            free old;
                        }
                    }
                """)) {
            CompilationArtifact owner = store(members + storeLeak);
            require(!owner.valid() && ownershipFailures(owner).isEmpty() && messages(owner).equals(List.of(
                            "cannot free 'store': allocation may still be observed through local 'old'")),
                    "freeing the store during a loan did not name the alias: " + explained(owner));
        }
        // A detaching free in a constructor or an instance initializer is a witness too
        // (D300): freeing an object with a destructor during a loan is rejected at the free.
        String noisyUse = """

                    void use() {
                        Noisy noisy = new Noisy();
                        int[] old = values;
                        free noisy;
                        old[0] = 7;
                    }
                """;
        for (String header : List.of("Store()", "")) {
            CompilationArtifact constructed = store("""
                        %s {
                            int[] old = values;
                            values = new int[2];
                            free old;
                        }
                    """.formatted(header) + noisyUse);
            require(!constructed.valid() && ownershipFailures(constructed).isEmpty()
                            && messages(constructed).equals(List.of("cannot free 'noisy': it can run a destructor"
                                    + " while local 'old' aliases field 'values'")),
                    "a reentrant free was not rejected at the free (" + (header.isEmpty() ? "initializer" : header)
                            + "): " + explained(constructed));
        }
        // A deferred free of the detached local is a witness when lowering reaches it at
        // the block's end or exits (D301), with or without a later loop that can finish;
        // freeing before the loan compiles. After a while (true) loop the deferred free
        // may never run, so D281 reports at both of the store's reclamations.
        String clear = """
                    void clear() {
                        int[] old = values;
                        values = null;
                        defer free old;
                    }
                """;
        String grow = """
                    void grow(int size) {
                        int[] old = values;
                        values = new int[size];
                        defer free old;
                        for (int index = 0; index < old.length && index < size; index++) {
                            values[index] = old[index];
                        }
                    }
                """;
        for (String members : List.of(clear, grow)) {
            CompilationArtifact deferred = store(members + noisyUse);
            require(!deferred.valid() && ownershipFailures(deferred).isEmpty()
                            && messages(deferred).equals(List.of("cannot free 'noisy': it can run a destructor"
                                    + " while local 'old' aliases field 'values'")),
                    "a reentrant free beside a deferred reclamation was not rejected at the free: "
                            + explained(deferred));
            CompilationArtifact twin = store(members + """

                        void use() {
                            Noisy noisy = new Noisy();
                            free noisy;
                            int[] old = values;
                            old[0] = 7;
                        }
                    """);
            require(twin.valid(), "freeing before the loan was rejected beside a deferred reclamation: "
                    + explained(twin));
        }
        CompilationArtifact looping = store("""
                    void clear() {
                        int[] old = values;
                        values = null;
                        defer free old;
                        while (true) {
                            break;
                        }
                    }
                """ + noisyUse);
        require(!looping.valid() && messages(looping).equals(List.of(RECLAIM, DEFERRED)) && failedBy(looping, FREE),
                "a deferred free after a while (true) loop counted as a witness: " + explained(looping));
    }

    private static boolean failedBy(CompilationArtifact artifact, String reason) {
        return ownershipFailures(artifact).stream().anyMatch(note -> note.endsWith("failed: " + reason));
    }

    private static List<String> messages(CompilationArtifact artifact) {
        return artifact.diagnostics().stream().map(diagnostic -> diagnostic.message()).toList();
    }

    private static List<Route> routes() {
        return List.of(
                around("string conversion", "Hook hook", "String text = \"x\" + hook;", CODE),
                around("compound string conversion", "Hook hook",
                        "String text = \"x\";\n        text += hook;", CODE),
                around("static field read", "", "int seen = Trigger.value;", CODE),
                around("static field write", "", "Trigger.value = 2;", CODE),
                around("enum constant", "", "Color color = Color.RED;", CODE),
                around("interface field", "", "Object value = Holder.VALUE;", CODE),
                around("enhanced-for iteration", "Walker walker",
                        "for (String text : walker) {\n        }", CODE),
                new Route("array store value", """
                            void use() {
                                values[0] = Trigger.value;
                            }
                        """, """
                            void use() {
                                int seen = Trigger.value;
                                values[0] = seen;
                            }
                        """, "this array store has a reentrant index or value while the "
                        + "field's storage is attached"),
                new Route("loop condition", """
                            void use(Hook hook) {
                                int[] old = null;
                                while (hook.more()) {
                                    if (old != null) {
                                        old[0] = 7;
                                    }
                                    old = values;
                                }
                            }
                        """, """
                            void use(Hook hook) {
                                while (hook.more()) {
                                    int[] seen = values;
                                    seen[0] = 7;
                                }
                            }
                        """, CALL),
                new Route("catch handler", """
                            void use(Hook hook) {
                                int[] old = null;
                                try {
                                    old = values;
                                    old[1] = 7;
                                } catch (RuntimeException failure) {
                                    hook.fire();
                                }
                                if (old != null) {
                                    old[0] = 7;
                                }
                            }
                        """, """
                            void use(Hook hook) {
                                try {
                                    int[] seen = values;
                                    seen[1] = 7;
                                } catch (RuntimeException failure) {
                                    hook.fire();
                                }
                            }
                        """, CALL),
                new Route("deferred call", """
                            void use(Hook hook) {
                                int[] old = null;
                                if (hook != null) {
                                    defer hook.fire();
                                    old = values;
                                }
                                if (old != null) {
                                    old[0] = 7;
                                }
                            }
                        """, """
                            void use(Hook hook) {
                                defer hook.fire();
                                int[] seen = values;
                                seen[0] = 7;
                            }
                        """, "this deferred action can run while a local alias of the "
                        + "field's allocation remains active"),
                new Route("static initializer", """
                            static Buffer shared = new Buffer();

                            static {
                                int[] alias = shared.values;
                                shared.drop();
                                alias[0] = 7;
                            }
                        """, """
                            static Buffer shared = new Buffer();

                            static {
                                int[] alias = shared.values;
                                alias[0] = 7;
                            }
                        """, CALL),
                new Route("destructor", """
                            destructor {
                                Registry.leaked = values;
                                free values;
                            }
                        """, """
                            destructor {
                                free values;
                            }
                        """, "this assignment publishes the field's allocation outside "
                        + "its owning field"));
    }

    /** {@code code} between loading the field and replacing it, and before loading it. */
    private static Route around(String name, String parameter, String code, String reason) {
        String unsafe = """
                    void use(%s) {
                        int[] old = values;
                        %s
                        values = new int[2];
                        free old;
                    }
                """.formatted(parameter, code);
        String safe = """
                    void use(%s) {
                        %s
                        int[] old = values;
                        values = new int[2];
                        free old;
                    }
                """.formatted(parameter, code);
        return new Route(name, unsafe, safe, reason);
    }

    private static CompilationArtifact analyze(String member) {
        SourceFile source = SourceFile.of("Main.iron", PROGRAM.formatted(member));
        return new CompilerPipeline(UnfreedMode.OFF, true, null).analyze(List.of(source));
    }

    private static List<String> ownershipFailures(CompilationArtifact artifact) {
        return artifact.diagnostics().stream().flatMap(diagnostic -> diagnostic.notes().stream())
                .map(note -> note.message())
                .filter(message -> message.startsWith("the ownership proof for field 'values'"))
                .toList();
    }

    private static List<String> explained(CompilationArtifact artifact) {
        return artifact.diagnostics().stream()
                .map(d -> d.message() + " @ " + d.span().start().line() + " "
                        + d.notes().stream().map(note -> note.message()).toList())
                .toList();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
