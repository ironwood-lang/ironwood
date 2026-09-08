// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.ast.AccessModifier;
import ironwood.compiler.ast.Block;
import ironwood.compiler.ast.Parameter;
import ironwood.compiler.ast.SuperConstructorInvocation;
import ironwood.compiler.ast.ThisConstructorInvocation;
import ironwood.compiler.ir.IrType;
import ironwood.compiler.ir.IrCallableKind;
import ironwood.compiler.source.SourceSpan;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

record CallableSymbol(String ownerType, String sourceName, AccessModifier accessModifier,
                      boolean isStatic, IrCallableKind kind, IrType returnType,
                      List<IrType> parameterTypes, List<Parameter> parameters,
                      Optional<Block> body, Optional<SuperConstructorInvocation> superInvocation,
                      Optional<ThisConstructorInvocation> thisInvocation,
                      boolean isAbstract, boolean isFinal, boolean isSynthetic,
                      SourceSpan nameSpan, SourceSpan span, String linkageName,
                      Optional<IrType> enclosingInstanceType, String dispatchKey,
                      String declarationId, List<TypeVariableSymbol> typeVariables,
                      List<IrType> thrownTypes) {
    CallableSymbol {
        parameterTypes = List.copyOf(parameterTypes);
        parameters = List.copyOf(parameters);
        body = body == null ? Optional.empty() : body;
        superInvocation = superInvocation == null ? Optional.empty() : superInvocation;
        thisInvocation = thisInvocation == null ? Optional.empty() : thisInvocation;
        enclosingInstanceType = enclosingInstanceType == null ? Optional.empty() : enclosingInstanceType;
        dispatchKey = dispatchKey == null ? erasedSignatureKey(sourceName, parameterTypes) : dispatchKey;
        declarationId = declarationId == null ? ownerType + "#" + sourceName
                + "@" + span.start().offset() : declarationId;
        typeVariables = typeVariables == null ? List.of() : List.copyOf(typeVariables);
        thrownTypes = thrownTypes == null ? List.of() : List.copyOf(thrownTypes);
        kind = kind == null ? IrCallableKind.METHOD : kind;
    }

    CallableSymbol(String ownerType, String sourceName, AccessModifier accessModifier,
                   boolean isStatic, IrCallableKind kind, IrType returnType,
                   List<IrType> parameterTypes, List<Parameter> parameters,
                   Optional<Block> body, Optional<SuperConstructorInvocation> superInvocation,
                   Optional<ThisConstructorInvocation> thisInvocation,
                   boolean isAbstract, boolean isFinal, boolean isSynthetic,
                   SourceSpan nameSpan, SourceSpan span, String linkageName,
                   Optional<IrType> enclosingInstanceType, String dispatchKey,
                   String declarationId, List<TypeVariableSymbol> typeVariables) {
        this(ownerType, sourceName, accessModifier, isStatic, kind, returnType,
                parameterTypes, parameters, body, superInvocation, thisInvocation,
                isAbstract, isFinal, isSynthetic, nameSpan, span, linkageName,
                enclosingInstanceType, dispatchKey, declarationId, typeVariables, List.of());
    }

    CallableSymbol(String ownerType, String sourceName, AccessModifier accessModifier,
                   boolean isStatic, IrCallableKind kind, IrType returnType,
                   List<IrType> parameterTypes, List<Parameter> parameters,
                   Optional<Block> body, Optional<SuperConstructorInvocation> superInvocation,
                   Optional<ThisConstructorInvocation> thisInvocation,
                   boolean isAbstract, boolean isFinal, boolean isSynthetic,
                   SourceSpan nameSpan, SourceSpan span, String linkageName,
                   Optional<IrType> enclosingInstanceType, String dispatchKey) {
        this(ownerType, sourceName, accessModifier, isStatic, kind, returnType,
                parameterTypes, parameters, body, superInvocation, thisInvocation,
                isAbstract, isFinal, isSynthetic, nameSpan, span, linkageName,
                enclosingInstanceType, dispatchKey, null, List.of());
    }

    CallableSymbol(String ownerType, String sourceName, AccessModifier accessModifier,
                   boolean isStatic, IrCallableKind kind, IrType returnType,
                   List<IrType> parameterTypes, List<Parameter> parameters,
                   Optional<Block> body, Optional<SuperConstructorInvocation> superInvocation,
                   Optional<ThisConstructorInvocation> thisInvocation,
                   boolean isAbstract, boolean isFinal, boolean isSynthetic,
                   SourceSpan nameSpan, SourceSpan span, String linkageName) {
        this(ownerType, sourceName, accessModifier, isStatic, kind, returnType,
                parameterTypes, parameters, body, superInvocation, thisInvocation,
                isAbstract, isFinal, isSynthetic, nameSpan, span, linkageName, Optional.empty(),
                null, null, List.of());
    }

    String signatureKey() {
        return sourceSignatureKey();
    }

    String sourceSignatureKey() {
        return sourceName + "(" + parameterTypes.stream()
                .map(IrType::displayName).reduce((left, right) -> left + "," + right).orElse("") + ")";
    }

    String overrideSignatureKey() {
        Map<String, String> variables = alphaVariableNames();
        String bounds = typeVariables.stream().map(variable -> variable.upperBounds().stream()
                        .map(bound -> alphaTypeName(bound, variables))
                        .reduce((left, right) -> left + "&" + right).orElse(""))
                .reduce((left, right) -> left + ";" + right).orElse("");
        String parameters = parameterTypes.stream().map(type -> alphaTypeName(type, variables))
                .reduce((left, right) -> left + "," + right).orElse("");
        return sourceName + "<" + bounds + ">(" + parameters + ")";
    }

    String erasedSignatureKey() {
        return erasedSignatureKey(sourceName, parameterTypes);
    }

    CallableSymbol substitute(java.util.Map<String, IrType> substitutions) {
        if (substitutions.isEmpty()) {
            return this;
        }
        return new CallableSymbol(ownerType, sourceName, accessModifier, isStatic, kind,
                returnType.substitute(substitutions),
                parameterTypes.stream().map(type -> type.substitute(substitutions)).toList(),
                parameters, body, superInvocation, thisInvocation, isAbstract, isFinal,
                isSynthetic,
                nameSpan, span, linkageName,
                enclosingInstanceType.map(type -> type.substitute(substitutions)), dispatchKey,
                declarationId, typeVariables.stream()
                        .map(variable -> variable.substitute(substitutions)).toList(),
                thrownTypes.stream().map(type -> type.substitute(substitutions)).toList());
    }

    CallableSymbol withEnclosingInstance(IrType type) {
        return new CallableSymbol(ownerType, sourceName, accessModifier, isStatic, kind,
                returnType, parameterTypes, parameters, body, superInvocation, thisInvocation,
                isAbstract, isFinal, isSynthetic, nameSpan, span, linkageName,
                Optional.of(type), dispatchKey, declarationId, typeVariables, thrownTypes);
    }

    CallableSymbol withThrownTypes(List<IrType> types) {
        return new CallableSymbol(ownerType, sourceName, accessModifier, isStatic, kind,
                returnType, parameterTypes, parameters, body, superInvocation, thisInvocation,
                isAbstract, isFinal, isSynthetic, nameSpan, span, linkageName,
                enclosingInstanceType, dispatchKey, declarationId, typeVariables, types);
    }

    /**
     * Returns a candidate-local parameter view for invocation inference.
     *
     * <p>The supplied types are the effective formals for one applicability phase. The returned
     * symbol deliberately retains the declaration's identity, linkage, dispatch key, source
     * parameters, and bounds; it must never replace the declared ABI symbol in a type or hierarchy
     * table.</p>
     */
    CallableSymbol withInvocationParameterTypes(List<IrType> invocationParameterTypes) {
        return new CallableSymbol(ownerType, sourceName, accessModifier, isStatic, kind,
                returnType, invocationParameterTypes, parameters, body, superInvocation,
                thisInvocation, isAbstract, isFinal, isSynthetic, nameSpan, span, linkageName,
                enclosingInstanceType, dispatchKey, declarationId, typeVariables, thrownTypes);
    }

    Optional<TypeVariableSymbol> typeVariable(String simpleName) {
        return typeVariables.stream().filter(variable -> variable.displayName().equals(simpleName))
                .findFirst();
    }

    boolean isConstructor() {
        return kind == IrCallableKind.CONSTRUCTOR;
    }

    boolean isDestructor() {
        return kind == IrCallableKind.DESTRUCTOR;
    }

    boolean isClassInitializer() {
        return kind == IrCallableKind.CLASS_INITIALIZER;
    }

    IrType adaptTypeVariablesTo(IrType type, CallableSymbol target) {
        if (typeVariables.size() != target.typeVariables.size()) {
            return type;
        }
        Map<String, IrType> substitutions = new LinkedHashMap<>();
        for (int index = 0; index < typeVariables.size(); index++) {
            substitutions.put(typeVariables.get(index).id(), target.typeVariables.get(index).irType());
        }
        return type.substitute(substitutions);
    }

    private Map<String, String> alphaVariableNames() {
        Map<String, String> result = new LinkedHashMap<>();
        for (int index = 0; index < typeVariables.size(); index++) {
            result.put(typeVariables.get(index).id(), "$" + index);
        }
        return result;
    }

    private static String alphaTypeName(IrType type, Map<String, String> variables) {
        return switch (type.kind()) {
            case I1 -> "boolean";
            case I8 -> "byte";
            case I16 -> "short";
            case U16 -> "char";
            case I32 -> "int";
            case I64 -> "long";
            case F32 -> "float";
            case F64 -> "double";
            case VOID -> "void";
            case NULL -> "null";
            case EXCEPTION -> "exception";
            case TYPE_PARAMETER -> variables.getOrDefault(type.referenceName(),
                    "type:" + type.referenceName());
            case REFERENCE -> "ref:" + type.referenceName() + (type.typeArguments().isEmpty()
                    ? "" : "<" + type.typeArguments().stream()
                    .map(argument -> alphaTypeName(argument, variables))
                    .reduce((left, right) -> left + "," + right).orElse("") + ">");
            case ARRAY -> alphaTypeName(type.elementType(), variables) + "[]";
            case WILDCARD -> switch (type.wildcardKind()) {
                case UNBOUNDED -> "?";
                case EXTENDS -> "?extends " + alphaTypeName(type.wildcardBound(), variables);
                case SUPER -> "?super " + alphaTypeName(type.wildcardBound(), variables);
            };
        };
    }

    private static String erasedSignatureKey(String name, List<IrType> types) {
        return name + "(" + types.stream().map(IrType::erasure).map(IrType::displayName)
                .reduce((left, right) -> left + "," + right).orElse("") + ")";
    }
}
