// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ast;

import ironwood.compiler.source.SourceSpan;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public record TypeName(Kind kind, String referenceName, List<TypeName> typeArguments,
                       List<Integer> typeArgumentSegmentCounts,
                       TypeName elementType, WildcardKind wildcardKind,
                       TypeName wildcardBound, SourceSpan span) {
    public TypeName {
        typeArguments = typeArguments == null ? List.of() : List.copyOf(typeArguments);
        typeArgumentSegmentCounts = typeArgumentSegmentCounts == null
                ? List.of() : List.copyOf(typeArgumentSegmentCounts);
        if (kind == Kind.REFERENCE && (referenceName == null || referenceName.isBlank())) {
            throw new IllegalArgumentException("reference type requires a class name");
        }
        if (kind != Kind.REFERENCE && referenceName != null) {
            throw new IllegalArgumentException("non-reference types cannot name a class");
        }
        if (kind == Kind.ARRAY && (elementType == null || elementType.kind() == Kind.VOID)) {
            throw new IllegalArgumentException("array type requires a non-void element type");
        }
        if (kind != Kind.ARRAY && elementType != null) {
            throw new IllegalArgumentException("only array types have an element type");
        }
        if (kind == Kind.WILDCARD && wildcardKind == null) {
            throw new IllegalArgumentException("wildcard type requires a wildcard kind");
        }
        if (kind != Kind.WILDCARD && (wildcardKind != null || wildcardBound != null)) {
            throw new IllegalArgumentException("only wildcard types have wildcard bounds");
        }
        if (wildcardKind == WildcardKind.UNBOUNDED && wildcardBound != null) {
            throw new IllegalArgumentException("unbounded wildcard cannot have a bound");
        }
        if ((wildcardKind == WildcardKind.EXTENDS || wildcardKind == WildcardKind.SUPER)
                && wildcardBound == null) {
            throw new IllegalArgumentException("bounded wildcard requires a bound");
        }
        if (kind != Kind.REFERENCE && !typeArguments.isEmpty()) {
            throw new IllegalArgumentException("only reference types have type arguments");
        }
        if (kind != Kind.REFERENCE && !typeArgumentSegmentCounts.isEmpty()) {
            throw new IllegalArgumentException("only reference types have parameterized name segments");
        }
        if (kind == Kind.REFERENCE && typeArgumentSegmentCounts.stream()
                .mapToInt(Integer::intValue).sum() != typeArguments.size()) {
            throw new IllegalArgumentException("parameterized name segments must account for every type argument");
        }
        if (typeArgumentSegmentCounts.stream().anyMatch(count -> count < 0)) {
            throw new IllegalArgumentException("parameterized name segment counts cannot be negative");
        }
        if (kind == Kind.REFERENCE && typeArgumentSegmentCounts.size()
                != segmentCount(referenceName)) {
            throw new IllegalArgumentException(
                    "parameterized name segments must match the qualified reference name");
        }
    }

    public TypeName(Kind kind, String referenceName, TypeName elementType, SourceSpan span) {
        this(kind, referenceName, List.of(), defaultSegmentCounts(referenceName, 0),
                elementType, null, null, span);
    }

    public TypeName(Kind kind, String referenceName, List<TypeName> typeArguments,
                    TypeName elementType, SourceSpan span) {
        this(kind, referenceName, typeArguments,
                defaultSegmentCounts(referenceName, typeArguments == null ? 0 : typeArguments.size()),
                elementType, null, null, span);
    }

    public TypeName(Kind kind, String referenceName, List<TypeName> typeArguments,
                    List<Integer> typeArgumentSegmentCounts,
                    TypeName elementType, SourceSpan span) {
        this(kind, referenceName, typeArguments, typeArgumentSegmentCounts,
                elementType, null, null, span);
    }

    public static TypeName primitive(Kind kind, SourceSpan span) {
        return new TypeName(kind, null, List.of(), List.of(), null, null, null, span);
    }

    public static TypeName reference(String name, SourceSpan span) {
        return reference(name, List.of(), span);
    }

    public static TypeName reference(String name, List<TypeName> typeArguments, SourceSpan span) {
        return new TypeName(Kind.REFERENCE, name, typeArguments,
                defaultSegmentCounts(name, typeArguments.size()), null, null, null, span);
    }

    public static TypeName reference(String name, List<TypeName> typeArguments,
                                     List<Integer> segmentCounts, SourceSpan span) {
        return new TypeName(Kind.REFERENCE, name, typeArguments, segmentCounts,
                null, null, null, span);
    }

    public static TypeName wildcard(SourceSpan span) {
        return wildcard(WildcardKind.UNBOUNDED, null, span);
    }

    public static TypeName wildcard(WildcardKind wildcardKind, TypeName bound, SourceSpan span) {
        return new TypeName(Kind.WILDCARD, null, List.of(), List.of(), null,
                wildcardKind, bound, span);
    }

    public static TypeName wildcardExtends(TypeName bound, SourceSpan span) {
        return wildcard(WildcardKind.EXTENDS, bound, span);
    }

    public static TypeName wildcardSuper(TypeName bound, SourceSpan span) {
        return wildcard(WildcardKind.SUPER, bound, span);
    }

    public static TypeName array(TypeName elementType, SourceSpan span) {
        return new TypeName(Kind.ARRAY, null, List.of(), List.of(), elementType,
                null, null, span);
    }

    public String displayName() {
        return switch (kind) {
            case REFERENCE -> displayReference();
            case ARRAY -> elementType.displayName() + "[]";
            case WILDCARD -> switch (wildcardKind) {
                case UNBOUNDED -> "?";
                case EXTENDS -> "? extends " + wildcardBound.displayName();
                case SUPER -> "? super " + wildcardBound.displayName();
            };
            default -> kind.name().toLowerCase();
        };
    }

    public int lastSegmentTypeArgumentCount() {
        return typeArgumentSegmentCounts.isEmpty() ? 0 : typeArgumentSegmentCounts.getLast();
    }

    public boolean hasParameterizedQualifier() {
        return typeArgumentSegmentCounts.size() > 1
                && typeArgumentSegmentCounts.subList(0, typeArgumentSegmentCounts.size() - 1)
                .stream().anyMatch(count -> count > 0);
    }

    public boolean hasExplicitMemberQualifier(String memberSimpleName) {
        return kind == Kind.REFERENCE && referenceName.endsWith("." + memberSimpleName);
    }

    public Optional<TypeName> qualifierReference() {
        if (kind != Kind.REFERENCE) {
            return Optional.empty();
        }
        int separator = referenceName.lastIndexOf('.');
        if (separator < 0 || typeArgumentSegmentCounts.isEmpty()) {
            return Optional.empty();
        }
        int finalCount = lastSegmentTypeArgumentCount();
        int qualifierArgumentCount = typeArguments.size() - finalCount;
        return Optional.of(reference(referenceName.substring(0, separator),
                typeArguments.subList(0, qualifierArgumentCount),
                typeArgumentSegmentCounts.subList(0, typeArgumentSegmentCounts.size() - 1), span));
    }

    private String displayReference() {
        String[] segments = referenceName.split("\\.");
        StringBuilder result = new StringBuilder();
        int argument = 0;
        int countOffset = Math.max(0, typeArgumentSegmentCounts.size() - segments.length);
        for (int index = 0; index < segments.length; index++) {
            if (index > 0) {
                result.append('.');
            }
            result.append(segments[index]);
            int countIndex = index + countOffset;
            int count = countIndex < typeArgumentSegmentCounts.size()
                    ? typeArgumentSegmentCounts.get(countIndex) : 0;
            if (count > 0) {
                result.append('<');
                for (int item = 0; item < count; item++) {
                    if (item > 0) {
                        result.append(", ");
                    }
                    result.append(typeArguments.get(argument++).displayName());
                }
                result.append('>');
            }
        }
        return result.toString();
    }

    private static List<Integer> defaultSegmentCounts(String referenceName, int argumentCount) {
        if (referenceName == null) {
            return List.of();
        }
        int segments = segmentCount(referenceName);
        ArrayList<Integer> counts = new ArrayList<>();
        for (int index = 0; index < segments - 1; index++) {
            counts.add(0);
        }
        counts.add(argumentCount);
        return List.copyOf(counts);
    }

    private static int segmentCount(String referenceName) {
        return (int) referenceName.chars().filter(character -> character == '.').count() + 1;
    }

    public enum Kind {
        BYTE,
        SHORT,
        INT,
        LONG,
        CHAR,
        FLOAT,
        DOUBLE,
        BOOLEAN,
        VOID,
        REFERENCE,
        WILDCARD,
        ARRAY
    }

    public enum WildcardKind {
        UNBOUNDED,
        EXTENDS,
        SUPER
    }
}
