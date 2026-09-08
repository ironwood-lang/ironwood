// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.ide.eclipse;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.ui.DebugUITools;
import org.eclipse.debug.ui.ILaunchShortcut;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.dialogs.ElementListSelectionDialog;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adds "Run As &gt; Ironwood Application" for an Ironwood source file or for a
 * whole Ironwood project.
 *
 * <p>Offering it on the project matters as much as on a file: a reader who wants
 * to run a program does not necessarily have its source selected, and Ironwood
 * has two launchable shapes worth finding, a class declaring {@code main} and a
 * {@code TestSuite}, whose entry point the compiler generates rather than the
 * source declaring it.
 *
 * <p>An existing configuration for the same main class is reused, so running the
 * same program repeatedly does not leave a trail of near-identical entries.
 */
public final class IronwoodLaunchShortcut implements ILaunchShortcut {

    /**
     * Matches a package declaration at the start of a line. Ironwood requires
     * the declaration before any type, so the first match is the real one.
     */
    private static final Pattern PACKAGE_DECLARATION =
            Pattern.compile("^\\s*package\\s+([A-Za-z_$][A-Za-z0-9_$.]*)\\s*;");

    /** A source-declared entry point. */
    private static final Pattern MAIN_DECLARATION =
            Pattern.compile("\\bstatic\\s+(?:int|void)\\s+main\\s*\\(");

    /** A suite, whose entry point the compiler generates from {@code @Test}. */
    private static final Pattern TEST_SUITE =
            Pattern.compile("\\bextends\\s+TestSuite\\b");

    @Override
    public void launch(ISelection selection, String mode) {
        if (!(selection instanceof IStructuredSelection structured)) {
            return;
        }
        Object element = structured.getFirstElement();
        if (element instanceof IFile file) {
            launchFile(file, mode);
            return;
        }
        IProject project = toProject(element);
        if (project != null) {
            launchProject(project, mode);
        }
    }

    @Override
    public void launch(IEditorPart editor, String mode) {
        if (editor.getEditorInput() instanceof IFileEditorInput input) {
            launchFile(input.getFile(), mode);
        }
    }

    private static IProject toProject(Object element) {
        if (element instanceof IProject project) {
            return project;
        }
        if (element instanceof IResource resource) {
            return resource.getProject();
        }
        if (element instanceof org.eclipse.core.runtime.IAdaptable adaptable) {
            IProject adapted = adaptable.getAdapter(IProject.class);
            if (adapted != null) {
                return adapted;
            }
            IResource resource = adaptable.getAdapter(IResource.class);
            return resource == null ? null : resource.getProject();
        }
        return null;
    }

    /**
     * Launches the project's only runnable type, or asks which one when it has
     * several. Reporting nothing runnable is better than silently doing nothing.
     */
    private void launchProject(IProject project, String mode) {
        List<IFile> candidates;
        try {
            candidates = runnableFiles(project);
        } catch (CoreException error) {
            IronwoodBuilder.log("Could not scan " + project.getName(), error);
            return;
        }

        if (candidates.isEmpty()) {
            MessageDialog.openInformation(shell(), "Nothing to run",
                    "No class in '" + project.getName() + "' declares main, and no type"
                    + " extends TestSuite.");
            return;
        }
        if (candidates.size() == 1) {
            launchFile(candidates.getFirst(), mode);
            return;
        }
        chooseFile(candidates).ifPresent(file -> launchFile(file, mode));
    }

    private Optional<IFile> chooseFile(List<IFile> candidates) {
        ElementListSelectionDialog dialog =
                new ElementListSelectionDialog(shell(), new LabelProvider() {
                    @Override
                    public String getText(Object element) {
                        IFile file = (IFile) element;
                        try {
                            return mainClassOf(file);
                        } catch (CoreException error) {
                            return file.getName();
                        }
                    }
                });
        dialog.setTitle("Run Ironwood Application");
        dialog.setMessage("Select the class to run:");
        dialog.setElements(candidates.toArray());
        if (dialog.open() != Window.OK) {
            return Optional.empty();
        }
        Object[] chosen = dialog.getResult();
        return chosen == null || chosen.length == 0
                ? Optional.empty() : Optional.of((IFile) chosen[0]);
    }

    /** Every source file in the project that declares something launchable. */
    private static List<IFile> runnableFiles(IProject project) throws CoreException {
        List<IFile> candidates = new ArrayList<>();
        project.accept(resource -> {
            if (resource.getType() != IResource.FILE
                    || !"iron".equals(resource.getFileExtension())) {
                return true;
            }
            IFile file = (IFile) resource;
            String text = read(file);
            if (text != null
                    && (MAIN_DECLARATION.matcher(text).find()
                    || TEST_SUITE.matcher(text).find())) {
                candidates.add(file);
            }
            return true;
        });
        candidates.sort((left, right) -> left.getName().compareTo(right.getName()));
        return candidates;
    }

    private void launchFile(IFile file, String mode) {
        if (!"iron".equals(file.getFileExtension())) {
            return;
        }
        try {
            ILaunchConfiguration configuration = findOrCreate(file);
            if (configuration != null) {
                DebugUITools.launch(configuration, mode);
            }
        } catch (CoreException error) {
            IronwoodBuilder.log("Could not launch " + file.getName(), error);
        }
    }

    private ILaunchConfiguration findOrCreate(IFile file) throws CoreException {
        String mainClass = mainClassOf(file);
        String projectName = file.getProject().getName();

        ILaunchManager manager = DebugPlugin.getDefault().getLaunchManager();
        ILaunchConfigurationType type =
                manager.getLaunchConfigurationType(IronwoodLaunchDelegate.TYPE_ID);

        Optional<ILaunchConfiguration> existing =
                Arrays.stream(manager.getLaunchConfigurations(type))
                        .filter(candidate -> matches(candidate, projectName, mainClass))
                        .findFirst();
        if (existing.isPresent()) {
            return existing.get();
        }

        ILaunchConfigurationWorkingCopy copy = type.newInstance(null,
                manager.generateLaunchConfigurationName(simpleName(mainClass)));
        copy.setAttribute(IronwoodLaunchDelegate.ATTRIBUTE_PROJECT, projectName);
        copy.setAttribute(IronwoodLaunchDelegate.ATTRIBUTE_MAIN_CLASS, mainClass);
        copy.setMappedResources(new IResource[] {file});
        return copy.doSave();
    }

    private static boolean matches(ILaunchConfiguration configuration, String projectName,
                                   String mainClass) {
        try {
            return projectName.equals(configuration.getAttribute(
                            IronwoodLaunchDelegate.ATTRIBUTE_PROJECT, ""))
                    && mainClass.equals(configuration.getAttribute(
                            IronwoodLaunchDelegate.ATTRIBUTE_MAIN_CLASS, ""));
        } catch (CoreException error) {
            return false;
        }
    }

    /**
     * Derives the qualified type name from the file.
     *
     * <p>Ironwood keeps one public type per file named after the file, so the
     * type name is the file name and only the package has to be read.
     */
    static String mainClassOf(IFile file) throws CoreException {
        String typeName = file.getName().substring(0, file.getName().length() - ".iron".length());
        String packageName = readPackage(file);
        return packageName.isEmpty() ? typeName : packageName + "." + typeName;
    }

    private static String readPackage(IFile file) throws CoreException {
        String text = read(file);
        if (text == null) {
            return "";
        }
        for (String line : text.split("\\R")) {
            Matcher matcher = PACKAGE_DECLARATION.matcher(line);
            if (matcher.find()) {
                return matcher.group(1);
            }
            // Only comments, whitespace, and the package declaration can precede
            // the first type, so a type keyword means there was no package.
            if (line.stripLeading().startsWith("public ")
                    || line.stripLeading().startsWith("class ")) {
                return "";
            }
        }
        return "";
    }

    private static String read(IFile file) {
        try (InputStream stream = file.getContents()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException | CoreException error) {
            return null;
        }
    }

    private static String simpleName(String qualifiedName) {
        int lastDot = qualifiedName.lastIndexOf('.');
        return lastDot < 0 ? qualifiedName : qualifiedName.substring(lastDot + 1);
    }

    private static org.eclipse.swt.widgets.Shell shell() {
        return Display.getDefault().getActiveShell();
    }
}
