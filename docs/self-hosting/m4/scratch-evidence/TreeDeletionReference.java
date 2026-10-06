// SPDX-License-Identifier: MIT OR Apache-2.0

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Java baseline for integration-tests/cases/compiler_tree_deletion.iron.
 * "quiet" calls NativeBackend.deleteTree itself (by reflection on the
 * compiler classes on the class path); "propagate" is the cleanup in
 * BridgeNativeSupport.deliver's finally block, transcribed unchanged. Prints
 * "ok" or "failed".
 */
public final class TreeDeletionReference {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[1]);
        if (args[0].equals("quiet")) {
            Method deleteTree = Class.forName("ironwood.compiler.backend.NativeBackend")
                    .getDeclaredMethod("deleteTree", Path.class);
            deleteTree.setAccessible(true);
            try {
                deleteTree.invoke(null, root);
                System.out.println("ok");
            } catch (java.lang.reflect.InvocationTargetException escaped) {
                System.out.println("failed");
            }
            return;
        }
        try {
            if (Files.exists(root)) {
                try (var paths = Files.walk(root)) {
                    for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
                }
            }
            System.out.println("ok");
        } catch (IOException | java.io.UncheckedIOException failure) {
            System.out.println("failed");
        }
    }
}
