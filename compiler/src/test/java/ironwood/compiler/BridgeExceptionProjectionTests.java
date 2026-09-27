// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.bridge.*;
import ironwood.compiler.semantic.BridgeRetentionAnalyzer;
import ironwood.compiler.source.SourceFile;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

final class BridgeExceptionProjectionTests {
    private BridgeExceptionProjectionTests() {}

    private static String source() {
        var text = new StringBuilder("package snapshots; final class Inventory {\n");
        int index = 0;
        for (String type : BridgeExportSurface.builtinThrowableNames().stream().sorted().toList()) {
            text.append("private static ").append(type).append(" field").append(index++).append(";\n");
        }
        return text.append("static String fresh(String input) { return new String(input); }\n")
                .append("static String borrowed(IllegalArgumentException failure) { return failure.getMessage(); }\n")
                .append("static String unknown(String input, boolean choose) { return choose ? null : input.repeat(2); }\n")
                .append("static int changed() { return 1; }\n}").toString();
    }

    private static BridgeExceptionProjection project(List<SourceFile> sources, UnfreedMode mode) {
        var artifact = new CompilerPipeline(mode).analyzeForBridge(sources);
        check(artifact.valid(), artifact.diagnostics().toString());
        var proof = BridgeExceptionProjection.builtins(artifact, BridgeExportSurface.builtinThrowableNames());
        check(proof.status() == BridgeProof.Status.PROVED, proof.reason());
        var projection = proof.contract().orElseThrow();
        for (var type : projection.types()) {
            check(artifact.program().orElseThrow().classes().stream().anyMatch(candidate ->
                    candidate.name().equals(type.nativeName()) && candidate.typeId() == type.typeId()),
                    "projection type identifier does not belong to its own program");
        }
        return projection;
    }

    static void projections() throws Exception {
        var sources = List.of(SourceFile.of("Inventory.iron", source()));
        for (var mode : UnfreedMode.values()) {
            var artifact = new CompilerPipeline(mode).analyzeForBridge(sources);
            check(artifact.valid(), artifact.diagnostics().toString());
            var proof = BridgeExceptionProjection.builtins(artifact, BridgeExportSurface.builtinThrowableNames());
            check(proof.status() == BridgeProof.Status.PROVED, proof.reason());
            var projection = proof.contract().orElseThrow();
            check(projection.types().size() == BridgeExportSurface.builtinThrowableNames().size(), "builtin projection omitted a type");
            check(projection.matches(artifact.program().orElseThrow()) && projection.accessors().resolved(), "projection lost input identity");
            for (var type : projection.types()) {
                check(Throwable.class.isAssignableFrom(Class.forName(type.javaName())), "wrong Java throwable mapping");
                var nativeType = artifact.program().orElseThrow().classes().stream()
                        .filter(candidate -> candidate.name().equals(type.nativeName())).findFirst().orElseThrow();
                check(nativeType.typeId() == type.typeId(), "projection native type identifier changed");
                check(Class.forName(BridgeJavaTypes.binaryName(ironwood.compiler.ir.IrType.reference(
                        nativeType.superclass().orElseThrow()))).isAssignableFrom(Class.forName(type.javaName())),
                        "native superclass catch lost: " + type.nativeName());
                check(type.properties().stream().filter(property -> property.name().equals("getMessage")).count() == 1,
                        "missing message: " + type.nativeName());
                for (var property : type.properties()) {
                    check(property.ownedString() == property.name().equals("getParsedString"),
                            "wrong builtin String getter ownership: " + type.nativeName() + "." + property);
                }
                if (type.nativeName().equals("ironwood.net.SocketTimeoutException")) {
                    check(type.properties().stream().anyMatch(property -> property.name().equals("bytesTransferred")
                            && property.field().isPresent()), "inherited transferred bytes missing");
                }
                if (type.nativeName().equals("ironwood.io.UncheckedIOException")) {
                    check(type.properties().stream().filter(property -> property.name().equals("getCause"))
                            .findFirst().orElseThrow().type().referenceName().equals("ironwood.io.IOException"), "covariant cause target lost");
                }
            }
            var program = artifact.program().orElseThrow();
            var roots = BridgeRootSet.resolve(program, program.functions().stream().filter(function ->
                    function.ownerClass().equals("snapshots.Inventory") && function.returnType().isReference())
                    .map(BridgeCallableId::of).toList());
            var borrowed = BridgeRetentionAnalyzer.borrowedStringResults(program, roots);
            check(borrowed.size() == 1 && borrowed.iterator().next().name().equals("borrowed"),
                    "fresh/unknown String getter was treated as borrowed: " + borrowed);
            check(BridgeExceptionProjection.builtins(artifact, List.of("snapshots.CustomException")).status() == BridgeProof.Status.REJECTED,
                    "unmapped custom throwable admitted in P2");
            var changed = new CompilerPipeline(mode).analyzeForBridge(List.of(SourceFile.of("Inventory.iron", source().replace("return 1;", "return 2;"))));
            var stale = new CompilationArtifact(changed.program(), changed.llvmIr(), changed.diagnostics(),
                    artifact.bridgeConstructionFacts(), changed.bridgeApiFacts());
            check(!projection.matches(changed.program().orElseThrow()) && BridgeExceptionProjection.builtins(stale,
                    BridgeExportSurface.builtinThrowableNames()).status() != BridgeProof.Status.PROVED, "stale projection accepted");
        }
        Path directory = Files.createTempDirectory("bridge-exception-projection-");
        try {
            Path source = directory.resolve("Inventory.iron");
            Files.writeString(source, source());
            var expected = project(List.of(SourceFile.read(source)), UnfreedMode.OFF);
            Path classes = directory.resolve("classes");
            var bytes = new ByteArrayOutputStream();
            var output = new PrintStream(bytes, true, StandardCharsets.UTF_8);
            check(Main.run(new String[]{source.toString(), "-d", classes.toString()}, output, output) == 0, bytes.toString());
            Path archive = directory.resolve("projection.ironjar");
            check(IronJarMain.run(new String[]{"--create", "--file", archive.toString(), classes.toString()}, output, output) == 0, bytes.toString());
            Files.delete(source);
            for (Path container : List.of(classes, classes.resolve("snapshots/Inventory.ironclass"), archive)) {
                var loaded = new SourceSetLoader(List.of(directory.resolve("absent")), List.of(container)).load(List.of(), List.of("snapshots.Inventory"));
                check(loaded.diagnostics().isEmpty(), loaded.diagnostics().toString());
                var actual = project(loaded.sources(), UnfreedMode.OFF);
                // Native type numbering is private to each final program, not a logical API identity.
                check(semanticTypes(expected).equals(semanticTypes(actual)) && expected.accessors().equals(actual.accessors()),
                        "exception projection semantics changed after reconstruction: " + container);
            }
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static List<BridgeExceptionProjection.Type> semanticTypes(BridgeExceptionProjection projection) {
        return projection.types().stream().map(type -> new BridgeExceptionProjection.Type(
                type.nativeName(), type.javaName(), 0, type.properties())).toList();
    }
}
