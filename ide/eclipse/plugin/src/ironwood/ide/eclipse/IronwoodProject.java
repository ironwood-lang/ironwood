// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.ide.eclipse;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The layout of an Ironwood project, by convention rather than configuration.
 *
 * <p>Ironwood projects already follow the Java source-organization model, and
 * the repository's own examples and projects all use {@code src/main/ironwood}
 * with output under {@code target}. Reading that layout directly means a
 * project imported from disk builds without anyone filling in a settings page
 * first.
 */
public final class IronwoodProject {

    public static final String SOURCE_FOLDER = "src/main/ironwood";
    public static final String TEST_SOURCE_FOLDER = "src/test/ironwood";
    public static final String CLASS_OUTPUT = "target/classes";
    public static final String EXECUTABLE_OUTPUT = "target";

    private final IProject project;

    public IronwoodProject(IProject project) {
        this.project = project;
    }

    public IProject project() {
        return project;
    }

    /**
     * The source root. Projects laid out the Maven way keep sources under
     * {@code src/main/ironwood}; a flatter project keeps them at its root.
     */
    public Path sourceFolder() {
        Path conventional = location().resolve(SOURCE_FOLDER);
        return java.nio.file.Files.isDirectory(conventional) ? conventional : location();
    }

    /** The test source root, when the project has one. */
    public Optional<Path> testSourceFolder() {
        Path conventional = location().resolve(TEST_SOURCE_FOLDER);
        return java.nio.file.Files.isDirectory(conventional)
                ? Optional.of(conventional) : Optional.empty();
    }

    /**
     * Every source root, in the order the compiler should search them. Tests
     * reference production types, so both roots go on one source path rather
     * than being compiled separately.
     */
    public List<Path> sourceFolders() {
        List<Path> folders = new ArrayList<>();
        folders.add(sourceFolder());
        testSourceFolder().ifPresent(folders::add);
        return folders;
    }

    public Path classOutput() {
        return location().resolve(CLASS_OUTPUT);
    }

    public Path executable() {
        return location().resolve(EXECUTABLE_OUTPUT).resolve(project.getName());
    }

    public Path location() {
        return project.getLocation().toFile().toPath();
    }

    /** The source roots rendered as one path-separated compiler argument. */
    public String sourcePathArgument() {
        return sourceFolders().stream()
                .map(Path::toString)
                .collect(Collectors.joining(java.io.File.pathSeparator));
    }

    /**
     * Every Ironwood source file in the project, in a stable order so that a
     * rebuild passes the compiler the same command line as the build before it.
     */
    public List<Path> sourceFiles() throws CoreException {
        List<Path> sources = new ArrayList<>();
        project.accept(resource -> {
            if (resource.getType() == IResource.FILE
                    && "iron".equals(resource.getFileExtension())) {
                // Output directories can hold copies that would collide with
                // the real declarations.
                if (!isUnderOutput(resource)) {
                    sources.add(resource.getLocation().toFile().toPath());
                }
            }
            return true;
        });
        sources.sort(Path::compareTo);
        return sources;
    }

    private boolean isUnderOutput(IResource resource) {
        String path = resource.getProjectRelativePath().toString();
        return path.startsWith(EXECUTABLE_OUTPUT + "/") || path.startsWith("build/");
    }

    /**
     * Maps an absolute path reported by the compiler back to the workspace
     * resource it came from, so a diagnostic can become a marker on the right
     * file. Returns null for a path outside this project, such as a standard
     * library source.
     */
    public IResource resourceFor(String absolutePath) {
        Path file = Path.of(absolutePath).toAbsolutePath().normalize();
        Path root = location().toAbsolutePath().normalize();
        if (!file.startsWith(root)) {
            return null;
        }
        return project.findMember(root.relativize(file).toString());
    }
}
