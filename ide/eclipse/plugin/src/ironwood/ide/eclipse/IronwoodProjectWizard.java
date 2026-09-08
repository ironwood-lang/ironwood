// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.ide.eclipse;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.operation.IRunnableWithProgress;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.wizard.Wizard;
import org.eclipse.ui.INewWizard;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.dialogs.WizardNewProjectCreationPage;

import java.io.ByteArrayInputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;

/**
 * File &gt; New &gt; Ironwood Project.
 *
 * <p>Creates a project that already builds: the Ironwood nature, the source and
 * test folders the builder expects, and a working sample with a test suite. The
 * alternative is a reader having to know the layout conventions before writing
 * a first line, which is the thing a new-project wizard exists to remove.
 */
public final class IronwoodProjectWizard extends Wizard implements INewWizard {

    private WizardNewProjectCreationPage projectPage;
    private IronwoodProjectSettingsPage settingsPage;
    private IWorkbench workbench;

    public IronwoodProjectWizard() {
        setWindowTitle("New Ironwood Project");
        setNeedsProgressMonitor(true);
    }

    @Override
    public void init(IWorkbench workbench, IStructuredSelection selection) {
        this.workbench = workbench;
    }

    @Override
    public void addPages() {
        projectPage = new WizardNewProjectCreationPage("ironwoodNewProject");
        projectPage.setTitle("Ironwood Project");
        projectPage.setDescription("Create a new Ironwood project.");
        addPage(projectPage);

        settingsPage = new IronwoodProjectSettingsPage();
        addPage(settingsPage);
    }

    @Override
    public org.eclipse.jface.wizard.IWizardPage getNextPage(
            org.eclipse.jface.wizard.IWizardPage page) {
        // Carrying the project name forward keeps the package suggestion in step
        // with what was just typed.
        if (page == projectPage) {
            settingsPage.suggestPackageFor(projectPage.getProjectName());
        }
        return super.getNextPage(page);
    }

    @Override
    public boolean performFinish() {
        String projectName = projectPage.getProjectName();
        IPath location = projectPage.useDefaults() ? null : projectPage.getLocationPath();
        String packageName = settingsPage.packageName();
        boolean sample = settingsPage.createSampleSource();

        IRunnableWithProgress operation = monitor -> {
            try {
                create(projectName, location, packageName, sample, monitor);
            } catch (CoreException error) {
                throw new InvocationTargetException(error);
            }
        };

        try {
            getContainer().run(true, false, operation);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            MessageDialog.openError(getShell(), "Could not create the project",
                    cause.getMessage());
            return false;
        }
        return true;
    }

    private void create(String projectName, IPath location, String packageName,
                        boolean sample, IProgressMonitor monitor) throws CoreException {
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        IProject project = workspace.getRoot().getProject(projectName);

        IProjectDescription description = workspace.newProjectDescription(projectName);
        if (location != null) {
            description.setLocation(location);
        }
        project.create(description, monitor);
        project.open(monitor);

        IronwoodNature.addTo(project);

        IFolder mainFolder = createFolders(project,
                IronwoodProject.SOURCE_FOLDER + "/" + packageName.replace('.', '/'), monitor);
        IFolder testFolder = createFolders(project,
                IronwoodProject.TEST_SOURCE_FOLDER + "/" + packageName.replace('.', '/'),
                monitor);

        if (sample) {
            String typeName = IronwoodProjectTemplates.typeNameFor(projectName);
            IFile main = write(mainFolder, typeName + ".iron",
                    IronwoodProjectTemplates.mainSource(packageName, typeName), monitor);
            write(testFolder, typeName + "Tests.iron",
                    IronwoodProjectTemplates.testSource(packageName, typeName), monitor);
            openInEditor(main);
        }

        // Build straight away so the project's state is visible without anyone
        // asking for a build first.
        project.build(org.eclipse.core.resources.IncrementalProjectBuilder.FULL_BUILD, monitor);
    }

    /** Creates a folder and every missing parent above it. */
    private IFolder createFolders(IProject project, String path, IProgressMonitor monitor)
            throws CoreException {
        IFolder folder = null;
        StringBuilder built = new StringBuilder();
        for (String segment : path.split("/")) {
            if (segment.isEmpty()) {
                continue;
            }
            if (!built.isEmpty()) {
                built.append('/');
            }
            built.append(segment);
            folder = project.getFolder(built.toString());
            if (!folder.exists()) {
                folder.create(false, true, monitor);
            }
        }
        if (folder == null) {
            throw new CoreException(Status.error("Could not create '" + path + "'."));
        }
        return folder;
    }

    private IFile write(IFolder folder, String name, String content, IProgressMonitor monitor)
            throws CoreException {
        IFile file = folder.getFile(name);
        file.create(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)),
                false, monitor);
        return file;
    }

    private void openInEditor(IFile file) {
        if (workbench == null) {
            return;
        }
        workbench.getDisplay().asyncExec(() -> {
            IWorkbenchPage page = workbench.getActiveWorkbenchWindow().getActivePage();
            if (page == null) {
                return;
            }
            try {
                IDE.openEditor(page, file);
            } catch (CoreException error) {
                IronwoodBuilder.log("Could not open " + file.getName(), error);
            }
        });
    }
}
