// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.ast.ClassDeclaration;
import ironwood.compiler.ast.CompilationUnit;
import ironwood.compiler.ast.DeclaredType;
import ironwood.compiler.ast.DeclaredTypes;
import ironwood.compiler.ast.InterfaceDeclaration;
import ironwood.compiler.ast.TypeName;
import ironwood.compiler.ast.TypeReference;
import ironwood.compiler.diagnostic.Diagnostic;
import ironwood.compiler.ir.IrType;
import ironwood.compiler.lexer.Lexer;
import ironwood.compiler.parser.Parser;
import ironwood.compiler.source.SourceFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Package-local smoke coverage for closed-world generic cast proofs. */
public final class GenericCastSafetySmoke {
    private static final IrType BASE = IrType.reference("Base");
    private static final IrType CHILD = IrType.reference("Child");
    private static final IrType OTHER = IrType.reference("Other");

    private GenericCastSafetySmoke() {
    }

    public static void main(String[] arguments) {
        Fixture safe = fixture("""
                class Base {}
                class Child extends Base {}
                interface Source<T> {}
                interface Mid<T> extends Source<T> {}
                interface Target<T> {}
                class Good<T extends Base> implements Mid<T>, Target<T> {}
                """);
        assertOutcome(safe, source(CHILD), target(CHILD), GenericCastSafety.Outcome.SAFE);
        assertOutcome(safe, source(CHILD), IrType.reference("Target",
                        List.of(IrType.wildcardExtends(BASE))),
                GenericCastSafety.Outcome.SAFE);

        Fixture mismatch = fixture("""
                class Base {}
                class Child extends Base {}
                interface Source<T> {}
                interface Mid<T> extends Source<T> {}
                interface Target<T> {}
                class Good<T extends Base> implements Mid<T>, Target<T> {}
                class Bad implements Source<Child>, Target<Base> {}
                """);
        GenericCastSafety.Result rejected = assertOutcome(mismatch, source(CHILD), target(CHILD),
                GenericCastSafety.Outcome.UNSAFE);
        if (!rejected.witness().orElseThrow().concreteType().equals("Bad")) {
            throw new AssertionError("unsafe witness must be deterministic: " + rejected);
        }

        Fixture boundedOut = fixture("""
                class Base {}
                class Other {}
                interface Source<T> {}
                interface Target<T> {}
                class BoundOnly<T extends Base> implements Source<T>, Target<Other> {}
                """);
        assertOutcome(boundedOut, source(OTHER), target(OTHER),
                GenericCastSafety.Outcome.IMPOSSIBLE);

        Fixture unconstrained = fixture("""
                class Base {}
                interface Marker {}
                interface Target<T> {}
                class Open<T extends Base> implements Marker, Target<T> {}
                """);
        assertOutcome(unconstrained, IrType.reference("Marker"), target(BASE),
                GenericCastSafety.Outcome.UNSAFE);
        assertOutcome(safe, IrType.reference("Source", List.of(IrType.wildcard())), target(CHILD),
                GenericCastSafety.Outcome.UNSAFE);
    }

    private static GenericCastSafety.Result assertOutcome(Fixture fixture, IrType source,
                                                           IrType target,
                                                           GenericCastSafety.Outcome expected) {
        GenericCastSafety.Result result = new GenericCastSafety(fixture.hierarchy())
                .prove(source, target);
        if (result.outcome() != expected) {
            throw new AssertionError("expected " + expected + " for " + source.displayName()
                    + " -> " + target.displayName() + " but received " + result);
        }
        return result;
    }

    private static IrType source(IrType argument) {
        return IrType.reference("Source", List.of(argument));
    }

    private static IrType target(IrType argument) {
        return IrType.reference("Target", List.of(argument));
    }

    private static Fixture fixture(String text) {
        SourceFile source = SourceFile.of("test/GenericCastSafetySmoke.iron", text);
        var lexed = new Lexer(source).lex();
        var parsed = new Parser(source, lexed.tokens()).parse();
        if (!lexed.diagnostics().isEmpty() || !parsed.diagnostics().isEmpty()
                || parsed.unit().isEmpty()) {
            throw new AssertionError("generic cast smoke fixture did not parse: "
                    + lexed.diagnostics() + parsed.diagnostics());
        }
        CompilationUnit unit = parsed.unit().orElseThrow();
        Map<String, TypeSymbol> types = new LinkedHashMap<>();
        for (DeclaredType declaration : DeclaredTypes.in(unit)) {
            TypeSymbol symbol = new TypeSymbol(declaration,
                    declaration.declaration() instanceof InterfaceDeclaration);
            types.put(symbol.name(), symbol);
        }
        TypeResolver resolver = new TypeResolver(types);
        GenericTypeSystem genericTypes = new GenericTypeSystem(types, resolver);
        List<Diagnostic> diagnostics = new ArrayList<>();
        genericTypes.initializeDeclaredBounds(diagnostics);
        ClassHierarchy hierarchy = new ClassHierarchy(types, resolver, genericTypes);
        genericTypes.attachHierarchy(hierarchy);
        linkHierarchy(types);
        genericTypes.markHierarchyReady(diagnostics);
        if (!diagnostics.isEmpty()) {
            throw new AssertionError("generic cast smoke hierarchy is invalid: " + diagnostics);
        }
        return new Fixture(hierarchy);
    }

    private static void linkHierarchy(Map<String, TypeSymbol> types) {
        for (TypeSymbol symbol : types.values()) {
            if (symbol.declaration() instanceof ClassDeclaration declaration) {
                declaration.superclass().ifPresent(parent -> {
                    TypeSymbol parentSymbol = types.get(parent.name());
                    symbol.setSuperclass(parentSymbol);
                    symbol.setSuperclassType(resolve(symbol, parent, types));
                });
                List<TypeSymbol> interfaces = declaration.implementedInterfaces().stream()
                        .map(parent -> types.get(parent.name())).toList();
                symbol.setDirectInterfaces(interfaces);
                symbol.setDirectInterfaceTypes(declaration.implementedInterfaces().stream()
                        .map(parent -> resolve(symbol, parent, types)).toList());
            } else {
                InterfaceDeclaration declaration = (InterfaceDeclaration) symbol.declaration();
                symbol.setDirectInterfaces(declaration.extendedInterfaces().stream()
                        .map(parent -> types.get(parent.name())).toList());
                symbol.setDirectInterfaceTypes(declaration.extendedInterfaces().stream()
                        .map(parent -> resolve(symbol, parent, types)).toList());
            }
        }
    }

    private static IrType resolve(TypeSymbol owner, TypeReference reference,
                                  Map<String, TypeSymbol> types) {
        return IrType.reference(types.get(reference.name()).name(), reference.typeArguments().stream()
                .map(argument -> resolve(owner, argument, types)).toList());
    }

    private static IrType resolve(TypeSymbol owner, TypeName type,
                                  Map<String, TypeSymbol> types) {
        if (type.kind() == TypeName.Kind.ARRAY) {
            return IrType.array(resolve(owner, type.elementType(), types));
        }
        if (type.kind() == TypeName.Kind.WILDCARD) {
            return switch (type.wildcardKind()) {
                case UNBOUNDED -> IrType.wildcard();
                case EXTENDS -> IrType.wildcardExtends(
                        resolve(owner, type.wildcardBound(), types));
                case SUPER -> IrType.wildcardSuper(
                        resolve(owner, type.wildcardBound(), types));
            };
        }
        IrType variable = owner.typeParameter(type.referenceName()).orElse(null);
        if (variable != null) {
            return variable;
        }
        return IrType.reference(types.get(type.referenceName()).name(), type.typeArguments().stream()
                .map(argument -> resolve(owner, argument, types)).toList());
    }

    private record Fixture(ClassHierarchy hierarchy) {
    }
}
