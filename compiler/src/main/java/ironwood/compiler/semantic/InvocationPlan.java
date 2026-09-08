// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.ast.Expression;
import ironwood.compiler.ir.IrType;
import ironwood.compiler.source.SourceSpan;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** A selected invocation plus every candidate-local applicability decision. */
record InvocationPlan(InvocationKind kind, SourceSpan span, ReceiverPlan receiver,
                      Optional<ExpressionTypePlan> enclosingInstance,
                      CandidatePlan selected, List<CandidatePlan> applicableCandidates,
                      List<CandidateRejection> rejectedCandidates,
                      Optional<IrType> expectedResultType, boolean polyExpression) {
    InvocationPlan {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(receiver, "receiver");
        enclosingInstance = enclosingInstance == null ? Optional.empty() : enclosingInstance;
        Objects.requireNonNull(selected, "selected");
        applicableCandidates = List.copyOf(applicableCandidates);
        rejectedCandidates = List.copyOf(rejectedCandidates);
        expectedResultType = expectedResultType == null ? Optional.empty() : expectedResultType;
        if (!applicableCandidates.contains(selected)) {
            throw new IllegalArgumentException("selected invocation candidate must be applicable");
        }
    }

    IrType resultType() {
        return selected.resultType();
    }

    record ReceiverPlan(ReceiverKind kind, IrType lookupType,
                        Optional<ExpressionTypePlan> valuePlan) {
        ReceiverPlan {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(lookupType, "lookupType");
            valuePlan = valuePlan == null ? Optional.empty() : valuePlan;
            if (kind == ReceiverKind.INSTANCE && valuePlan.isEmpty()) {
                throw new IllegalArgumentException("instance receiver requires a value plan");
            }
            if (kind != ReceiverKind.INSTANCE && valuePlan.isPresent()) {
                throw new IllegalArgumentException("only an instance receiver carries a value plan");
            }
        }
    }

    record CandidatePlan(InvocationCandidate candidate, ApplicabilityPhase phase,
                         InferenceSolution inference,
                         List<IrType> effectiveParameterTypes,
                         List<ArgumentPlan> arguments,
                         Optional<ExpressionTypePlan> enclosingInstance,
                         IrType resultType, boolean polyExpression,
                         Map<String, TypeVariableSymbol> plannedCaptures) {
        CandidatePlan {
            Objects.requireNonNull(candidate, "candidate");
            Objects.requireNonNull(phase, "phase");
            Objects.requireNonNull(inference, "inference");
            effectiveParameterTypes = List.copyOf(effectiveParameterTypes);
            arguments = List.copyOf(arguments);
            enclosingInstance = enclosingInstance == null ? Optional.empty() : enclosingInstance;
            Objects.requireNonNull(resultType, "resultType");
            plannedCaptures = Map.copyOf(new LinkedHashMap<>(plannedCaptures));
            if (effectiveParameterTypes.size() != arguments.size()) {
                throw new IllegalArgumentException(
                        "candidate plan requires one effective parameter per argument");
            }
        }

        CandidatePlan(InvocationCandidate candidate, ApplicabilityPhase phase,
                      InferenceSolution inference, List<IrType> effectiveParameterTypes,
                      List<ArgumentPlan> arguments,
                      Optional<ExpressionTypePlan> enclosingInstance,
                      IrType resultType, boolean polyExpression) {
            this(candidate, phase, inference, effectiveParameterTypes, arguments,
                    enclosingInstance, resultType, polyExpression, Map.of());
        }

        CandidatePlan withPlannedCaptures(Map<String, TypeVariableSymbol> captures) {
            return new CandidatePlan(candidate, phase, inference, effectiveParameterTypes,
                    arguments, enclosingInstance, resultType, polyExpression, captures);
        }
    }

    record ArgumentPlan(Expression expression, IrType parameterType,
                        ExpressionTypePlan expressionPlan, InvocationConversion conversion) {
        ArgumentPlan {
            Objects.requireNonNull(expression, "expression");
            Objects.requireNonNull(parameterType, "parameterType");
            Objects.requireNonNull(expressionPlan, "expressionPlan");
            Objects.requireNonNull(conversion, "conversion");
        }
    }

    record CandidateRejection(InvocationCandidate candidate, ApplicabilityPhase phase,
                              List<InvocationPlanningResult.PlanningRejection> reasons) {
        CandidateRejection {
            Objects.requireNonNull(candidate, "candidate");
            Objects.requireNonNull(phase, "phase");
            reasons = List.copyOf(reasons);
            if (reasons.isEmpty()) {
                throw new IllegalArgumentException("candidate rejection requires a reason");
            }
        }
    }

    record InferenceSolution(Map<String, IrType> classArguments,
                             Map<String, IrType> callableArguments,
                             List<IrType> actualArgumentTypes,
                             Map<String, TypeVariableSymbol> plannedCaptures) {
        InferenceSolution {
            classArguments = Map.copyOf(new LinkedHashMap<>(classArguments));
            callableArguments = Map.copyOf(new LinkedHashMap<>(callableArguments));
            actualArgumentTypes = List.copyOf(actualArgumentTypes);
            plannedCaptures = Map.copyOf(new LinkedHashMap<>(plannedCaptures));
            for (String id : classArguments.keySet()) {
                if (callableArguments.containsKey(id)) {
                    throw new IllegalArgumentException(
                            "duplicate inferred type-variable id '" + id + "'");
                }
            }
        }

        InferenceSolution(Map<String, IrType> classArguments,
                          Map<String, IrType> callableArguments) {
            this(classArguments, callableArguments, List.of(), Map.of());
        }

        static InferenceSolution empty() {
            return new InferenceSolution(Map.of(), Map.of(), List.of(), Map.of());
        }

        Map<String, IrType> substitutions() {
            LinkedHashMap<String, IrType> substitutions = new LinkedHashMap<>(classArguments);
            substitutions.putAll(callableArguments);
            return Map.copyOf(substitutions);
        }

        IrType conversionSourceType(int argumentIndex, IrType fallback) {
            if (argumentIndex < 0 || argumentIndex >= actualArgumentTypes.size()
                    || plannedCaptures.isEmpty()) {
                return fallback;
            }
            IrType inferred = actualArgumentTypes.get(argumentIndex);
            return containsCapture(inferred) ? inferred : fallback;
        }

        private boolean containsCapture(IrType type) {
            if (type.isTypeParameter()) {
                return plannedCaptures.containsKey(type.referenceName());
            }
            if (type.isArray()) {
                return containsCapture(type.elementType());
            }
            if (type.isWildcard() && type.wildcardBound() != null) {
                return containsCapture(type.wildcardBound());
            }
            return type.typeArguments().stream().anyMatch(this::containsCapture);
        }
    }

    record InvocationConversion(ConversionKind kind, IrType sourceType, IrType targetType) {
        InvocationConversion {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(sourceType, "sourceType");
            Objects.requireNonNull(targetType, "targetType");
        }
    }

    enum InvocationKind {
        METHOD,
        CONSTRUCTOR
    }

    enum ReceiverKind {
        IMPLICIT,
        INSTANCE,
        TYPE,
        SUPER,
        INTERFACE_SUPER,
        CONSTRUCTION
    }

    enum ApplicabilityPhase {
        STRICT_FIXED,
        LOOSE_FIXED
    }

    enum ConversionKind {
        IDENTITY,
        NULL_TO_REFERENCE,
        PRIMITIVE_WIDENING,
        REFERENCE_WIDENING,
        BOXING,
        UNBOXING,
        BOXING_WITH_WIDENING,
        UNBOXING_WITH_WIDENING
    }
}
