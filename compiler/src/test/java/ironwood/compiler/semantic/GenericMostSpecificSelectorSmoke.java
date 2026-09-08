// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.ast.AccessModifier;
import ironwood.compiler.ast.NameExpression;
import ironwood.compiler.ast.TypeParameter;
import ironwood.compiler.ir.IrType;
import ironwood.compiler.ir.IrCallableKind;
import ironwood.compiler.source.SourcePosition;
import ironwood.compiler.source.SourceSpan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Package-local smoke coverage for declaration-aware most-specific selection. */
public final class GenericMostSpecificSelectorSmoke {
    private static final SourceSpan SPAN = SourceSpan.at(new SourcePosition(0, 1, 1));
    private static final IrType OBJECT = IrType.reference("ironwood.lang.Object");
    private static final IrType STRING = IrType.reference("ironwood.lang.String");

    private GenericMostSpecificSelectorSmoke() {
    }

    public static void main(String[] arguments) {
        Map<String, TypeVariableSymbol> variables = new LinkedHashMap<>();
        TypeVariableSymbol repeated = variable("repeated#T", variables);
        TypeVariableSymbol narrowed = variable("narrowed#T", variables);
        ClassHierarchy hierarchy = emptyHierarchy();
        GenericMostSpecificSelector selector = new GenericMostSpecificSelector(hierarchy, variables);

        InvocationPlan.CandidatePlan repeatedGeneric = plan(callable("Repeated", "select",
                List.of(repeated.irType(), repeated.irType()), List.of(repeated)),
                InvocationPlan.ApplicabilityPhase.STRICT_FIXED, List.of(STRING, STRING));
        InvocationPlan.CandidatePlan repeatedNonGeneric = plan(callable("Repeated", "select",
                List.of(STRING, OBJECT), List.of()),
                InvocationPlan.ApplicabilityPhase.STRICT_FIXED, List.of(STRING, OBJECT));
        assertWinner(selector, InvocationPlan.ApplicabilityPhase.STRICT_FIXED,
                List.of(repeatedGeneric, repeatedNonGeneric), repeatedNonGeneric,
                "nongeneric (String,Object) must beat <T>(T,T)");

        InvocationPlan.CandidatePlan narrowedGeneric = plan(callable("Narrowed", "select",
                List.of(narrowed.irType(), STRING), List.of(narrowed)),
                InvocationPlan.ApplicabilityPhase.STRICT_FIXED, List.of(STRING, STRING));
        InvocationPlan.CandidatePlan broadNonGeneric = plan(callable("Narrowed", "select",
                List.of(OBJECT, OBJECT), List.of()),
                InvocationPlan.ApplicabilityPhase.STRICT_FIXED, List.of(OBJECT, OBJECT));
        assertWinner(selector, InvocationPlan.ApplicabilityPhase.STRICT_FIXED,
                List.of(narrowedGeneric, broadNonGeneric), narrowedGeneric,
                "<T>(T,String) must beat (Object,Object)");

        InvocationPlan.CandidatePlan ambiguousLeft = plan(callable("Left", "same",
                List.of(STRING), List.of()),
                InvocationPlan.ApplicabilityPhase.STRICT_FIXED, List.of(STRING));
        InvocationPlan.CandidatePlan ambiguousRight = plan(callable("Right", "same",
                List.of(STRING), List.of()),
                InvocationPlan.ApplicabilityPhase.STRICT_FIXED, List.of(STRING));
        InvocationPlanningResult<InvocationPlan.CandidatePlan> ambiguous = selector.select(request(
                InvocationPlan.ApplicabilityPhase.STRICT_FIXED,
                List.of(ambiguousLeft, ambiguousRight)));
        if (!ambiguous.isRejected()) {
            throw new AssertionError("unrelated equal declarations must remain ambiguous");
        }
    }

    private static TypeVariableSymbol variable(String id,
                                               Map<String, TypeVariableSymbol> variables) {
        TypeVariableSymbol variable = TypeVariableSymbol.declared(id,
                new TypeParameter("T", SPAN));
        variable.setUpperBounds(List.of(OBJECT));
        variables.put(id, variable);
        return variable;
    }

    private static ClassHierarchy emptyHierarchy() {
        Map<String, TypeSymbol> types = new LinkedHashMap<>();
        TypeResolver resolver = new TypeResolver(types);
        GenericTypeSystem genericTypes = new GenericTypeSystem(types, resolver);
        ClassHierarchy hierarchy = new ClassHierarchy(types, resolver, genericTypes);
        genericTypes.attachHierarchy(hierarchy);
        return hierarchy;
    }

    private static CallableSymbol callable(String owner, String name, List<IrType> parameters,
                                           List<TypeVariableSymbol> variables) {
        return new CallableSymbol(owner, name, AccessModifier.PUBLIC, true,
                IrCallableKind.METHOD, IrType.I32,
                parameters, List.of(), Optional.empty(), Optional.empty(), Optional.empty(),
                false, false, false, SPAN, SPAN, owner + "." + name + "@" + parameters,
                Optional.empty(), null, owner + "#" + name + "@" + parameters,
                variables);
    }

    private static InvocationPlan.CandidatePlan plan(CallableSymbol callable,
                                                     InvocationPlan.ApplicabilityPhase phase,
                                                     List<IrType> effectiveParameters) {
        List<InvocationPlan.ArgumentPlan> argumentPlans = new ArrayList<>();
        for (int index = 0; index < effectiveParameters.size(); index++) {
            IrType type = effectiveParameters.get(index);
            NameExpression expression = new NameExpression("argument" + index, SPAN);
            argumentPlans.add(new InvocationPlan.ArgumentPlan(expression, type,
                    ExpressionTypePlan.simple(expression, type, Optional.of(type)),
                    new InvocationPlan.InvocationConversion(InvocationPlan.ConversionKind.IDENTITY,
                            type, type)));
        }
        return new InvocationPlan.CandidatePlan(InvocationCandidate.method(callable), phase,
                InvocationPlan.InferenceSolution.empty(), effectiveParameters, argumentPlans,
                Optional.empty(), IrType.I32, false);
    }

    private static InvocationPlanningContext.SelectionRequest request(
            InvocationPlan.ApplicabilityPhase phase,
            List<InvocationPlan.CandidatePlan> candidates) {
        return new InvocationPlanningContext.SelectionRequest(InvocationPlan.InvocationKind.METHOD,
                new InvocationPlan.ReceiverPlan(InvocationPlan.ReceiverKind.TYPE, OBJECT,
                        Optional.empty()), phase, candidates, SPAN);
    }

    private static void assertWinner(GenericMostSpecificSelector selector,
                                     InvocationPlan.ApplicabilityPhase phase,
                                     List<InvocationPlan.CandidatePlan> candidates,
                                     InvocationPlan.CandidatePlan expected,
                                     String message) {
        InvocationPlanningResult<InvocationPlan.CandidatePlan> selected = selector.select(
                request(phase, candidates));
        if (!selected.isResolved() || selected.resolvedValue() != expected) {
            throw new AssertionError(message);
        }
    }
}
