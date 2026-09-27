// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.ast.DeclaredTypes;
import ironwood.compiler.bridge.BridgeGeneration;
import ironwood.compiler.bridge.BridgeJavaSources;
import ironwood.compiler.bridge.BridgeProof;
import ironwood.compiler.source.SourceFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

final class BridgeObjectGenerationTests {
    static final String NAME = "Java Bridge object identities require final admission and preserve projection parity";
    private static final String SOURCE = """
            package objectidentity;
            public final class Item {
                private static Item saved;
                public Item() {}
                public Item identity() { return this; }
                public int read() throws Problem { return 17; }
                public Side side() { return Side.SELL; }
                public enum Side { SELL, BUY; public int code() { return 11; } }
                public static final class Problem extends ironwood.io.InterruptedIOException {
                    public static final int CODE = 9;
                    public int getCode() { return 17; }
                }
            }
            """;

    private BridgeObjectGenerationTests() {}

    static void identities() throws Exception {
        var artifact = analyze("Item.iron", SOURCE);
        var admission = admit(artifact, "objectidentity");
        var generation = generate(artifact, admission);
        check(generation.matchesObjects(artifact, admission), "exact object identity does not match");
        check(!generation.matches(artifact, admission.surface()), "object identity entered the value-only route");
        check(generation.manifest().get("java.supported").equals("21,22,23"), "object identity broadened version support");
        check(admission.lifetime().exceptions().projection().customTypes().size() == 1, "missing declared snapshot");
        check(admission.surface().types().stream().flatMap(type -> type.fields().stream())
                .anyMatch(field -> field.name().equals("bytesTransferred") && field.constant().isEmpty()),
                "snapshot field identity case missing");
        try {
            BridgeJavaSources.generate(artifact, admission.surface(), generation, admission.entries());
            throw new AssertionError("value generator admitted incomplete object adapters");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains("identity mismatch"), expected.getMessage());
        }
        var relocated = analyze("elsewhere/Item.iron", SOURCE);
        check(generate(relocated, admit(relocated, "objectidentity")).manifest().equals(generation.manifest()),
                "source path entered object generation identity");
        var implementation = analyze("Item.iron", SOURCE.replace("return 17;", "return 19;"));
        var changed = generate(implementation, admit(implementation, "objectidentity"));
        check(generation.apiIdentity().equals(changed.apiIdentity()) && !generation.identity().equals(changed.identity()),
                "getter/body change conflated API and generation");
        check(!generation.matchesObjects(implementation, admission), "stale admission matched changed implementation");
        try {
            generate(implementation, admission);
            throw new AssertionError("stale final proof admitted to object identity");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains("exact current final admission"), expected.getMessage());
        }
        var permanent = analyze("Item.iron", SOURCE.replace("return this;", "saved = this; return saved;"));
        var permanentAdmission = admit(permanent, "objectidentity");
        check(permanentAdmission.roots().isEmpty(), "permanent identity case is not permanent");
        check(!generate(permanent, permanentAdmission).apiIdentity().equals(generation.apiIdentity()),
                "removal of generated destruction was omitted from API identity");
        for (String apiChange : List.of(SOURCE.replace("SELL, BUY", "BUY, SELL"), SOURCE.replace("CODE = 9", "CODE = 10"),
                SOURCE.replace("getCode()", "getNumber()"))) {
            var other = analyze("Item.iron", apiChange);
            check(!generate(other, admit(other, "objectidentity")).apiIdentity().equals(generation.apiIdentity()),
                    "enum order or snapshot API change omitted from identity");
        }
        var mixed = analyze("Holder.iron", BridgeMixedLifetimeTests.SOURCE);
        var mixedAdmission = admit(mixed, "mixedlife");
        check(generate(mixed, mixedAdmission).matchesObjects(mixed, mixedAdmission), "mixed lifetime identity mismatch");
        parity(SOURCE, "objectidentity", generation);
        parity(BridgeMixedLifetimeTests.SOURCE, "mixedlife", generate(mixed, mixedAdmission));
    }

    private static void parity(String source, String exports, BridgeGeneration expected) throws Exception {
        Path directory = Files.createTempDirectory("bridge object identity ");
        try {
            String fileName = exports.equals("mixedlife") ? "Holder.iron" : "Item.iron";
            var unit = SourceParser.parse(SourceFile.of(fileName, source)).unit().orElseThrow();
            Path classes = directory.resolve("classes");
            for (var type : DeclaredTypes.in(unit)) {
                IronClass.write(classes.resolve(type.binaryName().replace('.', '/') + IronClass.EXTENSION), unit, type.binaryName());
            }
            Path archive = directory.resolve("objects.ironjar");
            IronJar.create(archive, List.of(classes));
            for (Path input : List.of(classes, archive)) {
                var loaded = new SourceSetLoader(List.of(), List.of(input)).loadBridge(List.of(), List.of(exports));
                check(loaded.diagnostics().isEmpty(), loaded.diagnostics().toString());
                var artifact = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(loaded.sources());
                check(artifact.valid(), artifact.diagnostics().toString());
                check(generate(artifact, admit(artifact, exports)).manifest().equals(expected.manifest()),
                        "object reconstruction changed identity: " + input);
            }
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static CompilationArtifact analyze(String path, String source) {
        var result = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(List.of(SourceFile.of(path, source)));
        check(result.valid(), result.diagnostics().toString());
        return result;
    }

    private static BridgeObjectAdmission admit(CompilationArtifact artifact, String exports) {
        var proof = BridgeObjectAdmission.prove(artifact, List.of(exports));
        check(proof.status() == BridgeProof.Status.PROVED, proof.reason());
        return proof.contract().orElseThrow();
    }

    private static BridgeGeneration generate(CompilationArtifact artifact, BridgeObjectAdmission admission) {
        return BridgeGeneration.createObjects("objects.jar", artifact, admission, "test", "1".repeat(64), "2".repeat(64));
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
