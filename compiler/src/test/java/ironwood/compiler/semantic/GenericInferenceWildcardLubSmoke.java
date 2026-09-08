// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.ast.AccessModifier;
import ironwood.compiler.ast.CompilationUnit;
import ironwood.compiler.ast.DeclaredType;
import ironwood.compiler.ast.DeclaredTypes;
import ironwood.compiler.ast.InterfaceDeclaration;
import ironwood.compiler.ast.Parameter;
import ironwood.compiler.ast.TypeParameter;
import ironwood.compiler.ir.IrType;
import ironwood.compiler.ir.IrCallableKind;
import ironwood.compiler.lexer.LexResult;
import ironwood.compiler.lexer.Lexer;
import ironwood.compiler.parser.ParseResult;
import ironwood.compiler.parser.Parser;
import ironwood.compiler.source.SourceFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Package-local smoke coverage for principal wildcard-containing inference LUBs. */
public final class GenericInferenceWildcardLubSmoke {
    private static final IrType OBJECT = IrType.reference("ironwood.lang.Object");
    private static final IrType STRING = IrType.reference("ironwood.lang.String");

    private GenericInferenceWildcardLubSmoke() {
    }

    public static void main(String[] arguments) {
        Fixture fixture = fixture();
        IrType boxOfString = IrType.reference("Box", List.of(STRING));
        IrType boxOfObject = IrType.reference("Box", List.of(OBJECT));
        IrType boxOfWildcard = IrType.reference("Box", List.of(IrType.wildcard()));
        IrType pairOfStrings = IrType.reference("Pair", List.of(STRING, STRING));
        IrType pairOfObjects = IrType.reference("Pair", List.of(OBJECT, STRING));
        IrType pairOfWildcardAndString = IrType.reference("Pair",
                List.of(IrType.wildcard(), STRING));

        assertInference(fixture, List.of(boxOfString, boxOfObject), boxOfWildcard,
                "Box<String> and Box<Object> must infer Box<?>");
        assertInference(fixture, List.of(pairOfStrings, pairOfObjects),
                pairOfWildcardAndString,
                "least containment must preserve equal argument positions");
        assertInference(fixture, List.of(boxOfWildcard, boxOfString), boxOfWildcard,
                "an existing unbounded wildcard must remain principal");

        GenericInferenceSolver.Result repeated = infer(fixture,
                List.of(boxOfString, boxOfObject));
        if (!repeated.successful() || !repeated.instantiation().orElseThrow()
                .substitutions().get(fixture.variable().id()).equals(boxOfWildcard)) {
            throw new AssertionError("wildcard LUB inference must be deterministic");
        }
    }

    private static void assertInference(Fixture fixture, List<IrType> actualTypes,
                                        IrType expected, String message) {
        GenericInferenceSolver.Result result = infer(fixture, actualTypes);
        if (!result.successful()) {
            throw new AssertionError(message + ": " + result.failure());
        }
        IrType inferred = result.instantiation().orElseThrow().substitutions()
                .get(fixture.variable().id());
        if (!expected.equals(inferred)) {
            throw new AssertionError(message + "; expected " + expected.displayName()
                    + " but received " + inferred.displayName());
        }
    }

    private static GenericInferenceSolver.Result infer(Fixture fixture,
                                                        List<IrType> actualTypes) {
        return new GenericInferenceSolver(fixture.hierarchy()).infer(fixture.callable(),
                actualTypes, Optional.empty());
    }

    private static Fixture fixture() {
        SourceFile source = SourceFile.of("test/GenericInferenceWildcardLubSmoke.iron", """
                class Box<T> {}
                class Pair<A, B> {}
                """);
        LexResult lexed = new Lexer(source).lex();
        ParseResult parsed = new Parser(source, lexed.tokens()).parse();
        if (!lexed.diagnostics().isEmpty() || !parsed.diagnostics().isEmpty()
                || parsed.unit().isEmpty()) {
            throw new AssertionError("generic inference smoke fixture did not parse");
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
        ClassHierarchy hierarchy = new ClassHierarchy(types, resolver, genericTypes);
        genericTypes.attachHierarchy(hierarchy);

        TypeParameter sourceVariable = new TypeParameter("T", unit.span());
        TypeVariableSymbol variable = TypeVariableSymbol.declared("choose#T", sourceVariable);
        variable.setUpperBounds(List.of(OBJECT));
        CallableSymbol callable = new CallableSymbol("Inference", "choose",
                AccessModifier.PUBLIC, true, IrCallableKind.METHOD, variable.irType(),
                List.of(variable.irType(), variable.irType()), List.<Parameter>of(),
                Optional.empty(), Optional.empty(), Optional.empty(), false, false, false,
                unit.span(), unit.span(), "Inference.choose", Optional.empty(),
                "choose(Object,Object)", "Inference#choose", List.of(variable));
        return new Fixture(hierarchy, variable, callable);
    }

    private record Fixture(ClassHierarchy hierarchy, TypeVariableSymbol variable,
                           CallableSymbol callable) {
    }
}
