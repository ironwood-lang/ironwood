// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.ir.IrType;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable, candidate-local callable view supplied to the invocation planner.
 * Owner substitutions must already have been applied; callable and diamond variables remain.
 */
record InvocationCandidate(CallableSymbol callable,
                           List<CallableTypeParameter> classTypeParameters,
                           List<CallableTypeParameter> callableTypeParameters,
                           List<IrType> parameterTypeTemplates,
                           IrType resultTypeTemplate,
                           Optional<IrType> enclosingInstanceTypeTemplate) {
    InvocationCandidate {
        Objects.requireNonNull(callable, "callable");
        classTypeParameters = classTypeParameters == null
                ? List.of() : List.copyOf(classTypeParameters);
        callableTypeParameters = callableTypeParameters == null
                ? List.of() : List.copyOf(callableTypeParameters);
        parameterTypeTemplates = List.copyOf(parameterTypeTemplates);
        Objects.requireNonNull(resultTypeTemplate, "resultTypeTemplate");
        enclosingInstanceTypeTemplate = enclosingInstanceTypeTemplate == null
                ? Optional.empty() : enclosingInstanceTypeTemplate;
        Set<String> ids = new HashSet<>();
        classTypeParameters.forEach(parameter -> {
            if (!ids.add(parameter.id())) {
                throw new IllegalArgumentException("duplicate class type-variable id '"
                        + parameter.id() + "'");
            }
        });
        callableTypeParameters.forEach(parameter -> {
            if (!ids.add(parameter.id())) {
                throw new IllegalArgumentException("duplicate callable type-variable id '"
                        + parameter.id() + "'");
            }
        });
    }

    String identity() {
        return callable.linkageName();
    }

    boolean isConstructor() {
        return callable.isConstructor();
    }

    static InvocationCandidate method(CallableSymbol callable) {
        if (callable.isConstructor()) {
            throw new IllegalArgumentException("method candidate cannot wrap a constructor");
        }
        return new InvocationCandidate(callable, List.of(), parameters(callable.typeVariables()),
                callable.parameterTypes(), callable.returnType(),
                callable.enclosingInstanceType());
    }

    static InvocationCandidate constructor(CallableSymbol callable,
                                           List<CallableTypeParameter> classTypeParameters,
                                           IrType resultTypeTemplate) {
        if (!callable.isConstructor()) {
            throw new IllegalArgumentException("constructor candidate must wrap a constructor");
        }
        return new InvocationCandidate(callable, classTypeParameters,
                parameters(callable.typeVariables()), callable.parameterTypes(),
                resultTypeTemplate, callable.enclosingInstanceType());
    }

    /** Constructor-delegation view; the enclosing instance is an implicit ABI operand. */
    static InvocationCandidate constructorDelegation(CallableSymbol callable,
                                                      IrType resultTypeTemplate) {
        if (!callable.isConstructor()) {
            throw new IllegalArgumentException("constructor candidate must wrap a constructor");
        }
        return new InvocationCandidate(callable, List.of(), parameters(callable.typeVariables()),
                callable.parameterTypes(), resultTypeTemplate,
                Optional.empty());
    }

    private static List<CallableTypeParameter> parameters(
            List<TypeVariableSymbol> variables) {
        return variables.stream().map(variable -> new CallableTypeParameter(variable.id(),
                variable.displayName(), variable.upperBounds(), variable.permitsPrimitive())).toList();
    }

    /** A declared type variable used by either a class diamond or a generic callable. */
    record CallableTypeParameter(String id, String displayName, List<IrType> upperBounds,
                                 boolean permitsPrimitive) {
        CallableTypeParameter {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("callable type parameter requires an id");
            }
            if (displayName == null || displayName.isBlank()) {
                throw new IllegalArgumentException("callable type parameter requires a display name");
            }
            upperBounds = upperBounds == null ? List.of() : List.copyOf(upperBounds);
            if (upperBounds.stream().anyMatch(bound -> !bound.isReference())) {
                throw new IllegalArgumentException("generic upper bounds must be reference types");
            }
        }
    }
}
