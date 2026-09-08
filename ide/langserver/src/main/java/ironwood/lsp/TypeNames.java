// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.lsp;

import ironwood.compiler.ast.Parameter;
import ironwood.compiler.ast.TypeName;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Renders parsed type and parameter syntax back into the way it was written.
 *
 * <p>Outline entries and hover text are read next to the source they describe,
 * so a rendered type should look like the source rather than like an internal
 * representation.
 */
public final class TypeNames {

    private TypeNames() {
    }

    public static String render(TypeName type) {
        if (type == null) {
            return "?";
        }
        return switch (type.kind()) {
            case REFERENCE -> type.referenceName() + renderTypeArguments(type.typeArguments());
            case ARRAY -> render(type.elementType()) + "[]";
            case WILDCARD -> renderWildcard(type);
            // Every remaining kind is a primitive or void, and each is spelled
            // as the lowercase of its own name.
            default -> type.kind().name().toLowerCase(Locale.ROOT);
        };
    }

    private static String renderWildcard(TypeName type) {
        return switch (type.wildcardKind()) {
            case EXTENDS -> "? extends " + render(type.wildcardBound());
            case SUPER -> "? super " + render(type.wildcardBound());
            case UNBOUNDED -> "?";
        };
    }

    private static String renderTypeArguments(List<TypeName> typeArguments) {
        if (typeArguments.isEmpty()) {
            return "";
        }
        return typeArguments.stream()
                .map(TypeNames::render)
                .collect(Collectors.joining(", ", "<", ">"));
    }

    /** Renders a parameter list including the surrounding parentheses. */
    public static String renderParameters(List<Parameter> parameters) {
        return parameters.stream()
                .map(parameter -> render(parameter.type()) + " " + parameter.name())
                .collect(Collectors.joining(", ", "(", ")"));
    }

    /** Renders only the parameter types, for a compact outline entry. */
    public static String renderParameterTypes(List<Parameter> parameters) {
        return parameters.stream()
                .map(parameter -> render(parameter.type()))
                .collect(Collectors.joining(", ", "(", ")"));
    }
}
