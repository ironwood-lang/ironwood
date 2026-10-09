// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.diagnostic.Diagnostic;
import ironwood.compiler.source.SourceFile;

import java.util.List;

/**
 * A constructor is reported for publishing its in-progress object when building an exact
 * instance of its own class does (D282). Calls on the object dispatch on that class, so an
 * override that publishes is reported at the subclass whose construction runs it, never at
 * the superclass constructors it passes through.
 */
final class ConstructionPublicationTests {
    private static final String MESSAGE =
            "constructor may publish in-progress 'this' before construction completes";

    static void attributedToConstructedClass() {
        expect("subclass override", """
                class Registry { static Base saved; }
                class Base { Base() { hook(); } void hook() { } }
                class Quiet extends Base { }
                class Loud extends Base { @Override void hook() { Registry.saved = this; } }
                """, 4);
        expect("abstract hook", """
                class Registry { static Base saved; }
                abstract class Base { Base() { hook(); } abstract void hook(); }
                class Impl extends Base { @Override void hook() { Registry.saved = this; } }
                class Calm extends Base { @Override void hook() { } }
                """, 3);
        expect("delegating constructor", """
                class Registry { static Base saved; }
                class Base { Base() { this(1); } Base(int n) { hook(); } void hook() { } }
                class Loud extends Base { Loud() { super(2); } @Override void hook() { Registry.saved = this; } }
                """, 3);
        expect("interface default method", """
                class Registry { static Object saved; }
                interface Hooked { default void hook() { } }
                class Base implements Hooked { Base() { hook(); } }
                class Loud extends Base { @Override public void hook() { Registry.saved = this; } }
                """, 4);
        expect("field round trip in an override", """
                class Registry { static Base saved; }
                class Base {
                    private Base self;
                    Base() { self = this; leak(); }
                    void leak() { }
                    Base self() { return self; }
                }
                class Plain extends Base { }
                class Leaky extends Base { @Override void leak() { Registry.saved = self(); } }
                """, 9);
        // Every construction that runs the base constructor publishes.
        expect("base publication", """
                class Registry { static Base saved; }
                class Base { Base() { Registry.saved = this; } }
                class Sub extends Base { }
                """, 2, 3);
        expect("field round trip in the base", """
                class Registry { static Base saved; }
                class Base {
                    private Base self;
                    Base() { self = this; leak(); }
                    void leak() { Registry.saved = self; }
                }
                class Plain extends Base { }
                """, 4, 7);
        // A receiver that may be another object keeps every override.
        expect("receiver that may be the object", """
                class Registry { static Base saved; }
                class Base {
                    Base(Base peer, boolean flag) { Base other = flag ? this : peer; other.hook(); }
                    void hook() { }
                }
                class Loud extends Base { Loud() { super(null, false); } @Override void hook() { Registry.saved = this; } }
                """, 3, 6);
        expect("overrides that keep the object", """
                class Base { int count; Base() { hook(); } void hook() { count++; } }
                class Sub extends Base { @Override void hook() { count += 2; } }
                """);
    }

    /** A user override of a hook that bundled constructors call is reported once, at the user's class. */
    static void bundledConstructorsKeepUserCause() {
        expect("bundled exception hook", """
                class Publishing extends RuntimeException {
                    static Throwable saved;
                    @Override public Throwable fillInStackTrace() { saved = this; return this; }
                }
                """, 1);
    }

    private static void expect(String name, String declarations, int... lines) {
        SourceFile source = SourceFile.of("Main.iron", declarations
                + "class Main { public static int main(String[] args) { return 0; } }\n");
        List<Diagnostic> diagnostics = new CompilerPipeline(UnfreedMode.OFF)
                .analyze(List.of(source)).diagnostics();
        List<String> reported = diagnostics.stream()
                .map(d -> d.message().equals(MESSAGE) && d.source() != null
                        && d.source().path().equals(source.path())
                        ? "line " + d.span().start().line()
                        : d.message() + " @ " + (d.source() == null ? "program" : d.source().path()))
                .sorted().toList();
        List<String> expected = java.util.Arrays.stream(lines).mapToObj(line -> "line " + line)
                .sorted().toList();
        require(reported.equals(expected), name + ": expected " + expected + " but got " + reported);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
