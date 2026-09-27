// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.ast.DeclaredTypes;
import ironwood.compiler.bridge.BridgeExportSurface;
import ironwood.compiler.bridge.BridgeEnumConstants;
import ironwood.compiler.bridge.BridgeEnumDispatch;
import ironwood.compiler.ir.IrEnumConstant;
import ironwood.compiler.ir.IrType;
import ironwood.compiler.semantic.BridgeApiFacts;
import ironwood.compiler.source.SourceFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class BridgeEnumApiTests {
    static final String NAME = "Java Bridge enum inventory preserves named constants and synthesized roles";
    private static final String SOURCE = """
            package enuminventory;
            public enum Side {
                SELL(29) {
                    @Override public int code() { return 31; }
                    @Override public String toString() { return "sell"; }
                }, BUY(11);
                private final int code;
                Side(int code) { this.code = code; }
                public int code() { return code; }
                public static int values(int input) { return input; }
                public static Side choose(boolean sell) { return sell ? SELL : BUY; }
                public enum Empty { ; }
                public enum Mode {
                    FIRST { @Override public int code() { return 1; } },
                    SECOND { @Override public int code() { return 2; } };
                    public abstract int code();
                }
            }
            """;

    private BridgeEnumApiTests() {}

    static void inventory() throws Exception {
        var source = SourceFile.of("Side.iron", SOURCE);
        var artifact = analyze(List.of(source));
        var expected = shape(artifact);
        var facts = artifact.bridgeApiFacts().orElseThrow();
        var side = facts.types().get("enuminventory.Side");
        check(side.kind() == BridgeApiFacts.Kind.ENUM && side.accessible(), "lost enum identity");
        check(side.enumConstants().stream().map(BridgeApiFacts.EnumConstant::name).toList().equals(List.of("SELL", "BUY")),
                "named constants lost declaration order");
        check(!side.enumConstants().getFirst().nativeType().equals(IrType.reference(side.binaryName()))
                && side.enumConstants().get(1).nativeType().equals(IrType.reference(side.binaryName())),
                "constant-specific dynamic type was erased");
        for (var constant : side.enumConstants()) {
            check(source.content().substring(constant.span().start().offset(), constant.span().end().offset()).equals(constant.name()),
                    "constant name lost source span");
            var field = artifact.program().orElseThrow().staticFields().stream()
                    .filter(candidate -> candidate.ownerClass().equals(side.binaryName()) && candidate.name().equals(constant.name()))
                    .findFirst().orElseThrow();
            check(field.initialValue() instanceof IrEnumConstant value && value.constantName().equals(constant.name())
                    && value.storageType().equals(constant.nativeType()), "named constant inventory disagrees with typed storage");
        }
        check(side.callables().stream().anyMatch(method -> method.name().equals("values") && method.parameters().isEmpty()
                && method.synthetic()), "compiler-generated values role missing");
        check(side.callables().stream().anyMatch(method -> method.name().equals("values") && method.parameters().equals(List.of(IrType.I32))
                && !method.synthetic()), "source values overload misclassified as generated");
        check(side.callables().stream().anyMatch(method -> method.name().equals("valueOf") && method.synthetic()),
                "compiler-generated valueOf role missing");
        var empty = facts.types().get("enuminventory.Side$Empty");
        check(empty.kind() == BridgeApiFacts.Kind.ENUM && empty.enumConstants().isEmpty()
                && empty.accessible() && empty.staticMember(), "empty nested enum metadata lost");
        check(facts.types().values().stream().filter(type -> type.kind() != BridgeApiFacts.Kind.ENUM)
                .allMatch(type -> type.enumConstants().isEmpty()), "non-enum acquired named singleton metadata");
        var changed = analyze(List.of(SourceFile.of("Side.iron", SOURCE.replace("return 31", "return 32"))));
        check(!facts.matches(changed.program().orElseThrow()), "stale enum inventory accepted");
        var sideType = IrType.reference(side.binaryName());
        var emptyType = IrType.reference(empty.binaryName());
        var types = java.util.Set.of(sideType, emptyType);
        var mapping = BridgeEnumConstants.discover(artifact, types);
        check(mapping.matches(artifact.program().orElseThrow(), types)
                && !mapping.matches(changed.program().orElseThrow(), types)
                && !mapping.matches(artifact.program().orElseThrow(), java.util.Set.of(sideType)), "stale enum mapping accepted");
        check(mapping.constants().get(emptyType).isEmpty(), "empty enum converted to missing metadata");
        check(mapping.constants().get(sideType).stream().anyMatch(constant -> constant.field().name().equals("SELL")
                && constant.token() == 1 && ((IrEnumConstant) constant.field().initialValue()).ordinal() == 0),
                "generated token assumed native ordinal");
        var arbitrary = BridgeEnumConstants.prove(artifact, Map.of(sideType, Map.of("SELL", 41, "BUY", 7)));
        check(arbitrary.constants().get(sideType).stream().anyMatch(constant -> constant.field().name().equals("SELL")
                && constant.token() == 41), "producer token lost name binding");
        check(!mapping.initializers().isEmpty(), "enum conversion lost initialization closure");
        for (var invalid : List.of(Map.of("SELL", 0), Map.of("SELL", 0, "BUY", 0),
                Map.of("SELL", -1, "BUY", 0), Map.of("SELL", 1, "BUY", 0, "EXTRA", 2))) {
            denied(() -> BridgeEnumConstants.prove(artifact, Map.of(sideType, invalid)));
        }
        denied(() -> BridgeEnumConstants.prove(artifact, Map.of(IrType.reference("ironwood.lang.Object"), Map.of())));
        denied(() -> BridgeEnumConstants.discover(artifact, java.util.Set.of(side.enumConstants().getFirst().nativeType())));
        var ordinary = new CompilerPipeline(UnfreedMode.OFF).analyze(List.of(source));
        denied(() -> BridgeEnumConstants.discover(ordinary, types));
        var code = side.callables().stream().filter(method -> method.name().equals("code")).findFirst().orElseThrow();
        var dispatched = BridgeEnumDispatch.prove(artifact, sideType, code, mapping);
        check(dispatched.matches(artifact.program().orElseThrow(), sideType, code)
                && !dispatched.matches(changed.program().orElseThrow(), sideType, code), "stale enum dispatch accepted");
        check(dispatched.targets().stream().anyMatch(target -> target.constant().field().name().equals("SELL")
                && !target.callable().owner().equals(side.binaryName()) && !target.javaIdentity()), "constant override lost");
        check(dispatched.targets().stream().anyMatch(target -> target.constant().field().name().equals("BUY")
                && target.callable().owner().equals(side.binaryName()) && !target.javaIdentity()), "base constant implementation lost");
        var description = side.callables().stream().filter(method -> method.name().equals("toString")).findFirst().orElseThrow();
        var descriptions = BridgeEnumDispatch.prove(artifact, sideType, description, mapping);
        check(descriptions.targets().stream().allMatch(target -> target.javaIdentity() == target.constant().field().name().equals("BUY")),
                "constant override erased another constant's Java enum behavior: " + descriptions.targets().stream()
                        .map(target -> target.constant().field().name() + ":" + target.callable().linkage() + ":" + target.javaIdentity()).toList());
        for (String name : List.of("name", "ordinal", "equals", "hashCode", "compareTo")) {
            var method = side.callables().stream().filter(candidate -> candidate.name().equals(name)).findFirst().orElseThrow();
            check(BridgeEnumDispatch.prove(artifact, sideType, method, mapping).targets().stream().allMatch(BridgeEnumDispatch.Target::javaIdentity),
                    "inherited enum behavior became native: " + name);
            var emptyMethod = empty.callables().stream().filter(candidate -> candidate.name().equals(name)).findFirst().orElseThrow();
            check(BridgeEnumDispatch.prove(artifact, emptyType, emptyMethod, mapping).javaOnly(),
                    "empty enum inherited behavior became native: " + name);
        }
        var mode = facts.types().get("enuminventory.Side$Mode");
        var abstractCode = mode.callables().stream().filter(method -> method.name().equals("code")).findFirst().orElseThrow();
        check(abstractCode.target().isEmpty() && abstractCode.dispatchSlot().isPresent(), "abstract declaration fabricated a native body");
        var modeType = IrType.reference(mode.binaryName());
        var modeMapping = BridgeEnumConstants.discover(artifact, java.util.Set.of(modeType));
        var abstractTargets = BridgeEnumDispatch.prove(artifact, modeType, abstractCode, modeMapping);
        check(abstractTargets.targets().size() == 2 && abstractTargets.targets().stream().noneMatch(BridgeEnumDispatch.Target::javaIdentity)
                && abstractTargets.targets().stream().map(target -> target.callable().linkage()).distinct().count() == 2,
                "abstract constant-specific targets collapsed to a base body");
        denied(() -> BridgeEnumDispatch.prove(changed, sideType, code, mapping));
        denied(() -> BridgeEnumDispatch.prove(artifact, sideType, code, modeMapping));
        denied(() -> BridgeEnumDispatch.prove(artifact, modeType, code, modeMapping));
        var sourceValues = side.callables().stream().filter(method -> method.name().equals("values") && !method.synthetic()).findFirst().orElseThrow();
        denied(() -> BridgeEnumDispatch.prove(artifact, sideType, sourceValues, mapping));
        check(BridgeExportSurface.valuePreview(artifact, List.of("enuminventory")).surface().isEmpty()
                && BridgeExportSurface.concreteObjects(artifact, List.of("enuminventory")).surface().isEmpty(),
                "metadata alone admitted unfinished enum conversion");
        try {
            side.enumConstants().clear();
            throw new AssertionError("mutable enum inventory");
        } catch (UnsupportedOperationException expectedFailure) { /* Immutable semantic evidence. */ }
        Path directory = Files.createTempDirectory("bridge enum inventory ");
        try {
            var unit = SourceParser.parse(source).unit().orElseThrow();
            Path classes = directory.resolve("classes");
            for (var type : DeclaredTypes.in(unit)) {
                IronClass.write(classes.resolve(type.binaryName().replace('.', '/') + IronClass.EXTENSION), unit, type.binaryName());
            }
            Path archive = directory.resolve("enums.ironjar");
            IronJar.create(archive, List.of(classes));
            for (Path input : List.of(classes, archive)) {
                var loaded = new SourceSetLoader(List.of(), List.of(input)).loadBridge(List.of(), List.of("enuminventory"));
                check(loaded.diagnostics().isEmpty(), loaded.diagnostics().toString());
                check(shape(analyze(loaded.sources())).equals(expected), "enum inventory changed on reconstruction: " + input);
            }
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static CompilationArtifact analyze(List<SourceFile> sources) {
        var artifact = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(sources);
        check(artifact.valid(), artifact.diagnostics().toString());
        return artifact;
    }

    private static Map<String, String> shape(CompilationArtifact artifact) {
        var result = new TreeMap<String, String>();
        artifact.bridgeApiFacts().orElseThrow().types().values().stream()
                .filter(type -> type.packageName().equals("enuminventory")).forEach(type -> {
                    result.put(type.binaryName(), type.kind() + ":" + type.enumConstants());
                    type.callables().forEach(method -> result.put(type.binaryName() + ":" + method.name() + method.parameters(),
                            method.owner() + ":" + method.kind() + ":" + method.synthetic() + ":" + method.target()
                                    + ":" + method.dispatchSlot().map(slot -> slot.key() + slot.returnType() + slot.parameterTypes())));
                    if (type.kind() == BridgeApiFacts.Kind.ENUM && type.accessible()) {
                        var enumType = IrType.reference(type.binaryName());
                        var constants = BridgeEnumConstants.discover(artifact, java.util.Set.of(enumType));
                        type.callables().stream().filter(method -> !method.isStatic() && method.dispatchSlot().isPresent())
                                .forEach(method -> result.put(type.binaryName() + ":dispatch:" + method.name() + method.parameters(),
                                        BridgeEnumDispatch.prove(artifact, enumType, method, constants).targets().toString()));
                    }
                });
        var mapping = BridgeEnumConstants.discover(artifact, java.util.Set.of(
                IrType.reference("enuminventory.Side"), IrType.reference("enuminventory.Side$Empty")));
        mapping.constants().forEach((type, constants) -> result.put("mapping:" + type.displayName(), constants.toString()));
        result.put("initializers", mapping.initializers().toString());
        return result;
    }

    private static void denied(Runnable action) {
        try { action.run(); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("incomplete or stale enum mapping admitted");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
