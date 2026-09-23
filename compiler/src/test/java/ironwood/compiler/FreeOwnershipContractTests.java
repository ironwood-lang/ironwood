// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.source.SourceFile;

import java.util.List;

final class FreeOwnershipContractTests {
    private static final String ITERATOR = """
            import ironwood.ds.ArrayList;
            import ironwood.util.Iterator;

            class IteratorFree {

                static void example() {

                    ArrayList<Object> list = new ArrayList<>();
                    Iterator<Object> it = list.iterator();
                    free it;
                    free list;
                }
            }
            """;
    private static final String POOL = """
            import ironwood.pool.ArrayObjectPool;
            import ironwood.pool.ObjectBuilder;

            class Builder implements ObjectBuilder<Object> {

                @Override
                public Object newInstance() {

                    return new Object();
                }
            }

            class PooledFree {

                static void example() {

                    Builder builder = new Builder();
                    ArrayObjectPool<Object> pool = new ArrayObjectPool<Object>(1, 1, builder, 2.0f);
                    Object item = pool.get();
                    free item;
                    free pool;
                    free builder;
                }
            }
            """;
    private static final String SETTER = """
            class Holder {

                private Object value;

                void set(Object value) {

                    this.value = value;
                }
            }

            class SetterFree {

                static void example() {

                    Holder holder = new Holder();
                    Object value = new Object();
                    holder.set(value);
                    free value;
                    free holder;
                }
            }
            """;
    private static final String DISPATCH = """
            class Sink {

                void accept(byte[] value) {
                }
            }

            class Quiet extends Sink {

                @Override
                void accept(byte[] value) {
                }
            }

            class Keeper extends Sink {

                static byte[] kept;

                @Override
                void accept(byte[] value) {

                    kept = value;
                }
            }

            class Stash extends Sink {

                static byte[] stashed;

                @Override
                void accept(byte[] value) {

                    stashed = value;
                }
            }

            class Main {

                static void use(Sink sink) {

                    byte[] data = new byte[16];
                    sink.accept(data);
                    free data;
                }

                public static void main(String[] args) {

                    use(new Quiet());
                    use(new Keeper());
                }
            }
            """;

    private FreeOwnershipContractTests() {}

    static void ownersAndDispatch() {
        String borrowed = ": value is a borrowed helper owned by another object";
        rejected("IteratorFree", ITERATOR, "it", "cannot free 'it'" + borrowed);
        accepted("IteratorFree", ITERATOR.replace("free it;", ""));
        rejected("PooledFree", POOL, "item", "cannot free 'item'" + borrowed);
        accepted("PooledFree", POOL.replace("free item;", "pool.release(item);"));
        // Pool destruction owns teardown even while the object is checked out.
        accepted("PooledFree", POOL.replace("free item;", ""));

        String wrapper = "cannot free 'value': allocation is still borrowed by a live wrapper";
        rejected("SetterFree", SETTER, "value", wrapper);
        accepted("SetterFree", SETTER.replace("free value;\n        free holder;",
                "free holder;\n        free value;"));
        String closed = SETTER.replace("    void set(Object value)", "    void close() {}\n\n    void set(Object value)")
                .replace("free value;", "holder.close();\n        free value;");
        rejected("SetterFree", closed, "value", wrapper);
        // Ending one borrow does not discharge a second retaining receiver.
        String twoOwners = SETTER.replace("free value;\n        free holder;",
                "Holder other = new Holder();\n        other.set(value);\n        free holder;\n"
                        + "        free value;\n        free other;");
        rejected("SetterFree", twoOwners, "value", wrapper);

        String call = "cannot free 'data': allocation escapes through argument 1 of method 'accept'";
        rejected("Main", DISPATCH, "data", call);
        accepted("Main", DISPATCH.replace("use(new Keeper());", ""));
        // Stash remains retaining but does not flow to use in this executable.
        accepted("Main", DISPATCH.replace("kept = value;", ""));
        int entry = DISPATCH.indexOf("    public static void main");
        require(entry >= 0, "missing main fixture");
        String library = DISPATCH.substring(0, entry) + "}\n";
        rejected("Main", library, "data", call);
        rejected("Main", library.replace("kept = value;", ""), "data", call);
        accepted("Main", library.replace("kept = value;", "").replace("stashed = value;", ""));
    }

    private static void rejected(String name, String source, String local, String message) {
        CompilationArtifact artifact = analyze(name, source);
        require(!artifact.valid() && artifact.program().isEmpty() && artifact.llvmIr().isEmpty(),
                name + " invalid source produced a program");
        var errors = artifact.diagnostics().stream().filter(d -> d.isError()).toList();
        require(errors.size() == 1, name + " unexpected diagnostics: " + artifact.diagnostics());
        var error = errors.getFirst();
        int start = source.indexOf("free " + local + ";") + "free ".length();
        require(error.message().equals(message), name + " wrong primary: " + error);
        require(error.source().path().toString().equals(name + ".iron")
                        && error.span().start().offset() == start
                        && error.span().end().offset() == start + local.length(),
                name + " primary moved: " + error);
    }

    private static void accepted(String name, String source) {
        CompilationArtifact artifact = analyze(name, source);
        require(artifact.valid() && artifact.diagnostics().isEmpty(),
                name + " safe control rejected: " + artifact.diagnostics());
    }

    private static CompilationArtifact analyze(String name, String source) {
        return new CompilerPipeline(UnfreedMode.OFF).analyze(List.of(SourceFile.of(name + ".iron", source)));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
