// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.backend;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class RuntimeLibrary {
    private static final String SOURCE_PATH = "runtime/src/ironwood_runtime.c";

    private RuntimeLibrary() {
    }

    public static Discovery discover() {
        List<Path> roots = new ArrayList<>();
        String explicitHome = System.getenv("IRONWOOD_RUNTIME_HOME");
        if (explicitHome != null && !explicitHome.isBlank()) {
            Path home = Path.of(explicitHome).toAbsolutePath().normalize();
            Path source = runtimeSource(home);
            if (Files.isRegularFile(source)) {
                return new Discovery(Optional.of(source), "");
            }
            return new Discovery(Optional.empty(),
                    "IRONWOOD_RUNTIME_HOME does not contain " + SOURCE_PATH + ": " + home);
        }

        try {
            Path codeLocation = Path.of(RuntimeLibrary.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
            roots.add(Files.isDirectory(codeLocation) ? codeLocation : codeLocation.getParent());
        } catch (URISyntaxException | NullPointerException ignored) {
            // The working-directory search below still supports ordinary launches.
        }
        roots.add(Path.of("").toAbsolutePath().normalize());

        for (Path root : roots) {
            for (Path candidateRoot = root; candidateRoot != null; candidateRoot = candidateRoot.getParent()) {
                Path source = runtimeSource(candidateRoot);
                if (Files.isRegularFile(source)) {
                    return new Discovery(Optional.of(source), "");
                }
            }
        }
        return new Discovery(Optional.empty(),
                "cannot locate " + SOURCE_PATH + "; set IRONWOOD_RUNTIME_HOME to the Ironwood distribution root");
    }

    private static Path runtimeSource(Path root) {
        return root.resolve(SOURCE_PATH).toAbsolutePath().normalize();
    }

    public record Discovery(Optional<Path> source, String error) {
        public Discovery {
            source = source == null ? Optional.empty() : source;
        }

        public boolean successful() {
            return source.isPresent();
        }
    }
}
