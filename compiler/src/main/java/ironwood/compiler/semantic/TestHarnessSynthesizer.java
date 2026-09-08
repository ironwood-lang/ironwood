// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.ast.AccessModifier;
import ironwood.compiler.ast.Block;
import ironwood.compiler.ast.BreakStatement;
import ironwood.compiler.ast.CallExpression;
import ironwood.compiler.ast.ClassDeclaration;
import ironwood.compiler.ast.ExpressionStatement;
import ironwood.compiler.ast.IntegerLiteralExpression;
import ironwood.compiler.ast.LocalVariableDeclaration;
import ironwood.compiler.ast.MethodDeclaration;
import ironwood.compiler.ast.NameExpression;
import ironwood.compiler.ast.NewExpression;
import ironwood.compiler.ast.Parameter;
import ironwood.compiler.ast.ReturnStatement;
import ironwood.compiler.ast.Statement;
import ironwood.compiler.ast.StringLiteralExpression;
import ironwood.compiler.ast.SwitchGroup;
import ironwood.compiler.ast.SwitchLabel;
import ironwood.compiler.ast.SwitchStatement;
import ironwood.compiler.ast.ThrowStatement;
import ironwood.compiler.ast.TypeName;
import ironwood.compiler.diagnostic.Diagnostic;
import ironwood.compiler.source.SourceSpan;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Validates compiler-owned test directives and creates their ordinary harness methods. */
final class TestHarnessSynthesizer {
    private static final String TEST_SUITE_TYPE = "ironwood.testing.TestSuite";
    private static final String TEST_RUNNER_TYPE = "ironwood.testing.TestRunner";

    private TestHarnessSynthesizer() {
    }

    static List<MethodDeclaration> synthesize(TypeSymbol type,
                                               ClassDeclaration declaration,
                                               ClassHierarchy hierarchy,
                                               List<Diagnostic> diagnostics) {
        List<MethodDeclaration> tests = declaration.methods().stream()
                .filter(MethodDeclaration::hasTestDirective).toList();
        if (tests.isEmpty()) {
            return List.of();
        }

        boolean validSuite = true;
        if (!hierarchy.isSubtype(type.name(), TEST_SUITE_TYPE)) {
            diagnostics.add(error(type, tests.getFirst().nameSpan(),
                    "@Test may only be used on methods declared in a TestSuite subclass"));
            validSuite = false;
        }
        if (type.isAbstract()) {
            diagnostics.add(error(type, declaration.nameSpan(),
                    "a class declaring @Test methods must be concrete"));
            validSuite = false;
        }
        if (!declaration.typeParameters().isEmpty()) {
            diagnostics.add(error(type, declaration.typeParameters().getFirst().span(),
                    "a class declaring @Test methods cannot declare type parameters"));
            validSuite = false;
        }
        if (!type.isTopLevel() && !(type.isMemberType() && declaration.isStatic())) {
            diagnostics.add(error(type, declaration.nameSpan(),
                    "a class declaring @Test methods must be top-level or static"));
            validSuite = false;
        }
        boolean hasNoArgumentConstructor = type.constructors().stream()
                .anyMatch(constructor -> constructor.parameterTypes().isEmpty());
        if (!hasNoArgumentConstructor) {
            diagnostics.add(error(type, declaration.nameSpan(),
                    "a class declaring @Test methods requires a no-argument constructor"));
            validSuite = false;
        }
        if (declaresTestDispatch(declaration)) {
            diagnostics.add(error(type, declaration.nameSpan(),
                    "a class declaring @Test methods cannot declare run(int)"));
            validSuite = false;
        }
        if (declaresMainEntry(declaration)) {
            diagnostics.add(error(type, declaration.nameSpan(),
                    "a class declaring @Test methods cannot declare main(String[] args)"));
            validSuite = false;
        }

        List<MethodDeclaration> validTests = new ArrayList<>();
        for (MethodDeclaration test : tests) {
            boolean valid = true;
            if (test.isStatic()) {
                diagnostics.add(error(type, test.nameSpan(), "@Test method '" + test.name()
                        + "' must be an instance method"));
                valid = false;
            }
            if (test.isAbstract() || test.body().isEmpty()) {
                diagnostics.add(error(type, test.nameSpan(), "@Test method '" + test.name()
                        + "' must have a body"));
                valid = false;
            }
            if (test.returnType().kind() != TypeName.Kind.VOID) {
                diagnostics.add(error(type, test.nameSpan(), "@Test method '" + test.name()
                        + "' must return void"));
                valid = false;
            }
            if (!test.parameters().isEmpty()) {
                diagnostics.add(error(type, test.nameSpan(), "@Test method '" + test.name()
                        + "' cannot declare parameters"));
                valid = false;
            }
            if (!test.typeParameters().isEmpty()) {
                diagnostics.add(error(type, test.nameSpan(), "@Test method '" + test.name()
                        + "' cannot declare type parameters"));
                valid = false;
            }
            if (valid) {
                validTests.add(test);
            }
        }
        if (!validSuite) {
            return List.of();
        }
        return List.of(testDispatchMethod(validTests, declaration),
                testMainMethod(validTests, declaration));
    }

    private static Diagnostic error(TypeSymbol type, SourceSpan span, String message) {
        return Diagnostic.error(type.source(), span, message);
    }

    private static boolean declaresTestDispatch(ClassDeclaration declaration) {
        return declaration.methods().stream().anyMatch(method -> method.name().equals("run")
                && method.parameters().size() == 1
                && method.parameters().getFirst().type().kind() == TypeName.Kind.INT);
    }

    private static boolean declaresMainEntry(ClassDeclaration declaration) {
        return declaration.methods().stream().anyMatch(method -> method.name().equals("main")
                && method.parameters().size() == 1
                && isStringArray(method.parameters().getFirst().type()));
    }

    private static boolean isStringArray(TypeName type) {
        return type.kind() == TypeName.Kind.ARRAY
                && type.elementType().kind() == TypeName.Kind.REFERENCE
                && (type.elementType().referenceName().equals("String")
                || type.elementType().referenceName().equals("ironwood.lang.String"));
    }

    private static MethodDeclaration testDispatchMethod(List<MethodDeclaration> tests,
                                                        ClassDeclaration declaration) {
        SourceSpan span = declaration.nameSpan();
        List<SwitchGroup> groups = new ArrayList<>();
        for (int index = 0; index < tests.size(); index++) {
            MethodDeclaration test = tests.get(index);
            ExpressionStatement call = new ExpressionStatement(
                    new CallExpression(Optional.empty(), test.name(), test.nameSpan(),
                            List.of(), test.span()), test.span());
            groups.add(new SwitchGroup(List.of(new SwitchLabel(Optional.of(
                    new IntegerLiteralExpression(Integer.toString(index), test.nameSpan())),
                    test.nameSpan())), List.of(call, new BreakStatement(test.span())),
                    test.span()));
        }
        ThrowStatement unknown = new ThrowStatement(new NewExpression(
                "ironwood.lang.IllegalArgumentException", span,
                List.of(new StringLiteralExpression("unknown generated test case", span)), span),
                span);
        groups.add(new SwitchGroup(List.of(new SwitchLabel(Optional.empty(), span)),
                List.of(unknown), span));
        Parameter testCase = new Parameter(TypeName.primitive(TypeName.Kind.INT, span),
                "testCase", span, span);
        Block body = new Block(List.of(new SwitchStatement(
                new NameExpression("testCase", span), groups, span)), span);
        return new MethodDeclaration(AccessModifier.PUBLIC, false, false, false,
                false, false, List.of(), TypeName.primitive(TypeName.Kind.VOID, span),
                "run", span, List.of(testCase),
                List.of(TypeName.reference("ironwood.lang.Throwable", span)),
                Optional.of(body), span);
    }

    private static MethodDeclaration testMainMethod(List<MethodDeclaration> tests,
                                                    ClassDeclaration declaration) {
        SourceSpan span = declaration.nameSpan();
        TypeName runnerType = TypeName.reference(TEST_RUNNER_TYPE, span);
        TypeName suiteType = TypeName.reference(declaration.name(), span);
        List<Statement> statements = new ArrayList<>();
        statements.add(new LocalVariableDeclaration(runnerType, "testRunner", span,
                new NewExpression(runnerType, List.of(), span), span));
        statements.add(new LocalVariableDeclaration(suiteType, "testSuite", span,
                new NewExpression(suiteType, List.of(), span), span));
        for (int index = 0; index < tests.size(); index++) {
            MethodDeclaration test = tests.get(index);
            CallExpression run = new CallExpression(Optional.of(
                    new NameExpression("testRunner", span)), "run", span,
                    List.of(new StringLiteralExpression(test.name(), test.nameSpan()),
                            new NameExpression("testSuite", span),
                            new IntegerLiteralExpression(Integer.toString(index),
                                    test.nameSpan())), test.span());
            statements.add(new ExpressionStatement(run, test.span()));
        }
        CallExpression finish = new CallExpression(Optional.of(
                new NameExpression("testRunner", span)), "finish", span, List.of(), span);
        statements.add(new ReturnStatement(Optional.of(finish), span));
        Parameter arguments = new Parameter(TypeName.array(
                TypeName.reference("ironwood.lang.String", span), span), "args", span, span);
        return new MethodDeclaration(AccessModifier.PUBLIC, true, false, false,
                false, false, List.of(), TypeName.primitive(TypeName.Kind.INT, span),
                "main", span, List.of(arguments), List.of(),
                Optional.of(new Block(statements, span)), span);
    }
}
