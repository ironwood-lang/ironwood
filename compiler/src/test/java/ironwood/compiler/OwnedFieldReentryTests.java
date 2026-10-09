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
    private static final String CONTINGENT = "cannot free 'noisy': it can run a destructor while "
            + "local 'old' aliases field 'values'";
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
     * A store whose only reclamation of its field frees, in a catch handler, a local the
     * try body loads from the field but that holds a parameter at the try's entry. Lowering
     * starts a handler that no exception edge reaches from that entry, so no D297-D309
     * witness counts the free, and a free that may run code during a loan still fails the
     * field's proof (D281).
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

                void discard(int[] spare) {
                    int[] old = spare;
                    try {
                        old = values;
                        old[1] = 0;
                    } catch (RuntimeException failure) {
                        values = null;
                        free old;
                    }
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
     * directly or by a deferred free (D298-D310), a free that may run code during a loan
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
                            && messages(constructed).equals(List.of(CONTINGENT)),
                    "a reentrant free was not rejected at the free (" + (header.isEmpty() ? "initializer" : header)
                            + "): " + explained(constructed));
        }
        // A deferred free of the detached local is a witness when lowering reaches it at
        // the block's end or exits (D301), with or without a later loop that can finish;
        // freeing before the loan compiles.
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
                            && messages(deferred).equals(List.of(CONTINGENT)),
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
        // After a while (true) loop, the deferred free is a witness when the loop has a
        // route out that lowering takes: a break, a return or an uncaught throw (D302).
        for (String loop : List.of("""
                while (true) {
                    break;
                }""", """
                for (;;) {
                    if (size > 0) {
                        return;
                    }
                    size++;
                }""", """
                while (true) {
                    throw new IllegalStateException();
                }""")) {
            CompilationArtifact routed = store(deferredBefore(loop) + noisyUse);
            require(!routed.valid() && ownershipFailures(routed).isEmpty()
                            && messages(routed).equals(List.of(CONTINGENT)),
                    "a deferred free before a loop with a route out was no witness: " + explained(routed));
            require(lowersFree(deferredBefore(loop)), "lowering never freed a deferred target the witness counted");
        }
        // A defer in a switch case must sit in braces; braced, the case's break routes
        // out of the block, so the deferred free is a witness (D302).
        String braced = """
                    void pick(int mode) {
                        int[] old = values;
                        values = null;
                        switch (mode) {
                            case 0: {
                                defer free old;
                                break;
                            }
                            default:
                                free old;
                        }
                    }
                """;
        CompilationArtifact cased = store(braced + noisyUse);
        require(!cased.valid() && ownershipFailures(cased).isEmpty() && messages(cased).equals(List.of(CONTINGENT)),
                "a braced deferred free in a switch case was no witness: " + explained(cased));
        require(lowersFree(braced.replace("free old;\n        }", "break;\n        }")),
                "lowering never freed a braced deferred target the witness counted");
        CompilationArtifact unbraced = store("""
                    void pick(int mode) {
                        int[] old = values;
                        values = null;
                        switch (mode) {
                            case 0:
                                defer free old;
                                break;
                            default:
                                free old;
                        }
                    }
                """);
        require(!unbraced.valid() && messages(unbraced).equals(List.of(
                        "defer must be a direct statement of an explicit block; add braces")),
                "a defer directly in a switch group was accepted: " + explained(unbraced));
        // With no route out, or only a break that a finally block which cannot complete
        // stops, lowering may never free the deferred target, so D281 reports at both of
        // the store's reclamations.
        for (String loop : List.of("""
                while (true) {
                }""", """
                while (true) {
                    try {
                        break;
                    } finally {
                        while (true) {
                        }
                    }
                }""")) {
            CompilationArtifact stuck = store(deferredBefore(loop) + noisyUse);
            require(!stuck.valid() && messages(stuck).equals(List.of(RECLAIM, DEFERRED)) && failedBy(stuck, FREE),
                    "a deferred free that may never run counted as a witness: " + explained(stuck));
        }
        // A free in a finally block counts only when lowering reaches that block (D302).
        // After a try body with no route out the free is never lowered, so with no other
        // reclamation the field fails its proof and the program compiles, as under D281;
        // a body that completes makes the free a witness.
        CompilationArtifact unreached = finallyStore("""
                while (true) {
                }""");
        require(unreached.valid() && !lowersFree(finallyMember("while (true) {\n}")),
                "a free in a finally block lowering never reaches disqualified a valid program: "
                        + explained(unreached));
        CompilationArtifact reached = finallyStore("old[0] = size;");
        require(!reached.valid() && ownershipFailures(reached).isEmpty()
                        && messages(reached).equals(List.of(CONTINGENT)),
                "a free in a reached finally block was no witness: " + explained(reached));
        require(lowersFree(finallyMember("old[0] = size;")), "lowering never reached a finally block the witness counted");
        // A local assigned from the field by a statement of a block, a parameter included,
        // is a witness when the block frees it later with no write in between, even with
        // a write after the free (D303); freeing before the loan compiles. A write between
        // the assignment and the free keeps D281's report at the store's reclamations.
        for (String members : List.of("""
                    void reset() {
                        int[] old = null;
                        old = values;
                        values = null;
                        free old;
                    }
                """, """
                    void reset(int[] old) {
                        old = values;
                        values = null;
                        free old;
                    }
                """, """
                    void reset() {
                        int[] old = values;
                        values = null;
                        free old;
                        old = null;
                    }
                """)) {
            CompilationArtifact assigned = store(members + noisyUse);
            require(!assigned.valid() && ownershipFailures(assigned).isEmpty()
                            && messages(assigned).equals(List.of(CONTINGENT)),
                    "a reentrant free beside an assigned reclamation was not rejected at the free: "
                            + explained(assigned));
            CompilationArtifact twin = store(members + """

                        void use() {
                            Noisy noisy = new Noisy();
                            free noisy;
                            int[] old = values;
                            old[0] = 7;
                        }
                    """);
            require(twin.valid(), "freeing before the loan was rejected beside an assigned reclamation: "
                    + explained(twin));
            require(lowersFree(members), "lowering did not reject an assigned free the witness counted");
        }
        // An assignment inside an expression is a witness too when its statement always
        // evaluates it before the free: in a condition, a declaration's initializer or a
        // switch selector, with the free in the statement or after it (D304). On the
        // right of && the assignment may not run, so D281 keeps its report.
        for (String members : List.of("""
                    void reset() {
                        int[] old = null;
                        if ((old = values) != null) {
                            values = null;
                            free old;
                        }
                    }
                """, """
                    void reset() {
                        int[] old = null;
                        if ((old = values) == null) {
                            return;
                        }
                        values = null;
                        free old;
                    }
                """, """
                    int reset() {
                        int[] old = null;
                        int size = (old = values).length;
                        values = null;
                        free old;
                        return size;
                    }
                """, """
                    void reset() {
                        int[] old = null;
                        switch ((old = values).length) {
                            default:
                                values = null;
                                free old;
                        }
                    }
                """)) {
            CompilationArtifact evaluated = store(members + noisyUse);
            require(!evaluated.valid() && ownershipFailures(evaluated).isEmpty()
                            && messages(evaluated).equals(List.of(CONTINGENT)),
                    "a reentrant free beside an expression assignment was not rejected at the free: "
                            + explained(evaluated));
            CompilationArtifact twin = store(members + """

                        void use() {
                            Noisy noisy = new Noisy();
                            free noisy;
                            int[] old = values;
                            old[0] = 7;
                        }
                    """);
            require(twin.valid(), "freeing before the loan was rejected beside an expression assignment: "
                    + explained(twin));
            require(lowersFree(members), "lowering did not reject an expression-assigned free the witness counted");
        }
        // A cast around the load keeps its object, so a cast load is a witness too (D305).
        for (String members : List.of("""
                    void reset() {
                        int[] old = (int[]) values;
                        values = null;
                        free old;
                    }
                """, """
                    void reset() {
                        Object old = (Object) this.values;
                        values = null;
                        free old;
                    }
                """, """
                    void reset() {
                        int[] old = null;
                        if ((old = (int[]) values) != null) {
                            values = null;
                            free old;
                        }
                    }
                """)) {
            CompilationArtifact cast = store(members + noisyUse);
            require(!cast.valid() && ownershipFailures(cast).isEmpty() && messages(cast).equals(List.of(CONTINGENT)),
                    "a reentrant free beside a cast reclamation was not rejected at the free: " + explained(cast));
            CompilationArtifact twin = store(members + """

                        void use() {
                            Noisy noisy = new Noisy();
                            free noisy;
                            int[] old = values;
                            old[0] = 7;
                        }
                    """);
            require(twin.valid(), "freeing before the loan was rejected beside a cast reclamation: "
                    + explained(twin));
            require(lowersFree(members), "lowering did not reject a cast free the witness counted");
        }
        // An assignment that may not run counts where every path to the free runs it
        // (D307): in a switch expression's arm that every completing arm matches, or on
        // the right of && guarding the free. Lowering merges the && paths, so it cannot
        // prove that free even with the field owned, but it still rejects it without
        // ownership.
        String arm = """
                    void reset() {
                        int[] old = null;
                        int unused = switch (0) {
                            default -> {
                                old = values;
                                yield 0;
                            }
                        };
                        values = null;
                        free old;
                    }
                """;
        CompilationArtifact armed = store(arm + noisyUse);
        require(!armed.valid() && ownershipFailures(armed).isEmpty() && messages(armed).equals(List.of(CONTINGENT)),
                "a reentrant free beside a switch arm's load was not rejected at the free: " + explained(armed));
        CompilationArtifact armedTwin = store(arm + """

                    void use() {
                        Noisy noisy = new Noisy();
                        free noisy;
                        int[] old = values;
                        old[0] = 7;
                    }
                """);
        require(armedTwin.valid(), "freeing before the loan was rejected beside a switch arm's load: "
                + explained(armedTwin));
        require(lowersFree(arm), "lowering did not reject a switch arm's free the witness counted");
        String guarded = """
                    void reset(boolean flag) {
                        int[] old = null;
                        if (flag && (old = values) != null) {
                            values = null;
                            free old;
                        }
                    }
                """;
        CompilationArtifact shortCircuit = store(guarded + noisyUse);
        require(!shortCircuit.valid() && ownershipFailures(shortCircuit).isEmpty()
                        && messages(shortCircuit).contains(CONTINGENT),
                "a reentrant free beside a guarded load was not rejected at the free: " + explained(shortCircuit));
        require(lowersFree(guarded), "lowering did not reject a guarded free the witness counted");
        // A pattern binding holds its operand's value where its test is true, so binding
        // the field, also through a negated test that returns, is a witness (D308); a
        // binding of another value is not.
        for (String members : List.of("""
                    void reset() {
                        if (values instanceof int[] old) {
                            values = null;
                            free old;
                        }
                    }
                """, """
                    void reset() {
                        if (!(this.values instanceof int[] old)) {
                            return;
                        }
                        values = null;
                        free old;
                    }
                """)) {
            CompilationArtifact bound = store(members + noisyUse);
            require(!bound.valid() && ownershipFailures(bound).isEmpty() && messages(bound).equals(List.of(CONTINGENT)),
                    "a reentrant free beside a pattern reclamation was not rejected at the free: " + explained(bound));
            CompilationArtifact twin = store(members + """

                        void use() {
                            Noisy noisy = new Noisy();
                            free noisy;
                            int[] old = values;
                            old[0] = 7;
                        }
                    """);
            require(twin.valid(), "freeing before the loan was rejected beside a pattern reclamation: "
                    + explained(twin));
            require(lowersFree(members), "lowering did not reject a pattern free the witness counted");
        }
        // A catch handler starts from the states where lowering may add an exception edge,
        // so a load before the throwing store reaches the handler's free (D309); with the
        // null literal at the try's entry, a handler no edge reaches would see null.
        String caught = """
                    void reset() {
                        int[] old = null;
                        try {
                            old = values;
                            old[1] = 0;
                        } catch (RuntimeException failure) {
                            values = null;
                            free old;
                        }
                    }
                """;
        CompilationArtifact handled = store(caught + noisyUse);
        require(!handled.valid() && ownershipFailures(handled).isEmpty() && messages(handled).equals(List.of(CONTINGENT)),
                "a reentrant free beside a catch reclamation was not rejected at the free: " + explained(handled));
        CompilationArtifact handledTwin = store(caught + """

                    void use() {
                        Noisy noisy = new Noisy();
                        free noisy;
                        int[] old = values;
                        old[0] = 7;
                    }
                """);
        require(handledTwin.valid(), "freeing before the loan was rejected beside a catch reclamation: "
                + explained(handledTwin));
        require(lowersFree(caught), "lowering did not reject a catch free the witness counted");
        CompilationArtifact foreign = store("""
                    void reset(Object other) {
                        if (other instanceof int[] old) {
                            free old;
                        }
                    }
                """ + noisyUse);
        require(!foreign.valid() && messages(foreign).equals(List.of(RECLAIM, RECLAIM)) && failedBy(foreign, FREE),
                "a binding of another value counted as a witness: " + explained(foreign));
        // A free that a skipped assignment may reach is a witness too (D310): it frees a
        // field load on some path, or the null literal, so lowering rejects it without
        // ownership; with the field owned it cannot prove the merged value either. A free
        // of a local that is always null, or never holds a load, keeps D281's report.
        for (String members : List.of("""
                    void reset(boolean flag) {
                        int[] old = null;
                        if (flag && (old = values) != null) {
                            values = null;
                        }
                        free old;
                    }
                """, """
                    void reset(boolean flag) {
                        int[] old = new int[1];
                        if (flag) {
                            old = values;
                            values = new int[1];
                        }
                        free old;
                    }
                """)) {
            CompilationArtifact skipped = store(members + noisyUse);
            require(!skipped.valid() && ownershipFailures(skipped).isEmpty() && messages(skipped).contains(CONTINGENT),
                    "a reentrant free beside a skippable load was not rejected at the free: " + explained(skipped));
            require(lowersFree(members), "lowering did not reject a skippable free the witness counted");
        }
        for (String members : List.of("""
                    void reset() {
                        int[] old = null;
                        free old;
                    }
                """, """
                    void reset(boolean flag) {
                        int[] old = new int[1];
                        if (flag) {
                            old = new int[2];
                        }
                        free old;
                    }
                """)) {
            CompilationArtifact unloaded = store(members + noisyUse);
            require(!unloaded.valid() && messages(unloaded).equals(List.of(RECLAIM, RECLAIM)) && failedBy(unloaded, FREE),
                    "a free of a local that never holds a load counted as a witness: " + explained(unloaded));
        }
        // A write between the load and the free keeps the witness when it writes a field
        // load again: the local itself, a fresh load or a conditional of such values (D306).
        // A write of another value, such as a new array, makes the free free that value, so
        // with no other reclamation the field's failure leaves the store's report only.
        for (String members : List.of("""
                    void reset() {
                        int[] old = values;
                        old = old;
                        values = null;
                        free old;
                    }
                """, """
                    void reset(boolean flag) {
                        int[] old = values;
                        if (flag) {
                            old = values;
                        }
                        values = null;
                        free old;
                    }
                """, """
                    void reset(boolean flag) {
                        int[] old = values;
                        old = flag ? values : old;
                        values = null;
                        free old;
                    }
                """)) {
            CompilationArtifact rewritten = store(members + noisyUse);
            require(!rewritten.valid() && ownershipFailures(rewritten).isEmpty()
                            && messages(rewritten).equals(List.of(CONTINGENT)),
                    "a reentrant free beside a rewritten reclamation was not rejected at the free: "
                            + explained(rewritten));
            CompilationArtifact twin = store(members + """

                        void use() {
                            Noisy noisy = new Noisy();
                            free noisy;
                            int[] old = values;
                            old[0] = 7;
                        }
                    """);
            require(twin.valid(), "freeing before the loan was rejected beside a rewritten reclamation: "
                    + explained(twin));
            require(lowersFree(members), "lowering did not reject a rewritten free the witness counted");
        }
        CompilationArtifact replaced = store("""
                    void reset() {
                        int[] old = values;
                        old = new int[2];
                        values = null;
                        free old;
                    }
                """ + noisyUse);
        require(!replaced.valid() && messages(replaced).equals(List.of(RECLAIM)) && failedBy(replaced, FREE),
                "a free of a replaced value counted as a witness: " + explained(replaced));
        CompilationArtifact overwritten = store("""
                    void reset(int[] spare) {
                        int[] old = null;
                        old = values;
                        values = null;
                        old = spare;
                        free old;
                    }
                """ + noisyUse);
        require(!overwritten.valid() && messages(overwritten).equals(List.of(RECLAIM, RECLAIM))
                        && failedBy(overwritten, FREE),
                "a free after another write to the local counted as a witness: " + explained(overwritten));
        // A free through another receiver is no reclamation (D301): with no free during
        // a loan, writing the field through that receiver fails the field's proof, which
        // the store's own reclamation reports, and a value read through another receiver
        // cannot be freed even while the field stays owned.
        CompilationArtifact written = store("""
                    void absorb(Store other) {
                        int[] old = other.values;
                        other.values = null;
                        free old;
                    }
                """);
        require(!written.valid() && messages(written).equals(List.of(RECLAIM, RECLAIM))
                        && failedBy(written, "this assignment targets the same field on another receiver"),
                "a write through another receiver kept the field: " + explained(written));
        CompilationArtifact peeked = store("""
                    void peek(Store other) {
                        int[] old = other.values;
                        free old;
                    }
                """);
        require(!peeked.valid() && messages(peeked).equals(List.of(RECLAIM)) && ownershipFailures(peeked).isEmpty(),
                "a free through another receiver was accepted or disqualified the field: " + explained(peeked));
    }

    /** A store method that detaches the field, defers freeing it and then runs {@code loop}. */
    private static String deferredBefore(String loop) {
        return "    void spin(int size) {\n        int[] old = values;\n        values = null;\n"
                + "        defer free old;\n" + loop.indent(8) + "    }\n";
    }

    /** A store method that detaches the field and frees it in a finally block after {@code body}. */
    private static String finallyMember(String body) {
        return "    void spin(int size) {\n        int[] old = values;\n        values = null;\n        try {\n"
                + body.indent(12) + "        } finally {\n            free old;\n        }\n    }\n";
    }

    /**
     * A store whose only reclamation is {@link #finallyMember}, beside a free that runs a
     * destructor during a loan.
     */
    private static CompilationArtifact finallyStore(String body) {
        return compile("""
                class Noisy {
                    destructor {
                    }
                }

                class Store {
                    private int[] values = new int[1];

                %s
                    void use() {
                        Noisy noisy = new Noisy();
                        int[] old = values;
                        free noisy;
                        old[0] = 7;
                    }
                }

                class Main {
                    public static int main(String[] args) {
                        return 0;
                    }
                }
                """.formatted(finallyMember(body)));
    }

    /**
     * Whether lowering lowers the free of {@code old} in {@code member}: the store's field
     * escapes through a sibling field, so a load of it keeps an attached identity and
     * every lowering of that free is rejected, by either message, while one never
     * lowered is not reported.
     */
    private static boolean lowersFree(String member) {
        CompilationArtifact artifact = compile("""
                class Store {
                    private int[] values = new int[1];
                    private int[] other;

                    void leak() {
                        other = values;
                    }

                %s}

                class Main {
                    public static int main(String[] args) {
                        return 0;
                    }
                }
                """.formatted(member));
        return messages(artifact).stream().anyMatch(message -> message.startsWith("cannot free 'old'")
                || message.startsWith("cannot prove free of 'old' safe"));
    }

    private static CompilationArtifact compile(String source) {
        return new CompilerPipeline(UnfreedMode.OFF, true, null).analyze(List.of(SourceFile.of("Main.iron", source)));
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
