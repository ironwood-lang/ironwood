// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.bridge.*;
import ironwood.compiler.ast.DeclaredTypes;
import ironwood.compiler.ir.IrType;
import ironwood.compiler.source.SourceFile;

import java.util.List;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;

final class BridgeCustomSnapshotLayoutTests {
    static final String NAME = "Java Bridge custom snapshot layouts preserve inherited copied getter identities";
    private BridgeCustomSnapshotLayoutTests() {}

    static void layouts() throws Exception {
        var artifact = analyze(BridgeCustomExceptionTests.SOURCE);
        var projection = projection(artifact);
        var layout = BridgeCustomSnapshotLayout.create(artifact, projection);
        check(layout.matches(artifact, projection), "layout lost its exact projection");
        check(!layout.matches(artifact, projection(artifact)), "layout matched another extraction contract");
        check(layout.builtinBases().equals(Map.of("customsnap.Cases$Base", "ironwood.lang.Exception",
                "customsnap.Cases$Detail", "ironwood.lang.Exception", "customsnap.Cases$Unchecked", "ironwood.lang.RuntimeException")), "catch hierarchy changed");
        check(layout.slots().size() == 10 && layout.slots().stream().map(slot -> slot.key().name()).distinct().count() == 10,
                "primitive/String getter slots missing or graph getter acquired a slot: " + layout.slots());
        check(!layout.values().containsKey("customsnap.Cases$Base"), "abstract catch type acquired extraction values");
        var detail = layout.values().get("customsnap.Cases$Detail");
        check(detail.size() == 4, "inherited/overridden detail properties lost");
        check(detail.stream().filter(value -> value.property().name().equals("getBorrowed")).allMatch(value -> !value.property().ownedString()), "borrowed String ownership changed");
        check(detail.stream().filter(value -> value.property().name().equals("getCopy")).allMatch(value -> value.property().ownedString()), "fresh String ownership lost");
        check(layout.slot("getCode", IrType.I32).equals(detail.stream().filter(value -> value.property().name().equals("getCode")).findFirst().orElseThrow().slot()),
                "override changed the declaration slot");
        for (var type : List.of(IrType.I1, IrType.I8, IrType.I16, IrType.U16, IrType.I32, IrType.I64, IrType.F32, IrType.F64, IrType.reference("ironwood.lang.String"))) {
            check(layout.slots().stream().anyMatch(slot -> slot.key().type().equals(type)), "missing copied type " + type.displayName());
        }
        var changed = analyze(BridgeCustomExceptionTests.SOURCE.replace("return 29;", "return 31;"));
        check(!layout.matches(changed, projection), "stale snapshot layout admitted");
        try { BridgeCustomSnapshotLayout.create(changed, projection); throw new AssertionError("stale projection admitted"); }
        catch (IllegalArgumentException expected) { check(expected.getMessage().contains("matching complete"), expected.getMessage()); }
        check(layout.slots().equals(BridgeCustomSnapshotLayout.create(changed, projection(changed)).slots()), "body-only edit changed Java data layout");
        var inherited = analyze("""
                package customsnap;
                public final class Cases {
                    private Cases() {}
                    public static int ping() { return 42; }
                    public static class Base extends java.io.IOException {}
                    public static final class Detail extends Base { public int getCode() { return 17; } }
                    public static final class Unchecked extends java.io.InterruptedIOException {}
                }
                """.replace("java.io.", "ironwood.io."));
        var inheritedLayout = BridgeCustomSnapshotLayout.create(inherited, projection(inherited));
        check(inheritedLayout.builtinBases().get("customsnap.Cases$Detail").equals("ironwood.io.IOException"), "indirect builtin catch base lost");
        check(inheritedLayout.values().get("customsnap.Cases$Unchecked").stream().anyMatch(value -> value.property().field().isPresent()
                && value.slot().key().name().equals("bytesTransferred")), "inherited builtin data field lost");
        Path base = Path.of("workspace/java-bridge/evidence/p3b/custom-layout").toAbsolutePath(); Files.createDirectories(base);
        Path directory = Files.createTempDirectory(base, "run-");
        var unit = SourceParser.parse(SourceFile.of("Cases.iron", BridgeCustomExceptionTests.SOURCE)).unit().orElseThrow();
        Path classes = directory.resolve("classes");
        for (var type : DeclaredTypes.in(unit)) {
            IronClass.write(classes.resolve(type.binaryName().replace('.', '/') + IronClass.EXTENSION), unit, type.binaryName());
        }
        Path archive = directory.resolve("snapshots.ironjar"); IronJar.create(archive, List.of(classes));
        for (Path input : List.of(classes, archive)) {
            var loaded = new SourceSetLoader(List.of(), List.of(input)).loadBridge(List.of(), List.of("customsnap"));
            check(loaded.diagnostics().isEmpty(), loaded.diagnostics().toString());
            var rebuilt = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(loaded.sources());
            check(rebuilt.valid(), rebuilt.diagnostics().toString());
            var reconstructed = BridgeCustomSnapshotLayout.create(rebuilt, projection(rebuilt));
            check(layout.slots().equals(reconstructed.slots()) && layout.values().equals(reconstructed.values())
                    && layout.builtinBases().equals(reconstructed.builtinBases()), "snapshot data layout changed after reconstruction: " + input);
        }
        Files.writeString(directory.resolve("layout.txt"), layout.slots() + "\n" + new java.util.TreeMap<>(layout.values())
                + "\n" + new java.util.TreeMap<>(layout.builtinBases()) + "\n");
        System.out.println("custom snapshot layout evidence: " + directory);
    }

    private static CompilationArtifact analyze(String source) {
        var artifact = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(List.of(SourceFile.of("Cases.iron", source)));
        check(artifact.valid(), artifact.diagnostics().toString()); return artifact;
    }
    private static BridgeExceptionProjection projection(CompilationArtifact artifact) {
        var proof = BridgeExceptionProjection.snapshots(artifact, List.of("customsnap.Cases$Detail", "customsnap.Cases$Unchecked"));
        check(proof.contract().isPresent(), proof.reason()); return proof.contract().orElseThrow();
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
