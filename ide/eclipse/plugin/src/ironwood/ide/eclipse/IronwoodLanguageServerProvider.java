// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.ide.eclipse;

import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;
import org.eclipse.lsp4e.server.ProcessStreamConnectionProvider;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Launches the Ironwood language server as a separate process and connects
 * LSP4E to its standard streams.
 *
 * <p>Running the server out of process rather than inside the workbench keeps a
 * compiler fault from taking Eclipse down with it, and it is the same launch
 * shape any other editor would use, so the server stays portable.
 *
 * <p>The classpath is assembled from three places: the server jar shipped
 * inside this bundle, the bootstrap compiler jar from the Ironwood
 * installation, and the LSP4J jars the platform already provides. Nothing is
 * vendored, so the server always speaks the same protocol version as the LSP4E
 * client talking to it.
 */
public final class IronwoodLanguageServerProvider extends ProcessStreamConnectionProvider {

    /** The server jar, shipped as a resource inside this bundle. */
    private static final String SERVER_JAR_ENTRY = "lib/ironwood-langserver.jar";

    private static final String SERVER_MAIN_CLASS = "ironwood.lsp.IronwoodLanguageServerMain";

    /** Platform bundles supplying the protocol implementation. */
    private static final List<String> PROTOCOL_BUNDLES = List.of(
            "org.eclipse.lsp4j",
            "org.eclipse.lsp4j.jsonrpc",
            "com.google.gson");

    public IronwoodLanguageServerProvider() {
        List<String> command = buildCommand();
        if (!command.isEmpty()) {
            setCommands(command);
        }
    }

    @Override
    public void start() throws IOException {
        if (getCommands() == null || getCommands().isEmpty()) {
            // LSP4E surfaces this as a failed server rather than a silent
            // absence of diagnostics.
            throw new IOException("The Ironwood language server is not configured. "
                    + IronwoodInstallation.locate().problem());
        }
        super.start();
    }

    private static List<String> buildCommand() {
        IronwoodInstallation.Result located = IronwoodInstallation.locate();
        if (!located.usable()) {
            log(located.problem(), null);
            return List.of();
        }
        IronwoodInstallation installation = located.installation().orElseThrow();

        Optional<Path> serverJar = bundleResource(SERVER_JAR_ENTRY);
        if (serverJar.isEmpty()) {
            log("The Ironwood plugin is missing " + SERVER_JAR_ENTRY
                    + "; rebuild it with ide/eclipse/build.sh.", null);
            return List.of();
        }

        List<String> classpath = new ArrayList<>();
        classpath.add(serverJar.get().toString());
        classpath.add(installation.compilerJar().toString());
        for (String symbolicName : PROTOCOL_BUNDLES) {
            Optional<Path> jar = bundleLocation(symbolicName);
            if (jar.isEmpty()) {
                log("Bundle " + symbolicName + " is unavailable, so the Ironwood language"
                        + " server cannot start.", null);
                return List.of();
            }
            classpath.add(jar.get().toString());
        }

        List<String> command = new ArrayList<>();
        command.add(javaExecutable());
        command.add("-cp");
        command.add(String.join(File.pathSeparator, classpath));
        command.add(SERVER_MAIN_CLASS);
        return command;
    }

    /**
     * Runs the server on the JVM that is running Eclipse. That JVM already
     * satisfies the platform's own Java 21 minimum, which is the version the
     * bootstrap compiler needs, so there is no separate JVM to discover or
     * configure.
     */
    private static String javaExecutable() {
        Path javaHome = Path.of(System.getProperty("java.home"));
        return javaHome.resolve("bin").resolve("java").toString();
    }

    private static Optional<Path> bundleResource(String entry) {
        Bundle bundle = FrameworkUtil.getBundle(IronwoodLanguageServerProvider.class);
        if (bundle == null) {
            return Optional.empty();
        }
        try {
            java.net.URL url = FileLocator.toFileURL(bundle.getEntry(entry));
            return url == null ? Optional.empty() : Optional.of(Path.of(url.getPath()));
        } catch (IOException | NullPointerException error) {
            log("Could not resolve " + entry + " inside the Ironwood plugin.", error);
            return Optional.empty();
        }
    }

    private static Optional<Path> bundleLocation(String symbolicName) {
        Bundle bundle = Platform.getBundle(symbolicName);
        if (bundle == null) {
            return Optional.empty();
        }
        Optional<File> location = FileLocator.getBundleFileLocation(bundle);
        return location.map(File::toPath);
    }

    private static void log(String message, Throwable error) {
        Bundle bundle = FrameworkUtil.getBundle(IronwoodLanguageServerProvider.class);
        if (bundle == null) {
            return;
        }
        ILog log = Platform.getLog(bundle);
        if (error == null) {
            log.warn(message);
        } else {
            log.error(message, error);
        }
    }
}
