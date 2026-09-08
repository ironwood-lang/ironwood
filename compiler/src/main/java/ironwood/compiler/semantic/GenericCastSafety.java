// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.ir.IrType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Side-effect-free closed-world proof for non-reifiable generic narrowing casts. */
final class GenericCastSafety {
    private final ClassHierarchy hierarchy;

    GenericCastSafety(ClassHierarchy hierarchy) {
        this.hierarchy = java.util.Objects.requireNonNull(hierarchy, "hierarchy");
    }

    /**
     * Proves a checked narrowing from an exact, wildcard-free source to a parameterized target.
     * Ordinary assignability and reifiable targets are intentionally handled by the caller.
     */
    Result prove(IrType source, IrType target) {
        java.util.Objects.requireNonNull(source, "source");
        java.util.Objects.requireNonNull(target, "target");
        if (!source.isNominalReference() || !target.isNominalReference()
                || target.typeArguments().isEmpty()) {
            return Result.unsafe(new Witness("<cast>", source, target,
                    "generic cast proof requires nominal source and parameterized target types"));
        }
        if (containsWildcard(source)) {
            return Result.unsafe(new Witness("<source>", source, target,
                    "wildcard source arguments do not determine exact runtime arguments"));
        }
        if (containsTypeParameter(source) || containsTypeParameter(target)) {
            return Result.unsafe(new Witness("<cast>", source, target,
                    "cast proof cannot depend on source or target type variables"));
        }

        boolean overlap = false;
        List<TypeSymbol> concreteTypes = hierarchy.types().values().stream()
                .filter(type -> !type.isInterface() && !type.isAbstract())
                .sorted(Comparator.comparing(TypeSymbol::name)).toList();
        for (TypeSymbol concrete : concreteTypes) {
            List<IrType> views = hierarchy.exactSupertypes(concrete.selfType());
            IrType sourceView = uniqueView(views, source.referenceName());
            IrType targetView = uniqueView(views, target.referenceName());
            if (sourceView == null || targetView == null) {
                continue;
            }
            Map<String, IrType> bindings = new LinkedHashMap<>();
            if (!unifyExact(sourceView, source, bindings)
                    || !bindingsSatisfyKnownBounds(sourceView, targetView, bindings)) {
                continue;
            }
            overlap = true;
            IrType resolvedTargetView = targetView.substitute(bindings);
            if (containsTypeParameter(resolvedTargetView)) {
                return Result.unsafe(new Witness(concrete.name(), sourceView, targetView,
                        "raw target membership leaves target arguments unconstrained"));
            }
            if (!hierarchy.isSubtype(resolvedTargetView, target)) {
                return Result.unsafe(new Witness(concrete.name(), sourceView,
                        resolvedTargetView, "exact target view is not contained by requested target"));
            }
        }
        return overlap ? Result.safe() : Result.impossible();
    }

    private IrType uniqueView(List<IrType> views, String rawName) {
        List<IrType> matches = views.stream()
                .filter(IrType::isNominalReference)
                .filter(view -> view.referenceName().equals(rawName))
                .distinct().toList();
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    private boolean unifyExact(IrType pattern, IrType exact,
                               Map<String, IrType> bindings) {
        if (pattern.isTypeParameter()) {
            IrType previous = bindings.putIfAbsent(pattern.referenceName(), exact);
            return previous == null || previous.equals(exact);
        }
        if (pattern.kind() != exact.kind()) {
            return false;
        }
        if (pattern.isNominalReference()) {
            if (!pattern.referenceName().equals(exact.referenceName())
                    || pattern.typeArguments().size() != exact.typeArguments().size()) {
                return false;
            }
            for (int index = 0; index < pattern.typeArguments().size(); index++) {
                if (!unifyExact(pattern.typeArguments().get(index),
                        exact.typeArguments().get(index), bindings)) {
                    return false;
                }
            }
            return true;
        }
        if (pattern.isArray()) {
            return unifyExact(pattern.elementType(), exact.elementType(), bindings);
        }
        return pattern.equals(exact);
    }

    private boolean bindingsSatisfyKnownBounds(IrType sourceView, IrType targetView,
                                                Map<String, IrType> bindings) {
        List<IrType> variables = new ArrayList<>();
        collectTypeParameters(sourceView, variables);
        collectTypeParameters(targetView, variables);
        for (IrType variable : variables.stream().distinct().toList()) {
            IrType binding = bindings.get(variable.referenceName());
            if (binding == null) {
                continue;
            }
            for (IrType upperBound : hierarchy.upperBounds(variable)) {
                IrType resolvedBound = upperBound.substitute(bindings);
                if (!containsTypeParameter(resolvedBound)
                        && !hierarchy.isSubtype(binding, resolvedBound)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static void collectTypeParameters(IrType type, List<IrType> result) {
        if (type.isTypeParameter()) {
            result.add(type);
            return;
        }
        if (type.isArray()) {
            collectTypeParameters(type.elementType(), result);
            return;
        }
        if (type.isWildcard() && type.wildcardBound() != null) {
            collectTypeParameters(type.wildcardBound(), result);
            return;
        }
        type.typeArguments().forEach(argument -> collectTypeParameters(argument, result));
    }

    private static boolean containsTypeParameter(IrType type) {
        if (type == null) {
            return false;
        }
        if (type.isTypeParameter()) {
            return true;
        }
        if (type.isArray()) {
            return containsTypeParameter(type.elementType());
        }
        if (type.isWildcard() && type.wildcardBound() != null) {
            return containsTypeParameter(type.wildcardBound());
        }
        return type.typeArguments().stream().anyMatch(GenericCastSafety::containsTypeParameter);
    }

    private static boolean containsWildcard(IrType type) {
        if (type == null) {
            return false;
        }
        if (type.isWildcard()) {
            return true;
        }
        if (type.isArray()) {
            return containsWildcard(type.elementType());
        }
        return type.typeArguments().stream().anyMatch(GenericCastSafety::containsWildcard);
    }

    enum Outcome {
        SAFE,
        UNSAFE,
        IMPOSSIBLE
    }

    record Witness(String concreteType, IrType sourceView, IrType targetView, String reason) {
        Witness {
            if (concreteType == null || concreteType.isBlank()
                    || reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("generic cast witness requires subject and reason");
            }
        }
    }

    record Result(Outcome outcome, Optional<Witness> witness) {
        Result {
            witness = witness == null ? Optional.empty() : witness;
            if ((outcome == Outcome.UNSAFE) != witness.isPresent()) {
                throw new IllegalArgumentException("only unsafe generic cast results carry a witness");
            }
        }

        static Result safe() {
            return new Result(Outcome.SAFE, Optional.empty());
        }

        static Result unsafe(Witness witness) {
            return new Result(Outcome.UNSAFE, Optional.of(witness));
        }

        static Result impossible() {
            return new Result(Outcome.IMPOSSIBLE, Optional.empty());
        }
    }
}
