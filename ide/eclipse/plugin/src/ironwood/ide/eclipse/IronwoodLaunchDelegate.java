// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.ide.eclipse;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.model.ILaunchConfigurationDelegate;
import org.eclipse.debug.core.model.IProcess;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs an Ironwood program: link the compiled classes into a native executable,
 * then execute it.
 *
 * <p>Ironwood produces a native binary rather than something a virtual machine
 * runs, so launching is two steps and the second one starts an ordinary
 * process. Both are attached to the Console, which is where the reader expects
 * a link error and the program's own output to appear.
 */
public final class IronwoodLaunchDelegate implements ILaunchConfigurationDelegate {

    public static final String TYPE_ID = "ironwood.ide.eclipse.launchType";

    public static final String ATTRIBUTE_PROJECT = "ironwood.project";
    public static final String ATTRIBUTE_MAIN_CLASS = "ironwood.mainClass";
    public static final String ATTRIBUTE_ARGUMENTS = "ironwood.arguments";

    @Override
    public void launch(ILaunchConfiguration configuration, String mode, ILaunch launch,
                       IProgressMonitor monitor) throws CoreException {
        String projectName = configuration.getAttribute(ATTRIBUTE_PROJECT, "");
        String mainClass = configuration.getAttribute(ATTRIBUTE_MAIN_CLASS, "");
        if (projectName.isBlank() || mainClass.isBlank()) {
            throw new CoreException(Status.error(
                    "This launch configuration needs a project and a main class."));
        }

        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (!project.isAccessible()) {
            throw new CoreException(Status.error(
                    "Project '" + projectName + "' is not open."));
        }

        IronwoodInstallation.Result located = IronwoodInstallation.locate();
        if (!located.usable()) {
            throw new CoreException(Status.error(located.problem()));
        }
        Path launcher = located.installation().orElseThrow().launcher().orElseThrow(
                () -> new CoreException(Status.error("No bin/ironwoodc in the Ironwood home, "
                        + "so a native executable cannot be linked.")));

        IronwoodProject model = new IronwoodProject(project);
        link(launcher, located.installation().orElseThrow(), model, mainClass, launch);
        run(model, configuration, launch);

        try {
            project.refreshLocal(IResource.DEPTH_INFINITE, monitor);
        } catch (CoreException ignored) {
            // A refresh failure does not affect the running program.
        }
    }

    /**
     * Links compiled classes into a native executable. Linking runs to
     * completion before the program starts, and a failure aborts the launch
     * with the compiler's own message rather than running a stale binary.
     */
    private void link(Path launcher, IronwoodInstallation installation, IronwoodProject model,
                      String mainClass, ILaunch launch) throws CoreException {
        try {
            Files.createDirectories(model.executable().getParent());
        } catch (IOException error) {
            throw new CoreException(Status.error("Could not create the output directory.", error));
        }

        // A test suite is an ordinary main class, so running tests is just a
        // launch whose main class is the suite. That only links if the testing
        // archive is on the classpath alongside the project's own output.
        StringBuilder classpath = new StringBuilder(model.classOutput().toString());
        if (model.testSourceFolder().isPresent()) {
            Path testing = installation.testingArchive().orElse(null);
            if (testing == null) {
                throw new CoreException(Status.error("This project has "
                        + IronwoodProject.TEST_SOURCE_FOLDER
                        + ", but no ironwood-testing.ironjar was found under '"
                        + installation.home() + "', so it cannot be linked."));
            }
            classpath.append(java.io.File.pathSeparator).append(testing);
        }

        String[] command = {
                launcher.toString(),
                "--link",
                "-cp", classpath.toString(),
                "--main-class", mainClass,
                "-o", model.executable().toString(),
        };

        Process process = DebugPlugin.exec(command, model.location().toFile());

        // Registering the process routes the compiler's output to the Console.
        // Its streams belong to that proxy from here on, so this method must not
        // read them itself, and the exit status has to come from the raw process:
        // IProcess.getExitValue throws until the debug framework has noticed the
        // termination, which is later than waitFor returning.
        DebugPlugin.newProcess(launch, process, "Ironwood link " + mainClass);

        int status;
        try {
            status = process.waitFor();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new CoreException(Status.error("Linking was interrupted.", error));
        }

        if (status != 0) {
            throw new CoreException(Status.error(
                    "Linking failed with status " + status + ". See the Console for details."));
        }
    }

    private void run(IronwoodProject model, ILaunchConfiguration configuration, ILaunch launch)
            throws CoreException {
        Path executable = model.executable();
        if (!Files.isExecutable(executable)) {
            throw new CoreException(Status.error(
                    "Linking produced no executable at '" + executable + "'."));
        }

        List<String> command = new ArrayList<>();
        command.add(executable.toString());
        command.addAll(splitArguments(configuration.getAttribute(ATTRIBUTE_ARGUMENTS, "")));

        Process process = DebugPlugin.exec(command.toArray(String[]::new),
                model.location().toFile());
        DebugPlugin.newProcess(launch, process, executable.getFileName().toString());
    }

    /**
     * Splits a program-arguments string on whitespace, honouring double quotes
     * so that an argument containing spaces can be written the usual way.
     */
    static List<String> splitArguments(String arguments) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        boolean started = false;
        for (int index = 0; index < arguments.length(); index++) {
            char character = arguments.charAt(index);
            if (character == '"') {
                quoted = !quoted;
                started = true;
                continue;
            }
            if (!quoted && Character.isWhitespace(character)) {
                if (started) {
                    parts.add(current.toString());
                    current.setLength(0);
                    started = false;
                }
                continue;
            }
            current.append(character);
            started = true;
        }
        if (started) {
            parts.add(current.toString());
        }
        return parts;
    }
}
