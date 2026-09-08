// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.ir.IrType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Side-effect-free, candidate-local inference for generic callables.
 *
 * <p>The solver deliberately does not register method variables in the global generic type
 * system. A call-resolution layer supplies the variables declared by one candidate and receives
 * a substituted view of that candidate on success. Erasure, linkage names, and dispatch keys stay
 * those of the declaration through {@link CallableSymbol#substitute(Map)}.</p>
 */
final class GenericInferenceSolver {
    private final ClassHierarchy hierarchy;
    private final Map<String, TypeVariableSymbol> ambientVariables;

    GenericInferenceSolver(ClassHierarchy hierarchy) {
        this(hierarchy, Map.of());
    }

    GenericInferenceSolver(ClassHierarchy hierarchy,
                           Map<String, TypeVariableSymbol> ambientVariables) {
        this.hierarchy = hierarchy;
        this.ambientVariables = Map.copyOf(ambientVariables);
    }

    Result infer(CallableSymbol candidate, List<IrType> actualArgumentTypes,
                 Optional<IrType> expectedReturnType) {
        return infer(candidate, candidate.typeVariables(), actualArgumentTypes, expectedReturnType);
    }

    Result infer(CallableSymbol candidate, List<TypeVariableSymbol> variables,
                 List<IrType> actualArgumentTypes, Optional<IrType> expectedReturnType) {
        return solve(candidate, variables, candidate.parameterTypes(), Optional.empty(),
                actualArgumentTypes, candidate.returnType(), expectedReturnType, Set.of());
    }

    Result infer(CallableSymbol candidate, List<TypeVariableSymbol> variables,
                 List<IrType> formalParameterTypes, List<IrType> actualArgumentTypes,
                 IrType resultTypeTemplate, Optional<IrType> expectedResultType,
                 Set<String> evidenceRequired) {
        return solve(candidate, variables, formalParameterTypes, Optional.empty(),
                actualArgumentTypes, resultTypeTemplate, expectedResultType, evidenceRequired);
    }

    Result instantiate(CallableSymbol candidate, List<IrType> explicitTypeArguments,
                       List<IrType> actualArgumentTypes,
                       Optional<IrType> expectedReturnType) {
        return instantiate(candidate, candidate.typeVariables(), explicitTypeArguments,
                actualArgumentTypes, expectedReturnType);
    }

    Result instantiate(CallableSymbol candidate, List<TypeVariableSymbol> variables,
                       List<IrType> explicitTypeArguments, List<IrType> actualArgumentTypes,
                       Optional<IrType> expectedReturnType) {
        return solve(candidate, variables, candidate.parameterTypes(),
                Optional.of(List.copyOf(explicitTypeArguments)), actualArgumentTypes,
                candidate.returnType(), expectedReturnType, Set.of());
    }

    Result solve(CallableSymbol candidate, List<TypeVariableSymbol> variables,
                 List<IrType> formalParameterTypes,
                 Optional<List<IrType>> explicitTypeArguments,
                 List<IrType> actualArgumentTypes, IrType resultTypeTemplate,
                 Optional<IrType> expectedReturnType, Set<String> evidenceRequired) {
        List<TypeVariableSymbol> localVariables = List.copyOf(variables);
        List<IrType> formalTypes = List.copyOf(formalParameterTypes);
        List<IrType> actualTypes = List.copyOf(actualArgumentTypes);
        IrType resultTemplate = java.util.Objects.requireNonNull(
                resultTypeTemplate, "resultTypeTemplate");
        Optional<IrType> expectedType = expectedReturnType == null
                ? Optional.empty() : expectedReturnType;
        Set<String> requiredEvidence = evidenceRequired == null ? Set.of()
                : java.util.Collections.unmodifiableSet(new LinkedHashSet<>(evidenceRequired));
        if (formalTypes.size() != actualTypes.size()) {
            return Result.failure(FailureKind.ARITY_MISMATCH, "",
                    "candidate requires " + formalTypes.size()
                            + " argument(s) but received " + actualTypes.size());
        }

        Map<String, TypeVariableSymbol> variablesById = new LinkedHashMap<>();
        for (TypeVariableSymbol variable : localVariables) {
            if (variablesById.putIfAbsent(variable.id(), variable) != null) {
                return Result.failure(FailureKind.INVALID_DECLARATION, variable.id(),
                        "duplicate callable type-variable identity " + variable.id());
            }
        }
        for (String id : requiredEvidence) {
            if (!variablesById.containsKey(id)) {
                return Result.failure(FailureKind.INVALID_DECLARATION, id,
                        "required inference evidence names non-local variable " + id);
            }
        }
        if (explicitTypeArguments.isPresent()) {
            return explicitlyInstantiate(candidate, localVariables, variablesById,
                    explicitTypeArguments.orElseThrow(), formalTypes, actualTypes,
                    resultTemplate, expectedType);
        }
        return inferInstantiation(candidate, localVariables, variablesById, formalTypes,
                actualTypes, resultTemplate, expectedType, requiredEvidence);
    }

    private Result explicitlyInstantiate(CallableSymbol candidate,
                                         List<TypeVariableSymbol> variables,
                                         Map<String, TypeVariableSymbol> variablesById,
                                         List<IrType> explicitArguments,
                                         List<IrType> formalTypes,
                                         List<IrType> actualTypes,
                                         IrType resultTypeTemplate,
                                         Optional<IrType> expectedType) {
        if (explicitArguments.size() != variables.size()) {
            return Result.failure(FailureKind.TYPE_ARGUMENT_ARITY_MISMATCH, "",
                    "candidate declares " + variables.size()
                            + " type parameter(s) but received " + explicitArguments.size());
        }
        Map<String, IrType> substitutions = new LinkedHashMap<>();
        for (int index = 0; index < variables.size(); index++) {
            TypeVariableSymbol variable = variables.get(index);
            IrType argument = explicitArguments.get(index);
            if (!isProperTypeArgument(argument)
                    || containsLocalVariable(argument, variablesById.keySet())) {
                return Result.failure(FailureKind.INVALID_EXPLICIT_ARGUMENT, variable.id(),
                        "explicit argument for " + variable.displayName()
                                + " must be a proper reference or primitive type");
            }
            if (argument.isPrimitive() && !variable.permitsPrimitive()) {
                return Result.failure(FailureKind.BOUND_VIOLATION, variable.id(),
                        "primitive argument " + argument.displayName()
                                + " requires an unbounded type parameter "
                                + variable.displayName());
            }
            substitutions.put(variable.id(), argument);
        }
        Optional<Failure> boundFailure = validateDeclarationBounds(variables, substitutions,
                variablesById.keySet());
        if (boundFailure.isPresent()) {
            return Result.failure(boundFailure.orElseThrow());
        }
        return finish(candidate, substitutions, formalTypes, actualTypes, resultTypeTemplate,
                expectedType, variablesById.keySet());
    }

    private Result inferInstantiation(CallableSymbol candidate,
                                      List<TypeVariableSymbol> variables,
                                      Map<String, TypeVariableSymbol> variablesById,
                                      List<IrType> formalTypes,
                                      List<IrType> actualTypes,
                                      IrType resultTypeTemplate,
                                      Optional<IrType> expectedType,
                                      Set<String> evidenceRequired) {
        ConstraintSet constraints = new ConstraintSet(variablesById);
        for (int index = 0; index < actualTypes.size(); index++) {
            constraints.subtype(actualTypes.get(index), formalTypes.get(index),
                    true, "argument " + (index + 1));
            if (constraints.failure().isPresent()) {
                return Result.failure(constraints.failure().orElseThrow());
            }
        }
        expectedType.ifPresent(expected -> constraints.subtype(resultTypeTemplate, expected,
                true, "expected return type"));
        if (constraints.failure().isPresent()) {
            return Result.failure(constraints.failure().orElseThrow());
        }
        for (TypeVariableSymbol variable : variables) {
            if (variable.permitsPrimitive()) {
                continue;
            }
            for (IrType bound : variable.upperBounds()) {
                constraints.addUpper(variable.id(), bound, false,
                        "declared bound of " + variable.displayName());
            }
        }
        Map<String, IrType> substitutions = new LinkedHashMap<>();
        Set<String> expandedBounds = new LinkedHashSet<>();
        Set<String> evidenceSelections = new LinkedHashSet<>();
        boolean progress;
        do {
            progress = false;
            List<List<String>> components = stronglyConnectedComponents(constraints,
                    variablesById.keySet(), substitutions);
            for (List<String> component : components) {
                if (component.stream().allMatch(substitutions::containsKey)) {
                    continue;
                }
                CandidateChoice choice = chooseComponentCandidate(component, constraints,
                        substitutions, variablesById.keySet());
                if (choice.failure().isPresent()) {
                    return Result.failure(choice.failure().orElseThrow());
                }
                if (choice.type().isEmpty()) {
                    continue;
                }
                IrType selected = choice.type().orElseThrow();
                boolean selectedFromEvidence = component.stream()
                        .anyMatch(id -> hasInferenceEvidence(constraints.bounds(id)));
                for (String id : component) {
                    if (!substitutions.containsKey(id)) {
                        substitutions.put(id, selected);
                        if (selectedFromEvidence) {
                            evidenceSelections.add(id);
                        }
                        progress = true;
                    }
                }
            }

            for (TypeVariableSymbol variable : variables) {
                IrType selected = substitutions.get(variable.id());
                if (selected == null || !expandedBounds.add(variable.id())) {
                    continue;
                }
                if (selected.isPrimitive() && variable.permitsPrimitive()) {
                    continue;
                }
                for (IrType bound : variable.upperBounds()) {
                    constraints.subtype(selected, bound.substitute(substitutions),
                            evidenceSelections.contains(variable.id()),
                            "declared bound of " + variable.displayName());
                    if (constraints.failure().isPresent()) {
                        return Result.failure(constraints.failure().orElseThrow());
                    }
                }
            }

            if (!progress) {
                for (TypeVariableSymbol variable : variables) {
                    if (substitutions.containsKey(variable.id())
                            || hasInferenceEvidence(constraints.bounds(variable.id()))) {
                        continue;
                    }
                    List<IrType> declaredUppers = variable.upperBounds().stream()
                            .map(bound -> bound.substitute(substitutions)).toList();
                    if (declaredUppers.stream().anyMatch(bound ->
                            containsUnresolvedVariable(bound, variablesById.keySet(),
                                    substitutions))) {
                        continue;
                    }
                    Optional<IrType> principal = uniqueMostSpecificUpper(declaredUppers);
                    if (principal.isEmpty()) {
                        return Result.failure(FailureKind.AMBIGUOUS_UPPER_BOUND, variable.id(),
                                "declared bounds of " + variable.displayName()
                                        + " have no unique principal instantiation: "
                                        + displayTypes(declaredUppers));
                    }
                    substitutions.put(variable.id(), principal.orElseThrow());
                    progress = true;
                    break;
                }
            }
        } while (progress);

        for (String id : evidenceRequired) {
            if (!evidenceSelections.contains(id)
                    && !hasInferenceEvidence(constraints.bounds(id))) {
                TypeVariableSymbol variable = variablesById.get(id);
                return Result.failure(FailureKind.UNCONSTRAINED_VARIABLE, id,
                        "cannot infer diamond type argument " + variable.displayName()
                                + " without non-null constructor or expected-type evidence");
            }
        }

        for (TypeVariableSymbol variable : variables) {
            if (!substitutions.containsKey(variable.id())) {
                return Result.failure(FailureKind.UNCONSTRAINED_VARIABLE, variable.id(),
                        "cannot infer " + variable.displayName()
                                + " from non-null invocation or expected-type evidence");
            }
        }
        Optional<Failure> constraintFailure = validateConstraints(constraints, substitutions,
                variablesById.keySet());
        if (constraintFailure.isPresent()) {
            return Result.failure(constraintFailure.orElseThrow());
        }
        Optional<Failure> boundFailure = validateDeclarationBounds(variables, substitutions,
                variablesById.keySet());
        if (boundFailure.isPresent()) {
            return Result.failure(boundFailure.orElseThrow());
        }
        return finish(candidate, substitutions, formalTypes, actualTypes, resultTypeTemplate,
                expectedType, variablesById.keySet());
    }

    private static boolean hasInferenceEvidence(VariableBounds bounds) {
        return bounds.equalities().stream().anyMatch(Bound::evidence)
                || bounds.lowers().stream().anyMatch(Bound::evidence)
                || bounds.uppers().stream().anyMatch(Bound::evidence);
    }

    private CandidateChoice chooseComponentCandidate(List<String> component,
                                                      ConstraintSet constraints,
                                                      Map<String, IrType> substitutions,
                                                      Set<String> localIds) {
        List<IrType> equalities = new ArrayList<>();
        List<IrType> lowers = new ArrayList<>();
        List<IrType> selectionUppers = new ArrayList<>();
        boolean hasUpperEvidence = false;
        boolean hasDeferredEqualityOrLower = false;
        for (String id : component) {
            VariableBounds bounds = constraints.bounds(id);
            for (Bound equality : bounds.equalities()) {
                IrType type = equality.type().substitute(substitutions);
                if (containsUnresolvedVariable(type, localIds, substitutions)) {
                    if (!isBareVariableIn(type, component)) {
                        hasDeferredEqualityOrLower = true;
                    }
                } else {
                    addDistinct(equalities, type);
                }
            }
            for (Bound lower : bounds.lowers()) {
                IrType type = lower.type().substitute(substitutions);
                if (type.equals(IrType.NULL)) {
                    continue;
                }
                if (containsUnresolvedVariable(type, localIds, substitutions)) {
                    if (!isBareVariableIn(type, component)) {
                        hasDeferredEqualityOrLower = true;
                    }
                } else {
                    addDistinct(lowers, type);
                }
            }
            for (Bound upper : bounds.uppers()) {
                IrType type = upper.type().substitute(substitutions);
                if (!containsUnresolvedVariable(type, localIds, substitutions)) {
                    addDistinct(selectionUppers, type);
                    hasUpperEvidence |= upper.evidence();
                }
            }
        }
        String variableId = component.getFirst();
        if (hasDeferredEqualityOrLower) {
            return CandidateChoice.unresolved();
        }
        for (IrType equality : equalities) {
            if (!isProperTypeArgument(equality)) {
                return CandidateChoice.failure(FailureKind.CONSTRAINT_MISMATCH, variableId,
                        "equality constraint is not a proper reference type: "
                                + equality.displayName());
            }
        }
        if (equalities.size() > 1) {
            return CandidateChoice.failure(FailureKind.CONSTRAINT_MISMATCH, variableId,
                    "incompatible invariant equality constraints: "
                            + displayTypes(equalities));
        }

        IrType selected;
        if (!equalities.isEmpty()) {
            selected = equalities.getFirst();
            for (IrType lower : lowers) {
                if (!isProperTypeArgument(lower) || !isSubtype(lower, selected)) {
                    return CandidateChoice.failure(FailureKind.CONSTRAINT_MISMATCH, variableId,
                            lower.displayName() + " does not satisfy invariant inference "
                                    + selected.displayName());
                }
            }
        } else if (!lowers.isEmpty()) {
            Optional<IrType> lub = uniqueLeastUpperBound(lowers);
            if (lub.isEmpty()) {
                return CandidateChoice.failure(FailureKind.AMBIGUOUS_LUB, variableId,
                        "no unique safe least upper bound for " + displayTypes(lowers));
            }
            selected = lub.orElseThrow();
        } else if (hasUpperEvidence) {
            Optional<IrType> upper = uniqueMostSpecificUpper(selectionUppers);
            if (upper.isEmpty()) {
                return CandidateChoice.failure(FailureKind.AMBIGUOUS_UPPER_BOUND, variableId,
                        "expected-type constraints have no unique most-specific choice: "
                                + displayTypes(selectionUppers));
            }
            selected = upper.orElseThrow();
        } else {
            return CandidateChoice.unresolved();
        }
        return CandidateChoice.success(selected);
    }

    private Optional<Failure> validateConstraints(ConstraintSet constraints,
                                                  Map<String, IrType> substitutions,
                                                  Set<String> localIds) {
        for (Map.Entry<String, VariableBounds> entry : constraints.allBounds().entrySet()) {
            IrType selected = substitutions.get(entry.getKey());
            if (selected == null) {
                continue;
            }
            for (Bound equality : entry.getValue().equalities()) {
                IrType required = equality.type().substitute(substitutions);
                if (containsUnresolvedVariable(required, localIds, substitutions)
                        || !selected.equals(required)) {
                    return Optional.of(new Failure(FailureKind.CONSTRAINT_MISMATCH,
                            entry.getKey(), selected.displayName()
                                    + " does not equal invariant constraint "
                                    + required.displayName()));
                }
            }
            for (Bound lower : entry.getValue().lowers()) {
                IrType required = lower.type().substitute(substitutions);
                if (required.equals(IrType.NULL)) {
                    continue;
                }
                if (containsUnresolvedVariable(required, localIds, substitutions)
                        || !isSubtype(required, selected)) {
                    return Optional.of(new Failure(FailureKind.CONSTRAINT_MISMATCH,
                            entry.getKey(), required.displayName() + " is not a subtype of "
                                    + selected.displayName()));
                }
            }
            for (Bound upper : entry.getValue().uppers()) {
                IrType required = upper.type().substitute(substitutions);
                if (containsUnresolvedVariable(required, localIds, substitutions)
                        || !isSubtype(selected, required)) {
                    return Optional.of(new Failure(FailureKind.BOUND_VIOLATION,
                            entry.getKey(), selected.displayName() + " is not a subtype of "
                                    + required.displayName()));
                }
            }
        }
        return Optional.empty();
    }

    private Optional<Failure> validateDeclarationBounds(List<TypeVariableSymbol> variables,
                                                        Map<String, IrType> substitutions,
                                                        Set<String> localIds) {
        for (TypeVariableSymbol variable : variables) {
            IrType selected = substitutions.get(variable.id());
            if (selected == null) {
                continue;
            }
            if (selected.isPrimitive()) {
                if (!variable.permitsPrimitive()) {
                    return Optional.of(new Failure(FailureKind.BOUND_VIOLATION,
                            variable.id(), "primitive argument " + selected.displayName()
                                    + " requires an unbounded type parameter "
                                    + variable.displayName()));
                }
                continue;
            }
            for (IrType declaredBound : variable.upperBounds()) {
                IrType bound = declaredBound.substitute(substitutions);
                if (containsUnresolvedVariable(bound, localIds, substitutions)
                        || !isSubtype(selected, bound)) {
                    return Optional.of(new Failure(FailureKind.BOUND_VIOLATION, variable.id(),
                            selected.displayName() + " does not satisfy declared bound "
                                    + bound.displayName()));
                }
            }
        }
        return Optional.empty();
    }

    private Result finish(CallableSymbol declaration, Map<String, IrType> substitutions,
                          List<IrType> formalTypes, List<IrType> actualTypes,
                          IrType resultTypeTemplate, Optional<IrType> expectedType,
                          Set<String> localIds) {
        Map<String, IrType> immutableSubstitutions = Map.copyOf(substitutions);
        CallableSymbol instantiated = declaration.substitute(immutableSubstitutions);
        for (int index = 0; index < actualTypes.size(); index++) {
            IrType formal = formalTypes.get(index).substitute(immutableSubstitutions);
            if (containsUnresolvedVariable(formal, localIds, substitutions)
                    || !isAssignable(formal, actualTypes.get(index))) {
                return Result.failure(FailureKind.INAPPLICABLE_ARGUMENT, "",
                        "argument " + (index + 1) + " of type "
                                + actualTypes.get(index).displayName()
                                + " is not assignable to " + formal.displayName());
            }
        }
        if (expectedType.isPresent()) {
            IrType expected = expectedType.orElseThrow();
            IrType returned = resultTypeTemplate.substitute(immutableSubstitutions);
            if (containsUnresolvedVariable(returned, localIds, substitutions)
                    || !isAssignable(expected, returned)) {
                return Result.failure(FailureKind.EXPECTED_TYPE_MISMATCH, "",
                        returned.displayName() + " is not assignable to expected type "
                                + expected.displayName());
            }
        }
        return Result.success(instantiated, immutableSubstitutions);
    }

    private Optional<IrType> uniqueLeastUpperBound(List<IrType> types) {
        if (types.stream().anyMatch(type -> !isProperTypeArgument(type))) {
            return Optional.empty();
        }
        LinkedHashSet<IrType> candidateSet = new LinkedHashSet<>(
                supertypesInclusive(types.getFirst()));
        candidateSet.addAll(leastContainingParameterizedTypes(types));
        List<IrType> candidates = new ArrayList<>(candidateSet);
        candidates.removeIf(candidate -> types.stream()
                .anyMatch(type -> !isSubtype(type, candidate)));
        List<IrType> minimal = candidates.stream().filter(candidate -> candidates.stream()
                .noneMatch(other -> !other.equals(candidate)
                        && isSubtype(other, candidate)
                        && !isSubtype(candidate, other)))
                .sorted(Comparator.comparing(IrType::displayName)).toList();
        return minimal.size() == 1 ? Optional.of(minimal.getFirst()) : Optional.empty();
    }

    /**
     * Builds the representable least-containing parameterization for each common generic view.
     * Exact nominal supertypes alone are insufficient for inputs such as {@code List<String>}
     * and {@code List<Object>}: their principal common view is {@code List<?>}, which is not an
     * exact supertype declared by either input.
     */
    private List<IrType> leastContainingParameterizedTypes(List<IrType> types) {
        List<IrType> result = new ArrayList<>();
        for (IrType firstView : supertypesInclusive(types.getFirst())) {
            if (!firstView.isNominalReference() || firstView.typeArguments().isEmpty()) {
                continue;
            }
            List<IrType> exactViews = new ArrayList<>();
            exactViews.add(firstView);
            boolean commonView = true;
            for (int index = 1; index < types.size(); index++) {
                Optional<IrType> projection = exactProjection(types.get(index),
                        firstView.referenceName());
                if (projection.isEmpty()
                        || projection.orElseThrow().typeArguments().size()
                        != firstView.typeArguments().size()) {
                    commonView = false;
                    break;
                }
                exactViews.add(projection.orElseThrow());
            }
            if (!commonView) {
                continue;
            }
            Optional<IrType> containing = leastContainingParameterizedType(exactViews);
            containing.filter(candidate -> types.stream()
                            .allMatch(type -> isSubtype(type, candidate)))
                    .ifPresent(candidate -> addDistinct(result, candidate));
        }
        return List.copyOf(result);
    }

    private Optional<IrType> leastContainingParameterizedType(List<IrType> exactViews) {
        IrType first = exactViews.getFirst();
        List<IrType> arguments = new ArrayList<>();
        for (int index = 0; index < first.typeArguments().size(); index++) {
            List<IrType> position = new ArrayList<>();
            for (IrType view : exactViews) {
                position.add(view.typeArguments().get(index));
            }
            Optional<IrType> containing = leastContainingArgument(position);
            if (containing.isEmpty()) {
                return Optional.empty();
            }
            arguments.add(containing.orElseThrow());
        }
        return Optional.of(IrType.reference(first.referenceName(), arguments));
    }

    private Optional<IrType> leastContainingArgument(List<IrType> arguments) {
        List<IrType> distinct = arguments.stream().distinct().toList();
        if (distinct.size() == 1) {
            return Optional.of(distinct.getFirst());
        }
        if (distinct.stream().anyMatch(argument -> !argument.isReference())) {
            return Optional.empty();
        }
        // A lower-bounded or already-unbounded existential has no more specific common
        // covariant projection without computing a representable greatest lower bound.
        if (distinct.stream().anyMatch(argument -> argument.isWildcard()
                && argument.wildcardKind() != IrType.WildcardKind.EXTENDS)) {
            return Optional.of(IrType.wildcard());
        }
        List<IrType> upperBounds = distinct.stream().map(argument -> argument.isWildcard()
                ? argument.wildcardBound() : argument).toList();
        Optional<IrType> upper = uniqueLeastUpperBound(upperBounds);
        if (upper.isEmpty()) {
            return Optional.empty();
        }
        IrType bound = upper.orElseThrow();
        return bound.equals(IrType.reference("ironwood.lang.Object"))
                ? Optional.of(IrType.wildcard())
                : Optional.of(IrType.wildcardExtends(bound));
    }

    private Optional<IrType> exactProjection(IrType actual, String expectedRawType) {
        if (actual.isNominalReference() && actual.referenceName().equals(expectedRawType)) {
            return Optional.of(actual);
        }
        List<IrType> projections = exactSupertypes(actual).stream()
                .filter(type -> type.isNominalReference()
                        && type.referenceName().equals(expectedRawType))
                .distinct().toList();
        return projections.size() == 1 ? Optional.of(projections.getFirst()) : Optional.empty();
    }

    private Optional<IrType> uniqueMostSpecificUpper(List<IrType> upperBounds) {
        List<IrType> candidates = upperBounds.stream().filter(this::isProperTypeArgument)
                .filter(candidate -> upperBounds.stream()
                        .allMatch(upper -> isSubtype(candidate, upper)))
                .distinct().sorted(Comparator.comparing(IrType::displayName)).toList();
        List<IrType> minimal = candidates.stream().filter(candidate -> candidates.stream()
                .noneMatch(other -> !other.equals(candidate)
                        && isSubtype(other, candidate)
                        && !isSubtype(candidate, other))).toList();
        return minimal.size() == 1 ? Optional.of(minimal.getFirst()) : Optional.empty();
    }

    private List<IrType> supertypesInclusive(IrType type) {
        LinkedHashSet<IrType> result = new LinkedHashSet<>();
        result.add(type);
        if (type.isNominalReference() || type.isTypeParameter()) {
            result.addAll(exactSupertypes(type));
        }
        if (type.isReference()) {
            result.add(IrType.reference("ironwood.lang.Object"));
        }
        return List.copyOf(result);
    }

    private List<List<String>> stronglyConnectedComponents(ConstraintSet constraints,
                                                           Set<String> variableIds,
                                                           Map<String, IrType> substitutions) {
        Map<String, Set<String>> graph = new LinkedHashMap<>();
        for (String id : variableIds) {
            if (substitutions.containsKey(id)) {
                continue;
            }
            LinkedHashSet<String> dependencies = new LinkedHashSet<>();
            VariableBounds bounds = constraints.bounds(id);
            collectBareDependencies(bounds.equalities(), variableIds, substitutions,
                    dependencies);
            collectBareDependencies(bounds.lowers(), variableIds, substitutions, dependencies);
            collectBareDependencies(bounds.uppers(), variableIds, substitutions, dependencies);
            graph.put(id, dependencies);
        }
        Tarjan tarjan = new Tarjan(graph);
        return tarjan.components();
    }

    private static void collectBareDependencies(Collection<Bound> bounds,
                                                Set<String> variableIds,
                                                Map<String, IrType> substitutions,
                                                Set<String> result) {
        for (Bound bound : bounds) {
            IrType type = bound.type().substitute(substitutions);
            if (type.isTypeParameter() && variableIds.contains(type.referenceName())
                    && !substitutions.containsKey(type.referenceName())) {
                result.add(type.referenceName());
            }
        }
    }

    private static boolean containsUnresolvedVariable(IrType type, Set<String> localIds,
                                                      Map<String, IrType> substitutions) {
        IrType substituted = type.substitute(substitutions);
        if (substituted.isTypeParameter()) {
            return localIds.contains(substituted.referenceName())
                    && !substitutions.containsKey(substituted.referenceName());
        }
        if (substituted.isNominalReference()) {
            return substituted.typeArguments().stream().anyMatch(argument ->
                    containsUnresolvedVariable(argument, localIds, substitutions));
        }
        if (substituted.isArray()) {
            return containsUnresolvedVariable(substituted.elementType(), localIds, substitutions);
        }
        return substituted.isWildcard() && substituted.wildcardBound() != null
                && containsUnresolvedVariable(substituted.wildcardBound(), localIds,
                substitutions);
    }

    private static boolean containsLocalVariable(IrType type, Set<String> localIds) {
        if (type.isTypeParameter()) {
            return localIds.contains(type.referenceName());
        }
        if (type.isNominalReference()) {
            return type.typeArguments().stream()
                    .anyMatch(argument -> containsLocalVariable(argument, localIds));
        }
        if (type.isArray()) {
            return containsLocalVariable(type.elementType(), localIds);
        }
        return type.isWildcard() && type.wildcardBound() != null
                && containsLocalVariable(type.wildcardBound(), localIds);
    }

    private static boolean isBareVariableIn(IrType type, List<String> component) {
        return type.isTypeParameter() && component.contains(type.referenceName());
    }

    private boolean isProperTypeArgument(IrType type) {
        return type != null && (type.isReference() || type.isPrimitive())
                && !type.equals(IrType.NULL) && !type.isWildcard();
    }

    private static void addDistinct(List<IrType> types, IrType type) {
        if (!types.contains(type)) {
            types.add(type);
        }
    }

    private static String displayTypes(List<IrType> types) {
        return types.stream().map(IrType::displayName)
                .reduce((left, right) -> left + ", " + right).orElse("");
    }

    private boolean isSubtype(IrType actual, IrType expected) {
        return hierarchy.isSubtype(actual, expected, ambientVariables);
    }

    private boolean isAssignable(IrType expected, IrType actual) {
        return hierarchy.isAssignable(expected, actual, ambientVariables);
    }

    private List<IrType> exactSupertypes(IrType type) {
        return hierarchy.exactSupertypes(type, ambientVariables);
    }

    enum FailureKind {
        ARITY_MISMATCH,
        TYPE_ARGUMENT_ARITY_MISMATCH,
        INVALID_DECLARATION,
        INVALID_EXPLICIT_ARGUMENT,
        CONSTRAINT_MISMATCH,
        UNCONSTRAINED_VARIABLE,
        AMBIGUOUS_LUB,
        AMBIGUOUS_UPPER_BOUND,
        BOUND_VIOLATION,
        INAPPLICABLE_ARGUMENT,
        EXPECTED_TYPE_MISMATCH
    }

    record Failure(FailureKind kind, String variableId, String message) {
        Failure {
            variableId = variableId == null ? "" : variableId;
        }
    }

    record Instantiation(CallableSymbol callable, Map<String, IrType> substitutions) {
        Instantiation {
            substitutions = Map.copyOf(substitutions);
        }
    }

    record Result(Optional<Instantiation> instantiation, Optional<Failure> failure) {
        Result {
            instantiation = instantiation == null ? Optional.empty() : instantiation;
            failure = failure == null ? Optional.empty() : failure;
            if (instantiation.isPresent() == failure.isPresent()) {
                throw new IllegalArgumentException(
                        "generic inference must produce exactly one of success or failure");
            }
        }

        static Result success(CallableSymbol callable, Map<String, IrType> substitutions) {
            return new Result(Optional.of(new Instantiation(callable, substitutions)),
                    Optional.empty());
        }

        static Result failure(FailureKind kind, String variableId, String message) {
            return failure(new Failure(kind, variableId, message));
        }

        static Result failure(Failure failure) {
            return new Result(Optional.empty(), Optional.of(failure));
        }

        boolean successful() {
            return instantiation.isPresent();
        }
    }

    private record Bound(IrType type, boolean evidence, String origin) {
    }

    private record VariableBounds(List<Bound> equalities, List<Bound> lowers,
                                  List<Bound> uppers) {
        private VariableBounds() {
            this(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        }
    }

    private record CandidateChoice(Optional<IrType> type, Optional<Failure> failure) {
        private static CandidateChoice success(IrType type) {
            return new CandidateChoice(Optional.of(type), Optional.empty());
        }

        private static CandidateChoice unresolved() {
            return new CandidateChoice(Optional.empty(), Optional.empty());
        }

        private static CandidateChoice failure(FailureKind kind, String variableId,
                                               String message) {
            return new CandidateChoice(Optional.empty(),
                    Optional.of(new Failure(kind, variableId, message)));
        }
    }

    private final class ConstraintSet {
        private final Map<String, TypeVariableSymbol> variables;
        private final Map<String, VariableBounds> bounds = new LinkedHashMap<>();
        private Failure failure;

        private ConstraintSet(Map<String, TypeVariableSymbol> variables) {
            this.variables = variables;
            variables.keySet().forEach(id -> bounds.put(id, new VariableBounds()));
        }

        private Map<String, VariableBounds> allBounds() {
            return bounds;
        }

        private VariableBounds bounds(String id) {
            return bounds.get(id);
        }

        private Optional<Failure> failure() {
            return Optional.ofNullable(failure);
        }

        private void addEquality(String id, IrType type, boolean evidence, String origin) {
            addDistinct(bounds(id).equalities(), new Bound(type, evidence, origin));
        }

        private void addLower(String id, IrType type, boolean evidence, String origin) {
            if (!type.equals(IrType.NULL)) {
                addDistinct(bounds(id).lowers(), new Bound(type, evidence, origin));
            }
        }

        private void addUpper(String id, IrType type, boolean evidence, String origin) {
            addDistinct(bounds(id).uppers(), new Bound(type, evidence, origin));
        }

        private void subtype(IrType actual, IrType formal, boolean evidence, String origin) {
            if (failure != null) {
                return;
            }
            if (actual.equals(IrType.NULL)) {
                if (!formal.isReference()) {
                    fail(FailureKind.CONSTRAINT_MISMATCH, "",
                            origin + " cannot convert null to " + formal.displayName());
                }
                return;
            }
            if (isLocalVariable(formal)) {
                addLower(formal.referenceName(), actual, evidence, origin);
                return;
            }
            if (isLocalVariable(actual)) {
                addUpper(actual.referenceName(), formal, evidence, origin);
                return;
            }
            if (actual.isArray() && formal.isArray()) {
                equal(actual.elementType(), formal.elementType(), evidence, origin);
                return;
            }
            if (actual.isNominalReference() && formal.isNominalReference()) {
                Optional<IrType> projection = exactProjection(actual, formal.referenceName());
                if (projection.isEmpty()) {
                    if (formal.typeArguments().isEmpty()
                            && isSubtype(actual, formal)) {
                        return;
                    }
                    fail(FailureKind.CONSTRAINT_MISMATCH, "", origin + " type "
                            + actual.displayName() + " is not a subtype of "
                            + formal.displayName());
                    return;
                }
                IrType exactActual = projection.orElseThrow();
                if (exactActual.typeArguments().size() != formal.typeArguments().size()) {
                    fail(FailureKind.CONSTRAINT_MISMATCH, "", origin
                            + " has incompatible generic arity");
                    return;
                }
                for (int index = 0; index < formal.typeArguments().size(); index++) {
                    containArgument(exactActual.typeArguments().get(index),
                            formal.typeArguments().get(index), evidence, origin);
                }
                return;
            }
            if (!isAssignable(formal, actual)) {
                fail(FailureKind.CONSTRAINT_MISMATCH, "", origin + " type "
                        + actual.displayName() + " is not assignable to "
                        + formal.displayName());
            }
        }

        private void containArgument(IrType actual, IrType formal, boolean evidence,
                                     String origin) {
            if (formal.isWildcard()) {
                switch (formal.wildcardKind()) {
                    case UNBOUNDED -> {
                        if (!actual.isReference()) {
                            fail(FailureKind.CONSTRAINT_MISMATCH, "", origin
                                    + " has a non-reference wildcard argument");
                        }
                    }
                    case EXTENDS -> {
                        IrType upper = actual.isWildcard()
                                ? actual.wildcardKind() == IrType.WildcardKind.EXTENDS
                                ? actual.wildcardBound()
                                : IrType.reference("ironwood.lang.Object")
                                : actual;
                        subtype(upper, formal.wildcardBound(), evidence, origin);
                    }
                    case SUPER -> {
                        IrType lower = actual.isWildcard()
                                && actual.wildcardKind() == IrType.WildcardKind.SUPER
                                ? actual.wildcardBound() : actual.isWildcard() ? null : actual;
                        if (lower == null) {
                            fail(FailureKind.CONSTRAINT_MISMATCH, "", origin
                                    + " wildcard has no lower bound");
                        } else {
                            subtype(formal.wildcardBound(), lower, evidence, origin);
                        }
                    }
                }
                return;
            }
            if (actual.isWildcard()) {
                fail(FailureKind.CONSTRAINT_MISMATCH, "", origin
                        + " cannot satisfy an invariant argument with "
                        + actual.displayName());
                return;
            }
            equal(actual, formal, evidence, origin);
        }

        private void equal(IrType left, IrType right, boolean evidence, String origin) {
            if (failure != null || left.equals(right)) {
                return;
            }
            if (isLocalVariable(left)) {
                addEquality(left.referenceName(), right, evidence, origin);
                if (isLocalVariable(right)) {
                    addEquality(right.referenceName(), left, evidence, origin);
                }
                return;
            }
            if (isLocalVariable(right)) {
                addEquality(right.referenceName(), left, evidence, origin);
                return;
            }
            if (left.isArray() && right.isArray()) {
                equal(left.elementType(), right.elementType(), evidence, origin);
                return;
            }
            if (left.isNominalReference() && right.isNominalReference()
                    && left.referenceName().equals(right.referenceName())
                    && left.typeArguments().size() == right.typeArguments().size()) {
                for (int index = 0; index < left.typeArguments().size(); index++) {
                    if (left.typeArguments().get(index).isWildcard()
                            || right.typeArguments().get(index).isWildcard()) {
                        fail(FailureKind.CONSTRAINT_MISMATCH, "", origin
                                + " requires invariant proper type arguments");
                        return;
                    }
                    equal(left.typeArguments().get(index), right.typeArguments().get(index),
                            evidence, origin);
                }
                return;
            }
            fail(FailureKind.CONSTRAINT_MISMATCH, "", origin + " requires invariant equality of "
                    + left.displayName() + " and " + right.displayName());
        }

        private Optional<IrType> exactProjection(IrType actual, String expectedRawType) {
            if (actual.referenceName().equals(expectedRawType)) {
                return Optional.of(actual);
            }
            List<IrType> projections = exactSupertypes(actual).stream()
                    .filter(type -> type.isNominalReference()
                            && type.referenceName().equals(expectedRawType))
                    .distinct().toList();
            return projections.size() == 1 ? Optional.of(projections.getFirst())
                    : Optional.empty();
        }

        private boolean isLocalVariable(IrType type) {
            return type.isTypeParameter() && variables.containsKey(type.referenceName());
        }

        private void fail(FailureKind kind, String variableId, String message) {
            if (failure == null) {
                failure = new Failure(kind, variableId, message);
            }
        }

        private <T> void addDistinct(List<T> values, T value) {
            if (!values.contains(value)) {
                values.add(value);
            }
        }
    }

    /** Deterministic Tarjan traversal over declaration-order maps and sets. */
    private static final class Tarjan {
        private final Map<String, Set<String>> graph;
        private final Map<String, Integer> indices = new LinkedHashMap<>();
        private final Map<String, Integer> lowLinks = new LinkedHashMap<>();
        private final Deque<String> stack = new ArrayDeque<>();
        private final Set<String> onStack = new LinkedHashSet<>();
        private final List<List<String>> components = new ArrayList<>();
        private int nextIndex;

        private Tarjan(Map<String, Set<String>> graph) {
            this.graph = graph;
        }

        private List<List<String>> components() {
            graph.keySet().forEach(node -> {
                if (!indices.containsKey(node)) {
                    visit(node);
                }
            });
            return List.copyOf(components);
        }

        private void visit(String node) {
            indices.put(node, nextIndex);
            lowLinks.put(node, nextIndex);
            nextIndex++;
            stack.push(node);
            onStack.add(node);
            for (String dependency : graph.getOrDefault(node, Set.of())) {
                if (!graph.containsKey(dependency)) {
                    continue;
                }
                if (!indices.containsKey(dependency)) {
                    visit(dependency);
                    lowLinks.put(node, Math.min(lowLinks.get(node), lowLinks.get(dependency)));
                } else if (onStack.contains(dependency)) {
                    lowLinks.put(node, Math.min(lowLinks.get(node), indices.get(dependency)));
                }
            }
            if (!lowLinks.get(node).equals(indices.get(node))) {
                return;
            }
            List<String> component = new ArrayList<>();
            String member;
            do {
                member = stack.pop();
                onStack.remove(member);
                component.add(member);
            } while (!member.equals(node));
            component.sort(Comparator.naturalOrder());
            components.add(List.copyOf(component));
        }
    }
}
