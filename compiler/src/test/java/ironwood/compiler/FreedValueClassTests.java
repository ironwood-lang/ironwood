// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.diagnostic.Diagnostic;
import ironwood.compiler.source.SourceFile;

import java.util.List;

/**
 * A free or an owned-element destruction runs the destructors of the classes its value
 * may be, as closed-world value flow proves them, not of every subclass of its static
 * type (D284). A class whose destructor misbehaves is then reported only where its
 * objects can be released.
 */
final class FreedValueClassTests {
    private static final String LOUD_MAP = """
            import ironwood.ds.HashMap;
            import ironwood.ds.HashSet;

            class Registry { static Object saved; }
            class LoudMap<K, V> extends HashMap<K, V> {
                destructor { Registry.saved = new Object(); }
            }
            """;

    /** Values that cannot be the misbehaving class leave their holders unreported. */
    static void releasedValuesFollowTheirClasses() {
        exactly("user map beside bundled pools", LOUD_MAP + """
                class Main {
                    public static int main(String[] args) {
                        HashSet<String> set = new HashSet<String>();
                        free set;
                        return 0;
                    }
                }
                """, "allocates Main:6");
        // Without an entry point, private helpers still see every caller.
        exactly("user map in a library", LOUD_MAP + """
                class Use {
                    static void use() {
                        HashSet<String> set = new HashSet<String>();
                        free set;
                    }
                }
                """, "allocates Main:6");
        exactly("owned field of the base class", """
                class Registry { static Object saved; }
                class Part { }
                class LoudPart extends Part { destructor { Registry.saved = new Object(); } }
                class Holder { private Part part = new Part(); destructor { free part; } }
                class Main { public static int main(String[] args) { Holder h = new Holder(); free h; return 0; } }
                """, "allocates Main:3");
        exactly("misbehaving class held only in unrelated arrays", """
                import ironwood.pool.ArrayObjectPool;
                import ironwood.pool.ObjectBuilder;
                class Registry { static Object saved; }
                class Loud { destructor { Registry.saved = new Object(); } }
                class Quiet { }
                class QuietBuilder implements ObjectBuilder<Quiet> { @Override public Quiet newInstance() { return new Quiet(); } }
                class Main {
                    static Object[] seed() { return new Object[]{ new Loud() }; }
                    public static int main(String[] args) {
                        ArrayObjectPool<Quiet> pool = new ArrayObjectPool<Quiet>(1, new QuietBuilder());
                        Object[] copy = new Object[1];
                        System.arraycopy(seed(), 0, copy, 0, 1);
                        free pool;
                        return copy.length;
                    }
                }
                """, "allocates Main:4");
    }

    /** Every path by which the misbehaving class can be released keeps its holder reported. */
    static void reachableValuesStayReported() {
        exactly("owned field of the misbehaving class", """
                class Registry { static Object saved; }
                class Part { }
                class LoudPart extends Part { destructor { Registry.saved = new Object(); } }
                class Holder { private Part part = new LoudPart(); destructor { free part; } }
                class Main { public static int main(String[] args) { Holder h = new Holder(); free h; return 0; } }
                """, "allocates Main:3", "allocates Main:4");
        including("pooled misbehaving class", """
                import ironwood.pool.ArrayObjectPool;
                import ironwood.pool.ObjectBuilder;
                class Registry { static Object saved; }
                class Loud { destructor { Registry.saved = new Object(); } }
                class LoudBuilder implements ObjectBuilder<Loud> { @Override public Loud newInstance() { return new Loud(); } }
                class Main {
                    public static int main(String[] args) {
                        ArrayObjectPool<Loud> pool = new ArrayObjectPool<Loud>(1, new LoudBuilder());
                        free pool;
                        return 0;
                    }
                }
                """, "allocates Main:4", "allocates ArrayObjectPool");
        // A store into an array of unknown origin may reach any array its type allows.
        including("misbehaving class in an array of unknown origin", """
                import ironwood.ds.HashSet;
                class Registry { static Object[] slots = new Object[1]; static Object saved; }
                class Loud { destructor { Registry.saved = new Object(); } }
                class Main {
                    static void put(Object[] target, Object value) { target[0] = value; }
                    public static int main(String[] args) {
                        put(Registry.slots, new Loud());
                        HashSet<String> set = new HashSet<String>();
                        free set;
                        return 0;
                    }
                }
                """, "allocates Main:3", "allocates ArrayObjectPool");
        including("misbehaving class reaching a pool through array elements", """
                import ironwood.pool.ArrayObjectPool;
                import ironwood.pool.ObjectBuilder;
                class Registry { static Object saved; }
                class Loud { destructor { Registry.saved = new Object(); } }
                class StockBuilder implements ObjectBuilder<Object> {
                    private Object[] stock = new Object[]{ new Loud() };
                    @Override public Object newInstance() { return this.stock[0]; }
                }
                class Main {
                    public static int main(String[] args) {
                        ArrayObjectPool<Object> pool = new ArrayObjectPool<Object>(1, new StockBuilder());
                        free pool;
                        return 0;
                    }
                }
                """, "allocates Main:4", "allocates ArrayObjectPool");
    }

    private static void exactly(String name, String program, String... expected) {
        List<String> reported = destructorDiagnostics(program);
        List<String> wanted = java.util.Arrays.stream(expected).sorted().toList();
        require(reported.equals(wanted), name + ": expected " + wanted + " but got " + reported);
    }

    private static void including(String name, String program, String... expected) {
        List<String> reported = destructorDiagnostics(program);
        require(reported.containsAll(List.of(expected)),
                name + ": expected at least " + List.of(expected) + " but got " + reported);
    }

    /** Destructor allocation errors as "allocates File:line" for the program, by file elsewhere. */
    private static List<String> destructorDiagnostics(String program) {
        SourceFile source = SourceFile.of("Main.iron", program);
        List<Diagnostic> diagnostics = new CompilerPipeline(UnfreedMode.OFF)
                .analyze(List.of(source)).diagnostics();
        return diagnostics.stream()
                .filter(d -> d.message().equals(
                        "destructor may allocate; destructor cleanup must be allocation-free"))
                .map(d -> {
                    String file = d.source().path().getFileName().toString().replace(".iron", "");
                    return "allocates " + (d.source().path().equals(source.path())
                            ? file + ":" + d.span().start().line() : file);
                })
                .sorted().toList();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
