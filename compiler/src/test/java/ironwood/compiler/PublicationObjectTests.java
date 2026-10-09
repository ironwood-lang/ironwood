// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.diagnostic.Diagnostic;
import ironwood.compiler.source.SourceFile;

import java.util.List;
import java.util.Map;

/**
 * Publication of an object under construction or destruction is judged for each object
 * the constructor or destructor runs on, with the objects that object holds or receives
 * (D286). A storing implementation that is never passed in implicates no one; one that
 * is passed in keeps its report, as do constructors that run on no object.
 */
final class PublicationObjectTests {
    private static final Map<String, String> KINDS = Map.of(
            "constructor may publish in-progress 'this' before construction completes", "constructs",
            "destructor may publish or resurrect 'this'", "publishes",
            "destructor may allocate; destructor cleanup must be allocation-free", "allocates",
            "an exception may escape this destructor", "throws");

    private static final String SINKS = """
            class Registry { static Object saved; }
            interface Sink { void accept(Object value); }
            class QuietSink implements Sink { @Override public void accept(Object value) { } }
            class StoringSink implements Sink { @Override public void accept(Object value) { Registry.saved = value; } }
            class Wrapper {
                Wrapper(Sink sink) { sink.accept(this); }
            }
            """;

    private static final String LISTENERS = """
            class Registry { static Object saved; }
            interface Listener { void closed(Object value); }
            class QuietListener implements Listener { @Override public void closed(Object value) { } }
            class StoringListener implements Listener { @Override public void closed(Object value) { Registry.saved = value; } }
            class Resource {
                private final Listener listener;
                Resource(Listener listener) { this.listener = listener; }
                destructor { if (listener != null) { listener.closed(this); } }
            }
            """;

    static void argumentObjectsDecidePublication() {
        exactly("constructor given only quiet sinks", SINKS + """
                class Main {
                    public static int main(String[] args) {
                        Wrapper wrapper = new Wrapper(new QuietSink());
                        StoringSink unused = new StoringSink();
                        return 0;
                    }
                }
                """);
        exactly("constructor given a storing sink", SINKS + """
                class Main {
                    public static int main(String[] args) {
                        Wrapper quiet = new Wrapper(new QuietSink());
                        Wrapper loud = new Wrapper(new StoringSink());
                        return 0;
                    }
                }
                """, "constructs 6");
        // The listener test guards the call, so it needs no null check (D287).
        exactly("destructor holding only quiet listeners", LISTENERS + """
                class Main {
                    public static int main(String[] args) {
                        Resource resource = new Resource(new QuietListener());
                        free resource;
                        StoringListener unused = new StoringListener();
                        return 0;
                    }
                }
                """);
        exactly("destructor holding a storing listener", LISTENERS + """
                class Main {
                    public static int main(String[] args) {
                        Resource resource = new Resource(new StoringListener());
                        free resource;
                        return 0;
                    }
                }
                """, "publishes 8");
    }

    /** A constructor that builds no object keeps the verdict for any exact instance (D282). */
    static void constructorsWithoutObjectsKeepTheirVerdict() {
        exactly("unused publishing constructor", """
                class Registry { static Object saved; }
                class Gadget {
                    Gadget() { }
                    Gadget(int flag) { Registry.saved = this; }
                }
                class Main { public static int main(String[] args) { Gadget gadget = new Gadget(); return 0; } }
                """, "constructs 4");
        exactly("base constructor run only by subclasses", """
                class Registry { static Object saved; }
                class Base { Base() { hook(); } void hook() { } }
                class Quiet extends Base { }
                class Loud extends Base { @Override void hook() { Registry.saved = this; } }
                class Main { public static int main(String[] args) { Base b = new Quiet(); Base l = new Loud(); return 0; } }
                """, "constructs 4");
    }

    private static void exactly(String name, String program, String... expected) {
        SourceFile source = SourceFile.of("Main.iron", program);
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
