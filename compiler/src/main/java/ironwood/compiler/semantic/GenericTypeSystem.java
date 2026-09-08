// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.ast.TypeName;
import ironwood.compiler.ast.TypeParameter;
import ironwood.compiler.diagnostic.Diagnostic;
import ironwood.compiler.ir.IrType;
import ironwood.compiler.source.SourceFile;
import ironwood.compiler.source.SourceSpan;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Central declared-bound, wildcard-containment, and capture-conversion model. */
final class GenericTypeSystem {
    private static final String ROOT_OBJECT = "ironwood.lang.Object";
    private static final String ROOT_THROWABLE = "ironwood.lang.Throwable";
    private static final IrType ROOT_OBJECT_TYPE = IrType.reference(ROOT_OBJECT);

    private final Map<String, TypeSymbol> types;
    private final TypeResolver resolver;
    private final Map<String, TypeVariableSymbol> variables = new LinkedHashMap<>();
    private final List<PendingInstantiation> pendingInstantiations = new ArrayList<>();
    private ClassHierarchy hierarchy;
    private boolean hierarchyReady;

    GenericTypeSystem(Map<String, TypeSymbol> types, TypeResolver resolver) {
        this.types = types;
        this.resolver = resolver;
        types.values().forEach(type -> type.declaredTypeParameters().forEach(variable ->
                variables.put(variable.id(), variable)));
    }

    void initializeDeclaredBounds(List<Diagnostic> diagnostics) {
        Map<TypeVariableSymbol, VisitState> states = new IdentityHashMap<>();
        for (TypeSymbol owner : types.values()) {
            for (TypeVariableSymbol variable : owner.declaredTypeParameters()) {
                computeErasure(owner, variable, states, new ArrayList<>(), diagnostics);
            }
        }
        for (TypeSymbol owner : types.values()) {
            for (TypeVariableSymbol variable : owner.declaredTypeParameters()) {
                resolveBounds(owner, variable, diagnostics);
            }
        }
    }

    List<TypeVariableSymbol> initializeCallableTypeParameters(
            TypeSymbol owner, String declarationId, List<TypeParameter> declarations,
            boolean staticContext, List<Diagnostic> diagnostics) {
        if (declarations.isEmpty()) {
            return List.of();
        }
        List<TypeVariableSymbol> declared = new ArrayList<>();
        Map<String, TypeVariableSymbol> scope = new LinkedHashMap<>();
        for (int index = 0; index < declarations.size(); index++) {
            TypeParameter declaration = declarations.get(index);
            TypeVariableSymbol variable = TypeVariableSymbol.declared(
                    declarationId + "#type-" + index + "#" + declaration.name(), declaration);
            declared.add(variable);
            TypeVariableSymbol previous = scope.putIfAbsent(declaration.name(), variable);
            if (previous != null) {
                diagnostics.add(Diagnostic.error(owner.source(), declaration.span(),
                        "duplicate callable type parameter '" + declaration.name() + "'"));
            }
            variables.put(variable.id(), variable);
        }

        Set<TypeVariableSymbol> callableVariables = Set.copyOf(declared);
        Map<TypeVariableSymbol, VisitState> states = new IdentityHashMap<>();
        for (TypeVariableSymbol variable : declared) {
            computeCallableErasure(owner, variable, scope, callableVariables, staticContext,
                    states, new ArrayList<>(), diagnostics);
        }
        for (TypeVariableSymbol variable : declared) {
            resolveCallableBounds(owner, variable, scope, staticContext, diagnostics);
        }
        for (TypeVariableSymbol variable : declared) {
            SourceSpan span = variable.declaration().orElseThrow().span();
            validateIntersectionConsistency(owner, variable, span, diagnostics);
            variable.upperBounds().forEach(bound ->
                    validateBoundUses(bound, owner.source(), span, diagnostics));
        }
        return List.copyOf(declared);
    }

    void attachHierarchy(ClassHierarchy value) {
        hierarchy = value;
    }

    void markHierarchyReady(List<Diagnostic> diagnostics) {
        hierarchyReady = true;
        List<PendingInstantiation> pending = List.copyOf(pendingInstantiations);
        pendingInstantiations.clear();
        pending.forEach(use -> validateInstantiationNow(use.target(), use.arguments(),
                use.source(), use.span(), diagnostics));
        for (TypeSymbol owner : types.values()) {
            if (!owner.isInterface() && !owner.isAnonymousClass()
                    && !owner.typeParameters().isEmpty()
                    && hierarchy != null && types.containsKey(ROOT_THROWABLE)
                    && hierarchy.isSubtype(owner.name(), ROOT_THROWABLE)) {
                diagnostics.add(Diagnostic.error(owner.source(), owner.declaration().span(),
                        "generic class '" + owner.name()
                                + "' cannot be a direct or indirect subclass of '"
                                + ROOT_THROWABLE + "'"));
            }
            for (TypeVariableSymbol variable : owner.declaredTypeParameters()) {
                SourceSpan span = variable.declaration().orElseThrow().span();
                validateIntersectionConsistency(owner, variable, span, diagnostics);
                variable.upperBounds().forEach(bound ->
                        validateBoundUses(bound, owner.source(), span, diagnostics));
            }
        }
    }

    private void validateIntersectionConsistency(TypeSymbol owner,
                                                 TypeVariableSymbol variable,
                                                 SourceSpan span,
                                                 List<Diagnostic> diagnostics) {
        if (hierarchy == null || variable.upperBounds().size() < 2) {
            return;
        }
        Map<String, IrType> inheritedGenericViews = new LinkedHashMap<>();
        Set<String> reportedDeclarations = new LinkedHashSet<>();
        for (IrType bound : variable.upperBounds()) {
            collectInheritedGenericViews(bound, new LinkedHashSet<>(), inheritedGenericViews,
                    reportedDeclarations, owner, variable, span, diagnostics);
        }
    }

    private void collectInheritedGenericViews(IrType exactType,
                                              Set<String> path,
                                              Map<String, IrType> inheritedGenericViews,
                                              Set<String> reportedDeclarations,
                                              TypeSymbol owner,
                                              TypeVariableSymbol variable,
                                              SourceSpan span,
                                              List<Diagnostic> diagnostics) {
        if (exactType == null || !exactType.isReference()) {
            return;
        }
        String pathKey = (exactType.isTypeParameter() ? "variable:" : "nominal:")
                + exactType.referenceName();
        if (exactType.isNominalReference()) {
            TypeSymbol declaration = types.get(exactType.referenceName());
            if (declaration != null && !declaration.typeParameters().isEmpty()) {
                IrType previous = inheritedGenericViews.putIfAbsent(
                        exactType.referenceName(), exactType);
                if (previous != null && !previous.typeArguments().equals(exactType.typeArguments())
                        && reportedDeclarations.add(exactType.referenceName())) {
                    diagnostics.add(Diagnostic.error(owner.source(), span, "type parameter '"
                            + variable.displayName() + "' has incompatible intersection bounds: "
                            + "generic type '" + exactType.referenceName()
                            + "' is inherited with conflicting exact arguments in '"
                            + previous.displayName() + "' and '" + exactType.displayName() + "'"));
                }
            }
        }
        if (!path.add(pathKey)) {
            return;
        }
        for (IrType parent : hierarchy.directParents(exactType)) {
            collectInheritedGenericViews(parent, path, inheritedGenericViews,
                    reportedDeclarations, owner, variable, span, diagnostics);
        }
        path.remove(pathKey);
    }

    Optional<TypeVariableSymbol> variable(IrType type) {
        return type != null && type.isTypeParameter()
                ? Optional.ofNullable(variables.get(type.referenceName())) : Optional.empty();
    }

    List<IrType> upperBounds(IrType type) {
        return variable(type).map(TypeVariableSymbol::upperBounds)
                .orElseGet(() -> type != null && type.isReference()
                        ? List.of(type) : List.of());
    }

    boolean isCapture(IrType type) {
        return variable(type).map(symbol -> symbol.kind() == TypeVariableSymbol.Kind.CAPTURE)
                .orElse(false);
    }

    IrType captureReceiver(IrType receiver, String siteId) {
        CaptureConversion conversion = planCaptureReceiver(receiver, siteId);
        variables.putAll(conversion.variables());
        return conversion.type();
    }

    CaptureConversion planCaptureReceiver(IrType receiver, String siteId) {
        if (receiver == null || !receiver.isNominalReference()
                || receiver.typeArguments().stream().noneMatch(IrType::isWildcard)) {
            return new CaptureConversion(receiver, Map.of());
        }
        TypeSymbol declaration = types.get(receiver.referenceName());
        if (declaration == null
                || declaration.typeParameters().size() != receiver.typeArguments().size()) {
            return new CaptureConversion(receiver, Map.of());
        }
        List<TypeVariableSymbol> parameters = declaration.typeParameters();
        Map<String, IrType> substitution = new LinkedHashMap<>();
        Map<Integer, TypeVariableSymbol> captures = new LinkedHashMap<>();
        for (int index = 0; index < parameters.size(); index++) {
            IrType argument = receiver.typeArguments().get(index);
            if (!argument.isWildcard()) {
                substitution.put(parameters.get(index).id(), argument);
                continue;
            }
            String id = "$capture#" + siteId + "#" + index + "#capture of "
                    + argument.displayName();
            TypeVariableSymbol capture = TypeVariableSymbol.capture(id,
                    "capture of " + argument.displayName());
            captures.put(index, capture);
            substitution.put(parameters.get(index).id(), capture.irType());
        }
        // Establish erasures first, then rebuild the substitution so recursive
        // declaration bounds see canonical capture IR identities.
        for (Map.Entry<Integer, TypeVariableSymbol> entry : captures.entrySet()) {
            int index = entry.getKey();
            IrType wildcard = receiver.typeArguments().get(index);
            List<IrType> preliminary = captureUpperBounds(parameters.get(index), wildcard,
                    substitution);
            entry.getValue().setUpperBounds(preliminary);
            entry.getValue().setLowerBound(wildcard.wildcardKind() == IrType.WildcardKind.SUPER
                    ? wildcard.wildcardBound() : null);
            substitution.put(parameters.get(index).id(), entry.getValue().irType());
        }
        for (Map.Entry<Integer, TypeVariableSymbol> entry : captures.entrySet()) {
            int index = entry.getKey();
            IrType wildcard = receiver.typeArguments().get(index);
            entry.getValue().setUpperBounds(captureUpperBounds(parameters.get(index), wildcard,
                    substitution));
            substitution.put(parameters.get(index).id(), entry.getValue().irType());
        }
        List<IrType> arguments = new ArrayList<>();
        for (int index = 0; index < parameters.size(); index++) {
            TypeVariableSymbol capture = captures.get(index);
            arguments.add(capture == null ? receiver.typeArguments().get(index) : capture.irType());
        }
        Map<String, TypeVariableSymbol> planned = new LinkedHashMap<>();
        captures.values().forEach(capture -> planned.put(capture.id(), capture));
        return new CaptureConversion(IrType.reference(receiver.referenceName(), arguments),
                Map.copyOf(planned));
    }

    void registerCaptures(Map<String, TypeVariableSymbol> captures) {
        captures.forEach(variables::putIfAbsent);
    }

    void registerSyntheticVariables(List<TypeVariableSymbol> synthetic) {
        synthetic.forEach(variable -> variables.putIfAbsent(variable.id(), variable));
    }

    record CaptureConversion(IrType type, Map<String, TypeVariableSymbol> variables) {
        CaptureConversion {
            variables = Map.copyOf(variables);
        }
    }

    boolean acceptsCapturedWrite(IrType expected, IrType actual) {
        TypeVariableSymbol capture = variable(expected)
                .filter(symbol -> symbol.kind() == TypeVariableSymbol.Kind.CAPTURE)
                .orElse(null);
        if (capture == null || capture.lowerBound().isEmpty()) {
            return false;
        }
        return hierarchy.isAssignable(capture.lowerBound().orElseThrow(), actual);
    }

    boolean compatibleTypeArguments(IrType actual, IrType expected) {
        if (!actual.isNominalReference() || !expected.isNominalReference()
                || !actual.referenceName().equals(expected.referenceName())
                || actual.typeArguments().size() != expected.typeArguments().size()) {
            return false;
        }
        TypeSymbol declaration = types.get(actual.referenceName());
        List<TypeVariableSymbol> parameters = declaration == null
                ? List.of() : declaration.typeParameters();
        Map<String, IrType> actualSubstitution = substitution(parameters, actual.typeArguments());
        for (int index = 0; index < actual.typeArguments().size(); index++) {
            IrType actualArgument = actual.typeArguments().get(index);
            IrType expectedArgument = expected.typeArguments().get(index);
            TypeVariableSymbol parameter = index < parameters.size() ? parameters.get(index) : null;
            if (!argumentContained(actualArgument, expectedArgument, parameter,
                    actualSubstitution)) {
                return false;
            }
        }
        return true;
    }

    private List<IrType> captureUpperBounds(TypeVariableSymbol parameter, IrType wildcard,
                                            Map<String, IrType> substitution) {
        List<IrType> bounds = new ArrayList<>();
        if (wildcard.wildcardKind() == IrType.WildcardKind.EXTENDS) {
            bounds.add(wildcard.wildcardBound());
        }
        parameter.upperBounds().stream().map(bound -> bound.substitute(substitution))
                .filter(bound -> bounds.stream().noneMatch(bound::equals)).forEach(bounds::add);
        return bounds.isEmpty() ? List.of(ROOT_OBJECT_TYPE) : List.copyOf(bounds);
    }

    private boolean argumentContained(IrType actual, IrType expected,
                                      TypeVariableSymbol parameter,
                                      Map<String, IrType> substitution) {
        if (!expected.isWildcard()) {
            return actual.equals(expected);
        }
        if (expected.wildcardKind() == IrType.WildcardKind.UNBOUNDED) {
            return actual.isReference();
        }
        if (expected.wildcardKind() == IrType.WildcardKind.EXTENDS) {
            IrType expectedUpper = expected.wildcardBound();
            return effectiveUpperBounds(actual, parameter, substitution).stream()
                    .anyMatch(upper -> hierarchy.isSubtype(upper, expectedUpper));
        }
        IrType actualLower = effectiveLowerBound(actual);
        return actualLower != null
                && hierarchy.isSubtype(expected.wildcardBound(), actualLower);
    }

    private List<IrType> effectiveUpperBounds(IrType argument,
                                              TypeVariableSymbol parameter,
                                              Map<String, IrType> substitution) {
        List<IrType> bounds = new ArrayList<>();
        if (!argument.isWildcard()) {
            return List.of(argument);
        }
        if (argument.wildcardKind() == IrType.WildcardKind.EXTENDS) {
            bounds.add(argument.wildcardBound());
        }
        if (parameter != null) {
            parameter.upperBounds().stream().map(bound -> bound.substitute(substitution))
                    .filter(bound -> bounds.stream().noneMatch(bound::equals)).forEach(bounds::add);
        }
        return bounds.isEmpty() ? List.of(ROOT_OBJECT_TYPE) : List.copyOf(bounds);
    }

    private static IrType effectiveLowerBound(IrType argument) {
        if (!argument.isWildcard()) {
            return argument;
        }
        return argument.wildcardKind() == IrType.WildcardKind.SUPER
                ? argument.wildcardBound() : null;
    }

    private static Map<String, IrType> substitution(List<TypeVariableSymbol> parameters,
                                                    List<IrType> arguments) {
        Map<String, IrType> result = new LinkedHashMap<>();
        for (int index = 0; index < Math.min(parameters.size(), arguments.size()); index++) {
            result.put(parameters.get(index).id(), arguments.get(index));
        }
        return result;
    }

    void validateInstantiation(TypeSymbol target, List<IrType> arguments,
                               SourceFile source, SourceSpan span,
                               List<Diagnostic> diagnostics) {
        if (target.typeParameters().size() != arguments.size()) {
            return;
        }
        if (!hierarchyReady) {
            pendingInstantiations.add(new PendingInstantiation(target, List.copyOf(arguments),
                    source, span));
            return;
        }
        validateInstantiationNow(target, arguments, source, span, diagnostics);
    }

    private void validateInstantiationNow(TypeSymbol target, List<IrType> arguments,
                                          SourceFile source, SourceSpan span,
                                          List<Diagnostic> diagnostics) {
        if (hierarchy == null || target.typeParameters().size() != arguments.size()) {
            return;
        }
        List<TypeVariableSymbol> parameters = target.typeParameters();
        IrType capturedView = captureReceiver(IrType.reference(target.name(), arguments),
                "$validation:" + target.name() + ":" + span.start().offset());
        List<IrType> validationArguments = capturedView.typeArguments();
        Map<String, IrType> substitution = new LinkedHashMap<>();
        for (int index = 0; index < parameters.size(); index++) {
            substitution.put(parameters.get(index).id(), validationArguments.get(index));
        }
        int declaredOffset = parameters.size() - target.declaredTypeParameters().size();
        for (int index = declaredOffset; index < parameters.size(); index++) {
            TypeVariableSymbol parameter = parameters.get(index);
            IrType actual = arguments.get(index);
            if (actual.isWildcard()) {
                validateWildcardArgument(target, parameter, actual, substitution,
                        source, span, diagnostics);
                continue;
            }
            if (actual.isPrimitive()) {
                if (!parameter.permitsPrimitive()) {
                    diagnostics.add(Diagnostic.error(source, span, "primitive type argument '"
                            + actual.displayName() + "' requires an unbounded type parameter; '"
                            + parameter.displayName() + "' in '" + target.sourceName()
                            + "' declares a reference bound"));
                }
                continue;
            }
            for (IrType declaredBound : parameter.upperBounds()) {
                IrType bound = declaredBound.substitute(substitution);
                if (!hierarchy.isSubtype(actual, bound)) {
                    diagnostics.add(Diagnostic.error(source, span, "type argument '"
                            + actual.displayName() + "' does not satisfy bound '"
                            + bound.displayName() + "' of type parameter '"
                            + parameter.displayName() + "' in '" + target.sourceName() + "'"));
                }
            }
        }
    }

    private void validateWildcardArgument(TypeSymbol target, TypeVariableSymbol parameter,
                                          IrType wildcard, Map<String, IrType> substitution,
                                          SourceFile source, SourceSpan span,
                                          List<Diagnostic> diagnostics) {
        if (wildcard.wildcardKind() == IrType.WildcardKind.UNBOUNDED) {
            return;
        }
        IrType wildcardBound = wildcard.wildcardBound();
        for (IrType declared : parameter.upperBounds()) {
            IrType declaredBound = declared.substitute(substitution);
            boolean valid;
            if (wildcard.wildcardKind() == IrType.WildcardKind.SUPER) {
                valid = hierarchy.isSubtype(wildcardBound, declaredBound);
            } else {
                valid = intersectionMayExist(wildcardBound, declaredBound);
            }
            if (!valid) {
                diagnostics.add(Diagnostic.error(source, span, "wildcard argument '"
                        + wildcard.displayName() + "' is incompatible with bound '"
                        + declaredBound.displayName() + "' of type parameter '"
                        + parameter.displayName() + "' in '" + target.sourceName() + "'"));
            }
        }
    }

    private boolean intersectionMayExist(IrType left, IrType right) {
        if (hierarchy.isSubtype(left, right) || hierarchy.isSubtype(right, left)) {
            return true;
        }
        if (!left.isNominalReference() || !right.isNominalReference()) {
            return left.isTypeParameter() || right.isTypeParameter();
        }
        TypeSymbol leftType = types.get(left.referenceName());
        TypeSymbol rightType = types.get(right.referenceName());
        return leftType == null || rightType == null || leftType.isInterface() || rightType.isInterface();
    }

    private void validateBoundUses(IrType type, SourceFile source, SourceSpan span,
                                   List<Diagnostic> diagnostics) {
        if (type.isArray()) {
            validateBoundUses(type.elementType(), source, span, diagnostics);
            return;
        }
        if (type.isWildcard()) {
            if (type.wildcardBound() != null) {
                validateBoundUses(type.wildcardBound(), source, span, diagnostics);
            }
            return;
        }
        if (!type.isNominalReference()) {
            return;
        }
        TypeSymbol target = types.get(type.referenceName());
        if (target != null) {
            validateInstantiationNow(target, type.typeArguments(), source, span, diagnostics);
        }
        type.typeArguments().forEach(argument ->
                validateBoundUses(argument, source, span, diagnostics));
    }

    private IrType computeErasure(TypeSymbol owner, TypeVariableSymbol variable,
                                  Map<TypeVariableSymbol, VisitState> states,
                                  List<TypeVariableSymbol> path,
                                  List<Diagnostic> diagnostics) {
        VisitState state = states.get(variable);
        if (state == VisitState.DONE) {
            return variable.firstBoundErasure();
        }
        if (state == VisitState.ACTIVE) {
            int start = path.indexOf(variable);
            List<TypeVariableSymbol> cycle = start < 0 ? List.of(variable)
                    : path.subList(start, path.size());
            String names = cycle.stream().map(TypeVariableSymbol::displayName)
                    .reduce((left, right) -> left + " -> " + right).orElse(variable.displayName());
            TypeParameter declaration = variable.declaration().orElseThrow();
            diagnostics.add(Diagnostic.error(owner.source(), declaration.span(),
                    "cyclic type-parameter bound: " + names + " -> " + variable.displayName()));
            variable.setFirstBoundErasure(ROOT_OBJECT_TYPE);
            return ROOT_OBJECT_TYPE;
        }
        states.put(variable, VisitState.ACTIVE);
        path.add(variable);
        TypeParameter declaration = variable.declaration().orElseThrow();
        IrType erasure = ROOT_OBJECT_TYPE;
        if (!declaration.upperBounds().isEmpty()) {
            TypeName first = declaration.upperBounds().getFirst();
            if (first.kind() == TypeName.Kind.REFERENCE) {
                TypeVariableSymbol referenced = owner.typeVariable(first.referenceName()).orElse(null);
                if (referenced != null && first.typeArguments().isEmpty()) {
                    erasure = computeErasure(ownerOf(referenced), referenced, states, path, diagnostics);
                } else {
                    TypeResolver.Resolution resolution = resolver.resolve(first.referenceName(), owner);
                    if (resolution.type().isPresent()) {
                        erasure = IrType.reference(resolution.type().orElseThrow().name());
                    }
                }
            }
        }
        variable.setFirstBoundErasure(erasure);
        path.removeLast();
        states.put(variable, VisitState.DONE);
        return erasure;
    }

    private IrType computeCallableErasure(TypeSymbol owner, TypeVariableSymbol variable,
                                          Map<String, TypeVariableSymbol> scope,
                                          Set<TypeVariableSymbol> callableVariables,
                                          boolean staticContext,
                                          Map<TypeVariableSymbol, VisitState> states,
                                          List<TypeVariableSymbol> path,
                                          List<Diagnostic> diagnostics) {
        VisitState state = states.get(variable);
        if (state == VisitState.DONE) {
            return variable.firstBoundErasure();
        }
        if (state == VisitState.ACTIVE) {
            int start = path.indexOf(variable);
            List<TypeVariableSymbol> cycle = start < 0 ? List.of(variable)
                    : path.subList(start, path.size());
            String names = cycle.stream().map(TypeVariableSymbol::displayName)
                    .reduce((left, right) -> left + " -> " + right)
                    .orElse(variable.displayName());
            TypeParameter declaration = variable.declaration().orElseThrow();
            diagnostics.add(Diagnostic.error(owner.source(), declaration.span(),
                    "cyclic type-parameter bound: " + names + " -> " + variable.displayName()));
            variable.setFirstBoundErasure(ROOT_OBJECT_TYPE);
            return ROOT_OBJECT_TYPE;
        }
        states.put(variable, VisitState.ACTIVE);
        path.add(variable);
        TypeParameter declaration = variable.declaration().orElseThrow();
        IrType erasure = ROOT_OBJECT_TYPE;
        if (!declaration.upperBounds().isEmpty()) {
            TypeName first = declaration.upperBounds().getFirst();
            if (first.kind() == TypeName.Kind.REFERENCE) {
                TypeVariableSymbol referenced = scope.get(first.referenceName());
                if (referenced == null && !staticContext) {
                    referenced = owner.typeVariable(first.referenceName()).orElse(null);
                }
                if (referenced != null && first.typeArguments().isEmpty()) {
                    erasure = callableVariables.contains(referenced)
                            ? computeCallableErasure(owner, referenced, scope, callableVariables,
                            staticContext, states, path, diagnostics)
                            : referenced.firstBoundErasure();
                } else {
                    TypeResolver.Resolution resolution = resolver.resolve(first.referenceName(), owner);
                    if (resolution.type().isPresent()) {
                        erasure = IrType.reference(resolution.type().orElseThrow().name());
                    }
                }
            }
        }
        variable.setFirstBoundErasure(erasure);
        path.removeLast();
        states.put(variable, VisitState.DONE);
        return erasure;
    }

    private TypeSymbol ownerOf(TypeVariableSymbol variable) {
        int separator = variable.id().lastIndexOf('#');
        return types.getOrDefault(separator < 0 ? variable.id()
                : variable.id().substring(0, separator), types.values().iterator().next());
    }

    private void resolveBounds(TypeSymbol owner, TypeVariableSymbol variable,
                               List<Diagnostic> diagnostics) {
        TypeParameter declaration = variable.declaration().orElseThrow();
        if (declaration.upperBounds().isEmpty()) {
            variable.setUpperBounds(List.of(ROOT_OBJECT_TYPE));
            return;
        }
        List<IrType> bounds = new ArrayList<>();
        Map<String, IrType> erasedBounds = new LinkedHashMap<>();
        boolean sawClass = false;
        for (int index = 0; index < declaration.upperBounds().size(); index++) {
            TypeName syntax = declaration.upperBounds().get(index);
            IrType bound = resolveBoundType(owner, syntax, diagnostics);
            if (!bound.isReference() || bound.isArray() || bound.isWildcard()) {
                diagnostics.add(Diagnostic.error(owner.source(), syntax.span(),
                        "type-parameter bound '" + syntax.displayName()
                                + "' must be a class, interface, or type variable"));
                continue;
            }
            if (bound.isTypeParameter()) {
                if (index > 0) {
                    diagnostics.add(Diagnostic.error(owner.source(), syntax.span(),
                            "a type-variable bound may appear only as the first bound"));
                }
            } else {
                TypeSymbol nominal = types.get(bound.referenceName());
                if (nominal != null && !nominal.isInterface()) {
                    if (index > 0) {
                        diagnostics.add(Diagnostic.error(owner.source(), syntax.span(),
                                "class bound '" + syntax.displayName() + "' must be first"));
                    }
                    if (sawClass) {
                        diagnostics.add(Diagnostic.error(owner.source(), syntax.span(),
                                "type parameter '" + variable.displayName()
                                        + "' cannot have more than one class bound"));
                    }
                    sawClass = true;
                }
            }
            IrType erased = bound.erasure();
            IrType previous = erasedBounds.putIfAbsent(erased.displayName(), bound);
            if (previous != null) {
                String detail = previous.equals(bound) ? "repeated bound '"
                        + bound.displayName() + "'" : "conflicting parameterizations '"
                        + previous.displayName() + "' and '" + bound.displayName() + "'";
                diagnostics.add(Diagnostic.error(owner.source(), syntax.span(),
                        "type parameter '" + variable.displayName() + "' has " + detail));
            }
            bounds.add(bound);
        }
        variable.setUpperBounds(bounds.isEmpty() ? List.of(ROOT_OBJECT_TYPE) : bounds);
    }

    private void resolveCallableBounds(TypeSymbol owner, TypeVariableSymbol variable,
                                       Map<String, TypeVariableSymbol> scope,
                                       boolean staticContext,
                                       List<Diagnostic> diagnostics) {
        TypeParameter declaration = variable.declaration().orElseThrow();
        if (declaration.upperBounds().isEmpty()) {
            variable.setUpperBounds(List.of(ROOT_OBJECT_TYPE));
            return;
        }
        List<IrType> bounds = new ArrayList<>();
        Map<String, IrType> erasedBounds = new LinkedHashMap<>();
        boolean sawClass = false;
        for (int index = 0; index < declaration.upperBounds().size(); index++) {
            TypeName syntax = declaration.upperBounds().get(index);
            IrType bound = resolveCallableBoundType(owner, syntax, scope, staticContext, diagnostics);
            if (!bound.isReference() || bound.isArray() || bound.isWildcard()) {
                diagnostics.add(Diagnostic.error(owner.source(), syntax.span(),
                        "type-parameter bound '" + syntax.displayName()
                                + "' must be a class, interface, or type variable"));
                continue;
            }
            if (bound.isTypeParameter()) {
                if (index > 0) {
                    diagnostics.add(Diagnostic.error(owner.source(), syntax.span(),
                            "a type-variable bound may appear only as the first bound"));
                }
            } else {
                TypeSymbol nominal = types.get(bound.referenceName());
                if (nominal != null && !nominal.isInterface()) {
                    if (index > 0) {
                        diagnostics.add(Diagnostic.error(owner.source(), syntax.span(),
                                "class bound '" + syntax.displayName() + "' must be first"));
                    }
                    if (sawClass) {
                        diagnostics.add(Diagnostic.error(owner.source(), syntax.span(),
                                "type parameter '" + variable.displayName()
                                        + "' cannot have more than one class bound"));
                    }
                    sawClass = true;
                }
            }
            IrType erased = bound.erasure();
            IrType previous = erasedBounds.putIfAbsent(erased.displayName(), bound);
            if (previous != null) {
                String detail = previous.equals(bound) ? "repeated bound '"
                        + bound.displayName() + "'" : "conflicting parameterizations '"
                        + previous.displayName() + "' and '" + bound.displayName() + "'";
                diagnostics.add(Diagnostic.error(owner.source(), syntax.span(),
                        "type parameter '" + variable.displayName() + "' has " + detail));
            }
            bounds.add(bound);
        }
        variable.setUpperBounds(bounds.isEmpty() ? List.of(ROOT_OBJECT_TYPE) : bounds);
    }

    private IrType resolveBoundType(TypeSymbol owner, TypeName syntax,
                                    List<Diagnostic> diagnostics) {
        if (syntax.kind() == TypeName.Kind.ARRAY) {
            return IrType.array(resolveBoundType(owner, syntax.elementType(), diagnostics));
        }
        if (syntax.kind() == TypeName.Kind.WILDCARD) {
            return switch (syntax.wildcardKind()) {
                case UNBOUNDED -> IrType.wildcard();
                case EXTENDS -> IrType.wildcardExtends(
                        resolveBoundType(owner, syntax.wildcardBound(), diagnostics));
                case SUPER -> IrType.wildcardSuper(
                        resolveBoundType(owner, syntax.wildcardBound(), diagnostics));
            };
        }
        if (syntax.kind() != TypeName.Kind.REFERENCE) {
            return SemanticAnalyzer.irType(syntax);
        }
        TypeVariableSymbol variable = owner.typeVariable(syntax.referenceName()).orElse(null);
        if (variable != null) {
            if (!syntax.typeArguments().isEmpty()) {
                diagnostics.add(Diagnostic.error(owner.source(), syntax.span(), "type parameter '"
                        + syntax.referenceName() + "' cannot have type arguments"));
            }
            return variable.irType();
        }
        TypeResolver.Resolution resolution = resolver.resolve(syntax.referenceName(), owner);
        TypeSymbol nominal = resolution.type().orElse(null);
        if (nominal == null) {
            diagnostics.add(Diagnostic.error(owner.source(), syntax.span(), "unknown bound type '"
                    + syntax.referenceName() + "'"));
            return ROOT_OBJECT_TYPE;
        }
        List<IrType> arguments = syntax.typeArguments().stream()
                .map(argument -> resolveBoundType(owner, argument, diagnostics)).toList();
        if (nominal.declaredTypeParameters().size() != syntax.lastSegmentTypeArgumentCount()) {
            diagnostics.add(Diagnostic.error(owner.source(), syntax.span(), "generic bound type '"
                    + nominal.sourceName() + "' expects " + nominal.declaredTypeParameters().size()
                    + " type argument(s) but received " + syntax.lastSegmentTypeArgumentCount()));
        }
        return IrType.reference(nominal.name(), arguments);
    }

    private IrType resolveCallableBoundType(TypeSymbol owner, TypeName syntax,
                                            Map<String, TypeVariableSymbol> scope,
                                            boolean staticContext,
                                            List<Diagnostic> diagnostics) {
        if (syntax.kind() == TypeName.Kind.ARRAY) {
            return IrType.array(resolveCallableBoundType(owner, syntax.elementType(), scope,
                    staticContext, diagnostics));
        }
        if (syntax.kind() == TypeName.Kind.WILDCARD) {
            return switch (syntax.wildcardKind()) {
                case UNBOUNDED -> IrType.wildcard();
                case EXTENDS -> IrType.wildcardExtends(resolveCallableBoundType(owner,
                        syntax.wildcardBound(), scope, staticContext, diagnostics));
                case SUPER -> IrType.wildcardSuper(resolveCallableBoundType(owner,
                        syntax.wildcardBound(), scope, staticContext, diagnostics));
            };
        }
        if (syntax.kind() != TypeName.Kind.REFERENCE) {
            return SemanticAnalyzer.irType(syntax);
        }
        TypeVariableSymbol variable = scope.get(syntax.referenceName());
        if (variable == null && !staticContext) {
            variable = owner.typeVariable(syntax.referenceName()).orElse(null);
        }
        if (variable != null) {
            if (!syntax.typeArguments().isEmpty()) {
                diagnostics.add(Diagnostic.error(owner.source(), syntax.span(), "type parameter '"
                        + syntax.referenceName() + "' cannot have type arguments"));
            }
            return variable.irType();
        }
        if (staticContext && owner.typeVariable(syntax.referenceName()).isPresent()) {
            diagnostics.add(Diagnostic.error(owner.source(), syntax.span(), "class type parameter '"
                    + syntax.referenceName() + "' is not available in a static context"));
            return ROOT_OBJECT_TYPE;
        }
        TypeResolver.Resolution resolution = resolver.resolve(syntax.referenceName(), owner);
        TypeSymbol nominal = resolution.type().orElse(null);
        if (nominal == null) {
            diagnostics.add(Diagnostic.error(owner.source(), syntax.span(), "unknown bound type '"
                    + syntax.referenceName() + "'"));
            return ROOT_OBJECT_TYPE;
        }
        List<IrType> arguments = syntax.typeArguments().stream()
                .map(argument -> resolveCallableBoundType(owner, argument, scope,
                        staticContext, diagnostics)).toList();
        if (nominal.declaredTypeParameters().size() != syntax.lastSegmentTypeArgumentCount()) {
            diagnostics.add(Diagnostic.error(owner.source(), syntax.span(), "generic bound type '"
                    + nominal.sourceName() + "' expects " + nominal.declaredTypeParameters().size()
                    + " type argument(s) but received " + syntax.lastSegmentTypeArgumentCount()));
        }
        return IrType.reference(nominal.name(), arguments);
    }

    private enum VisitState {
        ACTIVE,
        DONE
    }

    private record PendingInstantiation(TypeSymbol target, List<IrType> arguments,
                                        SourceFile source, SourceSpan span) {
    }
}
