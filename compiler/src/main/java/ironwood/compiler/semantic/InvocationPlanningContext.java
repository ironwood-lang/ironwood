// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.ast.CallExpression;
import ironwood.compiler.ast.Expression;
import ironwood.compiler.ast.FieldAccessExpression;
import ironwood.compiler.ast.InterfaceSuperExpression;
import ironwood.compiler.ast.NameExpression;
import ironwood.compiler.ast.NewExpression;
import ironwood.compiler.ast.QualifiedThisExpression;
import ironwood.compiler.ast.TypeName;
import ironwood.compiler.ir.IrType;
import ironwood.compiler.source.SourceSpan;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Pure services needed by {@link InvocationPlanner}.
 *
 * <p>Implementations must be deterministic and side-effect-free: in particular, lookups and
 * inference must not emit diagnostics, lower IR, allocate captures in shared registries, or
 * mutate flow state. A caller renders structured rejections only after selecting the operation
 * which actually failed.</p>
 */
interface InvocationPlanningContext {
    InvocationPlanningResult<IrType> lexicalNameType(NameExpression expression);

    InvocationPlanningResult<IrType> resolveType(TypeName typeName, TypeUse use);

    default InvocationPlanningResult<IrType> resolveConstructionTarget(NewExpression expression,
                                                                       TypeUse use) {
        return resolveType(expression.classType(), use);
    }

    default InvocationPlanningResult<ConstructionTargetPlan> planConstructionTarget(
            NewExpression expression, TypeUse use) {
        InvocationPlanningResult<IrType> resolved = resolveConstructionTarget(expression, use);
        if (resolved.isResolved()) {
            return InvocationPlanningResult.resolved(new ConstructionTargetPlan(
                    resolved.resolvedValue(), Optional.empty(), false));
        }
        if (resolved.isRejected()) {
            return InvocationPlanningResult.rejected(resolved.rejections());
        }
        return InvocationPlanningResult.notFound();
    }

    /** Resolves a syntactic expression such as {@code pkg.Type} as a type qualifier. */
    InvocationPlanningResult<IrType> resolveTypeQualifier(Expression expression);

    InvocationPlanningResult<IrType> implicitReceiverType(SourceSpan span);

    default InvocationPlanningResult<IrType> implicitEnclosingInstanceType(IrType required,
                                                                            SourceSpan span) {
        return InvocationPlanningResult.notFound();
    }

    InvocationPlanningResult<IrType> currentThisType(SourceSpan span);

    InvocationPlanningResult<IrType> qualifiedThisType(QualifiedThisExpression expression);

    InvocationPlanningResult<IrType> superType(SourceSpan span);

    InvocationPlanningResult<IrType> interfaceSuperType(InterfaceSuperExpression expression);

    InvocationPlanningResult<ResolvedField> resolveField(FieldLookup lookup);

    InvocationPlanningResult<List<InvocationCandidate>> methodCandidates(MethodLookup lookup);

    InvocationPlanningResult<List<InvocationCandidate>> constructorCandidates(
            ConstructorLookup lookup);

    AccessDecision checkAccess(AccessRequest request);

    InvocationPlanningResult<InvocationPlan.InferenceSolution> infer(InferenceRequest request);

    InvocationPlanningResult<InvocationPlan.InvocationConversion> classifyConversion(
            ConversionRequest request);

    InvocationPlanningResult<InvocationPlan.CandidatePlan> selectMostSpecific(
            SelectionRequest request);

    /** Returns an unambiguous Java-like conditional-expression LUB, or empty if none exists. */
    Optional<IrType> leastUpperBound(IrType left, IrType right);

    /** Snapshot of candidate-local captures accumulated by recursive pure planning. */
    default Map<String, TypeVariableSymbol> plannedCaptures() {
        return Map.of();
    }

    /** Restores a prior candidate-local capture snapshot after an overload probe. */
    default void restorePlannedCaptures(Map<String, TypeVariableSymbol> captures) {
    }

    /** Makes captures from a selected nested or overload plan visible to later pure checks. */
    default void mergePlannedCaptures(Map<String, TypeVariableSymbol> captures) {
    }

    record ResolvedField(IrType type, boolean isStatic, boolean writable, String ownerType) {
        public ResolvedField {
            Objects.requireNonNull(type, "type");
            if (ownerType == null || ownerType.isBlank()) {
                throw new IllegalArgumentException("resolved field requires an owner type");
            }
        }
    }

    record FieldLookup(InvocationPlan.ReceiverPlan receiver,
                       FieldAccessExpression expression) {
        public FieldLookup {
            Objects.requireNonNull(receiver, "receiver");
            Objects.requireNonNull(expression, "expression");
        }
    }

    record MethodLookup(InvocationPlan.ReceiverPlan receiver, CallExpression expression) {
        public MethodLookup {
            Objects.requireNonNull(receiver, "receiver");
            Objects.requireNonNull(expression, "expression");
        }
    }

    record ConstructorLookup(IrType targetType, NewExpression expression) {
        public ConstructorLookup {
            Objects.requireNonNull(targetType, "targetType");
            Objects.requireNonNull(expression, "expression");
        }
    }

    record ConstructionTargetPlan(IrType targetType,
                                  Optional<ExpressionTypePlan> enclosingInstance,
                                  boolean classArgumentsBaked) {
        public ConstructionTargetPlan {
            Objects.requireNonNull(targetType, "targetType");
            enclosingInstance = enclosingInstance == null
                    ? Optional.empty() : enclosingInstance;
        }
    }

    record AccessRequest(InvocationCandidate candidate, InvocationPlan.ReceiverPlan receiver,
                         InvocationPlan.InvocationKind kind, SourceSpan span) {
        public AccessRequest {
            Objects.requireNonNull(candidate, "candidate");
            Objects.requireNonNull(receiver, "receiver");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(span, "span");
        }
    }

    record AccessDecision(boolean accessible,
                          Optional<InvocationPlanningResult.PlanningRejection> rejection) {
        public AccessDecision {
            rejection = rejection == null ? Optional.empty() : rejection;
            if (accessible == rejection.isPresent()) {
                throw new IllegalArgumentException(
                        "access decision must have exactly one of accessibility or rejection");
            }
        }

        static AccessDecision allowed() {
            return new AccessDecision(true, Optional.empty());
        }

        static AccessDecision denied(InvocationPlanningResult.PlanningRejection rejection) {
            return new AccessDecision(false, Optional.of(rejection));
        }
    }

    /** Candidate-local generic constraint problem, including recursive expression probes. */
    record InferenceRequest(InvocationCandidate candidate,
                            InvocationPlan.ApplicabilityPhase phase,
                            List<IrType> unresolvedEffectiveParameterTypes,
                            List<Expression> arguments,
                            List<IrType> explicitClassTypeArguments,
                            boolean diamond,
                            List<IrType> explicitCallableTypeArguments,
                            Optional<IrType> expectedResultType,
                            ExpressionProbe expressionProbe,
                            SourceSpan span) {
        public InferenceRequest {
            Objects.requireNonNull(candidate, "candidate");
            Objects.requireNonNull(phase, "phase");
            unresolvedEffectiveParameterTypes = List.copyOf(unresolvedEffectiveParameterTypes);
            arguments = List.copyOf(arguments);
            explicitClassTypeArguments = List.copyOf(explicitClassTypeArguments);
            explicitCallableTypeArguments = List.copyOf(explicitCallableTypeArguments);
            expectedResultType = expectedResultType == null ? Optional.empty() : expectedResultType;
            Objects.requireNonNull(expressionProbe, "expressionProbe");
            Objects.requireNonNull(span, "span");
        }
    }

    @FunctionalInterface
    interface ExpressionProbe {
        InvocationPlanningResult<ExpressionTypePlan> plan(Expression expression,
                                                          Optional<IrType> expectedType);
    }

    record ConversionRequest(IrType targetType, IrType sourceType,
                             InvocationPlan.ApplicabilityPhase phase, SourceSpan span) {
        public ConversionRequest {
            Objects.requireNonNull(targetType, "targetType");
            Objects.requireNonNull(sourceType, "sourceType");
            Objects.requireNonNull(phase, "phase");
            Objects.requireNonNull(span, "span");
        }
    }

    record SelectionRequest(InvocationPlan.InvocationKind kind,
                            InvocationPlan.ReceiverPlan receiver,
                            InvocationPlan.ApplicabilityPhase phase,
                            List<InvocationPlan.CandidatePlan> candidates,
                            SourceSpan span) {
        public SelectionRequest {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(receiver, "receiver");
            Objects.requireNonNull(phase, "phase");
            candidates = List.copyOf(candidates);
            Objects.requireNonNull(span, "span");
            if (candidates.isEmpty()) {
                throw new IllegalArgumentException("most-specific selection requires candidates");
            }
        }
    }

    enum TypeUse {
        ORDINARY,
        CAST,
        INSTANCEOF,
        ARRAY_ELEMENT,
        EXPLICIT_CLASS_ARGUMENT,
        EXPLICIT_CALLABLE_ARGUMENT,
        CONSTRUCTION_TARGET,
        DIAMOND_TARGET
    }
}
