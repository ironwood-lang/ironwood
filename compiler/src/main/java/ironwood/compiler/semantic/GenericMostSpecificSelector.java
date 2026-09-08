// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.ir.IrType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Declaration-aware most-specific selection for already-applicable invocation candidates.
 *
 * <p>Invocation inference may narrow a generic candidate's effective parameter types for one
 * call, but those call-site instantiations do not replace the declaration types used by Java's
 * most-specific test. When the candidate on the right is generic, a fresh, comparison-local
 * inference instead asks whether the declaration on the left can be forwarded to some valid
 * instantiation of the declaration on the right.</p>
 */
final class GenericMostSpecificSelector {
    private final ClassHierarchy hierarchy;
    private final Map<String, TypeVariableSymbol> ambientVariables;

    GenericMostSpecificSelector(ClassHierarchy hierarchy) {
        this(hierarchy, Map.of());
    }

    GenericMostSpecificSelector(ClassHierarchy hierarchy,
                                Map<String, TypeVariableSymbol> ambientVariables) {
        this.hierarchy = Objects.requireNonNull(hierarchy, "hierarchy");
        this.ambientVariables = Map.copyOf(ambientVariables);
    }

    /** Integration entry point for {@link InvocationPlanningContext#selectMostSpecific}. */
    InvocationPlanningResult<InvocationPlan.CandidatePlan> select(
            InvocationPlanningContext.SelectionRequest request) {
        Objects.requireNonNull(request, "request");
        List<InvocationPlan.CandidatePlan> candidates = request.candidates();
        List<InvocationPlan.CandidatePlan> maximal = new ArrayList<>();
        for (int candidateIndex = 0; candidateIndex < candidates.size(); candidateIndex++) {
            InvocationPlan.CandidatePlan candidate = candidates.get(candidateIndex);
            boolean dominated = false;
            for (int otherIndex = 0; otherIndex < candidates.size(); otherIndex++) {
                if (candidateIndex != otherIndex
                        && dominates(candidates.get(otherIndex), candidate, request.phase())) {
                    dominated = true;
                    break;
                }
            }
            if (!dominated) {
                maximal.add(candidate);
            }
        }
        if (maximal.size() == 1) {
            return InvocationPlanningResult.resolved(maximal.getFirst());
        }
        List<InvocationPlan.CandidatePlan> ambiguous = maximal.isEmpty() ? candidates : maximal;
        String signatures = ambiguous.stream().map(GenericMostSpecificSelector::displayIdentity)
                .sorted().reduce((left, right) -> left + ", " + right).orElse("candidates");
        return InvocationPlanningResult.rejected(
                InvocationPlanningResult.PlanningRejection.of(
                        InvocationPlanningResult.PlanningRejection.Code.AMBIGUOUS_INVOCATION,
                        "ambiguous invocation among " + signatures, request.span()));
    }

    /** Package-local relation hook for focused tests and integration diagnostics. */
    boolean dominates(InvocationPlan.CandidatePlan left,
                      InvocationPlan.CandidatePlan right,
                      InvocationPlan.ApplicabilityPhase phase) {
        boolean leftToRight = declarationParametersAreMoreSpecific(left, right, phase);
        if (!leftToRight) {
            return false;
        }
        boolean rightToLeft = declarationParametersAreMoreSpecific(right, left, phase);
        if (!rightToLeft) {
            return true;
        }
        CallableSymbol leftCallable = declarationCallable(left);
        CallableSymbol rightCallable = declarationCallable(right);
        boolean leftCallableNonGeneric = leftCallable.typeVariables().isEmpty();
        boolean rightCallableNonGeneric = rightCallable.typeVariables().isEmpty();
        if (leftCallableNonGeneric != rightCallableNonGeneric) {
            return leftCallableNonGeneric;
        }
        boolean leftOwnerNarrower = properOwnerSubtype(leftCallable.ownerType(),
                rightCallable.ownerType());
        boolean rightOwnerNarrower = properOwnerSubtype(rightCallable.ownerType(),
                leftCallable.ownerType());
        return leftOwnerNarrower && !rightOwnerNarrower;
    }

    private boolean declarationParametersAreMoreSpecific(
            InvocationPlan.CandidatePlan left,
            InvocationPlan.CandidatePlan right,
            InvocationPlan.ApplicabilityPhase phase) {
        int invocationArity = left.arguments().size();
        if (right.arguments().size() != invocationArity
                || left.phase() != phase || right.phase() != phase) {
            return false;
        }
        CallableSymbol leftCallable = declarationCallable(left);
        CallableSymbol rightCallable = declarationCallable(right);
        ParameterComparison comparison = comparisonParameters(leftCallable, rightCallable,
                invocationArity);
        if (!comparison.valid()) {
            return false;
        }
        if (hasPrimitiveSpecialization(left) || hasPrimitiveSpecialization(right)) {
            for (int index = 0; index < invocationArity; index++) {
                if (!hierarchy.isAssignable(right.effectiveParameterTypes().get(index),
                        left.effectiveParameterTypes().get(index), ambientVariables)) {
                    return false;
                }
            }
            return true;
        }
        if (!rightCallable.typeVariables().isEmpty()) {
            CallableSymbol inferenceTarget = withParameters(rightCallable,
                    comparison.rightParameters());
            GenericInferenceSolver.Result inferred = new GenericInferenceSolver(hierarchy,
                    ambientVariables).infer(inferenceTarget, inferenceTarget.typeVariables(),
                    comparison.leftParameters(), Optional.empty());
            return inferred.successful();
        }
        for (int index = 0; index < comparison.leftParameters().size(); index++) {
            if (!hierarchy.isAssignable(comparison.rightParameters().get(index),
                    comparison.leftParameters().get(index), ambientVariables)) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasPrimitiveSpecialization(InvocationPlan.CandidatePlan plan) {
        return plan.inference().substitutions().values().stream().anyMatch(IrType::isPrimitive);
    }

    private static ParameterComparison comparisonParameters(CallableSymbol left,
                                                            CallableSymbol right,
                                                            int invocationArity) {
        List<IrType> leftParameters = invocationParameters(left, invocationArity);
        List<IrType> rightParameters = invocationParameters(right, invocationArity);
        if (leftParameters == null || rightParameters == null) {
            return ParameterComparison.invalid();
        }
        return new ParameterComparison(leftParameters, rightParameters, true);
    }

    private static List<IrType> invocationParameters(CallableSymbol callable,
                                                     int invocationArity) {
        return callable.parameterTypes().size() == invocationArity
                ? callable.parameterTypes() : null;
    }

    private static CallableSymbol declarationCallable(InvocationPlan.CandidatePlan plan) {
        return plan.candidate().callable().substitute(plan.inference().classArguments());
    }

    private static CallableSymbol withParameters(CallableSymbol callable,
                                                 List<IrType> parameterTypes) {
        return new CallableSymbol(callable.ownerType(), callable.sourceName(),
                callable.accessModifier(), callable.isStatic(), callable.kind(),
                callable.returnType(), parameterTypes, callable.parameters(), callable.body(),
                callable.superInvocation(), callable.thisInvocation(), callable.isAbstract(),
                callable.isFinal(), callable.isSynthetic(), callable.nameSpan(), callable.span(),
                callable.linkageName(), callable.enclosingInstanceType(), callable.dispatchKey(),
                callable.declarationId(), callable.typeVariables(), callable.thrownTypes());
    }

    private boolean properOwnerSubtype(String left, String right) {
        return !left.equals(right) && hierarchy.isSubtype(left, right);
    }

    private static String displayIdentity(InvocationPlan.CandidatePlan plan) {
        CallableSymbol callable = plan.candidate().callable();
        return callable.ownerType() + "." + callable.sourceSignatureKey();
    }

    private record ParameterComparison(List<IrType> leftParameters,
                                       List<IrType> rightParameters,
                                       boolean valid) {
        private ParameterComparison {
            leftParameters = List.copyOf(leftParameters);
            rightParameters = List.copyOf(rightParameters);
        }

        private static ParameterComparison invalid() {
            return new ParameterComparison(List.of(), List.of(), false);
        }
    }
}
