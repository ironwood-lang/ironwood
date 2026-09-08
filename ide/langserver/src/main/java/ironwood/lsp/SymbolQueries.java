// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.lsp;

import ironwood.compiler.ast.CompilationUnit;
import ironwood.compiler.lexer.DocumentationComment;
import ironwood.compiler.lexer.Lexer;
import ironwood.compiler.lexer.Token;
import ironwood.compiler.lexer.TokenKind;
import ironwood.compiler.parser.Parser;
import ironwood.compiler.source.SourceFile;

import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MarkupKind;
import org.eclipse.lsp4j.Position;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Answers hover and go-to-definition questions from the syntax tree.
 *
 * <p>Both work without semantic analysis, which keeps them fast and keeps them
 * working in a file that does not compile yet. The cost is a deliberate limit:
 * a name that is not a declaration in the current file is resolved by matching
 * it against the type names declared in the source set, so an identifier that
 * merely shares a type's spelling resolves to that type. Resolving a name the
 * way the compiler does would need the semantic binder to expose its results,
 * which it does not today.
 */
public final class SymbolQueries {

    private final DocumentStore documents;

    /**
     * Parsed files keyed by path. Parsing is far cheaper than analysis, but
     * hovering should not reparse a whole source set per keystroke, so each
     * entry is reused until the file's content changes.
     */
    private final Map<Path, CachedUnit> cache = new ConcurrentHashMap<>();

    private record CachedUnit(int contentHash, CompilationUnit unit,
                              List<DocumentationComment> documentation,
                              List<Declarations.Declared> declared) {
    }

    public SymbolQueries(DocumentStore documents) {
        this.documents = documents;
    }

    public Optional<Hover> hover(Path path, Position position) {
        Optional<CachedUnit> current = unitFor(path);
        if (current.isEmpty()) {
            return Optional.empty();
        }

        // A cursor on a declaration's own name describes that declaration
        // exactly, with no name resolution needed.
        Optional<Declarations.Declared> local = current.get().declared().stream()
                .filter(entry -> Ranges.contains(entry.nameSpan(), position))
                .findFirst();
        if (local.isPresent()) {
            return Optional.of(hoverFor(local.get(),
                    Declarations.documentationFor(current.get().documentation(),
                            local.get().span()),
                    Ranges.of(local.get().nameSpan())));
        }

        Optional<Token> identifier = identifierAt(path, position);
        if (identifier.isEmpty()) {
            return Optional.empty();
        }
        Optional<TypeLocation> type = findType(path, identifier.get().lexeme());
        return type.map(found -> hoverFor(found.declared(), found.documentation(),
                Ranges.of(identifier.get().span())));
    }

    public List<Location> definition(Path path, Position position) {
        Optional<Token> identifier = identifierAt(path, position);
        if (identifier.isEmpty()) {
            return List.of();
        }
        return findType(path, identifier.get().lexeme())
                .map(found -> List.of(new Location(DocumentStore.toUri(found.path()),
                        Ranges.of(found.declared().nameSpan()))))
                .orElse(List.of());
    }

    private record TypeLocation(Path path, Declarations.Declared declared,
                                Optional<String> documentation) {
    }

    /**
     * Looks for a type of the given simple name across the file's source set.
     * The file's own declarations are searched first so that a type shadowing a
     * name from elsewhere still resolves to itself.
     */
    private Optional<TypeLocation> findType(Path from, String simpleName) {
        List<Path> candidates = new ArrayList<>();
        candidates.add(from);
        sourceSetOf(from).forEach(path -> {
            if (!path.equals(from)) {
                candidates.add(path);
            }
        });

        for (Path candidate : candidates) {
            Optional<CachedUnit> unit = unitFor(candidate);
            if (unit.isEmpty()) {
                continue;
            }
            for (Declarations.Declared declared : unit.get().declared()) {
                if (declared.isType() && declared.name().equals(simpleName)) {
                    return Optional.of(new TypeLocation(candidate, declared,
                            Declarations.documentationFor(unit.get().documentation(),
                                    declared.span())));
                }
            }
        }
        return Optional.empty();
    }

    private List<Path> sourceSetOf(Path file) {
        Optional<String> content = contentOf(file);
        if (content.isEmpty()) {
            return List.of();
        }
        Optional<Path> root = SourceSetResolver.sourceRootFor(file, content.get());
        if (root.isEmpty()) {
            return List.of();
        }
        try {
            return SourceSetResolver.collectSources(root.get());
        } catch (IOException error) {
            return List.of();
        }
    }

    private Hover hoverFor(Declarations.Declared declared, Optional<String> documentation,
                           org.eclipse.lsp4j.Range range) {
        StringBuilder text = new StringBuilder();
        text.append("```iron\n").append(declared.signature()).append("\n```");
        declared.enclosing().ifPresent(owner ->
                text.append("\n\nDeclared in `").append(owner).append('`'));
        documentation.filter(doc -> !doc.isEmpty())
                .ifPresent(doc -> text.append("\n\n").append(doc));

        Hover hover = new Hover();
        hover.setContents(new MarkupContent(MarkupKind.MARKDOWN, text.toString()));
        hover.setRange(range);
        return hover;
    }

    private Optional<Token> identifierAt(Path path, Position position) {
        Optional<String> content = contentOf(path);
        if (content.isEmpty()) {
            return Optional.empty();
        }
        SourceFile source = SourceFile.of(path.toString(), content.get());
        for (Token token : new Lexer(source).lex().tokens()) {
            if (token.kind() == TokenKind.IDENTIFIER && Ranges.contains(token.span(), position)) {
                return Optional.of(token);
            }
        }
        return Optional.empty();
    }

    private Optional<CachedUnit> unitFor(Path path) {
        Optional<String> content = contentOf(path);
        if (content.isEmpty()) {
            cache.remove(path);
            return Optional.empty();
        }
        int hash = content.get().hashCode();
        CachedUnit cached = cache.get(path);
        if (cached != null && cached.contentHash() == hash) {
            return Optional.of(cached);
        }

        SourceFile source = SourceFile.of(path.toString(), content.get());
        Lexer lexer = new Lexer(source, true);
        CompilationUnit unit = new Parser(source, lexer.lex().tokens()).parse().unit().orElse(null);
        if (unit == null) {
            cache.remove(path);
            return Optional.empty();
        }
        CachedUnit fresh = new CachedUnit(hash, unit, List.copyOf(lexer.documentationComments()),
                Declarations.of(unit));
        cache.put(path, fresh);
        return Optional.of(fresh);
    }

    private Optional<String> contentOf(Path path) {
        Optional<String> open = documents.contentOf(path);
        if (open.isPresent()) {
            return open;
        }
        try {
            return Optional.of(SourceFile.read(path).content());
        } catch (IOException error) {
            return Optional.empty();
        }
    }

    /** Drops cached parses, used when a document is closed. */
    public void forget(Path path) {
        cache.remove(path);
    }
}
