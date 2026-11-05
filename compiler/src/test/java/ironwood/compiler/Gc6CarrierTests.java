// SPDX-License-Identifier: MIT OR Apache-2.0
package ironwood.compiler;

import ironwood.compiler.bridge.*;
import ironwood.compiler.ir.*;
import ironwood.compiler.source.SourceFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** GC6 carrier migration, without granting foreign segment or library admission. */
public final class Gc6CarrierTests {
    static final String NAME = "GC6 carriers separate exact managed linkage from JNI completion";
    private Gc6CarrierTests() {}

    static void proofs() throws Exception {
        generate(null);
    }

    private static void generate(Path output) throws Exception {
        var pipeline = new CompilerPipeline(UnfreedMode.ERROR);
        var input = SourceFile.of("CarrierProbe.iron", PROBE);
        var original = pipeline.analyzeForBridge(List.of(SourceFile.of("Discover.iron", "final class Discover {}")));
        check(original.valid(), original.diagnostics().toString());
        var source = BridgeCallbackCarrierSources.discover(original);
        var sources = List.of(input, source.source());
        var artifact = pipeline.analyzeForBridge(sources);
        check(artifact.valid(), artifact.diagnostics().toString());
        var bound = source.bind(artifact);
        var program = artifact.program().orElseThrow();
        var declaration = program.classes().stream().filter(c -> c.name().equals(source.type().referenceName())).findFirst().orElseThrow();
        var layout = IrAllocationLayout.of(declaration);
        layout.requireSupported();
        check(layout.references().stream().anyMatch(f -> f.name().equals("nextCarrier") && f.type().equals(source.type())),
                "carrier chain is absent from its exact map");
        check(layout.references().stream().noneMatch(f -> f.name().equals("javaReference")), "JNI cookie became a managed root");
        var fields = declaration.fields().stream().map(f -> f.name().equals("nextCarrier")
                ? new IrField(f.ownerClass(), f.name(), IrType.I64, f.layoutIndex(), f.sourceSpan()) : f).toList();
        var stale = new IrClass(declaration.name(), declaration.kind(), declaration.superclass(), declaration.interfaces(),
                fields, declaration.typeId(), declaration.typeMembership(), declaration.dispatchEntries(), declaration.sourceSpan());
        check(IrAllocationLayout.of(stale).shape() == IrAllocationLayout.Shape.REFUSED, "integer chain accepted as exact");
        check(bound.factory().parameters().get(1).value().type().equals(source.type())
                && bound.next().returnType().equals(source.type()), "carrier ABI erased its exact link");
        check(bound.takeReference().blocks().stream().flatMap(b -> GcEffects.operations(b).stream())
                .anyMatch(i -> i instanceof IrFieldStoreInstruction store && store.field().name().equals("javaReference")),
                "JNI completion does not clear ownership");
        var roots = BridgeRootSet.resolve(program, List.of(BridgeCallableId.of(program.functions().stream()
                .filter(f -> f.ownerClass().equals("ironwood.bridge.internal.CarrierProbe") && f.sourceName().equals("leaf"))
                .findFirst().orElseThrow())));
        var cleanup = BridgeCallbackCarrierCleanup.prove(artifact, source, roots);
        var entries = BridgeCallbackCarrierEntries.create(artifact, source);
        check(entries.functions().size() == 9 && entries.takeReference().returnType().equals(IrType.I32), "missing protected JNI completion entry");
        String nativeCleanup = BridgeCallbackCarrierNativeSources.cleanup(artifact, entries, roots, cleanup);
        check(nativeCleanup.contains("next.value.reference") && nativeCleanup.contains(entries.takeReference().linkageName())
                && !nativeCleanup.contains("destroy") && !nativeCleanup.contains("free("), "completion still reclaims managed memory");
        for (var function : entries.program().functions()) for (var block : function.blocks()) {
            check(GcEffects.operations(block).stream().noneMatch(i -> i instanceof IrFreeInstruction
                    || i instanceof IrRawDeallocateInstruction || i instanceof IrRollbackInstruction), "carrier operation produces legacy cleanup");
        }
        // User source reconstructs from class/archive inputs. Private support is
        // regenerated at its compiler-owned path rather than trusting a caller's
        // copy of a reserved internal declaration.
        Path directory = output == null ? Files.createTempDirectory(Path.of("workspace"), "gc6-carrier-source-") : output;
        Files.createDirectories(directory);
        String probeType = "ironwood.bridge.internal.CarrierProbe";
        Path loose = directory.resolve("CarrierProbe.ironclass"), archive = directory.resolve("carrier.ironjar");
        IronClass.write(loose, SourceParser.parse(input).unit().orElseThrow(), probeType);
        IronJar.create(archive, List.of(loose));
        for (var restored : List.of(IronClass.read(loose).source(probeType).orElseThrow(),
                IronJar.read(archive).source(probeType).orElseThrow())) {
            var reconstructed = pipeline.analyzeForBridge(List.of(restored, source.source()));
            check(reconstructed.valid(), reconstructed.diagnostics().toString());
            source.bind(reconstructed);
        }
        if (output != null) {
            Files.writeString(output.resolve("_ForeignFailure.iron"), source.source().content());
            Files.writeString(output.resolve("CarrierProbe.iron"), PROBE);
            Files.writeString(output.resolve("completion.inc"), nativeCleanup);
            Files.writeString(output.resolve("operations.h"), "// SPDX-License-Identifier: MIT OR Apache-2.0\n"
                    + "#define IW_CARRIER_NEXT " + entries.next().linkageName() + "\n"
                    + "#define IW_CARRIER_TAKE " + entries.takeReference().linkageName() + "\n");
        }
    }

    public static void main(String[] arguments) throws Exception {
        generate(Path.of(arguments[0]));
    }

    private static final String PROBE = """
            // SPDX-License-Identifier: MIT OR Apache-2.0
            package ironwood.bridge.internal;
            import ironwood.lang.Memory;

            // The compiler-owned carrier chain survives exact collection. JNI
            // cookies here are test numbers; the separate JVM fixture owns globals.
            final class CarrierProbe {

                static _ForeignFailure kept;

                static void leaf() {

                }

                static void require(boolean value) {

                    if (!value) throw new IllegalStateException("GC6 carrier invariant");
                }

                static void create() {

                    _ForeignFailure tail = _ForeignFailure.create(101L, null);
                    kept = _ForeignFailure.create(202L, tail);
                }

                static void inspect() {

                    _ForeignFailure tail = _ForeignFailure.next(kept);
                    require(_ForeignFailure.reference(kept) == 202L);
                    require(_ForeignFailure.reference(tail) == 101L);
                    long count = System.allocationCount();
                    long live = System.liveAllocationCount();
                    require(_ForeignFailure.takeReference(kept) == 202L);
                    require(_ForeignFailure.takeReference(kept) == 0L);
                    require(_ForeignFailure.reference(tail) == 101L);
                    require(_ForeignFailure.takeReference(tail) == 101L);
                    require(System.allocationCount() == count);
                    require(System.liveAllocationCount() == live);
                }

                public static void main(String[] args) {

                    create();
                    kept = null;
                    Memory.collect();
                    long baseline = System.liveAllocationCount();
                    create();
                    Memory.collect();
                    require(System.liveAllocationCount() == baseline + 2L);
                    inspect();
                    Memory.collect();
                    require(System.liveAllocationCount() == baseline + 2L);
                    require(_ForeignFailure.reference(kept) == 0L);
                    require(_ForeignFailure.reference(_ForeignFailure.next(kept)) == 0L);
                    kept = null;
                    Memory.collect();
                    require(System.liveAllocationCount() == baseline);
                    System.out.println("GC6 carrier graph passed");
                }
            }
            """;

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
