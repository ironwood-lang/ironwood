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

    static void poolExplanations() {
        String borrowed = "cannot free 'item': value is a borrowed helper owned by another object";
        poolFreeNote(POOL, borrowed, "pool 'pool' lends this checked-out object",
                "pool.get()");
        String alias = POOL.replace("free item;", "Object alias = item;\n        free alias;");
        poolFreeNote(alias, "cannot free 'alias': value is a borrowed helper owned by another object",
                "pool 'pool' lends this checked-out object", "pool.get()");
        String reassigned = POOL.replace("free item;",
                "ArrayObjectPool<Object> origin = pool;\n"
                        + "        pool = new ArrayObjectPool<Object>(1, 1, builder, 2.0f);\n"
                        + "        free item;");
        poolFreeNote(reassigned, borrowed, "pool 'origin' lends this checked-out object",
                "pool.get()");

        String helper = POOL.replace("    static void example() {",
                "    static void returnItem(ArrayObjectPool<Object> owner) {\n"
                        + "        Object item = owner.get();\n"
                        + "        owner.release(item);\n    }\n\n    static void example() {");
        acceptedBoth(helper.replace("Object item = pool.get();\n        free item;",
                "returnItem(pool);"));
        acceptedBoth(POOL.replace("free item;", "pool.release(item);"));
        acceptedBoth(POOL.replace("free item;", ""));

        String transferred = POOL.replace("Object item = pool.get();",
                "Object item = new Object();")
                .replace("free item;", "pool.release(item);\n        free item;");
        poolFreeNote(transferred, borrowed, "pool 'pool' was passed this object through release",
                "pool.release(item);", "does not promise pool cleanup");

        String wrong = POOL.replace("free item;",
                "ArrayObjectPool<Object> other = new ArrayObjectPool<Object>(1, 1, builder, 2.0f);\n"
                        + "        other.release(item);\n        free other;");
        releaseErrorNoteFree(wrong);

        String ambiguous = POOL.replace("static void example()", "static void example(boolean flag)")
                .replace("Object item = pool.get();\n        free item;",
                        "ArrayObjectPool<Object> other = new ArrayObjectPool<Object>(1, 1, builder, 2.0f);\n"
                        + "        Object item = flag ? pool.get() : other.get();\n"
                        + "        pool.release(item);\n        free other;");
        releaseErrorNoteFree(ambiguous);

        String helperWrong = POOL.replace("    static void example() {",
                "    static void returnWrong(ArrayObjectPool<Object> origin, "
                        + "ArrayObjectPool<Object> target) {\n"
                        + "        Object value = origin.get();\n"
                        + "        target.release(value);\n    }\n\n    static void example() {")
                .replace("Object item = pool.get();\n        free item;",
                        "ArrayObjectPool<Object> other = new ArrayObjectPool<Object>(1, 1, builder, 2.0f);\n"
                        + "        returnWrong(pool, other);\n        free other;");
        CompilationArtifact helperOff = analyze("PooledFree", helperWrong);
        CompilationArtifact helperOn = analyze("PooledFree", helperWrong, true);
        require(samePrimaries(helperOff, helperOn)
                        && helperOff.diagnostics().stream().allMatch(d -> d.notes().isEmpty())
                        && helperOn.diagnostics().stream().anyMatch(d -> d.message().startsWith(
                        "cannot free 'pool': allocation escapes through argument 1 of method 'returnWrong'"))
                        && !helperOn.valid() && helperOn.program().isEmpty()
                        && helperOn.llvmIr().isEmpty(),
                "wrong-pool helper was accepted or changed safety: " + helperOn.diagnostics());
    }

    private static void releaseErrorNoteFree(String text) {
        CompilationArtifact off = analyze("PooledFree", text);
        CompilationArtifact on = analyze("PooledFree", text, true);
        String message = "release must return a value checked out from this pool";
        require(samePrimaries(off, on) && off.diagnostics().stream()
                        .allMatch(d -> d.notes().isEmpty()),
                "wrong/unknown-pool primary changed: " + on.diagnostics());
        require(on.diagnostics().stream().anyMatch(d -> d.message().contains(message))
                        && on.diagnostics().stream().filter(d -> d.message().contains(message))
                        .allMatch(d -> d.notes().isEmpty()),
                "wrong/unknown-pool release gained notes or was accepted: " + on.diagnostics());
    }

    private static void poolFreeNote(String text, String primary, String note, String operation,
                                     String... additionalText) {
        CompilationArtifact off = analyze("PooledFree", text);
        CompilationArtifact on = analyze("PooledFree", text, true);
        require(samePrimaries(off, on)
                        && off.diagnostics().stream().allMatch(d -> d.notes().isEmpty())
                        && !on.valid() && on.program().isEmpty() && on.llvmIr().isEmpty(),
                "pool explanation changed disabled diagnostics");
        var error = on.diagnostics().stream().filter(d -> d.message().equals(primary))
                .findFirst().orElseThrow(() -> new AssertionError("missing pool free: " + on.diagnostics()));
        int operationOffset = text.indexOf(operation);
        int line = 1 + (int) text.substring(0, operationOffset).chars().filter(c -> c == '\n').count();
        require(operationOffset >= 0 && !error.notes().isEmpty()
                        && error.notes().getFirst().message().contains(note)
                        && java.util.Arrays.stream(additionalText).allMatch(
                        textPart -> error.notes().getFirst().message().contains(textPart))
                        && error.notes().getFirst().span().start().line() == line
                        && error.notes().getFirst().span().start().offset() >= operationOffset
                        && error.notes().getFirst().span().start().offset()
                        < operationOffset + operation.length(),
                "pool note site or owner changed: " + error);
    }

    private static void acceptedBoth(String text) {
        CompilationArtifact off = analyze("PooledFree", text);
        CompilationArtifact on = analyze("PooledFree", text, true);
        require(off.valid() && on.valid() && off.diagnostics().isEmpty()
                        && on.diagnostics().isEmpty() && off.llvmIr().equals(on.llvmIr()),
                "pool cleanup changed by explanation: " + on.diagnostics());
    }

    private static boolean samePrimaries(CompilationArtifact first, CompilationArtifact second) {
        if (first.diagnostics().size() != second.diagnostics().size()) return false;
        for (int index = 0; index < first.diagnostics().size(); index++) {
            var left = first.diagnostics().get(index);
            var right = second.diagnostics().get(index);
            if (!left.message().equals(right.message()) || !left.span().equals(right.span())
                    || left.severity() != right.severity()
                    || !left.source().path().equals(right.source().path())) return false;
        }
        return true;
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

    private static CompilationArtifact analyze(String name, String source, boolean explain) {
        return new CompilerPipeline(UnfreedMode.OFF, explain, null)
                .analyze(List.of(SourceFile.of(name + ".iron", source)));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
