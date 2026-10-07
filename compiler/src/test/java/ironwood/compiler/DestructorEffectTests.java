// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.diagnostic.Diagnostic;
import ironwood.compiler.source.SourceFile;

import java.util.List;
import java.util.Map;

/**
 * A destructor is reported for allocating, letting an exception escape or publishing its
 * object when destroying an exact instance of its own class does (D283). Calls on the
 * object dispatch on that class, so an override that does it is reported at the
 * subclass whose destruction runs it, never at the superclass destructors it passes through.
 */
final class DestructorEffectTests {
    private static final Map<String, String> KINDS = Map.of(
            "destructor may allocate; destructor cleanup must be allocation-free", "allocates",
            "an exception may escape this destructor", "throws",
            "destructor may publish or resurrect 'this'", "publishes");

    static void attributedToDestroyedClass() {
        expect("publishing override", """
                class Registry { static Object saved; }
                class Base { destructor { cleanup(); } void cleanup() { } }
                class Quiet extends Base { }
                class Loud extends Base { @Override void cleanup() { Registry.saved = this; } }
                """, "publishes 4");
        expect("allocating override", """
                class Registry { static Object saved; }
                class Base { destructor { cleanup(); } void cleanup() { } }
                class Quiet extends Base { }
                class Loud extends Base { @Override void cleanup() { Registry.saved = new Object(); } }
                """, "allocates 4");
        expect("throwing override", """
                class Failure extends RuntimeException { }
                class Base { destructor { cleanup(); } void cleanup() { } }
                class Quiet extends Base { }
                class Loud extends Base { @Override void cleanup() { throw new Failure(); } }
                """, "allocates 4", "throws 4");
        // Only the subclass's call can throw, so only its destruction reaches the cleanup path.
        expect("throwing override under finally", """
                class Failure extends RuntimeException { }
                class Base { int count; destructor { try { cleanup(); } finally { count = 0; } } void cleanup() { } }
                class Quiet extends Base { }
                class Loud extends Base { @Override void cleanup() { throw new Failure(); } }
                """, "allocates 4", "throws 4");
        // Every destruction that runs the base destructor allocates.
        expect("base allocation", """
                class Registry { static Object saved; }
                class Base { destructor { Registry.saved = new Object(); } }
                class Quiet extends Base { }
                """, "allocates 2", "allocates 3");
        expect("overrides that stay inside the object", """
                class Base { int count; destructor { cleanup(); } void cleanup() { count = 1; } }
                class Sub extends Base { @Override void cleanup() { count = 2; } }
                """);
    }

    /** A receiver that may be another object keeps every override, so both stay reported. */
    static void uncertainReceiverKeepsEveryOverride() {
        expect("receiver that may be the object", """
                class Registry { static Object saved; }
                class Base {
                    Base peer;
                    destructor { Base other = peer == null ? this : peer; other.cleanup(); }
                    void cleanup() { }
                }
                class Loud extends Base { @Override void cleanup() { Registry.saved = this; } }
                """, "allocates 4", "allocates 7", "publishes 4", "publishes 7",
                "throws 4", "throws 7");
    }

    private static void expect(String name, String declarations, String... expected) {
        SourceFile source = SourceFile.of("Main.iron", declarations
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
