// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.source.SourceFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Source proofs for the networking facade/delegate ownership graph. */
final class OwnedDelegationTests {
    private OwnedDelegationTests() {}

    static void observingAndRetaining() throws Exception {
        String source = Files.readString(Path.of("integration-tests/cases/owned_delegation/Main.iron"));
        CompilationArtifact observed = compile(source);
        require(observed.successful(), "observing delegation failed: " + observed.diagnostics());
        for (String operation : List.of(
                "InputStream view = socket.input(); free view;",
                "InputStream view = socket.input(); free socket; view.read();",
                "Main.saved = socket.input(); free socket;")) {
            String unsafe = source.replace("class Main {", "class Main { static InputStream saved;")
                    .replace("socket.close();", operation);
            reject(unsafe, "borrow misuse: " + operation);
        }
        // A possible override must contribute its retaining effect, even when
        // the declared base method and another concrete target only observe.
        String hostile = source.replace("class NativeImpl extends Impl {",
                        "class NativeImpl extends Impl { static byte[] saved;")
                .replace("return length;", "NativeImpl.saved = bytes; return length;");
        reject(hostile, "retained caller payload");
        String published = source.replace("class ObserverImpl extends Impl {",
                        "class ObserverImpl extends Impl { static InputStream saved;")
                .replace("return this.delegate.input();",
                        "ObserverImpl.saved = this.delegate.input(); return this.delegate.input();");
        reject(published, "published delegate child");
        reject("""
                class Value { int value; }
                class Helper {
                    private Value value;
                    Helper(Value value) { this.value = value; }
                    int read() { return this.value.value; }
                }
                class Owner {
                    private Helper helper;
                    void install(Value value) { this.helper = new Helper(value); }
                    int read() { return this.helper.read(); }
                    destructor { free this.helper; }
                }
                class Main {
                    public static int main(String[] args) {
                        Owner owner = new Owner();
                        Value value = new Value();
                        owner.install(value);
                        free value;
                        int result = owner.read();
                        free owner;
                        return result;
                    }
                }
                """, "caller input retained by installed helper");
        CompilationArtifact primitiveSet = compile("""
                import ironwood.ds.IntSet;
                class Main {
                    public static int main(String[] args) {
                        IntSet values = new IntSet(2, 1.0f);
                        values.add(40); values.add(2);
                        free values;
                        return 0;
                    }
                }
                """);
        require(primitiveSet.successful(), "audited map effect through IntSet.add: " + primitiveSet.diagnostics());
        reject("""
                import ironwood.ds.IntMap;
                class RetainingMap extends IntMap<Object> {
                    static Object published;
                    RetainingMap() { super(2, 1.0f); }
                    @Override
                    public Object put(int key, Object value) { published = this; return null; }
                }
                class Main {
                    public static int main(String[] args) {
                        IntMap<Object> values = new RetainingMap();
                        values.put(1, null);
                        free values;
                        return 0;
                    }
                }
                """, "actual retaining map override is not covered by the audited base contract");
    }

    private static CompilationArtifact compile(String source) {
        return new CompilerPipeline(UnfreedMode.ERROR).compile(SourceFile.of("test/Main.iron", source));
    }

    private static void reject(String source, String description) {
        CompilationArtifact artifact = compile(source);
        require(!artifact.successful() && artifact.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.isError() && (diagnostic.message().contains("free")
                        || diagnostic.message().contains("freed"))),
                description + " was accepted or failed without a reclamation diagnostic: "
                        + artifact.diagnostics());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
