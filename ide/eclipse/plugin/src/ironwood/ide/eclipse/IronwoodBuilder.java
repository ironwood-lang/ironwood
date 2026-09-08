// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.ide.eclipse;

import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.osgi.framework.FrameworkUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Compiles an Ironwood project and turns the compiler's errors into markers.
 *
 * <p>Ironwood's analysis is whole-program, so there is no useful notion of
 * rebuilding one changed file: an edit in one file can make a {@code free} in
 * another provable or unprovable. Every build therefore compiles the whole
 * project, and an incremental build differs from a full one only in that it
 * returns early when no Ironwood source changed.
 */
public final class IronwoodBuilder extends IncrementalProjectBuilder {

    public static final String ID = "ironwood.ide.eclipse.builder";

    /** Marker type contributed by this plugin, so its markers clear cleanly. */
    public static final String MARKER_TYPE = "ironwood.ide.eclipse.problem";

    @Override
    protected IProject[] build(int kind, Map<String, String> args, IProgressMonitor monitor)
            throws CoreException {
        IProject project = getProject();
        if (kind == INCREMENTAL_BUILD || kind == AUTO_BUILD) {
            if (!needsBuild(project)) {
                return null;
            }
        }
        compile(project, monitor);
        return null;
    }

    @Override
    protected void clean(IProgressMonitor monitor) throws CoreException {
        clearMarkers(getProject());
    }

    /**
     * Reports whether an incremental build has anything to do.
     *
     * <p>Ironwood source changing is the usual reason, but a missing output
     * directory is another: someone who deletes {@code target} expects the next
     * build to put it back rather than to decide nothing changed.
     */
    private boolean needsBuild(IProject project) throws CoreException {
        if (!Files.isDirectory(new IronwoodProject(project).classOutput())) {
            return true;
        }
        var delta = getDelta(project);
        if (delta == null) {
            return true;
        }
        boolean[] relevant = {false};
        delta.accept(change -> {
            IResource resource = change.getResource();
            if (resource.getType() == IResource.FILE
                    && "iron".equals(resource.getFileExtension())) {
                relevant[0] = true;
            }
            return !relevant[0];
        });
        return relevant[0];
    }

    private void compile(IProject project, IProgressMonitor monitor) throws CoreException {
        IronwoodProject model = new IronwoodProject(project);
        clearMarkers(project);

        List<Path> sources = model.sourceFiles();
        if (sources.isEmpty()) {
            return;
        }

        IronwoodInstallation.Result located = IronwoodInstallation.locate();
        if (!located.usable()) {
            // Without an installation there is nothing to compile with, and a
            // marker on the project says so where the reader will see it.
            createProjectMarker(project, located.problem());
            return;
        }
        IronwoodInstallation installation = located.installation().orElseThrow();
        Path launcher = installation.launcher().orElse(null);
        if (launcher == null) {
            createProjectMarker(project, "No bin/ironwoodc under '" + installation.home()
                    + "', so the project cannot be compiled.");
            return;
        }

        try {
            Files.createDirectories(model.classOutput());
            List<String> command = new java.util.ArrayList<>(List.of(
                    launcher.toString(),
                    "--source-path", model.sourcePathArgument(),
                    "-d", model.classOutput().toString()));

            // A project with tests needs TestSuite and Assertions on the
            // classpath, or every test file fails to resolve them.
            if (model.testSourceFolder().isPresent()) {
                Path testing = installation.testingArchive().orElse(null);
                if (testing == null) {
                    createProjectMarker(project, "This project has "
                            + IronwoodProject.TEST_SOURCE_FOLDER
                            + ", but no ironwood-testing.ironjar was found under '"
                            + installation.home() + "', so its tests cannot compile.");
                    return;
                }
                command.add("-cp");
                command.add(testing.toString());
            }

            sources.forEach(source -> command.add(source.toString()));

            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(model.location().toFile());
            builder.redirectErrorStream(true);
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            int status = process.waitFor();

            applyDiagnostics(project, model, output);

            // A non-zero exit with nothing parseable means the compiler failed
            // in a way the reader still needs to see.
            if (status != 0 && CompilerOutputParser.parse(output).isEmpty()) {
                createProjectMarker(project, "The Ironwood compiler failed: "
                        + output.strip());
            }
        } catch (IOException error) {
            throw new CoreException(status("Could not run the Ironwood compiler.", error));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new CoreException(status("Interrupted while compiling.", error));
        } finally {
            // The compiler wrote outside the workbench's knowledge, so the
            // output directory has to be refreshed for it to appear.
            project.refreshLocal(IResource.DEPTH_INFINITE, monitor);
        }
    }

    private void applyDiagnostics(IProject project, IronwoodProject model, String output)
            throws CoreException {
        for (CompilerOutputParser.CompilerDiagnostic diagnostic
                : CompilerOutputParser.parse(output)) {
            IResource target = diagnostic.path()
                    .map(model::resourceFor)
                    .orElse(null);
            if (target == null) {
                // A diagnostic about a file outside the project, or about the
                // compilation as a whole, still belongs somewhere visible.
                createProjectMarker(project, describeElsewhere(diagnostic));
                continue;
            }
            IMarker marker = target.createMarker(MARKER_TYPE);
            marker.setAttribute(IMarker.MESSAGE, diagnostic.message());
            marker.setAttribute(IMarker.SEVERITY, IMarker.SEVERITY_ERROR);
            marker.setAttribute(IMarker.PRIORITY, IMarker.PRIORITY_HIGH);
            if (diagnostic.line() > 0) {
                marker.setAttribute(IMarker.LINE_NUMBER, diagnostic.line());
            }
        }
    }

    private static String describeElsewhere(CompilerOutputParser.CompilerDiagnostic diagnostic) {
        return diagnostic.path()
                .map(path -> diagnostic.message() + " (" + path + ":" + diagnostic.line() + ")")
                .orElse(diagnostic.message());
    }

    private void createProjectMarker(IProject project, String message) throws CoreException {
        IMarker marker = project.createMarker(MARKER_TYPE);
        marker.setAttribute(IMarker.MESSAGE, message);
        marker.setAttribute(IMarker.SEVERITY, IMarker.SEVERITY_ERROR);
    }

    private void clearMarkers(IProject project) throws CoreException {
        project.deleteMarkers(MARKER_TYPE, true, IResource.DEPTH_INFINITE);
    }

    private static IStatus status(String message, Throwable error) {
        return Status.error(message, error);
    }

    static void log(String message, Throwable error) {
        Platform.getLog(FrameworkUtil.getBundle(IronwoodBuilder.class)).error(message, error);
    }
}
