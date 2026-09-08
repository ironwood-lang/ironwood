// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.ide.eclipse;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.handlers.HandlerUtil;

import java.util.Iterator;

/**
 * Converts an existing project into an Ironwood project.
 *
 * <p>A project checked out from version control or created by another wizard
 * arrives without the Ironwood nature, so this is the one step that starts it
 * building. It appears under Configure in a project's context menu.
 */
public final class AddIronwoodNatureHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        if (!(HandlerUtil.getCurrentSelection(event) instanceof IStructuredSelection selection)) {
            return null;
        }
        for (Iterator<?> elements = selection.iterator(); elements.hasNext();) {
            IProject project = toProject(elements.next());
            if (project == null) {
                continue;
            }
            try {
                IronwoodNature.addTo(project);
                // Build straight away so the reader sees problems, or their
                // absence, without having to ask for a build.
                project.build(IncrementalProjectBuilder.FULL_BUILD, null);
            } catch (CoreException error) {
                throw new ExecutionException(
                        "Could not add the Ironwood nature to " + project.getName(), error);
            }
        }
        return null;
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
}
