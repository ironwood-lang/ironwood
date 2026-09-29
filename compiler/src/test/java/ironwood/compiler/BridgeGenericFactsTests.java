// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.ast.DeclaredTypes;
import ironwood.compiler.bridge.BridgeCallableId;
import ironwood.compiler.bridge.BridgeExportSurface;
import ironwood.compiler.bridge.BridgeProof;
import ironwood.compiler.ir.IrCallableKind;
import ironwood.compiler.ir.IrType;
import ironwood.compiler.semantic.BridgeApiFacts;
import ironwood.compiler.source.SourceFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** P7d0 metadata evidence must never become premature generic facade admission. */
final class BridgeGenericFactsTests {
    static final String NAME = "Java Bridge generic signatures preserve bounds owners and proof identity";
    static final String PARITY = "Java Bridge generic metadata reconstructs without admitting generic exports";
    private static final String SOURCE = """
            package genericfacts;
            public interface MarkA {}
            public interface MarkB {}
            public interface Self<T extends Self<T>> { T next(); }
            public class Base {}
            public final class Quote extends Base implements MarkA {}
            public final class Trade extends Base implements MarkA {}
            public final class Box<T extends Base & MarkA> {
                private T value;
                public Box(T value) { this.value = value; }
                public T get() { return value; }
                public void set(T value) { this.value = value; }
            }
            public final class Bounds<T extends Base & MarkA, U extends T> {
                private Bounds() {}
                public int count() { return 1; }
                public U choose(U value) { return value; }
                public <T extends Base & MarkB> T echo(T value) { return value; }
            }
            public class Parent<T extends Base & MarkA> {
                public T field;
                public int count() { return 1; }
                public T get() { return field; }
                public <U extends T> U select(U value) { return value; }
            }
            public final class Child extends Parent<Quote> {}
            public class Outer<T extends Base & MarkA> {
                public class Inner<U extends T> { public U get(U value) { return value; } }
            }
            public final class Unbounded<T> { private Unbounded() {} }
            public final class Explicit<T extends Object> { private Explicit() {} }
            public final class Api {
                private Api() {}
                public static Box<Quote> quotes() { return null; }
                public static Box<Trade> trades() { return null; }
                public static Box<?> any(Box<?> value) { return value; }
                public static Box<? extends Quote> upper(Box<? extends Quote> value) { return value; }
                public static Box<? super Quote> lower(Box<? super Quote> value) { return value; }
                public static Box<Quote>[] array(Box<Quote>[] value) { return value; }
                public static Unbounded<int> primitive() { return null; }
                public static <E extends Exception> void declared() throws E {}
                public static int inspect(Box<Quote> value) { return 0; }
                public static int inspect(Quote value) { return 0; }
            }
            """;

    private BridgeGenericFactsTests() {}

    private static CompilationArtifact analyze(String source) {
        var artifact = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(sources(source));
        check(artifact.valid(), artifact.diagnostics().toString());
        return artifact;
    }

    private static List<SourceFile> sources(String text) {
        var unit = SourceParser.parse(SourceFile.of("Api.iron", text)).unit().orElseThrow();
        return unit.declarations().stream().map(declaration -> SourceFile.of(declaration.name() + ".iron",
                "package genericfacts;\n" + text.substring(declaration.span().start().offset(), declaration.span().end().offset()))).toList();
    }

    private static BridgeApiFacts.Type type(CompilationArtifact artifact, String name) {
        return artifact.bridgeApiFacts().orElseThrow().types().get("genericfacts." + name);
    }

    private static BridgeApiFacts.Callable method(CompilationArtifact artifact, String owner, String name) {
        return type(artifact, owner).callables().stream().filter(method -> method.name().equals(name)).findFirst().orElseThrow();
    }

    static void signatures() {
        var artifact = analyze(SOURCE);
        var facts = artifact.bridgeApiFacts().orElseThrow();
        var program = artifact.program().orElseThrow();
        var bounds = type(artifact, "Bounds");
        var t = bounds.typeParameters().getFirst();
        var u = bounds.typeParameters().get(1);
        check(bounds.generic() && bounds.exactType().typeArguments().size() == 2, "generic self view lost");
        check(t.upperBounds().equals(List.of(IrType.reference("genericfacts.Base"), IrType.reference("genericfacts.MarkA"))),
                "ordered intersection bounds lost");
        check(u.upperBounds().getFirst().referenceName().equals(t.id()) && u.erasure().equals(t.erasure()),
                "dependent bound or first-bound erasure lost");
        check(!t.permitsPrimitive() && type(artifact, "Unbounded").typeParameters().getFirst().permitsPrimitive()
                && !type(artifact, "Explicit").typeParameters().getFirst().permitsPrimitive(), "implicit Object bound collapsed");
        var recursive = type(artifact, "Self").typeParameters().getFirst();
        check(recursive.upperBounds().getFirst().typeArguments().getFirst().referenceName().equals(recursive.id()),
                "recursive bound lost its scoped variable");
        var echo = method(artifact, "Bounds", "echo");
        var local = echo.signature().typeParameters().getFirst();
        check(echo.generic() && local.name().equals(t.name()) && !local.id().equals(t.id())
                && local.upperBounds().get(1).equals(IrType.reference("genericfacts.MarkB"))
                && echo.parameters().getFirst().referenceName().equals(local.id()), "method variable shadowing collapsed");
        var inner = type(artifact, "Outer$Inner");
        check(inner.typeParameters().size() == 1 && inner.exactType().typeArguments().size() == 2
                && method(artifact, "Outer$Inner", "get").signature().receiverParameters().size() == 2,
                "enclosing generic scope lost");
        var child = method(artifact, "Child", "get");
        var owner = IrType.reference("genericfacts.Parent", List.of(IrType.reference("genericfacts.Quote")));
        check(child.signature().receiver().equals(IrType.reference("genericfacts.Child"))
                && child.signature().declaringOwner().equals(owner)
                && child.result().equals(IrType.reference("genericfacts.Quote")) && child.target().isEmpty(),
                "applied inherited signature was collapsed into an erased native target");
        var inheritedCount = method(artifact, "Child", "count");
        check(inheritedCount.target().isPresent()
                && facts.resolve(program, inheritedCount.signature()).contract().orElseThrow().target().isEmpty(),
                "generic receiver mismatch admitted despite scalar signature");
        var appliedVariable = method(artifact, "Child", "select").signature().typeParameters().getFirst();
        check(appliedVariable.upperBounds().equals(List.of(IrType.reference("genericfacts.Quote")))
                && appliedVariable.erasure().equals(IrType.reference("genericfacts.Base")),
                "applied bound replaced declaration erasure");
        check(type(artifact, "Child").fields().stream().anyMatch(field -> field.name().equals("field")
                && field.type().equals(child.result()) && field.declaringOwner().equals(owner)), "inherited field view lost");
        var quotes = method(artifact, "Api", "quotes");
        var trades = method(artifact, "Api", "trades");
        check(!quotes.result().equals(trades.result()) && quotes.result().erasure().equals(trades.result().erasure()),
                "concrete applications not distinct from erasure");
        for (String name : List.of("any", "upper", "lower")) {
            var signature = method(artifact, "Api", name).signature();
            check(signature.result().equals(signature.parameters().getFirst())
                    && signature.result().typeArguments().getFirst().isWildcard(), "wildcard signature lost: " + name);
        }
        check(method(artifact, "Api", "upper").result().typeArguments().getFirst().wildcardKind() == IrType.WildcardKind.EXTENDS
                && method(artifact, "Api", "lower").result().typeArguments().getFirst().wildcardKind() == IrType.WildcardKind.SUPER,
                "wildcard variance collapsed");
        check(method(artifact, "Api", "array").result().elementType().equals(quotes.result()), "generic array element erased");
        check(method(artifact, "Api", "primitive").result().typeArguments().equals(List.of(IrType.I32)), "primitive application lost");
        var declared = method(artifact, "Api", "declared");
        check(declared.signature().thrownTypes().equals(declared.thrownTypes())
                && declared.thrownTypes().getFirst().referenceName().equals(declared.signature().typeParameters().getFirst().id()),
                "generic throws identity lost");
        check(type(artifact, "Api").callables().stream().filter(m -> m.name().equals("inspect")).count() == 2,
                "overloads collapsed");
        check(type(artifact, "Box").callables().stream().anyMatch(m -> m.kind() == IrCallableKind.CONSTRUCTOR)
                && method(artifact, "Box", "set").parameters().getFirst().isTypeParameter(), "unsupported public mutation omitted");

        var count = method(artifact, "Bounds", "count");
        var proof = facts.resolve(program, count.signature());
        check(proof.status() == BridgeProof.Status.PROVED && proof.contract().orElseThrow().matches(program, facts), "signature did not bind");
        var unchanged = analyze(SOURCE);
        check(!facts.matches(unchanged.program().orElseThrow())
                && proof.contract().orElseThrow().matches(unchanged.program().orElseThrow(), unchanged.bridgeApiFacts().orElseThrow()),
                "unchanged signature could not revalidate against fresh semantic facts");
        // Only the second bound changes. Native erasure and declaration positions stay identical.
        var changed = analyze(SOURCE.replace("class Bounds<T extends Base & MarkA", "class Bounds<T extends Base & MarkB"));
        var changedFacts = changed.bridgeApiFacts().orElseThrow();
        var changedProgram = changed.program().orElseThrow();
        check(count.target().isPresent() && count.target().equals(method(changed, "Bounds", "count").target()),
                "fixture changed native callable identity");
        check(program.equals(changedProgram) && !facts.matches(changedProgram), "source-only bound change reused equal-IR facts");
        check(changedFacts.resolve(changedProgram, count.signature()).status() == BridgeProof.Status.REJECTED
                && !proof.contract().orElseThrow().matches(changedProgram, changedFacts), "secondary bound reused a stale signature proof");
        var body = analyze(SOURCE.replace("public int count() { return 1; }", "public int count() { return 2; }"));
        check(facts.resolve(body.program().orElseThrow(), count.signature()).status() == BridgeProof.Status.UNKNOWN
                && !proof.contract().orElseThrow().matches(body.program().orElseThrow(), body.bridgeApiFacts().orElseThrow()),
                "stale implementation facts admitted");
        var raw = withReceiver(count.signature(), IrType.reference("genericfacts.Bounds"));
        var wildcard = withReceiver(count.signature(), IrType.reference("genericfacts.Bounds", List.of(IrType.wildcard(), IrType.wildcard())));
        check(facts.resolve(program, raw).status() == BridgeProof.Status.REJECTED
                && facts.resolve(program, wildcard).status() == BridgeProof.Status.REJECTED,
                "unresolved raw/wildcard client view manufactured a native binding");
        try {
            t.upperBounds().clear();
            throw new AssertionError("mutable generic bounds");
        } catch (UnsupportedOperationException expected) {
            // Immutable declaration metadata cannot be edited into a proof.
        }
        // An unused variable is still an unsupported generic declaration, even
        // when every member's erased/native signature would otherwise be scalar.
        for (String declaration : List.of(
                "public final class Only<T> { private Only() {} public static int value() { return 1; } }",
                "public final class Only { private Only() {} public static <T> int value() { return 1; } }")) {
            var only = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(
                    List.of(SourceFile.of("Only.iron", "package boundary; " + declaration)));
            check(only.valid() && BridgeExportSurface.valuePreview(only, List.of("boundary")).surface().isEmpty()
                    && BridgeExportSurface.objectValues(only, List.of("boundary")).surface().isEmpty(),
                    "unused generic parameter bypassed the producer boundary");
        }
    }

    private static BridgeCallableId.SourceSignature withReceiver(BridgeCallableId.SourceSignature signature, IrType receiver) {
        return new BridgeCallableId.SourceSignature(receiver, signature.declaringOwner(), signature.receiverParameters(),
                signature.ownerParameters(), signature.name(), signature.kind(), signature.isStatic(), signature.typeParameters(),
                signature.parameters(), signature.result(), signature.thrownTypes());
    }

    static void parity() throws Exception {
        var sources = sources(SOURCE);
        var original = analyze(SOURCE);
        var expected = shape(original);
        var directory = Files.createTempDirectory("bridge generic metadata ");
        try {
            Path classes = directory.resolve("classes");
            Path inputSource = directory.resolve("sources"); Files.createDirectories(inputSource);
            for (var source : sources) {
                var unit = SourceParser.parse(source).unit().orElseThrow();
                Files.writeString(inputSource.resolve(unit.declaration().name() + ".iron"), source.content());
                for (var declaration : DeclaredTypes.in(unit)) {
                    IronClass.write(classes.resolve(declaration.binaryName().replace('.', '/') + IronClass.EXTENSION), unit, declaration.binaryName());
                }
            }
            Path archive = directory.resolve("generic.ironjar");
            IronJar.create(archive, List.of(classes));
            for (String variant : List.of("source", "classes", "individual", "archive")) {
                List<Path> inputs;
                if (variant.equals("individual")) {
                    try (var files = Files.walk(classes)) {
                        inputs = files.filter(path -> path.toString().endsWith(IronClass.EXTENSION)).sorted().toList();
                    }
                } else inputs = List.of(variant.equals("archive") ? archive : classes);
                var reconstructed = new SourceSetLoader(List.of(), inputs).loadBridge(List.of(), List.of("genericfacts"));
                check(reconstructed.diagnostics().isEmpty(), reconstructed.diagnostics().toString());
                var loaded = variant.equals("source") ? original
                        : new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(reconstructed.sources());
                check(loaded.valid() && expected.equals(shape(loaded)), "generic metadata changed after " + variant);
                for (var declaration : original.bridgeApiFacts().orElseThrow().types().values()) {
                    if (!declaration.packageName().equals("genericfacts")) continue;
                    for (var callable : declaration.callables()) {
                        var proof = loaded.bridgeApiFacts().orElseThrow().resolve(loaded.program().orElseThrow(), callable.signature());
                        var expectedProof = original.bridgeApiFacts().orElseThrow()
                                .resolve(original.program().orElseThrow(), callable.signature()).contract().orElseThrow();
                        check(proof.status() == BridgeProof.Status.PROVED
                                && proof.contract().orElseThrow().target().equals(expectedProof.target()),
                                "reconstructed signature proof changed: " + callable.name());
                    }
                }
                check(BridgeExportSurface.objectValues(loaded, List.of("genericfacts")).surface().isEmpty(),
                        "metadata-only phase admitted generic facades");
                Path output = directory.resolve("preserved.jar"); Files.writeString(output, "preserved");
                var args = new java.util.ArrayList<>(List.of("--java-bridge", "--export", "genericfacts", "--unfreed=off", "-o", output.toString()));
                if (variant.equals("source")) {
                    try (var files = Files.list(inputSource)) {
                        files.sorted().map(Path::toString).forEach(args::add);
                    }
                }
                else args.addAll(List.of("--source-path", directory.resolve("absent").toString(), "-cp", inputs.stream().map(Path::toString).collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator))));
                BridgeProducerTests.command(directory, "refused-" + variant, 1, args.toArray(String[]::new));
                check(Files.readString(output).equals("preserved"), "refused generic producer replaced existing output");
            }
            var absent = new CompilationArtifact(original.program(), original.llvmIr(), original.diagnostics(), original.bridgeConstructionFacts());
            check(BridgeExportSurface.objectValues(absent, List.of("genericfacts")).surface().isEmpty(), "absent API facts admitted");
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static Map<String, Object> shape(CompilationArtifact artifact) {
        var result = new TreeMap<String, Object>();
        artifact.bridgeApiFacts().orElseThrow().types().values().stream()
                .filter(type -> type.packageName().equals("genericfacts")).forEach(type -> {
                    result.put(type.binaryName(), List.of(type.exactType(), type.typeParameters(), type.supertypes()));
                    for (var method : type.callables()) result.put(type.binaryName() + ":" + method.signature(),
                            List.of(method.signature(), method.target(), method.parameterNames(), method.generic()));
                    for (var field : type.fields()) result.put(type.binaryName() + ":field:" + field.name(),
                            List.of(field.declaringOwner(), field.type(), field.ambiguous()));
                });
        return result;
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
