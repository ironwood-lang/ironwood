// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.ast.AccessModifier;
import ironwood.compiler.ast.NewExpression;
import ironwood.compiler.ast.TypeName;
import ironwood.compiler.ir.IrType;
import ironwood.compiler.semantic.InvocationPlanningContext.TypeUse;
import ironwood.compiler.semantic.InvocationPlanningResult.PlanningRejection;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Pure, staged binding for an anonymous subclass created through a qualified member-class
 * construction expression.
 *
 * <p>The enclosing primary is planned only after ordinary declaration signatures are available.
 * This class does not install hierarchy edges, register captures, emit diagnostics, or lower IR.
 * The integrating semantic phase owns those commits after a successful binding.</p>
 */
final class AnonymousParentBinder {
    private static final String ROOT_OBJECT = "ironwood.lang.Object";

    private final ClassHierarchy hierarchy;
    private final PlanningContextFactory contextFactory;

    AnonymousParentBinder(ClassHierarchy hierarchy, PlanningContextFactory contextFactory) {
        this.hierarchy = Objects.requireNonNull(hierarchy, "hierarchy");
        this.contextFactory = Objects.requireNonNull(contextFactory, "contextFactory");
    }

    /**
     * Orders deferred types so a syntactically nested anonymous primary is made available before
     * the allocation which consumes it. The caller may install and collect each result between
     * successive {@link #bindOne(TypeSymbol)} calls.
     */
    List<TypeSymbol> bindingOrder(Collection<TypeSymbol> deferredTypes) {
        Objects.requireNonNull(deferredTypes, "deferredTypes");
        return deferredTypes.stream()
                .filter(TypeSymbol::isAnonymousClass)
                .filter(type -> type.anonymousAllocation()
                        .flatMap(NewExpression::enclosingInstance).isPresent())
                .sorted(Comparator
                        .comparingInt((TypeSymbol type) -> spanWidth(type.anonymousAllocation()
                                .orElseThrow()))
                        .thenComparingInt(type -> type.anonymousAllocation().orElseThrow()
                                .span().start().offset())
                        .thenComparing(TypeSymbol::name))
                .toList();
    }

    InvocationPlanningResult<Binding> bindOne(TypeSymbol anonymous) {
        Objects.requireNonNull(anonymous, "anonymous");
        if (!anonymous.isAnonymousClass()) {
            return rejected(PlanningRejection.Code.INVALID_CONSTRUCTION,
                    "anonymous-parent binding requires an anonymous class",
                    anonymous.declaration().span());
        }
        NewExpression allocation = anonymous.anonymousAllocation().orElse(null);
        if (allocation == null || allocation.enclosingInstance().isEmpty()) {
            return rejected(PlanningRejection.Code.INVALID_CONSTRUCTION,
                    "anonymous-parent binding requires a qualified class creation",
                    anonymous.declaration().span());
        }

        PrimaryPlanningContext context = contextFactory.create(anonymous, allocation);
        if (context == null) {
            return rejected(PlanningRejection.Code.UNRESOLVED_NAME,
                    "qualified anonymous creation has no lexical planning context",
                    allocation.enclosingInstance().orElseThrow().span());
        }
        TypeSymbol accessingType = context.accessingType();
        if (accessingType == null) {
            return rejected(PlanningRejection.Code.UNRESOLVED_TYPE,
                    "qualified anonymous creation has no lexical accessing type",
                    allocation.enclosingInstance().orElseThrow().span());
        }
        InvocationPlanningResult<ExpressionTypePlan> primary = new InvocationPlanner(context)
                .plan(allocation.enclosingInstance().orElseThrow(), Optional.empty());
        if (!primary.isResolved()) {
            return propagate(primary);
        }

        ExpressionTypePlan primaryPlan = primary.resolvedValue();
        IrType plannedPrimaryType = primaryPlan.type();
        GenericTypeSystem.CaptureConversion capture = hierarchy.planCaptureReceiver(
                plannedPrimaryType, anonymous.name() + ":qualified-owner");
        IrType capturedPrimaryType = capture.type();
        if (!capturedPrimaryType.isReference() || capturedPrimaryType.equals(IrType.NULL)) {
            return rejected(PlanningRejection.Code.INVALID_CONSTRUCTION,
                    "qualified class creation requires a reference-typed enclosing primary",
                    allocation.enclosingInstance().orElseThrow().span());
        }

        InvocationPlanningResult<MemberView> member = resolveMember(capturedPrimaryType,
                allocation.className(), accessingType, allocation);
        if (!member.isResolved()) {
            return propagate(member);
        }
        MemberView selected = member.resolvedValue();
        TypeSymbol target = selected.member();
        if (!target.isInnerClass()) {
            return rejected(PlanningRejection.Code.INVALID_CONSTRUCTION,
                    "static member class '" + target.sourceName()
                            + "' cannot be created with an enclosing instance",
                    allocation.classNameSpan());
        }
        if (target.isInterface()) {
            return rejected(PlanningRejection.Code.INVALID_CONSTRUCTION,
                    "member interface '" + target.sourceName()
                            + "' cannot be created with an enclosing instance",
                    allocation.classNameSpan());
        }
        if (target.isFinal()) {
            return rejected(PlanningRejection.Code.INVALID_CONSTRUCTION,
                    "anonymous class cannot extend final class '" + target.sourceName() + "'",
                    allocation.classNameSpan());
        }
        if (!isAccessible(target, accessingType, capturedPrimaryType)) {
            return rejected(PlanningRejection.Code.INACCESSIBLE_MEMBER,
                    "member class '" + target.sourceName() + "' is not accessible",
                    allocation.classNameSpan());
        }

        TypeSymbol declaringOwner = target.enclosingType().orElseThrow();
        IrType exactOwner = selected.exactOwner();
        if (exactOwner.typeArguments().size() != declaringOwner.typeParameters().size()) {
            return rejected(PlanningRejection.Code.INVALID_CONSTRUCTION,
                    "enclosing primary does not provide a complete exact owner view for '"
                            + target.sourceName() + "'",
                    allocation.enclosingInstance().orElseThrow().span());
        }
        Map<String, IrType> substitutions = substitution(
                declaringOwner.typeParameters(), exactOwner.typeArguments());
        List<IrType> memberArguments = new ArrayList<>();
        List<TypeVariableSymbol> diamondVariables = new ArrayList<>();

        if (allocation.diamond()) {
            if (target.declaredTypeParameters().isEmpty()) {
                return rejected(PlanningRejection.Code.TYPE_ARGUMENT_REJECTED,
                        "diamond construction requires a generic member class",
                        allocation.classNameSpan());
            }
            for (int index = 0; index < target.declaredTypeParameters().size(); index++) {
                TypeVariableSymbol declared = target.declaredTypeParameters().get(index);
                String id = anonymous.name() + "#diamond-" + index + "#"
                        + declared.displayName();
                TypeVariableSymbol synthetic = TypeVariableSymbol.synthetic(id,
                        declared.displayName(), List.of(IrType.reference(ROOT_OBJECT)));
                diamondVariables.add(synthetic);
                substitutions.put(declared.id(), synthetic.irType());
            }
            stabilizeSyntheticBounds(target.declaredTypeParameters(), diamondVariables,
                    substitutions);
            diamondVariables.stream().map(TypeVariableSymbol::irType)
                    .forEach(memberArguments::add);
        } else {
            InvocationPlanningResult<List<IrType>> explicit = resolveExplicitMemberArguments(
                    allocation, target, context);
            if (!explicit.isResolved()) {
                return propagate(explicit);
            }
            memberArguments.addAll(explicit.resolvedValue());
            for (int index = 0; index < target.declaredTypeParameters().size(); index++) {
                substitutions.put(target.declaredTypeParameters().get(index).id(),
                        memberArguments.get(index));
            }
            InvocationPlanningResult<Binding> bounds = validateExplicitBounds(
                    allocation, target, memberArguments, substitutions);
            if (bounds != null) {
                return bounds;
            }
        }

        List<IrType> completeArguments = new ArrayList<>(exactOwner.typeArguments());
        completeArguments.addAll(memberArguments);
        if (completeArguments.size() != target.typeParameters().size()) {
            return rejected(PlanningRejection.Code.WRONG_TYPE_ARGUMENT_COUNT,
                    "member class '" + target.sourceName() + "' requires "
                            + target.typeParameters().size() + " complete type argument(s) but "
                            + completeArguments.size() + " were derived",
                    allocation.classNameSpan());
        }
        IrType parentTemplate = IrType.reference(target.name(), completeArguments);
        Map<String, TypeVariableSymbol> captures = mergeCaptures(
                context.plannedCaptures(), capture.variables());
        ExpressionTypePlan capturedPrimaryPlan = new ExpressionTypePlan(
                primaryPlan.expression(), capturedPrimaryType, primaryPlan.expectedType(),
                primaryPlan.polyExpression(), primaryPlan.operands(), primaryPlan.invocation());
        TypeSymbol.AnonymousParentBinding parentBinding =
                new TypeSymbol.AnonymousParentBinding(target, exactOwner, parentTemplate,
                        diamondVariables, Optional.of(capturedPrimaryPlan), captures);
        return InvocationPlanningResult.resolved(new Binding(anonymous, capturedPrimaryType,
                substitutions, parentBinding));
    }

    private static void stabilizeSyntheticBounds(List<TypeVariableSymbol> declared,
                                                 List<TypeVariableSymbol> synthetic,
                                                 Map<String, IrType> substitutions) {
        List<IrType> previous = List.of();
        for (int round = 0; round <= declared.size(); round++) {
            List<List<IrType>> resolvedBounds = declared.stream()
                    .map(variable -> variable.upperBounds().stream()
                            .map(bound -> bound.substitute(substitutions)).toList())
                    .toList();
            for (int index = 0; index < synthetic.size(); index++) {
                List<IrType> bounds = resolvedBounds.get(index);
                synthetic.get(index).setUpperBounds(bounds.isEmpty()
                        ? List.of(IrType.reference(ROOT_OBJECT)) : bounds);
            }
            List<IrType> current = synthetic.stream().map(TypeVariableSymbol::irType).toList();
            for (int index = 0; index < declared.size(); index++) {
                substitutions.put(declared.get(index).id(), current.get(index));
            }
            if (current.equals(previous)) {
                return;
            }
            previous = current;
        }
    }

    private InvocationPlanningResult<List<IrType>> resolveExplicitMemberArguments(
            NewExpression allocation, TypeSymbol target, PrimaryPlanningContext context) {
        List<TypeName> syntax = allocation.classType().typeArguments();
        if (syntax.size() != target.declaredTypeParameters().size()) {
            return rejected(PlanningRejection.Code.WRONG_TYPE_ARGUMENT_COUNT,
                    "generic member class '" + target.sourceName() + "' expects "
                            + target.declaredTypeParameters().size()
                            + " member type argument(s) but received " + syntax.size(),
                    allocation.classNameSpan());
        }
        List<IrType> result = new ArrayList<>();
        for (TypeName argument : syntax) {
            InvocationPlanningResult<IrType> resolved = context.resolveType(argument,
                    TypeUse.EXPLICIT_CLASS_ARGUMENT);
            if (!resolved.isResolved()) {
                return propagate(resolved);
            }
            IrType type = resolved.resolvedValue();
            if (!(type.isReference() || type.isPrimitive())
                    || type.equals(IrType.NULL) || type.isWildcard()) {
                return rejected(PlanningRejection.Code.TYPE_ARGUMENT_REJECTED,
                        "explicit member type argument must be a proper reference or primitive type",
                        argument.span());
            }
            result.add(type);
        }
        return InvocationPlanningResult.resolved(List.copyOf(result));
    }

    /** Returns a non-null rejected result when a bound fails. */
    private InvocationPlanningResult<Binding> validateExplicitBounds(
            NewExpression allocation, TypeSymbol target, List<IrType> arguments,
            Map<String, IrType> substitutions) {
        for (int index = 0; index < target.declaredTypeParameters().size(); index++) {
            TypeVariableSymbol variable = target.declaredTypeParameters().get(index);
            IrType argument = arguments.get(index);
            if (argument.isPrimitive()) {
                if (!variable.permitsPrimitive()) {
                    return rejected(PlanningRejection.Code.TYPE_ARGUMENT_REJECTED,
                            "primitive argument " + argument.displayName()
                                    + " requires unbounded type parameter "
                                    + variable.displayName(),
                            allocation.classType().typeArguments().get(index).span());
                }
                continue;
            }
            for (IrType bound : variable.upperBounds()) {
                IrType instantiated = bound.substitute(substitutions);
                if (!hierarchy.isAssignable(instantiated, argument)) {
                    return rejected(PlanningRejection.Code.TYPE_ARGUMENT_REJECTED,
                            argument.displayName() + " does not satisfy bound "
                                    + instantiated.displayName(),
                            allocation.classType().typeArguments().get(index).span());
                }
            }
        }
        return null;
    }

    private InvocationPlanningResult<MemberView> resolveMember(
            IrType primaryType, String simpleName, TypeSymbol accessingType,
            NewExpression allocation) {
        List<MemberView> candidates = new ArrayList<>();
        Set<String> seenMembers = new LinkedHashSet<>();
        for (IrType view : hierarchy.exactSupertypes(primaryType)) {
            if (!view.isNominalReference()) {
                continue;
            }
            TypeSymbol owner = hierarchy.type(view.referenceName()).orElse(null);
            if (owner == null) {
                continue;
            }
            TypeSymbol declared = owner.declaredMemberType(simpleName).orElse(null);
            if (declared == null || !seenMembers.add(declared.name())) {
                continue;
            }
            if (!view.referenceName().equals(primaryType.referenceName())
                    && !isInheritedBy(declared, owner, primaryType)) {
                continue;
            }
            TypeSymbol declaringOwner = declared.enclosingType().orElse(null);
            Optional<IrType> exactOwner = declaringOwner == null
                    ? Optional.empty()
                    : view.referenceName().equals(declaringOwner.name())
                    ? Optional.of(view)
                    : hierarchy.exactClassSupertype(view, declaringOwner);
            if (exactOwner.isPresent()) {
                candidates.add(new MemberView(declared, exactOwner.orElseThrow()));
                if (!owner.isInterface()) {
                    break;
                }
            }
        }
        if (candidates.isEmpty()) {
            return rejected(PlanningRejection.Code.UNRESOLVED_TYPE,
                    "enclosing primary has no member class '" + simpleName + "'",
                    allocation.classNameSpan());
        }
        MemberView selected = mostSpecific(candidates);
        if (selected == null) {
            return rejected(PlanningRejection.Code.UNRESOLVED_TYPE,
                    "member class '" + simpleName
                            + "' is ambiguous for the enclosing primary",
                    allocation.classNameSpan());
        }
        if (!isAccessible(selected.member(), accessingType, primaryType)) {
            return rejected(PlanningRejection.Code.INACCESSIBLE_MEMBER,
                    "member class '" + selected.member().sourceName() + "' is not accessible",
                    allocation.classNameSpan());
        }
        return InvocationPlanningResult.resolved(selected);
    }

    private MemberView mostSpecific(List<MemberView> candidates) {
        if (candidates.size() == 1) {
            return candidates.getFirst();
        }
        List<MemberView> maximal = candidates.stream().filter(candidate ->
                candidates.stream().noneMatch(other -> other != candidate
                        && hierarchy.isSubtype(other.member().enclosingType().orElseThrow().name(),
                        candidate.member().enclosingType().orElseThrow().name())))
                .toList();
        return maximal.size() == 1 ? maximal.getFirst() : null;
    }

    private boolean isInheritedBy(TypeSymbol member, TypeSymbol declaringOwner,
                                  IrType receiverType) {
        TypeSymbol receiver = receiverType.isNominalReference()
                ? hierarchy.type(receiverType.referenceName()).orElse(null) : null;
        return switch (member.declaration().accessModifier()) {
            case PRIVATE -> false;
            case PACKAGE_PRIVATE -> receiver != null
                    && declaringOwner.packageName().equals(receiver.packageName());
            case PROTECTED, PUBLIC -> true;
        };
    }

    private boolean isAccessible(TypeSymbol target, TypeSymbol accessingType,
                                 IrType receiverType) {
        for (TypeSymbol current = target; current != null;
             current = current.enclosingType().orElse(null)) {
            AccessModifier access = current.declaration().accessModifier();
            TypeSymbol owner = current.enclosingType().orElse(null);
            boolean accessible = switch (access) {
                case PUBLIC -> true;
                case PRIVATE -> hierarchy.sameNest(current.name(), accessingType.name());
                case PACKAGE_PRIVATE -> current.packageName().equals(accessingType.packageName());
                case PROTECTED -> current.packageName().equals(accessingType.packageName())
                        || owner != null && hierarchy.isSubtype(accessingType.name(), owner.name())
                        && protectedReceiverAllowed(receiverType, accessingType);
            };
            if (!accessible) {
                return false;
            }
        }
        return true;
    }

    private boolean protectedReceiverAllowed(IrType receiverType, TypeSymbol accessingType) {
        if (receiverType.isNominalReference()) {
            return hierarchy.isSubtype(receiverType.referenceName(), accessingType.name());
        }
        return hierarchy.exactSupertypes(receiverType).stream()
                .filter(IrType::isNominalReference)
                .anyMatch(view -> hierarchy.isSubtype(view.referenceName(), accessingType.name()));
    }

    private static Map<String, IrType> substitution(List<TypeVariableSymbol> variables,
                                                    List<IrType> arguments) {
        if (variables.size() != arguments.size()) {
            return Map.of();
        }
        LinkedHashMap<String, IrType> result = new LinkedHashMap<>();
        for (int index = 0; index < variables.size(); index++) {
            result.put(variables.get(index).id(), arguments.get(index));
        }
        return result;
    }

    private static Map<String, TypeVariableSymbol> mergeCaptures(
            Map<String, TypeVariableSymbol> planned,
            Map<String, TypeVariableSymbol> ownerCaptures) {
        LinkedHashMap<String, TypeVariableSymbol> result = new LinkedHashMap<>();
        if (planned != null) {
            result.putAll(planned);
        }
        ownerCaptures.forEach((id, variable) -> {
            TypeVariableSymbol previous = result.putIfAbsent(id, variable);
            if (previous != null && previous != variable) {
                throw new IllegalStateException("conflicting planned capture '" + id + "'");
            }
        });
        return Collections.unmodifiableMap(result);
    }

    private static int spanWidth(NewExpression expression) {
        return expression.span().end().offset() - expression.span().start().offset();
    }

    private static <T> InvocationPlanningResult<T> rejected(
            PlanningRejection.Code code, String message,
            ironwood.compiler.source.SourceSpan span) {
        return InvocationPlanningResult.rejected(PlanningRejection.of(code, message, span));
    }

    private static <T, U> InvocationPlanningResult<T> propagate(
            InvocationPlanningResult<U> result) {
        if (result.isRejected()) {
            return InvocationPlanningResult.rejected(result.rejections());
        }
        return InvocationPlanningResult.notFound();
    }

    @FunctionalInterface
    interface PlanningContextFactory {
        PrimaryPlanningContext create(TypeSymbol anonymous, NewExpression allocation);
    }

    /**
     * A lexical-snapshot context for the allocation site. Implementations expose captures planned
     * while typing the selected primary but must not register them globally.
     */
    interface PrimaryPlanningContext extends InvocationPlanningContext {
        TypeSymbol accessingType();

        default Map<String, TypeVariableSymbol> plannedCaptures() {
            return Map.of();
        }
    }

    record Binding(TypeSymbol anonymous, IrType capturedPrimaryType,
                   Map<String, IrType> targetSubstitutions,
                   TypeSymbol.AnonymousParentBinding parentBinding) {
        Binding {
            Objects.requireNonNull(anonymous, "anonymous");
            Objects.requireNonNull(capturedPrimaryType, "capturedPrimaryType");
            targetSubstitutions = Collections.unmodifiableMap(
                    new LinkedHashMap<>(targetSubstitutions));
            Objects.requireNonNull(parentBinding, "parentBinding");
        }
    }

    private record MemberView(TypeSymbol member, IrType exactOwner) {
    }
}
