// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.ide.eclipse;

import org.eclipse.jface.preference.DirectoryFieldEditor;
import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.jface.preference.IPersistentPreferenceStore;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;
import org.eclipse.ui.preferences.ScopedPreferenceStore;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.preferences.InstanceScope;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Preferences > Ironwood.
 *
 * <p>The only setting so far is where Ironwood is installed, which the language
 * server needs in order to load the compiler and, through it, the standard
 * library. The field validates eagerly so that a wrong path is reported here
 * rather than as an unexplained absence of diagnostics later.
 */
public final class IronwoodPreferencePage extends FieldEditorPreferencePage
        implements IWorkbenchPreferencePage {

    /** The value on entry, so a change can be detected on the way out. */
    private String originalHome = "";

    public IronwoodPreferencePage() {
        super(GRID);
    }

    @Override
    public void init(IWorkbench workbench) {
        setPreferenceStore(new ScopedPreferenceStore(InstanceScope.INSTANCE,
                IronwoodPreferences.NODE));
        setDescription("Ironwood editor support runs the Ironwood compiler to report errors."
                + " Point this at an extracted IDK or an Ironwood source checkout.");
        originalHome = IronwoodPreferences.ironwoodHome();
    }

    @Override
    protected void createFieldEditors() {
        DirectoryFieldEditor home = new DirectoryFieldEditor(
                IronwoodPreferences.IRONWOOD_HOME, "Ironwood &home:", getFieldEditorParent()) {

            @Override
            protected boolean doCheckState() {
                String value = getStringValue().trim();
                if (value.isEmpty()) {
                    // An empty setting falls back to the environment variable,
                    // which is a legitimate configuration rather than an error.
                    setErrorMessage(null);
                    return true;
                }
                Path candidate = Path.of(value);
                if (!Files.isDirectory(candidate)) {
                    setErrorMessage("'" + value + "' is not a directory.");
                    return false;
                }
                if (!Files.isRegularFile(candidate.resolve("lib/ironwoodc.jar"))
                        && !Files.isRegularFile(
                                candidate.resolve("compiler/build/ironwoodc.jar"))) {
                    setErrorMessage("No Ironwood compiler jar under '" + value
                            + "'. Expected lib/ironwoodc.jar or compiler/build/ironwoodc.jar.");
                    return false;
                }
                setErrorMessage(null);
                return true;
            }
        };
        home.setEmptyStringAllowed(true);
        addField(home);
    }

    @Override
    public boolean performOk() {
        if (!super.performOk()) {
            return false;
        }
        // Field editor pages do not flush a scoped store on their own, and an
        // unsaved setting would leave the language server unconfigured after a
        // restart.
        if (getPreferenceStore() instanceof IPersistentPreferenceStore store) {
            try {
                store.save();
            } catch (IOException error) {
                setErrorMessage("Could not save the Ironwood preferences: " + error.getMessage());
                return false;
            }
        }

        // Nothing else reacts to this setting, so without an explicit rebuild a
        // project keeps the errors from the last build, including the marker
        // that says the home was never set. That reads as the setting having no
        // effect, which is exactly the wrong impression.
        String updatedHome = IronwoodPreferences.ironwoodHome();
        if (!updatedHome.equals(originalHome)) {
            originalHome = updatedHome;
            rebuildIronwoodProjects();
        }
        return true;
    }

    /** Rebuilds every Ironwood project so their problems reflect the new setting. */
    private static void rebuildIronwoodProjects() {
        Job job = new Job("Rebuilding Ironwood projects") {

            @Override
            protected IStatus run(IProgressMonitor monitor) {
                for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
                    try {
                        if (project.isAccessible() && project.hasNature(IronwoodNature.ID)) {
                            project.build(IncrementalProjectBuilder.FULL_BUILD, monitor);
                        }
                    } catch (CoreException error) {
                        return Status.error("Could not rebuild " + project.getName(), error);
                    }
                }
                return Status.OK_STATUS;
            }
        };
        job.setUser(true);
        job.schedule();
    }
}
