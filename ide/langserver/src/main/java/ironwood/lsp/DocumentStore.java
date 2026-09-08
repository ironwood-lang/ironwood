// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.lsp;

import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks the contents of documents the editor currently has open.
 *
 * <p>An open document's buffer is authoritative over the file on disk, which is
 * what lets diagnostics appear while typing rather than only after a save.
 * Documents are keyed by normalized absolute path so that a file reached
 * through different URI spellings resolves to one entry.
 */
public final class DocumentStore {

    private final Map<Path, String> openDocuments = new ConcurrentHashMap<>();

    public void open(String uri, String content) {
        toPath(uri).ifPresent(path -> openDocuments.put(path, content));
    }

    public void update(String uri, String content) {
        open(uri, content);
    }

    public void close(String uri) {
        toPath(uri).ifPresent(openDocuments::remove);
    }

    public Optional<String> contentOf(Path path) {
        return Optional.ofNullable(openDocuments.get(normalize(path)));
    }

    public boolean isOpen(Path path) {
        return openDocuments.containsKey(normalize(path));
    }

    /**
     * Resolves a document URI to a normalized absolute path, or empty when the
     * URI does not denote a local file. Untitled and in-memory documents have
     * no path and are outside what the compiler front end can analyze.
     */
    public static Optional<Path> toPath(String uri) {
        try {
            URI parsed = URI.create(uri);
            if (!"file".equalsIgnoreCase(parsed.getScheme())) {
                return Optional.empty();
            }
            return Optional.of(normalize(Path.of(parsed)));
        } catch (IllegalArgumentException | java.nio.file.FileSystemNotFoundException error) {
            return Optional.empty();
        }
    }

    public static String toUri(Path path) {
        return normalize(path).toUri().toString();
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }
}
