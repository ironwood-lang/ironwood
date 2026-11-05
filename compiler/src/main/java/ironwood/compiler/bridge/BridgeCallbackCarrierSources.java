// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import ironwood.compiler.CompilationArtifact;
import ironwood.compiler.ir.IrFunction;
import ironwood.compiler.ir.IrType;
import ironwood.compiler.source.SourceFile;

import java.util.List;

/** Compiler-owned native throwable representation. Does not grant export or cleanup admission. */
public final class BridgeCallbackCarrierSources {
    private static final String NAME = "ironwood.bridge.internal._ForeignFailure";
    private final SourceFile source;

    private BridgeCallbackCarrierSources() {
        source = SourceFile.of("<java-bridge>/ironwood/bridge/internal/_ForeignFailure.iron", SOURCE);
    }

    public SourceFile source() { return source; }
    public IrType type() { return IrType.reference(NAME); }

    public record Bound(IrFunction factory, IrFunction reference, IrFunction next, IrFunction exhausted,
                        IrFunction unchangedReference, IrFunction cause, IrFunction secondaryCount, IrFunction secondary,
                        IrFunction takeReference) {}

    public static BridgeCallbackCarrierSources discover(CompilationArtifact original) {
        if (!original.valid() || original.bridgeApiFacts().isEmpty()
                || !original.bridgeApiFacts().orElseThrow().matches(original.program().orElseThrow())) {
            throw new IllegalArgumentException("callback carrier requires matching semantic API facts");
        }
        if (original.bridgeApiFacts().orElseThrow().types().containsKey(NAME)) {
            throw new IllegalArgumentException("reserved callback carrier type collision: " + NAME);
        }
        return new BridgeCallbackCarrierSources();
    }

    public Bound bind(CompilationArtifact analyzed) {
        if (!analyzed.valid() || analyzed.bridgeApiFacts().isEmpty()
                || !analyzed.bridgeApiFacts().orElseThrow().matches(analyzed.program().orElseThrow())) {
            throw new IllegalArgumentException("callback carrier requires matching analyzed source");
        }
        var declaration = analyzed.bridgeApiFacts().orElseThrow().types().get(NAME);
        if (declaration == null || !declaration.source().path().equals(source.path())
                || !declaration.source().content().equals(source.content())) {
            throw new IllegalArgumentException("callback carrier requires its exact compiler-owned declaration");
        }
        return new Bound(method(analyzed, "create", List.of(IrType.I64, type()), type()),
                method(analyzed, "reference", List.of(IrType.reference("ironwood.lang.Throwable")), IrType.I64),
                method(analyzed, "next", List.of(type()), type()),
                method(analyzed, "exhausted", List.of(), IrType.VOID),
                method(analyzed, "unchangedReference", List.of(IrType.reference("ironwood.lang.Throwable")), IrType.I64),
                method(analyzed, "cause", List.of(type()), IrType.reference("ironwood.lang.Throwable")),
                method(analyzed, "secondaryCount", List.of(type()), IrType.I32),
                method(analyzed, "secondary", List.of(type(), IrType.I32), IrType.reference("ironwood.lang.Throwable")),
                method(analyzed, "takeReference", List.of(type()), IrType.I64));
    }

    private static IrFunction method(CompilationArtifact analyzed, String name, List<IrType> parameters, IrType result) {
        return analyzed.program().orElseThrow().functions().stream().filter(function -> function.ownerClass().equals(NAME)
                && function.sourceName().equals(name) && function.returnType().equals(result)
                && function.parameters().stream().map(parameter -> parameter.value().type()).toList().equals(parameters))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("missing callback carrier operation: " + name));
    }

    private static final String SOURCE = """
            // SPDX-License-Identifier: MIT OR Apache-2.0
            package ironwood.bridge.internal;

            // Internal transport only. Native handlers see an unchecked foreign
            // failure; unchanged carriers rethrow the original Java throwable.
            final class _ForeignFailure extends RuntimeException {

                // JNI ownership ends at translation completion. The exact managed
                // chain can remain reachable after its JNI references are released.
                private long javaReference;

                private final _ForeignFailure nextCarrier;

                private _ForeignFailure(long reference, _ForeignFailure next) {

                    this.javaReference = reference;
                    this.nextCarrier = next;
                }

                static _ForeignFailure create(long reference, _ForeignFailure next) {

                    return new _ForeignFailure(reference, next);
                }

                static long reference(Throwable failure) {

                    if (failure instanceof _ForeignFailure) {
                        return ((_ForeignFailure) failure).javaReference;
                    }
                    return 0L;
                }

                static _ForeignFailure next(_ForeignFailure failure) {

                    return failure.nextCarrier;
                }

                // Native completion deletes the returned JNI global. Clearing the
                // cookie first makes repeated completion release ownership once.
                static long takeReference(_ForeignFailure failure) {

                    long reference = failure.javaReference;
                    failure.javaReference = 0L;
                    return reference;
                }

                static long unchangedReference(Throwable failure) {

                    if (failure instanceof _ForeignFailure) {
                        _ForeignFailure carrier = (_ForeignFailure) failure;
                        if (carrier.getCause() == null && carrier.getSecondaryExceptionCount() == 0) {
                            return carrier.javaReference;
                        }
                    }
                    return 0L;
                }

                static Throwable cause(_ForeignFailure failure) {

                    return failure.getCause();
                }

                static int secondaryCount(_ForeignFailure failure) {

                    return failure.getSecondaryExceptionCount();
                }

                static Throwable secondary(_ForeignFailure failure, int index) {

                    return failure.getSecondaryException(index);
                }

                // Used after JNI cannot retain a callback throwable. Allocation
                // exhaustion itself uses the existing native emergency object.
                static void exhausted() {

                    throw new OutOfMemoryError();
                }
            }
            """;
}
