// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.ast.TypeParameter;
import ironwood.compiler.ir.IrType;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Semantic identity and resolved bounds for a declared variable or fresh capture. */
final class TypeVariableSymbol {
    private static final IrType ROOT_OBJECT = IrType.reference("ironwood.lang.Object");

    private final Kind kind;
    private final String id;
    private final String displayName;
    private final TypeParameter declaration;
    private IrType firstBoundErasure = ROOT_OBJECT;
    private List<IrType> upperBounds = List.of(ROOT_OBJECT);
    private Optional<IrType> lowerBound = Optional.empty();

    private TypeVariableSymbol(Kind kind, String id, String displayName,
                               TypeParameter declaration) {
        if (kind == null || id == null || id.isBlank()
                || displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("type variable requires kind, identity, and display name");
        }
        this.kind = kind;
        this.id = id;
        this.displayName = displayName;
        this.declaration = declaration;
    }

    static TypeVariableSymbol declared(String id, TypeParameter declaration) {
        return new TypeVariableSymbol(Kind.DECLARED, id, declaration.name(), declaration);
    }

    static TypeVariableSymbol capture(String id, String displayName,
                                      List<IrType> upperBounds, IrType lowerBound) {
        TypeVariableSymbol symbol = new TypeVariableSymbol(Kind.CAPTURE, id, displayName, null);
        symbol.setUpperBounds(upperBounds);
        symbol.lowerBound = Optional.ofNullable(lowerBound);
        return symbol;
    }

    static TypeVariableSymbol capture(String id, String displayName) {
        return new TypeVariableSymbol(Kind.CAPTURE, id, displayName, null);
    }

    static TypeVariableSymbol synthetic(String id, String displayName,
                                        List<IrType> upperBounds) {
        TypeVariableSymbol symbol = new TypeVariableSymbol(
                Kind.SYNTHETIC, id, displayName, null);
        symbol.setUpperBounds(upperBounds);
        return symbol;
    }

    Kind kind() {
        return kind;
    }

    String id() {
        return id;
    }

    String displayName() {
        return displayName;
    }

    Optional<TypeParameter> declaration() {
        return Optional.ofNullable(declaration);
    }

    List<IrType> upperBounds() {
        return upperBounds;
    }

    Optional<IrType> lowerBound() {
        return lowerBound;
    }

    IrType firstBoundErasure() {
        return firstBoundErasure;
    }

    /** Only an omitted bound admits an allocation-free primitive specialization. */
    boolean permitsPrimitive() {
        return kind == Kind.DECLARED && declaration != null
                && declaration.upperBounds().isEmpty();
    }

    void setFirstBoundErasure(IrType erasure) {
        if (erasure == null || !erasure.isReference()) {
            throw new IllegalArgumentException("type-variable erasure must be a reference type");
        }
        firstBoundErasure = erasure.erasure();
    }

    void setUpperBounds(List<IrType> bounds) {
        List<IrType> resolved = bounds == null || bounds.isEmpty()
                ? List.of(ROOT_OBJECT) : List.copyOf(bounds);
        if (resolved.stream().anyMatch(bound -> !bound.isReference())) {
            throw new IllegalArgumentException("type-variable upper bounds must be reference types");
        }
        upperBounds = resolved;
        setFirstBoundErasure(resolved.getFirst());
    }

    void setLowerBound(IrType bound) {
        if (bound != null && !bound.isReference()) {
            throw new IllegalArgumentException("type-variable lower bound must be a reference type");
        }
        lowerBound = Optional.ofNullable(bound);
    }

    /** Captures intentionally use the existing TYPE_PARAMETER IR kind. */
    IrType irType() {
        return IrType.typeParameter(id, firstBoundErasure);
    }

    TypeVariableSymbol substitute(Map<String, IrType> substitutions) {
        if (substitutions.isEmpty()) {
            return this;
        }
        TypeVariableSymbol result = new TypeVariableSymbol(kind, id, displayName, declaration);
        result.firstBoundErasure = firstBoundErasure;
        result.upperBounds = upperBounds.stream()
                .map(bound -> bound.substitute(substitutions)).toList();
        result.lowerBound = lowerBound.map(bound -> bound.substitute(substitutions));
        return result;
    }

    enum Kind {
        DECLARED,
        CAPTURE,
        SYNTHETIC
    }
}
