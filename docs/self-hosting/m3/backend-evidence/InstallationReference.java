// SPDX-License-Identifier: MIT OR Apache-2.0

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Java reference for integration-tests/cases/compiler_installation.iron. Run
 * it with a compiler jar or class directory as the class path and the
 * scenario's working directory and environment: it prints the baseline's own
 * RuntimeLibrary.discover result and StandardLibrary.discover's source roots
 * (read by reflection), whose code-source location is that class path entry.
 */
public final class InstallationReference {
    private InstallationReference() { }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        StringBuilder out = new StringBuilder();
        Class<?> runtime = Class.forName("ironwood.compiler.backend.RuntimeLibrary");
        Object discovery = runtime.getMethod("discover").invoke(null);
        Optional<Path> source = (Optional<Path>) discovery.getClass().getMethod("source").invoke(discovery);
        if (source.isPresent()) {
            out.append("runtime ").append(source.get()).append('\n');
        } else {
            out.append("error ").append(discovery.getClass().getMethod("error").invoke(discovery)).append('\n');
        }
        Class<?> library = Class.forName("ironwood.compiler.StandardLibrary");
        Method discover = library.getDeclaredMethod("discover");
        discover.setAccessible(true);
        Object standard = discover.invoke(null);
        Field roots = library.getDeclaredField("sourceRoots");
        roots.setAccessible(true);
        List<Path> sourceRoots = (List<Path>) roots.get(standard);
        out.append("roots ").append(sourceRoots.size()).append('\n');
        for (Path root : sourceRoots) out.append("root ").append(root).append('\n');
        System.out.print(out);
    }
}
