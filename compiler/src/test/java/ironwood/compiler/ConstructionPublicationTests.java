// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.source.SourceFile;

import java.util.List;

/** Constructor publication through real dispatch is legal for managed graphs. */
final class ConstructionPublicationTests {
    static void attributedToConstructedClass() {
        expect("subclass override", """
                class Registry { static Base saved; }
                class Base { Base() { hook(); } void hook() { } }
                class Quiet extends Base { }
                class Loud extends Base { @Override void hook() { Registry.saved = this; } }
                """);
        expect("abstract hook", """
                class Registry { static Base saved; }
                abstract class Base { Base() { hook(); } abstract void hook(); }
                class Impl extends Base { @Override void hook() { Registry.saved = this; } }
                class Calm extends Base { @Override void hook() { } }
                """);
        expect("delegating constructor", """
                class Registry { static Base saved; }
                class Base { Base() { this(1); } Base(int n) { hook(); } void hook() { } }
                class Loud extends Base { Loud() { super(2); } @Override void hook() { Registry.saved = this; } }
                """);
        expect("interface default method", """
                class Registry { static Object saved; }
                interface Hooked { default void hook() { } }
                class Base implements Hooked { Base() { hook(); } }
                class Loud extends Base { @Override public void hook() { Registry.saved = this; } }
                """);
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
                """);
        // Every construction that runs the base constructor publishes.
        expect("base publication", """
                class Registry { static Base saved; }
                class Base { Base() { Registry.saved = this; } }
                class Sub extends Base { }
                """);
        expect("field round trip in the base", """
                class Registry { static Base saved; }
                class Base {
                    private Base self;
                    Base() { self = this; leak(); }
                    void leak() { Registry.saved = self; }
                }
                class Plain extends Base { }
                """);
        // A receiver that may be another object keeps every override.
        expect("receiver that may be the object", """
                class Registry { static Base saved; }
                class Base {
                    Base(Base peer, boolean flag) { Base other = flag ? this : peer; other.hook(); }
                    void hook() { }
                }
                class Loud extends Base { Loud() { super(null, false); } @Override void hook() { Registry.saved = this; } }
                """);
        expect("overrides that keep the object", """
                class Base { int count; Base() { hook(); } void hook() { count++; } }
                class Sub extends Base { @Override void hook() { count += 2; } }
                """);
    }

    /** A bundled constructor may dispatch to a publishing user hook. */
    static void bundledConstructorsKeepUserCause() {
        expect("bundled exception hook", """
                class Publishing extends RuntimeException {
                    static Throwable saved;
                    @Override public Throwable fillInStackTrace() { saved = this; return this; }
                }
                """);
    }

    private static void expect(String name, String declarations) {
        SourceFile source = SourceFile.of("Main.iron", declarations
                + "class Main { public static int main(String[] args) { return 0; } }\n");
        var artifact = new CompilerPipeline().analyze(List.of(source));
        require(artifact.valid(), name + ": " + artifact.diagnostics());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
