// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.source.SourceFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Managed sharing across networking facades, delegates and retaining overrides. */
final class OwnedDelegationTests {
    private OwnedDelegationTests() {}

    static void observingAndRetaining() throws Exception {
        String source = Files.readString(Path.of("integration-tests/cases/owned_delegation/Main.iron"));
        CompilationArtifact observed = compile(source);
        require(observed.successful(), "observing delegation failed: " + observed.diagnostics());
        for (String operation : List.of(
                "InputStream view = socket.input(); ironwood.lang.Memory.collect();",
                "InputStream view = socket.input(); ironwood.lang.Memory.collect(); view.read();",
                "Main.saved = socket.input(); ironwood.lang.Memory.collect();")) {
            String unsafe = source.replace("class Main {", "class Main { static InputStream saved;")
                    .replace("socket.close();", operation + " socket.close();");
            accept(unsafe, "rooted facade view: " + operation);
        }
        // Retention through possible overrides is legal for managed references.
        String hostile = source.replace("class NativeImpl extends Impl {",
                        "class NativeImpl extends Impl { static byte[] saved;")
                .replace("return length;", "NativeImpl.saved = bytes; return length;");
        accept(hostile, "retained caller payload");
        String published = source.replace("class ObserverImpl extends Impl {",
                        "class ObserverImpl extends Impl { static InputStream saved;")
                .replace("return this.delegate.input();",
                        "ObserverImpl.saved = this.delegate.input(); return this.delegate.input();");
        accept(published, "published delegate child");
        accept("class Value { int value; }\nclass Helper {\n    private Value value;\n    Helper(Value value) { this.value = value; }\n    int read() { return this.value.value; }\n}\nclass Owner {\n    private Helper helper;\n    void install(Value value) { this.helper = new Helper(value); }\n    int read() { return this.helper.read(); }\n    \n}\nclass Main {\n    public static int main(String[] args) {\n        Owner owner = new Owner();\n        Value value = new Value();\n        owner.install(value);\n        ;\n        int result = owner.read();\n        ;\n        return result;\n    }\n}\n", "caller input retained by installed helper");
        CompilationArtifact primitiveSet = compile("import ironwood.ds.IntSet;\nclass Main {\n    public static int main(String[] args) {\n        IntSet values = new IntSet(2, 1.0f);\n        values.add(40); values.add(2);\n        ;\n        return 0;\n    }\n}\n");
        require(primitiveSet.successful(), "audited map effect through IntSet.add: " + primitiveSet.diagnostics());
        accept("import ironwood.ds.IntMap;\nclass RetainingMap extends IntMap<Object> {\n    static Object published;\n    RetainingMap() { super(2, 1.0f); }\n    @Override\n    public Object put(int key, Object value) { published = this; return null; }\n}\nclass Main {\n    public static int main(String[] args) {\n        IntMap<Object> values = new RetainingMap();\n        values.put(1, null);\n        ;\n        return 0;\n    }\n}\n", "retaining map override preserves legal managed sharing");
    }

    private static CompilationArtifact compile(String source) {
        return new CompilerPipeline().compile(SourceFile.of("test/Main.iron", source));
    }

    private static void accept(String source, String description) {
        CompilationArtifact artifact = compile(source);
        require(artifact.successful(), description + ": " + artifact.diagnostics());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
