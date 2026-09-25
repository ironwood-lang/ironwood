// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.diagnostic.Diagnostic;
import ironwood.compiler.diagnostic.DiagnosticNote;
import ironwood.compiler.source.SourceFile;

import java.util.List;

/**
 * Locks the rendered diagnostic of every rejection kind the free proof probe can
 * return. The primary message, its line, and the explanation notes were recorded
 * from the compiler before the probe was split from the renderer.
 */
final class FreeProofProbeTests {
    private static final String COMMON = """
            class Keeper {
                int value() { return 1; }
            }
            class Sink {
                static Keeper kept;
                static void use(Keeper keeper) { }
            }
            class Holder {
                final Keeper held;
                Holder(Keeper held) { this.held = held; }
            }
            """;

    private FreeProofProbeTests() {}

    /** One expected note: its text and the source line it points at, or null for no span. */
    private record Note(String text, String at) {}

    private record Case(String name, String main, String free, String primary, List<Note> notes) {}

    private static final List<Case> CASES = List.of(
            new Case("NotReference", """
                    class Main {
                        public static void main(String[] args) {
                            int x = 1;
                            free x;
                        }
                    }
                    """, "free x;",
                    "free target must have a class, interface, or array reference type, not int",
                    List.of()),
            new Case("NoIdentityNamed", """
                    class Main {
                        static void f(Keeper k) {
                            free k;
                        }
                        public static void main(String[] args) { f(null); }
                    }
                    """, "free k;",
                    "cannot prove free of 'k' safe: value is not a known allocation created by new "
                    + "in this method, returned by a proven fresh factory, or a proven detached "
                    + "private backing array",
                    List.of(new Note("parameter 'k' is supplied by its caller; no fresh allocation "
                            + "identity is proved", "static void f(Keeper k) {"))),
            new Case("NoIdentityExpression", """
                    class Main {
                        public static void main(String[] args) {
                            free args[0];
                        }
                    }
                    """, "free args[0];",
                    "cannot prove free of expression safe: free target must be a local variable "
                    + "created by new in this method or a proven fresh expression",
                    List.of(new Note("this expression has no proven fresh allocation origin",
                            "free args[0];"))),
            new Case("DependentBorrow", """
                    class Main {
                        static ironwood.util.Iterator<String> iterator(ironwood.ds.ArrayList<String> list) {
                            return list.iterator();
                        }
                        public static void main(String[] args) {
                            ironwood.ds.ArrayList<String> list = new ironwood.ds.ArrayList<String>();
                            ironwood.util.Iterator<String> iterator = iterator(list);
                            free iterator;
                            free list;
                        }
                    }
                    """, "free iterator;",
                    "cannot free 'iterator': value is a borrowed helper owned by another object",
                    List.of(new Note("container 'list' lends a dependent helper acquired or "
                            + "propagated here; the helper cannot be freed independently",
                            "ironwood.util.Iterator<String> iterator = iterator(list);"))),
            new Case("PendingDeferredFree", """
                    class Main {
                        public static void main(String[] args) {
                            Keeper k = new Keeper();
                            defer free k;
                            free k;
                        }
                    }
                    """, "defer free k;\n        free k;",
                    "cannot free 'k': allocation has a pending deferred free",
                    List.of(new Note("this deferred free is bound to 'k' and schedules reclamation "
                            + "of the same allocation at block exit", "defer free k;"))),
            new Case("RetainingOwner", """
                    class Main {
                        public static void main(String[] args) {
                            ironwood.ds.ArrayList<Keeper> list = new ironwood.ds.ArrayList<Keeper>();
                            Keeper k = new Keeper();
                            list.add(k);
                            free k;
                            free list;
                        }
                    }
                    """, "free k;",
                    "cannot free 'k': allocation is still borrowed by a live container",
                    List.of(new Note("container 'list' retains this allocation through this operation",
                            "list.add(k);"))),
            new Case("PendingDeferredCall", """
                    class Main {
                        public static void main(String[] args) {
                            Keeper k = new Keeper();
                            defer Sink.use(k);
                            free k;
                        }
                    }
                    """, "free k;",
                    "cannot free 'k': allocation is retained by a pending deferred call",
                    List.of(new Note("argument 1 of this deferred call captured the allocation here, "
                            + "when 'k' still referred to it", "defer Sink.use(k);"))),
            new Case("PendingYield", """
                    class Main {
                        public static void main(String[] args) {
                            Keeper r = switch (args.length) {
                                default -> {
                                    Keeper k = new Keeper();
                                    try {
                                        yield k;
                                    } finally {
                                        free k;
                                    }
                                }
                            };
                            Sink.use(r);
                        }
                    }
                    """, "free k;",
                    "cannot free 'k': allocation is retained by a pending yield result",
                    List.of(new Note("this pending yield result still observes the allocation "
                            + "during cleanup", "yield k;"),
                            new Note("this cleanup is checked for this yield", "yield k;"))),
            new Case("AlreadyFreed", """
                    class Main {
                        public static void main(String[] args) {
                            Keeper k = new Keeper();
                            free k;
                            free k;
                        }
                    }
                    """, "free k;\n        free k;",
                    "cannot free 'k': allocation was already freed",
                    List.of(new Note("the same allocation was freed here", "free k;"))),
            new Case("BlockedStaticEscape", """
                    class Main {
                        public static void main(String[] args) {
                            Keeper k = new Keeper();
                            Sink.kept = k;
                            free k;
                        }
                    }
                    """, "free k;",
                    "cannot free 'k': allocation escapes through static field 'Sink.kept'",
                    List.of(new Note("this operation established the selected ownership reason: "
                            + "allocation escapes through static field 'Sink.kept'",
                            "Sink.kept = k;"))),
            new Case("BlockedCallSummary", """
                    class Main {
                        public static void main(String[] args) {
                            Keeper k = new Keeper();
                            Holder h = new Holder(k);
                            free k;
                            free h;
                        }
                    }
                    """, "free k;",
                    "cannot free 'k': allocation escapes through constructor argument 1",
                    List.of(new Note("the final call summary permits this escape: allocation "
                            + "escapes through constructor argument 1; a callee source path is "
                            + "unavailable", "Holder h = new Holder(k);"))),
            new Case("AttachedField", """
                    class Owner {
                        private final byte[] data = new byte[4];
                        void f() {
                            byte[] d = data;
                            free d;
                        }
                    }
                    class Main {
                        public static void main(String[] args) {
                            Owner o = new Owner();
                            o.f();
                            free o;
                        }
                    }
                    """, "free d;",
                    "cannot free 'd': allocation is still reachable through private field 'data'",
                    List.of(new Note("private field 'data' is still attached to this object; its "
                            + "value was loaded here", "byte[] d = data;"))),
            new Case("ArraySlotAlias", """
                    class Main {
                        public static void main(String[] args) {
                            Keeper[] arr = new Keeper[1];
                            Keeper k = new Keeper();
                            arr[0] = k;
                            free k;
                            free arr;
                        }
                    }
                    """, "free k;",
                    "cannot free 'k': allocation is still reachable through known array element [0]",
                    List.of(new Note("array element [0] receives a reference to this allocation here",
                            "arr[0] = k;"))),
            new Case("LocalAlias", """
                    class Main {
                        public static void main(String[] args) {
                            Keeper k = new Keeper();
                            Keeper a = k;
                            free k;
                            Sink.use(a);
                        }
                    }
                    """, "free k;",
                    "cannot free 'k': allocation may still be observed through local 'a'",
                    List.of(new Note("local 'a' receives a reference to the same allocation here",
                            "Keeper a = k;"),
                            new Note("the ownership analysis still tracks 'a' as an observer at "
                                    + "this free", null))));

    static void renderedRejections() {
        for (Case item : CASES) {
            String text = COMMON + item.main();
            CompilationArtifact off = compile(item.name(), text, false);
            CompilationArtifact on = compile(item.name(), text, true);
            Diagnostic offError = onlyError(item, off);
            Diagnostic onError = onlyError(item, on);
            int line = lineOf(text, item.free());
            require(offError.span().start().line() == line
                            && onError.span().start().line() == line,
                    item.name() + " primary line changed: " + offError + " / " + onError);
            require(offError.notes().isEmpty(),
                    item.name() + " printed notes with explanations off: " + offError);
            List<DiagnosticNote> notes = onError.notes();
            require(notes.size() == item.notes().size(),
                    item.name() + " note count changed: " + notes);
            for (int index = 0; index < notes.size(); index++) {
                Note expected = item.notes().get(index);
                DiagnosticNote actual = notes.get(index);
                require(actual.message().equals(expected.text()),
                        item.name() + " note " + (index + 1) + " text changed: " + actual);
                if (expected.at() == null) {
                    require(actual.span() == null,
                            item.name() + " note " + (index + 1) + " gained a span: " + actual);
                } else {
                    require(actual.span() != null
                                    && actual.span().start().line() == lineOf(text, expected.at()),
                            item.name() + " note " + (index + 1) + " line changed: " + actual);
                }
            }
        }
    }

    private static Diagnostic onlyError(Case item, CompilationArtifact artifact) {
        List<Diagnostic> errors = artifact.diagnostics().stream().filter(Diagnostic::isError).toList();
        require(!artifact.valid() && errors.size() == 1
                        && errors.getFirst().message().equals(item.primary()),
                item.name() + " expected exactly <" + item.primary() + "> but was "
                        + artifact.diagnostics());
        return errors.getFirst();
    }

    private static CompilationArtifact compile(String name, String text, boolean explain) {
        return new CompilerPipeline(UnfreedMode.OFF, explain, null)
                .analyze(List.of(SourceFile.of(name + ".iron", text)));
    }

    /** Line of the fragment's last line, so a multi-line fragment selects its final statement. */
    private static int lineOf(String text, String fragment) {
        int offset = text.indexOf(fragment);
        require(offset >= 0, "missing source fragment " + fragment);
        int end = offset + fragment.length();
        return 1 + (int) text.substring(0, end).chars().filter(c -> c == '\n').count();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
