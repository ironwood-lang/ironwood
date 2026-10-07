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
