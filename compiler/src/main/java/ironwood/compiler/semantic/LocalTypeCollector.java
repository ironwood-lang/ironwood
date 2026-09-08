// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.ast.AccessModifier;
import ironwood.compiler.ast.AnonymousClassBody;
import ironwood.compiler.ast.ClassDeclaration;
import ironwood.compiler.ast.CompilationUnit;
import ironwood.compiler.ast.EnumConstant;
import ironwood.compiler.ast.InterfaceDeclaration;
import ironwood.compiler.ast.LocalClassDeclaration;
import ironwood.compiler.ast.NewExpression;
import ironwood.compiler.ast.TypeDeclaration;
import ironwood.compiler.ast.TypeName;
import ironwood.compiler.diagnostic.Diagnostic;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Adds local, anonymous, and their member types to the closed-world symbol universe. */
final class LocalTypeCollector {
    Result collect(List<CompilationUnit> units, Map<String, TypeSymbol> types,
                   List<Diagnostic> diagnostics) {
        LexicalTypeScopes lexicalScopes = new LexicalTypeScopes();
        boolean requiresLowering = false;
        for (CompilationUnit unit : units) {
            for (TypeDeclaration root : unit.declarations()) {
                String rootName = unit.packageName().isEmpty()
                        ? root.name() : unit.packageName() + "." + root.name();
                LocalClassSemantics.CaptureResult captureResult =
                        new EffectivelyFinalCaptureAnalyzer().analyze(root, rootName);
                LocalClassDiscovery.Result discovery = captureResult.discovery();
                lexicalScopes.registerDiscovery(unit.source(), discovery);
                lexicalScopes.registerVariables(unit.source(), captureResult.variables());
                for (LocalClassSemantics.CaptureDiagnostic diagnostic
                        : captureResult.diagnostics()) {
                    diagnostics.add(Diagnostic.error(unit.source(), diagnostic.span(),
                            diagnostic.message()));
                }
                for (LocalClassSemantics.ClassIdentity identity : discovery.classes()) {
                    Object node = discovery.nodeFor(identity.binaryName()).orElseThrow();
                    LocalClassSemantics.ClassCapturePlan capturePlan = captureResult
                            .plan(identity.binaryName()).orElseThrow();
                    TypeSymbol symbol = types.get(identity.binaryName());
                    if (symbol == null) {
                        symbol = createSymbol(unit, rootName, identity, node, capturePlan,
                                discovery, diagnostics);
                        TypeSymbol previous = types.putIfAbsent(symbol.name(), symbol);
                        if (previous != null) {
                            diagnostics.add(Diagnostic.error(unit.source(), identity.span(),
                                    "duplicate lexical type identity '" + symbol.name() + "'"));
                            symbol = previous;
                        }
                    } else {
                        symbol.attachLexicalMetadata(identity, capturePlan);
                    }
                    registerNodes(lexicalScopes, unit, discovery, identity, node, symbol);
                    if (identity.kind() == LocalClassSemantics.ClassKind.LOCAL) {
                        LocalClassSemantics.ScopeDescriptor typeScope = discovery.scopeFor(
                                ((LocalClassDeclaration) node).declaration()).orElseThrow();
                        String declarationScope = typeScope.parentId().orElseThrow();
                        lexicalScopes.addLocalBinding(unit.source(),
                                        identity.simpleName().orElseThrow(), declarationScope,
                                        identity.span(), symbol)
                                .ifPresent(previous -> diagnostics.add(Diagnostic.error(
                                        unit.source(), identity.span(), "duplicate local class '"
                                                + identity.simpleName().orElseThrow()
                                                + "' in the same lexical scope")));
                    }
                    requiresLowering |= identity.isLexicalClass();
                }
                registerVariableIdentities(types, captureResult);
            }
        }
        return new Result(lexicalScopes, requiresLowering);
    }

    private static void registerVariableIdentities(
            Map<String, TypeSymbol> types,
            LocalClassSemantics.CaptureResult captureResult) {
        for (LocalClassSemantics.VariableIdentity variable : captureResult.variables()) {
            TypeSymbol owner = types.get(variable.ownerBinaryName());
            if (owner != null) {
                owner.registerVariableSpan(variable.nameSpan(), variable);
            }
        }
        for (LocalClassSemantics.Write write : captureResult.writes()) {
            TypeSymbol owner = types.get(write.variable().ownerBinaryName());
            if (owner != null) {
                owner.registerVariableWrite(write.variable().id());
            }
        }
        for (LocalClassSemantics.ClassCapturePlan plan : captureResult.plans().values()) {
            TypeSymbol capturing = types.get(plan.identity().binaryName());
            if (capturing == null) {
                continue;
            }
            plan.references().forEach(reference ->
                    capturing.registerVariableSpan(reference.span(), reference.variable()));
        }
    }

    private TypeSymbol createSymbol(CompilationUnit unit, String nestHost,
                                    LocalClassSemantics.ClassIdentity identity, Object node,
                                    LocalClassSemantics.ClassCapturePlan capturePlan,
                                    LocalClassDiscovery.Result discovery,
                                    List<Diagnostic> diagnostics) {
        TypeDeclaration declaration;
        Optional<TypeName> anonymousTarget = Optional.empty();
        Optional<NewExpression> anonymousAllocation = Optional.empty();
        if (node instanceof LocalClassDeclaration local) {
            declaration = local.declaration();
        } else if (node instanceof NewExpression allocation) {
            declaration = anonymousDeclaration(identity, allocation);
            anonymousTarget = Optional.of(allocation.classType());
            anonymousAllocation = Optional.of(allocation);
        } else if (node instanceof EnumConstant constant) {
            declaration = enumConstantDeclaration(identity, constant);
        } else {
            declaration = (TypeDeclaration) node;
        }
        validateDeclaration(unit, declaration, identity, discovery, diagnostics);
        LocalClassSemantics.ScopeDescriptor scope = discovery.scopeFor(
                node instanceof LocalClassDeclaration local ? local.declaration()
                        : node instanceof NewExpression allocation
                        ? allocation.anonymousClassBody().orElseThrow() : node).orElseThrow();
        boolean memberType = identity.kind() == LocalClassSemantics.ClassKind.MEMBER;
        boolean staticBoundary = memberType ? declaration.isStatic()
                : capturePlan.directEnclosingInstance().isEmpty();
        boolean innerClass = capturePlan.directEnclosingInstance().isPresent();
        if (identity.kind() == LocalClassSemantics.ClassKind.ANONYMOUS
                && scope.staticContext()) {
            staticBoundary = true;
        }
        TypeSymbol symbol = new TypeSymbol(unit, declaration,
                declaration instanceof InterfaceDeclaration, identity, nestHost,
                memberType, staticBoundary, innerClass, capturePlan,
                anonymousTarget, anonymousAllocation);
        if (node instanceof EnumConstant constant) {
            symbol.attachEnumConstant(constant);
        }
        return symbol;
    }

    private static ClassDeclaration anonymousDeclaration(
            LocalClassSemantics.ClassIdentity identity, NewExpression allocation) {
        AnonymousClassBody body = allocation.anonymousClassBody().orElseThrow();
        String syntheticName = identity.binaryName().substring(
                identity.binaryName().lastIndexOf('$') + 1);
        return new ClassDeclaration(AccessModifier.PACKAGE_PRIVATE,
                false, false, true, syntheticName, allocation.classNameSpan(), List.of(),
                Optional.empty(), List.of(), body.fields(), body.instanceInitializations(),
                body.staticInitializations(),
                List.of(), body.destructor(), body.methods(), body.memberTypes(), body.span());
    }

    private static ClassDeclaration enumConstantDeclaration(
            LocalClassSemantics.ClassIdentity identity, EnumConstant constant) {
        AnonymousClassBody body = constant.classBody().orElseThrow();
        String syntheticName = identity.binaryName().substring(
                identity.binaryName().lastIndexOf('$') + 1);
        return new ClassDeclaration(AccessModifier.PACKAGE_PRIVATE,
                false, false, true, syntheticName, constant.nameSpan(), List.of(),
                Optional.empty(), List.of(), body.fields(), body.instanceInitializations(),
                body.staticInitializations(), List.of(), Optional.empty(), body.methods(),
                body.memberTypes(),
                body.span());
    }

    private static void validateDeclaration(CompilationUnit unit, TypeDeclaration declaration,
                                            LocalClassSemantics.ClassIdentity identity,
                                            LocalClassDiscovery.Result discovery,
                                            List<Diagnostic> diagnostics) {
        if (declaration instanceof ClassDeclaration classDeclaration
                && classDeclaration.isAbstract() && classDeclaration.isFinal()) {
            diagnostics.add(Diagnostic.error(unit.source(), declaration.nameSpan(),
                    "class '" + declaration.name() + "' cannot be both abstract and final"));
        }
        Set<String> parameters = new LinkedHashSet<>();
        declaration.typeParameters().forEach(parameter -> {
            if (!parameters.add(parameter.name())) {
                diagnostics.add(Diagnostic.error(unit.source(), parameter.span(),
                        "duplicate type parameter '" + parameter.name() + "' in type '"
                                + declaration.name() + "'"));
            }
            if (parameter.name().equals(declaration.name())) {
                diagnostics.add(Diagnostic.error(unit.source(), parameter.span(),
                        "type parameter '" + parameter.name()
                                + "' cannot have the same name as its declaring type"));
            }
        });
        if (identity.kind() == LocalClassSemantics.ClassKind.MEMBER) {
            for (String enclosing = identity.enclosingBinaryName().orElse(null);
                 enclosing != null;
                 enclosing = discovery.classNamed(enclosing)
                         .flatMap(LocalClassSemantics.ClassIdentity::enclosingBinaryName)
                         .orElse(null)) {
                LocalClassSemantics.ClassIdentity enclosingIdentity = discovery
                        .classNamed(enclosing).orElse(null);
                if (enclosingIdentity != null && enclosingIdentity.simpleName()
                        .filter(declaration.name()::equals).isPresent()) {
                    diagnostics.add(Diagnostic.error(unit.source(), declaration.nameSpan(),
                            "member type '" + declaration.name()
                                    + "' cannot have the same name as an enclosing type"));
                    break;
                }
            }
        }
    }

    private static void registerNodes(LexicalTypeScopes lexicalScopes, CompilationUnit unit,
                                      LocalClassDiscovery.Result discovery,
                                      LocalClassSemantics.ClassIdentity identity,
                                      Object node, TypeSymbol symbol) {
        lexicalScopes.registerNode(node, symbol);
        lexicalScopes.registerSpan(unit.source(), identity.span(), symbol);
        if (node instanceof LocalClassDeclaration local) {
            lexicalScopes.registerNode(local.declaration(), symbol);
        } else if (node instanceof NewExpression allocation) {
            allocation.anonymousClassBody().ifPresent(body ->
                    lexicalScopes.registerNode(body, symbol));
        } else if (node instanceof EnumConstant constant) {
            constant.classBody().ifPresent(body -> lexicalScopes.registerNode(body, symbol));
        } else if (node instanceof TypeDeclaration declaration) {
            discovery.classFor(declaration).ifPresent(ignored ->
                    lexicalScopes.registerNode(declaration, symbol));
        }
    }

    record Result(LexicalTypeScopes lexicalScopes, boolean requiresFunctionLowering) {
    }
}
