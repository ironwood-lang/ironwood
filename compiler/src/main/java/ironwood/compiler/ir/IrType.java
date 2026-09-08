// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import java.util.List;
import java.util.Map;

/** Source-precise type; generic information is erased only at the native ABI. */
public record IrType(Kind kind, String referenceName, List<IrType> typeArguments,
                     IrType elementType, IrType typeParameterErasure,
                     WildcardKind wildcardKind, IrType wildcardBound) {
    private static final String ROOT_OBJECT = "ironwood.lang.Object";

    public static final IrType I1 = new IrType(Kind.I1, null, List.of(), null);
    public static final IrType I8 = new IrType(Kind.I8, null, List.of(), null);
    public static final IrType I16 = new IrType(Kind.I16, null, List.of(), null);
    public static final IrType U16 = new IrType(Kind.U16, null, List.of(), null);
    public static final IrType I32 = new IrType(Kind.I32, null, List.of(), null);
    public static final IrType I64 = new IrType(Kind.I64, null, List.of(), null);
    public static final IrType F32 = new IrType(Kind.F32, null, List.of(), null);
    public static final IrType F64 = new IrType(Kind.F64, null, List.of(), null);
    public static final IrType VOID = new IrType(Kind.VOID, null, List.of(), null);
    public static final IrType NULL = new IrType(Kind.NULL, null, List.of(), null);
    public static final IrType EXCEPTION = new IrType(Kind.EXCEPTION, null, List.of(), null);
    public static final IrType WILDCARD = wildcard();

    public IrType {
        typeArguments = typeArguments == null ? List.of() : List.copyOf(typeArguments);
        if (kind == Kind.TYPE_PARAMETER && typeParameterErasure != null) {
            typeParameterErasure = typeParameterErasure.erasure();
        }
        if ((kind == Kind.REFERENCE || kind == Kind.TYPE_PARAMETER)
                && (referenceName == null || referenceName.isBlank())) {
            throw new IllegalArgumentException("reference IR type requires a nominal or type-parameter name");
        }
        if (kind != Kind.REFERENCE && kind != Kind.TYPE_PARAMETER && referenceName != null) {
            throw new IllegalArgumentException("non-reference IR type cannot name a nominal type");
        }
        if (kind != Kind.REFERENCE && !typeArguments.isEmpty()) {
            throw new IllegalArgumentException("only nominal reference IR types have type arguments");
        }
        if (kind == Kind.ARRAY && (elementType == null || elementType.equals(VOID))) {
            throw new IllegalArgumentException("array IR type requires a non-void element type");
        }
        if (kind != Kind.ARRAY && elementType != null) {
            throw new IllegalArgumentException("only array IR types have an element type");
        }
        if (kind == Kind.TYPE_PARAMETER && typeParameterErasure == null) {
            throw new IllegalArgumentException("type-parameter IR type requires its first-bound erasure");
        }
        if (kind != Kind.TYPE_PARAMETER && typeParameterErasure != null) {
            throw new IllegalArgumentException("only type-parameter IR types have a first-bound erasure");
        }
        if (typeParameterErasure != null && !typeParameterErasure.isReference()) {
            throw new IllegalArgumentException("type-parameter erasure must be a reference type");
        }
        if (kind == Kind.WILDCARD && wildcardKind == null) {
            throw new IllegalArgumentException("wildcard IR type requires a wildcard kind");
        }
        if (kind != Kind.WILDCARD && (wildcardKind != null || wildcardBound != null)) {
            throw new IllegalArgumentException("only wildcard IR types have wildcard metadata");
        }
        if (wildcardKind == WildcardKind.UNBOUNDED && wildcardBound != null) {
            throw new IllegalArgumentException("unbounded wildcard IR type cannot have a bound");
        }
        if ((wildcardKind == WildcardKind.EXTENDS || wildcardKind == WildcardKind.SUPER)
                && wildcardBound == null) {
            throw new IllegalArgumentException("bounded wildcard IR type requires a bound");
        }
        if (wildcardBound != null && !wildcardBound.isReference()) {
            throw new IllegalArgumentException("wildcard IR bound must be a reference type");
        }
    }

    /** Compatibility constructor for callers that predate generic-bound metadata. */
    public IrType(Kind kind, String referenceName, List<IrType> typeArguments,
                  IrType elementType) {
        this(kind, referenceName, typeArguments, elementType,
                kind == Kind.TYPE_PARAMETER ? reference(ROOT_OBJECT) : null,
                kind == Kind.WILDCARD ? WildcardKind.UNBOUNDED : null, null);
    }

    public IrType(Kind kind, String referenceName, IrType elementType) {
        this(kind, referenceName, List.of(), elementType);
    }

    public static IrType reference(String className) {
        return reference(className, List.of());
    }

    public static IrType reference(String className, List<IrType> typeArguments) {
        return new IrType(Kind.REFERENCE, className, typeArguments, null);
    }

    /** The id is owner-qualified, for example {@code example.Box#T}. */
    public static IrType typeParameter(String id) {
        return typeParameter(id, reference(ROOT_OBJECT));
    }

    /**
     * Creates a type variable while retaining the erasure of its first upper bound.
     * The id may identify either a declared variable or a fresh capture.
     */
    public static IrType typeParameter(String id, IrType firstBound) {
        if (firstBound == null) {
            throw new IllegalArgumentException("type parameter requires a first bound");
        }
        return new IrType(Kind.TYPE_PARAMETER, id, List.of(), null,
                firstBound.erasure(), null, null);
    }

    public static IrType wildcard() {
        return wildcard(WildcardKind.UNBOUNDED, null);
    }

    public static IrType wildcardExtends(IrType upperBound) {
        return wildcard(WildcardKind.EXTENDS, upperBound);
    }

    public static IrType wildcardSuper(IrType lowerBound) {
        return wildcard(WildcardKind.SUPER, lowerBound);
    }

    public static IrType wildcard(WildcardKind wildcardKind, IrType bound) {
        return new IrType(Kind.WILDCARD, null, List.of(), null,
                null, wildcardKind, bound);
    }

    public static IrType array(IrType elementType) {
        return new IrType(Kind.ARRAY, null, List.of(), elementType);
    }

    public boolean isReference() {
        return kind == Kind.REFERENCE || kind == Kind.TYPE_PARAMETER
                || kind == Kind.WILDCARD || kind == Kind.ARRAY;
    }

    public boolean isNominalReference() {
        return kind == Kind.REFERENCE;
    }

    public boolean isTypeParameter() {
        return kind == Kind.TYPE_PARAMETER;
    }

    public boolean isWildcard() {
        return kind == Kind.WILDCARD;
    }

    public boolean isArray() {
        return kind == Kind.ARRAY;
    }

    public boolean isNumeric() {
        return switch (kind) {
            case I8, I16, U16, I32, I64, F32, F64 -> true;
            default -> false;
        };
    }

    public boolean isPrimitive() {
        return kind == Kind.I1 || isNumeric();
    }

    public boolean isIntegral() {
        return switch (kind) {
            case I8, I16, U16, I32, I64 -> true;
            default -> false;
        };
    }

    public boolean isFloating() {
        return kind == Kind.F32 || kind == Kind.F64;
    }

    public IrType substitute(Map<String, IrType> substitutions) {
        if (kind == Kind.TYPE_PARAMETER) {
            return substitutions.getOrDefault(referenceName, this);
        }
        if (kind == Kind.REFERENCE && !typeArguments.isEmpty()) {
            return reference(referenceName,
                    typeArguments.stream().map(type -> type.substitute(substitutions)).toList());
        }
        if (kind == Kind.WILDCARD && wildcardBound != null) {
            return wildcard(wildcardKind, wildcardBound.substitute(substitutions));
        }
        if (kind == Kind.ARRAY) {
            return array(elementType.substitute(substitutions));
        }
        return this;
    }

    public IrType erasure() {
        return switch (kind) {
            case TYPE_PARAMETER -> typeParameterErasure;
            case WILDCARD -> reference(ROOT_OBJECT);
            case REFERENCE -> reference(referenceName);
            case ARRAY -> array(elementType.erasure());
            default -> this;
        };
    }

    public String displayName() {
        return switch (kind) {
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
            case EXCEPTION -> "native exception";
            case TYPE_PARAMETER -> {
                int separator = referenceName.lastIndexOf('#');
                yield separator < 0 ? referenceName : referenceName.substring(separator + 1);
            }
            case WILDCARD -> switch (wildcardKind) {
                case UNBOUNDED -> "?";
                case EXTENDS -> "? extends " + wildcardBound.displayName();
                case SUPER -> "? super " + wildcardBound.displayName();
            };
            case REFERENCE -> referenceName + (typeArguments.isEmpty() ? "" : "<"
                    + typeArguments.stream().map(IrType::displayName)
                    .reduce((left, right) -> left + ", " + right).orElse("") + ">");
            case ARRAY -> elementType.displayName() + "[]";
        };
    }

    public enum Kind {
        I1,
        I8,
        I16,
        U16,
        I32,
        I64,
        F32,
        F64,
        VOID,
        NULL,
        EXCEPTION,
        REFERENCE,
        TYPE_PARAMETER,
        WILDCARD,
        ARRAY
    }

    public enum WildcardKind {
        UNBOUNDED,
        EXTENDS,
        SUPER
    }
}
