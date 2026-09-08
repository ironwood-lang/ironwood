// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.ide.eclipse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Locates the Ironwood installation the language server should analyze against.
 *
 * <p>The server needs the bootstrap compiler jar, and the compiler in turn
 * discovers the standard library by walking up from its own location. Pointing
 * at a real installation therefore settles both, which is why this resolves an
 * installation root rather than a bare jar path.
 *
 * <p>Both an extracted IDK and a source checkout are supported, since a
 * language developer works in the checkout while everyone else uses a release.
 */
public final class IronwoodInstallation {

    /** Overrides discovery entirely, for an installation in an unusual place. */
    public static final String HOME_ENVIRONMENT_VARIABLE = "IRONWOOD_HOME";

    /** Compiler jar locations, in the order they are tried. */
    private static final List<String> COMPILER_JAR_CANDIDATES = List.of(
            "lib/ironwoodc.jar",                 // extracted IDK
            "compiler/build/ironwoodc.jar");     // source checkout

    /** Testing archive locations, in the order they are tried. */
    private static final List<String> TESTING_ARCHIVE_CANDIDATES = List.of(
            "lib/ironwood-testing.ironjar",
            "compiler/build/ironwood-testing.ironjar");

    /**
     * The compiler launcher. Both an IDK and a checkout ship it, and it is the
     * entry point that prepares the bundled JVM and LLVM toolchain, which
     * linking a native executable requires.
     */
    private static final String LAUNCHER = "bin/ironwoodc";

    private final Path home;
    private final Path compilerJar;

    private IronwoodInstallation(Path home, Path compilerJar) {
        this.home = home;
        this.compilerJar = compilerJar;
    }

    public Path home() {
        return home;
    }

    public Path compilerJar() {
        return compilerJar;
    }

    /**
     * The launcher script, or empty when the installation has none. Compiling
     * only needs the jar, so a missing launcher is reported at the point that
     * needs it rather than failing installation discovery outright.
     */
    public Optional<Path> launcher() {
        Path launcher = home.resolve(LAUNCHER);
        return Files.isExecutable(launcher) ? Optional.of(launcher) : Optional.empty();
    }

    /**
     * The archive holding {@code TestSuite} and {@code Assertions}, or empty
     * when the installation has none. A project without tests does not need it,
     * so its absence is only reported when a test source folder exists.
     */
    public Optional<Path> testingArchive() {
        return TESTING_ARCHIVE_CANDIDATES.stream()
                .map(home::resolve)
                .filter(Files::isRegularFile)
                .findFirst();
    }

    /**
     * Finds an installation, or reports why none could be used.
     *
     * <p>A missing installation is a configuration problem the programmer has
     * to fix, so the failure carries an actionable message rather than being
     * swallowed into a silently dead language server.
     */
    public static Result locate() {
        // The preference wins over the environment because Eclipse started from
        // the Dock or Finder inherits no shell environment, so the preference is
        // the only setting most users can rely on.
        String configured = IronwoodPreferences.ironwoodHome();
        String origin = "The Ironwood home preference";
        if (configured.isEmpty()) {
            configured = System.getenv(HOME_ENVIRONMENT_VARIABLE);
            origin = HOME_ENVIRONMENT_VARIABLE;
        }
        if (configured == null || configured.isBlank()) {
            return Result.failure("Set the Ironwood home on the Preferences > Ironwood page,"
                    + " or the " + HOME_ENVIRONMENT_VARIABLE + " environment variable, to an"
                    + " Ironwood installation or source checkout so that Ironwood editors can"
                    + " report errors.");
        }

        Path home = Path.of(configured).toAbsolutePath().normalize();
        if (!Files.isDirectory(home)) {
            return Result.failure(origin + " points at '" + home
                    + "', which is not a directory.");
        }

        Optional<Path> jar = COMPILER_JAR_CANDIDATES.stream()
                .map(home::resolve)
                .filter(Files::isRegularFile)
                .findFirst();
        if (jar.isEmpty()) {
            return Result.failure("No Ironwood compiler jar under '" + home + "'. Expected "
                    + String.join(" or ", COMPILER_JAR_CANDIDATES)
                    + ". Build the compiler, or point " + HOME_ENVIRONMENT_VARIABLE
                    + " at an extracted IDK.");
        }

        return Result.success(new IronwoodInstallation(home, jar.get()));
    }

    /** Either a usable installation or the reason there is none. */
    public record Result(Optional<IronwoodInstallation> installation, String problem) {

        static Result success(IronwoodInstallation installation) {
            return new Result(Optional.of(installation), null);
        }

        static Result failure(String problem) {
            return new Result(Optional.empty(), problem);
        }

        public boolean usable() {
            return installation.isPresent();
        }
    }
}
