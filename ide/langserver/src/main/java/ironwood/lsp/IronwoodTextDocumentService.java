// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.lsp;

import ironwood.compiler.ast.CompilationUnit;
import ironwood.compiler.lexer.Lexer;
import ironwood.compiler.parser.Parser;
import ironwood.compiler.source.SourceFile;

import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.TextDocumentService;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Handles document lifecycle notifications and keeps diagnostics current.
 *
 * <p>Typing produces a change notification per keystroke, so analysis is
 * debounced: each change schedules a run and supersedes any run still waiting.
 * Analysis itself is serialized on a single thread, which keeps the compiler
 * front end off the protocol threads and means only one whole-source-set pass
 * is ever in flight.
 */
public final class IronwoodTextDocumentService implements TextDocumentService {

    /**
     * How long to wait for typing to settle before analyzing. Short enough that
     * errors feel immediate, long enough that a burst of keystrokes triggers
     * one analysis rather than one per character.
     */
    private static final long DEBOUNCE_MILLIS = 300;

    private final DocumentStore documents = new DocumentStore();
    private final AnalysisEngine engine = new AnalysisEngine(documents);
    private final SymbolQueries queries = new SymbolQueries(documents);
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "ironwood-analysis");
                thread.setDaemon(true);
                return thread;
            });

    /** Guards against a superseded run publishing over a newer one's results. */
    private final AtomicLong scheduledGeneration = new AtomicLong();

    /**
     * URIs that currently carry published diagnostics. An entry that no longer
     * appears in a later analysis is republished as empty so its markers clear
     * rather than lingering.
     */
    private final Set<String> publishedUris = new HashSet<>();

    private volatile LanguageClient client;

    public void connect(LanguageClient client) {
        this.client = client;
    }

    public void shutdown() {
        scheduler.shutdownNow();
    }

    @Override
    public void didOpen(DidOpenTextDocumentParams params) {
        String uri = params.getTextDocument().getUri();
        documents.open(uri, params.getTextDocument().getText());
        scheduleAnalysis(uri, 0);
    }

    @Override
    public void didChange(DidChangeTextDocumentParams params) {
        String uri = params.getTextDocument().getUri();
        // The server advertises full synchronization, so the last change in the
        // list carries the complete new text.
        params.getContentChanges().stream()
                .reduce((first, second) -> second)
                .ifPresent(change -> documents.update(uri, change.getText()));
        scheduleAnalysis(uri, DEBOUNCE_MILLIS);
    }

    @Override
    public void didSave(DidSaveTextDocumentParams params) {
        scheduleAnalysis(params.getTextDocument().getUri(), 0);
    }

    /**
     * Supplies the Outline view.
     *
     * <p>Built from the syntax tree alone, so the outline still works while the
     * file has semantic errors, which is when it is most useful. Parsing one
     * file is cheap enough to do on the calling thread rather than scheduling
     * it behind the analysis queue.
     */
    @Override
    public CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> documentSymbol(
            DocumentSymbolParams params) {
        String uri = params.getTextDocument().getUri();
        return CompletableFuture.supplyAsync(() -> {
            Optional<CompilationUnit> unit = parseUnit(uri);
            if (unit.isEmpty()) {
                return List.of();
            }
            return DocumentSymbols.of(unit.get()).stream()
                    .map(symbol -> Either.<SymbolInformation, DocumentSymbol>forRight(symbol))
                    .toList();
        });
    }

    /**
     * Describes what the cursor is on: a declaration's signature and its
     * IronDocs comment, or the type a name refers to.
     */
    @Override
    public CompletableFuture<Hover> hover(HoverParams params) {
        Path path = DocumentStore.toPath(params.getTextDocument().getUri()).orElse(null);
        if (path == null) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.supplyAsync(
                () -> queries.hover(path, params.getPosition()).orElse(null));
    }

    /** Jumps from a type name to the file that declares it. */
    @Override
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>>
            definition(DefinitionParams params) {
        Path path = DocumentStore.toPath(params.getTextDocument().getUri()).orElse(null);
        if (path == null) {
            return CompletableFuture.completedFuture(Either.forLeft(List.of()));
        }
        return CompletableFuture.supplyAsync(() ->
                Either.forLeft(queries.definition(path, params.getPosition())));
    }

    private Optional<CompilationUnit> parseUnit(String uri) {
        Path path = DocumentStore.toPath(uri).orElse(null);
        if (path == null) {
            return Optional.empty();
        }
        String content = documents.contentOf(path).orElse(null);
        if (content == null) {
            try {
                content = SourceFile.read(path).content();
            } catch (IOException error) {
                return Optional.empty();
            }
        }
        SourceFile source = SourceFile.of(path.toString(), content);
        return new Parser(source, new Lexer(source).lex().tokens()).parse().unit();
    }

    @Override
    public void didClose(DidCloseTextDocumentParams params) {
        String uri = params.getTextDocument().getUri();
        documents.close(uri);
        DocumentStore.toPath(uri).ifPresent(queries::forget);
        // Diagnostics for a closed file are cleared, because the editor no
        // longer shows the buffer they were computed against.
        publish(uri, List.of());
    }

    private void scheduleAnalysis(String uri, long delayMillis) {
        Path path = DocumentStore.toPath(uri).orElse(null);
        if (path == null) {
            return;
        }
        long generation = scheduledGeneration.incrementAndGet();
        scheduler.schedule(() -> runAnalysis(path, generation),
                delayMillis, TimeUnit.MILLISECONDS);
    }

    private void runAnalysis(Path path, long generation) {
        if (generation != scheduledGeneration.get()) {
            return;
        }
        AnalysisEngine.Result result;
        try {
            result = engine.analyze(path);
        } catch (RuntimeException error) {
            // A crash in the front end must not take the server down, or the
            // editor loses every later diagnostic with no way back.
            logError("analysis failed for " + path + ": " + error);
            return;
        }
        if (generation != scheduledGeneration.get()) {
            return;
        }
        publishAll(result);
    }

    /**
     * Publishes results for documents the editor actually shows.
     *
     * <p>Analysis covers the whole source set, because Ironwood's ownership
     * rules are not decidable one file at a time, but diagnostics are published
     * only for open documents and for documents that previously had some, so
     * that fixed errors clear. Publishing for a file the editor has never
     * opened gains nothing visible and makes a host go looking for an editor
     * that does not exist. Project-wide problems belong to the builder, which
     * owns real workspace markers.
     */
    private synchronized void publishAll(AnalysisEngine.Result result) {
        Map<String, List<org.eclipse.lsp4j.Diagnostic>> byUri = result.diagnosticsByUri();

        List<String> stale = new ArrayList<>();
        for (String uri : publishedUris) {
            if (!byUri.containsKey(uri)) {
                stale.add(uri);
            }
        }
        for (String uri : stale) {
            publishWithoutTracking(uri, List.of());
        }
        publishedUris.removeAll(stale);

        for (Map.Entry<String, List<org.eclipse.lsp4j.Diagnostic>> entry : byUri.entrySet()) {
            String uri = entry.getKey();
            boolean open = DocumentStore.toPath(uri).map(documents::isOpen).orElse(false);
            if (!open && !publishedUris.contains(uri)) {
                continue;
            }
            publishWithoutTracking(uri, entry.getValue());
            if (entry.getValue().isEmpty()) {
                publishedUris.remove(uri);
            } else {
                publishedUris.add(uri);
            }
        }
    }

    private synchronized void publish(String uri, List<org.eclipse.lsp4j.Diagnostic> diagnostics) {
        publishWithoutTracking(uri, diagnostics);
        if (diagnostics.isEmpty()) {
            publishedUris.remove(uri);
        } else {
            publishedUris.add(uri);
        }
    }

    private void publishWithoutTracking(String uri,
                                        List<org.eclipse.lsp4j.Diagnostic> diagnostics) {
        LanguageClient target = client;
        if (target != null) {
            target.publishDiagnostics(new PublishDiagnosticsParams(uri, diagnostics));
        }
    }

    private void logError(String message) {
        // Standard error is forwarded to the client's error stream, so it lands
        // in the host's log rather than corrupting the protocol on stdout.
        System.err.println(message);
    }
}
