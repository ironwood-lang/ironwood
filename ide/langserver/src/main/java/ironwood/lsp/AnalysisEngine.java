// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.lsp;

import ironwood.compiler.CompilationArtifact;
import ironwood.compiler.CompilerPipeline;
import ironwood.compiler.diagnostic.Diagnostic;
import ironwood.compiler.source.SourceFile;

import org.eclipse.lsp4j.DiagnosticSeverity;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Runs the compiler front end over a source set and translates what it reports
 * into protocol diagnostics.
 *
 * <p>Analysis goes through {@link CompilerPipeline#analyze}, which is the same
 * front end {@code ironwoodc} runs, stopping before code generation. There is
 * no second, IDE-specific implementation of Ironwood's rules, so an editor
 * squiggle and a command-line error always agree, including ownership and
 * reclamation errors that only whole-program analysis can find.
 */
public final class AnalysisEngine {

    /** Diagnostic source label shown next to each problem in the editor. */
    private static final String SOURCE_LABEL = "ironwood";

    private final DocumentStore documents;

    public AnalysisEngine(DocumentStore documents) {
        this.documents = documents;
    }

    /**
     * The outcome of one analysis: diagnostics grouped by the file they belong
     * to, with an entry for every analyzed file so that a file whose errors are
     * now fixed is published as empty and its markers clear.
     */
    public record Result(Map<String, List<org.eclipse.lsp4j.Diagnostic>> diagnosticsByUri,
                         int analyzedFileCount,
                         boolean truncated) {
    }

    public Result analyze(Path trigger) {
        String content = documents.contentOf(trigger).orElse(null);
        if (content == null) {
            try {
                content = SourceFile.read(trigger).content();
            } catch (IOException error) {
                return new Result(Map.of(), 0, false);
            }
        }

        Optional<Path> root = SourceSetResolver.sourceRootFor(trigger, content);
        List<Path> paths;
        if (root.isEmpty()) {
            // A file whose package does not match its directory is still worth
            // checking on its own, which is what surfaces the mismatch.
            paths = List.of(trigger);
        } else {
            try {
                paths = SourceSetResolver.collectSources(root.get());
            } catch (IOException error) {
                paths = List.of(trigger);
            }
            if (!paths.contains(trigger)) {
                paths = new ArrayList<>(paths);
                paths.add(trigger);
            }
        }

        List<SourceFile> sources = SourceSetResolver.toSourceFiles(paths, documents);
        CompilationArtifact artifact = new CompilerPipeline().analyze(sources);

        Map<String, List<org.eclipse.lsp4j.Diagnostic>> byUri = new LinkedHashMap<>();
        for (SourceFile source : sources) {
            byUri.put(DocumentStore.toUri(source.path()), new ArrayList<>());
        }
        String triggerUri = DocumentStore.toUri(trigger);
        byUri.computeIfAbsent(triggerUri, key -> new ArrayList<>());

        for (Diagnostic diagnostic : artifact.diagnostics()) {
            // A diagnostic with no source is about the compilation as a whole.
            // Attaching it to the file being edited keeps it visible rather
            // than dropping it.
            String uri = diagnostic.source() == null
                    ? triggerUri
                    : DocumentStore.toUri(diagnostic.source().path());
            byUri.computeIfAbsent(uri, key -> new ArrayList<>()).add(translate(diagnostic));
        }

        return new Result(byUri, sources.size(), paths.size() >= SourceSetResolver.MAX_SOURCES);
    }

    /**
     * Converts one compiler diagnostic into its protocol form. A diagnostic
     * without a span is about the compilation rather than a location, so it is
     * anchored at the start of the file.
     */
    private static org.eclipse.lsp4j.Diagnostic translate(Diagnostic diagnostic) {
        org.eclipse.lsp4j.Diagnostic translated = new org.eclipse.lsp4j.Diagnostic();
        translated.setMessage(diagnostic.message());
        translated.setSeverity(DiagnosticSeverity.Error);
        translated.setSource(SOURCE_LABEL);
        translated.setRange(Ranges.of(diagnostic.span()));
        return translated;
    }
}
