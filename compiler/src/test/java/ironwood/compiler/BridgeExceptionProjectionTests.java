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

    static void entries() throws Exception {
        var artifact = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(List.of(SourceFile.of("Inventory.iron", source())));
        check(artifact.valid(), artifact.diagnostics().toString());
        var original = artifact.program().orElseThrow();
        var id = original.functions().stream().filter(function -> function.ownerClass().equals("snapshots.Inventory")
                && function.sourceName().equals("changed")).map(BridgeCallableId::of).findFirst().orElseThrow();
        var module = BridgeEntryModule.scalars(artifact, BridgeRootSet.resolve(original, List.of(id)));
        var projection = BridgeExceptionProjection.builtins(artifact, BridgeExportSurface.builtinThrowableNames()).contract().orElseThrow();
        var entries = BridgeExceptionEntries.attach(artifact, module, projection);
        var changed = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(List.of(SourceFile.of("Inventory.iron",
                source().replace("return 1;", "return 2;"))));
        try {
            BridgeExceptionEntries.attach(changed, module, projection);
            throw new AssertionError("stale exception entries attached");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains("original projected program"), expected.toString());
        }
        for (var entry : entries.accessors().entrySet()) {
            if (entry.getKey().field().isPresent()) continue;
            var function = entry.getValue();
            check(function.blocks().getFirst().terminator() instanceof ironwood.compiler.ir.IrInvokeTerminator,
                    "getter escaped protected invoke");
            var failure = function.blocks().stream().filter(block -> block.label().equals("failure")).findFirst().orElseThrow();
            check(failure.instructions().get(1) instanceof ironwood.compiler.ir.IrExceptionCaughtInstruction,
                    "getter failure did not clear ordinary occurrence state");
            check(function.blocks().stream().noneMatch(block -> block.terminator() instanceof ironwood.compiler.ir.IrInvokeTerminator invoke
                    && invoke.call() instanceof ironwood.compiler.ir.IrBridgeFailureSnapshotInstruction), "recursive getter-failure snapshot");
        }
        var optimized = NativeLinkPipeline.finish(NativeLinkPipeline.optimize(entries.program()));
        check(optimized.exportRoots().equals(entries.program().exportRoots()), "snapshot entry roots disappeared");
        var generated = java.util.stream.Stream.concat(entries.accessors().values().stream(), java.util.stream.Stream.of(entries.trace()))
                .map(BridgeCallableId::of).toList();
        check(BridgeRootSet.resolve(optimized, generated).resolved(), "snapshot entry signatures changed");
        var span = entries.trace().sourceSpan();
        try {
            new ironwood.compiler.ir.IrBridgeFailureSnapshotInstruction(
                    new ironwood.compiler.ir.IrValueReference(0, ironwood.compiler.ir.IrType.reference("ironwood.lang.String"), span),
                    new ironwood.compiler.ir.IrValueReference(1, ironwood.compiler.ir.IrType.I64, span), span);
            throw new AssertionError("untyped snapshot reference admitted");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains("Throwable"), expected.toString());
        }
        Path base = Path.of("workspace/java-bridge/evidence/p2/exception-entries").toAbsolutePath();
        Files.createDirectories(base);
        Path directory = Files.createTempDirectory(base, "run-");
        Path llvm = directory.resolve("entries.ll"), bitcode = directory.resolve("entries.bc");
        Files.writeString(llvm, new ironwood.compiler.backend.LlvmEmitter().emit(optimized));
        var discovery = ironwood.compiler.backend.LlvmToolchain.discover(null);
        check(discovery.successful(), discovery.error());
        var toolchain = discovery.toolchain().orElseThrow();
        BridgeEntryTests.run(directory, List.of(toolchain.llvmAs().toString(), llvm.toString(), "-o", bitcode.toString()), "assemble");
        BridgeEntryTests.run(directory, List.of(toolchain.opt().toString(), "-passes=verify", bitcode.toString(), "-disable-output"), "verify");
        System.out.println("protected exception entry evidence: " + directory);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static List<BridgeExceptionProjection.Type> semanticTypes(BridgeExceptionProjection projection) {
        return projection.types().stream().map(type -> new BridgeExceptionProjection.Type(
                type.nativeName(), type.javaName(), 0, type.properties())).toList();
    }
}
