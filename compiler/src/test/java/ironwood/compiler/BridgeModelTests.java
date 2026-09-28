// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.bridge.BridgeAbi;
import ironwood.compiler.bridge.BridgeCallableId;
import ironwood.compiler.bridge.BridgeProof;
import ironwood.compiler.bridge.BridgeRootSet;
import ironwood.compiler.ir.IrCallableKind;
import ironwood.compiler.ir.IrProgram;
import ironwood.compiler.ir.IrType;
import ironwood.compiler.source.SourceFile;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

final class BridgeModelTests {
    private static final String SOURCE = """
            package bridgefixture;
            public final class Engine {
                private int value;
                public Engine(int value) { this.value = value; }
                public int read() { return value; }
                public static int add(int a, int b) { return a + b; }
                public static long add(long a, long b) { return a + b; }
                public static char character(char value) { return value; }
                public static boolean flag(boolean value) { return value; }
                public static int[][] unsupported(int[][] values) { return values; }
            }
            """;

    private BridgeModelTests() {}

    static void rootsAndAbi() {
        IrProgram program = analyze(List.of(SourceFile.of("test/Engine.iron", SOURCE)));
        List<BridgeCallableId> requested = roots(program);
        BridgeRootSet model = BridgeRootSet.resolve(program, requested);
        check(model.resolved(), model.problems().toString());
        check(model.roots().size() == 6, "all overloads, constructor and instance method must resolve");
        check(model.roots().stream().filter(root -> root.callable().name().equals("add")).count() == 2,
                "overload identity was lost");
        check(model.roots().stream().filter(root -> root.callable().name().equals("read"))
                .findFirst().orElseThrow().abi().parameters().getFirst().carrier()
                == BridgeAbi.Carrier.OPAQUE_REFERENCE, "instance receiver ABI");
        check(BridgeAbi.carrierFor(IrType.I16).orElseThrow() == BridgeAbi.Carrier.SIGNED_I16,
                "short signedness");
        check(BridgeAbi.carrierFor(IrType.U16).orElseThrow() == BridgeAbi.Carrier.UTF16_U16,
                "char signedness");
        check(BridgeAbi.carrierFor(IrType.I1).orElseThrow() == BridgeAbi.Carrier.BOOLEAN_U8,
                "boolean must use normalized byte transport");
        check(BridgeAbi.carrierFor(IrType.array(IrType.I32)).orElseThrow() == BridgeAbi.Carrier.OPAQUE_REFERENCE,
                "primitive array descriptor does not itself authorize conversion");
        for (IrType unsupported : List.of(IrType.array(IrType.array(IrType.I32)), IrType.typeParameter("T"),
                IrType.reference("Box", List.of(IrType.I32)), IrType.EXCEPTION, IrType.NULL)) {
            check(BridgeAbi.carrierFor(unsupported).isEmpty(), "unsupported ABI silently erased: " + unsupported);
        }
        var invalid = BridgeCallableId.of(program.functions().stream()
                .filter(function -> function.sourceName().equals("unsupported")).findFirst().orElseThrow());
        var mixed = new ArrayList<>(requested);
        mixed.add(invalid);
        var failed = BridgeRootSet.resolve(program, mixed);
        check(!failed.resolved() && failed.roots().isEmpty(), "must not expose partial roots");
        check(failed.problems().getFirst().span().isPresent(), "unsupported ABI requires source span");
        BridgeCallableId first = requested.getFirst();
        var stale = new BridgeCallableId(first.owner(), first.name(), first.linkage(), first.kind(),
                first.parameters(), IrType.F64);
        check(!BridgeRootSet.resolve(program, List.of(stale)).resolved(), "stale signature accepted");
        check(!BridgeRootSet.resolve(program, List.of()).resolved(), "empty roots authorize no output");
        var repeated = new ArrayList<>(requested);
        repeated.addAll(requested);
        java.util.Collections.reverse(repeated);
        check(model.equals(BridgeRootSet.resolve(program, repeated)), "root order/duplicates changed model");
        var mutable = new ArrayList<>(model.roots());
        var copied = new BridgeRootSet(mutable, List.of());
        mutable.clear();
        check(copied.equals(model), "root model retained mutable list");
        expectFailure(() -> model.roots().clear(), UnsupportedOperationException.class);
        expectFailure(() -> new BridgeAbi.Value(IrType.I32, BridgeAbi.Carrier.OPAQUE_REFERENCE),
                IllegalArgumentException.class);
    }

    static void artifacts() throws Exception {
        Path temporary = Files.createTempDirectory("bridge model artifacts ");
        try {
            Path input = temporary.resolve("source/Engine.iron");
            Files.createDirectories(input.getParent());
            Files.writeString(input, SOURCE);
            IrProgram original = analyze(List.of(SourceFile.read(input)));
            BridgeRootSet expected = BridgeRootSet.resolve(original, roots(original));
            Path classes = temporary.resolve("classes");
            runMain(new String[]{input.toString(), "-d", classes.toString()});
            Path individual = classes.resolve("bridgefixture/Engine.ironclass");
            Path archive = temporary.resolve("engine.ironjar");
            ByteArrayOutputStream errors = new ByteArrayOutputStream();
            check(IronJarMain.run(new String[]{"--create", "--file", archive.toString(), classes.toString()},
                    stream(new ByteArrayOutputStream()), stream(errors)) == 0, errors.toString(StandardCharsets.UTF_8));
            Files.delete(input);
            for (Path container : List.of(classes, individual, archive)) {
                var loaded = new SourceSetLoader(List.of(temporary.resolve("missing")), List.of(container))
                        .load(List.of(), List.of("bridgefixture.Engine"));
                check(loaded.diagnostics().isEmpty(), loaded.diagnostics().toString());
                IrProgram reconstructed = analyze(loaded.sources());
                BridgeRootSet actual = expected.revalidate(reconstructed);
                check(actual.resolved(), container + ": " + actual.problems());
                check(expected.equals(actual), "reconstruction changed identities/ABI/source spans: " + container);
            }
        } finally {
            try (var paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    static void proofs() {
        var unknown = BridgeProof.<String>unknown("analysis has not run");
        var rejected = BridgeProof.<String>rejected("reachable exposed reclamation");
        var proved = BridgeProof.proved("immutable contract", "complete closure");
        check(unknown.status() != rejected.status(), "unknown and disproved must remain distinct");
        check(unknown.contract().isEmpty() && rejected.contract().isEmpty(), "unproved contract escaped");
        check(proved.contract().orElseThrow().equals("immutable contract"), "proved contract lost");
        expectFailure(() -> new BridgeProof<>(BridgeProof.Status.UNKNOWN, Optional.of("unsafe"), "unknown"),
                IllegalArgumentException.class);
        expectFailure(() -> new BridgeProof<>(BridgeProof.Status.REJECTED, Optional.of("unsafe"), "rejected"),
                IllegalArgumentException.class);
        expectFailure(() -> new BridgeProof<>(BridgeProof.Status.PROVED, Optional.empty(), "empty"),
                IllegalArgumentException.class);
        expectFailure(() -> BridgeProof.unknown(""), IllegalArgumentException.class);
    }

    private static List<BridgeCallableId> roots(IrProgram program) {
        return program.functions().stream().filter(function -> function.ownerClass().equals("bridgefixture.Engine")
                && (function.kind() == IrCallableKind.METHOD || function.kind() == IrCallableKind.CONSTRUCTOR)
                && !function.sourceName().equals("unsupported")).map(BridgeCallableId::of).toList();
    }

    private static IrProgram analyze(List<SourceFile> sources) {
        var artifact = new CompilerPipeline(UnfreedMode.OFF).analyze(sources);
        check(artifact.valid(), artifact.diagnostics().toString());
        return artifact.program().orElseThrow();
    }

    private static void runMain(String[] args) {
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        check(Main.run(args, stream(new ByteArrayOutputStream()), stream(errors)) == 0,
                errors.toString(StandardCharsets.UTF_8));
    }

    private static PrintStream stream(ByteArrayOutputStream bytes) {
        return new PrintStream(bytes, true, StandardCharsets.UTF_8);
    }

    private static void expectFailure(Runnable action, Class<? extends RuntimeException> expected) {
        try {
            action.run();
        } catch (RuntimeException exception) {
            check(expected.isInstance(exception), exception.toString());
            return;
        }
        throw new AssertionError("expected " + expected.getSimpleName());
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
