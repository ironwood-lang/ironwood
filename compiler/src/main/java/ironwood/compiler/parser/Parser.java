// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.parser;

import ironwood.compiler.ast.AccessModifier;
import ironwood.compiler.ast.AnonymousClassBody;
import ironwood.compiler.ast.ArrayAccessExpression;
import ironwood.compiler.ast.ArrayCreationExpression;
import ironwood.compiler.ast.ArrayInitializerExpression;
import ironwood.compiler.ast.AssignmentExpression;
import ironwood.compiler.ast.AssignmentOperator;
import ironwood.compiler.ast.AssignmentStatement;
import ironwood.compiler.ast.BinaryExpression;
import ironwood.compiler.ast.BinaryOperator;
import ironwood.compiler.ast.Block;
import ironwood.compiler.ast.BooleanLiteralExpression;
import ironwood.compiler.ast.BreakStatement;
import ironwood.compiler.ast.CallExpression;
import ironwood.compiler.ast.CatchClause;
import ironwood.compiler.ast.CastExpression;
import ironwood.compiler.ast.CharacterLiteralExpression;
import ironwood.compiler.ast.ClassDeclaration;
import ironwood.compiler.ast.CompilationUnit;
import ironwood.compiler.ast.ConditionalExpression;
import ironwood.compiler.ast.ContinueStatement;
import ironwood.compiler.ast.DoWhileStatement;
import ironwood.compiler.ast.DestructorDeclaration;
import ironwood.compiler.ast.EmptyStatement;
import ironwood.compiler.ast.EnhancedForStatement;
import ironwood.compiler.ast.ConstructorDeclaration;
import ironwood.compiler.ast.EnumConstant;
import ironwood.compiler.ast.Expression;
import ironwood.compiler.ast.ExpressionStatement;
import ironwood.compiler.ast.FieldAccessExpression;
import ironwood.compiler.ast.FloatingLiteralExpression;
import ironwood.compiler.ast.FieldDeclaration;
import ironwood.compiler.ast.ForStatement;
import ironwood.compiler.ast.FreeStatement;
import ironwood.compiler.ast.IfStatement;
import ironwood.compiler.ast.LabeledStatement;
import ironwood.compiler.ast.ImportDeclaration;
import ironwood.compiler.ast.InstanceOfExpression;
import ironwood.compiler.ast.InstanceInitialization;
import ironwood.compiler.ast.IntegerLiteralExpression;
import ironwood.compiler.ast.InterfaceDeclaration;
import ironwood.compiler.ast.InterfaceMethodDeclaration;
import ironwood.compiler.ast.InterfaceMethodKind;
import ironwood.compiler.ast.InterfaceSuperExpression;
import ironwood.compiler.ast.LocalClassDeclaration;
import ironwood.compiler.ast.LocalVariableDeclaration;
import ironwood.compiler.ast.MethodDeclaration;
import ironwood.compiler.ast.ModernSwitchStatement;
import ironwood.compiler.ast.NameExpression;
import ironwood.compiler.ast.NewExpression;
import ironwood.compiler.ast.NullLiteralExpression;
import ironwood.compiler.ast.Parameter;
import ironwood.compiler.ast.PackageDeclaration;
import ironwood.compiler.ast.QualifiedThisExpression;
import ironwood.compiler.ast.QualifiedSuperConstructorExpression;
import ironwood.compiler.ast.ReturnStatement;
import ironwood.compiler.ast.Statement;
import ironwood.compiler.ast.StaticInitialization;
import ironwood.compiler.ast.SuperConstructorInvocation;
import ironwood.compiler.ast.SuperExpression;
import ironwood.compiler.ast.SwitchGroup;
import ironwood.compiler.ast.SwitchExpression;
import ironwood.compiler.ast.SwitchLabel;
import ironwood.compiler.ast.SwitchRule;
import ironwood.compiler.ast.SwitchRuleBlock;
import ironwood.compiler.ast.SwitchRuleBody;
import ironwood.compiler.ast.SwitchRuleExpression;
import ironwood.compiler.ast.SwitchRuleThrow;
import ironwood.compiler.ast.SwitchStatement;
import ironwood.compiler.ast.StringLiteralExpression;
import ironwood.compiler.ast.ThisExpression;
import ironwood.compiler.ast.ThisConstructorInvocation;
import ironwood.compiler.ast.ThrowStatement;
import ironwood.compiler.ast.TryStatement;
import ironwood.compiler.ast.TypeName;
import ironwood.compiler.ast.TypePatternBinding;
import ironwood.compiler.ast.TypeParameter;
import ironwood.compiler.ast.TypeDeclaration;
import ironwood.compiler.ast.TypeReference;
import ironwood.compiler.ast.UnaryExpression;
import ironwood.compiler.ast.UnaryOperator;
import ironwood.compiler.ast.UpdateExpression;
import ironwood.compiler.ast.UpdateOperator;
import ironwood.compiler.ast.WhileStatement;
import ironwood.compiler.ast.YieldStatement;
import ironwood.compiler.diagnostic.Diagnostic;
import ironwood.compiler.lexer.Token;
import ironwood.compiler.lexer.TokenKind;
import ironwood.compiler.source.SourceFile;
import ironwood.compiler.source.SourceSpan;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class Parser {
    private final SourceFile source;
    private final List<Token> tokens;
    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private int current;

    public Parser(SourceFile source, List<Token> tokens) {
        this.source = source;
        this.tokens = tokens;
    }

    public ParseResult parse() {
        CompilationUnit unit = parseCompilationUnit();
        return new ParseResult(Optional.ofNullable(unit), diagnostics);
    }

    private CompilationUnit parseCompilationUnit() {
        List<TypeDeclaration> declarations = new ArrayList<>();
        Token first = peek();
        Optional<PackageDeclaration> packageDeclaration = Optional.empty();
        if (match(TokenKind.PACKAGE)) {
            packageDeclaration = Optional.ofNullable(parsePackageDeclaration(previous()));
        }
        List<ImportDeclaration> imports = new ArrayList<>();
        while (match(TokenKind.IMPORT)) {
            ImportDeclaration declaration = parseImportDeclaration(previous());
            if (declaration != null) {
                imports.add(declaration);
            }
        }
        while (!check(TokenKind.EOF)) {
            int before = current;
            TypeDeclaration declaration = parseTypeDeclaration();
            if (declaration != null) {
                declarations.add(declaration);
            }
            if (current == before) {
                advance();
            }
        }
        if (declarations.isEmpty()) {
            if (diagnostics.isEmpty()) {
                diagnostics.add(error(first, "expected a class declaration"));
            }
            return null;
        }
        SourceSpan start = packageDeclaration.map(PackageDeclaration::span)
                .orElseGet(() -> imports.isEmpty() ? declarations.getFirst().span() : imports.getFirst().span());
        return new CompilationUnit(source, packageDeclaration, imports, declarations,
                new SourceSpan(start.start(), declarations.getLast().span().end()));
    }

    private PackageDeclaration parsePackageDeclaration(Token keyword) {
        QualifiedName name = parseQualifiedName("expected package name after 'package'", false);
        Token semicolon = expect(TokenKind.SEMICOLON, "expected ';' after package declaration");
        if (name == null) {
            synchronizeTopLevel();
            return null;
        }
        SourceSpan end = semicolon == null ? name.span() : semicolon.span();
        return new PackageDeclaration(name.text(), name.span(),
                new SourceSpan(keyword.span().start(), end.end()));
    }

    private ImportDeclaration parseImportDeclaration(Token keyword) {
        boolean staticImport = match(TokenKind.STATIC);
        QualifiedName name = parseQualifiedName(staticImport
                ? "expected type name after 'import static'"
                : "expected type or package name after 'import'", true);
        Token semicolon = expect(TokenKind.SEMICOLON, "expected ';' after import declaration");
        if (name == null) {
            synchronizeTopLevel();
            return null;
        }
        if (staticImport && !name.wildcard() && name.text().indexOf('.') < 0) {
            diagnostics.add(Diagnostic.error(source, name.span(),
                    "single-static import requires a type name followed by a member name"));
        }
        SourceSpan end = semicolon == null ? name.span() : semicolon.span();
        return new ImportDeclaration(name.text(), name.wildcard(), staticImport, name.span(),
                new SourceSpan(keyword.span().start(), end.end()));
    }

    private TypeDeclaration parseTypeDeclaration() {
        Token start = peek();
        Modifiers modifiers = parseModifiers();
        if (modifiers.isStatic()) {
            diagnostics.add(error(modifiers.staticToken(), "top-level types cannot be static"));
        }
        if (modifiers.isDefault()) {
            diagnostics.add(error(modifiers.defaultToken(), "top-level types cannot be default"));
        }
        rejectOverrideDirective(modifiers);
        if (match(TokenKind.CLASS)) {
            return parseClass(start, modifiers, true, false);
        }
        if (match(TokenKind.ENUM)) {
            return parseEnum(start, modifiers, true, false);
        }
        if (match(TokenKind.INTERFACE)) {
            if (modifiers.isFinal()) {
                diagnostics.add(error(modifiers.finalToken(), "interfaces cannot be final"));
            }
            return parseInterface(start, modifiers, true, false);
        }
        diagnostics.add(error(peek(),
                "expected 'class', 'enum', or 'interface' at the start of a type declaration"));
        synchronizeTopLevel();
        return null;
    }

    private ClassDeclaration parseClass(Token start, Modifiers modifiers,
                                        boolean topLevel, boolean enclosingInterface) {
        Token className = expect(TokenKind.IDENTIFIER, "expected class name after 'class'");
        List<TypeParameter> typeParameters = className == null
                ? List.of() : parseTypeParameters();
        Optional<TypeReference> superclass = Optional.empty();
        if (match(TokenKind.EXTENDS)) {
            superclass = Optional.ofNullable(parseTypeReference(
                    "expected superclass name after 'extends'"));
        }
        List<TypeReference> interfaces = new ArrayList<>();
        if (match(TokenKind.IMPLEMENTS)) {
            interfaces.addAll(parseTypeReferenceList("expected interface name after 'implements'"));
        }
        Token leftBrace = expect(TokenKind.LEFT_BRACE, "expected '{' after class name");
        if (className == null || leftBrace == null) {
            synchronizeTopLevel();
            return null;
        }

        List<FieldDeclaration> fields = new ArrayList<>();
        List<InstanceInitialization> instanceInitializations = new ArrayList<>();
        List<StaticInitialization> staticInitializations = new ArrayList<>();
        List<ConstructorDeclaration> constructors = new ArrayList<>();
        List<DestructorDeclaration> destructors = new ArrayList<>();
        List<MethodDeclaration> methods = new ArrayList<>();
        List<TypeDeclaration> memberTypes = new ArrayList<>();
        while (!check(TokenKind.RIGHT_BRACE) && !check(TokenKind.EOF)) {
            int before = current;
            parseMember(className.lexeme(), fields, instanceInitializations, staticInitializations,
                    constructors, destructors, methods, memberTypes, false, false);
            if (current == before) {
                advance();
            }
        }

        Token rightBrace = expect(TokenKind.RIGHT_BRACE, "expected '}' to close class body");
        if (rightBrace == null) {
            return null;
        }
        AccessModifier access = enclosingInterface ? AccessModifier.PUBLIC : modifiers.accessModifier();
        return new ClassDeclaration(access, !topLevel && (modifiers.isStatic() || enclosingInterface),
                modifiers.isAbstract(), modifiers.isFinal(),
                className.lexeme(), className.span(),
                typeParameters, superclass, interfaces, fields, instanceInitializations,
                staticInitializations,
                constructors, destructors.stream().findFirst(), methods, memberTypes,
                new SourceSpan(start.span().start(), rightBrace.span().end()));
    }

    private ClassDeclaration parseEnum(Token start, Modifiers modifiers,
                                       boolean topLevel, boolean enclosingInterface) {
        if (modifiers.isAbstract()) {
            diagnostics.add(error(modifiers.abstractToken(), "enums cannot be abstract"));
        }
        if (modifiers.isFinal()) {
            diagnostics.add(error(modifiers.finalToken(),
                    "enums are implicitly final and cannot declare the 'final' modifier"));
        }
        if (modifiers.isDefault()) {
            diagnostics.add(error(modifiers.defaultToken(), "enums cannot be default"));
        }
        Token enumName = expect(TokenKind.IDENTIFIER, "expected enum name after 'enum'");
        List<TypeParameter> typeParameters = enumName == null
                ? List.of() : parseTypeParameters();
        if (!typeParameters.isEmpty()) {
            diagnostics.add(Diagnostic.error(source, typeParameters.getFirst().span(),
                    "enum declarations cannot declare type parameters"));
        }
        if (match(TokenKind.EXTENDS)) {
            Token extendsToken = previous();
            parseTypeReference("expected type name after 'extends'");
            diagnostics.add(error(extendsToken,
                    "enums cannot declare an explicit superclass"));
        }
        List<TypeReference> interfaces = new ArrayList<>();
        if (match(TokenKind.IMPLEMENTS)) {
            interfaces.addAll(parseTypeReferenceList(
                    "expected interface name after 'implements'"));
        }
        Token leftBrace = expect(TokenKind.LEFT_BRACE, "expected '{' after enum name");
        if (enumName == null || leftBrace == null) {
            synchronizeTopLevel();
            return null;
        }

        List<EnumConstant> constants = new ArrayList<>();
        if (!check(TokenKind.RIGHT_BRACE) && !check(TokenKind.SEMICOLON)) {
            while (!check(TokenKind.RIGHT_BRACE) && !check(TokenKind.SEMICOLON)
                    && !check(TokenKind.EOF)) {
                Token constantName = expect(TokenKind.IDENTIFIER,
                        "expected enum constant name");
                if (constantName == null) {
                    synchronizeMember();
                    break;
                }
                List<Expression> arguments = List.of();
                SourceSpan end = constantName.span();
                if (match(TokenKind.LEFT_PAREN)) {
                    arguments = parseArguments();
                    Token rightParen = expect(TokenKind.RIGHT_PAREN,
                            "expected ')' after enum constant arguments");
                    if (rightParen != null) {
                        end = rightParen.span();
                    }
                }
                Optional<AnonymousClassBody> classBody = Optional.empty();
                if (check(TokenKind.LEFT_BRACE)) {
                    AnonymousClassBody body = parseAnonymousClassBody(true);
                    classBody = Optional.ofNullable(body);
                    if (body != null) {
                        end = body.span();
                    }
                }
                constants.add(new EnumConstant(constantName.lexeme(), constantName.span(),
                        arguments, classBody,
                        new SourceSpan(constantName.span().start(), end.end())));
                if (!match(TokenKind.COMMA)) {
                    break;
                }
                if (check(TokenKind.RIGHT_BRACE) || check(TokenKind.SEMICOLON)) {
                    break;
                }
            }
        }

        List<FieldDeclaration> fields = new ArrayList<>();
        List<InstanceInitialization> instanceInitializations = new ArrayList<>();
        List<StaticInitialization> staticInitializations = new ArrayList<>();
        List<ConstructorDeclaration> constructors = new ArrayList<>();
        List<DestructorDeclaration> destructors = new ArrayList<>();
        List<MethodDeclaration> methods = new ArrayList<>();
        List<TypeDeclaration> memberTypes = new ArrayList<>();
        if (match(TokenKind.SEMICOLON)) {
            while (!check(TokenKind.RIGHT_BRACE) && !check(TokenKind.EOF)) {
                int before = current;
                parseMember(enumName.lexeme(), fields, instanceInitializations,
                        staticInitializations, constructors, destructors, methods, memberTypes,
                        false, true);
                if (current == before) {
                    advance();
                }
            }
        } else if (!check(TokenKind.RIGHT_BRACE)) {
            diagnostics.add(error(peek(),
                    "expected ',' or ';' after enum constant list"));
            synchronizeMember();
        }

        Token rightBrace = expect(TokenKind.RIGHT_BRACE, "expected '}' to close enum body");
        if (rightBrace == null) {
            return null;
        }
        AccessModifier access = enclosingInterface ? AccessModifier.PUBLIC
                : modifiers.accessModifier();
        return new ClassDeclaration(access, !topLevel, false, true,
                enumName.lexeme(), enumName.span(), List.of(), Optional.empty(), interfaces,
                fields, instanceInitializations, staticInitializations, constructors,
                Optional.empty(), methods,
                memberTypes, true, constants,
                new SourceSpan(start.span().start(), rightBrace.span().end()));
    }

    private InterfaceDeclaration parseInterface(Token start, Modifiers modifiers,
                                                boolean topLevel, boolean enclosingInterface) {
        Token interfaceName = expect(TokenKind.IDENTIFIER, "expected interface name after 'interface'");
        List<TypeParameter> typeParameters = interfaceName == null
                ? List.of() : parseTypeParameters();
        List<TypeReference> extendedInterfaces = new ArrayList<>();
        if (match(TokenKind.EXTENDS)) {
            extendedInterfaces.addAll(parseTypeReferenceList("expected interface name after 'extends'"));
        }
        Token leftBrace = expect(TokenKind.LEFT_BRACE, "expected '{' after interface name");
        if (interfaceName == null || leftBrace == null) {
            synchronizeTopLevel();
            return null;
        }

        List<FieldDeclaration> fields = new ArrayList<>();
        List<InterfaceMethodDeclaration> methods = new ArrayList<>();
        List<TypeDeclaration> memberTypes = new ArrayList<>();
        while (!check(TokenKind.RIGHT_BRACE) && !check(TokenKind.EOF)) {
            int before = current;
            parseInterfaceMember(fields, methods, memberTypes);
            if (current == before) {
                advance();
            }
        }
        Token rightBrace = expect(TokenKind.RIGHT_BRACE, "expected '}' to close interface body");
        if (rightBrace == null) {
            return null;
        }
        AccessModifier access = enclosingInterface ? AccessModifier.PUBLIC : modifiers.accessModifier();
        return new InterfaceDeclaration(access, !topLevel, interfaceName.lexeme(),
                interfaceName.span(), typeParameters, extendedInterfaces, fields, methods, memberTypes,
                new SourceSpan(start.span().start(), rightBrace.span().end()));
    }

    private List<TypeReference> parseTypeReferenceList(String message) {
        List<TypeReference> references = new ArrayList<>();
        do {
            TypeReference reference = parseTypeReference(message);
            if (reference == null) {
                break;
            }
            references.add(reference);
        } while (match(TokenKind.COMMA));
        return references;
    }

    private List<TypeParameter> parseTypeParameters() {
        if (!match(TokenKind.LESS)) {
            return List.of();
        }
        List<TypeParameter> parameters = new ArrayList<>();
        if (check(TokenKind.GREATER)) {
            diagnostics.add(error(peek(), "generic type declarations require at least one type parameter"));
            advance();
            return parameters;
        }
        do {
            Token parameter = expect(TokenKind.IDENTIFIER, "expected type parameter name");
            if (parameter == null) {
                break;
            }
            List<TypeName> upperBounds = new ArrayList<>();
            if (match(TokenKind.EXTENDS)) {
                do {
                    TypeName bound = parseType(false, "expected type after type-parameter bound");
                    if (bound != null) {
                        upperBounds.add(bound);
                    }
                } while (match(TokenKind.AMPERSAND));
            }
            parameters.add(new TypeParameter(parameter.lexeme(), upperBounds, parameter.span()));
        } while (match(TokenKind.COMMA));
        expect(TokenKind.GREATER, "expected '>' after type parameter list");
        return parameters;
    }

    private TypeReference parseTypeReference(String message) {
        ParameterizedQualifiedName name = parseParameterizedQualifiedName(message);
        if (name == null) {
            return null;
        }
        return new TypeReference(name.text(), name.typeArguments(),
                name.typeArgumentSegmentCounts(), name.span());
    }

    private void parseInterfaceMember(List<FieldDeclaration> fields,
                                      List<InterfaceMethodDeclaration> methods,
                                      List<TypeDeclaration> memberTypes) {
        Token start = peek();
        Modifiers modifiers = parseModifiers();
        if (match(TokenKind.DESTRUCTOR)) {
            rejectOverrideDirective(modifiers);
            Block body = parseBlock("expected '{' after 'destructor'",
                    "expected '}' to close destructor body");
            diagnostics.add(error(start, "interfaces cannot declare destructors"));
            if (body == null) {
                synchronizeMember();
            }
            return;
        }
        if (check(TokenKind.LEFT_BRACE)) {
            rejectOverrideDirective(modifiers);
            Block block = parseBlock("expected '{' before interface initializer block",
                    "expected '}' to close interface initializer block");
            diagnostics.add(error(modifiers.staticToken() == null ? start : modifiers.staticToken(),
                    "interfaces cannot declare initializer blocks"));
            if (block == null) {
                synchronizeMember();
            }
            return;
        }
        if (check(TokenKind.CLASS) || check(TokenKind.ENUM) || check(TokenKind.INTERFACE)) {
            rejectOverrideDirective(modifiers);
            if (modifiers.accessModifier() == AccessModifier.PRIVATE
                    || modifiers.accessModifier() == AccessModifier.PROTECTED) {
                diagnostics.add(error(modifiers.accessToken(),
                        "types declared in an interface must be public"));
            }
            if (modifiers.isDefault()) {
                diagnostics.add(error(modifiers.defaultToken(), "nested types cannot be default"));
            }
            if (match(TokenKind.CLASS)) {
                TypeDeclaration declaration = parseClass(start, modifiers, false, true);
                if (declaration != null) {
                    memberTypes.add(declaration);
                }
            } else if (match(TokenKind.ENUM)) {
                TypeDeclaration declaration = parseEnum(start, modifiers, false, true);
                if (declaration != null) {
                    memberTypes.add(declaration);
                }
            } else {
                advance();
                if (modifiers.isFinal()) {
                    diagnostics.add(error(modifiers.finalToken(), "interfaces cannot be final"));
                }
                TypeDeclaration declaration = parseInterface(start, modifiers, false, true);
                if (declaration != null) {
                    memberTypes.add(declaration);
                }
            }
            return;
        }
        List<TypeParameter> typeParameters = parseTypeParameters();
        TypeName type = parseType(true, "expected interface field type or method return type");
        Token name = expect(TokenKind.IDENTIFIER, "expected interface member name after type");
        if (type == null || name == null) {
            synchronizeMember();
            return;
        }
        if (!match(TokenKind.LEFT_PAREN)) {
            rejectOverrideDirective(modifiers);
            if (!typeParameters.isEmpty()) {
                diagnostics.add(Diagnostic.error(source, typeParameters.getFirst().span(),
                        "type parameters may only be declared by an interface method"));
            }
            if (type.kind() == TypeName.Kind.VOID) {
                diagnostics.add(error(name, "interface fields cannot have type void"));
            }
            if (modifiers.accessModifier() == AccessModifier.PRIVATE
                    || modifiers.accessModifier() == AccessModifier.PROTECTED) {
                diagnostics.add(error(modifiers.accessToken(),
                        "interface fields must be public static final"));
            }
            if (modifiers.isAbstract()) {
                diagnostics.add(error(modifiers.abstractToken(), "interface fields cannot be abstract"));
            }
            if (modifiers.isDefault()) {
                diagnostics.add(error(modifiers.defaultToken(), "interface fields cannot be default"));
            }
            Optional<Expression> initializer = Optional.empty();
            if (match(TokenKind.EQUAL)) {
                initializer = Optional.ofNullable(parseVariableInitializer());
            } else {
                diagnostics.add(error(name, "interface field '" + name.lexeme()
                        + "' requires an initializer"));
            }
            Token semicolon = expect(TokenKind.SEMICOLON, "expected ';' after interface field declaration");
            SourceSpan end = semicolon == null
                    ? initializer.map(Expression::span).orElse(name.span()) : semicolon.span();
            fields.add(new FieldDeclaration(AccessModifier.PUBLIC, true, true, type,
                    name.lexeme(), name.span(), initializer,
                    new SourceSpan(start.span().start(), end.end())));
            if (semicolon == null) {
                synchronizeMember();
            }
            return;
        }

        if (modifiers.isFinal()) {
            diagnostics.add(error(modifiers.finalToken(), "interface methods cannot be final"));
        }
        if (modifiers.accessModifier() == AccessModifier.PROTECTED) {
            diagnostics.add(error(modifiers.accessToken(), "interface methods cannot be protected"));
        }
        if (modifiers.isDefault() && modifiers.isStatic()) {
            diagnostics.add(error(modifiers.defaultToken(), "default interface methods cannot be static"));
        }
        if (modifiers.isDefault() && modifiers.isAbstract()) {
            diagnostics.add(error(modifiers.defaultToken(), "default interface methods cannot be abstract"));
        }
        if (modifiers.isDefault() && modifiers.accessModifier() == AccessModifier.PRIVATE) {
            diagnostics.add(error(modifiers.defaultToken(), "default interface methods cannot be private"));
        }
        if (modifiers.isAbstract() && modifiers.isStatic()) {
            diagnostics.add(error(modifiers.abstractToken(), "abstract interface methods cannot be static"));
        }
        if (modifiers.isAbstract() && modifiers.accessModifier() == AccessModifier.PRIVATE) {
            diagnostics.add(error(modifiers.abstractToken(), "abstract interface methods cannot be private"));
        }
        List<Parameter> parameters = parseParameters();
        expect(TokenKind.RIGHT_PAREN, "expected ')' after interface method parameter list");
        List<TypeName> thrownTypes = parseThrowsClause();
        InterfaceMethodKind kind;
        if (modifiers.isDefault()) {
            kind = InterfaceMethodKind.DEFAULT;
        } else if (modifiers.accessModifier() == AccessModifier.PRIVATE) {
            kind = modifiers.isStatic() ? InterfaceMethodKind.PRIVATE_STATIC
                    : InterfaceMethodKind.PRIVATE_INSTANCE;
        } else if (modifiers.isStatic()) {
            kind = InterfaceMethodKind.STATIC;
        } else {
            kind = InterfaceMethodKind.ABSTRACT;
        }
        Optional<Block> body = Optional.empty();
        SourceSpan end;
        if (kind == InterfaceMethodKind.ABSTRACT) {
            if (check(TokenKind.LEFT_BRACE)) {
                diagnostics.add(error(peek(),
                        "interface instance method with a body must be declared default"));
                Block parsed = parseBlock("expected '{' before interface method body",
                        "expected '}' to close interface method body");
                body = Optional.ofNullable(parsed);
                end = parsed == null ? name.span() : parsed.span();
            } else {
                Token semicolon = expect(TokenKind.SEMICOLON,
                        "expected ';' after abstract interface method declaration");
                end = semicolon == null ? name.span() : semicolon.span();
                if (semicolon == null) {
                    synchronizeMember();
                }
            }
        } else {
            Block parsed = parseBlock("expected '{' before interface method body",
                    "expected '}' to close interface method body");
            body = Optional.ofNullable(parsed);
            end = parsed == null ? name.span() : parsed.span();
            if (parsed == null) {
                synchronizeMember();
            }
        }
        AccessModifier access = kind == InterfaceMethodKind.PRIVATE_INSTANCE
                || kind == InterfaceMethodKind.PRIVATE_STATIC
                ? AccessModifier.PRIVATE : AccessModifier.PUBLIC;
        if (modifiers.hasTestDirective()) {
            diagnostics.add(error(modifiers.testToken(),
                    "the @Test directive may only be used on class methods"));
        }
        methods.add(new InterfaceMethodDeclaration(access, kind,
                modifiers.hasOverrideDirective(), typeParameters, type, name.lexeme(),
                name.span(), parameters, thrownTypes, body,
                new SourceSpan(start.span().start(), end.end())));
    }

    private void parseMember(String className, List<FieldDeclaration> fields,
                             List<InstanceInitialization> instanceInitializations,
                             List<StaticInitialization> staticInitializations,
                             List<ConstructorDeclaration> constructors,
                             List<DestructorDeclaration> destructors,
                             List<MethodDeclaration> methods,
                             List<TypeDeclaration> memberTypes,
                             boolean anonymousClass,
                             boolean enumConstantBody) {
        Token start = peek();
        Modifiers modifiers = parseModifiers();

        if (match(TokenKind.DESTRUCTOR)) {
            Token keyword = previous();
            if (modifiers.accessToken() != null || modifiers.isStatic() || modifiers.isFinal()
                    || modifiers.isAbstract() || modifiers.isDefault()
                    || modifiers.hasOverrideDirective()) {
                diagnostics.add(error(start,
                        "destructors cannot have modifiers or @Override"));
            }
            if (modifiers.hasTestDirective()) {
                diagnostics.add(error(modifiers.testToken(),
                        "the @Test directive may only be used on methods"));
            }
            Block body = parseBlock("expected '{' after 'destructor'",
                    "expected '}' to close destructor body");
            if (enumConstantBody) {
                diagnostics.add(error(keyword, anonymousClass
                        ? "enum constant bodies cannot declare destructors"
                        : "enums cannot declare destructors"));
            } else if (body != null) {
                if (!destructors.isEmpty()) {
                    diagnostics.add(error(keyword,
                            "class may declare at most one destructor"));
                } else {
                    destructors.add(new DestructorDeclaration(body, keyword.span(),
                            new SourceSpan(start.span().start(), body.span().end())));
                }
            }
            if (body == null) {
                synchronizeMember();
            }
            return;
        }

        if (modifiers.isDefault()) {
            diagnostics.add(error(modifiers.defaultToken(), "class members cannot be default"));
        }

        if (check(TokenKind.LEFT_BRACE)) {
            rejectOverrideDirective(modifiers);
            Block initializer = parseBlock("expected '{' before initializer block",
                    "expected '}' to close initializer block");
            if (initializer != null) {
                if (modifiers.isStatic()) {
                    if (anonymousClass) {
                        diagnostics.add(error(modifiers.staticToken(),
                                (enumConstantBody ? "enum constant bodies" : "anonymous classes")
                                        + " cannot declare static initializer blocks"));
                    } else if (modifiers.accessToken() != null || modifiers.isFinal()
                            || modifiers.isAbstract() || modifiers.isDefault()) {
                        diagnostics.add(error(start,
                                "static initializer blocks cannot have modifiers other than static"));
                    } else {
                        staticInitializations.add(initializer);
                    }
                } else if (modifiers.accessToken() != null || modifiers.isFinal()
                        || modifiers.isAbstract()) {
                    diagnostics.add(error(start,
                            "instance initializer blocks cannot have modifiers"));
                } else {
                    instanceInitializations.add(initializer);
                }
            }
            return;
        }

        if (match(TokenKind.CLASS)) {
            rejectOverrideDirective(modifiers);
            TypeDeclaration declaration = parseClass(start, modifiers, false, false);
            if (declaration != null) {
                memberTypes.add(declaration);
            }
            return;
        }
        if (match(TokenKind.ENUM)) {
            rejectOverrideDirective(modifiers);
            TypeDeclaration declaration = parseEnum(start, modifiers, false, false);
            if (declaration != null) {
                memberTypes.add(declaration);
            }
            return;
        }
        if (match(TokenKind.INTERFACE)) {
            rejectOverrideDirective(modifiers);
            if (modifiers.isFinal()) {
                diagnostics.add(error(modifiers.finalToken(), "interfaces cannot be final"));
            }
            TypeDeclaration declaration = parseInterface(start, modifiers, false, false);
            if (declaration != null) {
                memberTypes.add(declaration);
            }
            return;
        }

        List<TypeParameter> typeParameters = parseTypeParameters();

        if (anonymousClass && check(TokenKind.IDENTIFIER) && checkNext(TokenKind.LEFT_PAREN)) {
            Token name = advance();
            rejectOverrideDirective(modifiers);
            diagnostics.add(error(name,
                    (enumConstantBody ? "enum constant bodies" : "anonymous classes")
                            + " cannot declare constructors"));
            advance();
            parseParameters();
            expect(TokenKind.RIGHT_PAREN, "expected ')' after constructor parameter list");
            Block body = parseBlock("expected '{' before constructor body",
                    "expected '}' to close constructor body");
            if (body == null) {
                synchronizeMember();
            }
            return;
        }

        if (!anonymousClass && check(TokenKind.IDENTIFIER) && peek().lexeme().equals(className)
                && checkNext(TokenKind.LEFT_PAREN)) {
            Token name = advance();
            rejectOverrideDirective(modifiers);
            if (modifiers.isStatic()) {
                diagnostics.add(error(modifiers.staticToken(), "constructors cannot be static"));
            }
            if (modifiers.isFinal()) {
                diagnostics.add(error(modifiers.finalToken(), "constructors cannot be final"));
            }
            if (modifiers.isAbstract()) {
                diagnostics.add(error(modifiers.abstractToken(), "constructors cannot be abstract"));
            }
            advance();
            List<Parameter> parameters = parseParameters();
            expect(TokenKind.RIGHT_PAREN, "expected ')' after constructor parameter list");
            List<TypeName> thrownTypes = parseThrowsClause();
            Block parsedBody = parseBlock("expected '{' before constructor body",
                    "expected '}' to close constructor body");
            if (parsedBody != null) {
                Optional<SuperConstructorInvocation> superInvocation = Optional.empty();
                Optional<ThisConstructorInvocation> thisInvocation = Optional.empty();
                List<Statement> bodyStatements = new ArrayList<>(parsedBody.statements());
                if (!bodyStatements.isEmpty()
                        && bodyStatements.getFirst() instanceof SuperConstructorInvocation invocation) {
                    superInvocation = Optional.of(invocation);
                    bodyStatements.removeFirst();
                } else if (!bodyStatements.isEmpty()
                        && bodyStatements.getFirst() instanceof ThisConstructorInvocation invocation) {
                    thisInvocation = Optional.of(invocation);
                    bodyStatements.removeFirst();
                }
                Block body = new Block(bodyStatements, parsedBody.span());
                constructors.add(new ConstructorDeclaration(modifiers.accessModifier(), typeParameters,
                        name.lexeme(),
                        name.span(), parameters, thrownTypes, superInvocation, thisInvocation, body,
                        new SourceSpan(start.span().start(), body.span().end())));
            } else {
                synchronizeMember();
            }
            return;
        }

        TypeName type = parseType(true, "expected field or method type");
        if (type == null) {
            synchronizeMember();
            return;
        }
        Token name = expect(TokenKind.IDENTIFIER, "expected member name after type");
        if (name == null) {
            synchronizeMember();
            return;
        }

        if (match(TokenKind.LEFT_PAREN)) {
            List<Parameter> parameters = parseParameters();
            expect(TokenKind.RIGHT_PAREN, "expected ')' after method parameter list");
            List<TypeName> thrownTypes = parseThrowsClause();
            if (anonymousClass && modifiers.isAbstract()) {
                diagnostics.add(error(modifiers.abstractToken(),
                        (enumConstantBody ? "enum constant bodies" : "anonymous classes")
                                + " cannot declare abstract methods"));
            }
            if (modifiers.isAbstract()) {
                Token semicolon = expect(TokenKind.SEMICOLON,
                        "expected ';' after abstract method declaration");
                SourceSpan end = semicolon == null ? name.span() : semicolon.span();
                methods.add(new MethodDeclaration(modifiers.accessModifier(), modifiers.isStatic(), true,
                        modifiers.isFinal(), modifiers.hasOverrideDirective(),
                        modifiers.hasTestDirective(), typeParameters,
                        type, name.lexeme(), name.span(),
                        parameters, thrownTypes, Optional.empty(),
                        new SourceSpan(start.span().start(), end.end())));
                if (semicolon == null) {
                    synchronizeMember();
                }
            } else {
                Block body = parseBlock("expected '{' before method body", "expected '}' to close method body");
                if (body != null) {
                    methods.add(new MethodDeclaration(modifiers.accessModifier(), modifiers.isStatic(), false,
                            modifiers.isFinal(), modifiers.hasOverrideDirective(),
                            modifiers.hasTestDirective(), typeParameters,
                            type, name.lexeme(), name.span(),
                            parameters, thrownTypes, Optional.of(body),
                            new SourceSpan(start.span().start(), body.span().end())));
                } else {
                    synchronizeMember();
                }
            }
            return;
        }

        if (!typeParameters.isEmpty()) {
            diagnostics.add(Diagnostic.error(source, typeParameters.getFirst().span(),
                    "type parameters may only be declared by a method or constructor"));
        }
        if (modifiers.isAbstract()) {
            diagnostics.add(error(modifiers.abstractToken(), "fields cannot be abstract"));
        }
        rejectOverrideDirective(modifiers);

        Optional<Expression> initializer = Optional.empty();
        if (match(TokenKind.EQUAL)) {
            initializer = Optional.ofNullable(parseVariableInitializer());
        }
        Token semicolon = expect(TokenKind.SEMICOLON, "expected ';' after field declaration");
        SourceSpan end = semicolon == null ? name.span() : semicolon.span();
        FieldDeclaration field = new FieldDeclaration(modifiers.accessModifier(), modifiers.isStatic(),
                modifiers.isFinal(), type, name.lexeme(), name.span(), initializer,
                new SourceSpan(start.span().start(), end.end()));
        fields.add(field);
        if (!field.isStatic()) {
            instanceInitializations.add(field);
        } else {
            staticInitializations.add(field);
        }
        if (semicolon == null) {
            synchronizeMember();
        }
    }

    private List<TypeName> parseThrowsClause() {
        if (!match(TokenKind.THROWS)) {
            return List.of();
        }
        List<TypeName> thrownTypes = new ArrayList<>();
        do {
            TypeName type = parseType(false, "expected exception type after 'throws'");
            if (type == null) {
                break;
            }
            thrownTypes.add(type);
        } while (match(TokenKind.COMMA));
        if (thrownTypes.isEmpty()) {
            diagnostics.add(error(previous(), "throws clause requires at least one exception type"));
        }
        return List.copyOf(thrownTypes);
    }

    private Modifiers parseModifiers() {
        AccessModifier access = AccessModifier.PACKAGE_PRIVATE;
        Token accessToken = null;
        Token staticToken = null;
        Token finalToken = null;
        Token abstractToken = null;
        Token defaultToken = null;
        Token overrideToken = null;
        Token testToken = null;
        boolean progress;
        do {
            progress = false;
            if (check(TokenKind.PUBLIC) || check(TokenKind.PROTECTED) || check(TokenKind.PRIVATE)) {
                Token token = advance();
                if (accessToken != null) {
                    diagnostics.add(error(token, "declaration cannot have more than one access modifier"));
                } else {
                    accessToken = token;
                    access = switch (token.kind()) {
                        case PUBLIC -> AccessModifier.PUBLIC;
                        case PROTECTED -> AccessModifier.PROTECTED;
                        case PRIVATE -> AccessModifier.PRIVATE;
                        default -> throw new IllegalStateException("unexpected access modifier");
                    };
                }
                progress = true;
            } else if (match(TokenKind.STATIC)) {
                Token token = previous();
                if (staticToken != null) {
                    diagnostics.add(error(token, "duplicate 'static' modifier"));
                } else {
                    staticToken = token;
                }
                progress = true;
            } else if (match(TokenKind.FINAL)) {
                Token token = previous();
                if (finalToken != null) {
                    diagnostics.add(error(token, "duplicate 'final' modifier"));
                } else {
                    finalToken = token;
                }
                progress = true;
            } else if (match(TokenKind.ABSTRACT)) {
                Token token = previous();
                if (abstractToken != null) {
                    diagnostics.add(error(token, "duplicate 'abstract' modifier"));
                } else {
                    abstractToken = token;
                }
                progress = true;
            } else if (match(TokenKind.DEFAULT)) {
                Token token = previous();
                if (defaultToken != null) {
                    diagnostics.add(error(token, "duplicate 'default' modifier"));
                } else {
                    defaultToken = token;
                }
                progress = true;
            } else if (match(TokenKind.AT)) {
                Token at = previous();
                Token name = expect(TokenKind.IDENTIFIER,
                        "expected 'Override' or 'Test' after '@'");
                if (name != null) {
                    if (name.lexeme().equals("Override")) {
                        if (overrideToken != null) {
                            diagnostics.add(error(at, "duplicate '@Override' directive"));
                        } else {
                            overrideToken = at;
                        }
                    } else if (name.lexeme().equals("Test")) {
                        if (testToken != null) {
                            diagnostics.add(error(at, "duplicate '@Test' directive"));
                        } else {
                            testToken = at;
                        }
                    } else {
                        diagnostics.add(error(name, "general annotations are not supported; "
                                + "only the built-in @Override and @Test directives are recognized"));
                    }
                    if ((name.lexeme().equals("Override") || name.lexeme().equals("Test"))
                            && check(TokenKind.LEFT_PAREN)) {
                        diagnostics.add(error(peek(), "the @" + name.lexeme()
                                + " directive does not accept arguments"));
                    }
                }
                progress = true;
            }
        } while (progress);
        return new Modifiers(access, accessToken, staticToken, finalToken, abstractToken,
                defaultToken, overrideToken, testToken);
    }

    private void rejectOverrideDirective(Modifiers modifiers) {
        if (modifiers.hasOverrideDirective()) {
            diagnostics.add(error(modifiers.overrideToken(),
                    "the @Override directive may only be used on methods"));
        }
        if (modifiers.hasTestDirective()) {
            diagnostics.add(error(modifiers.testToken(),
                    "the @Test directive may only be used on methods"));
        }
    }

    private List<Parameter> parseParameters() {
        List<Parameter> parameters = new ArrayList<>();
        if (check(TokenKind.RIGHT_PAREN)) {
            return parameters;
        }
        do {
            Token start = peek();
            boolean isFinal = parseVariableModifiers("parameter");
            TypeName type = parseType(false, "expected parameter type");
            SourceSpan ellipsis = matchEllipsis();
            if (ellipsis != null) {
                diagnostics.add(Diagnostic.error(source, ellipsis,
                        "varargs are not supported; declare an explicit array parameter instead"));
            }
            isFinal = parseMisplacedVariableModifiers("parameter", isFinal);
            Token name = expect(TokenKind.IDENTIFIER, "expected parameter name after type");
            if (type != null && name != null) {
                parameters.add(new Parameter(type, isFinal,
                        name.lexeme(), name.span(),
                        new SourceSpan(start.span().start(), name.span().end())));
            } else {
                synchronizeParameter();
            }
        } while (match(TokenKind.COMMA));
        return parameters;
    }

    private boolean parseVariableModifiers(String role) {
        boolean isFinal = false;
        while (isVariableModifier(peek().kind())) {
            Token modifier = advance();
            if (modifier.kind() == TokenKind.FINAL) {
                if (isFinal) {
                    diagnostics.add(error(modifier,
                            "duplicate 'final' modifier on " + role));
                }
                isFinal = true;
            } else {
                diagnostics.add(error(modifier, "modifier '" + modifier.lexeme()
                        + "' is not permitted on " + role));
            }
        }
        return isFinal;
    }

    private boolean parseMisplacedVariableModifiers(String role, boolean isFinal) {
        while (isVariableModifier(peek().kind())) {
            Token modifier = advance();
            if (modifier.kind() == TokenKind.FINAL) {
                diagnostics.add(error(modifier,
                        "'final' modifier must precede the " + role + " type"));
                isFinal = true;
            } else {
                diagnostics.add(error(modifier, "modifier '" + modifier.lexeme()
                        + "' is not permitted on " + role));
            }
        }
        return isFinal;
    }

    private static boolean isVariableModifier(TokenKind kind) {
        return kind == TokenKind.PUBLIC || kind == TokenKind.PROTECTED
                || kind == TokenKind.PRIVATE || kind == TokenKind.STATIC
                || kind == TokenKind.FINAL || kind == TokenKind.ABSTRACT
                || kind == TokenKind.DEFAULT;
    }

    private SourceSpan matchEllipsis() {
        if (!contiguousKinds(TokenKind.DOT, TokenKind.DOT, TokenKind.DOT)) {
            return null;
        }
        Token first = advance();
        advance();
        Token last = advance();
        return new SourceSpan(first.span().start(), last.span().end());
    }

    private TypeName parseType(boolean allowVoid, String message) {
        TypeName base;
        if (isNumericPrimitive(peek().kind())) {
            Token primitive = advance();
            base = TypeName.primitive(primitiveKind(primitive.kind()), primitive.span());
        } else if (match(TokenKind.BOOLEAN)) {
            base = TypeName.primitive(TypeName.Kind.BOOLEAN, previous().span());
        } else if (allowVoid && match(TokenKind.VOID)) {
            base = TypeName.primitive(TypeName.Kind.VOID, previous().span());
        } else if (check(TokenKind.IDENTIFIER)) {
            ParameterizedQualifiedName name = parseParameterizedQualifiedName(message);
            if (name == null) {
                base = null;
            } else {
                base = TypeName.reference(name.text(), name.typeArguments(),
                        name.typeArgumentSegmentCounts(), name.span());
            }
        } else {
            diagnostics.add(error(peek(), message));
            return null;
        }
        while (base != null && match(TokenKind.LEFT_BRACKET)) {
            Token right = expect(TokenKind.RIGHT_BRACKET, "expected ']' in array type");
            SourceSpan end = right == null ? previous().span() : right.span();
            if (base.kind() == TypeName.Kind.VOID) {
                diagnostics.add(error(previous(), "array element type cannot be void"));
                return base;
            }
            base = TypeName.array(base, new SourceSpan(base.span().start(), end.end()));
        }
        return base;
    }

    private List<TypeName> parseTypeArguments() {
        return parseTypeArguments(false).arguments();
    }

    private ParsedTypeArguments parseTypeArguments(boolean allowDiamond) {
        // Consecutive '>' characters remain separate GREATER tokens so nested
        // arguments close without lexer state. Future shift expressions should
        // compose adjacent tokens in expression context rather than changing
        // this type grammar.
        if (!match(TokenKind.LESS)) {
            return ParsedTypeArguments.absent();
        }
        Token opening = previous();
        List<TypeName> arguments = new ArrayList<>();
        if (match(TokenKind.GREATER)) {
            SourceSpan span = new SourceSpan(opening.span().start(), previous().span().end());
            if (!allowDiamond) {
                diagnostics.add(error(opening,
                        "empty type arguments are only permitted for diamond class creation"));
            }
            return new ParsedTypeArguments(true, true, List.of(), span);
        }
        do {
            if (match(TokenKind.QUESTION)) {
                Token wildcard = previous();
                TypeName.WildcardKind wildcardKind = TypeName.WildcardKind.UNBOUNDED;
                if (match(TokenKind.EXTENDS)) {
                    wildcardKind = TypeName.WildcardKind.EXTENDS;
                } else if (match(TokenKind.SUPER)) {
                    wildcardKind = TypeName.WildcardKind.SUPER;
                }
                if (wildcardKind == TypeName.WildcardKind.UNBOUNDED) {
                    arguments.add(TypeName.wildcard(wildcard.span()));
                } else {
                    TypeName bound = parseType(false, "expected wildcard bound type");
                    if (bound == null) {
                        arguments.add(TypeName.wildcard(wildcard.span()));
                    } else {
                        arguments.add(TypeName.wildcard(wildcardKind, bound,
                                new SourceSpan(wildcard.span().start(), bound.span().end())));
                    }
                }
                continue;
            }
            TypeName argument = parseType(false, "expected type argument");
            if (argument != null) {
                arguments.add(argument);
            }
        } while (match(TokenKind.COMMA));
        Token closing = expect(TokenKind.GREATER, "expected '>' after type argument list");
        SourceSpan span = new SourceSpan(opening.span().start(),
                closing == null ? previous().span().end() : closing.span().end());
        return new ParsedTypeArguments(true, false, arguments, span);
    }

    private Block parseBlock(String openingMessage, String closingMessage) {
        Token leftBrace = expect(TokenKind.LEFT_BRACE, openingMessage);
        if (leftBrace == null) {
            return null;
        }
        List<Statement> statements = new ArrayList<>();
        while (!check(TokenKind.RIGHT_BRACE) && !check(TokenKind.EOF)) {
            int before = current;
            Statement statement = parseBlockStatement();
            if (statement != null) {
                statements.add(statement);
            }
            if (current == before) {
                advance();
            }
        }
        Token rightBrace = expect(TokenKind.RIGHT_BRACE, closingMessage);
        SourceSpan span = rightBrace == null
                ? leftBrace.span()
                : new SourceSpan(leftBrace.span().start(), rightBrace.span().end());
        return new Block(statements, span);
    }

    private Statement parseBlockStatement() {
        TokenKind localType = localTypeDeclarationKind();
        if (localType == TokenKind.CLASS) {
            return parseLocalClassDeclaration();
        }
        if (localType == TokenKind.INTERFACE) {
            Token start = peek();
            Modifiers modifiers = parseModifiers();
            rejectOverrideDirective(modifiers);
            diagnostics.add(error(peek(), "interfaces cannot be declared in a block"));
            advance();
            parseInterface(start, modifiers, false, false);
            return null;
        }
        if (localType == TokenKind.ENUM) {
            Token start = peek();
            Modifiers modifiers = parseModifiers();
            rejectOverrideDirective(modifiers);
            diagnostics.add(error(peek(), "local enum declarations are not supported"));
            advance();
            parseEnum(start, modifiers, false, false);
            return null;
        }
        return parseStatement();
    }

    private LocalClassDeclaration parseLocalClassDeclaration() {
        Token start = peek();
        Modifiers modifiers = parseModifiers();
        if (modifiers.accessToken() != null) {
            diagnostics.add(error(modifiers.accessToken(),
                    "local classes cannot have an access modifier"));
        }
        if (modifiers.isStatic()) {
            diagnostics.add(error(modifiers.staticToken(), "local classes cannot be static"));
        }
        if (modifiers.isDefault()) {
            diagnostics.add(error(modifiers.defaultToken(), "local classes cannot be default"));
        }
        expect(TokenKind.CLASS, "expected 'class' in local class declaration");
        rejectOverrideDirective(modifiers);
        Modifiers localModifiers = new Modifiers(AccessModifier.PACKAGE_PRIVATE, null, null,
                modifiers.finalToken(), modifiers.abstractToken(), null, null, null);
        ClassDeclaration declaration = parseClass(start, localModifiers, false, false);
        return declaration == null ? null : new LocalClassDeclaration(declaration);
    }

    private Statement parseStatement() {
        if (match(TokenKind.SEMICOLON)) {
            return new EmptyStatement(previous().span());
        }
        if (check(TokenKind.IDENTIFIER) && checkNext(TokenKind.COLON)) {
            return parseLabeledStatement();
        }
        if (check(TokenKind.LEFT_BRACE)) {
            return parseBlock("expected '{'", "expected '}' to close block");
        }
        if (match(TokenKind.RETURN)) {
            return parseReturn(previous());
        }
        if (match(TokenKind.YIELD)) {
            return parseYield(previous());
        }
        if (match(TokenKind.FREE)) {
            return parseFree(previous());
        }
        if (match(TokenKind.THROW)) {
            return parseThrow(previous());
        }
        if (match(TokenKind.TRY)) {
            return parseTry(previous());
        }
        if (match(TokenKind.IF)) {
            return parseIf(previous());
        }
        if (match(TokenKind.WHILE)) {
            return parseWhile(previous());
        }
        if (match(TokenKind.DO)) {
            return parseDoWhile(previous());
        }
        if (match(TokenKind.FOR)) {
            return parseFor(previous());
        }
        if (match(TokenKind.SWITCH)) {
            return parseSwitch(previous());
        }
        if (match(TokenKind.BREAK)) {
            return parseBreak(previous());
        }
        if (match(TokenKind.CONTINUE)) {
            return parseContinue(previous());
        }
        if (check(TokenKind.SUPER) && checkNext(TokenKind.LEFT_PAREN)) {
            advance();
            return parseSuperConstructorInvocation(previous());
        }
        if (check(TokenKind.THIS) && checkNext(TokenKind.LEFT_PAREN)) {
            advance();
            return parseThisConstructorInvocation(previous());
        }
        if (check(TokenKind.LESS)) {
            Token opening = peek();
            List<TypeName> typeArguments = parseTypeArguments();
            if (match(TokenKind.SUPER) && check(TokenKind.LEFT_PAREN)) {
                return parseSuperConstructorInvocation(opening, previous(), typeArguments);
            }
            if (match(TokenKind.THIS) && check(TokenKind.LEFT_PAREN)) {
                return parseThisConstructorInvocation(opening, previous(), typeArguments);
            }
            diagnostics.add(error(peek(),
                    "explicit constructor type arguments must be followed by 'this' or 'super'"));
            synchronizeStatement();
            return null;
        }
        if (looksLikeLocalVariableDeclaration()) {
            return parseLocalVariable();
        }
        return parseAssignmentOrExpressionStatement();
    }

    private SuperConstructorInvocation parseSuperConstructorInvocation(Token superKeyword) {
        return parseSuperConstructorInvocation(superKeyword, superKeyword, List.of());
    }

    private SuperConstructorInvocation parseSuperConstructorInvocation(
            Token start, Token superKeyword, List<TypeName> typeArguments) {
        expect(TokenKind.LEFT_PAREN, "expected '(' after 'super'");
        List<Expression> arguments = parseArguments();
        Token rightParen = expect(TokenKind.RIGHT_PAREN, "expected ')' after superclass constructor arguments");
        Token semicolon = expect(TokenKind.SEMICOLON, "expected ';' after superclass constructor invocation");
        SourceSpan end = semicolon != null ? semicolon.span()
                : rightParen != null ? rightParen.span() : superKeyword.span();
        return new SuperConstructorInvocation(Optional.empty(), typeArguments, arguments,
                new SourceSpan(start.span().start(), end.end()));
    }

    private ThisConstructorInvocation parseThisConstructorInvocation(Token thisKeyword) {
        return parseThisConstructorInvocation(thisKeyword, thisKeyword, List.of());
    }

    private ThisConstructorInvocation parseThisConstructorInvocation(
            Token start, Token thisKeyword, List<TypeName> typeArguments) {
        expect(TokenKind.LEFT_PAREN, "expected '(' after 'this'");
        List<Expression> arguments = parseArguments();
        Token rightParen = expect(TokenKind.RIGHT_PAREN, "expected ')' after constructor delegation arguments");
        Token semicolon = expect(TokenKind.SEMICOLON, "expected ';' after constructor delegation");
        SourceSpan end = semicolon != null ? semicolon.span()
                : rightParen != null ? rightParen.span() : thisKeyword.span();
        return new ThisConstructorInvocation(typeArguments, arguments,
                new SourceSpan(start.span().start(), end.end()));
    }

    private ReturnStatement parseReturn(Token returnKeyword) {
        Optional<Expression> value = check(TokenKind.SEMICOLON)
                ? Optional.empty()
                : Optional.ofNullable(parseExpression());
        Token semicolon = expect(TokenKind.SEMICOLON, "expected ';' after return statement");
        SourceSpan span = semicolon == null
                ? returnKeyword.span()
                : new SourceSpan(returnKeyword.span().start(), semicolon.span().end());
        return new ReturnStatement(value, span);
    }

    private FreeStatement parseFree(Token freeKeyword) {
        Expression value = parseExpression();
        Token semicolon = expect(TokenKind.SEMICOLON, "expected ';' after free statement");
        SourceSpan end = semicolon == null
                ? value == null ? freeKeyword.span() : value.span()
                : semicolon.span();
        return value == null ? null : new FreeStatement(value,
                new SourceSpan(freeKeyword.span().start(), end.end()));
    }

    private ThrowStatement parseThrow(Token throwKeyword) {
        Expression value = parseExpression();
        Token semicolon = expect(TokenKind.SEMICOLON, "expected ';' after throw statement");
        SourceSpan end = semicolon == null
                ? value == null ? throwKeyword.span() : value.span()
                : semicolon.span();
        return value == null ? null : new ThrowStatement(value,
                new SourceSpan(throwKeyword.span().start(), end.end()));
    }

    private TryStatement parseTry(Token tryKeyword) {
        boolean resourceClause = check(TokenKind.LEFT_PAREN);
        if (resourceClause) {
            Token opening = peek();
            parseTryResources();
            diagnostics.add(error(opening, "try-with-resources is not supported; declare the "
                    + "resource before try and close it in finally"));
        }
        Block body = parseBlock("expected '{' after 'try'", "expected '}' to close try block");
        List<CatchClause> catches = new ArrayList<>();
        while (match(TokenKind.CATCH)) {
            Token catchKeyword = previous();
            expect(TokenKind.LEFT_PAREN, "expected '(' after 'catch'");
            boolean isFinal = parseVariableModifiers("catch parameter");
            TypeName type = parseType(false, "expected catch type");
            List<TypeName> types = new ArrayList<>();
            if (type != null) {
                types.add(type);
            }
            while (match(TokenKind.PIPE)) {
                TypeName alternative = parseType(false,
                        "expected catch type after '|'");
                if (alternative != null) {
                    types.add(alternative);
                }
            }
            isFinal = parseMisplacedVariableModifiers("catch parameter", isFinal);
            Token variable = expect(TokenKind.IDENTIFIER, "expected catch variable name after catch type");
            expect(TokenKind.RIGHT_PAREN, "expected ')' after catch parameter");
            Block catchBody = parseBlock("expected '{' before catch body",
                    "expected '}' to close catch body");
            if (!types.isEmpty() && variable != null && catchBody != null) {
                catches.add(new CatchClause(types, isFinal || types.size() > 1,
                        variable.lexeme(), variable.span(), catchBody,
                        new SourceSpan(catchKeyword.span().start(), catchBody.span().end())));
            }
        }
        Optional<Block> finallyBlock = Optional.empty();
        if (match(TokenKind.FINALLY)) {
            Block parsed = parseBlock("expected '{' after 'finally'",
                    "expected '}' to close finally block");
            finallyBlock = Optional.ofNullable(parsed);
        }
        if (!resourceClause && catches.isEmpty() && finallyBlock.isEmpty()) {
            diagnostics.add(error(peek(), "try statement requires at least one catch or finally block"));
        }
        if (body == null) {
            return null;
        }
        SourceSpan end = finallyBlock.map(Block::span)
                .orElseGet(() -> catches.isEmpty() ? body.span() : catches.getLast().span());
        return new TryStatement(body, catches, finallyBlock,
                new SourceSpan(tryKeyword.span().start(), end.end()));
    }

    private List<Expression> parseTryResources() {
        Token opening = expect(TokenKind.LEFT_PAREN,
                "expected '(' before try resource list");
        List<Expression> resources = new ArrayList<>();
        if (check(TokenKind.RIGHT_PAREN)) {
            diagnostics.add(error(peek(), "try resource list requires at least one resource"));
        } else {
            while (!check(TokenKind.EOF) && !check(TokenKind.RIGHT_PAREN)) {
                Expression resource = parseExpression();
                if (resource != null) {
                    resources.add(resource);
                }
                if (!match(TokenKind.SEMICOLON)) {
                    break;
                }
            }
        }
        Token closing = expect(TokenKind.RIGHT_PAREN,
                "expected ')' after try resource list");
        if (resources.isEmpty() && opening != null && closing == null) {
            synchronizeStatement();
        }
        return List.copyOf(resources);
    }

    private IfStatement parseIf(Token ifKeyword) {
        expect(TokenKind.LEFT_PAREN, "expected '(' after 'if'");
        Expression condition = parseExpression();
        expect(TokenKind.RIGHT_PAREN, "expected ')' after if condition");
        Statement thenBranch = parseRequiredStatement("expected statement after if condition");
        Optional<Statement> elseBranch = Optional.empty();
        if (match(TokenKind.ELSE)) {
            elseBranch = Optional.ofNullable(parseRequiredStatement("expected statement after 'else'"));
        }
        SourceSpan end = elseBranch.map(Statement::span)
                .orElseGet(() -> thenBranch == null ? ifKeyword.span() : thenBranch.span());
        return new IfStatement(condition, thenBranch, elseBranch,
                new SourceSpan(ifKeyword.span().start(), end.end()));
    }

    private WhileStatement parseWhile(Token whileKeyword) {
        expect(TokenKind.LEFT_PAREN, "expected '(' after 'while'");
        Expression condition = parseExpression();
        expect(TokenKind.RIGHT_PAREN, "expected ')' after while condition");
        Statement body = parseRequiredStatement("expected statement after while condition");
        SourceSpan end = body == null ? whileKeyword.span() : body.span();
        return new WhileStatement(condition, body, new SourceSpan(whileKeyword.span().start(), end.end()));
    }

    private DoWhileStatement parseDoWhile(Token doKeyword) {
        Statement body = parseRequiredStatement("expected statement after 'do'");
        expect(TokenKind.WHILE, "expected 'while' after do statement body");
        expect(TokenKind.LEFT_PAREN, "expected '(' after 'while' in do statement");
        Expression condition = parseExpression();
        Token rightParen = expect(TokenKind.RIGHT_PAREN,
                "expected ')' after do-while condition");
        Token semicolon = expect(TokenKind.SEMICOLON,
                "expected ';' after do-while statement");
        SourceSpan end = semicolon != null ? semicolon.span()
                : rightParen != null ? rightParen.span()
                : condition != null ? condition.span()
                : body != null ? body.span() : doKeyword.span();
        return body == null || condition == null ? null
                : new DoWhileStatement(body, condition,
                new SourceSpan(doKeyword.span().start(), end.end()));
    }

    private Statement parseFor(Token forKeyword) {
        expect(TokenKind.LEFT_PAREN, "expected '(' after 'for'");
        if (looksLikeEnhancedForInitializer()) {
            return parseEnhancedFor(forKeyword);
        }
        Optional<Statement> initializer = Optional.empty();
        if (match(TokenKind.SEMICOLON)) {
            // Empty initializer.
        } else if (looksLikeLocalVariableDeclaration()) {
            initializer = Optional.ofNullable(parseLocalVariable());
        } else {
            Expression expression = parseExpression();
            Token semicolon = expect(TokenKind.SEMICOLON,
                    "expected ';' after for initializer");
            if (expression != null) {
                SourceSpan end = semicolon == null ? expression.span() : semicolon.span();
                initializer = Optional.of(new ExpressionStatement(expression,
                        new SourceSpan(expression.span().start(), end.end())));
            }
        }

        Optional<Expression> condition = check(TokenKind.SEMICOLON)
                ? Optional.empty() : Optional.ofNullable(parseExpression());
        expect(TokenKind.SEMICOLON, "expected ';' after for condition");

        List<Expression> updates = new ArrayList<>();
        if (!check(TokenKind.RIGHT_PAREN)) {
            do {
                Expression update = parseExpression();
                if (update != null) {
                    updates.add(update);
                }
            } while (match(TokenKind.COMMA));
        }
        expect(TokenKind.RIGHT_PAREN, "expected ')' after for clauses");
        Statement body = parseRequiredStatement("expected statement after for clauses");
        SourceSpan end = body == null ? forKeyword.span() : body.span();
        return new ForStatement(initializer, condition, updates, body,
                new SourceSpan(forKeyword.span().start(), end.end()));
    }

    private EnhancedForStatement parseEnhancedFor(Token forKeyword) {
        boolean isFinal = parseVariableModifiers("enhanced-for variable");
        TypeName type = parseType(false, "expected enhanced-for variable type");
        isFinal = parseMisplacedVariableModifiers("enhanced-for variable", isFinal);
        Token name = expect(TokenKind.IDENTIFIER, "expected enhanced-for variable name after type");
        expect(TokenKind.COLON, "expected ':' after enhanced-for variable");
        Expression iterable = parseExpression();
        expect(TokenKind.RIGHT_PAREN, "expected ')' after enhanced-for expression");
        Statement body = parseRequiredStatement("expected statement after enhanced-for expression");
        SourceSpan end = body == null ? forKeyword.span() : body.span();
        if (type == null || name == null || iterable == null || body == null) {
            synchronizeStatement();
            return null;
        }
        return new EnhancedForStatement(type, isFinal, name.lexeme(), name.span(),
                iterable, body, new SourceSpan(forKeyword.span().start(), end.end()));
    }

    private Statement parseSwitch(Token switchKeyword) {
        expect(TokenKind.LEFT_PAREN, "expected '(' after 'switch'");
        Expression selector = parseExpression();
        expect(TokenKind.RIGHT_PAREN, "expected ')' after switch selector");
        Token leftBrace = expect(TokenKind.LEFT_BRACE, "expected '{' before switch body");
        if (leftBrace == null) {
            synchronizeStatement();
            return null;
        }
        ParsedSwitchBody body = parseSwitchBody(leftBrace);
        if (selector == null) {
            return null;
        }
        SourceSpan span = new SourceSpan(switchKeyword.span().start(), body.end().end());
        return body.arrowRules()
                ? new ModernSwitchStatement(selector, body.rules(), span)
                : new SwitchStatement(selector, body.groups(), span);
    }

    private SwitchExpression parseSwitchExpression(Token switchKeyword) {
        expect(TokenKind.LEFT_PAREN, "expected '(' after 'switch'");
        Expression selector = parseExpression();
        expect(TokenKind.RIGHT_PAREN, "expected ')' after switch selector");
        Token leftBrace = expect(TokenKind.LEFT_BRACE, "expected '{' before switch body");
        if (leftBrace == null) {
            return null;
        }
        ParsedSwitchBody body = parseSwitchBody(leftBrace);
        if (selector == null) {
            return null;
        }
        return new SwitchExpression(selector, body.groups(), body.rules(), body.arrowRules(),
                new SourceSpan(switchKeyword.span().start(), body.end().end()));
    }

    private ParsedSwitchBody parseSwitchBody(Token leftBrace) {
        if (check(TokenKind.RIGHT_BRACE)) {
            Token rightBrace = advance();
            return new ParsedSwitchBody(List.of(), List.of(), false, rightBrace.span());
        }
        if (!check(TokenKind.CASE) && !check(TokenKind.DEFAULT)) {
            diagnostics.add(error(peek(),
                    "expected 'case' or 'default' label in switch body"));
        }
        ParsedSwitchLabels first = parseSwitchLabels();
        if (first == null) {
            while (!check(TokenKind.RIGHT_BRACE) && !check(TokenKind.EOF)) {
                advance();
            }
            Token rightBrace = expect(TokenKind.RIGHT_BRACE,
                    "expected '}' to close switch body");
            return new ParsedSwitchBody(List.of(), List.of(), false,
                    rightBrace == null ? leftBrace.span() : rightBrace.span());
        }
        return first.arrowRules()
                ? parseSwitchRules(first, leftBrace)
                : parseSwitchGroups(first, leftBrace);
    }

    private ParsedSwitchBody parseSwitchRules(ParsedSwitchLabels first, Token leftBrace) {
        List<SwitchRule> rules = new ArrayList<>();
        ParsedSwitchLabels labels = first;
        while (labels != null) {
            SwitchRuleBody body = parseSwitchRuleBody();
            SourceSpan end = body == null ? labels.span() : body.span();
            if (body != null) {
                rules.add(new SwitchRule(labels.labels(), body,
                        new SourceSpan(labels.span().start(), end.end())));
            }
            if (check(TokenKind.CASE) || check(TokenKind.DEFAULT)) {
                labels = parseSwitchLabels();
                if (labels != null && !labels.arrowRules()) {
                    diagnostics.add(Diagnostic.error(source, labels.span(),
                            "cannot mix ':' labels and '->' rules in one switch body"));
                }
            } else {
                labels = null;
            }
        }
        while (!check(TokenKind.RIGHT_BRACE) && !check(TokenKind.EOF)) {
            diagnostics.add(error(peek(),
                    "expected 'case' or 'default' rule in switch body"));
            int before = current;
            parseBlockStatement();
            if (current == before) {
                advance();
            }
        }
        Token rightBrace = expect(TokenKind.RIGHT_BRACE, "expected '}' to close switch body");
        return new ParsedSwitchBody(List.of(), rules, true,
                rightBrace == null ? leftBrace.span() : rightBrace.span());
    }

    private SwitchRuleBody parseSwitchRuleBody() {
        if (check(TokenKind.LEFT_BRACE)) {
            Block block = parseBlock("expected '{' after '->'",
                    "expected '}' to close switch rule block");
            return block == null ? null : new SwitchRuleBlock(block, block.span());
        }
        if (match(TokenKind.THROW)) {
            ThrowStatement statement = parseThrow(previous());
            return new SwitchRuleThrow(statement, statement.span());
        }
        Expression expression = parseExpression();
        Token semicolon = expect(TokenKind.SEMICOLON,
                "expected ';' after switch rule expression");
        if (expression == null) {
            return null;
        }
        SourceSpan span = semicolon == null ? expression.span()
                : new SourceSpan(expression.span().start(), semicolon.span().end());
        return new SwitchRuleExpression(expression, span);
    }

    private ParsedSwitchBody parseSwitchGroups(ParsedSwitchLabels first, Token leftBrace) {
        List<SwitchGroup> groups = new ArrayList<>();
        ParsedSwitchLabels pending = first;
        while (pending != null) {
            List<SwitchLabel> labels = new ArrayList<>(pending.labels());
            while (check(TokenKind.CASE) || check(TokenKind.DEFAULT)) {
                ParsedSwitchLabels next = parseSwitchLabels();
                if (next == null) {
                    break;
                }
                if (next.arrowRules()) {
                    diagnostics.add(Diagnostic.error(source, next.span(),
                            "cannot mix ':' labels and '->' rules in one switch body"));
                    parseSwitchRuleBody();
                    continue;
                }
                labels.addAll(next.labels());
            }
            List<Statement> statements = new ArrayList<>();
            while (!check(TokenKind.CASE) && !check(TokenKind.DEFAULT)
                    && !check(TokenKind.RIGHT_BRACE) && !check(TokenKind.EOF)) {
                int before = current;
                Statement statement = parseBlockStatement();
                if (statement != null) {
                    statements.add(statement);
                }
                if (current == before) {
                    advance();
                }
            }
            SourceSpan end = statements.isEmpty()
                    ? labels.getLast().span() : statements.getLast().span();
            groups.add(new SwitchGroup(labels, statements,
                    new SourceSpan(labels.getFirst().span().start(), end.end())));
            pending = check(TokenKind.CASE) || check(TokenKind.DEFAULT)
                    ? parseSwitchLabels() : null;
            if (pending != null && pending.arrowRules()) {
                diagnostics.add(Diagnostic.error(source, pending.span(),
                        "cannot mix ':' labels and '->' rules in one switch body"));
                parseSwitchRuleBody();
                pending = null;
            }
        }
        while (!check(TokenKind.RIGHT_BRACE) && !check(TokenKind.EOF)) {
            diagnostics.add(error(peek(),
                    "expected 'case' or 'default' label in switch body"));
            advance();
        }
        Token rightBrace = expect(TokenKind.RIGHT_BRACE, "expected '}' to close switch body");
        return new ParsedSwitchBody(groups, List.of(), false,
                rightBrace == null ? leftBrace.span() : rightBrace.span());
    }

    private ParsedSwitchLabels parseSwitchLabels() {
        if (!check(TokenKind.CASE) && !check(TokenKind.DEFAULT)) {
            diagnostics.add(error(peek(),
                    "expected 'case' or 'default' label in switch body"));
            return null;
        }
        Token keyword = advance();
        List<SwitchLabel> labels = new ArrayList<>();
        if (keyword.kind() == TokenKind.DEFAULT) {
            labels.add(new SwitchLabel(Optional.empty(), keyword.span()));
        } else {
            while (true) {
                if (match(TokenKind.DEFAULT)) {
                    if (labels.size() != 1
                            || labels.getFirst().value().isEmpty()
                            || !(labels.getFirst().value().orElseThrow()
                            instanceof NullLiteralExpression)) {
                        diagnostics.add(error(previous(),
                                "default may be combined only with case null"));
                    }
                    labels.add(new SwitchLabel(Optional.empty(), previous().span()));
                } else {
                    if (looksLikeSwitchPatternLabel()) {
                        diagnostics.add(error(peek(),
                                "switch pattern labels are not supported; use constant labels only"));
                    }
                    Expression value = parseExpression();
                    if (value != null) {
                        if (check(TokenKind.IDENTIFIER)) {
                            diagnostics.add(error(peek(),
                                    "switch pattern labels are not supported; "
                                            + "use constant labels only"));
                        }
                        labels.add(new SwitchLabel(Optional.of(value),
                                new SourceSpan(keyword.span().start(), value.span().end())));
                    }
                }
                if (!match(TokenKind.COMMA)) {
                    break;
                }
            }
        }
        Token delimiter;
        boolean arrow;
        if (match(TokenKind.COLON)) {
            delimiter = previous();
            arrow = false;
        } else if (match(TokenKind.ARROW)) {
            delimiter = previous();
            arrow = true;
        } else {
            diagnostics.add(error(peek(), keyword.kind() == TokenKind.DEFAULT
                    ? "expected ':' after 'default' or '->' after switch rule label"
                    : "expected ':' after case label or '->' after switch rule label"));
            delimiter = keyword;
            arrow = false;
        }
        if (labels.isEmpty()) {
            return null;
        }
        return new ParsedSwitchLabels(labels, arrow,
                new SourceSpan(keyword.span().start(), delimiter.span().end()));
    }

    private YieldStatement parseYield(Token keyword) {
        Expression value = parseExpression();
        Token semicolon = expect(TokenKind.SEMICOLON, "expected ';' after yield value");
        SourceSpan end = semicolon == null
                ? value == null ? keyword.span() : value.span() : semicolon.span();
        return value == null ? null : new YieldStatement(value,
                new SourceSpan(keyword.span().start(), end.end()));
    }

    private BreakStatement parseBreak(Token keyword) {
        Optional<String> label = Optional.empty();
        Optional<SourceSpan> labelSpan = Optional.empty();
        if (match(TokenKind.IDENTIFIER)) {
            label = Optional.of(previous().lexeme());
            labelSpan = Optional.of(previous().span());
        }
        Token semicolon = expect(TokenKind.SEMICOLON, "expected ';' after 'break'");
        SourceSpan end = semicolon == null ? labelSpan.orElse(keyword.span()) : semicolon.span();
        return new BreakStatement(label, labelSpan,
                new SourceSpan(keyword.span().start(), end.end()));
    }

    private ContinueStatement parseContinue(Token keyword) {
        Optional<String> label = Optional.empty();
        Optional<SourceSpan> labelSpan = Optional.empty();
        if (match(TokenKind.IDENTIFIER)) {
            label = Optional.of(previous().lexeme());
            labelSpan = Optional.of(previous().span());
        }
        Token semicolon = expect(TokenKind.SEMICOLON, "expected ';' after 'continue'");
        SourceSpan end = semicolon == null ? labelSpan.orElse(keyword.span()) : semicolon.span();
        return new ContinueStatement(label, labelSpan,
                new SourceSpan(keyword.span().start(), end.end()));
    }

    private LabeledStatement parseLabeledStatement() {
        Token label = advance();
        expect(TokenKind.COLON, "expected ':' after statement label");
        if (looksLikeLocalVariableDeclaration()) {
            diagnostics.add(error(peek(),
                    "statement labels cannot directly precede a local variable declaration; use a block"));
        }
        Statement body = parseRequiredStatement("expected statement after label '"
                + label.lexeme() + "'");
        SourceSpan end = body == null ? label.span() : body.span();
        return body == null ? null : new LabeledStatement(label.lexeme(), label.span(), body,
                new SourceSpan(label.span().start(), end.end()));
    }

    private Statement parseRequiredStatement(String message) {
        if (check(TokenKind.EOF) || check(TokenKind.RIGHT_BRACE) || check(TokenKind.ELSE)) {
            diagnostics.add(error(peek(), message));
            return null;
        }
        if (localTypeDeclarationKind() != null) {
            diagnostics.add(error(peek(),
                    "local type declarations must be directly enclosed by a block"));
            parseBlockStatement();
            return null;
        }
        return parseStatement();
    }

    private LocalVariableDeclaration parseLocalVariable() {
        Token start = peek();
        boolean isFinal = parseVariableModifiers("local variable");
        TypeName type = parseType(false, "expected local variable type");
        isFinal = parseMisplacedVariableModifiers("local variable", isFinal);
        Token name = expect(TokenKind.IDENTIFIER, "expected local variable name after type");
        expect(TokenKind.EQUAL, "local variable declarations require an initializer after '='");
        Expression initializer = parseVariableInitializer();
        Token semicolon = expect(TokenKind.SEMICOLON, "expected ';' after local variable declaration");
        SourceSpan end = semicolon == null ? start.span() : semicolon.span();
        if (type == null || name == null || initializer == null) {
            synchronizeStatement();
            return null;
        }
        return new LocalVariableDeclaration(type, isFinal, name.lexeme(), name.span(), initializer,
                new SourceSpan(start.span().start(), end.end()));
    }

    private Statement parseAssignmentOrExpressionStatement() {
        Expression expression = parseExpression();
        if (expression == null) {
            synchronizeStatement();
            return null;
        }
        Token semicolon = expect(TokenKind.SEMICOLON, "expected ';' after expression statement");
        SourceSpan span = semicolon == null
                ? expression.span()
                : new SourceSpan(expression.span().start(), semicolon.span().end());
        if (expression instanceof QualifiedSuperConstructorExpression invocation) {
            return new SuperConstructorInvocation(Optional.of(invocation.enclosingInstance()),
                    invocation.typeArguments(), invocation.arguments(), span);
        }
        return new ExpressionStatement(expression, span);
    }

    private Expression parseExpression() {
        return parseAssignment();
    }

    private Expression parseVariableInitializer() {
        return check(TokenKind.LEFT_BRACE) ? parseArrayInitializer() : parseExpression();
    }

    private ArrayInitializerExpression parseArrayInitializer() {
        Token leftBrace = expect(TokenKind.LEFT_BRACE,
                "expected '{' to begin array initializer");
        if (leftBrace == null) {
            return null;
        }
        List<Expression> elements = new ArrayList<>();
        if (!check(TokenKind.RIGHT_BRACE)) {
            while (true) {
                Expression element = check(TokenKind.LEFT_BRACE)
                        ? parseArrayInitializer() : parseExpression();
                if (element != null) {
                    elements.add(element);
                }
                if (!match(TokenKind.COMMA)) {
                    break;
                }
                if (check(TokenKind.RIGHT_BRACE)) {
                    break;
                }
            }
        }
        Token rightBrace = expect(TokenKind.RIGHT_BRACE,
                "expected '}' to close array initializer");
        SourceSpan end = rightBrace == null
                ? elements.isEmpty() ? leftBrace.span() : elements.getLast().span()
                : rightBrace.span();
        return new ArrayInitializerExpression(elements,
                new SourceSpan(leftBrace.span().start(), end.end()));
    }

    private Expression parseAssignment() {
        Expression target = parseConditional();
        ParsedAssignment operator = matchAssignmentOperator();
        if (operator == null) {
            return target;
        }
        Expression value = parseAssignment();
        if (target == null || value == null) {
            return null;
        }
        return new AssignmentExpression(target, operator.operator(), operator.span(), value,
                new SourceSpan(target.span().start(), value.span().end()));
    }

    private Expression parseConditional() {
        Expression condition = parseLogicalOr();
        if (!match(TokenKind.QUESTION)) {
            return condition;
        }
        Token question = previous();
        Expression whenTrue = parseExpression();
        expect(TokenKind.COLON, "expected ':' in conditional expression");
        Expression whenFalse = parseConditional();
        if (condition == null || whenTrue == null || whenFalse == null) {
            return null;
        }
        return new ConditionalExpression(condition, whenTrue, whenFalse, question.span(),
                new SourceSpan(condition.span().start(), whenFalse.span().end()));
    }

    private Expression parseLogicalOr() {
        Expression expression = parseLogicalAnd();
        while (match(TokenKind.OR_OR)) {
            Token operator = previous();
            expression = binary(expression, operator, parseLogicalAnd(), BinaryOperator.LOGICAL_OR);
        }
        return expression;
    }

    private Expression parseLogicalAnd() {
        Expression expression = parseBitwiseOr();
        while (match(TokenKind.AND_AND)) {
            Token operator = previous();
            expression = binary(expression, operator, parseBitwiseOr(), BinaryOperator.LOGICAL_AND);
        }
        return expression;
    }

    private Expression parseBitwiseOr() {
        Expression expression = parseBitwiseXor();
        while (match(TokenKind.PIPE)) {
            Token operator = previous();
            expression = binary(expression, operator, parseBitwiseXor(), BinaryOperator.BITWISE_OR);
        }
        return expression;
    }

    private Expression parseBitwiseXor() {
        Expression expression = parseBitwiseAnd();
        while (match(TokenKind.CARET)) {
            Token operator = previous();
            expression = binary(expression, operator, parseBitwiseAnd(), BinaryOperator.BITWISE_XOR);
        }
        return expression;
    }

    private Expression parseBitwiseAnd() {
        Expression expression = parseEquality();
        while (match(TokenKind.AMPERSAND)) {
            Token operator = previous();
            expression = binary(expression, operator, parseEquality(), BinaryOperator.BITWISE_AND);
        }
        return expression;
    }

    private Expression parseEquality() {
        Expression expression = parseComparison();
        while (match(TokenKind.EQUAL_EQUAL) || match(TokenKind.BANG_EQUAL)) {
            Token operator = previous();
            Expression right = parseComparison();
            expression = binary(expression, operator, right,
                    operator.kind() == TokenKind.EQUAL_EQUAL ? BinaryOperator.EQUAL : BinaryOperator.NOT_EQUAL);
        }
        return expression;
    }

    private Expression parseComparison() {
        Expression expression = parseShift();
        while (true) {
            if (match(TokenKind.INSTANCEOF)) {
                Token operator = previous();
                boolean bindingFinal = match(TokenKind.FINAL);
                TypeName target = parseType(false, "expected type name after 'instanceof'");
                Token bindingName = null;
                if (target != null && check(TokenKind.IDENTIFIER)) {
                    bindingName = advance();
                } else if (bindingFinal) {
                    diagnostics.add(error(peek(),
                            "expected pattern variable name after instanceof type"));
                }
                if (expression != null && target != null) {
                    Optional<TypePatternBinding> binding = bindingName == null
                            ? Optional.empty()
                            : Optional.of(new TypePatternBinding(bindingName.lexeme(),
                            bindingName.span(), bindingFinal));
                    SourceSpan end = bindingName == null ? target.span() : bindingName.span();
                    expression = new InstanceOfExpression(expression,
                            target, binding, operator.span(),
                            new SourceSpan(expression.span().start(), end.end()));
                }
                continue;
            }
            if (contiguousKinds(TokenKind.LESS, TokenKind.LESS)
                    || contiguousKinds(TokenKind.LESS, TokenKind.LESS_EQUAL)
                    || contiguousKinds(TokenKind.GREATER, TokenKind.GREATER_EQUAL)
                    || contiguousKinds(TokenKind.GREATER, TokenKind.GREATER,
                    TokenKind.GREATER_EQUAL)) {
                break;
            }
            if (!(match(TokenKind.LESS) || match(TokenKind.LESS_EQUAL)
                    || match(TokenKind.GREATER) || match(TokenKind.GREATER_EQUAL))) {
                break;
            }
            Token operator = previous();
            Expression right = parseShift();
            BinaryOperator kind = switch (operator.kind()) {
                case LESS -> BinaryOperator.LESS;
                case LESS_EQUAL -> BinaryOperator.LESS_EQUAL;
                case GREATER -> BinaryOperator.GREATER;
                case GREATER_EQUAL -> BinaryOperator.GREATER_EQUAL;
                default -> throw new IllegalStateException("unexpected comparison operator");
            };
            expression = binary(expression, operator, right, kind);
        }
        return expression;
    }

    private Expression parseShift() {
        Expression expression = parseAdditive();
        ParsedBinary operator;
        while ((operator = matchShiftOperator()) != null) {
            Expression right = parseAdditive();
            if (expression == null || right == null) {
                return null;
            }
            expression = new BinaryExpression(expression, operator.operator(), right,
                    operator.span(), new SourceSpan(expression.span().start(), right.span().end()));
        }
        return expression;
    }

    private Expression parseAdditive() {
        Expression expression = parseMultiplicative();
        while (match(TokenKind.PLUS) || match(TokenKind.MINUS)) {
            Token operator = previous();
            Expression right = parseMultiplicative();
            expression = binary(expression, operator, right,
                    operator.kind() == TokenKind.PLUS ? BinaryOperator.ADD : BinaryOperator.SUBTRACT);
        }
        return expression;
    }

    private Expression parseMultiplicative() {
        Expression expression = parseUnary();
        while (match(TokenKind.STAR) || match(TokenKind.SLASH) || match(TokenKind.PERCENT)) {
            Token operator = previous();
            Expression right = parseUnary();
            BinaryOperator kind = switch (operator.kind()) {
                case STAR -> BinaryOperator.MULTIPLY;
                case SLASH -> BinaryOperator.DIVIDE;
                case PERCENT -> BinaryOperator.REMAINDER;
                default -> throw new IllegalStateException("unexpected multiplicative operator");
            };
            expression = binary(expression, operator, right, kind);
        }
        return expression;
    }

    private Expression parseUnary() {
        if (match(TokenKind.PLUS_PLUS) || match(TokenKind.MINUS_MINUS)) {
            Token operator = previous();
            Expression operand = parseUnary();
            if (operand == null) {
                return null;
            }
            UpdateOperator kind = operator.kind() == TokenKind.PLUS_PLUS
                    ? UpdateOperator.INCREMENT : UpdateOperator.DECREMENT;
            return new UpdateExpression(operand, kind, true, operator.span(),
                    new SourceSpan(operator.span().start(), operand.span().end()));
        }
        if (match(TokenKind.PLUS) || match(TokenKind.MINUS) || match(TokenKind.BANG) || match(TokenKind.TILDE)) {
            Token operator = previous();
            Expression operand = parseUnary();
            if (operand == null) {
                return null;
            }
            UnaryOperator kind = switch (operator.kind()) {
                case PLUS -> UnaryOperator.POSITIVE;
                case MINUS -> UnaryOperator.NEGATE;
                case BANG -> UnaryOperator.NOT;
                case TILDE -> UnaryOperator.BITWISE_COMPLEMENT;
                default -> throw new IllegalStateException("unexpected unary operator");
            };
            return new UnaryExpression(kind, operand, operator.span(),
                    new SourceSpan(operator.span().start(), operand.span().end()));
        }
        if (check(TokenKind.LEFT_PAREN) && looksLikeCast()) {
            Token opening = advance();
            TypeName castType = parseType(false, "expected cast type");
            expect(TokenKind.RIGHT_PAREN, "expected ')' after cast type");
            Expression operand = parseUnary();
            if (castType == null || operand == null) {
                return null;
            }
            return new CastExpression(castType, operand,
                    new SourceSpan(opening.span().start(), operand.span().end()));
        }
        return parsePostfix();
    }

    private Expression parsePostfix() {
        Expression expression = parsePrimary();
        while (expression != null) {
            if (match(TokenKind.LEFT_BRACKET)) {
                Expression index = parseExpression();
                Token right = expect(TokenKind.RIGHT_BRACKET, "expected ']' after array index");
                if (index != null) {
                    SourceSpan end = right == null ? index.span() : right.span();
                    expression = new ArrayAccessExpression(expression, index,
                            new SourceSpan(expression.span().start(), end.end()));
                }
                continue;
            }
            if (match(TokenKind.PLUS_PLUS) || match(TokenKind.MINUS_MINUS)) {
                Token operator = previous();
                UpdateOperator kind = operator.kind() == TokenKind.PLUS_PLUS
                        ? UpdateOperator.INCREMENT : UpdateOperator.DECREMENT;
                expression = new UpdateExpression(expression, kind, false, operator.span(),
                        new SourceSpan(expression.span().start(), operator.span().end()));
                break;
            }
            if (!match(TokenKind.DOT)) {
                break;
            }
            List<TypeName> typeArguments = parseTypeArguments();
            if (match(TokenKind.SUPER)) {
                Token superKeyword = previous();
                if (match(TokenKind.LEFT_PAREN)) {
                    List<Expression> arguments = parseArguments();
                    Token rightParen = expect(TokenKind.RIGHT_PAREN,
                            "expected ')' after superclass constructor arguments");
                    SourceSpan end = rightParen == null ? superKeyword.span() : rightParen.span();
                    expression = new QualifiedSuperConstructorExpression(expression,
                            typeArguments, arguments,
                            new SourceSpan(expression.span().start(), end.end()));
                    continue;
                }
                if (!typeArguments.isEmpty()) {
                    diagnostics.add(Diagnostic.error(source, typeArguments.getFirst().span(),
                            "type arguments before 'super' require a superclass constructor invocation"));
                }
                String interfaceName = sourceQualifiedName(expression);
                if (interfaceName == null) {
                    diagnostics.add(error(superKeyword,
                            "interface super qualifier must be a type name"));
                    return expression;
                }
                expression = new InterfaceSuperExpression(interfaceName, expression.span(),
                        new SourceSpan(expression.span().start(), superKeyword.span().end()));
                continue;
            }
            if (match(TokenKind.THIS)) {
                Token thisKeyword = previous();
                if (!typeArguments.isEmpty()) {
                    diagnostics.add(Diagnostic.error(source, typeArguments.getFirst().span(),
                            "qualified 'this' cannot have type arguments"));
                }
                String typeName = sourceQualifiedName(expression);
                if (typeName == null) {
                    diagnostics.add(error(thisKeyword,
                            "qualified this receiver must be a type name"));
                    return expression;
                }
                expression = new QualifiedThisExpression(typeName, expression.span(),
                        new SourceSpan(expression.span().start(), thisKeyword.span().end()));
                continue;
            }
            if (match(TokenKind.NEW)) {
                if (!typeArguments.isEmpty()) {
                    diagnostics.add(Diagnostic.error(source, typeArguments.getFirst().span(),
                            "constructor type arguments must follow 'new'"));
                }
                expression = finishQualifiedNew(expression, previous());
                continue;
            }
            Token member = expect(TokenKind.IDENTIFIER, "expected member name after '.'");
            if (member == null) {
                return expression;
            }
            if (match(TokenKind.LEFT_PAREN)) {
                expression = finishCall(Optional.of(expression), typeArguments, member);
            } else {
                if (!typeArguments.isEmpty()) {
                    diagnostics.add(Diagnostic.error(source, typeArguments.getFirst().span(),
                            "explicit type arguments require a method invocation"));
                }
                expression = new FieldAccessExpression(expression, member.lexeme(), member.span(),
                        new SourceSpan(expression.span().start(), member.span().end()));
            }
        }
        return expression;
    }

    private Expression parsePrimary() {
        if (match(TokenKind.INTEGER)) {
            return new IntegerLiteralExpression(previous().lexeme(), previous().span());
        }
        if (match(TokenKind.FLOATING)) {
            return new FloatingLiteralExpression(previous().lexeme(), previous().span());
        }
        if (match(TokenKind.CHARACTER)) {
            String value = previous().lexeme();
            return new CharacterLiteralExpression(value.isEmpty() ? 0 : value.charAt(0), previous().span());
        }
        if (match(TokenKind.STRING)) {
            return new StringLiteralExpression(previous().lexeme(), previous().span());
        }
        if (match(TokenKind.TRUE)) {
            return new BooleanLiteralExpression(true, previous().span());
        }
        if (match(TokenKind.FALSE)) {
            return new BooleanLiteralExpression(false, previous().span());
        }
        if (match(TokenKind.NULL)) {
            return new NullLiteralExpression(previous().span());
        }
        if (match(TokenKind.THIS)) {
            return new ThisExpression(previous().span());
        }
        if (match(TokenKind.SUPER)) {
            return new SuperExpression(previous().span());
        }
        if (match(TokenKind.NEW)) {
            return finishNew(previous());
        }
        if (match(TokenKind.SWITCH)) {
            return parseSwitchExpression(previous());
        }
        if (match(TokenKind.IDENTIFIER)) {
            Token name = previous();
            if (match(TokenKind.LEFT_PAREN)) {
                return finishCall(Optional.empty(), name);
            }
            return new NameExpression(name.lexeme(), name.span());
        }
        if (match(TokenKind.LEFT_PAREN)) {
            Expression expression = parseExpression();
            expect(TokenKind.RIGHT_PAREN, "expected ')' after expression");
            return expression;
        }
        diagnostics.add(error(peek(), "expected expression"));
        return null;
    }

    private boolean looksLikeCast() {
        int lookahead = current + 1;
        if (lookahead >= tokens.size()) {
            return false;
        }
        boolean primitive = isPrimitive(tokens.get(lookahead).kind());
        if (primitive) {
            lookahead++;
        } else {
            lookahead = parameterizedTypeEnd(lookahead);
            if (lookahead < 0) {
                return false;
            }
        }
        while (lookahead + 1 < tokens.size()
                && tokens.get(lookahead).kind() == TokenKind.LEFT_BRACKET
                && tokens.get(lookahead + 1).kind() == TokenKind.RIGHT_BRACKET) {
            lookahead += 2;
        }
        if (lookahead >= tokens.size() || tokens.get(lookahead).kind() != TokenKind.RIGHT_PAREN) {
            return false;
        }
        lookahead++;
        if (lookahead >= tokens.size()) {
            return false;
        }
        TokenKind following = tokens.get(lookahead).kind();
        if (!primitive && (following == TokenKind.MINUS || following == TokenKind.PLUS)) {
            return false;
        }
        return switch (following) {
            case IDENTIFIER, INTEGER, FLOATING, CHARACTER, STRING, TRUE, FALSE, NULL, THIS, NEW, SWITCH, LEFT_PAREN,
                    SUPER, MINUS, BANG, TILDE, PLUS_PLUS, MINUS_MINUS -> true;
            default -> false;
        };
    }

    private ParsedAssignment matchAssignmentOperator() {
        AssignmentOperator direct = switch (peek().kind()) {
            case EQUAL -> AssignmentOperator.ASSIGN;
            case PLUS_EQUAL -> AssignmentOperator.ADD;
            case MINUS_EQUAL -> AssignmentOperator.SUBTRACT;
            case STAR_EQUAL -> AssignmentOperator.MULTIPLY;
            case SLASH_EQUAL -> AssignmentOperator.DIVIDE;
            case PERCENT_EQUAL -> AssignmentOperator.REMAINDER;
            case AMPERSAND_EQUAL -> AssignmentOperator.BITWISE_AND;
            case CARET_EQUAL -> AssignmentOperator.BITWISE_XOR;
            case PIPE_EQUAL -> AssignmentOperator.BITWISE_OR;
            default -> null;
        };
        if (direct != null) {
            return new ParsedAssignment(direct, advance().span());
        }
        if (contiguousKinds(TokenKind.LESS, TokenKind.LESS_EQUAL)) {
            Token first = advance();
            Token last = advance();
            return new ParsedAssignment(AssignmentOperator.SHIFT_LEFT,
                    new SourceSpan(first.span().start(), last.span().end()));
        }
        if (contiguousKinds(TokenKind.GREATER, TokenKind.GREATER,
                TokenKind.GREATER_EQUAL)) {
            Token first = advance();
            advance();
            Token last = advance();
            return new ParsedAssignment(AssignmentOperator.UNSIGNED_SHIFT_RIGHT,
                    new SourceSpan(first.span().start(), last.span().end()));
        }
        if (contiguousKinds(TokenKind.GREATER, TokenKind.GREATER_EQUAL)) {
            Token first = advance();
            Token last = advance();
            return new ParsedAssignment(AssignmentOperator.SHIFT_RIGHT,
                    new SourceSpan(first.span().start(), last.span().end()));
        }
        return null;
    }

    private ParsedBinary matchShiftOperator() {
        if (contiguousKinds(TokenKind.LESS, TokenKind.LESS)) {
            Token first = advance();
            Token last = advance();
            return new ParsedBinary(BinaryOperator.SHIFT_LEFT,
                    new SourceSpan(first.span().start(), last.span().end()));
        }
        // A trailing GREATER_EQUAL belongs to >>= or >>>= and must remain for
        // the assignment grammar. Plain nested generic closes are consumed
        // only by parseTypeArguments, never by this expression-only helper.
        if (contiguousKinds(TokenKind.GREATER, TokenKind.GREATER,
                TokenKind.GREATER_EQUAL)
                || contiguousKinds(TokenKind.GREATER, TokenKind.GREATER_EQUAL)) {
            return null;
        }
        if (contiguousKinds(TokenKind.GREATER, TokenKind.GREATER, TokenKind.GREATER)) {
            Token first = advance();
            advance();
            Token last = advance();
            return new ParsedBinary(BinaryOperator.UNSIGNED_SHIFT_RIGHT,
                    new SourceSpan(first.span().start(), last.span().end()));
        }
        if (contiguousKinds(TokenKind.GREATER, TokenKind.GREATER)) {
            Token first = advance();
            Token last = advance();
            return new ParsedBinary(BinaryOperator.SHIFT_RIGHT,
                    new SourceSpan(first.span().start(), last.span().end()));
        }
        return null;
    }

    private boolean contiguousKinds(TokenKind... kinds) {
        if (current + kinds.length > tokens.size()) {
            return false;
        }
        for (int index = 0; index < kinds.length; index++) {
            if (tokens.get(current + index).kind() != kinds[index]) {
                return false;
            }
            if (index > 0 && tokens.get(current + index - 1).span().end().offset()
                    != tokens.get(current + index).span().start().offset()) {
                return false;
            }
        }
        return true;
    }

    private Expression finishNew(Token newKeyword) {
        List<TypeName> constructorTypeArguments = parseTypeArguments();
        TypeName elementType;
        TypeName classType = null;
        boolean diamond = false;
        if (isNumericPrimitive(peek().kind())) {
            Token primitive = advance();
            elementType = TypeName.primitive(primitiveKind(primitive.kind()), primitive.span());
        } else if (match(TokenKind.BOOLEAN)) {
            elementType = TypeName.primitive(TypeName.Kind.BOOLEAN, previous().span());
        } else if (check(TokenKind.IDENTIFIER)) {
            ParameterizedCreationType creationType = parseParameterizedCreationType(
                    "expected type name after 'new'");
            if (creationType == null) {
                return null;
            }
            ParameterizedQualifiedName className = creationType.name();
            diamond = creationType.diamond();
            classType = TypeName.reference(className.text(), className.typeArguments(),
                    className.typeArgumentSegmentCounts(), className.span());
            elementType = classType;
        } else {
            diagnostics.add(error(peek(), "expected type name after 'new'"));
            return null;
        }

        if (match(TokenKind.LEFT_BRACKET)) {
            if (!constructorTypeArguments.isEmpty()) {
                diagnostics.add(Diagnostic.error(source,
                        constructorTypeArguments.getFirst().span(),
                        "explicit constructor type arguments cannot be used for array creation"));
            }
            Token opening = previous();
            boolean initializerCreation = match(TokenKind.RIGHT_BRACKET);
            Expression length = initializerCreation ? null : parseExpression();
            Token rightBracket = initializerCreation ? previous() : expect(TokenKind.RIGHT_BRACKET,
                    "expected ']' after array length");
            SourceSpan end = rightBracket == null
                    ? length == null ? opening.span() : length.span()
                    : rightBracket.span();
            while (match(TokenKind.LEFT_BRACKET)) {
                Token dimensionOpening = previous();
                boolean emptyDimension = match(TokenKind.RIGHT_BRACKET);
                Token dimensionRight = emptyDimension ? previous() : null;
                if (!emptyDimension) {
                    parseExpression();
                    dimensionRight = expect(TokenKind.RIGHT_BRACKET,
                            "expected ']' after array dimension");
                    diagnostics.add(error(dimensionOpening, "array creation may size only the outer dimension; "
                            + "allocate each child array with a separate new expression"));
                }
                SourceSpan dimensionEnd = dimensionRight == null ? previous().span()
                        : dimensionRight.span();
                elementType = TypeName.array(elementType,
                        new SourceSpan(elementType.span().start(), dimensionEnd.end()));
                end = dimensionEnd;
            }
            if (initializerCreation) {
                if (!check(TokenKind.LEFT_BRACE)) {
                    diagnostics.add(error(peek(),
                            "array creation with an unsized dimension requires an initializer"));
                    return null;
                }
                ArrayInitializerExpression initializer = parseArrayInitializer();
                SourceSpan initializerEnd = initializer == null ? end : initializer.span();
                return initializer == null ? null : new ArrayCreationExpression(elementType,
                        Optional.empty(), Optional.of(initializer),
                        new SourceSpan(newKeyword.span().start(), initializerEnd.end()));
            }
            if (check(TokenKind.LEFT_BRACE)) {
                ArrayInitializerExpression initializer = parseArrayInitializer();
                diagnostics.add(error(opening,
                        "array creation cannot combine an explicit length with an initializer"));
                if (initializer != null) {
                    end = initializer.span();
                }
            }
            return length == null ? null : new ArrayCreationExpression(elementType, length,
                    new SourceSpan(newKeyword.span().start(), end.end()));
        }

        if (classType == null) {
            diagnostics.add(error(peek(), "expected '[' after primitive array element type"));
            return null;
        }
        expect(TokenKind.LEFT_PAREN, "expected '(' after class name in object creation");
        List<Expression> arguments = parseArguments();
        Token rightParen = expect(TokenKind.RIGHT_PAREN, "expected ')' after constructor arguments");
        SourceSpan end = rightParen == null ? classType.span() : rightParen.span();
        Optional<AnonymousClassBody> anonymousClassBody = Optional.empty();
        if (check(TokenKind.LEFT_BRACE)) {
            AnonymousClassBody body = parseAnonymousClassBody(false);
            anonymousClassBody = Optional.ofNullable(body);
            if (body != null) {
                end = body.span();
            }
        }
        return new NewExpression(classType, Optional.empty(), constructorTypeArguments,
                diamond, arguments, anonymousClassBody,
                new SourceSpan(newKeyword.span().start(), end.end()));
    }

    private Expression finishQualifiedNew(Expression enclosingInstance, Token newKeyword) {
        List<TypeName> constructorTypeArguments = parseTypeArguments();
        Token className = expect(TokenKind.IDENTIFIER,
                "expected member class name after qualified 'new'");
        if (className == null) {
            return enclosingInstance;
        }
        ParsedTypeArguments parsedTypeArguments = parseTypeArguments(true);
        List<TypeName> typeArguments = parsedTypeArguments.arguments();
        SourceSpan typeSpan = parsedTypeArguments.present()
                ? new SourceSpan(className.span().start(), parsedTypeArguments.span().end())
                : className.span();
        TypeName classType = TypeName.reference(className.lexeme(), typeArguments,
                List.of(typeArguments.size()), typeSpan);
        expect(TokenKind.LEFT_PAREN, "expected '(' after member class name");
        List<Expression> arguments = parseArguments();
        Token rightParen = expect(TokenKind.RIGHT_PAREN, "expected ')' after constructor arguments");
        SourceSpan end = rightParen == null ? classType.span() : rightParen.span();
        Optional<AnonymousClassBody> anonymousClassBody = Optional.empty();
        if (check(TokenKind.LEFT_BRACE)) {
            AnonymousClassBody body = parseAnonymousClassBody(false);
            anonymousClassBody = Optional.ofNullable(body);
            if (body != null) {
                end = body.span();
            }
        }
        return new NewExpression(classType, Optional.of(enclosingInstance),
                constructorTypeArguments, parsedTypeArguments.diamond(), arguments,
                anonymousClassBody,
                new SourceSpan(enclosingInstance.span().start(), end.end()));
    }

    private AnonymousClassBody parseAnonymousClassBody(boolean enumConstantBody) {
        Token leftBrace = expect(TokenKind.LEFT_BRACE,
                "expected '{' before anonymous class body");
        if (leftBrace == null) {
            return null;
        }
        List<FieldDeclaration> fields = new ArrayList<>();
        List<InstanceInitialization> instanceInitializations = new ArrayList<>();
        List<StaticInitialization> staticInitializations = new ArrayList<>();
        List<ConstructorDeclaration> constructors = new ArrayList<>();
        List<DestructorDeclaration> destructors = new ArrayList<>();
        List<MethodDeclaration> methods = new ArrayList<>();
        List<TypeDeclaration> memberTypes = new ArrayList<>();
        while (!check(TokenKind.RIGHT_BRACE) && !check(TokenKind.EOF)) {
            int before = current;
            parseMember(null, fields, instanceInitializations, staticInitializations,
                    constructors, destructors, methods,
                    memberTypes, true, enumConstantBody);
            if (current == before) {
                advance();
            }
        }
        Token rightBrace = expect(TokenKind.RIGHT_BRACE,
                "expected '}' to close anonymous class body");
        if (rightBrace == null) {
            return null;
        }
        return new AnonymousClassBody(fields, instanceInitializations, staticInitializations,
                destructors.stream().findFirst(), methods, memberTypes,
                new SourceSpan(leftBrace.span().start(), rightBrace.span().end()));
    }

    private boolean looksLikeLocalVariableDeclaration() {
        int start = current;
        while (start < tokens.size() && isVariableModifier(tokens.get(start).kind())) {
            start++;
        }
        if (start >= tokens.size()) {
            return false;
        }
        if (isPrimitive(tokens.get(start).kind())) {
            return true;
        }
        if (tokens.get(start).kind() != TokenKind.IDENTIFIER) {
            return false;
        }
        int lookahead = parameterizedTypeEnd(start);
        if (lookahead < 0) {
            return false;
        }
        while (lookahead + 1 < tokens.size()
                && tokens.get(lookahead).kind() == TokenKind.LEFT_BRACKET
                && tokens.get(lookahead + 1).kind() == TokenKind.RIGHT_BRACKET) {
            lookahead += 2;
        }
        return lookahead < tokens.size() && tokens.get(lookahead).kind() == TokenKind.IDENTIFIER;
    }

    private boolean looksLikeEnhancedForInitializer() {
        int lookahead = current;
        while (lookahead < tokens.size() && isVariableModifier(tokens.get(lookahead).kind())) {
            lookahead++;
        }
        if (lookahead >= tokens.size()) {
            return false;
        }
        if (isPrimitive(tokens.get(lookahead).kind())) {
            lookahead++;
        } else if (tokens.get(lookahead).kind() == TokenKind.IDENTIFIER) {
            lookahead = parameterizedTypeEnd(lookahead);
            if (lookahead < 0) {
                return false;
            }
        } else {
            return false;
        }
        while (lookahead + 1 < tokens.size()
                && tokens.get(lookahead).kind() == TokenKind.LEFT_BRACKET
                && tokens.get(lookahead + 1).kind() == TokenKind.RIGHT_BRACKET) {
            lookahead += 2;
        }
        return lookahead + 1 < tokens.size()
                && tokens.get(lookahead).kind() == TokenKind.IDENTIFIER
                && tokens.get(lookahead + 1).kind() == TokenKind.COLON;
    }

    private TokenKind localTypeDeclarationKind() {
        int lookahead = current;
        while (lookahead < tokens.size()) {
            TokenKind kind = tokens.get(lookahead).kind();
            if (kind == TokenKind.AT
                    && lookahead + 1 < tokens.size()
                    && tokens.get(lookahead + 1).kind() == TokenKind.IDENTIFIER
                    && tokens.get(lookahead + 1).lexeme().equals("Override")) {
                lookahead += 2;
                continue;
            }
            if (kind == TokenKind.PUBLIC || kind == TokenKind.PROTECTED
                    || kind == TokenKind.PRIVATE || kind == TokenKind.STATIC
                    || kind == TokenKind.FINAL || kind == TokenKind.ABSTRACT
                    || kind == TokenKind.DEFAULT) {
                lookahead++;
                continue;
            }
            return kind == TokenKind.CLASS || kind == TokenKind.ENUM
                    || kind == TokenKind.INTERFACE ? kind : null;
        }
        return null;
    }

    private int parameterizedTypeEnd(int start) {
        int lookahead = start;
        if (lookahead >= tokens.size() || tokens.get(lookahead).kind() != TokenKind.IDENTIFIER) {
            return -1;
        }
        while (true) {
            lookahead++;
            if (lookahead < tokens.size() && tokens.get(lookahead).kind() == TokenKind.LESS) {
                int depth = 0;
                do {
                    TokenKind kind = tokens.get(lookahead).kind();
                    if (kind == TokenKind.LESS) {
                        depth++;
                    } else if (kind == TokenKind.GREATER) {
                        depth--;
                    }
                    lookahead++;
                } while (lookahead < tokens.size() && depth > 0);
                if (depth != 0) {
                    return -1;
                }
            }
            if (lookahead + 1 >= tokens.size()
                    || tokens.get(lookahead).kind() != TokenKind.DOT
                    || tokens.get(lookahead + 1).kind() != TokenKind.IDENTIFIER) {
                return lookahead;
            }
            lookahead++;
        }
    }

    private static boolean isPrimitive(TokenKind kind) {
        return isNumericPrimitive(kind) || kind == TokenKind.BOOLEAN;
    }

    private static boolean isNumericPrimitive(TokenKind kind) {
        return switch (kind) {
            case BYTE, SHORT, INT, LONG, CHAR, FLOAT, DOUBLE -> true;
            default -> false;
        };
    }

    private static TypeName.Kind primitiveKind(TokenKind kind) {
        return switch (kind) {
            case BYTE -> TypeName.Kind.BYTE;
            case SHORT -> TypeName.Kind.SHORT;
            case INT -> TypeName.Kind.INT;
            case LONG -> TypeName.Kind.LONG;
            case CHAR -> TypeName.Kind.CHAR;
            case FLOAT -> TypeName.Kind.FLOAT;
            case DOUBLE -> TypeName.Kind.DOUBLE;
            default -> throw new IllegalArgumentException("not a numeric primitive token: " + kind);
        };
    }

    private QualifiedName parseQualifiedName(String message, boolean allowWildcard) {
        Token first = expect(TokenKind.IDENTIFIER, message);
        if (first == null) {
            return null;
        }
        StringBuilder text = new StringBuilder(first.lexeme());
        SourceSpan end = first.span();
        boolean wildcard = false;
        while (match(TokenKind.DOT)) {
            if (allowWildcard && match(TokenKind.STAR)) {
                wildcard = true;
                end = previous().span();
                break;
            }
            Token part = expect(TokenKind.IDENTIFIER, "expected identifier after '.'");
            if (part == null) {
                break;
            }
            text.append('.').append(part.lexeme());
            end = part.span();
        }
        return new QualifiedName(text.toString(), wildcard,
                new SourceSpan(first.span().start(), end.end()));
    }

    private ParameterizedQualifiedName parseParameterizedQualifiedName(String message) {
        Token first = expect(TokenKind.IDENTIFIER, message);
        if (first == null) {
            return null;
        }
        StringBuilder text = new StringBuilder(first.lexeme());
        List<TypeName> firstArguments = parseTypeArguments();
        List<TypeName> arguments = new ArrayList<>(firstArguments);
        List<Integer> segmentCounts = new ArrayList<>();
        segmentCounts.add(firstArguments.size());
        SourceSpan end = arguments.isEmpty() ? first.span() : previous().span();
        while (check(TokenKind.DOT) && checkNext(TokenKind.IDENTIFIER)) {
            advance();
            Token part = expect(TokenKind.IDENTIFIER, "expected identifier after '.'");
            if (part == null) {
                break;
            }
            text.append('.').append(part.lexeme());
            end = part.span();
            List<TypeName> segmentArguments = parseTypeArguments();
            segmentCounts.add(segmentArguments.size());
            arguments.addAll(segmentArguments);
            if (!segmentArguments.isEmpty()) {
                end = previous().span();
            }
        }
        return new ParameterizedQualifiedName(text.toString(), List.copyOf(arguments),
                List.copyOf(segmentCounts),
                new SourceSpan(first.span().start(), end.end()));
    }

    private ParameterizedCreationType parseParameterizedCreationType(String message) {
        Token first = expect(TokenKind.IDENTIFIER, message);
        if (first == null) {
            return null;
        }
        StringBuilder text = new StringBuilder(first.lexeme());
        List<TypeName> arguments = new ArrayList<>();
        List<Integer> segmentCounts = new ArrayList<>();
        ParsedTypeArguments segment = parseTypeArguments(true);
        arguments.addAll(segment.arguments());
        segmentCounts.add(segment.arguments().size());
        boolean diamond = segment.diamond();
        SourceSpan end = segment.present() ? segment.span() : first.span();
        while (check(TokenKind.DOT) && checkNext(TokenKind.IDENTIFIER)) {
            advance();
            if (diamond) {
                diagnostics.add(Diagnostic.error(source, segment.span(),
                        "diamond type arguments are only permitted on the final class name segment"));
            }
            Token part = expect(TokenKind.IDENTIFIER, "expected identifier after '.'");
            if (part == null) {
                break;
            }
            text.append('.').append(part.lexeme());
            segment = parseTypeArguments(true);
            arguments.addAll(segment.arguments());
            segmentCounts.add(segment.arguments().size());
            diamond = segment.diamond();
            end = segment.present() ? segment.span() : part.span();
        }
        ParameterizedQualifiedName name = new ParameterizedQualifiedName(text.toString(),
                List.copyOf(arguments), List.copyOf(segmentCounts),
                new SourceSpan(first.span().start(), end.end()));
        return new ParameterizedCreationType(name, diamond);
    }

    private CallExpression finishCall(Optional<Expression> receiver,
                                      List<TypeName> typeArguments, Token name) {
        List<Expression> arguments = parseArguments();
        Token rightParen = expect(TokenKind.RIGHT_PAREN, "expected ')' after method arguments");
        SourceSpan span = rightParen == null
                ? name.span()
                : new SourceSpan(receiver.map(value -> value.span().start()).orElse(name.span().start()),
                        rightParen.span().end());
        return new CallExpression(receiver, typeArguments, name.lexeme(), name.span(), arguments, span);
    }

    private CallExpression finishCall(Optional<Expression> receiver, Token name) {
        return finishCall(receiver, List.of(), name);
    }

    private List<Expression> parseArguments() {
        List<Expression> arguments = new ArrayList<>();
        if (!check(TokenKind.RIGHT_PAREN)) {
            do {
                Expression argument = parseExpression();
                if (argument != null) {
                    arguments.add(argument);
                }
            } while (match(TokenKind.COMMA));
        }
        return arguments;
    }

    private BinaryExpression binary(Expression left, Token token, Expression right, BinaryOperator operator) {
        if (left == null || right == null) {
            return null;
        }
        return new BinaryExpression(left, operator, right, token.span(),
                new SourceSpan(left.span().start(), right.span().end()));
    }

    private void synchronizeParameter() {
        while (!check(TokenKind.EOF) && !check(TokenKind.RIGHT_PAREN) && !check(TokenKind.COMMA)) {
            advance();
        }
    }

    private void synchronizeStatement() {
        while (!check(TokenKind.EOF) && !check(TokenKind.RIGHT_BRACE)) {
            if (match(TokenKind.SEMICOLON)) {
                return;
            }
            advance();
        }
    }

    private void synchronizeMember() {
        while (!check(TokenKind.EOF) && !check(TokenKind.RIGHT_BRACE)) {
            if (match(TokenKind.SEMICOLON)) {
                return;
            }
            advance();
        }
    }

    private void synchronizeTopLevel() {
        while (!check(TokenKind.EOF)) {
            if (check(TokenKind.CLASS) || check(TokenKind.ENUM) || check(TokenKind.INTERFACE)
                    || check(TokenKind.PUBLIC) || check(TokenKind.PROTECTED)
                    || check(TokenKind.PRIVATE) || check(TokenKind.ABSTRACT)
                    || check(TokenKind.DEFAULT) || check(TokenKind.FINAL) || check(TokenKind.STATIC)) {
                return;
            }
            advance();
        }
    }

    private Token expect(TokenKind kind, String message) {
        if (check(kind)) {
            return advance();
        }
        diagnostics.add(error(peek(), message));
        return null;
    }

    private boolean match(TokenKind kind) {
        if (!check(kind)) {
            return false;
        }
        advance();
        return true;
    }

    private boolean check(TokenKind kind) {
        return peek().kind() == kind;
    }

    private boolean checkNext(TokenKind kind) {
        return current + 1 < tokens.size() && tokens.get(current + 1).kind() == kind;
    }

    private boolean looksLikeSwitchPatternLabel() {
        TokenKind first = peek().kind();
        if ((isNumericPrimitive(first) || first == TokenKind.BOOLEAN)
                && current + 1 < tokens.size()
                && tokens.get(current + 1).kind() == TokenKind.IDENTIFIER) {
            return true;
        }
        if (first != TokenKind.IDENTIFIER || current + 1 >= tokens.size()) {
            return false;
        }
        TokenKind second = tokens.get(current + 1).kind();
        if (second == TokenKind.IDENTIFIER) {
            return true;
        }
        if (second != TokenKind.LEFT_PAREN || current + 2 >= tokens.size()) {
            return false;
        }
        TokenKind nested = tokens.get(current + 2).kind();
        if (isNumericPrimitive(nested) || nested == TokenKind.BOOLEAN) {
            return current + 3 < tokens.size()
                    && tokens.get(current + 3).kind() == TokenKind.IDENTIFIER;
        }
        if (nested != TokenKind.IDENTIFIER) {
            return false;
        }
        String text = tokens.get(current + 2).lexeme();
        return text.equals("_") || current + 3 < tokens.size()
                && tokens.get(current + 3).kind() == TokenKind.IDENTIFIER;
    }

    private Token advance() {
        if (!check(TokenKind.EOF)) {
            current++;
        }
        return previous();
    }

    private Token peek() {
        return tokens.get(current);
    }

    private Token previous() {
        return tokens.get(Math.max(0, current - 1));
    }

    private Diagnostic error(Token token, String message) {
        return Diagnostic.error(source, token.span(), message);
    }

    private static String sourceQualifiedName(Expression expression) {
        if (expression instanceof NameExpression name) {
            return name.name();
        }
        if (expression instanceof FieldAccessExpression access) {
            String receiver = sourceQualifiedName(access.receiver());
            return receiver == null ? null : receiver + "." + access.fieldName();
        }
        return null;
    }

    private record Modifiers(AccessModifier accessModifier, Token accessToken,
                             Token staticToken, Token finalToken,
                             Token abstractToken, Token defaultToken,
                             Token overrideToken, Token testToken) {
        private boolean isStatic() {
            return staticToken != null;
        }

        private boolean isFinal() {
            return finalToken != null;
        }

        private boolean isAbstract() {
            return abstractToken != null;
        }

        private boolean isDefault() {
            return defaultToken != null;
        }

        private boolean hasOverrideDirective() {
            return overrideToken != null;
        }

        private boolean hasTestDirective() {
            return testToken != null;
        }
    }

    private record QualifiedName(String text, boolean wildcard, SourceSpan span) {
    }

    private record ParameterizedQualifiedName(String text, List<TypeName> typeArguments,
                                              List<Integer> typeArgumentSegmentCounts,
                                              SourceSpan span) {
    }

    private record ParsedTypeArguments(boolean present, boolean diamond,
                                       List<TypeName> arguments, SourceSpan span) {
        private ParsedTypeArguments {
            arguments = List.copyOf(arguments);
        }

        private static ParsedTypeArguments absent() {
            return new ParsedTypeArguments(false, false, List.of(), null);
        }
    }

    private record ParameterizedCreationType(ParameterizedQualifiedName name,
                                             boolean diamond) {
    }

    private record ParsedAssignment(AssignmentOperator operator, SourceSpan span) {
    }

    private record ParsedBinary(BinaryOperator operator, SourceSpan span) {
    }

    private record ParsedSwitchLabels(List<SwitchLabel> labels, boolean arrowRules,
                                      SourceSpan span) {
        private ParsedSwitchLabels {
            labels = List.copyOf(labels);
        }
    }

    private record ParsedSwitchBody(List<SwitchGroup> groups, List<SwitchRule> rules,
                                    boolean arrowRules, SourceSpan end) {
        private ParsedSwitchBody {
            groups = List.copyOf(groups);
            rules = List.copyOf(rules);
        }
    }
}
