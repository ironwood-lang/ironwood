// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.backend;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Compiler-owned executable discovery (B4): launches use absolute paths, so a
 * caller that needs a tool from PATH resolves it here first. Entries are
 * tried in order; an empty entry is skipped rather than meaning the working
 * directory, a relative entry is resolved against the working directory, and
 * the first regular file the process may execute wins. No component is
 * normalized lexically, so the selected file is the one exec would run.
 */
final class ExecutableSearch {
    private ExecutableSearch() {
    }

    static Optional<Path> find(String name) {
        return find(name, System.getenv("PATH"), Path.of("").toAbsolutePath());
    }

    static Optional<Path> find(String name, String pathValue, Path workingDirectory) {
        if (pathValue == null || name.isEmpty() || name.indexOf('/') >= 0) {
            return Optional.empty();
        }
        for (String entry : pathValue.split(java.io.File.pathSeparator, -1)) {
            if (entry.isEmpty()) {
                continue;
            }
            Path candidate = workingDirectory.resolve(entry).resolve(name);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }
}
