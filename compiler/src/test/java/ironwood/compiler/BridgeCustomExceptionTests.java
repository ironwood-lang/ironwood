// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.ast.DeclaredTypes;
import ironwood.compiler.bridge.*;
import ironwood.compiler.ir.*;
import ironwood.compiler.source.SourceFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class BridgeCustomExceptionTests {
    static final String NAME = "Java Bridge custom snapshot proofs preserve hierarchy exact getters and copied data";
    private static final List<String> REQUESTED = List.of("customsnap.Cases$Detail", "customsnap.Cases$Unchecked");
    static final String SOURCE = """
            package customsnap;
            public final class Cases {
                private Cases() {}
                public static int ping() { return 42; }
                public abstract static class Base extends Exception {
                    public Base(String message) { super(message); }
                    public abstract int getCode();
                    public String getBorrowed() { return getMessage(); }
                }
                public static final class Detail extends Base {
                    private boolean fail;
                    public Detail(String message) { super(message); }
                    @Override public int getCode() { return 29; }
                    @Override public String getMessage() { return "detail"; }
                    @Override public Unchecked getCause() { return null; }
                    public String getCopy() { if (fail) throw null; return new String("copy"); }
                    public double getMagnitude() { if (fail) throw null; return 1.25; }
                }
                public static final class Unchecked extends RuntimeException {
                    public static final int TAG = 7;
                    public boolean isReady() { return true; }
                    public byte getByte() { return (byte) -2; }
                    public short getShort() { return (short) -3; }
                    public char getUnit() { return 'x'; }
                    public long getLong() { return 12345678901L; }
                    public float getFloat() { return 1.5f; }
                }
            }
            """;

    private BridgeCustomExceptionTests() {}

    static void proofs() throws Exception {
        var source = SourceFile.of("Cases.iron", SOURCE);
        for (var mode : UnfreedMode.values()) {
            var artifact = analyze(source, mode);
            var proof = project(artifact);
            check(proof.status() == BridgeProof.Status.PROVED, proof.reason());
            var projection = proof.contract().orElseThrow();
            check(projection.customTypes().size() == 3 && projection.types().size() == 4, "abstract catch hierarchy incomplete");
            var causeClosure = BridgeExceptionProjection.snapshots(artifact, List.of(REQUESTED.getFirst()));
            check(causeClosure.status() == BridgeProof.Status.PROVED
                    && causeClosure.contract().orElseThrow().customTypes().keySet().equals(projection.customTypes().keySet()),
                    "covariant cause declaration escaped snapshot type closure");
            check(projection.customTypes().get("customsnap.Cases$Base").abstractType()
                    && projection.types().stream().noneMatch(type -> type.nativeName().endsWith("$Base")),
                    "abstract catch type acquired an instance extractor");
            var detail = projection.types().stream().filter(type -> type.nativeName().endsWith("$Detail")).findFirst().orElseThrow();
            check(property(detail, "getCode").callable().orElseThrow().owner().endsWith("$Detail")
                    && property(detail, "getMessage").callable().orElseThrow().owner().endsWith("$Detail"), "override target was not exact");
            check(property(detail, "getCopy").ownedString() && !property(detail, "getBorrowed").ownedString(),
                    "fresh and borrowed String snapshots share cleanup");
            check(property(detail, "getMagnitude").type().equals(IrType.F64), "double snapshot lost its type");
            check(BridgeExceptionProjection.builtins(artifact, REQUESTED).status() != BridgeProof.Status.PROVED
                    && BridgeExportSurface.valuePreview(artifact, List.of("customsnap")).surface().isEmpty(),
                    "unfinished custom snapshot transport became public");
            var signatures = BridgeExportSurface.objectValues(artifact, List.of("customsnap"));
            check(signatures.surface().isPresent() && signatures.surface().orElseThrow().roots().roots().size() == 1,
                    "snapshot declarations became native constructor entries: " + signatures.diagnostics());
            var entries = entries(artifact, projection);
            String transport = BridgeExceptionNativeSources.generate(artifact, projection, entries);
            check(transport.contains("iw_exception_custom_") && transport.contains("[[J[[Ljava/lang/String;"),
                    "custom copied data absent from native transport");
            var changed = analyze(SourceFile.of("Cases.iron", SOURCE.replace("return 29;", "return 31;")), mode);
            var stale = new CompilationArtifact(changed.program(), changed.llvmIr(), changed.diagnostics(),
                    changed.bridgeConstructionFacts(), artifact.bridgeApiFacts());
            check(!projection.matches(changed.program().orElseThrow()) && project(stale).status() != BridgeProof.Status.PROVED,
                    "stale custom hierarchy reused");
            if (mode == UnfreedMode.OFF) {
                parity(source, shape(proof));
                assemble(entries);
            }
            for (String bad : List.of(
                    SOURCE.replace("public double getMagnitude()", "public double getMagnitude(int index)"),
                    SOURCE.replace("public double getMagnitude() { if (fail) throw null; return 1.25; }", "public Object getObject() { return null; }"),
                    SOURCE.replace("public double getMagnitude() { if (fail) throw null; return 1.25; }", "public int[] getArray() { return null; }"),
                    SOURCE.replace("private boolean fail;", "private boolean fail; public int mutable;"),
                    SOURCE.replace("public abstract static class Base", "private abstract static class Base"),
                    SOURCE.replace("private boolean fail;", "private boolean fail; private static String unknown; public String getUnknown() { return unknown; }"),
                    SOURCE.replace("private boolean fail;", "private boolean fail; public <T> T getValue() { return null; }"),
                    SOURCE.replace("private boolean fail;", "private boolean fail; private static Detail saved;")
                            .replace("return 29;", "saved = this; return 29;"),
                    SOURCE.replace("return new String(\"copy\");", "return fail ? new String(\"copy\") : getMessage();"),
                    SOURCE.replace("public double getMagnitude() { if (fail) throw null; return 1.25; }",
                            "public int getSecondaryException() { return 1; }"))) {
                var input = SourceFile.of("Cases.iron", bad);
                var rejected = project(analyze(input, mode));
                check(rejected.status() != BridgeProof.Status.PROVED, "unsupported custom snapshot admitted: " + bad);
                if (mode == UnfreedMode.OFF) parity(input, shape(rejected));
            }
        }
    }

    private static BridgeExceptionProjection.Property property(BridgeExceptionProjection.Type type, String name) {
        return type.properties().stream().filter(value -> value.name().equals(name)).findFirst().orElseThrow();
    }

    private static CompilationArtifact analyze(SourceFile source, UnfreedMode mode) {
        var artifact = new CompilerPipeline(mode).analyzeForBridge(List.of(source));
        check(artifact.valid(), artifact.diagnostics().toString());
        return artifact;
    }

    private static BridgeProof<BridgeExceptionProjection> project(CompilationArtifact artifact) {
        return BridgeExceptionProjection.snapshots(artifact, REQUESTED);
    }

    private static BridgeExceptionEntries entries(CompilationArtifact artifact, BridgeExceptionProjection projection) {
        var program = artifact.program().orElseThrow();
        var ping = program.functions().stream().filter(function -> function.ownerClass().equals("customsnap.Cases")
                && function.sourceName().equals("ping")).map(BridgeCallableId::of).findFirst().orElseThrow();
        var module = BridgeEntryModule.scalars(artifact, BridgeRootSet.resolve(program, List.of(ping)));
        var entries = BridgeExceptionEntries.attach(artifact, module, projection);
        check(entries.accessors().values().stream().allMatch(function -> function.blocks().getFirst().terminator() instanceof IrInvokeTerminator),
                "raising snapshot getter escaped typed protection");
        return entries;
    }

    private static void assemble(BridgeExceptionEntries entries) throws Exception {
        var program = NativeLinkPipeline.finish(NativeLinkPipeline.optimize(entries.program()));
        Path base = Path.of("workspace/java-bridge/evidence/p3a/custom-exception-proofs").toAbsolutePath();
        Files.createDirectories(base);
        Path directory = Files.createTempDirectory(base, "run-");
        Path llvm = directory.resolve("entries.ll"), bitcode = directory.resolve("entries.bc");
        Files.writeString(llvm, new ironwood.compiler.backend.LlvmEmitter().emit(program));
        var discovery = ironwood.compiler.backend.LlvmToolchain.discover(null);
        check(discovery.successful(), discovery.error());
        var toolchain = discovery.toolchain().orElseThrow();
        BridgeEntryTests.run(directory, List.of(toolchain.llvmAs().toString(), llvm.toString(), "-o", bitcode.toString()), "assemble");
        BridgeEntryTests.run(directory, List.of(toolchain.opt().toString(), "-passes=verify", bitcode.toString(), "-disable-output"), "verify");
        System.out.println("custom exception proof evidence: " + directory);
    }

    private static Map<String, String> shape(BridgeProof<BridgeExceptionProjection> proof) {
        if (proof.status() != BridgeProof.Status.PROVED) return Map.of("rejected", proof.reason());
        var result = new TreeMap<String, String>();
        var projection = proof.contract().orElseThrow();
        projection.customTypes().forEach((name, type) -> result.put(name, type.supertypes() + ":" + type.abstractType()));
        projection.types().forEach(type -> result.put("data:" + type.nativeName(), type.properties().toString()));
        return result;
    }

    private static void parity(SourceFile source, Map<String, String> expected) throws Exception {
        Path directory = Files.createTempDirectory("bridge custom exception ");
        try {
            var unit = SourceParser.parse(source).unit().orElseThrow();
            Path classes = directory.resolve("classes");
            for (var type : DeclaredTypes.in(unit)) {
                IronClass.write(classes.resolve(type.binaryName().replace('.', '/') + IronClass.EXTENSION), unit, type.binaryName());
            }
            Path archive = directory.resolve("snapshots.ironjar");
            IronJar.create(archive, List.of(classes));
            for (Path input : List.of(classes, archive)) {
                var loaded = new SourceSetLoader(List.of(), List.of(input)).loadBridge(List.of(), List.of("customsnap"));
                check(loaded.diagnostics().isEmpty(), loaded.diagnostics().toString());
                var artifact = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(loaded.sources());
                check(artifact.valid() && shape(project(artifact)).equals(expected), "custom snapshot proof changed after reconstruction: " + input);
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
}
