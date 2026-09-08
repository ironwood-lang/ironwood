// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.source.SourceFile;
import ironwood.compiler.source.SourceSpan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Source-identity-aware lexical type bindings produced before hierarchy collection. */
final class LexicalTypeScopes {
    private final IdentityHashMap<Object, TypeSymbol> typesByNode = new IdentityHashMap<>();
    private final IdentityHashMap<SourceFile, Map<SpanKey, TypeSymbol>> typesBySpan =
            new IdentityHashMap<>();
    private final IdentityHashMap<SourceFile, List<LocalClassSemantics.ScopeDescriptor>>
            scopesBySource = new IdentityHashMap<>();
    private final IdentityHashMap<SourceFile, List<Binding>> bindingsBySource =
            new IdentityHashMap<>();
    private final IdentityHashMap<SourceFile, List<LocalClassSemantics.VariableIdentity>>
            variablesBySource = new IdentityHashMap<>();
    private final Map<String, LocalClassSemantics.ScopeDescriptor> scopesById =
            new LinkedHashMap<>();

    static LexicalTypeScopes empty() {
        return new LexicalTypeScopes();
    }

    void registerDiscovery(SourceFile source, LocalClassDiscovery.Result discovery) {
        List<LocalClassSemantics.ScopeDescriptor> scopes = scopesBySource.computeIfAbsent(
                source, ignored -> new ArrayList<>());
        for (LocalClassSemantics.ScopeDescriptor scope : discovery.scopes()) {
            if (!scopesById.containsKey(scope.id())) {
                scopes.add(scope);
                scopesById.put(scope.id(), scope);
            }
        }
    }

    void registerVariables(SourceFile source,
                           List<LocalClassSemantics.VariableIdentity> variables) {
        variablesBySource.computeIfAbsent(source, ignored -> new ArrayList<>())
                .addAll(variables);
    }

    void registerNode(Object node, TypeSymbol symbol) {
        if (node != null) {
            typesByNode.put(node, symbol);
        }
    }

    void registerSpan(SourceFile source, SourceSpan span, TypeSymbol symbol) {
        typesBySpan.computeIfAbsent(source, ignored -> new LinkedHashMap<>())
                .putIfAbsent(SpanKey.of(span), symbol);
    }

    Optional<TypeSymbol> addLocalBinding(SourceFile source, String simpleName,
                                         String declaringScopeId, SourceSpan declarationSpan,
                                         TypeSymbol symbol) {
        List<Binding> bindings = bindingsBySource.computeIfAbsent(
                source, ignored -> new ArrayList<>());
        Optional<TypeSymbol> duplicate = bindings.stream()
                .filter(binding -> binding.simpleName().equals(simpleName)
                        && binding.declaringScopeId().equals(declaringScopeId))
                .map(Binding::symbol).findFirst();
        bindings.add(new Binding(simpleName, declaringScopeId, declarationSpan, symbol));
        return duplicate;
    }

    Optional<TypeSymbol> typeFor(Object astNode) {
        return Optional.ofNullable(typesByNode.get(astNode));
    }

    Optional<TypeSymbol> typeAt(SourceFile source, SourceSpan span) {
        return Optional.ofNullable(typesBySpan.getOrDefault(source, Map.of())
                .get(SpanKey.of(span)));
    }

    Optional<TypeSymbol> resolve(String simpleName, TypeSymbol context, SourceSpan useSpan) {
        if (simpleName == null || simpleName.indexOf('.') >= 0 || context == null
                || useSpan == null) {
            return Optional.empty();
        }
        SourceFile source = context.source();
        LocalClassSemantics.ScopeDescriptor useScope = innermostScope(
                source, context, useSpan).orElse(null);
        if (useScope == null) {
            return Optional.empty();
        }
        int useOffset = useSpan.start().offset();
        return bindingsBySource.getOrDefault(source, List.of()).stream()
                .filter(binding -> binding.simpleName().equals(simpleName))
                .filter(binding -> binding.declarationSpan().start().offset() <= useOffset)
                .filter(binding -> isAncestor(binding.declaringScopeId(), useScope.id()))
                .sorted(Comparator
                        .comparingInt((Binding binding) -> depth(binding.declaringScopeId()))
                        .reversed()
                        .thenComparingInt(binding ->
                                -binding.declarationSpan().start().offset()))
                .map(Binding::symbol).findFirst();
    }

    Optional<LocalClassSemantics.VariableIdentity> resolveVariable(
            String name, TypeSymbol context, SourceSpan useSpan) {
        if (name == null || name.indexOf('.') >= 0 || context == null || useSpan == null) {
            return Optional.empty();
        }
        LocalClassSemantics.ScopeDescriptor useScope = innermostScope(
                context.source(), context, useSpan).orElse(null);
        if (useScope == null) {
            return Optional.empty();
        }
        int useOffset = useSpan.start().offset();
        return variablesBySource.getOrDefault(context.source(), List.of()).stream()
                .filter(variable -> variable.name().equals(name))
                .filter(variable -> variable.nameSpan().start().offset() < useOffset)
                .filter(variable -> isAncestor(variable.declaringScopeId(), useScope.id()))
                .sorted(Comparator
                        .comparingInt((LocalClassSemantics.VariableIdentity variable) ->
                                depth(variable.declaringScopeId())).reversed()
                        .thenComparingInt(variable -> -variable.declarationOrdinal()))
                .findFirst();
    }

    private Optional<LocalClassSemantics.ScopeDescriptor> innermostScope(
            SourceFile source, TypeSymbol context, SourceSpan useSpan) {
        Set<String> lexicalOwners = new LinkedHashSet<>();
        for (TypeSymbol owner = context; owner != null;
             owner = owner.enclosingType().orElse(null)) {
            lexicalOwners.add(owner.name());
        }
        int offset = useSpan.start().offset();
        return scopesBySource.getOrDefault(source, List.of()).stream()
                .filter(scope -> scope.span().start().offset() <= offset
                        && offset <= scope.span().end().offset())
                .filter(scope -> lexicalOwners.contains(scope.ownerBinaryName()))
                .max(Comparator.comparingInt((LocalClassSemantics.ScopeDescriptor scope) ->
                        depth(scope.id())).thenComparingInt(scope ->
                        -scope.span().end().offset() + scope.span().start().offset()));
    }

    private boolean isAncestor(String possibleAncestor, String scopeId) {
        for (LocalClassSemantics.ScopeDescriptor current = scopesById.get(scopeId);
             current != null;
             current = current.parentId().map(scopesById::get).orElse(null)) {
            if (current.id().equals(possibleAncestor)) {
                return true;
            }
        }
        return false;
    }

    private int depth(String scopeId) {
        int result = 0;
        for (LocalClassSemantics.ScopeDescriptor current = scopesById.get(scopeId);
             current != null;
             current = current.parentId().map(scopesById::get).orElse(null)) {
            result++;
        }
        return result;
    }

    private record Binding(String simpleName, String declaringScopeId,
                           SourceSpan declarationSpan, TypeSymbol symbol) {
    }

    private record SpanKey(int startOffset, int endOffset) {
        private static SpanKey of(SourceSpan span) {
            return new SpanKey(span.start().offset(), span.end().offset());
        }
    }
}
