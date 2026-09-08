// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ast;

import ironwood.compiler.source.SourceSpan;

import java.util.List;

public record TypeReference(String name, List<TypeName> typeArguments,
                            List<Integer> typeArgumentSegmentCounts, SourceSpan span) {
    public TypeReference {
        typeArguments = typeArguments == null ? List.of() : List.copyOf(typeArguments);
        typeArgumentSegmentCounts = typeArgumentSegmentCounts == null
                ? List.of() : List.copyOf(typeArgumentSegmentCounts);
        if (typeArgumentSegmentCounts.stream().anyMatch(count -> count < 0)
                || typeArgumentSegmentCounts.stream().mapToInt(Integer::intValue).sum()
                != typeArguments.size()) {
            throw new IllegalArgumentException(
                    "parameterized name segments must account for every type argument");
        }
        int segmentCount = (int) name.chars().filter(character -> character == '.').count() + 1;
        if (typeArgumentSegmentCounts.size() != segmentCount) {
            throw new IllegalArgumentException(
                    "parameterized name segments must match the qualified reference name");
        }
    }

    public TypeReference(String name, SourceSpan span) {
        this(name, List.of(), defaultSegmentCounts(name, 0), span);
    }

    public TypeReference(String name, List<TypeName> typeArguments, SourceSpan span) {
        this(name, typeArguments, defaultSegmentCounts(name,
                typeArguments == null ? 0 : typeArguments.size()), span);
    }

    public String displayName() {
        return TypeName.reference(name, typeArguments, typeArgumentSegmentCounts, span).displayName();
    }

    public int lastSegmentTypeArgumentCount() {
        return typeArgumentSegmentCounts.isEmpty() ? 0 : typeArgumentSegmentCounts.getLast();
    }

    public boolean hasParameterizedQualifier() {
        return typeArgumentSegmentCounts.size() > 1
                && typeArgumentSegmentCounts.subList(0, typeArgumentSegmentCounts.size() - 1)
                .stream().anyMatch(count -> count > 0);
    }

    private static List<Integer> defaultSegmentCounts(String name, int argumentCount) {
        int segments = (int) name.chars().filter(character -> character == '.').count() + 1;
        java.util.ArrayList<Integer> counts = new java.util.ArrayList<>();
        for (int index = 0; index < segments - 1; index++) {
            counts.add(0);
        }
        counts.add(argumentCount);
        return List.copyOf(counts);
    }
}
