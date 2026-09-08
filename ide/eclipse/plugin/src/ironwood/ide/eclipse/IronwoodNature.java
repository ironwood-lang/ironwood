// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.ide.eclipse;

import org.eclipse.core.resources.ICommand;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IProjectNature;
import org.eclipse.core.runtime.CoreException;

import java.util.ArrayList;
import java.util.List;

/**
 * Marks a project as Ironwood and attaches the Ironwood builder.
 *
 * <p>The nature is what makes a project build, so adding it is the single step
 * that turns a folder of {@code .iron} files into something Eclipse compiles
 * and reports problems for.
 */
public final class IronwoodNature implements IProjectNature {

    public static final String ID = "ironwood.ide.eclipse.nature";

    private IProject project;

    @Override
    public void configure() throws CoreException {
        IProjectDescription description = project.getDescription();
        List<ICommand> builders = new ArrayList<>(List.of(description.getBuildSpec()));
        boolean present = builders.stream()
                .anyMatch(command -> IronwoodBuilder.ID.equals(command.getBuilderName()));
        if (present) {
            return;
        }
        ICommand build = description.newCommand();
        build.setBuilderName(IronwoodBuilder.ID);
        builders.add(build);
        description.setBuildSpec(builders.toArray(ICommand[]::new));
        project.setDescription(description, null);
    }

    @Override
    public void deconfigure() throws CoreException {
        IProjectDescription description = project.getDescription();
        List<ICommand> builders = new ArrayList<>(List.of(description.getBuildSpec()));
        builders.removeIf(command -> IronwoodBuilder.ID.equals(command.getBuilderName()));
        description.setBuildSpec(builders.toArray(ICommand[]::new));
        project.setDescription(description, null);
    }

    @Override
    public IProject getProject() {
        return project;
    }

    @Override
    public void setProject(IProject project) {
        this.project = project;
    }

    /** Adds the nature to a project that does not have it yet. */
    public static void addTo(IProject project) throws CoreException {
        if (project.hasNature(ID)) {
            return;
        }
        IProjectDescription description = project.getDescription();
        List<String> natures = new ArrayList<>(List.of(description.getNatureIds()));
        natures.add(ID);
        description.setNatureIds(natures.toArray(String[]::new));
        project.setDescription(description, null);
    }
}
